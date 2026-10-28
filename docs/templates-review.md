# Template review

The built-in templates (`examples/*.json`) are the first designs most people simulate, so a
modelling mistake in one of them is the fastest way to lose the engineers ArchSim wants to
convince. The October 2026 inspection found one (H-4: the starter modelled Redis as reading
PostgreSQL by itself). This page records the audit of the first ten that followed (workstream W5):
what was checked, what changed and why, and a line for outside experts to sign.

Five more were added on 28 October 2026 to show what the first ten did not: several client
groups with their own timeouts and retries, a circuit breaker and an egress proxy's route timeout,
a rate limit that acts on one client group, a third-party dependency that fills a thread pool,
PostgreSQL row-lock contention, Kafka dead letters and a hot partition key, SQS visibility
timeouts, RabbitMQ flow control, a network-latency fault, a hot key on a Redis Cluster shard
against Memcached's threads, Elasticsearch as a CQRS read model, and a design whose scenarios are
sizing options for the cost and speed map and the breaking point. Their sections
([below](#added-on-28-october-2026)) use the same checklist.

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

## Added on 28 October 2026

These five were built against this page's checklist rather than audited afterwards: every
scenario was simulated while the template was tuned, each description's numbers come from those
runs, and what was changed on the way is listed under "Tuned while building". Same conditions as
above: engine 0.12.0, the AWS us-east-1 snapshot of 2026-10-27, each template's own seed. Like the
others, they are illustrative designs; the costs will move when auto-sizing and instance selection
change (the stories should not).

## Event-driven CQRS with Kafka: `cqrs-kafka-read-model`

A Go command service commits orders to PostgreSQL and publishes them to a 12-partition Kafka topic
keyed by merchant; a Java projector indexes them into Elasticsearch, which a Node.js query service
searches. 1,000 req/s, a quarter of them orders, with a 15 s flash sale at 2,500 req/s in every
scenario.

**Checked.** Request paths (commands only write: PostgreSQL first, then the publish; queries only
read Elasticsearch), the partition key (the merchant, so each merchant's events stay in order),
consumers against partitions (12 and 12; the projector's 32 workers cover them), redelivery (3
deliveries, then the dead-letter count), timeouts (a command waits at most 500 ms in the queue, 1 s for
PostgreSQL and 500 ms for Kafka: together the users' 2 s), every search
scattered over Elasticsearch's 3 shards with one replica each.

**Tuned while building.**
- The flash sale is in the baseline, so auto-sizing sizes every part for its peak. As a scenario
  of its own, the burst ran into CPU the auto-sizer had not given the query service, which then
  became the bottleneck and hid the story.
- The hot merchant is Zipf 1.15. At 1.3 that merchant's row lock in PostgreSQL also saturated the
  command service during the sale (26 ms of lock wait per write), which mixed two stories; at 1.15
  the lock costs about 2 ms and only the partition falls behind.
- An Elasticsearch slowdown scenario became a projector slowdown: it failed searches but showed
  nothing about the asynchronous side that the outage scenarios do not already show.

**For reviewers.** The publish after the commit is a dual write; production would use a
transactional outbox, which the engine does not model. A failed delivery goes back to the end of
its partition, closer to a retry topic than to a consumer that retries in place: is that a fair
model of "gives up after three deliveries"? Is 48 partitions a fair "more partitions" reflex for
about 250 events a second?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (12 partitions, flash sale at 25–40 s) | 100% | 37 ms | No component is saturated | $3,111 |
| One merchant places a sixth of the orders | 100% | 38 ms | order-events topic's backlog grew (consumers 66% busy) | $3,111 |
| Hot merchant with 48 partitions | 100% | 38 ms | order-events topic's backlog grew (consumers 18% busy) | $3,547 |
| Projector 8× slower for 25 s (50–75 s) | 100% | 37 ms | order-events topic's backlog grew (consumers 91% busy) | $3,111 |
| Read model down for 15 s, the projector gives up | 87.7% | 37 ms | order-events topic's backlog grew (consumers 91% busy) | $3,111 |
| Read model down for 15 s, the projector retries with backoff | 87.7% | 37 ms | order-events topic's backlog grew (consumers 91% busy) | $3,111 |

What the request path does not show (the topic's numbers in the same runs): the baseline's lag
peaks at 3,334 events (p95 event age 7.5 s) and drains; the hot merchant's partition ends the run
with 1,524 events still waiting (p95 age 25.6 s); with 48 partitions the peak lag halves (2,390) but
the hot partition still ends 320 behind (p95 age 21.0 s); the projector slowdown peaks at 5,497
(p95 age 20.6 s); the outage dead-letters 2,870 events when the projector gives up and none when
it retries with backoff (peak lag 5,527). Inside the outage window success is 25%, which is the
orders: every one of them succeeds.

Reviewed by: ______________________ (name, role, date)

## Public API: three clients, one slow dependency: `public-api-slow-dependency`

A Java orders API (4 pods, 32 threads each) behind an ALB for the web and mobile apps and a Kong
gateway for partners, which enforces their plan's 450 req/s. Every new order is scored by a
third-party fraud API through an Envoy egress proxy, then committed to PostgreSQL; reads touch only
PostgreSQL. 1,500 req/s, a quarter of them orders.

**Checked.** Client groups (web 40%: one attempt, 10 s; mobile 35%: two attempts, 10 s, 500 ms
backoff; partners 25%: three attempts, 5 s, 200 ms backoff, the API-consumer defaults), the
timeout chain (1 s per fraud attempt, and Envoy's route timeout is also 1 s, so the proxy does not
hold calls the API has given up on; three attempts plus backoff is 3.3 s, plus the API's 1 s queue
wait, inside every client's timeout), the circuit breaker (64 calls in flight, half the API's
128 threads; the baseline needs about 40), failing open (`continueOnFailure` on the fraud call),
the partner quota against the partners' 375 req/s. The design review's one high finding, "Up to 9
attempts reach Egress proxy per request" (the partners' 3 attempts times the API's 3), is the
multiplication the scenarios are about.

**Tuned while building.**
- Partners first had a 3 s timeout, shorter than the API's own worst case for one order (3.3 s):
  the checklist's nesting rule. They now have the API-consumer default of 5 s.
- Partners first shared the public gateway, whose rate limit (one bucket for all clients) never
  acted. With a gateway of their own, the quota acts on the retries of exactly the clients that
  send them.
- The breaker is 64, not lower: at 32 the baseline's own in-flight calls (about 40) would trip it.

**For reviewers.** One attempt per fraud call helps much less than intuition says (46% succeed in
the window against 36%): is the Little's-law explanation in the scenario convincing? The breaker
scenario's headline names the partner gateway, because rate limiting reads as overload (see
Follow-ups). Would you fail open on fraud scoring, or queue unscored orders for review?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline | 100% | 264 ms | No component is saturated | $971 |
| Fraud API 8× slower (40–70 s), as drawn | 76.4% | 1,635 ms | Orders API is the primary bottleneck | $971 |
| Fraud API slow, one attempt per call | 80.0% | 1,572 ms | Orders API is the primary bottleneck | $971 |
| Fraud API slow, one attempt, circuit breaker | 92.1% | 298 ms | Partner gateway is the primary bottleneck | $971 |
| Fraud API slow, circuit breaker, fail open | 100% | 286 ms | No component is saturated | $971 |

Per client and inside the fault window: as drawn, 35.8% of requests succeed in the window; over the
run the web app keeps 72.8%, the mobile app 80.5% (1.27 attempts per request) and partners 76.6%
(1.59 attempts; the partner gateway turns away 32% of what they send). With one attempt per call
the window's success is 45.6%. With the breaker it is 79.3%: 89,307 of 89,559 reads succeed and
nearly every failure is an order. Failing open, every request succeeds and about 8,400 orders
(about three in four during the slowdown) are accepted without a score.

Reviewed by: ______________________ (name, role, date)

## Flash sale: one hot row, two queues: `flash-sale-order-flow`

A Java checkout API reserves stock and inserts the order in one PostgreSQL transaction, then
enqueues the payment on SQS; a Go payment worker charges the card and publishes to RabbitMQ, where a
fulfilment worker buys a shipping label from a carrier API. 600 req/s with a 20 s sale at 2,400;
15% of requests are orders.

**Checked.** Request paths (orders: the stock row and the order in PostgreSQL, then the enqueue;
product pages: PostgreSQL reads, half of them on the replica), the stock key (the SKU: every order
of a SKU updates the same row, which `rowLocks` models), the payment worker's retries (3 attempts
of 5 s with 500 ms backoff: 16.5 s in the worst case, inside SQS's 30 s visibility timeout; the
charge carries an idempotency key, so retrying it is safe), RabbitMQ's memory watermark (40% of
0.25 GiB at 64 KB a message: about 1,640 messages), the carrier call (2 attempts of 5 s), every
consumer of both queues actually consuming (SQS and RabbitMQ are one shared queue each).

**Tuned while building.**
- Both queues first had the generic default of 8 partitions, which left 72 SQS consumers idle (a
  validation warning said so); they now have their presets' 10,000 and 1,000.
- A 10× carrier *slowdown* set off RabbitMQ's memory alarm, but each block lasted about 7 s, never
  long enough to outlive the visibility timeout. The chaos scenario is a network-latency fault
  (+3 s each way) instead: every label call times out, fulfilment drains a few messages a second,
  and the publishers stay blocked for more than 30 s.
- Orders are 15% of requests (first 10%) and the deal is Zipf 1.6, so the hot row is clearly over
  its capacity at the sale's peak rather than borderline.

**For reviewers.** Do your AMQP publishers block while a broker raises a memory alarm, as modelled
here, or time out? Is 0.25 GiB with a 40% watermark a fair small broker? Would you shorten the
locked transaction, as the fix here does, or reserve stock through a queue?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (the sale opens at 20 s) | 100% | 37 ms | No component is saturated | $1,319 |
| Deal of the day: 44% of orders for one SKU | 93.3% | 584 ms | Checkout API is the primary bottleneck | $1,309 |
| Deal of the day, shorter stock transaction | 100% | 33 ms | No component is saturated | $1,319 |
| Carrier API hangs for 40 s (50–90 s) | 100% | 37 ms | fulfilment (RabbitMQ)'s backlog grew (consumers 95% busy) | $1,319 |
| Carrier hangs, 5-minute visibility timeout | 100% | 37 ms | fulfilment (RabbitMQ)'s backlog grew (consumers 95% busy) | $1,319 |
| Carrier hangs, RabbitMQ with 4 GiB | 100% | 37 ms | fulfilment (RabbitMQ)'s backlog grew (consumers 95% busy) | $1,323 |

Behind the headlines: the deal makes writes wait 106 ms on average for the stock row while the
database's connections are 10% busy (the lock-contention finding); the shorter transaction cuts
that to 0.8 ms. When the carrier hangs, RabbitMQ raises its memory alarm twice and SQS redelivers
80 payment messages (one per blocked consumer; the payments API sees them twice); with the 300 s
visibility timeout it redelivers none and its backlog peaks at 4,249; with 4 GiB the alarm never
fires, payments carry on (SQS back to its baseline peak, 3,066) and the fulfilment backlog peaks at
5,866 instead.

Reviewed by: ______________________ (name, role, date)

## Social feed: a celebrity hot key: `social-feed-hot-key`

A Go feed API reads each author's recent posts from a six-shard Redis Cluster (cache-aside, TTL
model) and Cassandra on a miss; likes and replies update Cassandra and invalidate the cached list.
Mobile (70%, one retry) and web (30%) clients, 2,000 req/s over a million authors.

**Checked.** The cache pattern (cache-aside: Cassandra is linked with `readsOnMiss`, the 20 ms
lookup carries on if it fails, writes go to Cassandra first and then invalidate), Redis executing
commands on one thread per shard however many vCPUs a node has (the cache is auto-sized; the
Memcached scenario gives each node 4 vCPUs for its 4 threads), keys hashing to shards in both Redis and Cassandra, client timeouts (mobile 5 s with one
retry, web 8 s), the Feed API's CPU (set to 4 vCPU per pod so the celebrity spike does not run out
of CPU the auto-sizer would give it for the baseline).

