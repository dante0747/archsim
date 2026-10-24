# Simulation model

This page describes exactly what the ArchSim engine simulates, how metrics are defined, and
what it leaves out. If the code and this page disagree, that is a bug; please report it.

> **Results are estimates, not production guarantees.** The engine faithfully simulates the
> *model you drew* with the *assumptions you entered* (service times, hit ratios, latencies,
> limits). Real systems have effects the model omits, and your assumptions may be wrong.
> Without calibration against real telemetry, treat absolute numbers as illustrative and
> focus on how scenarios differ from each other under the same assumptions. Calibration is
> on the [roadmap](roadmap.md).

## Clock, units and randomness

- **Simulated time is in milliseconds** (`env.now` in SimPy). Workload durations are given in
  seconds and converted. Rates are requests per second.
- **Distributions** (`distributions.py`), all in ms: `constant {value}`,
  `exponential {mean}`, `uniform {min, max}`, `lognormal {median, p95}` with
  `sigma = ln(p95 / median) / 1.645`. Every sample is capped at 600,000 ms so a heavy tail
  cannot stall a run.
- **Seeded RNG streams.** Each source of randomness has its own `random.Random` seeded from
  `SHA-256("{seed}:{stream name}")` (not Python's `hash()`, which is randomised per process).
  Streams: `arrivals`, `arrivals:thinning`, `mix` (read/write), `keys` (cache keyspace),
  `edge:{id}:latency`, `node:{id}:service`, `node:{id}:hits`, `node:{id}:lb`,
  `node:{id}:overhead`.

## Arrivals (open loop)

The single traffic source generates requests during `[0, durationS)`:

- `poisson` (default): exponential inter-arrival times. Non-constant patterns use thinning
  (Lewis and Shedler): candidates are generated at the peak rate and accepted with
  probability `rate(t) / peak`.
- `deterministic`: evenly spaced arrivals at `1000 / rate(t)` ms. While the rate is 0 the
  generator advances in 10 ms steps.
- Patterns: `constant` (`rps`), `ramp` (linear from `startRps` to `endRps` over the run;
  `rps` is ignored), `burst` (`rps` baseline, `burstRps` during
  `[startS, startS + durationS)`).
- Each request is a `read` with probability `readRatio`, otherwise a `write`.
- If any component uses keys (a TTL cache), each request also gets a key rank sampled from a
  Zipf distribution over `keyCount` keys with exponent `keySkew` (probability proportional to
  `1 / rank^keySkew`).

Arrivals are **open-loop**: clients keep sending at the configured rate however slow the system
gets. Real users often back off; closed-loop users are not modeled.

## Calls across links

Every edge is a call from a caller to a callee:

1. One-way latency is sampled from the link's `latencyMs` and applied to the request.
2. The callee handles the request (possibly calling its own dependencies).
3. One-way latency is sampled again for the response.

If the caller has a **timeout** (`timeoutMs` on the link, or the client timeout on the
traffic source), it stops waiting when the timeout fires and treats the attempt as failed.
**The callee keeps working**: its queue slot, worker and downstream calls are still consumed.
This matches reality and is why overload with long queues produces "wasted work" (below).

**Retries** (`maxAttempts` > 1, capped at 5) happen on any failed attempt (rejection or
timeout), after a backoff of `retryBackoffMs * 2^(attempt - 1)`. They are bounded, so a run
cannot loop forever, but they add load exactly when the system is struggling, and the report
shows the amplification (`retryAmplification` = attempts / requests). Timeout and retry
settings on the link leaving the traffic source are ignored with a warning; the traffic source
has its own `timeoutMs`, `maxAttempts` and `retryBackoffMs`.

A failure propagates upward unchanged: if PostgreSQL rejects a query, the API request fails
with origin "PostgreSQL, queue_timeout", and the client records an error.

## Components

### Worker pools (shared building block)

Services, caches and databases execute work on a `WorkerPool`: `N` slots (workers,
connections, executors) and a FIFO queue.

```
arrive ─┬─ all slots busy and queue full ──────────────▶ reject "queue_full"
        └─ wait in FIFO queue ─┬─ waited > queueTimeoutMs ─▶ reject "queue_timeout"
                               └─ slot granted ─▶ hold slot ─▶ release ─▶ respond
```

`queueCapacity` = 0 means no waiting at all. `queueTimeoutMs` empty means wait indefinitely.

### Traffic source (`traffic_source`)

Submits each generated request to its single outgoing link. Each attempt waits at most
`timeoutMs` (the timeout is **per attempt**, not a total deadline). On failure it retries up
to `maxAttempts` with exponential backoff. It records the end-to-end outcome: success, error
(any rejection or dependency timeout downstream) or client timeout. Each attempt is a separate
request object so work done for an abandoned attempt can be detected.

### Load balancer (`load_balancer`)

- Accepts up to `maxConnections` concurrent requests; beyond that it rejects
  (`max_connections`).
- Adds `overheadMs` per request.
- Candidates are **all replicas of all linked targets whose link carries the request type**.
  `round_robin` cycles through them; `random` picks uniformly; `least_connections` picks the
  candidate with the fewest requests currently assigned (for a service replica this counts
  requests queued or executing on that replica).
- A retry (the chosen link's `maxAttempts` > 1) avoids the instance that just failed when
  another candidate exists.
- Utilization for a load balancer is concurrent requests / `maxConnections`.

### Service (`service`)

- `replicas` instances, each with its own pool of `workersPerReplica` workers and its own
  queue (`queueCapacity`, `queueTimeoutMs`). Without a load balancer in front, requests are
  spread across replicas round-robin.
- On a worker: sample `serviceTimeMs` (the handler's own CPU time), then call each outgoing
  link that carries the request type, **one after another**, ordered by the link's `order`
  then id. The first failing dependency stops the chain and fails the request.
- `blockingDownstream: true` (default, thread-per-request): the worker stays occupied while
  waiting for dependencies. `false` (async/event loop): the worker is released before the
  downstream calls, so slow dependencies do not exhaust workers.
- The report separates **utilization** (worker occupancy, including time blocked on
  dependencies) from **CPU utilization** (time spent in `serviceTimeMs`). A big gap means the
  service is waiting on something else.

### Cache (`cache`), cache-aside

- Every command (lookup, invalidation, repopulation) runs on a pool of `concurrency`
  executors (default 1, like Redis) taking `serviceTimeMs`, with its own queue.
- **Writes** perform one command and invalidate the key (TTL model). They never use the
  `miss` port. In the starter example the API writes to PostgreSQL first and then invalidates.
- **Reads** perform one lookup command and decide hit or miss:
  - `fixed_ratio`: hit with probability `hitRatio` (the simplest assumption);
  - `ttl_keyspace`: hit if the request's key was filled less than `ttlSeconds` ago. The hit
    rate is *derived* from TTL, keyspace size and skew, and it includes **cold start** (the
    cache starts empty, so early buckets show lower hit rates). Capacity is unbounded; there is
    no eviction.
- **Misses** continue through the `miss` port (reads only, at most one link) to the data
  store. The miss path's latency counts toward the caller's request. The cache executor is
  *not* held while the data store works.
- If `populateOnMiss` is on and the miss succeeded, one more command writes the value back
  (and, in the TTL model, sets the key's expiry). If that command is rejected the read still
  succeeds; the key just is not cached.

### Database (`database`)

`maxConnections` concurrent queries, a wait queue (`queueCapacity`) and an acquire timeout
(`queueTimeoutMs`). Reads take `readServiceTimeMs`, writes `writeServiceTimeMs`. Nothing else
(locks, I/O, buffer cache) is modeled.

## Validation rules

Documents are validated before simulation (`validation.py`); errors block the run and carry
a stable code, the node or edge involved and a hint. Highlights:

- unique ids; known component types; config values in range (`node.config_invalid` with the
  field path);
- links must start at an output port and end at an input port; request types must be allowed
  by both ports (for example the cache `miss` port only emits reads); no self-loops or
  duplicate links;
- port arity (the traffic source needs exactly one link; a load balancer at least one; the
  cache miss port at most one);
- exactly one traffic source;
- **no cycles** (synchronous calls would recurse forever), reported with the loop path;
- **request-type routing**: request types are propagated from the source through the graph;
  a load balancer receiving writes with no link carrying writes is an error; so is a workload
  generating writes when the source link only carries reads; a cache that can miss with no
  `miss` link is an error;
- warnings (non-blocking): unreachable nodes, links that never carry traffic, a 100%-hit cache
  with a miss link, a TTL cache that never populates, zero offered load.

## Measurement window and metric definitions

- **Warm-up** `[0, warmupS)` is simulated but excluded from summary statistics.
- **Measurement window** `[warmupS, durationS)`; its length is `measuredS`.
- **Drain:** after arrivals stop, the simulation continues so in-flight requests can finish.
  `drainS` empty means automatic: the longest a client can wait
  (`timeoutMs * maxAttempts` plus backoffs) plus 1 s, capped at 600 s. The run stops early as
  soon as no request is in flight anywhere (pending stale timers do not count).

Client-side (end-to-end) summary:

| Metric | Definition |
| --- | --- |
| `arrived`, `succeeded`, `failed`, `timedOut`, `outstanding` | The **measured cohort**: requests that *arrived* inside the window, by final outcome. |
| `offeredRps` | Measured-cohort arrivals / `measuredS`. |
| `throughputRps` | Successful completions whose *completion time* falls inside the window / `measuredS`. Because it is time-based it can be marginally above `offeredRps`. |
| `successRate`, `errorRate`, `timeoutRate` | Outcome counts of the measured cohort / `arrived`. `null` when nothing arrived. |
| `latencyMs` (`mean`, `p50`, `p90`, `p95`, `p99`, `max`) | End-to-end latency of **successful** measured-cohort requests, including retries and backoff. Failed and timed-out requests are counted in the rates, not in latency. |
| `retryAmplification` | Client attempts / client requests inside the window. |
| `failureBreakdown` | Failures of the measured cohort grouped by (status, reason, origin node, link). |

Per-node summary (events counted by the time they happen inside the window):

| Metric | Definition |
| --- | --- |
| `arrivals`, `arrivalRps` | Requests that reached the node (including retries). |
| `completed`, `failedDownstream`, `rejected{reason}` | How those requests ended at this node. |
| `utilization` | Time-weighted mean of occupied slots / slots over the window. |
| `peakUtilization` | Highest per-bucket utilization inside the window (bursts hide in averages). |
| `cpuUtilization` | Services only: time-weighted share of slots executing `serviceTimeMs`. |
| `meanQueue`, `peakQueue`, `meanInSystem` | Time-weighted queue length, peak queue, and mean requests at the node (queued + executing + waiting on dependencies). |
| `meanQueueWaitMs`, `p95QueueWaitMs`, `p99QueueWaitMs` | Waiting time of requests that were granted a slot. |
| `meanHoldMs`, `meanServiceMs` | Mean slot holding time and mean sampled service time. |
| `capacityRps` | `slots / meanHoldMs`: the rate the node could sustain at the observed holding time (derived, not assumed). |
| `serviceOnlyCapacityRps` | `slots / meanServiceMs`: the ceiling if dependencies were instant. |
| `hitRatio`, `counters` | Cache hits/misses/invalidations/populates; database reads/writes; `wasted`. |

**Time series** use fixed buckets chosen so the arrival window has at most 90 buckets (and
the whole run at most 1,200), from the sizes 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120 s.
Latency percentiles per bucket are computed from successes completing in that bucket (all
cohorts, so warm-up is visible in charts).

**Memory is bounded** by buckets and histogram bins, never by the number of requests.
Latency histograms use logarithmic bins growing by 2% (reported values are the bin's geometric
midpoint, so percentiles are accurate to about 1-2%). Means are exact.

## Accounting invariants

Checked in every report (`invariantHolds`) and in tests:

- **Client**, per cohort (warm-up, measured, total):
  `arrived == succeeded + failed + timedOut + outstanding`, where `outstanding` comes from an
  independently tracked in-flight gauge and must equal the difference.
- **Every node:** `arrivals == completed + failedDownstream + rejected + inProgress`.
- **Links**, when the run drained (no request in flight at the end): the total attempts over
  links into a node equal that node's arrivals.

## Status classification and findings

Each node gets a status from its window metrics:

| Status | Rule |
| --- | --- |
| `saturated` | utilization >= 95%, or >= 1% of arrivals rejected, or the queue kept growing (mean queue in the last quarter of the window > max(2, 1.5 x first quarter + 1); needs at least 4 window buckets) |
| `hot` | utilization >= 85% |
| `warm` | utilization >= 70% |
| `ok` / `idle` | otherwise / no arrivals |

Findings (`analysis.py`) are rule-based and cite the numbers they use:

- **Primary bottleneck:** the saturated node with the highest rejection rate and utilization,
  excluding *symptoms*. A saturated or hot node is a **symptom** when a downstream node is
  also saturated or hot and either less than 50% of its occupied worker time is its own
  processing (`cpuUtilization / utilization < 0.5`) or it is a load balancer.
- **Running hot** for hot nodes; a note when less than 70% of busy worker time is own work.
- **Throughput gap** when successful throughput is below 97% of offered load.
- **Main cause of failures** from the failure breakdown.
- **Retry amplification** above 1.05x.
- **Wasted work:** a node completed a request after the client had already timed out on that
  attempt. Reported when it is at least 1% of the node's completions (critical at 25%). This
  is the signature of overload with unbounded queues: the server stays 100% busy while goodput
  collapses. The usual fix is a queue limit or a queue timeout below the client timeout.
  (Dependency timeouts inside the graph are not counted as wasted work.)
- **Cache** hit ratio and the read rate reaching the data store.
- **Healthy** when nothing is saturated, naming the busiest component.

Comparisons (`compare.py`) diff the two *resolved input snapshots* (so they explain actual
parameter changes), compute KPI deltas with a 3% "about the same" band, report bottleneck
shifts and the largest utilization/load/hit-ratio changes, and warn when seeds or engine
versions differ.

## Determinism

- Same document + scenario + seed + engine version ⇒ identical `resultDigest` **on the same
  platform and Python version**. Across platforms, floating-point library differences could
  in principle change low-order digits; results then agree within statistical noise.
- Changing one parameter can change random samples elsewhere, because events are processed in
  a different order and streams are consumed in that order. Scenario comparisons are therefore
  not perfectly paired. Differences of a few percent may be noise; re-run with other seeds to
  check.
- Engine semantics changes bump `ENGINE_VERSION`, which every report records.

## Validation against queueing theory

`packages/engine/tests/test_reference_benchmark.py` checks the engine against exact results:

- A service with Poisson arrivals and exponential service is an M/M/c queue. The simulated
  mean response time matches the Erlang C formula within 8% for M/M/1 at 50% and 80% load and
  M/M/4 at 80% load, and utilization matches `lambda / (c * mu)` within 5%.
- The M/M/1 median response time matches `ln 2 / (mu - lambda)`.
- Little's law (`L = lambda * W`) holds per node in the starter example.
- The starter example's scenarios stay inside documented ranges and directions (fewer
  replicas ⇒ rejections and higher latency; more replicas ⇒ lower utilization).

These checks pin down the queueing core. They say nothing about whether your service-time
assumptions match your production system.

## Limits

Runs are bounded by an expected-request limit (default 2,000,000, checked before starting),
a wall-clock limit (default 300 s, checked between progress chunks), 200 nodes, 1,000 edges,
queue capacities up to 100,000 per pool, at most 5 attempts per call and 600 s of drain.
Execution speed is roughly 5,000-10,000 simulated requests per second on a laptop, depending
on graph depth.

## What is NOT modeled

- CPU, memory, disk and network bandwidth contention; garbage collection; noisy neighbours.
- Autoscaling, health checks, instance failures, deployments; failure injection in general.
- Closed-loop users with think time; client connection pools; HTTP keep-alive and TLS setup.
- Parallel fan-out calls (dependencies are sequential), async messaging, streaming.
- Database locks, MVCC contention, query plans, replication and read replicas.
- Cache memory limits and eviction, request coalescing / thundering herd, replication.
- Circuit breakers, rate limiters, priority queues, deadline propagation between services.
- Multiple traffic sources, regions and cross-region latency.
- Packet loss, bandwidth limits and payload sizes.

If one of these dominates your system, the estimates will be off in ways the model cannot
show you.
