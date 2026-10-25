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
    none; illustrative). CPU work running when a pause starts is delayed by the pause.
  - JIT warm-up: replicas started during the run (autoscaling) and new serverless instances run
    `warmupFactor` times slower at first, fading linearly over `warmupS` (Java 2.5x over 45 s,
    .NET 1.5x over 20 s; illustrative).

### Cache (`cache`), cache-aside

- Every command (lookup, invalidation, repopulation) runs on a pool of executors per shard
  taking `serviceTimeMs`, with its own queue. **Redis and Valkey execute commands on one thread
  per shard** whatever `concurrency` or the CPU says (`singleThreaded`, auto); Memcached, CDN
  and Varnish run `concurrency` executors in parallel (Memcached's default is 4 threads).
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
- **Misses** continue through the `miss` port (reads only, at most one link) to the data
  store. The miss path's latency counts toward the caller's request. The cache executor is
  *not* held while the data store works.
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
`archsim_engine/technology.py`, which lists every default with its documentation link):

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
  consumers idle (`partitionLagMax`). `none` is one shared FIFO with competing consumers.
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
and `costPerMillionRequests` (third-party APIs) never affect the simulation. The web app
turns them into a monthly estimate (730 hours) and a cost per million successful requests,
using the run's average replica count for autoscaled services and the measured call rate for
third-party APIs.

### Resource specs (CPU, memory, storage)

Services, databases, caches, load balancers and message queues can carry `cpuCores`,
`memoryGib` and (databases, queues) `storageGib` per instance (per replica, per database
instance, per cache node, per broker). Every spec is optional: left empty it is not modelled
and the component behaves exactly as before. Set, it has these effects:

| Component | CPU cores | Memory | Storage |
| --- | --- | --- | --- |
| Service | `serviceTimeMs` is CPU work: at most `floor(cpuCores)` handlers per replica run it at once, the rest wait for a core; below 1 core the work runs `1 / cpuCores` times slower. Serverless: only a fraction below 1 matters (one request per instance). | Each request a replica holds (queued, running, or waiting on downstream calls) uses `memoryPerRequestMb` on top of `memoryBaselineMb`; a request that would exceed `memoryGib` is refused (`out_of_memory`). | - |
| Database | `cpuShare` of each query's time needs a core (the rest is I/O, locks, network); queries queue for cores per instance. | With `workingSetGib`: 75% of memory is buffer cache; reads are served from memory with probability `min(1, 0.75 x memoryGib / (workingSetGib / shards))`, the others add `diskReadMs`. | Checked against `dataSizeGib / shards` per instance (error when it does not fit, warning above 85%). |
| Cache | Command execution queues for cores when `concurrency` exceeds them. | TTL model: 80% of memory holds keys of `itemSizeKb`; when full, the least recently used key is evicted. Fixed ratio: a warning when the workload's keyspace does not fit. | - |
| Load balancer, message queue | Shown and priced only. | Shown and priced only. | Shown and priced only (queues). |

Whole cores above one give parallelism, a fraction keeps total capacity (2.5 vCPU = two
executors at 1.25x). With as many whole cores as handlers nothing waits and results are the
same as without the spec. Reports add `cpuCoreUtilization` (busy executors / executors in the
window), `memoryPeakGiB` (services), `bufferHitRatio` (databases) and `capacityKeys` /
`keysCached` plus an `evictions` counter (caches). Not modelled: garbage collection, OOM kills
and restarts (memory is a refusal limit), page-cache dynamics, write I/O, network bandwidth.
Specs also feed the calculations around a run:

- **Capacity figures**: `capacityRps` is the lowest ceiling of the slots (`slots / meanHoldMs`),
  the CPU (`cpuCores x instances / CPU ms per request`) and, for services, the memory (requests
  that fit x replicas / `meanHoldMs`); `capacityLimit` says which (`slots`, `cpu`, `memory`) and
  bottleneck findings name it. `serviceOnlyCapacityRps` also respects the CPU ceiling.
- **Cost estimate (web)**: a part's price per instance-hour is the price set on it; without one,
  its size at illustrative on-demand rates ($0.037 per vCPU-hour, $0.0028 per GiB-hour, $0.08 per
  GiB-month of storage); without a size, the catalog default. A price equal to the catalog
  default counts as not set (saved runs store every default). The cost table marks size-priced
  parts.
- **Design review (web, before a run)**: a static estimate of the peak load at each part (peak
  rate split by read/write mix and client share, spread over load-balancer targets by replicas,
  cache misses at the fixed hit ratio) is compared with its CPU: high when the CPU would be over
  100% busy, medium above 80%. It also flags a database working set that does not fit in memory
  and a TTL cache smaller than the keyspace.
- **Exports (web)**: Kubernetes containers get `resources.requests` = `limits` from CPU and memory
  and StatefulSet volumes the storage size; Docker Compose gets `deploy.resources` limits and
  reservations. Parts without specs keep the previous placeholders.

## Validation rules

Documents are validated before simulation (`validation.py`); errors block the run and carry
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
| `retryAmplification` | Client attempts / client requests inside the window. |
| `failureBreakdown` | Failures of the measured cohort grouped by (status, reason, origin node, link). |
| `clients` | One entry per client group: `share` (fraction of the workload), the measured cohort's `arrived` / `succeeded` / `failed` / `timedOut`, `offeredRps`, `successRate`, `retryAmplification` and `latencyMs`. The fields above are the totals across groups. |

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
| `cpuCapacityRps` | CPU ceiling from the CPU spec: cores x instances / CPU ms per request (null when CPU is not set). |
| `serviceOnlyCapacityRps` | `slots / meanServiceMs`, capped by the CPU ceiling: the limit if dependencies were instant. |
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
  pods on one node (a pod's CPU is its own once scheduled).
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
- Multiple traffic sources, regions and cross-region latency.
- Packet loss, bandwidth limits and payload sizes.

If one of these dominates your system, the estimates will be off in ways the model cannot
show you.