**Tuned while building.**
- The first version also had a Kafka fan-out-on-write path, a small-cache eviction scenario and an
  RDB snapshot scenario. Fan-out on write needs one post to become many timeline writes, which the
  engine cannot do (see Follow-ups), and its writes were keyed by author, which made a misleading
  hot shard. A 60 s run never fills a realistic Redis node's memory (about 41,000 keys were cached
  against room for over a million), so eviction and snapshot forks showed nothing. All three are
  gone.
- The celebrity spike first exhausted the Feed API's auto-sized CPU before the hot Redis shard;
  the API's CPU is set now. The cache's CPU and memory were set too, but with both set the size
  the analysis reports without a price snapshot is not a standard shape (`test_pricing`, see
  Follow-ups), so the cache is auto-sized and only the Memcached scenario sets its CPU. Redis commands take 0.4 ms (64 KB lists), so one shard does about
  2,500 commands a second and the hot key is decisively over it.

**For reviewers.** The cache's own numbers stay calm in the celebrity scenario (about 20% busy, no
saturation) because a cache's metrics are not split by shard; the hot shard shows only through
Cassandra's hot-shard finding (see Follow-ups). Is 0.4 ms per command fair for 64 KB values? Is
Zipf 1.5 with a 3.5× spike a fair "celebrity posts"?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (2,000 req/s, a million authors) | 100% | 185 ms | No component is saturated | $1,734 |
| Celebrity post: 7,000 req/s, one author gets 38% | 68.1% | 1,239 ms | Posts (Cassandra) is the primary bottleneck | $1,734 |
| Celebrity post, 12 cache shards | 69.1% | 1,215 ms | Posts (Cassandra) is the primary bottleneck | $1,734 |
| Celebrity post, no fallback to Cassandra | 72.0% | 189 ms | Posts cache (Redis Cluster) is the primary bottleneck | $1,734 |
| Celebrity post on Memcached | 100% | 185 ms | No component is saturated | $1,734 |

