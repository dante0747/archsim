# Template review

The ten built-in templates (`examples/*.json`) are the first designs most people simulate, so a
modelling mistake in one of them is the fastest way to lose the engineers ArchSim wants to
convince. The October 2026 inspection found one (H-4: the starter modelled Redis as reading
PostgreSQL by itself). This page records the audit of all ten that followed (workstream W5): what
was checked, what changed and why, and a line for outside experts to sign.

How the numbers were made: engine 0.12.0 (0.11.0 plus the analysis changes below), prices from the AWS
us-east-1 snapshot of 2026-10-27, each template's own seed. Every scenario was simulated with
`uv run archsim run examples/<template>.json --scenario <scenario>`; the tables come straight from
those reports. Rerun them to check any number here.

## How to review a template

1. Read the template's section below, then open the template and press Simulate on each
   scenario (or run the command above).
2. Go through the checklist and the "For reviewers" questions. Judge the design as you would a
   design review at work: would you build it like this, and do the numbers look like your
   production systems?
3. If you agree, send your "Reviewed by" line (name, role, date) back to whoever shared this
   checklist with you. It is added here, and the template's card then shows "Reviewed by ..."
   (`reviewedBy` in `apps/web/src/components/templates/templates-meta.ts`).
4. If you disagree, reply with what you would change instead.

Nothing is signed yet.

## Checklist (applied to every template)

- **Request path.** Each link carries only the request types that take it, and a service calls
  its dependencies in the order real code would (write the database, then invalidate the cache;
  write the order, then charge the card).
- **Cache pattern.** Redis, Valkey and Memcached cannot read a database themselves, so they are
  cache-aside: the service looks the key up, reads the data store itself on a miss
  (`readsOnMiss`) and writes the value back, and a failed lookup counts as a miss
  (`continueOnFailure`), so reads fall back to the store. Writes invalidate the key when they
  change what is cached. Only CDN- or Varnish-style caches read through.
- **Pools, queues and timeouts.** Pool sizes are what the technology would really run with. A
  timeout times its attempts, plus backoff, fits inside the caller's own timeout. Queues do not
  wait longer than the client does, unless the template is about exactly that.
- **Retries and fallbacks.** Retries only where the operation is idempotent (or carries an
  idempotency key), within the budget above.
- **Sizes and prices.** Each part is plausible on the AWS instance type it is priced as, for the
  load it gets.
- **Copy.** The description, the scenario names and descriptions, and the card's one-line insight
  (`templates-meta.ts`) say what the simulation shows. Each was checked against the runs.

## Engine changes made during the audit

These came up while reading the templates' results. Each has a test.

- **A planned outage is not the bottleneck.** Calls a part refuses while a planned outage has it
  down no longer count towards "saturated". Results used to call a Redis outage "the primary
  bottleneck" although Redis was 2% busy. The outage's own finding now says what it did: how many
  calls the part refused, and how many client requests failed there rather than carrying on
  without it. (`analysis.py`, `findings_dynamics.py`; tests in `test_dynamics.py`.)
- **Scattered searches do not make a hot shard.** An Elasticsearch-style search runs on every
  shard but is counted once, on shard 0, so the shared-pool template reported "Search index shard
  0 gets 100% of requests". (`findings_data.py`; `test_technology.py`.)
