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
([below](#added-on-28-october-2026)) use the same checklist. Ten more followed the same day for
variety of architecture ([below](#ten-more-architectures-28-october-2026)).

How the numbers were made: engine 0.12.0 (0.11.0 plus the analysis changes below), prices from the AWS
us-east-1 snapshot of 2026-10-27, each template's own seed. Every scenario was simulated with
the Python engine's `archsim run examples/<template>.json --scenario <scenario>`; the tables come
straight from those reports, a record of that engine.

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

## Ten more architectures (28 October 2026)

Ten templates added the same day for variety: each is a different kind of system (video
delivery, search, a multi-tenant SaaS on SQL Server, MySQL replicas, notifications, live
telemetry, API authentication, a microservice call chain, a serverless pipeline and an LLM
assistant), with five scenarios each (six for the call chain), 51 in all. They were built against
the same checklist and to a written brief that carried the lessons of the five above (a burst
belongs in the baseline when auto-sizing must cover it; CPU, not CPU and memory, is set on a
machine part; one-queue technologies need their preset's partitions). Every scenario was
simulated while it was tuned, and every number in a description comes from the final runs,
re-run once more to confirm. Same conditions: engine 0.12.0, the AWS us-east-1 snapshot of
2026-10-27, each template's own seed.

Several of them show their story in numbers the request path never sees (queue lag and age,
dropped or dead-lettered messages, replica lag and stale reads, a provider's refused calls), and
their headlines often say "No component is saturated" while a critical finding further down
carries the story. Their sections say where to look, and the Follow-ups list the engine changes
that would put it in the headline.

## Video streaming: CDN, origin shield and object storage: `cdn-video-streaming`

Video on demand for a mobile app (45%) and web and smart-TV players (55%) at 1,500 segment requests
a second. Anycast routing spreads viewers over three read-through CDN POPs; their misses go to one
Varnish origin shield, its misses to a Go packager that reads source fragments from S3. Hit rates
come from a one-day TTL over 100,000 segment files (Zipf 1.2), so the caches start empty and warm up.

**Checked.** Request path (reads only: viewers never write; POP `miss` port to the shield, the
shield's `miss` port to the packager, the packager's GET to S3), the cache pattern (CDN and Varnish
are the only caches and both read through, as the checklist allows; no cache-aside links), every
cache on `ttl_keyspace` with populate on miss, the timeouts (players 8 s and 10 s per attempt; a
POP gives the shield 3 s, two attempts with 100 ms backoff, 6.1 s in all; the shield gives the
packager 2.5 s; the packager waits at most 500 ms in its queue and gives S3 two attempts of 900 ms:
2.35 s), retries only on segment GETs (idempotent), the anycast router (random spread, no
per-request overhead, priced at 0 because it is DNS, not a machine), sizes (the shield auto-sizes
to x2gd.4xlarge, 16 vCPU and 256 GiB, for 100,000 segments of 1.5 MB; the packager is 2 pods of
1 vCPU, about 320 segments a second, against about 120 a second in the baseline; S3's 5,500 GET/s
per prefix is never reached).

**Tuned while building.**
- The first draft had one CDN node. Behind a single edge with the same TTL as the shield, every
  edge miss is also a shield miss, so the shield has nothing to do. Three POPs behind anycast
  routing make it do what it is for, collapsing the POPs' misses for the same segment (it serves
  about 31% of them).
- The keyspace is 100,000 files at Zipf 1.2. A run is about 150,000 requests, too few to warm a
  catalogue at a gentler skew: at Zipf 1.0 the POPs served 68%, the shield 18%, and the origin's
  286 requests a second ran the packager close to its limit (p95 1.9 s).
- The packager's CPU is set. Auto-sizing counts every read of a TTL cache as a possible miss, so it
  would have sized the packager for all 1,500 req/s and hidden both the cold start and the short
  TTL.
- The short-TTL scenario was checked with a 12-pod packager: the 2 s TTL itself asks the origin
  for about 384 segments a second (3.3 times the baseline's 118) and every request then succeeds.
  The failures in the scenario are the drawn packager, sized for video on demand.
- The cold start is its own 20 s run measured from the first request; inside the baseline's warm-up
  it was invisible.
- A sixth scenario, S3 10 times slower for 30 s, was dropped: nothing failed and p95 inside the
  window rose only from 237 to 367 ms, because only the 8% of requests that reach the origin wait
  for S3.

**For reviewers.** The POPs are priced at the CDN preset's illustrative hourly rate; a real CDN
bills per GB and per request, and at 1,500 segments of about 1.5 MB a second data transfer would
dwarf every line in the table (the template description says it is left out). The engine has no
request collapsing, so concurrent misses for one segment all travel to the origin; a real POP and
Varnish collapse them, which matters most in the first second of a cold start. A player's retry
goes through anycast routing again and can land on another POP (the engine picks one at random);
real anycast keeps a viewer on one POP, so the outage's 92% may be a few points optimistic. Is one
shield node a fair "as drawn", and is Zipf 1.2 over 100,000 files a fair stand-in for titles times
segments?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline | 100% | 226 ms | No component is saturated | $1,599 |
| Premiere: 2.5× viewers on the new episode | 100% | 189 ms | No component is saturated | $1,557 |
| Every object cached for 2 s (live-style) | 94.8% | 3,611 ms | Packager (origin) is the primary bottleneck | $1,816 |
| Origin shield down for 20 s (50–70 s) | 97.7% | 235 ms | No component is saturated | $1,573 |
| Cold start: the first 20 s with empty caches | 100% | 1,168 ms | Packager (origin) is the primary bottleneck | $1,747 |

Behind the headlines: in the baseline each POP serves 89% of its requests and the shield 31% of
what reaches it, so the packager and S3 see about 118 requests a second (141 in the first 10 s of
the window, 100 in the last); p95 is 264 ms for the mobile app and 171 ms for the web and TV
players, mostly their own network. During the premiere's 20 s (3,730 req/s) the origin gets 135
requests a second, against 69 in the 20 s before it and 118 in the baseline at the same time; the
POPs serve 95%. With a 2 s TTL the POPs serve 44–47%, the shield receives 1,923 requests a second
(retries included), the packager 1,739 against a capacity of 324, and S3 324 GETs a second (the
packager's limit); the mobile app keeps 94.4% (1.35 attempts per request), the players 95.0%. When
the shield is down, 91.7% of requests succeed inside the window, the shield refuses 17,908 calls,
2,456 client requests fail there, p95 inside the window is 190 ms (237 ms before it) and the design
is back about 2 s after the window. In the cold start the packager runs at its CPU limit for about
10 s (S3 gets about 320 GETs a second, the packager's queue peaks at 212, 128 requests time out in
its queue and are retried by the POPs), p95 averages 1.1 s in those 10 s and 255 ms in the next 10.

Reviewed by: ______________________ (name, role, date)

## Marketplace search on OpenSearch: `search-opensearch-catalog`

Shoppers search a marketplace from a web shop (60%) and a mobile app (40%); sellers edit listings
from the same apps (5% of requests). A Go search API reads a Redis query cache (cache-aside, 40%
hits) and otherwise searches OpenSearch: 3 primary shards of 30 GiB, one replica each, six 8-vCPU
nodes. Edits go through a Listings API onto a Kafka topic keyed by listing; a Java indexer writes
them to OpenSearch. 1,400 req/s.

**Checked.** Request paths (searches: the cache lookup, then OpenSearch on a miss; edits: the
publish only, then the indexer's write; the load balancer sends each type only to its own API),
the cache pattern (cache-aside: the OpenSearch link has `readsOnMiss` and order 0, the cache link
order 1 with a 50 ms timeout and `continueOnFailure`; results are not invalidated on an edit, they
age out, which is what query caches do), every search scattered to all shards with the
coordinating node's merge (`routerOverheadMs` 2 ms, set by hand, see the follow-ups), index writes
waiting for the replica (`replication: all`, the OpenSearch default) and replayed at full cost
(`replicaApplyFactor` 1.0: document replication indexes the document again on the replica), the
thread pools (21 per node: 13 search threads for 8 vCPU, (8 × 3 / 2) + 1, plus 8 write threads),
the timeouts (the API gives OpenSearch 1 s with no retry and waits at most 500 ms in its own queue,
inside the web shop's 10 s and the mobile app's 8 s with one retry; the indexer makes three attempts
of 2 s with 200 ms and 400 ms backoff, safe because each write carries the listing's version),
Kafka (12 partitions, 12 consumers, keyed by listing so one listing's edits stay in order), sizes
(OpenSearch CPU set to 8 vCPU per node, memory auto: r6g.2xlarge, a common OpenSearch data node;
the APIs and indexer are pods with CPU set; Redis cache.m6g.large and Kafka 3 × kafka.m7g.large
auto-sized).

**Tuned while building.**
- The query cache first used the TTL model (30 s over 300,000 distinct queries, which also gave
  about 40%). Its cold start sent every search to OpenSearch in the first seconds; the oversharded
  cluster never got out of it (16.6% success, the cache at 7% hits), because the cache fills only
  from successful searches and a search fails when any of its 12 shards turns it away. A fixed 40%
  compares steady states instead.
- The search API was first thread-per-request (64 workers per pod). In the slowdown its workers,
  blocked on OpenSearch, filled first and the findings named the API while OpenSearch was 58% busy.
  A Go service does not hold a thread per request, so it is non-blocking now, with 2 GiB per pod
  (the auto-sized 1 GiB refused requests in the peak once many were waiting).
- The search queues are 200 per node, not OpenSearch's default 1,000. With the default, the same
  slowdown recovers about 7 s after the fault instead of 2 s and 12,180 searches time out at the API
  instead of 7,604 (78.8% success over the run instead of 81.9%): the long queues keep running
  searches the API has already given up on.
- The oversharded scenario keeps the hardware: the engine runs each shard copy as an instance, so
  12 shards × 2 copies get 2 vCPU and 16 GiB each. The memory override is needed because a scenario
  keeps the design's 64 GiB per instance; the first run priced 24 x2gd.xlarge at more than three
  times the drawn cluster.
- A hot working set (`workingSetGib`) gave a validation warning without memory set, and CPU and
  memory cannot both be set on a machine part, so the buffer cache is not modelled.
- A sixth scenario, sellers repricing at 8 times the usual edit rate (560 a second), was dropped to
  keep five: Kafka absorbed it (lag 10,675 messages, p95 21.5 s from publish to indexed), searches
  did not notice (p95 182 ms) and OpenSearch's CPU went from 65% to 75%. The peak shows the other
  half of that contention.

**For reviewers.** Every client group sends the same read/write mix, which is why the writers are
sellers using the shoppers' apps. The engine has one pool per OpenSearch node for searches and
index writes, and a write holds its slot while it waits for the replica; OpenSearch has separate
search and write pools, so index writes here interfere with searches a little more than in a real
cluster. A search fails here if any shard copy turns it away; OpenSearch returns partial results by
default (`allow_partial_search_results`), so some of the peak's and the slowdown's failures would be
incomplete result pages instead. Is a 4.5 ms median for a 7.5 GiB shard against 13 ms for a 30 GiB
one a fair split between per-shard overhead and per-document work? Would you shorten the search
queue as this template does?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline | 100% | 185 ms | No component is saturated | $2,761 |
| Oversharded: 12 small shards on the same hardware | 100% | 209 ms | Product index (OpenSearch) is running hot (CPU 91% busy) | $2,833 |
| Evening peak: 2,400 req/s for 25 s (45–70 s) | 91.4% | 561 ms | Product index (OpenSearch) is the primary bottleneck | $2,761 |
| Evening peak with a second replica | 100% | 189 ms | No component is saturated | $3,656 |
| OpenSearch 3× slower for 20 s (45–65 s) | 81.9% | 684 ms | Product index (OpenSearch) is the primary bottleneck | $2,761 |

Behind the headlines: in the baseline OpenSearch's CPU is 65% busy and a search takes 33 ms there on
average (p95 74 ms); the web shop's p95 is 120 ms and the mobile app's 230 ms; an edit is indexed
72 ms (p95) after Kafka has it. Oversharded, OpenSearch's CPU is 91% busy, a search takes 61 ms
there (p95 108 ms), the web shop's p95 is 152 ms and 27 requests fail; Breaking point (8 steps of
20 s): as drawn every request succeeds up to 2,000 req/s (p95 204 ms) and 94.3% at 2,250;
oversharded every request succeeds at 1,250 and 81.1% at 1,500; with the second replica every
request succeeds at 3,000 (p95 213 ms), 99.7% at 3,250 and 86% at 3,500. In the peak 86% of
requests succeed during the 25 s, OpenSearch turns away 20.9% of what it gets (13,009
searches and index writes), its CPU averages 85% over the window, and Kafka's lag reaches 2,306 messages (p95 18.3 s
from publish to indexed); per client, the web shop keeps 87.5% and the mobile app 97.2% (1.12
attempts per request). With the second replica the CPU is 60%, the replicas serve 67% of
reads and Kafka's p95 is 86 ms. In the slowdown 55.7% of requests succeed inside the window (p95
1,574 ms), the cluster is back about 2 s after it, the failures are 7,604 API timeouts and 4,967
searches turned away, and per client the web shop keeps 78.1% and the mobile app 87.7% (1.22
attempts); the index falls about 17 s behind (p95) and no edit is dead-lettered.

Reviewed by: ______________________ (name, role, date)

## .NET SaaS on SQL Server: readers blocked by writers: `sqlserver-saas-blocking`

A multi-tenant accounting app: browser users and customers' integrations call an ASP.NET Core API (four
instances, 64 synchronous threads each) that keeps every tenant in one SQL Server database (8 vCPU, 32
sessions). 1,500 req/s over 2,000 tenants, 15% of them postings; each posting updates its tenant's totals
row, and the tenant's screens read it.

**Checked.** Request paths (reads and postings both go to the one database, nothing else is on the path),
the lock key (the tenant: a posting's transaction updates the tenant's totals row, so `rowLocks` queues a
tenant's postings behind each other and, with locking reads, `readsWaitForWrites` makes that tenant's reads
wait for them; both are SQL Server's defaults in the engine), the session pool (32, the most the validator
accepts for 8 vCPU; the design review's "fewer connections than callers", 256 API threads against 32
sessions, is the narrow point the month-end scenarios are about), client groups (browsers 75%: one attempt,
15 s; integrations 25%: three attempts, 15 s, 1 s backoff, POSTs with an idempotency key), the timeout chain
(the API queues a request at most 2 s and waits 3 s for SQL Server, which queues a query at most 2 s for a
session; the load balancer gives up after 10 s, inside the clients' 15 s), the API's CPU (set to 4 vCPU per
instance, 37% busy). On AWS: the API on 4 × c6i.xlarge and the database priced as a c6i.2xlarge; the
template names the x86 families (c6i, m6i, r6i) so nothing snaps to Graviton, which SQL Server and
Windows do not run on (see For reviewers).

**Tuned while building.**
- Month-end first also raised postings from 15% to 25% of requests. The largest tenant's totals row was then
  over its capacity (about 64 postings a second at about 18 ms each), so postings piled up whatever the
  readers did, and RCSI changed nothing (82.0% success against 84.2% without it; postings waited 184 ms for
  the lock). Month-end now keeps the normal mix and only concentrates traffic on the largest tenants (Zipf
  1.2) and lengthens postings (median 6 ms, p95 60 ms), so the readers are what tips it over.
- A heavier tail (median 3 ms, p95 60 ms) made the results hinge on single samples: one posting of about 5 s
  on the hot row filled every session, with or without RCSI, and the failures came in two or three bursts.
  The lighter tail gives many medium waits instead, and the direction holds across seeds (below).
- The database first had 16 vCPU and 64 sessions, twice what the load needs (16% CPU on a normal day). It has
  8 vCPU and 32 sessions now (29%).
- The fourth scenario first combined RCSI with postings twice as long (a plan regression). It collapsed on
  postings waiting for each other, which the RCSI scenario already shows, so it became the fix for that:
  append-only postings.

**For reviewers.** The price snapshot has no RDS for SQL Server types, so the database is priced as a
self-managed c6i.2xlarge without a licence. A licence-included 8 vCPU instance costs several times more;
the description says the cost leaves out the licence (see Follow-ups).
Month-end depends on the few longest postings: on seeds 12 and 13 it succeeds 94.9% and 92.6%, with RCSI 100%
and 99.5%, and append-only 100% (p95 87 and 86 ms). RCSI's p95 is 322 and 226 ms there (470 ms on the
template's seed), so whether RCSI alone meets the 250 ms objective is a coin toss; the direction is not.
Append-only postings are modelled by turning row locks off: is that a fair stand-in for inserts plus a rollup
job whose cost, and the totals' delay, are not modelled? Is synchronous data access (a thread per request)
fair for a .NET SaaS today? With async calls the threads would be released and the wait would move into the
connection pool, which the database's session queue already models. The failover's headline is "No
component is saturated" although 18% of requests fail: a planned outage is not counted as overload, and the
fault-impact finding comes fourth (see Follow-ups). No standby is drawn; the outage stands in for an
availability-group or Multi-AZ failover. Would you add EF Core's retrying execution strategy? Here only the
integrations retry.

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Normal day | 100% | 81 ms | No component is saturated | $762 |
| Month-end close | 93.5% | 1,079 ms | Tenant DB (SQL Server) is the primary bottleneck | $762 |
| Month-end close with RCSI | 100% | 470 ms | No component is saturated | $762 |
| Month-end with RCSI and append-only postings | 100% | 86 ms | No component is saturated | $762 |
| Failover: 15 s without the database (45–60 s) | 82.2% | 86 ms | No component is saturated | $762 |

Behind the headlines: at month-end 26,123 reads waited for a posting (469 on a normal day), the 32 sessions
were 97% busy with a p95 wait of 335 ms for one, and the API's threads were 90% occupied, 3% of it their own
work (the symptom finding); 10,694 requests found the API's queue full. Browser users kept 91.4%,
integrations 100% at 1.10 attempts per request. Postings waited 16.9 ms on average for each other; with RCSI
55.5 ms (the lock-contention finding), the sessions were 63% busy on average and full at the busiest
moments; with append-only postings 24%. In the failover SQL Server refused 32,156 calls, 0.2% of requests
succeeded inside the window and the system recovered about 2 s after it; browser users kept 81.3% over the
run, integrations 85.0% at 1.37 attempts per request.

Reviewed by: ______________________ (name, role, date)

## User profiles on MySQL: replica lag and stale reads: `mysql-replica-lag`

A Go profile API for a mobile app and a web app reads from three MySQL 8 replicas and writes to the primary;
each replica applies the primary's changes on 4 worker threads. 1,500 req/s over 200,000 users, 10% of them
updates; a settings surge takes updates to 1,050 a second.

**Checked.** Request paths (reads go to the replicas, `readFrom: replicas`; updates go to the primary and
are replayed asynchronously), replay (4 applier threads per replica, replica_parallel_workers' default since
MySQL 8.0.27, at 70% of the write's execution time: about 890 writes a second per replica), what counts as
stale (a replica read of a user whose latest committed change that replica has not applied; changes become
visible in commit order, as with replica_preserve_commit_order), client groups (mobile 65%: two attempts,
10 s, 500 ms backoff, safe because the settings update is an idempotent PUT; web 35%: one attempt, 15 s),
timeouts (the API queues a request at most 1 s and waits 1.5 s for MySQL, which queues a query at most 1 s;
the load balancer gives up after 5 s), sizes (four MySQL instances of 4 vCPU with 16 connections each, the
most the validator accepts, a third of each query's time on CPU, priced as db.m6g.xlarge; the API on 4 ×
c6g.large), the surge (2,400 req/s at 56.25% reads keeps reads at 1,350 a second and changes only the
writes, inside the run budget).

**Tuned while building.**
- Writes take a median of 5 ms (the profile row, its history row and a search row) and replay at 70% of that.
  At the engine's default 50%, 4 workers would apply about 1,250 writes a second, more than the surge, and
  lag would need write rates beyond the run budget.
- The storage stall was first 3×: the replicas still kept up with 150 updates a second (lag 55 ms) and there
  was nothing to see. At 6× the API's queue overflowed too (96.1% success inside the window, and the headline
  blamed the API), which mixed two stories. 5× shows the lag on its own.
- A sixth scenario made every write wait until all three replicas had applied it (`replication: all`; the
  closest MySQL setting is Group Replication's consistency AFTER). Stale reads fell to 137, but each write
  held its primary connection about 10 ms longer, the primary's queue overflowed and 7.5% of requests failed
  (13.7% of the web app's), with a write p95 of 977 ms. It was dropped to keep five scenarios, and because
  the setting MySQL users reach for, semi-synchronous replication, waits for a replica to receive a change,
  not to apply it: it does not prevent stale reads, and the engine cannot model it (see Follow-ups).

**For reviewers.** Is 70% of the write's time a fair cost for applying a row-based change on a MySQL 8
replica (every index is still updated; parsing, planning and the client round trip are saved)? Stale reads
count any reader of a profile with an unapplied change: the engine cannot tell whether the reader is the user
who just wrote (read-your-writes), and it cannot send only those reads to the primary or wait for the
change's GTID (WAIT_FOR_EXECUTED_GTID_SET), the usual fix; "reads from the primary" is the blunt version.
Reads and updates share one key popularity (Zipf 0.7), so the most popular profile is also the most updated
(about 8 updates a second in the surge). There is no cache: a cache-aside Redis would take most reads off
the replicas, but a stale replica read would then stay cached until its TTL or the next write, which the
engine does not model. In "reads from the primary" the bottleneck finding says 25% of the database's 64
connections were busy: the average includes the three replicas, which serve no reads, while the primary's 16
were full and its CPU could do about 2,563 req/s against the 2,516 it received (see Follow-ups). Is a 5×
slowdown a fair picture of an EBS volume out of I/O credits?

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Normal day | 100% | 185 ms | No component is saturated | $1,106 |
| Settings surge: 1,050 updates a second | 100% | 185 ms | No component is saturated | $1,106 |
| Settings surge, 16 apply workers | 100% | 189 ms | No component is saturated | $1,106 |
| Settings surge, reads from the primary | 96.8% | 451 ms | Profiles (MySQL 8) is the primary bottleneck | $1,106 |
| Storage stall: 5× slower for 20 s (25–45 s) | 100% | 204 ms | No component is saturated | $1,106 |

Behind the headlines (the replication-lag finding and the database's counters in the same runs): on a normal
day the replicas lag at most 15 ms and 13 of 67,050 replica reads are stale. In the surge the lag grows by
about 0.15 s every second to 9.0 s, and 11,670 of 67,406 replica reads (17.3%) are stale, while the request
path's p95 stays at 185 ms; with 16 apply workers the lag peaks at 30 ms and 122 reads (0.2%) are stale. Reading
from the primary leaves no stale read, but the replicas still fall 7.3 s behind for nobody, the primary's
queue refuses 9,872 queries (7.8% of its arrivals), and the web app keeps 92.3% while the mobile app's retry
keeps 99.3% (1.08 attempts per request). In the storage stall the lag peaks at 1.6 s, 388 replica reads are
stale, p95 inside the window is 223 ms against 185 ms before it, and the mean commit wait rises from 1.5 to
4.8 ms over the run.

Reviewed by: ______________________ (name, role, date)

## Notifications: one campaign, three providers' quotas: `notification-fanout-quotas`

Internal services post notifications to a Go API that puts one SQS message per channel on its own queue; a sender
deployment per channel calls a push gateway (1,000/s), an e-mail provider (50/s) and an SMS provider (30/s). 12
notifications a second, with a marketing campaign of about 3,100 in 8 s (20–28 s) in every scenario.

**Checked.** Request paths (every notification is queued for push, then e-mail; the quarter that is the write type, the
recipients who opted in to SMS, is also queued for SMS; each queue's consumers call only their sender and each sender only
its provider, so a failure on one channel never re-sends another), consumers against workers (push 32 = 4 pods x 8, e-mail
10 = 2 x 5, SMS 12 = 3 x 4; the SQS queues have the preset's 10,000 partitions, so no consumer is idle), pacing (an e-mail
send takes about 185 ms, so 10 workers make about 50 calls a second; an SMS about 450 ms, so 12 workers make about 26 a
second), retries (callers: 3 attempts with an idempotency key on the request; senders: 3 attempts with 200 ms backoff, and
the e-mail and SMS calls carry an idempotency key; SQS gives up after 5 receives and dead-letters), timeouts (callers 5 s
against the API's 0.5 s queue wait plus three 1 s publishes; a sender's worst case is 6.6 s for e-mail and 9.6 s for SMS,
27 s in the backoff scenario, all inside SQS's 30 s visibility timeout), sizes (auto-sized; the campaign is in the baseline
workload, so every part is sized for it). The design review's three high findings, "Up to 9 attempts reach" each provider
per request, multiply the callers' 3 attempts by the senders' 3; a caller retries only when the API fails, possibly after
it has queued, which is why the request carries an idempotency key.

**Tuned while building.**
- Push first had 16 workers: the campaign's 400/s outran their 300/s and pushes waited up to 9 s (p95). With 32 they go out
  within about a second, which is the contrast with e-mail that the template needs.
- E-mail workers: 8 sent about 43 a second (p95 e-mail age 69 s, done at about 135 s); 9 on three pods of 3 workers about
  42, because the engine spreads a queue's deliveries round-robin over the pods (see Follow-ups); 10 on two pods of 5 fill
  the quota (one 429 in the run). With 10, the 40-worker scenario finishes no sooner, which is the lesson; against 8 it
  would have looked like a 35 s win bought with 429s.
- The backoff scenario first used a 1 s base: 15 s of backoff plus five 3 s timeouts is exactly the 30 s visibility timeout.
  It is 0.8 s now (at most 27 s per delivery).
- A sixth scenario, the e-mail quota raised to 200/s with 40 workers, emptied the e-mail backlog within seconds (p95 age
  about 9 s, no 429s). It was dropped to keep five; the 40-worker scenario's copy says that only a bigger quota helps.
- Channels have separate queues on purpose: with one queue whose consumer called push, e-mail and SMS in turn, a 429 from
  the SMS provider would redeliver the whole message and send its push and e-mail again (the engine redelivers a message
  whole, as SQS does).

**For reviewers.** The API has no database: a production notification service would check preferences and record what it
sent; left out to keep the channels in focus. Pacing is by worker count, because the engine has no rate limit on a queue's
consumers (see Follow-ups); production senders would use a token bucket at about 90% of the quota. A failed message goes to
the back of the queue at once, where SQS would hide it until its visibility timeout ran out: is that a fair model of "gives
up after five receives"? Provider cost is metered on every call, including 429s and calls refused during the outage (see
Follow-ups), so the 40-worker and SMS-outage rows overstate the bill.

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (campaign at 20–28 s) | 100% | 86 ms | No component is saturated | $258,204 |
| Scale the email sender to 40 workers | 100% | 86 ms | No component is saturated | $415,377 |
| SMS provider down for 30 s (60–90 s) | 100% | 86 ms | No component is saturated | $419,123 |
| SMS provider down, sender backs off | 100% | 86 ms | No component is saturated | $279,605 |
| Every notification also sent as SMS | 100% | 102 ms | sms queue (SQS) is running hot (96% busy) | $520,798 |

Behind the headlines: the request path never sees the providers, so every row is 100%. In the baseline the e-mail backlog
peaks at 2,777 messages and empties at about 100 s (p95 age 53 s), SMS peaks at 588 (p95 age 25 s) and push at a few hundred
(p95 age 1.0 s); SMS is about 65% of the monthly cost and e-mail about 35%, the parts themselves well under 1%. With 40
workers the provider gets 94 calls a second instead of 34 and refuses 8,374 of them (the critical throttling finding), 1,772
deliveries fail, 9 e-mails are dead-lettered, and the backlog still empties at about 100 s. While the SMS provider is down
it refuses 1,218 calls, 400 deliveries fail and 75 texts are dead-lettered, while e-mail keeps draining at its normal rate;
backing off, it refuses 152 calls, 24 deliveries fail, none is dead-lettered and the SMS backlog peaks at about 76 texts that
wait up to about 23 s. With a text for everyone, 374 texts are still queued at the end, the p95 text waits 116 s, and SMS is
about 83% of the monthly cost.

Reviewed by: ______________________ (name, role, date)

## Live match telemetry on NATS: drop or buffer: `nats-live-telemetry`

Game servers post match events through a load balancer to a Go ingest gateway, which publishes them to NATS; a queue group
of 16 Go aggregators appends each event to the match history in DynamoDB and then updates a Redis leaderboard. 1,000 events
a second, all writes, on core NATS (at-most-once, a 2,000-event pending limit); one scenario switches to JetStream.

**Checked.** Request paths (writes only; the aggregator writes the match history first, a put keyed by event id, and bumps
the score last, so a failed history write never moves the leaderboard and a JetStream redelivery repeats only the
idempotent put), Redis as the leaderboard's store rather than a cache (it gets only writes, so the cache-aside rule does not
apply; one command thread at 0.05 ms a command, about 5% busy), consumers against pods (16 subscribers on 3 x 16 workers,
32 on 6 x 16; 1,000 partitions as in the preset, so none is idle), core NATS settings (`delivery: at_most_once`, one
delivery, no ack wait) against JetStream's (at least once, a million-event stream, acks after the three replicas, 5
deliveries, a 30 s ack wait), timeouts (game servers 1 s with one retry, safe because events carry an id; the gateway's
250 ms queue wait plus a 250 ms publish timeout fit inside it; the history write's three 200 ms attempts plus 150 ms of
backoff fit far inside the ack wait), DynamoDB on demand (8 partitions, keys are match ids spread evenly, at most 2,500
writes a second against 4,000), sizes (CPU is set on the gateway, 1 vCPU, and the aggregators, 2 vCPU, because auto-sizing
would size them for 1,000 events a second and the final exists only in two scenarios; the NATS brokers set only CPU).

**Tuned while building.**
- The pending limit was first 10,000 events (10 s of traffic). The bad deploy then dropped 6,713 events and delivered the
  rest about 16 s late (p95), as late as JetStream, so core NATS lost events without being any fresher. At 2,000 it drops
  more (14,711) and what gets through is at most about 4.5 s old: the trade-off the template is about.
- The gateway first had 2 vCPU per pod and used 12% of it; 1 vCPU is about 36% busy over the final's run.
- A store outage (DynamoDB unreachable for 15 s) was the other candidate. On core NATS it loses events the way the bad
  deploy does; on JetStream the engine redelivers a failed event at once, so the 5 deliveries are spent within milliseconds
  unless the aggregator holds the event with long retries, the mechanism the notification template already shows. The
  final and its fix show capacity instead.

**For reviewers.** Core NATS has no shared backlog: each subscriber's client buffers its own pending messages, and the server
cuts a slow consumer off when its outbound buffer passes `max_pending`. The template models both as one 2,000-event limit
for the queue group; is that fair? The two loss scenarios headline "No component is saturated", the fault finding says the
system "absorbed" the slowdown and the queue's lag finding is informational, while half the events are dropped (see
Follow-ups); only the queue's `dropped` counter tells. The design review's "Match history (DynamoDB) has no replica" does not apply to a managed table that keeps three copies
itself. DynamoDB is priced as an instance (the known follow-up), and
JetStream costs the same as core NATS here because stream storage is not priced.

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (core NATS, 1,000 events/s) | 100% | 14 ms | No component is saturated | $784 |
| Bad aggregator deploy: 10× slower (30–60 s) | 100% | 14 ms | No component is saturated | $784 |
| Bad deploy on JetStream | 100% | 16 ms | match-events (NATS)'s backlog grew (consumers 83% busy) | $784 |
| Tournament final: 2,500 events/s (30–60 s) | 100% | 14 ms | No component is saturated | $784 |
| Tournament final, twice the aggregators | 100% | 14 ms | No component is saturated | $949 |

Behind the headlines (the queue's numbers in the same runs): the baseline's lag never passes about 10 events and an event is
about 9 ms old when it is processed. In the bad deploy the aggregators handle about 450 events a second, the pending limit
is full from about 32 s, and 14,711 of about 30,000 events published in the window are dropped (p95 age of the rest 4.5 s).
On JetStream none is dropped: the backlog peaks at 16,712, events wait up to about 16 s (p95 age 15.3 s) and the backlog is
empty at about 82 s; publishes take about 1 ms more (mean 9.8 ms against 8.7 ms). In the final the 16 aggregators handle
about 1,700 events a second and 21,741 of the final's 75,000 events are dropped (p95 age of the rest 1.2 s); 32 drop none
and the lag stays under 15 events.

Reviewed by: ______________________ (name, role, date)

## API auth: token introspection vs local JWT checks: `auth-token-introspection`

A Go accounts API behind a Kong gateway checks every OAuth token with a rate-limited identity provider (200 calls/s) and caches
the answer in Redis for 60 s; writes that move money always check again (step-up). Mobile, web and partner client groups;
traffic ramps from 150 to 1,350 req/s over 150 s, 4% writes.

**Checked.** Request paths (a read: the token cache, the identity provider only on a miss over the `readsOnMiss` link, then
PostgreSQL; a write: the identity provider first, always, because writes on a `readsOnMiss` link are always sent, then
PostgreSQL; the cache link carries reads only, since a write does not change what a token check answers), the cache pattern
(cache-aside, a 50 ms lookup that carries on if Redis fails, the TTL model keyed by token over 5,000 active tokens with Zipf 0.6,
a refused introspection not written back), timeouts (the API's 500 ms queue wait, 1 s introspection, 50 ms lookup and 1 s query
come to about 2.6 s, inside the gateway's 4 s, which is inside the partners' 5 s and the apps' 10 s), retries (only the clients
retry: mobile twice, partners three times; money-moving writes carry an idempotency key; the API does not retry an
introspection), the quota against the baseline (about 150 calls/s at the peak against 200), database connections (8 for the
auto-sized 4 vCPU, under the validator's 16). On AWS: Kong on c6g.medium, the API 4 pods of 2 vCPU, Redis cache.m6g.large,
PostgreSQL db.m6g.xlarge.

**Tuned while building.**
- The traffic ramps instead of starting at full rate. The cache starts empty, so at a constant 750 req/s every token's first
  check would miss at once and run into the quota during warm-up (a cold-start storm that would dominate every chart). From
  150 req/s the cache fills under the quota.
- The first workload had 5% writes and Zipf 0.5; the cached baseline then used about 90% of the quota at the peak (182 calls/s),
  too little headroom to tell the drawn design from the fragile ones. 4% writes and Zipf 0.6 bring it to about 76%.
- The TTL is 60 s, not 5 minutes: with a TTL longer than the 150 s run no entry expires inside it, so an outage could not show
  entries running out and a short TTL would compare two cold fills.
- The outage first ran at 100–130 s, and the run ended before the storm that follows it had died down ("had not recovered by
  the time arrivals stopped"); at 80–110 s the 14 s recovery shows.
- A scenario cannot remove a link, so "local JWT checks for reads" keeps the Redis lookup as a revocation deny-list check that
  always answers (fixed hit ratio 1, nothing written back) and the identity-provider link carries writes only. The extra CPU of
  the signature check is about 0.2 ms per request.
- In extra runs a 30 s TTL stayed just under the quota at the peak (197 calls/s) and a 20 s TTL crossed it in the middle of the
  ramp (89% succeeded at the peak). 10 s shows the failure clearly, and it is a TTL teams pick to make revocation faster.

**For reviewers.** Is 200 introspections a second (the preset) a fair quota for a SaaS identity provider? Would you re-check
every money-moving write with the identity provider, or trust short-lived tokens plus the deny-list? One request every 4 s per
active token at the peak is busy for an app; the model also draws each request's token independently, so a user's burst of calls
when the app opens is not there. In the outage, writes fail closed: would you rather queue them? The headline is "No component
is saturated" in every scenario, because a provider's 429s are not rejections of one of your parts; the throttling finding
carries the story (see Follow-ups).

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (token checks cached for 60 s) | 100% | 235 ms | No component is saturated | $599 |
| Introspect every call | 24.2% | 939 ms | No component is saturated | $599 |
| Token cache TTL 10 s | 74.5% | 802 ms | No component is saturated | $599 |
| Identity provider down for 30 s (80–110 s) | 90.4% | 244 ms | No component is saturated | $599 |
| Local JWT checks for reads, identity provider down | 98.9% | 193 ms | No component is saturated | $599 |

Behind the headlines: the identity provider is priced per active user, so it adds nothing to the cost and every scenario
costs the same. The baseline's cache hits 90.7%; the identity provider averages 109 calls/s and 152 in the last 20 s. Without
the cache the quota refuses 86% of 184,519 calls; 51.5% of requests succeed between 20 and 40 s (310 to 470 req/s) and 15.7% in
the last 20 s; over the run the mobile app keeps 26.2% (1.85 attempts per request), the web app 14.5% and partners 35.7% (2.59
attempts). With a 10 s TTL the hit ratio is 41.6% and the identity provider refuses 69% of its calls; success falls from 99.6%
between 20 and 40 s to 67.0% in the last 30 s, and over the run the mobile app keeps 76.7%, the web app 66.4% and partners
82.6%. In the outage the identity provider refuses 16,966 calls and 9,101 requests fail; success falls through the window as
cached checks expire (76.8%, 66.7% and 58.1% in its three 10 s thirds), and afterwards 2,843 introspections are rate limited
(89.1% succeed in the next 10 s, 99.1% in the 10 s after). With local JWT checks the identity provider sees 54.6 calls/s at the
peak and refuses 2,166 during the outage, and every one of the 1,161 failed requests is a write; p95 is 193 ms against the
baseline's 235 ms, because a read no longer waits for an introspection on a miss.

Reviewed by: ______________________ (name, role, date)

## Checkout call chain: deadlines and retries per hop: `microservices-call-chain`

A checkout behind an Envoy ingress across four services with their own stores: a Node.js BFF, Java
orders (PostgreSQL plus a Redis order-history cache), Python pricing (PostgreSQL quotes, reached
through an Envoy sidecar route) and Go inventory (DynamoDB). Web shop and mobile app, 600 req/s,
a quarter of them checkouts; the scenarios hang pricing for 30 s and break one rule at a time.

**Checked.** Request paths (order-history reads: the cache, then PostgreSQL on a miss, never
pricing; checkouts: pricing, inventory, the insert, then the cache invalidation, `order` 0 to 3),
the cache pattern (cache-aside: the database link has `readsOnMiss` and order 2, the cache link
order 3 with a 50 ms timeout and `continueOnFailure`), the deadline budget (orders' worst
checkout is a 500 ms queue wait + 800 ms pricing + 500 ms inventory + 500 ms database + 50 ms
cache = 2.35 s, inside the BFF's 2.5 s, inside the ingress's 3 s route timeout, inside the apps'
5 s and 8 s; pricing's 300 ms queue wait plus its 300 ms quote write fit its 800 ms; inventory's
200 + 200 ms fit 500 ms), retries at one layer (only the mobile app, 2 attempts with 500 ms
backoff and an idempotency key on checkouts: 10.5 s worst case), pools (Tomcat's 200 threads per
orders pod, 8 Python workers per 2 vCPU pricing pod, PostgreSQL pools of 8 on 2 vCPU, the most the
validator accepts without a warning), Envoy's `upstreamTimeoutMs` on both routes (3 s and 800 ms),
the sizes (auto, except pricing's CPU and the two databases). The baseline's design review has no
timeout or retry finding; the retry scenario's three high findings ("Up to 54 attempts reach
Pricing per request") are its story.

**Tuned while building.**
- The circuit breaker was first at the ingress (256 requests in flight to the BFF). It counts every
  route, so whenever checkouts piled up it turned order-history reads away too, and it made every
  bad scenario worse (in a test run without the timeout: 42.6% success in the window with it, 61.6%
  without). It
  now sits on the pricing route (an Envoy sidecar) and has a scenario of its own: in the baseline
  at 64 it contained both the missing timeout and the inverted timeouts, so those scenarios showed
  nothing.
- The wasted-work finding counts only work finished after the *client* gave up, not after an inner
  caller timed out, so the inverted scenario turns the whole budget around (apps shortest) rather
  than one inner pair.
- The fault is a latency fault on pricing's link, not a slowdown of pricing: a slowdown saturates
  pricing's 32 workers, its own 300 ms queue timeout sheds the excess, and a missing timeout
  upstream then changes little. A hang, the case timeouts exist for, needs pricing to answer late,
  not to refuse.
- Pricing's CPU is set (2 vCPU per pod). Auto-sized at 1 vCPU it ran at 56% CPU in the baseline and
  turned requests away on CPU in the fault scenarios before the story started.
- The design review ignores `upstreamTimeoutMs`, so both Envoy links also carry the route timeout
  as a link timeout (the same value); otherwise the baseline's review says "waits indefinitely".
- The apps' timeouts went from 10 s each to 8 s (web, one attempt) and 5 s (mobile, two attempts),
  so the inverted scenario separates them: the web shop waits out a 5 s checkout, the app does not.
- The BFF runs one event loop per pod (first two), matching its auto-sized 1 vCPU.

**For reviewers.** Is 800 ms a fair deadline for a quote whose p99 is under 100 ms? Is a hang
(every call 5 s late) the right test for timeouts, rather than errors? Envoy's ingress breaker at
its default 1,024 opens in the retry and inverted scenarios: would you set a tighter one at the
edge, knowing it cannot tell a read from a checkout? The sidecar is drawn and priced as one proxy;
in a mesh it runs in every orders pod. Orders calls pricing and inventory one after the other (the
engine has no parallel fan-out); a real service might do both at once, which shortens the budget.

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (deadline budget, one retry layer) | 100% | 197 ms | No component is saturated | $1,293 |
| Pricing answers 5 s late (40–70 s), as drawn | 91.6% | 197 ms | No component is saturated | $1,293 |
| Pricing late, retries at every hop | 89.4% | 573 ms | Pricing is the primary bottleneck | $1,293 |
| Pricing late, inverted timeouts | 90.4% | 1,168 ms | Orders is the primary bottleneck | $1,293 |
| Pricing late, no timeout on orders → pricing | 87.4% | 632 ms | Orders is the primary bottleneck | $1,293 |
| Pricing late, no timeout, circuit breaker | 91.4% | 193 ms | No component is saturated | $1,293 |

Behind the headlines: the fault lasts 30 of the 90 measured seconds, so whole-run success hides
the differences; inside the window success is 75.5% as drawn, 67.9% with retries at every hop,
70.6% with inverted timeouts, 62.3% without the timeout and 74.6% with the breaker. As drawn and
with the breaker, all 40,499 order-history reads succeed and every failure is a checkout (4,558
time out at orders after 800 ms; with the breaker, 4,424 get an immediate 503 and 233 time out at
the BFF). With retries at every hop, pricing receives 593 req/s against 152 and rejects 62.3% of
it, 3,743 of its completions (19%) finish after the client has given up, the ingress breaker opens
18,159 times and 1,740 reads fail. With inverted timeouts the web shop's p95 is 5,056 ms (its
checkouts succeed, slowly), the mobile app times out 1,285 times, 2,941 checkouts complete after
the app gave up (21% of inventory's work) and 2,547 reads fail. Without the timeout, orders' 800
threads peak at 100%, its queue at 400, 3,522 requests fail in its queue and 2,987 time out at the
BFF; 2,444 of them are reads. The headline of the as-drawn and breaker scenarios is "No component
is saturated" because nothing is: the failures are timeouts and 503s, which the fault-impact
finding reports.

Reviewed by: ______________________ (name, role, date)

## Image uploads: API Gateway, Lambda, SQS and S3: `serverless-image-pipeline`

A photo app's upload path on managed AWS services: API Gateway, a Node.js Lambda that stores the
original in S3, writes DynamoDB metadata and queues a job on SQS, and a Python resize Lambda behind
the queue (reserved concurrency 300). Mobile (70%) and web clients, 150 req/s with a viral spike to
900 req/s at 20–50 s in every scenario; half the requests are status reads.

**Checked.** Request paths (an upload PUTs the original, puts the item, then sends the job, in that
order; a status read only reads DynamoDB; the worker GETs the original, PUTs the sizes, then marks
the item ready), the timeout chain (the API's worst case, 2.5 s PUT + 300 ms item + 500 ms send +
a cold start, fits API Gateway's 8 s integration timeout, lowered from its 29 s default so it gives
up before the clients' 10 s; the worker's worst case, 5 s GET + 5 s PUT + 500 ms update + the resize
(4 s at p99 on 1 vCPU, 34 s at most on 512 MB), fits its 60 s function timeout), the visibility
timeout (360 s, six times the function timeout, as AWS advises), redelivery (5 receives, then the
dead-letter queue), retries at one layer per path (the mobile app retries an upload, which its
client-generated photo id makes safe; SQS redelivers a failed job; the SDK calls make one attempt),
the queue (one shared SQS queue, `partitions` 10,000, 300 consumers standing for the event source's
maximum concurrency, equal to the reserved concurrency so the poller is never throttled), Lambda
sizes (1,792 MB is one vCPU, 1 GB about 0.58), DynamoDB on demand (8 partitions, far from their
limits).

**Tuned while building.**
- A visibility-timeout scenario was dropped. Lambda refuses to connect a queue whose visibility
  timeout is below the function timeout, and the engine does not stop a function at its timeout,
  so duplicates from a short visibility timeout cannot be shown honestly for a Lambda consumer
  (`flash-sale-order-flow` shows them for a container worker). The six-times rule is in the design.
- Poison images were dropped. Failures in the engine are random per call, never tied to a message,
  so a message that always fails cannot be modelled; and a failed delivery is visible again at
  once, so an outage dead-letters within seconds where SQS would wait a visibility timeout between
  receives (see Follow-ups).
- For the same reason a test with reserved concurrency below the event source's concurrency (300
  pollers, 100 instances) dead-lettered 10,896 jobs: throttled deliveries come straight back. On
  AWS they return minutes apart. The scenario lowers both together, which is also AWS's advice.
- The function timeout was 30 s at first (visibility 180 s). At 512 MB one resize ran past it; the
  engine kept that instance busy after the poller gave up, and the poller spun through 274
  throttles. 60 s leaves room.
- The runtime scenario moved from the Photos API to the worker. On the API, Java's slower cold
  starts all fell in the warm-up (123 instances, none started in the window) and p95 did not move;
  on the worker every instance is new during the spike and the JIT warm-up shows.
- The SDK calls first made 2 to 3 attempts; with the app's retry on top the review reported "Up to
  12 attempts reach Photo metadata (DynamoDB) per request". One retry layer per path keeps it quiet.

**For reviewers.** Is 0.8 s median (2.5 s p95) a fair Pillow resize to three sizes on one vCPU?
Would you keep the SDK's own retries in the worker, where a redelivery repeats the GET and the
resize? In the 512 MB scenario only the resize slows with the CPU (S3 and DynamoDB waits do not),
which is how power tuning usually comes out for this kind of function: fair? Two review findings
do not apply: "Photos API (Lambda) gives up before resize-jobs (SQS) does" (a producer waits only
for the publish) and "Photo metadata (DynamoDB) has no replica" (see Follow-ups). Presigned URLs
and S3 event notifications, the usual production design, cannot be drawn (a client has one link,
S3 emits no events).

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (a post goes viral at 20–50 s) | 100% | 329 ms | No component is saturated | $20,419 |
| Reserved concurrency 100 | 100% | 329 ms | Resize worker (Lambda) is the primary bottleneck | $12,093 |
| Resize worker at 512 MB | 100% | 329 ms | Resize worker (Lambda) is the primary bottleneck | $10,995 |
| Resize worker on Java | 100% | 329 ms | No component is saturated | $22,596 |
| S3 8× slower for 30 s (80–110 s) | 99.98% | 443 ms | No component is saturated | $21,661 |

Behind the headlines: the request path barely changes because the queue absorbs everything; the
story is in the queue's numbers. Backlog peak, p95 job age and jobs left when the run ends: 5,641,
21.5 s and none as drawn (drained 30 s after the spike); 10,852, 91.1 s and 4,770 with 100
instances; 10,970, 92.9 s and 5,531 at 512 MB; 7,694, 29.5 s and none on Java (drained 12 s
later); the S3 slowdown leaves the spike's 5,641 and 21.5 s unchanged. No job is dead-lettered or
redelivered in any scenario. A job costs 2.0 GB-seconds as drawn (1,149 ms at 1.75 GiB), 1.8 at
512 MB (3,626 ms at 0.5 GiB) and 2.3 on Java (1,323 ms). The monthly figures extrapolate the run's
average rate, spike included, to a month (about 14 million resizes a day); the two cheaper rows
processed about half as many jobs in the run, so they are not savings. The Photos API scales to 155
instances with 120 cold starts in the window (0.3% of its requests). In the S3 slowdown the
window's p95 rises from 336 ms to 1,297 ms and 9 uploads fail on the 2.5 s PUT timeout.

Reviewed by: ______________________ (name, role, date)

## RAG assistant: living within the LLM provider's limits: `llm-rag-assistant`

A Node.js chat API embeds each question, looks for an answer to a similar question in a Redis semantic cache, and only on a miss
searches an OpenSearch k-NN vector store and asks a hosted LLM (30 calls/s, 150 in flight). A web chat and integrations are the
client groups; uploads go through SQS to an ingest worker that embeds and indexes them. 40 questions/s, 5% of requests uploads.

**Checked.** Request paths (a question: the embedding, then the cache lookup, then on a miss both `readsOnMiss` links in order,
the k-NN search and then the completion, and the answer written back; an upload: only the publish to SQS, and the worker embeds
and bulk-indexes, so nothing on the write path reaches the LLM or the cache), the cache pattern (cache-aside, a 50 ms lookup
that carries on if Redis fails), timeouts (the completion's 20 s × 2 attempts + 1 s backoff, the embedding's 2 × 2 s and the 2 s
search come to about 47 s, inside the load balancer's 55 s and both clients' 60 s; the SQS consumer gives up at 45 s, inside the
60 s visibility timeout, and the worker's own worst case is about 43 s), retries (completions, embeddings and searches change
nothing, so retrying them is safe; the design review's "Up to 4 attempts reach LLM completion API per request" is the
integrations' 2 times the SDK's 2, which the launch scenario shows), the provider's limits against the baseline (23 calls/s
against 30; about 72 in flight against 150, Little's law at about 3.1 s a call), the vector store (three shards with one replica
each, every search scattered over all three; 24 GiB of vectors and graphs, 8 GiB a shard, fit in 16 GiB per node, buffer hit
ratio 1.0), SQS partitions (10,000, so all 16 consumers work). On AWS: chat API 3 pods of 2 vCPU, Redis cache.m6g.large,
OpenSearch 6 × x2gd.medium (self-managed, 1 vCPU and 16 GiB: the cheapest type with room for the graphs at this load), an ALB.

**Tuned while building.**
- The LLM preset (50 calls/s, 100 in flight, about 2.1 s a call) cannot reach its own rate limit: 50 calls/s × 2.1 s is about
  107 in flight, so its cap binds first. The template has 30 calls/s and 150 in flight, so the quota binds on a spike and the
  cap only when the provider slows down.
- "An ingestion burst slows retrieval" was dropped. The read/write mix is fixed for a run, so a burst of uploads alone cannot be
  scheduled (see Follow-ups). Ingestion stays in the design as a write path that never calls the LLM.
- The vector store first had 4 vCPU set and memory on auto, which snapped to c6g.xlarge (8 GiB), too small for k-NN graphs, and
  a working set with auto memory is a validation warning. It now sets 16 GiB and leaves CPU on auto (never both).
- Uploads were keyed with the workload's Zipf skew, so the design review warned about a hot vector-store shard. Chunks are routed
  by document id, so the keys are uniform now (skew 0).
- Launch day with only the rate limit raised (80 calls/s, cap still 150) answered 92.4% of questions during the spike in an extra
  run: the cap binds, as the scenario's Little's-law sentence says. The scenario raises both.

**For reviewers.** The headline is "No component is saturated" in every scenario, even when a quarter of questions fail: a
provider's 429s and throttles are not rejections of one of your parts, so nothing is saturated and the critical throttling
finding sits further down (see Follow-ups); the scenario copy points at it. Is 40% a fair semantic-cache hit rate for a support
assistant? Is 30 calls/s and 150 in flight a fair account quota (providers state tokens per minute, which the engine cannot
count)? Would you let the SDK retry a 429 in an interactive chat, or fail fast and show "try again"? The embeddings API has one
latency for a question and for a batch of chunks (one distribution per part). The design review's "Chat API gives up
before ingest-jobs (SQS) does" and "5 synchronous hops" count the ingest worker as part of the upload's request, although
the upload is answered once the job is queued (see Follow-ups).

Results (engine 0.12.0, seed as in the template):

| Scenario | Success | p95 | Headline finding | Per month |
| --- | --- | --- | --- | --- |
| Baseline (40 questions/s) | 100% | 6,413 ms | No component is saturated | $185,522 |
| Launch day: 100 questions/s for 45 s (50–95 s) | 86.8% | 6,287 ms | No component is saturated | $405,523 |
| Launch day with raised LLM limits (80 calls/s, 400 in flight) | 100% | 6,287 ms | No component is saturated | $277,861 |
| Semantic cache down for 30 s (60–90 s) | 94.3% | 7,080 ms | No component is saturated | $264,161 |
| LLM provider 3× slower for 40 s (60–100 s) | 93.6% | 9,914 ms | No component is saturated | $226,924 |

Behind the headlines: LLM calls are 98.5% of the baseline's monthly cost (embeddings 1.1%; everything the team runs, about $740,
0.4%), which is realistic for an assistant at this volume; the per-call price is an assumption (about 3,000 prompt and 300 answer
tokens at mid-range prices). Cache hits take 0.1 to 0.25 s, misses about 3 s, and the LLM is 90% of a successful question's
time. During the launch spike 76.8% of questions are answered and 103 LLM calls/s arrive (the SDK's retries) of which 30 are
served; the throttling finding says 49% of LLM calls were turned away; the web chat keeps 84.1% over the run and the
integrations 95.0% (1.16 attempts per question). The launch's monthly figure is inflated: the engine bills every call that
arrives, 429s included (51 calls/s billed on average, 25 served; see Follow-ups). With raised limits in-flight calls peak at 202
(mean 171 during the spike). With Redis down, 2,212 lookups are refused, no question fails at the cache, 66 calls/s reach the
LLM and 76.9% of questions are answered in the window; it recovers about 2 s after Redis returns. In the slowdown, 80.9% are
answered in the window (p95 13.0 s), the LLM throttles 534 calls at its cap and rate-limits 482 more, in flight sits at 150,
and it recovers 14 s after the fault; the chat API stays at 3.4% CPU and 0.38 GiB peak memory. Uploads are indexed about 0.4 s
(p95) after they are queued in every scenario.

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
  fifteen new templates are not in it. The README's gallery screenshot and its alt text still say 10
  templates and 45 scenarios (now 25 and 124).

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

Found while building the ten templates of the second batch (none fixed here; engine code was out
of scope):

- **The headline misses refused calls and planned outages.** When a third-party provider's quota
  or concurrency cap refuses calls, or a planned outage fails requests, no part counts as
  saturated, so the results lead with "No component is saturated" even at 24% success (API auth,
  "Introspect every call"); the critical throttling or fault-impact finding comes later. It
  should lead.
- **Third-party cost bills refused calls.** `cost.py` prices calls at their arrival rate, so 429s
  and calls refused during an outage are billed (the RAG launch bills 51 calls/s against 25
  served; 40 e-mail senders nearly triple the e-mail line).
- **Monthly cost extrapolates a run's average rate, spikes included,** to 730 hours, so a template
  with a campaign or a viral spike in its baseline shows a month of spikes (notifications, image
  pipeline), and scenarios that process less work look cheaper.
- **Sharded caches are priced as one node** (`cost.instances_of` reads `nodes`): the social feed's
  six-shard Redis Cluster is understated.
- **OpenSearch/Elasticsearch coordinator time is never applied.** The profile's `coordinator_ms`
  (1 ms) is not read; the runtime uses `router_overhead_ms`, whose auto value is 0. The search
  template sets `routerOverheadMs` by hand.
- **A database's auto memory follows the auto-sized CPU,** not the CPU set on the part (8 vCPU
  set gives 64 GiB), and a scenario's CPU override keeps the design's memory.
- **Queue redeliveries are immediate.** A failed delivery goes back to the queue at once, where
  SQS waits for the visibility timeout and JetStream for its backoff, so dead-lettering is far
  faster than in production; a backoff can only be modelled as link retries that hold the
  consumer. There is no per-message failure either (poison messages), no rate limit on a queue's
  consumers (pacing only by worker count), and deliveries go round-robin over the consumer
  service's replicas rather than to the pod that owns the consumer.
- **Dropped messages have no finding or objective.** Core NATS drops show only in the queue's
  `dropped` counter; the lag finding stays informational and the fault finding says "absorbed".
- **Findings and the design review around queues and managed services:** "X dominates request
  latency" fires for providers behind a queue; the design review treats a queue's consumer chain
  as part of the producer's request (timeout-inversion and "synchronous hops" false positives),
  ignores a load balancer's `upstreamTimeoutMs` ("waits indefinitely"), does not flag a missing
  timeout on a link to a load balancer, and calls DynamoDB a single point of failure.
- **Serverless:** a function is never stopped at its timeout, account concurrency is not shared
  between functions, and provisioned concurrency, SnapStart and gradual poller scale-up are not
  modelled.
- **Search:** shard copies are separate instances, one pool per instance serves searches and
  writes (OpenSearch has separate pools), a scattered search fails if any shard rejects (no
  partial results), and the read-replicas finding can exceed 100% when searches fail on another
  shard.
- **CDN:** no request collapsing, no stale-if-error, one miss link (no origin failover), priced
  per hour rather than per GB, and a retry through a random load balancer can land on another POP.
- **Databases:** semi-synchronous replication (wait for receipt, not apply) is not modelled; stale
  reads count any reader (no read-your-writes routing); a database's utilization averages idle
  replicas; blocked readers have no wait-time metric or finding; row locks are per shard, not per
  instance; RCSI's version-store cost is not modelled; reads and writes share one key
  distribution; the price list has no RDS for SQL Server (the template names x86 families and
  leaves out the licence).
- **Workload and routing:** the read/write mix is fixed for a run (no burst of one request type,
  such as a bulk import); there is no "call B only if A fails" fallback link and no stale-on-error
  cache; overrides cannot remove a link or a part; each request draws its key independently (no
  per-user bursts or request coalescing); third-party APIs have one latency distribution and no
  tokens-per-minute quota, streaming or provisioned-throughput pricing.
- **Smaller ones:** circuit breakers are per target only and retries after an open circuit do not
  back off; wasted work counts only work finished after the client gave up; a sidecar is drawn and
  priced as its own proxy; JetStream is priced like core NATS; core NATS's per-subscriber pending
  buffers are one shared `maxDepth`.