Behind the headlines: with the fallback, Cassandra's shard 1 gets 74% of its requests (the
hot-shard finding) and the Feed API's queue overflows while its CPU is 22% busy; per client, the web
app (no retries) keeps 60% and the mobile app 72% (1.41 attempts per request). Failing fast keeps
both at 72% and Cassandra's hot shard at 38%. Memcached's 4 threads per node absorb the same spike.

Reviewed by: ______________________ (name, role, date)

## Right-size a catalog API: `right-size-catalog-api`

A Node.js catalog API on three 2 vCPU VMs and PostgreSQL on 2 vCPU and 8 GiB, at 1,800 req/s (90%
reads). The six scenarios are sizing options for Results → Compare → Cost vs. speed and the
Breaking point tab, not incidents; this template has no chaos scenario on purpose, so the map
compares like with like.

**Checked.** The API's event loops (2 processes on 2 vCPU per VM; non-blocking calls), the
database's connection pool (8, the most the validator accepts for 2 vCPU without a warning:
PostgreSQL peaks at a few active connections per core), the buffer cache (a 10 GiB hot working set
against 75% of 8 GiB: 60% of reads from memory), the sizes (CPU set on the API and CPU and memory
on the database, so the options do not depend on auto-sizing; the API's memory is auto), the
prices (each option snaps to an AWS type: c6g.large API VMs, db.m6g.large, db.r6g.large and
db.m6g.xlarge).

**Tuned while building.**
- The clients' network was first a realistic 10 ms (p95 30 ms) each way, which put every option at
  about 60 ms p95 and hid the servers. It is 1 ms now, and the description says so.
- The load is 1,800 req/s (first 1,500), where the database is near its knee, so the options
  differ.
- The pool was 100 connections, then 20; the validator warned about both.
- The API's memory is no longer set: with both CPU and memory set, the size the analysis would
  pick without a price snapshot is not a standard shape, which `test_pricing` checks for every
  template (see Follow-ups). Memory is not one of this template's levers.

**For reviewers.** Is a 2 vCPU database at 93% CPU for 1,800 simple catalog queries a second
plausible (each query is about 1.7 ms, half of it on CPU)? Should the map's options include a
Redis cache in front, which would need a cache part in every option?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| As drawn: 3 API VMs, database 2 vCPU / 8 GiB | 100% | 35 ms | Catalog DB is running hot (90% busy) | $289 |
| Cheapest: 2 API VMs | 100% | 43 ms | Catalog DB is running hot (90% busy) | $240 |
| Scale the API: 5 VMs | 100% | 37 ms | Catalog DB is running hot (90% busy) | $389 |
| More database memory: 16 GiB | 100% | 32 ms | Catalog DB is running hot (CPU 93% busy) | $338 |
| Bigger database: 4 vCPU / 16 GiB | 100% | 15 ms | No component is saturated | $406 |
| Read replica: 2 × 2 vCPU | 100% | 17 ms | No component is saturated | $414 |

The 25 ms objective passes for the bigger database and the read replica only. The frontier runs
from the two-VM option through the drawn design and the extra memory to the bigger database; five
API VMs and the replica sit off it. Breaking point (8 steps from 1,000 to 5,500 req/s, 20 s each):
as drawn, the design holds 1,645 req/s within the objectives and saturates around 2,285 at the
database; the bigger database holds 2,285 and saturates around 3,570 at the API; the replica holds
2,285, and latency climbs sharply around 2,930 at the API.

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
  the limit is a policy, not a symptom. It also takes the headline: the public API's breaker
  scenario says "Partner gateway is the primary bottleneck" for the partners' quota, and the
  finding's advice is "Raise the balancer's connection limit or scale it out" although 0% of its
  connections were busy.
- **docs/performance.md** lists each template's request count and run time; the Kafka, MongoDB
  and serverless templates now generate different loads, so its table needs a fresh run, and the
  five new templates are not in it. The README's gallery screenshot and its alt text still say 10
  templates and 45 scenarios (now 15 and 73).

Found while building the five templates of 28 October (none fixed here: engine code was out of
scope):

- **Exports drop client groups.** `roundtrip.NOT_DEPLOYED` lists `traffic_source` and
  `kubernetes_cluster` but not `mobile_app`, `web_client` or `api_consumer`, so a docker-compose or
  Kubernetes export of the public API or the social feed loses its clients, and importing it back
  adds one generic Clients part. The fix is those three types in that tuple.
  `test_import.py::test_every_template_round_trips` marks the two templates as strict expected
  failures until then.
- **No objective for freshness.** Objectives cover the request path only, so the CQRS template
  "meets its objectives" while the hot merchant's events wait 26 s (p95). A consumer-lag or
  event-age objective would let the verdict say so.
- **A lagging topic is blamed for its producer.** At Zipf 1.3 (an early CQRS draft), the findings
  said "Order commands is saturated as a consequence of order-events topic", although a publish is
  acknowledged at once and the cause was PostgreSQL's row lock (the lock-contention finding said
  so). The symptom rule should not look through a message queue.