- **A CPU-bound hot shard is estimated from its CPU.** The hot-shard estimate counted the
  connections of every replica. With CPU set, it now uses the cores of the instances that serve
  the shard (only the primary when reads go to the primary, MongoDB's default). A database's CPU
  capacity likewise no longer counts replicas that serve no reads.
- **Wording.** A part that is hot on CPU said "running hot (20% busy)"; it now says "CPU 86%
  busy". A queue whose backlog grew says so instead of "running hot".

## Cache-aside web API (starter): `cache-aside-starter`

Clients, a load balancer, two API replicas, Redis and PostgreSQL at 400 req/s.

**Checked.** Redis is cache-aside since the H-4 fix: the API looks reads up (250 ms timeout,
carries on if Redis fails), reads PostgreSQL on a miss and writes the value back; writes go to
PostgreSQL first, then invalidate the key. Timeouts nest: the client's 2 s covers the API queue
(500 ms) plus the PostgreSQL link (1.5 s, above the database's 1 s pool wait). On AWS: load
balancer c6g.medium, API 2 x c6g.xlarge, Redis cache.m6g.large, PostgreSQL db.m6g.large.

Losing Redis for 15 s from the chaos pads: success is 97.6% over the run and 91.9% inside the
window. Results used to call Redis "the primary bottleneck"; it now names the API (every read goes
to PostgreSQL, so its workers wait longer and its queue fills) and says Redis refused 10,426 calls
while down, with no request failing there (see the engine changes).

**Changed.** Nothing in this audit.

**For reviewers.** Four worker threads per 4-vCPU API replica is low for thread-per-request; it is
what makes one replica saturate at 400 req/s, the beginner lesson. Is an 80% hit ratio with a
60 s TTL a fair default for a web API?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (2 API replicas, 80% hits) | 100% | 91 ms | No component is saturated | $449 |
| 1 API replica | 71.9% | 270 ms | API Servers is the primary bottleneck | $350 |
| 3 API replicas | 100% | 89 ms | No component is saturated | $548 |
| Cache hit ratio 20% | 99.4% | 292 ms | API Servers is the primary bottleneck | $449 |
| Cache hit ratio 95% | 100% | 87 ms | No component is saturated | $449 |
| 10 s burst to 900 req/s | 86.4% | 249 ms | API Servers is the primary bottleneck | $449 |

Reviewed by: ______________________ (name, role, date)

## Black Friday on Kubernetes: `black-friday-kubernetes`

Checkout behind Kong with HPA autoscaling, a Redis catalog cache, PostgreSQL with two read
replicas and a payments API, under a ramp from 300 to 1,500 req/s.

**Checked.** Request paths (reads: catalog cache, then PostgreSQL on a miss; writes: the order
row, then the payment), the cache pattern, payment timeouts and retries, the HPA (3 to 14 pods,
60% target, 15 s interval, 20 s pod start), the rate limit, replica lag.

**Changed.**
- *Redis read PostgreSQL by itself (read-through), the H-4 mistake.* Now cache-aside, and a
  failed lookup counts as a miss. Before, "Lose the cache for 20 s" failed every catalog read
  (success fell to 21% in the window) although its description said reads fall through to
  PostgreSQL; now they do, and the system absorbs the outage.
- *The payment retry could not fit in the client's timeout:* 2 attempts x 2,000 ms + 200 ms of
  backoff is 4.2 s against the shoppers' 3 s. Now 2 x 1,200 ms + 100 ms (2.5 s); the payments
  p99 is about 1.1 s. The link's label says the retry needs an idempotency key.
- *PostgreSQL replicas lagged 18 s at about 190 writes/s.* The engine's default replays a write
  on one worker at half its execution time (4.5 ms of a 9 ms checkout), far slower than
  PostgreSQL's WAL replay, which skips parsing, planning and client round trips. Replay is set
  to 0.15 of the write time (about 1.4 ms) here.
- *Copy.* The description promised the gateway's rate limit "protecting everything behind it",
  but the limit (2,000 req/s) is above the peak (1,500), so it never acts. "Gateway rate limit
  900 req/s" claimed to "shed load at the edge instead of queueing it"; it turns away one shopper
  in six that the autoscaled checkout could have served, and the queueing while pods start is
  unchanged. Both now say what happens.

**For reviewers.** The baseline misses its 500 ms p95 objective while the pods catch up (about
40 to 65 s into the ramp). Is a 20 s pod start fair for a Go service? Is a 3 s client timeout
realistic for a checkout that calls a payments provider?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (HPA 3→14 pods) | 98.8% | 561 ms | Checkout API is the primary bottleneck | $1,129 |
| No autoscaling (3 fixed pods) | 64.4% | 818 ms | Checkout API is the primary bottleneck | $1,001 |
| Payments provider 5× slower (60–90 s) | 78.3% | 802 ms | Checkout API is the primary bottleneck | $1,146 |
| Lose the cache for 20 s | 98.6% | 596 ms | Checkout API is the primary bottleneck | $1,134 |
| Gateway rate limit 900 req/s | 82.2% | 584 ms | Checkout API is the primary bottleneck | $1,127 |

Reviewed by: ______________________ (name, role, date)

## IoT telemetry on Cassandra: `iot-cassandra-quorum`

Devices write telemetry through Envoy to a Go ingest service and a 6-shard Cassandra ring with
replication factor 3, at 2,500 req/s, 95% writes.

**Checked.** The consistency model (an operation waits for the fastest, second-fastest or
slowest of three independently sampled replica times), device retries (one retry; Cassandra
writes are idempotent upserts, so it is safe), timeouts (devices 1 s, ingest to ring 500 ms,
equal to the ring's queue timeout), pool sizes.

**Changed.** Copy only.
- The card said "One slow replica decides how fast a quorum write can be". At QUORUM with three
  replicas one slow replica is masked: the second-fastest decides. Now: "At QUORUM the
  second-fastest of three replicas sets the latency; at ALL, the slowest."
- The description promised "what one slow replica does to quorum latency", but the chaos
  scenario slows the whole ring (a fault targets a whole part; the engine cannot slow one
  replica). It now describes the compaction storm it simulates.

**For reviewers.** At ALL the ring's CPU reaches 86% against 40% at QUORUM, because the engine
holds the operation's CPU for the slowest replica's time (see Follow-ups). In the compaction
storm, Ingest is named the bottleneck: its workers wait on the slow ring and its queue
overflows. Would you call the ring the cause?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (RF 3, QUORUM) | 100% | 12 ms | No component is saturated | $986 |
| Consistency ONE | 100% | 10 ms | No component is saturated | $986 |
| Consistency ALL | 100% | 48 ms | Telemetry (Cassandra) is running hot (CPU 86% busy) | $986 |
| Ring 3× slower (18–32 s) | 93.2% | 645 ms | Ingest is the primary bottleneck | $986 |

Reviewed by: ______________________ (name, role, date)

## Async order pipeline with Kafka: `kafka-order-pipeline`

A Node.js API reads order status from PostgreSQL and publishes new orders to Kafka; a fulfillment
service consumes them, writes PostgreSQL and sends a confirmation e-mail. 400 req/s with a 15 s
burst to 1,600.

**Checked.** Partitions against consumers, per-message work, the API's capacity in the burst,
retries (e-mail: 3 attempts; messages: 3 deliveries), timeouts.

**Changed.**
- *The API refused a quarter of all requests.* Node.js runs one event loop per process; with one
  process per replica, three replicas could do about 790 req/s against the 1,600 req/s burst, so
  24% of requests failed in every scenario. Now 4 processes per replica (cluster mode, one per
  vCPU). The template's own claim, "clients get fast acks", now holds.
- *Consumers could never keep up.* Each order takes about 230 ms (fulfillment's own work, the
  PostgreSQL write, a 120 ms e-mail call), one at a time per partition, so 4 consumers handled
  about 16 orders/s against 237 published: the lag grew for the whole run in every scenario, and
  no scenario could show anything. Now 10% of requests are orders (40/s, 160/s in the burst),
  with 16 partitions and 16 consumers: the lag peaks and drains after the burst.
- *One hot partition.* Orders were partitioned by the workload's Zipf-skewed key, so one
  partition took about 11% of all orders and never drained. Orders are keyed by order id now
  (key skew 0), so partitions get even shares.
- Fulfillment has 24 workers per replica (was 8), so 32 consumers are not capped by its threads.
- *Copy.* Scenario sizes follow (32 consumers on 16 partitions; 32 and 32). The slow-database
  scenario said clients "still get fast acks"; status reads go to PostgreSQL directly and some
  time out, which it now says.

**For reviewers.** Would you send the confirmation e-mail inline in the consumer, or hand it to
its own queue? Are 16 partitions a fair default for an orders topic at this rate?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (16 consumers, 16 partitions) | 100% | 24 ms | orders topic's backlog grew (consumers 85% busy) | $1,885 |
| 32 consumers (still 16 partitions) | 100% | 24 ms | orders topic's backlog grew (consumers 85% busy) | $1,885 |
| 32 partitions, 32 consumers | 100% | 24 ms | orders topic lag peaked at 492 messages | $1,885 |
| Database 4× slower (45–75 s) | 91.0% | 259 ms | During the 4x slowdown of Orders DB (45-75 s) success fell from 100.0% to 81.3% | $1,885 |

Reviewed by: ______________________ (name, role, date)

## Kubernetes spike: pods wait for nodes: `kubernetes-pods-pending`

A Java API on a two-node cluster with the HPA and the cluster autoscaler; traffic jumps from 120
to 450 req/s for 220 s.

**Checked.** Pod size against node size (0.5 vCPU and 1 GiB pods, 3 per 2-vCPU, 8 GiB node, priced
like an m5.large at $0.096 an hour), the HPA (60% target, 15 s), node provisioning (120 s), image
pull (10 s), Java JIT warm-up on new pods, the cache pattern.

**Changed.**
- Redis read PostgreSQL by itself; now cache-aside, and product updates invalidate the key.
- The "Headroom" scenario said new pods "schedule at once"; most do, but the HPA's own reaction
  time, JIT warm-up and the last pods waiting for a sixth node still refuse requests. It says so.

**For reviewers.** At the 120 req/s base load the 0.5 vCPU pods already run at about 80% CPU, so
the HPA grows the API from 3 to 5 pods before the spike. Is 0.5 vCPU a fair request for a Java
service like this?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (2 nodes, 2 min per new node) | 77.9% | 1,290 ms | Catalog API is the primary bottleneck | $692 |
| Headroom: 5 nodes up front | 93.5% | 1,168 ms | Catalog API is the primary bottleneck | $907 |
| Fast node provisioning (40 s) | 89.7% | 1,215 ms | Catalog API is the primary bottleneck | $832 |
| No cluster autoscaler | 66.4% | 1,239 ms | Catalog API is the primary bottleneck | $624 |

Reviewed by: ______________________ (name, role, date)

## Shared node pool: a greedy neighbour: `kubernetes-shared-pool`

Search (Java, HPA at 30% CPU) and Checkout (Go, HPA at 60%) share four fixed nodes; traffic jumps
from 100 to 450 req/s.

**Checked.** Pod and node sizes (1 vCPU pods, three per 4-vCPU node), both HPAs, the request
split (reads to Search and Elasticsearch, writes to Checkout and PostgreSQL), each scenario's fix.

**Changed.**
- *The "normal target" fix moved the failures instead of removing them.* With 16 ms of Search's
  own CPU per request, the four nodes could not hold both services even at a 60% target, so
  "Search targets 60% CPU" just shifted the rejections from Checkout to Search (12% of searches).
  Search's own work is now 8 ms (median; the Elasticsearch query is separate). The greedy 30%
  target still starves Checkout, and each fix gives it room.
- The hot-shard finding no longer misfires on the search index (engine change above).

**For reviewers.** Every scenario misses the 300 ms p95 and 99% availability objectives, because
of the first seconds of an instant 4.5x spike before any HPA reacts. Should the objectives, or
the spike, be gentler so the fixes show as a pass?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (Search targets 30% CPU, 4 fixed nodes) | 92.0% | 977 ms | Checkout is the primary bottleneck | $1,271 |
| Search targets 60% CPU | 98.7% | 977 ms | Checkout is the primary bottleneck | $1,271 |
| Cap Search at 8 pods | 98.7% | 903 ms | Checkout is the primary bottleneck | $1,271 |
| Twice the nodes (8) | 98.5% | 958 ms | Checkout is the primary bottleneck | $1,720 |
| Cluster autoscaler on (up to 10 nodes) | 95.8% | 958 ms | Checkout is the primary bottleneck | $1,473 |

Reviewed by: ______________________ (name, role, date)

## Product catalog on sharded MongoDB: `mongodb-hot-shard`

A Node.js catalog API, Redis cache-aside and a 4-shard MongoDB cluster at 2,800 req/s, 95% reads,
product popularity Zipf s = 1.2.

**Checked.** What limits a shard, shard topology, read preference, the cache pattern, the API's
capacity.

**Changed.**
- *The API, not the shard, was the bottleneck.* One event loop per Node.js replica refused 30%
  of requests in every scenario, so the hot shard never showed and all four scenarios gave the
  same result. Now 4 processes per replica.
- *Shards were limited by a 3-connection pool.* Production shards are limited by CPU: 100 pooled
  connections, 2 vCPU per member. Shards were also single nodes; they are three-member replica
  sets now (reads still go to the primary, MongoDB's default).
- *Load 2,000 to 2,800 req/s,* so the hot shard (37% of requests) needs about 116% of its
  2 vCPUs while the others need 57 to 77%.
- Redis read MongoDB by itself; now cache-aside, and product updates invalidate the key.
- *Copy.* "The other three sit idle" is now "have room to spare". The baseline cache is not
  bypassed: every read pays a lookup and a write-back, so the scenario says every read misses.
  "8 shards" said the hottest keys "still land together"; it copes, at half again the cost, with
  its hot shard still at twice its fair share.

**For reviewers.** Half of each query's time on CPU (the engine's default `cpuShare`) is an
assumption; is it fair for in-memory catalog reads?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (4 shards, skewed keys, every read misses the cache) | 94.0% | 508 ms | Products (MongoDB) is the primary bottleneck | $1,266 |
| High-cardinality shard key | 100% | 35 ms | No component is saturated | $1,266 |
| 8 shards | 100% | 32 ms | Hot shard: Products (MongoDB) shard 1 gets 28% of requests | $1,950 |
| Redis in front (90% hits) | 100% | 19 ms | No component is saturated | $1,266 |

Reviewed by: ______________________ (name, role, date)

## Queueing lab: one service under load: `queueing-lab`

One service with four workers and exponential 10 ms service times: an M/M/4 queue.

**Checked.** The scenarios against Erlang C: at 50% load the mean response is 10.9 ms (the run's
median is 10.1 ms), at 90% it is 28.6 ms (median 26.6 ms); at 110% an unbounded queue times every
client out, and a 200 ms queue timeout keeps 91% succeeding.

**Changed.**
- *Auto-sizing priced the toy as a 64 GiB memory-optimised server* (x2gd.xlarge, $244 a month),
  because its deliberately unbounded queue could hold 100,000 requests at 2 MiB each. Auto-sizing
  is off for this template, so CPU and memory are not modelled and it behaves exactly like the
  textbook queue (results unchanged: the auto size had 4 vCPU for 4 workers).
- The baseline scenario had no description.

**For reviewers.** Does the 110% pair make the case for queue timeouts clearly?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| 50% load (200 req/s) | 100% | 34 ms | No component is saturated | $73 |
| 90% load (360 req/s) | 100% | 86 ms | Service (4 workers) is running hot (91% busy) | $73 |
| 110% load, unbounded queue | 0.0% | n/a | Service (4 workers) is the primary bottleneck | $73 |
| 110% load, queue timeout 200 ms | 90.8% | 221 ms | Service (4 workers) is the primary bottleneck | $73 |

Reviewed by: ______________________ (name, role, date)

## Retry storm with a TTL cache: `retry-storm-ttl-cache`

A catalog API with Redis (TTL-derived hit rate) and PostgreSQL; mobile clients with a 400 ms
timeout and three attempts; a burst from 900 to 2,400 req/s for 15 s.

**Checked.** The deliberate anti-patterns (they are the lesson): aggressive client retries, an API
queue with no wait limit and a database queue that waits 2 s, longer than any client. The cache
pattern, the TTL model.

**Changed.**
- Redis read PostgreSQL by itself; now cache-aside, writes invalidate.
- *Copy.* The scenarios suggested retries make the burst worse. They more than double the
  attempts reaching the API during the burst, but success is a little higher with them (85% against
  82%): rejections are fast and cheap, and a later attempt often gets through. What retries cost
  is load and latency: the p95 of successful requests is about 670 ms with retries and 385 ms
  without. The description, the scenarios and the card now say that.

**For reviewers.** The model recovers as soon as the burst ends; retries do not keep it
overloaded afterwards (no metastable failure). Would you expect them to, with these settings?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Burst, 3 attempts per request | 85.3% | 671 ms | Catalog API is the primary bottleneck | $2,587 |
| Burst, no retries | 82.1% | 385 ms | Catalog API is the primary bottleneck | $2,587 |
| TTL 3 s | 69.1% | 608 ms | Catalog API is the primary bottleneck | $2,587 |
| Steady 900 req/s | 100% | 164 ms | No component is saturated | $2,587 |

Reviewed by: ______________________ (name, role, date)

## Serverless API under a burst: `serverless-burst`

API Gateway, a Python Lambda-style function (concurrency limit 100) and a DynamoDB table; 150
req/s with a one-minute spike to 1,500.

**Checked.** How the engine models DynamoDB (capacity units, partitions from capacity, 300 s of
burst credits), the function's concurrency and cold starts, retries at two layers (the client and
the SDK's call to the table).

**Changed.**
- *The table could never throttle.* On DynamoDB the engine meters capacity units and derives
  partitions from capacity (8 for an on-demand table), so `shards: 4` and the 250 ops/s per-shard
  limit never applied, and "Provisioned throughput x3" changed nothing. The table now has
  provisioned capacity sized at about twice the base load (100 RCU, 60 WCU).
- *A 15 s burst cannot exhaust 300 s of burst credits* on a table provisioned for its base load,
  on AWS or in the engine. The spike now lasts a minute (30 to 90 s of a 100 s run): write
  credits run out about 46 s in, then writes are throttled, and the throttling cascades: retried
  writes hold function instances longer, so the function hits its concurrency limit too.
- *Scenarios.* "Provisioned capacity x3" raises the table's units; "On-demand capacity" is new;
  "Concurrency limit 1,000" now says the table then takes the whole spike and throttles harder.

**For reviewers.** Is 100 a realistic reserved concurrency here? Would you size provisioned
capacity at twice the base load, or use on-demand for a spiky API?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (concurrency 100, 100 RCU / 60 WCU) | 91.7% | 164 ms | Sessions table is the primary bottleneck | $1,922 |
| Concurrency limit 1,000 | 94.3% | 98 ms | Sessions table is the primary bottleneck | $2,080 |
| Provisioned capacity x3 | 100% | 93 ms | No component is saturated | $1,713 |
| On-demand capacity | 100% | 93 ms | No component is saturated | $1,713 |
| No burst | 100% | 91 ms | No component is saturated | $478 |

Reviewed by: ______________________ (name, role, date)

## Follow-ups outside this audit

- **DynamoDB cost.** The table is priced as an instance at the catalog's illustrative $0.35 an
  hour, not per capacity unit. The serverless template's 100 RCU and 60 WCU cost about $38 a month
  on AWS, not $255 (`cost.py`).
- **Consistency level and CPU.** The engine holds a Cassandra-style operation, and its CPU share,
  for the k-th fastest replica's time, so ALL costs more CPU than ONE. A real replica does the
  same work whatever level the client asks for; only the coordinator waits longer.
- **Rate limiting reads as overload.** A gateway that rate-limits is "saturated" and becomes "a
  consequence of" the service behind it (Black Friday, "Gateway rate limit 900 req/s"), although
  the limit is a policy, not a symptom.
- **docs/performance.md** lists each template's request count and run time; the Kafka, MongoDB
  and serverless templates now generate different loads, so its table needs a fresh run.
