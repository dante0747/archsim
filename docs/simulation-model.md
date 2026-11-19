# Simulation model

This page describes exactly what the ArchSim engine simulates, how metrics are defined, and
what it leaves out. If the code and this page disagree, that is a bug; please report it. The
classes it names are the Java engine's (`archsim-engine`) and design model's (`archsim-model`),
whose packages are listed in [archsim-engine/README.md](../archsim-engine/README.md#packages);
the engine replays, figure for figure, the Python engine 0.16.0 it was ported from, which the tag
`engine-0.16.0` keeps.

> **Results are estimates, not production guarantees.** The engine faithfully simulates the
> *model you drew* with the *assumptions you entered* (service times, hit ratios, latencies,
> limits). Real systems have effects the model omits, and your assumptions may be wrong.
> Without calibration against real telemetry, treat absolute numbers as illustrative and
> focus on how scenarios differ from each other under the same assumptions. Calibration is
> on the [roadmap](roadmap.md).

## Clock, units and randomness

- **Simulated time is in milliseconds** (`Simulation.now()` in the engine's event kernel, which
  keeps SimPy's order of events). Workload durations are given in seconds and converted. Rates are
  requests per second.
- **Distributions** (archsim-model's `distribution` package), all in ms: `constant {value}`,
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

## Faults (chaos experiments)

`workload.faults` schedules failures in simulated time. Each fault is active on
`[startS, startS + durationS)`:

- **outage** (node): a call that reaches the node while the fault is active is refused
  immediately, as a refused connection would be (rejection reason `outage`, counted in the
  node's arrivals and rejections). Requests already inside the node finish normally. The caller
  sees a failed attempt, so its timeout/retry settings and any fallback in the design decide
  what happens next.
- **slowdown** (node): every service or processing time the node samples while the fault is
  active (worker service time, query time, cache lookup, load balancer overhead, external API
  latency) is multiplied by `factor`. The sample is taken when the work starts.
- **latency** (edge): each one-way traversal of the link that starts while the fault is
  active gets `extraMs` added on top of the sampled latency.

Faults never draw random numbers themselves. Slowdowns and latency faults scale values that
are sampled anyway, so arrivals and every other component keep the same random draws as the
run without the fault. An outage skips the work it refuses, so later samples at that node
shift. Runs with faults are as reproducible as any other run (same document and seed, same
result).

## Components

### Measured inputs (calibration)

Calibration from Prometheus or OpenTelemetry ([calibration.md](calibration.md)) can set any
distribution to an `empirical` quantile table (sampled by interpolating a uniform draw from the
seeded generator, so runs stay reproducible) and the workload to a `trace` pattern: the
observed rate curve, sampled by thinning like the ramp and burst patterns. It also sets the CPU
share of services and databases from CPU per request, a service's memory at idle, per req/s and
per request held from its working set, and a database's hot working set (schema 1.5). Every
input carries its provenance in the document (measured, assumed, illustrative) and in reports
and `/analyze`, which add a fourth, derived kind: documented (below).

### Technology tiers and where defaults come from

Every technology profile has a tier. **Core** technologies are the ones ArchSim focuses on and
validates first: generic, Node.js, Java and Go services, PostgreSQL, Redis, Kafka, SQS-style
queues, Nginx and ALB-style load balancers, upstream Kubernetes, and the generic part of each
kind (it adds no technology behaviour, only the shared mechanisms). Every other technology is
**experimental**: its behaviour rests on documentation and illustrative numbers. The tier is
metadata: it never changes what is simulated (result digests are unchanged). It is in the
catalog (`technologyTiers`, `presets[].tier`), in `/analyze` and in reports
(`assumptions.technologies`, and a model note naming the experimental parts); the web app shows
an "experimental" badge in the parts library, the inspector, the results and the exports. No
technology has been validated against measurements yet.

A technology's "auto" default is **documented** when one of its traits links the vendor
documentation that states it (`Trait.covers`; PostgreSQL-linked traits document PostgreSQL's
values only), and a preset number when the preset's `sources` links it; everything else is
**illustrative**. The inspector says which for every Auto value. In reports
(`assumptions.userInputs`) and `/analyze` (`inputs`), an input that is left at such a documented
value, or set to the same number, is classified **documented**, with `source` the link and a
`note` saying whose default it is; `provenanceCounts` counts `measured`, `documented`, `assumed`
and `illustrative`. It is derived each time: documents still store only the three kinds of the
schema (1.4), and a record in the document (a calibration) always wins.
[assumptions.md](assumptions.md) lists every such value, preset number, assumption default and
engine constant with its basis; `./gradlew :archsim-engine:generate` writes it from the code, and
archsim-engine's `GeneratedFileTests` fails when it is out of date.

### Worker pools (shared building block)

Services, caches and databases execute work on a `WorkerPool`: `N` slots (workers,
connections, executors) and a FIFO queue.

```
arrive ─┬─ all slots busy and queue full ──────────────▶ reject "queue_full"
        └─ wait in FIFO queue ─┬─ waited > queueTimeoutMs ─▶ reject "queue_timeout"
                               └─ slot granted ─▶ hold slot ─▶ release ─▶ respond
```

`queueCapacity` = 0 means no waiting at all. `queueTimeoutMs` empty means wait indefinitely.

### Client groups (`traffic_source`, `mobile_app`, `web_client`, `api_consumer`)

A client group submits each request it is given to its single outgoing link. Each attempt waits
at most `timeoutMs` (the timeout is **per attempt**, not a total deadline). On failure it
retries up to `maxAttempts` with exponential backoff. It records the end-to-end outcome:
success, error (any rejection or dependency timeout downstream) or client timeout. Each attempt
is a separate request object so work done for an abandoned attempt can be detected.

A design can have several client groups. The workload generates one arrival stream; each
arrival goes to one client group, picked at random in proportion to `trafficShare` (a relative
weight, so 60 / 30 / 10 means 60%, 30% and 10%). A design with a single client group draws no
extra random numbers, so its results are the same as before client groups existed (apart from
the engine version and the new `summary.clients` entry). The four types share this behaviour
and differ only in defaults and labels:

| Type | Shown as | Timeout | Attempts | Backoff | Share | Link latency (one way, suggested) |
| --- | --- | --- | --- | --- | --- | --- |
| `traffic_source` | Clients | 3 s | 1 | 100 ms | 100 | link default (0.5 ms) |
| `mobile_app` | Mobile app | 10 s | 2 | 500 ms | 50 | lognormal p50 35 ms / p95 120 ms |
| `web_client` | Web browser | 15 s | 1 | 100 ms | 35 | lognormal p50 15 ms / p95 45 ms |
| `api_consumer` | API consumers | 5 s | 3 | 200 ms | 15 | lognormal p50 10 ms / p95 30 ms |

The link latency is a suggestion the editor copies onto a new link drawn from that client
(`linkDefaults` in the catalog); it is an ordinary, editable link setting, not hidden
behaviour. `technology` on the three new types (iOS, Android, React, Next.js, GraphQL...) is a
label and logo only. All these numbers are assumptions, not measurements of real networks or
SDKs.

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
- **Rate limiting** (`rateLimitRps`, optional `rateLimitBurst`): a token bucket over all
  requests; requests that find it empty are rejected immediately (`rate_limited`). Gateways
  such as Kong or an API-gateway-style preset use this to shed load at the edge.
- When targets autoscale, the candidate list follows the replicas that are currently routable.
- **Upstream timeout** (`upstreamTimeoutMs`, auto: nginx 60 s, Envoy 15 s, API Gateway 29 s,
  ALB and Kong 60 s, none for HAProxy, Traefik and generic): the balancer stops waiting for a
  target after it (or the link's timeout, if shorter) and answers 504 (`gateway_timeout`); the
  target keeps working on the abandoned request.
- **Circuit breakers** (`circuitBreakerMaxRequests`, auto: Envoy 1,024): when a target already
  has that many requests in flight from the balancer, new ones get 503 at once
  (`circuit_open`).

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
- `platform` is `vm`, `docker`, `kubernetes` or `serverless`. VM and Docker behave the same
  (the label documents the deployment); Kubernetes and VMs/containers can autoscale;
  serverless uses its own model. A Kubernetes service whose design has a Kubernetes cluster
  part runs its replicas as pods on that cluster's nodes (see below); `cluster` names the
  cluster when there are several.
- **Autoscaling** (`autoscaling: true`, HPA-like): every `scaleIntervalS` the scaler reads the
  utilization of the active replicas over the last interval (the share of busy workers, or,
  when the replica has a CPU size, the share of its CPU in use, whichever is higher, like the
  Kubernetes HPA's CPU target) and asks for
  `ceil(active × utilization / targetUtilization)` replicas, clamped to
  `[minReplicas, maxReplicas]`. New replicas become routable after `startupS` (pod start).
  Scale-down waits for three consecutive low readings, stops routing new requests to the
  newest replica and lets it drain. Utilization is measured against the capacity actually
  available at each moment; the report adds a `replicas` time series and `scaleUps` /
  `scaleDowns` counters.
- **Serverless** (`platform: serverless`): each in-flight request uses one instance. A warm
  idle instance is reused; otherwise a new instance starts and the request pays
  `coldStartMs` (empty: the runtime's typical cold start), as long as fewer than
  `concurrencyLimit` instances exist and the scale-out rate allows it (`scaleOutRate` new
  instances per 10 s, AWS Lambda's 1,000 by default); otherwise the request is rejected
  (`throttled`). Instances idle longer than `keepWarmS` are reclaimed. The report adds
  `replicas` (instances), `coldStarts` and `coldStartRps`.
- **Runtime behaviour** (by `technology`, settings with auto defaults):
  - `runtimeModel: event_loop` (Node.js): each replica runs `processesPerReplica` event loops;
    the CPU part of every request queues for a loop (`eventLoopLagMs`) and downstream calls
    never hold it. `thread_pool` (everything else) gives each worker one request.
  - Garbage collection: every `gcEveryRequests` requests a replica pauses all its CPU work for
    a lognormal pause around `gcPauseMs` (Java about 12 ms, .NET 6, Node.js 2, Go 0.1, Rust
    none; illustrative). CPU work running when a pause starts is delayed by the pause. A pause
    holds the replica's work without counting as CPU use, so a calibrated service time should
    not also contain it (the benchmarks take the modelled pauses off the CPU per request).
- **Service time by load** (`serviceTimeByLoad`, schema 1.6; empty by default): points of
  `{rps, factor}` for one replica. Each request's service time is multiplied by the factor at
  the replica's own arrival rate over the last second (an exponentially weighted count with a
  1 s time constant, corrected for the time observed so the first second reads right; a
  serverless function: its rate over the instances busy at that moment). Between points the
  factor is interpolated linearly in log(rate); below the first and above the last it stays at
  that point's factor, so a cost measured to fall up to some rate is never assumed to keep
  falling past it. The factor applies to the whole service time, of which `cpuShare` counts as
  CPU, so CPU per request follows the curve. Every analytic copy uses it: a replica's CPU or
  event-loop capacity is the rate where its cores or loops fill at that rate's factor (a fixed
  point: the report's `capacityRps`, the event-loop explanation and the review's CPU finding);
  the instant estimate and sizing take the factor at the rate per replica they reason about.
  The explanation adds "Service time at this load". Calibration fits it from CPU per request at
  several loads ([calibration.md](calibration.md#cpu-and-memory)).
  - JIT warm-up: replicas started during the run (autoscaling) and new serverless instances run
    `warmupFactor` times slower at first, fading linearly over `warmupS` (Java 2.5x over 45 s,
    .NET 1.5x over 20 s; illustrative).

### Cache (`cache`): cache-aside or read-through

A miss is handled one of two ways, chosen by the links:

- **Cache-aside** (what applications usually do with Redis or Memcached, and the starter
  example): the cache has no `miss` link. A read that misses returns "miss" to the calling
  service, which then reads the data store itself over its link set to `readsOnMiss` and, if
  that succeeds and `populateOnMiss` is on, writes the value back (one more command on the
  cache, over the same link). With `continueOnFailure` on the service's link to the cache, a
  failed lookup (outage, timeout, rejection) counts as a miss, so reads fall back to the data
  store, and a failed invalidation is skipped instead of failing the write. A read the cache
  refused, down or turned away by its queue (full, or waited past `queueTimeoutMs`), is counted
  at the cache (`refusedLookups`) and counts against its hit ratio as a read it did not serve
  (ADR-0082): once, at its first refusal, however often its caller retries it, and not at all
  when the cache answers it on a later attempt. Its caller reads the data store for it where it
  carries on and reads on a miss, and fails the read otherwise. A write's invalidation and a
  write-back are not lookups, and a lookup its caller gave up on but the cache answered later
  counts as the hit or miss it found.
- **Read-through**: the cache's `miss` port links to the data store, and the cache reads it
  itself (below).

Validation requires one of the two for a cache that can miss and receives reads; a service's
`readsOnMiss` link needs a cache lookup to follow, and is never used behind a read-through
cache (a warning).

- Every command (lookup, invalidation, repopulation) runs on a pool of executors per shard
  taking `serviceTimeMs`, with its own queue. **Redis and Valkey execute commands on one thread
  per shard** whatever `concurrency` or the CPU says (`singleThreaded`, auto); Memcached, CDN
  and Varnish run `concurrency` executors in parallel (Memcached's default is 4 threads). For
  Redis, `serviceTimeMs` is what one command keeps that thread busy, reading the request and
  writing the reply included: its CPU per command, which sets both a shard's capacity and its
  CPU. `INFO commandstats` reports only the command's own share of it, about a tenth on the
  benchmark stack (0.003 of 0.032 to 0.038 ms per GET or SET).
- `shards` (Redis Cluster, Memcached servers): keys hash to a shard; each shard has its own
  executors, queue and memory, so a hot key loads one shard.
- **Writes** perform one command and invalidate the key (TTL model). They never use the
  `miss` port. In the starter example the API writes to PostgreSQL first and then invalidates.
- **Reads** perform one lookup command and decide hit or miss:
  - `fixed_ratio`: hit with probability `hitRatio` (the simplest assumption);
  - `ttl_keyspace`: hit if the request's key was filled less than `ttlSeconds` ago. The hit
    rate is *derived* from TTL, keyspace size and skew, and it includes **cold start** (the
    cache starts empty, so early buckets show lower hit rates). With memory set, each shard
    holds 80% of its memory / `itemSizeKb` keys: `allkeys_lru` evicts the least recently used
    key, `noeviction` (Redis's own default) refuses new keys with an out-of-memory error, so
    missed reads stay uncached.
- **Misses** (read-through) continue through the `miss` port (reads only, at most one link)
  to the data store. The miss path's latency counts toward the caller's request. The cache
  executor is *not* held while the data store works.
- If `populateOnMiss` is on and the miss succeeded, one more command writes the value back
  (and, in the TTL model, sets the key's expiry). If that command is rejected the read still
  succeeds; the key just is not cached.
- `persistence: rdb`: every `snapshotIntervalS` the process forks; the fork blocks the command
  thread for `forkMsPerGib` x the data held (Redis measures about 10-13 ms per GB on modern
  hardware).

### Database (`database`)

Each database instance has `maxConnections` concurrent queries, a wait queue
(`queueCapacity`) and an acquire timeout (`queueTimeoutMs`). Reads take `readServiceTimeMs`,
writes `writeServiceTimeMs` (before the log flush and replica acknowledgements). Topology:

- `readReplicas`: copies per shard that replay the primary's writes. Writes go to the primary;
  `readFrom` decides which instances serve reads (all round-robin, the primary only, or the
  replicas only).
- `shards`: requests are routed to `hash(key) mod shards`, using the workload's Zipf keyspace,
  so skewed popularity concentrates load on one shard (a hot partition). Each shard has its own
  primary (and replicas). The report counts arrivals per shard (`shard0`, `shard1`, ...).
- `replicationFactor` + `consistency` (`one`, `quorum`, `all`): for leaderless and consensus
  stores, an operation holds its connection for the k-th fastest of `replicationFactor`
  independently sampled times (k = 1, a majority, or all).
- `throughputLimitRps`: provisioned throughput per shard (token bucket); excess requests are
  rejected immediately (`throttled`).

**Technology behaviour.** The `technology` decides the mechanisms below; each is a setting in
the "Technology behaviour" group whose default `auto` takes the technology's value (see
[assumptions.md](assumptions.md), which lists every default with its documentation link):

| Mechanism | What happens | On by default for |
| --- | --- | --- |
| `rowLocks` | A write locks its key; a second write to it waits (holding its connection) until the first commits. | relational databases, MongoDB, distributed SQL |
| `readsWaitForWrites` | A read of a locked key waits for the writer (no MVCC). | SQL Server (READ COMMITTED without RCSI) |
| `commitFlush` | Each write waits for a write-ahead log flush (`commitFlushMs`); commits arriving during a flush share the next one (group commit). | relational databases, MongoDB |
| `replication: async` | Replicas replay each committed write after `replicationDelayMs`, on `replicaApplyWorkers` workers at `replicaApplyFactor` x the write time. Replay that falls behind is replication lag; a replica read of a key whose latest write it has not replayed counts as stale. | PostgreSQL (1 worker), MySQL (4), Oracle, SQL Server |
| `replication: majority` / `all` | The write also waits until a majority of the copies (or all replicas) have replayed it, plus the acknowledgement's network trip. | MongoDB (majority), Elasticsearch (all) |
| `readTickets` / `writeTickets` | At most N operations of each kind execute at once per instance, beyond the connection limit. | MongoDB (128 each) |
| `scatterReads` | That share of reads goes to every shard and finishes with the slowest; `routerOverheadMs` is added. | Elasticsearch (every search) |
| `sstablesPerRead` | A buffer-cache miss costs that many disk reads. | Cassandra (2), Scylla (1.5) |
| `capacityUnits` | Reads cost ⌈item / 4 KB⌉ read units (half for eventually consistent reads), writes ⌈item / 1 KB⌉ write units. Each partition serves at most 3,000 read and 1,000 write units per second; the table at most its provisioned units (plus 300 s of banked burst) or, on demand, 12,000 / 4,000. Partitions = max(⌈RCU / 3000 + WCU / 1000⌉, ⌈size / 10 GB⌉). | DynamoDB |

### Message queue (`message_queue`)

Kafka, RabbitMQ, SQS-style, Pub/Sub-style, NATS or Redis Streams. A publish takes
`publishTimeMs` and the **producer's request succeeds once the message is acknowledged**; the
work continues asynchronously. Messages wait in a backlog (bounded by `maxDepth`; a full queue
rejects publishes with `queue_full`) until a consumer picks them up, spends `consumerTimeMs`
and calls the queue's outgoing links in order (like a service's dependencies). A failed
delivery is retried up to `maxDeliveries` times, then dead-lettered. The run keeps going until
the backlog drains or the drain limit is reached. Client latency and success do not include
background processing.

Technology behaviour (settings with `auto` defaults):

- `partitioning`: `key` (Kafka) hashes each message's key to one of `partitions` ordered
  partitions; consumer i owns partitions p with p mod c = i (c = min(consumers, partitions))
  and processes them one message at a time, so a hot key backs up its partition while other
  consumers idle (`partitionLagMax`). `none` is one shared FIFO with competing consumers. On
  either, no more consumers work than the queue has partitions: the rest sit idle, which the
  report gives as `idleConsumers` and the queue's finding explains and advises on (ADR-0085).
- Fetch cycles (schema 1.6; off unless `fetchOverheadMs` is set or `consumerConcurrency` is
  above 1): a consumer polls, takes its partitions' whole backlog (a shared queue: one message
  per slot), processes it in `consumerConcurrency` slots (each partition's messages one after
  another, as kafkajs's `partitionsConsumedConcurrently` does), waits until all are done, pays
  `fetchOverheadMs` (the fetch round trip and the offset commit) and polls again. Messages that
  arrive meanwhile wait for the next poll; with no backlog it takes the next message as soon as
  it arrives. The overhead is spread over more messages per poll as the backlog grows, so
  throughput approaches slots × 1000 ÷ processing time only under load (`consumerSlots`; a
  consumer uses at most one slot per partition it owns). `partitionLagMax` counts messages not
  yet fetched; `lag` also counts fetched messages not yet processed.
- Message age (`meanMessageAgeMs`, `p95MessageAgeMs`) runs from the start of the publish to the
  end of processing, as a producer that stamps a message before sending it sees it; until engine
  0.15 it started at the acknowledgement. `queueWaitMs` still starts at the acknowledgement.
- `acks: all` adds `replicationMs` to every publish; `lingerMs` adds a uniform share of the
  producer's batching delay (Kafka 4.0: 5 ms).
- `flowControl` (RabbitMQ): once the backlog uses `memoryHighWatermark` of `memoryGib` (at
  `messageSizeKb` per message), publishers block until it falls below 90% of that: they wait
  instead of failing (`publishBlockedMs`).
- `visibilityTimeoutS` (SQS 30 s, Pub/Sub 10 s): a message still being processed when it runs
  out becomes visible again and another consumer processes it too (`redeliveries`).
- `fifo` (SQS): publishes above `fifoLimitRps` (300) are throttled.
- `delivery: at_most_once` (core NATS): a full backlog drops the message silently; the
  publisher still gets its acknowledgement (`dropped`).

### Third-party API (`external_api`)

A dependency you do not operate: payments, e-mail, SMS, identity, LLM inference, object
storage. Each call takes `latencyMs` and fails with probability `errorRate`
(`upstream_error`). Optional `rateLimitRps` (token bucket, `rate_limited`) and
`concurrencyLimit` (in-flight cap, `throttled`) model provider quotas. There is no queue: the
provider either answers or refuses.

### Kubernetes cluster (`kubernetes_cluster`)

A node pool for the services on the Kubernetes platform. It has no links: services join it
through their platform (and `cluster` when the design has more than one cluster), and it is
judged by its pods, not by requests.

- **Nodes**: `nodes` nodes of `nodeVcpu` vCPU and `nodeMemoryGib` GiB; `allocatableShare`
  (90% by default) of each is allocatable to pods, the rest is reserved for the system.
- **Pods**: every replica of a member service is a pod that requests the service's CPU and
  memory size (`cpuCores`, `memoryGib`), or the cluster's `defaultCpuRequest` /
  `defaultMemoryRequestGib` when the service has none. The scheduler binds a pod to the node
  with the most free CPU that fits both requests (LeastAllocated spreading). A pod that fits
  nowhere waits as **Pending** in a first-come queue and is bound as soon as a node has room.
- **Start**: the starting replicas are bound at t = 0 and ready at once if they fit; the rest
  start Pending. A pod bound later pulls the service's image (`imagePullS`, only on a node's
  first pod of that service) and then starts (`startupS`) before it serves.
- **Cluster autoscaler** (`clusterAutoscaler`): every `scanIntervalS` it looks at the Pending
  pods, works out how many nodes they need (first-fit bin packing into empty nodes) and asks
  for that many more, within `maxNodes`. Each node is ready `nodeProvisionS` later. A node
  empty for `scaleDownUnneededS` is removed, down to `minNodes`.
- **HPA and pods together**: the service's autoscaler asks for replicas as usual; each new
  replica is a pod that can wait Pending, then pull and start. Time from a spike to new
  capacity is roughly scan interval + node provisioning + image pull + start-up when the
  nodes are full. When the autoscaler wants fewer pods than it has asked for, it first
  deletes the service's Pending pods (newest first, after the same three low readings), as a
  ReplicaSet scaled down deletes unscheduled pods first; ready pods scale down as usual after
  that. A load balancer whose targets have no ready pod answers `no_ready_pods` (503).
- **Report**: the cluster adds `nodes`, `nodesProvisioning`, `podsRunning`, `podsPending`,
  `cpuRequestedRatio` and `memoryRequestedRatio` series, peaks and end values in its summary
  (`nodesPeak`, `podsPendingPeak`, `podsPendingEnd`, `maxPendingS`, which also counts pods
  deleted or still waiting...) and `nodeAdds` / `nodeRemovals` / `podsCancelled` counters;
  member services add `podsReady`, `podsStarting` and `podsPending`. The cluster's status is saturated with pods still Pending at the end, hot
  when pods waited, warm when 85% or more of the CPU is requested. A `pods_pending` finding
  reports the longest wait and what the cluster autoscaler did.
- **Validation**: a pod bigger than a node is an error (`kubernetes.pod_too_big`); starting
  pods that do not fit, a node count outside the autoscaler's range, a cluster with no
  services, a missing or ambiguous `cluster` on a service are warnings.
- **Cost**: the cluster is billed per node (`costPerHour`, or the node size at the
  illustrative rates; the average node count when a run exists); member services cost nothing
  extra ("runs on" the cluster).

### Cost assumptions

`costPerHour` (per instance: replica, database instance, cache node, broker, load balancer)
and `costPerMillionRequests` (third-party APIs) never affect the simulation. The engine
(`CostEstimator`) turns them into a monthly estimate (730 hours) and a cost per million successful
requests; every report carries it as `cost`, and `POST /analyze` prices a design before it runs.
A month is priced at the workload's steady **base** load (`BaseRate`: a constant rate, the
rate outside a burst, a ramp's low end, a trace's median), but for the hours a month it runs at
its peak (`workload.peakHoursPerMonth`, priced at the peak): per-request parts (third-party APIs,
functions, API gateways, managed queues, DynamoDB on demand) at each part's rate while the run's
offered load was at that load, scaled to it exactly (in the run's buckets nearest it when none
is at it; before a run, the static load estimate at its rate), and autoscaled services at the
count their autoscaler settles at there (see **Cost estimate** below). Managed and per-request
lines list each priced quantity with its unit price (`items`: "394.20 M requests × $3.50/M"); see
[pricing.md](pricing.md). A price you set on a part wins; a technology preset's `costPerHour` is
illustrative, so with a price snapshot the part is priced at the instance type it snaps to (or
the snapshot's managed rate) instead; parts without either are priced from their size at
illustrative rates. Each cost line says which (`priceSource`), see
[pricing.md](pricing.md#which-price-a-part-gets). A third-party API's price per million calls
follows the same rule (a price equal to a preset's of its technology is the preset's, unless a
person measured or typed it) and may be left empty (schema 1.9): it then takes the price of its
technology's first preset that has one (`preset`, with a note naming the preset), else the API is
not priced: its line has no unit price, the cost lists it in `unpriced` and a note says the month
leaves it out (ADR-0081). A price of 0 is a free API; before schema 1.9 a saved design wrote 0 for
an unset price, so the upgrade reads an API's 0 from an older design as unset, unless its
provenance says a person stated it. With a snapshot, sizes snap to its instance
types, so the snapshot changes results too: a design pins one with `pricing.snapshotDate`, and a
run's `input.pricing` records the one it used.

### Resource specs (CPU, memory, storage)

Services, databases, caches, load balancers and message queues can carry `cpuCores`,
`memoryGib` and (databases, queues) `storageGib` per instance (per replica, per database
instance, per cache node, per broker). A spec left empty is auto-sized (see below) unless the
document sets `architecture.autoSize: false`, in which case it is not modelled and the component
behaves as if it had unlimited CPU and memory. Set, a spec has these effects:

| Component | CPU cores | Memory | Storage |
| --- | --- | --- | --- |
| Service | `cpuShare` (default 1) of `serviceTimeMs` is CPU work: at most `floor(cpuCores)` handlers per replica run it at once, the rest wait for a core; below 1 core the work runs `1 / cpuCores` times slower. The rest of the service time (I/O inside the handler) holds the worker (or the event loop) without a core, so the CPU share changes CPU use and contention, not latency. Serverless: only a fraction below 1 matters (one request per instance). | Each request a replica holds (queued, running, or waiting on downstream calls) uses `memoryPerRequestMb` on top of `memoryBaselineMb`; a request that would exceed `memoryGib` is refused (`out_of_memory`). `memoryPerRpsMb` (default 0) adds memory that follows throughput: garbage-collected runtimes keep what requests allocate until the next collection. It counts in `memoryPeakGiB` (per replica, at its busiest time bucket in the window) and in the size, not in the refusal limit, since a collector reclaims it under pressure. | - |
| Database | `cpuShare` of each query's time needs a core (the rest is I/O, locks, network); queries queue for cores per instance. | With `workingSetGib`: 75% of memory is buffer cache; reads are served from memory with probability `min(1, 0.75 x memoryGib / (workingSetGib / shards))`, the others add `diskReadMs`. | Checked against `dataSizeGib / shards` per instance (error when it does not fit, warning above 85%). |
| Cache | Command execution queues for cores when `concurrency` exceeds them. | TTL model: 80% of memory holds keys of `itemSizeKb`; when full, the least recently used key is evicted. Fixed ratio: a warning when the workload's keyspace does not fit. | - |
| Load balancer, message queue | Shown and priced only. | Shown and priced only. | Shown and priced only (queues). |

Whole cores above one give parallelism, a fraction keeps total capacity (2.5 vCPU = two
executors at 1.25x). With as many whole cores as handlers nothing waits and results are the
same as without the spec. Reports add `cpuCoreUtilization` (busy executors / executors in the
window, over all a part's instances), `busiestInstanceCpuUtilization` (the same for a database's
busiest instance, when it has several, ADR-0083), `memoryPeakGiB` (services), `bufferHitRatio`
(databases) and `capacityKeys` / `keysCached` plus an `evictions` counter (caches). Not
modelled: memory pressure driving garbage collection (GC pauses come from the technology
profile, see above, not from the memory spec), OOM kills and restarts (memory is a refusal
limit), page-cache dynamics, disk write bandwidth, network bandwidth.

#### CPU threads that slow each other down (schema 1.7)

A vCPU is usually a hardware thread, not a core: two threads share each physical core
(hyperthreads), and a machine's clock falls as more of it is busy (turbo and power limits). So
N busy vCPUs do less than N times one's work. Two optional settings model it (archsim-engine's
`host` package); both empty, every vCPU is a full core at a fixed speed, as before:

- **`architecture.host`**: the one machine every part with a CPU size runs on (docker compose on
  one VM, a benchmark on a laptop), with `physicalCores`, `threadsPerCore` (2 with
  hyperthreads), `siblingSpeed`, `allCoreClock`, `otherBusyThreads` (its background) and
  `clientCpuMs` (CPU the clients' traffic costs on it outside the design's parts, such as a load
  generator on the same machine). The parts' CPU work, the background and the clients' load all
  count as busy threads of that machine.
- **`siblingSpeed`** on a service, database or cache with 2 or more `cpuCores`: its own vCPUs
  are hyperthreads, two per core, as on most x86 cloud instances; 1 makes them whole cores.
  Ignored on a design with a host.

**The default (schema 1.11, ADR-0091).** A part with 2 or more vCPU, on a design without a
host, that sets no `siblingSpeed` runs on hyperthread pairs at the first sibling speed that
applies: the design's `architecture.siblingSpeed`; 1 (whole cores) when the instance type the
cost prices for it is of a whole-core family (Graviton and other Arm families, AWS T2 and its
AMD families from M7a; the list, each family with the page that says so, is on
[assumptions.md](assumptions.md)), when the service runs serverless, or when it runs as pods on
Kubernetes; else 0.6, the middle of what published measurements of request-serving work give
(0.55 to 0.70), from vendors' documentation and others' benchmarks, not a measurement of
ArchSim's. Pods share their node's cores with other pods, so their own pairs would be the wrong
shape; counting them as whole cores is optimistic on an x86 node pool, and making the node the
machine is a decision of its own. One resolver (`SiblingSpeeds`) applies the rule when a run's
document is resolved, after the scenario and auto-sizing, and writes into each part that sets
no speed the one it counts at (inferred, or the design's below 1), as auto-sizing records the
instance type it chose: the simulation, the instant estimate, the design review, `explain`, the cost, sweeps and checks all read it there, and the
run's `input` says what ran. The sizing section gives each part's `vcpus` (`siblingSpeed`,
`basis`: `part`, `design`, `wholeCoreFamily`, `serverless`, `pod` or `default`, and `text`), and
the report's model notes name the parts on the default and the pods. A design saved before 1.11
is pinned to whole cores by `architecture.siblingSpeed: 1`, which the upgrade writes, so it keeps
its results; clearing it puts the design on the default. The accuracy benchmarks' designs are
written at schemas 1.0 and 1.7, so they stay on whole cores, which is also what is known of the
laptop they run on.

With `busy` threads wanting the CPU on `P` physical cores of `T` threads, a thread runs at
`clock x sharing x turns` of its speed alone on an idle machine:

- `clock = 1 - (1 - allCoreClock) x (min(busy, P x T) - 1) / (P x T - 1)`: the clock falls in a
  straight line from one busy thread to all of them;
- `sharing = 1 - (1 - siblingSpeed) x min(1, 2 (busy - P) / busy)` when `T = 2` and
  `busy > P`: the scheduler gives threads idle cores first, so only past `P` do they share one,
  and a thread sharing its core runs at `siblingSpeed`;
- `turns = min(1, P x T / busy)`: past every thread they take turns.

In the simulation a piece of CPU work starting with `busy` threads (its machine's executors
then running, the background and `clientCpuMs` x the clients' offered rate) takes
`work / speed(busy)` on its vCPU. The vCPU is occupied for that long, so a part's busy cores and
CPU per request rise as the machine fills, as a container's CPU time does when the machine is
busy. Without a host, a part's own hyperthreads use the same formula with `P = cpuCores / 2` and
no other load: 2 vCPU at `siblingSpeed` 0.6 serve 1.2 cores' work with both busy. The report
gains `host` (`physicalCores`, `threads`, `partsBusyThreads`, `otherBusyThreads`, `busyThreads`
and `meanSpeed` over the window) when the design has one.

The calculations around a run use the same speed at the load they reason about: the CPU
ceiling in `capacityRps` is the load multiple at which the part's threads fill while every
part's load and the clients' grow in proportion to the window's (own hyperthreads: every vCPU
busy); the event-loop formula in `explain` says the speed it assumed; the instant estimate, the
design review and auto-sizing take the speed at the estimated peak, `busy = base / speed(busy) +
other`, where `base` is the threads the parts' CPU work needs at full speed. Sizing on a host
counts the parts it is about to size. A part on its own hyperthreads is sized another way,
since the speed of its vCPUs depends on how many it gets: see the auto-sizing rules below.

The accuracy benchmarks can set the host from a contention check of the machine (the harness's
`contention`, [validation.md](validation.md)): a fixed unit of V8 work timed with its
hyperthread sibling, other cores or every CPU busy, Windows' own frequency and logical-processor
counters meanwhile, and k6's CPU per request; each calibration pass's CPU per request is then
divided out by the machine's speed during that pass (the harness's machine inputs). They do so only
with `BENCH_HOST=on`: on in-sample re-simulation it did not yet clear the accuracy gate
(IMPLEMENTATION_STATUS.md, W27).

**Auto-sizing.** An empty spec is *Auto*: before a run the engine (`AutoSizer`) gives the part
the size it needs for the design's own peak load, so the CLI, the API and the web app simulate
the same document identically (same result digest). The report's `sizing` section says, per
part, the value simulated and whether it was set, auto-sized or set by a scenario, with how each
auto size was worked out, and what the part **needs** beside what its instance has (`need`,
`needText`: "needs 520 MiB (up to 620 MiB when queues are full) · c6g.xlarge has 8 GiB"):
`busyCores` (CPU busy at the peak) and `baseBusyCores` (at the base load), `cpuCores` (busy at
the 60% target), `memoryGib` (a service: its fixed memory plus the requests one replica holds on
average, λ·W from the instant estimate's `meanResidenceMs`; a cache: its keyspace; a database:
its hot data in the buffer cache, and enough for its connections), `memoryGibMax` (a service
with every queue full, and the request arriving when it is), `storageGib` (the data it stores).
`cpuCores` and `memoryGib` in `specs` stay the instance's real capacity, which is what runs.
`smallest` marks a part whose CPU need is below the smallest size it can get (1 vCPU, or the
smallest type of its kind). `POST /analyze` and `archsim analyze` return the same without
simulating. The web app shows auto sizes greyed with an "auto" tag on the canvas and in the
inspector; a typed value replaces them, "Back to auto" clears it. `architecture.autoSize: false`
turns auto-sizing off for a document (an empty spec is then not modelled). The rules follow
the effects in the table above:

| Part | CPU | Memory | Storage |
| --- | --- | --- | --- |
| Service | peak req/s per replica (at full scale when autoscaling) x mean `serviceTimeMs` x `cpuShare` / 60%, never more than its handlers (workers, or event loops for Node.js) can use: capped at exactly that count; on a Kubernetes cluster, never more than one node can allocate; serverless: 1 vCPU (with memory set: memory / 1,769 MB, AWS Lambda's coupling) | `memoryBaselineMb` + (requests in flight + `queueCapacity`) x `memoryPerRequestMb` + peak req/s per replica x `memoryPerRpsMb`, plus 0.25 GiB for the operating system on a VM of its own, +25%, at most 64 GiB; serverless: at least 1,769 MB per vCPU | - |
| Database | the busiest primary's CPU work (its share of reads, every scattered read for Elasticsearch-style search, every write; RF and consistency for rings) x `cpuShare` / 60%, never more than its connections x `cpuShare` can keep busy | hot data per instance / 0.75 (the buffer cache share): the working set, else an illustrative 25% of the data it stores; at least its connections x 9,531,392 bytes (RDS's default `max_connections` formula) and 1 GiB | data per instance (`dataSizeGib`, else keys x item size) kept under 70% full, at least 10 GiB |
| Cache | commands per node x `serviceTimeMs` / 60%, never more than the commands it runs at once (1 for single-threaded Redis-style caches) | keyspace x item size per node / 0.8, +25% | - |
| Load balancer | 5,000 req/s per vCPU (illustrative) | 1 GiB per vCPU (illustrative) | - |
| Message queue | Kafka: 0.39 fixed cores + 0.053 ms of CPU per message per broker, at 60% (measured, ADR-0085); others: 10,000 messages/s per broker vCPU at 60% (illustrative) | Kafka: a 973 MiB working set (measured), at least 2 GiB per vCPU; others: 2 GiB per vCPU (illustrative) | Kafka and Redis Streams: 7 days at the base rate, 3 copies; others: an hour of peak publishes |

CPU is rounded up to 1, 2, 4, 8... vCPU (at least 1, since below one core the engine slows
every request; the fraction a light part needs shows only as its need and as a Kubernetes CPU
request in the export; the size below is kept when it runs the peak at up to 65%, rather than
doubling the cores for a few points over the 60% target), memory to 0.5, 1, 2, 4... GiB, storage to 10, 20, 50, 100... GiB. A part
that runs on machines of its own then gets a shape a cloud sells: with a price snapshot, the
cheapest instance type that covers its CPU and memory, a burstable t4g type when the part passes
the credit check (its CPU at the base load under 80% of the type's baseline, at the peak under
60% of its vCPUs; `pricing.burstable: never` turns it off; see [pricing.md](pricing.md)), or its
own `instanceType` (schema 1.4) while that covers it; auto-sizing writes the type it chose into
the run's input, so running the input again gives the same types and cost. Without a snapshot,
CPU and memory are rounded up together to the cheapest standard shape of 2, 4 or 8 GiB per vCPU
(4 vCPU and 0.5 GiB becomes 4 vCPU and 8 GiB). Pods on a Kubernetes cluster and serverless
functions keep their computed size: their machines are the cluster's nodes and the provider's.
Peak load comes from the static estimate below, with each TTL cache's hit ratio estimated from
its TTL and the key popularity (`TtlMissShares`; a cache whose memory holds fewer keys
than the keyspace keeps the most popular ones).

A service, database or cache whose vCPUs are hyperthreads (its own `siblingSpeed`, the design's,
or the default), on a design without a host, is sized for them. Each of its vCPUs counts as its
sibling speed of a core, what each does with
all of them busy by the formula above, which a load that fills them reaches and which its CPU
capacity counts on (`cpuCapacityRps`). The target is the same 60%, of what its vCPUs can do, so
a part on pairs needs its whole-core size divided by the sibling speed: its busy cores / the
sibling speed / 0.6 vCPU, at a sibling speed of 0.6 the busy cores / 0.36 (`need.cpuCores` is
that figure, and `needText` says "at a 60% target of what its hyperthreads can do, 0.6 of a
core each"), rounded up to the same sizes, with the same 65% for the size below. From two
handlers on (a service's workers or event loops, the cores a database's connections keep busy,
the commands a cache runs at once) the cap is two vCPU, one physical core, for each handler: at
a sibling speed of 0.6, 4 workers on 4 vCPU would share 2 cores and do 2.4 cores' work at most,
and on 8 each has a core to itself. A part with one handler, which never has two vCPUs busy, and
a part that gets one vCPU on whole cores, which has no sibling, are sized as on whole cores. At a
sibling speed of a half or less a part so goes from 1 vCPU straight to 4, since 2 vCPU with both
busy do no more than one core's work. A part that sets its sibling speed, or whose design does,
has one need on every instance type or standard shape of 2 or more vCPU, whatever its family. A
part on the default has two needs from the same work, on whole cores and on pairs at 0.6: with a
price snapshot, each type covers the need of the kind its vCPUs are (whole cores on a whole-core
family's types and on a type of 1 vCPU, pairs elsewhere), so the part gets, in one scan, the
cheapest type that covers its own family's need, and its own `instanceType` while that covers its
need; a standard shape has no family, so at generic prices the part is sized on pairs. The size's
explanation, `need` and `needText` then follow the kind it got, and the run counts its vCPUs the
same way. A part that keeps one vCPU and lands, for its memory or because none smaller is sold,
on a shape or type of two or more, which runs them as pairs, needs that shape or type to cover its
need on pairs: at a sibling speed of 0.3 a service doing 0.65 cores' work gets 4 vCPU, not the 2
whose pair would do 0.6 of a core's work with both busy. On a design with a host the parts are
sized at the host's speed instead.

In a run, work that starts while more vCPUs are busy runs slower, but the share of them busy
stays below the share the work is of what they can do: at the edge of a size, where that is 65%,
47% of 8 vCPU are busy at a sibling speed of 0.6 and 59% at 0.8, 60% and 64% of 2 vCPU, and 21%
of 8 vCPU at 0.3, where few busy vCPUs each have a core to themselves (Poisson arrivals, a fixed
service time; `HyperthreadSizingTests`).

**The sizing audit** (`SizingAuditTests` in `archsim-engine`, on every template) checks auto
sizes against what the simulation then does: each auto-sized part's p95 busy cores per instance (from the per-bucket `cpuBusyCores` series of services, databases and
caches) between 25% and 75% of its vCPU, unless it is on the smallest type its need allows,
capped by its workers or connections, on a bigger type for its memory, or behind a part that
holds its load back; no auto-sized part is the bottleneck because of its CPU or memory;
simulated memory peak ≤ need ≤ instance memory; a re-run of the input reproduces the same types
and cost; a higher request rate never gets a part a cheaper type; per-request cost
lines within 10% of a hand calculation; and each template's monthly cost at most 1.15 times a
hand-picked reference.
Managed services (ALB- and API-gateway-style, CDN, DynamoDB-, SQS- and Pub/Sub-style) are not
sized. Sizes come from the design as drawn: scenarios run on the same sizes, so a scenario with
more traffic, fewer cache hits or a slowdown shows what those machines do.

Specs also feed the calculations around a run:

- **Capacity figures**: `capacityRps` is the lowest ceiling of the slots (`slots / meanHoldMs`),
  the CPU (`cpuCores x instances / CPU ms per request`, at the speed of its machine's threads when
  the design has a host or the part a `siblingSpeed`) and, for services, the memory (requests
  that fit x replicas / `meanHoldMs`); `capacityLimit` says which (`slots`, `cpu`, `memory`) and
  bottleneck findings name it. `serviceOnlyCapacityRps` also respects the CPU ceiling. A
  database of more than one instance (read replicas, partitions) sets its CPU ceiling where its
  busiest instance's cores fill, as a primary takes every write and a hot shard more than its
  share: `cpuCores x 1000 / CPU ms per request` of the work that instance was sent, at full speed,
  the operations it refused costing what the ones it served did, at the speed of its machine's
  threads. The report gives that instance's share of its cores busy too
  (`busiestInstanceCpuUtilization`, ADR-0083).
- **Cost estimate (`CostEstimator`)**: a part's price per instance-hour is the price set on it; without one,
  its size at illustrative on-demand rates ($0.037 per vCPU-hour, $0.0028 per GiB-hour, $0.08 per
  GiB-month of storage); without a size, the catalog default. A price equal to the catalog
  default counts as not set (saved runs store every default). The cost table marks size-priced
  parts. The month is 730 hours: `workload.peakHoursPerMonth` of them at the pattern's peak and
  the rest at the steady base load (0 by default: all at the base load), with an hour at each
  priced beside it (`cost.hourly`); an autoscaled service is billed at each load the replicas its
  autoscaler settles at there (its work in busy replicas ÷ `targetUtilization`, rounded up, within
  `minReplicas`–`maxReplicas`), and a cluster with its autoscaler the nodes its pods there need,
  within `minNodes`–`maxNodes` ([pricing.md](pricing.md#what-a-month-is),
  [ADR-0074](adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)).
- **Design review (`DesignReview`, before a run)**: a static estimate of the peak load at each part (peak
  rate split by read/write mix and client share, spread over load-balancer targets by replicas,
  cache misses at the fixed hit ratio) is compared with its CPU: high when the CPU would be over
  100% busy, medium above 80%. It also flags a database working set that does not fit in memory
  and a TTL cache smaller than the keyspace.
- **Exports**: Kubernetes containers get `resources.requests` = `limits` from CPU and memory
  (set or auto-sized, as the engine reports them), except that an auto-sized pod requests the CPU
  it needs in millicores (e.g. `210m`, rounded up to 10m) with its whole cores as the limit, and
  StatefulSet volumes the storage size; Docker Compose gets `deploy.resources` limits and
  reservations.

## Validation rules

Documents are validated before simulation (archsim-model's `DocumentValidator`); errors block the run and carry
a stable code, the node or edge involved and a hint. Highlights:

- unique ids; known component types; config values in range (`node.config_invalid` with the
  field path);
- links must start at an output port and end at an input port; request types must be allowed
  by both ports (for example the cache `miss` port only emits reads); no self-loops or
  duplicate links;
- port arity (each client group needs exactly one link; a load balancer at least one; the
  cache miss port at most one);
- at least one client group;
- **no cycles** (synchronous calls would recurse forever), reported with the loop path;
- **request-type routing**: request types are propagated from the source through the graph;
  a load balancer receiving writes with no link carrying writes is an error; so is a workload
  generating writes when the source link only carries reads; a cache that can miss with no
  `miss` link is an error;
- warnings (non-blocking): unreachable nodes, links that never carry traffic, a 100%-hit cache
  with a miss link, a TTL cache that never populates, zero offered load;
- faults: an unknown target (`fault_unknown_target`), the wrong kind of target
  (`fault_wrong_target`: latency needs a link, outage/slowdown a node other than the traffic
  source), a window that starts after the run (`fault_outside_run`) are errors; a window that
  runs past the end is clipped with a warning (`fault_clipped`);
- technology settings: autoscaling with `minReplicas > maxReplicas` is an error; consistency
  set without replication, more shards than keys, autoscaling settings on a serverless service,
  a queue without consumers or with idle consumers are warnings.

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
| `latencyWithFailuresMs` (same fields) | What users waited, failures included: successes at their latency, and each failed or timed-out measured-cohort request as its client's full timeout (`timeoutMs`), or as long as it took when retries ran past it. Failing fast never looks fast, and once more than 5% of requests fail, p95 is at least the timeout. Report 1.12+. |
| `retryAmplification` | Client attempts / client requests inside the window. |
| `failureBreakdown` | Failures of the measured cohort grouped by (status, reason, origin node, link). |
| `clients` | One entry per client group: `share` (fraction of the workload), the measured cohort's `arrived` / `succeeded` / `failed` / `timedOut`, `offeredRps`, `successRate`, `retryAmplification` and `latencyMs`. The fields above are the totals across groups. |
| `messageAgeP95Ms`, `lostMessages`, `availability` | A design with a queue only (report 1.19, ADR-0084): the p95 age of a message when processed at its slowest queue (null when a queue held messages it never processed); the messages the measured requests published that a queue dead-lettered or dropped, by the end of the run; and the share of measured requests that succeeded and lost no message, which the availability objective judges, as a request whose message was lost was answered all the same. |

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
| `capacityRps` | The lowest of `slots / meanHoldMs` (the rate at the observed holding time) and any CPU or memory ceiling from resource specs (derived, not assumed). |
| `capacityLimit` | Which ceiling sets `capacityRps`: `slots`, `cpu` or `memory`. |
| `cpuCapacityRps` | CPU ceiling from the CPU spec: cores x instances / CPU ms per request; for a database of several instances, cores x 1000 / its busiest instance's CPU work per request, the operations it refused included (ADR-0083) (null when CPU is not set). |
| `cpuCoreUtilization`, `busiestInstanceCpuUtilization` | The share of a part's CPU cores busy, over all its instances; and, for a database of several instances, its busiest instance's (ADR-0083). Neither is `utilization`, its slots busy, nor `cpuUtilization`, the share of busy slot time spent on its own work. |
| `serviceOnlyCapacityRps` | `slots / meanServiceMs`, capped by the CPU ceiling: the limit if dependencies were instant. |
| `hitRatio`, `counters` | A cache's hits over its lookups: hits, misses and lookups it refused (`refusedLookups`, ADR-0082); cache hits/misses/refusedLookups/invalidations/populates; database reads/writes; `wasted`. |

**Time series** use fixed buckets chosen so the arrival window has at most 90 buckets (and
the whole run at most 1,200), from the sizes 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120 s.
Latency percentiles per bucket are computed from successes completing in that bucket (all
cohorts, so warm-up is visible in charts).

**Memory is bounded** by buckets and histogram bins, never by the number of requests.
Latency histograms use logarithmic bins growing by 2% (reported values are the bin's geometric
midpoint, so percentiles are accurate to about 1-2%). Means are exact.

## Confidence (`confidence`)

Every report (1.13+) and every `/analyze` result has `confidence`: how much of what decides the
result rests on numbers someone checked. There is no numeric score.

| Field | Definition |
| --- | --- |
| `level` | `high`, `medium` or `low` (rules below). |
| `reason` | The fact that decided the level, e.g. "31 of 35 inputs are illustrative defaults". |
| `reasons` | Every fact behind it, the deciding one first. |
| `drivers` | Up to six inputs to replace first: badly fitted measured inputs, then the assumed and illustrative inputs of the heaviest parts, service times and latencies first. Each has `scope`, `id`, `label`, `field`, `fieldLabel`, `kind` and `weight` (its part's share of the weight). |
| `weightedBy` | `latency` (reports), `utilization` (analysis), or `inputs` when nothing carries weight. |
| `howToRaise` | One sentence: what would raise it. |

**Weight.** In a report each part counts by its share of a successful request's time
(`explain.latencyBreakdown`); the network's share goes to the links in proportion to calls x
mean link latency. A run in which no request succeeded falls back to utilization. Before a run
(`/analyze`), each part counts by its utilization at the peak from the instant estimate (below),
capped at 1. Parts without inputs carry no weight; the workload's inputs describe the question,
not the system, and are not weighed. Cost inputs are not weighed either.

**Grounded inputs.** A part's share of grounded inputs: measured with a calibration fit error of
at most 15% (or no fit error recorded), or documented.

- **High:** at least 80% of the weight is grounded and no experimental technology is on the
  request path (a part that serves requests in the run, or that the estimate puts on a client's
  path).
- **Low:** under 40% is grounded, or an experimental technology is at the bottleneck (the report's
  bottleneck finding, else its busiest part; before a run, the estimate's busiest part), or a
  measured input's fit error is above 30%.
- **Medium:** otherwise.

The web app shows the badge and the reason next to every verdict, the Markdown report and
`archsim run` print them, the CI gate's comment carries them, and the MCP tools return them.
Confidence is not part of `resultDigest`.

## Instant estimate (`estimate`)

What textbook queueing formulas predict for the design, without simulating: `/analyze` returns it
for the design (cheap enough for every edit, about a millisecond), and every report (1.13+)
records it for exactly what was simulated, so the two can be compared. At the workload's peak,
from the static load estimate (first attempts only; a fixed hit ratio, or for a TTL cache one
estimated from its TTL and the key popularity: each key's hit ratio is r·W / (1 + r·W), with W
the time until the key expires or is next written):

- **Utilization** ρ = λ / (pools x slots x μ), with μ = 1000 / mean holding time. A slot is held
  for the part's own time, plus its downstream calls when it waits on them (thread-per-request
  services, load-balancer connections, serverless instances, queue consumers). CPU cores (set or
  auto-sized) can bind first. `utilization` is the busy share of slots or CPU, what a run's
  utilization measures; a rate or throughput limit is not work, so its share is `rateLimitShare`,
  and `load` (the largest of them) says whether the part can keep up (before engine 0.13 a
  gateway at 75% of its rate limit showed as 75% busy).
- **A database's commit** is a wait, as in a run: a write holds its connection for its query and
  one log flush, and both count in its latency, but only the CPU share of its queries is work on
  its cores, and only that work takes longer on threads a host machine or a hyperthread sibling
  slows. Its `cpuUtilization` is its primary's: `((reads ÷ instances of a shard) × read time +
  writes × write time) × cpuShare ÷ 1000 ÷ shards ÷ cores`. Before engine 1.5.1 the flush counted
  as CPU time too, which filled a write-heavy database's cores too early
  ([ADR-0089](adr/0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md)).
- **A cache's commands** spread evenly over its shards, as in a run, where each shard has its
  own executors and `cpuCores`: its slots' share and its `cpuUtilization` are those of every
  shard, `commands × command time ÷ 1000 ÷ shards ÷ (executors, or cpuCores)`. A read-through
  cache's commands are its requests and a fill for each read it misses and reads through its
  miss link (with `populateOnMiss`): the fill takes a slot and the shard's cores, and the
  reads queue behind it, each paying for its own fill as before. A cache-aside caller's
  write-back is a request to the cache already. A hot key's shard is busier than the even
  spread says, which the estimate leaves to a run, as it does for a database's shards. Before
  engine 1.5.2 a sharded cache's whole load counted on one shard's cores, and the fills did not
  count in its slots or CPU
  ([ADR-0092](adr/0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md)).
- **Waiting:** an M/M/c queue per pool (a service replica, a database instance, a cache shard),
  Wq = C(c, a) / (c·μ - λ) with Erlang C. Load balancers, serverless functions and third-party
  APIs refuse rather than queue, so they add no wait.
- **Latency** of each request type: the network both ways on every link, each part's wait and own
  time, and its calls in order, each weighted by how often it is made (misses, load-balancer
  shares). A message queue's consumers are off the path: the producer waits for the publish.

| Field | Definition |
| --- | --- |
| `atRps` | The peak the formulas are evaluated at (req/s). |
| `latencyMs.mean` | Mean over the request mix, from the sums above. |
| `latencyMs.p95` | **Rough approximation**: a lognormal per request path with that path's mean and variance, mixed by traffic share. `latencyMs` is null when a part on the path is overloaded or its load depends on the run. |
| `busiest` | The part with the highest utilization: `id`, `label`, `utilization`, `limitedBy` (`slots`, `cpu` or `rate limit`). |
| `overloaded`, `saturationRps` | Whether any part is at a `load` of 1 or more, and about where the first part reaches a limit (its slots, CPU or a rate limit): peak / `load`. A queue whose consumers fall behind is overloaded but does not slow its producers. |
| `summary` | "p95 ≈ 97 ms; busiest API Servers 70%", or "Overloaded at 1,600 req/s: Fulfillment needs 1145% of its capacity; it saturates at about 140 req/s." |
| `parts[]` | Per part: `arrivalRps`, `slots`, `pools`, `meanServiceMs`, `meanHoldMs`, `utilization`, `slotUtilization`, `cpuUtilization`, `rateLimitShare`, `load`, `limitedBy`, `waitProbability`, `meanWaitMs`, `meanResidenceMs` (waiting, working and its own calls), `perRequestMs` (wait and own time per client request), `onPath`, `status` (`ok`, `busy` from a load of 80%, `overloaded`, or `unknown` with a `note`). |
| `paths[]`, `breakdown[]` | Mean latency per client and request type; where a request's time goes (comparable with `explain.latencyBreakdown`). |
| `notes` | What it leaves out, and how it was computed. |

Not included: retries, timeouts, faults, row-lock waits, buffer-cache misses, cold starts, GC
pauses, autoscaling delays and the memory model. On the starter the estimate is within 7% of the
simulated mean and p95 and within a point of its busiest part's utilization; on the queueing lab
(an M/M/4 queue) within 5%. archsim-engine's `EstimateAgreementTests` keeps both within 15%,
and the estimate gives the frozen engine 0.16.0's figures on every golden entry and recorded
case, but for the CPU of a database that flushes its commits, a cache's slots and CPU, and what
follows from them (`EstimateDepartures`).

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
| `saturated` | utilization >= 95%, or >= 1% of arrivals rejected, or the queue kept growing (mean queue in the last quarter of the window > max(2, 1.5 x first quarter + 1); needs at least 4 window buckets). Calls refused while a planned outage has the part down do not count: they are the outage, not overload (its status reasons say how many it refused) |
| `hot` | utilization >= 85% |
| `warm` | utilization >= 70% |
| `ok` / `idle` | otherwise / no arrivals |

Findings (`RunAnalysis`) are rule-based and cite the numbers they use:

- **Primary bottleneck:** the saturated node with the highest rejection rate and utilization,
  excluding *symptoms* (rejections exclude calls refused by a planned outage). A saturated or hot node is a **symptom** when a downstream node is
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
- **Fault impact** (one per planned fault): success and p95 before and inside the window, and
  how long recovery took. For an outage it also says how many calls the part refused while
  down and how many client requests failed there rather than carrying on without it. After a
  cache's outage the advice asks only for the fallback its callers lack (carry on when a lookup
  fails, read the data store on a miss); when they have both, it asks for the capacity the
  fallback's reads need, or a replicated cache. The design review's advice on a cache without
  a fallback likewise asks only for what the part lacks.
- **Hot shard:** one shard gets well over its fair share of keyed operations (scattered
  searches load every shard evenly and do not count), with its estimated load from connections
  or, when CPU is set, its cores (only the primary when reads go to the primary).
- **Cache** hit ratio and the read rate reaching the data store: its misses, and the reads it
  refused with what became of them (read from the data store by callers that carry on and read
  on a miss, failed otherwise).
- **Healthy** when nothing is saturated, naming the busiest component.

Comparisons (`RunComparer`) diff the two *resolved input snapshots* (so they explain actual
parameter changes), compute KPI deltas with a 3% "about the same" band, report bottleneck
shifts and the largest utilization/load/hit-ratio changes, and warn when seeds or engine
versions differ.

## Determinism

- Same document + scenario + seed + engine version ⇒ identical `resultDigest`. The Java
  simulator, which every run and replay uses since stage 4 of the port, gives it **on every
  platform**: it takes its logarithms, exponentials and powers from `StrictMath`
  ([ADR-0050](adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)). The frozen Python
  engine, deleted at stage 5, gave it on the same platform and Python version; across platforms
  its floating-point library could change low-order digits, and results then agreed within
  statistical noise.
- Changing one parameter can change random samples elsewhere, because events are processed in
  a different order and streams are consumed in that order. Scenario comparisons are therefore
  not perfectly paired. Differences of a few percent may be noise; re-run with other seeds to
  check.
- Engine semantics changes bump the engine's version (`EngineVersions.ENGINE`), which every
  report records.

## Sampled mode (opt-in)

A sampled run (`SimulationMode.SAMPLED`, `archsim run --sampled`) answers a long steady run (a
constant rate, no scheduled faults) from its warm-up plus a steady sample of at least 10 s and
50,000 arrivals, when the sample is no more than half of the run. The sample must be stationary: the
measured window's two halves agree on throughput (within 3%) and p95 (within 15%), the system keeps
up with at least 97% of the offered load, and no queue, backlog or replica count is still growing.
Otherwise the whole run is simulated. A sampled report carries `simulationMode` (the requested and
simulated durations, the checks, a note); its counts and time series cover the sample, and its
rates, success and percentiles stand for the whole run. It is deterministic like any run. Validation
against full runs: [performance.md](performance.md).

The API and the web app call it a *quick check* (`mode: "sampled"`, Simulate menu → "Quick check
(estimated)"). It is offered only when `QuickCheck.of(workload)` (in `/analyze` as `quickCheck`) says
the design qualifies, and otherwise says why. When a sample turns out not steady, the whole run is
simulated and `simulationMode` is `{kind: "full", requestedMode: "sampled", note}`. The web app
labels a sampled report "Estimated" and its charts say they cover only the sample.

## Seeds and seed groups

One run answers for one random sequence. A run with a seed of its own (`RunRequest.withSeed`)
replaces the workload's seed (after the scenario is applied), so a run of seed N reproduces exactly:
the report's input, and so its `inputDigest`, carries the seed used. The API's seed groups run one
document with seeds base to base + N - 1, and `SeedSummary` (in the engine's `seeds` package) reads
them together: the verdict split ("Meets the objectives in 4 of 5 runs"), p50/p95/p99, success and
throughput ranges with their medians. Runs that disagree on the verdict are *borderline*; the web
app shows an amber Borderline and caps the confidence at medium, and offers "Check 5 seeds" whenever
a single run's p95 is within 15% of its objective.

## Validation against queueing theory

archsim-engine's `QueueingTheoryTests` and `StarterReferenceTests` (in `simulator`) check the
simulator against exact results, with the tolerances the frozen engine 0.16.0 was held to:

- A service with Poisson arrivals and exponential service is an M/M/c queue. The simulated
  mean response time matches the Erlang C formula within 8% for M/M/1 at 50% and 80% load and
  M/M/4 at 80% load, and utilization matches `lambda / (c * mu)` within 5%.
- The M/M/1 median response time matches `ln 2 / (mu - lambda)`.
- Little's law (`L = lambda * W`) holds per node in the starter example.
- The starter example's scenarios stay inside documented ranges and directions (fewer
  replicas ⇒ rejections and higher latency; more replicas ⇒ lower utilization).

The Java simulator replays that engine's runs figure for figure
([java-parity.md](validation/java-parity.md)). These checks pin down the queueing core. They say
nothing about whether your service-time assumptions match your production system.

## Limits

Runs are bounded by an expected-request limit (default 2,000,000, checked before starting),
a wall-clock limit (default 300 s) and cancellation, both checked every 4,096 processed events
inside the event loop (so one request of a heavily fanned-out design cannot run past them), an
amplification limit, 200 nodes, 1,000 edges, queue capacities up to 100,000 per pool, at most 5
attempts per call and 600 s of drain. Reports count the events processed
(`provenance.eventsProcessed`).

**Amplification** (`design.amplification`): services call their dependencies one after another,
so fan-out multiplies along a path, and retries multiply it again. Validation computes the
worst-case number of calls between parts one client request can cause: one call into a part
costs 1 plus, for a service, cache or client, the sum over its links of (attempts x what one
call there costs) (it calls every dependency); for a load balancer or queue, the most expensive
link (one target per call). Above 10,000 the document is rejected, naming the path that drives
it; above 1,000 there is a warning. The limit is a `SimulationLimits` setting
(`max_amplification`). Example: 11 layers of 3 services, each calling every service of the next
layer, cause 88,575 calls per request.

Execution speed is roughly 5,000-10,000 simulated requests per second on a laptop, depending
on graph depth.

## Calculations behind the numbers (`explain`)

Every node in the report carries `explain`: formulas written out symbolically, again with the
run's own numbers substituted, and the result. Generic entries come from queueing theory
(arrival rate, capacity from slots, utilization ρ = λ·H / (1000·c), Little's law, and the M/M/c
waiting time through the Erlang C formula); components add their own (buffer cache hit ratio,
the hottest row's lock, group commit, replica replay capacity, DynamoDB partitions and unit
costs, fan-out, single-threaded command capacity, effective consumers, event-loop capacity, GC
time, JIT warm-up, serverless instances needed). The predictions are the textbook models; the
simulation does not use them, so the two can be compared. `report.explain` adds system
formulas (throughput, success rate, retry amplification) and `latencyBreakdown`: the mean time
a successful request spends in each component, excluding its downstream calls, on the network
and backing off.

## Request traces

Every n-th request (about 1,200 per run, chosen by request number so no random numbers are
drawn) records spans: each network leg, the time in each component, and inside it the waits
(worker or connection queue, tickets, row locks, publisher blocking, backlog) and work
(processing, query, log flush, replica acknowledgements, GC pauses, cold starts, snapshot
forks), plus markers for cache hits and misses, stale reads, rejections and timeouts. The
report keeps about 30: evenly spread ones, the 8 slowest, the median and up to 8 failures
(`report.traces`), each span relative to the request's start with its call depth.

## What is NOT modeled

- Disk and network bandwidth contention; concurrent GC CPU overhead; CPU interference between
  pods on one node (a pod's CPU is its own once scheduled), and between parts of one machine
  unless the design names it (`architecture.host`). On a host, a thread's speed is set when its
  piece of work starts (a sibling that starts later does not slow it, one that stops does not
  speed it up: the two errors offset on average), and threads are pooled over the machine:
  which part shares a core with which is not modelled.
- CPU per request that changes with load beyond the measured range: `serviceTimeByLoad` keeps
  its last point's factor at higher rates, so a service measured only at light load is charged
  that load's cost up to saturation, although measured stacks often keep getting cheaper (the
  cache-aside benchmark's Node.js service: 0.21 ms at 1,335 req/s, the top of its calibration,
  and 0.16 ms at 4,850). Calibrating to a higher load is the remedy: the benchmarks' protocol
  v6 (W26) climbs to about two thirds of the core. W26's curve extension (`CurveExtensions`) can also add a last
  point along a clear measured fall (experimental, off by default;
  [calibration.md](calibration.md#cpu-and-memory)); the engine itself never extrapolates a
  table. Without the setting a request costs `cpuShare` x its service time at any rate. CPU
  spent outside the service time (more than 1 x the service time) is not counted either.
- Health checks and automatic failover, deployments and rollouts; faults are the three kinds
  above (outage, slowdown, link latency), scheduled by you, not random failures.
- Autoscaling cooldowns and stabilization windows beyond the simple rules above; node
  provisioning outside Kubernetes clusters.
- Kubernetes: pod priority and preemption, affinity and anti-affinity, taints and
  tolerations, topology spread, several node pools per cluster, node failures and drains,
  rolling deployments, CPU limits and throttling (requests only), ResourceQuotas.
- Closed-loop users with think time; client connection pools; HTTP keep-alive and TLS setup.
- Parallel fan-out calls between services (dependencies are sequential; database scatter
  reads are the exception), streaming, consumer group rebalancing.
- Deadlocks, range (gap) locks, lock escalation, vacuum and compaction, query plans,
  rebalancing of shards, adaptive capacity.
- Cache eviction policies other than LRU and noeviction, request coalescing / thundering herd,
  cache replicas.
- Circuit breakers other than Envoy-style request limits on a load balancer, priority queues,
  deadline propagation between services.
- Independent workloads per client part (several client parts share one workload by their
  traffic shares), regions and cross-region latency.
- Packet loss, bandwidth limits and payload sizes.

If one of these dominates your system, the estimates will be off in ways the model cannot
show you.