- **A hot cache shard is invisible.** Cache metrics are not split by shard and the hot-shard
  finding covers databases only, so in the social feed's celebrity scenario the Redis Cluster
  reports about 20% busy and "ok" while one shard's thread is the root cause; only Cassandra's
  hot-shard finding (74% of its requests) points at it.
- **Fan-out on write.** One message cannot become N writes (one post, one write per follower), so
  the social feed models fan-out on read only. A per-message multiplier on a consumer's link would
  allow it.
- **Retry budgets and per-client rate limits.** Retries are a fixed attempt count per link; a
  budget (a share of requests, as in Envoy's retry budgets or gRPC retry throttling) is not
  modelled. A gateway's rate limit is one bucket for all callers, which is why the public API gives
  partners a gateway of their own.
- **Replicas serving cache reads** are not modelled (simulation-model.md says so), which rules out
  the usual alternative fix for a hot Redis key.
- **Sizes of fully specified parts without a snapshot.** When a VM part sets both CPU and memory,
  the size the analysis reports for it without a price snapshot is not rounded to a standard shape
  (2 vCPU and 2 GiB for the right-size template's API, 1 vCPU and 16 GiB for the social feed's
  cache), which `test_pricing` checks for every template; those templates leave the memory, or
  the whole cache, on auto. Worth a look in the sizing rework.
