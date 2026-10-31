# W23 trial: Kafka's publish tail and the consumer's fetch cycle (31 Oct 2026)

Development data for accuracy plan W23, not a benchmark run: `run.py` and `report.py` never read
`results/trials/`, and nothing here is on /accuracy. One pass is a calibration pass (10 s
warm-up, 30 s recorded) at a fixed rate; no knee was measured by the protocol. Kafka is an
in-sample experiment. `trial.py` ran it (`--raw` kept the consumer's raw kafkajs events outside
the repo); `override.yaml` is what variants 2 and 3 added to `compose.yaml`.

- `diagnostics.json` (13:16 to 13:32 UTC+1): three variants back to back, one stack each. The
  host was at 18% CPU when the lock was taken and 3% to 53% before each pass; the other jobs
  running were other sessions' trial scripts waiting for the lock and an editor runtime
  (`atStart.otherJobs`).
  1. As benchmarked (compose.yaml before this trial): 569 req/s twice (the top of the kept runs'
     calibration staircases), 1,050, 1,575 and 1,890 req/s.
  2. The broker's healthcheck run only until it first succeeds, and the broker's JMX on: rest,
     525, 1,050, 1,575 and 1,890 req/s.
  3. As 2 with four producer connections (`KAFKA_PRODUCERS=4`) instead of one: 1,050 to 1,890.
- `knee.json` (15:49 to 15:57): the stack with compose.yaml's fixed healthcheck, one pass each at
  1,890 to 3,300 req/s, after a 30 s warm-up at 50 req/s; host at 6% to 15% CPU when the lock was
  taken (nothing else running: the main session's lock order) and 7% to 40% before each pass.

A first attempt at 11:50 (a full protocol v5 calibration plus these passes) was stopped: the host
was at 100% CPU from other sessions' re-simulations and tests, and the staircase stopped at
50 req/s. Nothing from it is kept.

## The publish tail: the healthcheck, not Kafka

The broker's healthcheck ran `kafka-broker-api-versions.sh`, a JVM tool, every 3 s for the
stack's whole life, on the broker's cpuset (CPUs 0 and 1: one physical core).

| Client p95 (ms) | kept run (latest) | 1. as benchmarked | 2. healthcheck once | 3. and 4 connections | engine 0.14.0 |
| --- | --- | --- | --- | --- | --- |
| 525 / 569 req/s | 4.64 (525) | 4.05, 3.67 (569) | 1.71 (525) | - | 3.32 (525) |
| 1,050 req/s | 10.5 | 5.51 | 3.25 | 4.25 | 3.39 |
| 1,575 req/s | 22.4 | 21.3 | 5.15 | 3.44 | 3.67 |
| 1,890 req/s | 42.5 | 41.3 | 4.99 | 5.05 | 4.94 |

- The broker's container used 0.57 cores at rest, of which the broker JVM 0.05; with the
  healthcheck run once, 0.035 cores, all of it the broker. Under load the container used 1.0 to
  1.1 cores as benchmarked and 0.45 to 0.49 without the repeated check; the broker JVM itself
  0.5 to 0.8 either way.
- As benchmarked, the sends in flight on the producer's one connection grew with load (mean 0.2,
  0.9, 5.0 and 10.9) while the median send stayed at 0.5 ms: publishes queued while the broker
  waited for its CPU. Without the repeated check: 0.07, 0.53, 1.07 and 1.28.
- The broker spends 0.02 to 0.06 ms on a publish (JMX `TotalTimeMs`, Produce, variant 2), so one
  connection could carry far more than these rates; four connections changed the p95 little.
- Broker GC: 1 to 4 young pauses per pass, 10 to 38 ms each, in every variant: too rare to set a
  p95. Windows' 15.6 ms timer tick: no latencies clustered near it (run.py envcheck), and the
  tail grows smoothly with load.

So the publish tail in the kept runs is the benchmark's environment. It is not modelled;
compose.yaml's healthcheck now stops starting JVMs once the broker is ready.

Every kept Kafka run had the repeated check (it is in compose.yaml since the first benchmark
commit, 4a2e9c6): run 1 and run 2 (v4), run 3, run 4 and the latest (v5). Each shows the broker's
container at 0.74 to 1.08 cores at every step, 0.74 already at 252 req/s; the v5 runs' rest
passes 0.42 to 0.43 cores. Their measured knees (945 req/s before the Windows update, then 2,040,
2,132, 2,087 and 2,028) are the harness's, not Kafka's.

With the check fixed (`knee.json`):

| Offered req/s | 1,890 (first pass) | 2,100 | 2,400 | 2,700 | 3,000 | 3,300 |
| --- | --- | --- | --- | --- | --- | --- |
| client p95 (ms) | 36.6 | 13.2 | 15.0 | 26.1 | 31.5 | 41.8 |
| success | 99.3% | 99.7% | 99.9% | 99.5% | 99.3% | 98.3% |
| producer CPU (cores) | 0.71 | 0.72 | 0.78 | 0.82 | 0.87 | 0.89 |
| producer CPU per request (ms) | 0.38 | 0.35 | 0.33 | 0.31 | 0.29 | 0.27 |

The objective (p95 at most 50 ms, at least 99% success) held up to 3,000 req/s and failed at
3,300 on success (k6 dropped 1,678 of the requests it had no free virtual user for), with the
producer's event loop near its core: the fixed stack's knee is about 3,000 to 3,300 req/s. The
first pass, the stack's first under load, was slower than the next one at a higher rate (the
JIT warming up). Single 30 s passes, not the protocol's three repeats: a guide, not a result.

## The consumer's fetch cycle

kafkajs 2.2.4 (the service's consumer, `partitionsConsumedConcurrently` 3) fetches every
partition's backlog in one request, gives each partition's batch to one of three workers, commits
a batch's offsets when it is done and fetches again once all are done.

| Variant 1 at 569 req/s | pass 1 | pass 2 |
| --- | --- | --- |
| polls (cycles with messages) | 8,246 | 9,331 |
| cycle overhead: median / p95 / mean (ms) | 1.00 / 4.19 / 2.08 | 0.86 / 3.56 / 1.31 |
| the fetch: median / p95 (ms) | 0.53 / 1.57 | 0.45 / 1.22 |
| the last batch's commit: median / p95 (ms) | 0.42 / 1.41 | 0.38 / 1.10 |
| messages per poll (mean) | 2.1 | 1.8 |
| INSERT: mean (ms) | 1.74 | 1.68 |
| kafkajs's own time per message (ms) | 0.022 | 0.020 |
| message age: median / p95 (ms) | 4 / 15 | 4 / 15 |

Cycle overhead is each poll's time less its batches' least possible span on three workers (the
larger of their work over the workers it could use and the longest batch). The kept runs'
calibrations have no fetch-cycle data, so `run.py` gives them these two passes' means (median
0.93 ms, p95 3.88 ms; `KAFKA_FETCH_FALLBACK`); runs calibrated from now on measure their own.

Two limits seen here:

- At 1,050 req/s the message age's p95 (46 ms as benchmarked, 104 ms in variant 2; 132 ms in the
  kept run) comes from rare stalls: the median poll took 6 to 8 ms, but the slowest took 231 to
  807 ms with up to 335 messages, after which the consumer caught up. The model has no such
  stalls.
- At 1,575 and 1,890 req/s the consumer kept up with about 1,320 to 1,440 messages/s (INSERT
  1.87 to 1.94 ms), where the kept run's consumed about 1,650/s: the INSERT's time under load
  varies between runs. The W23 model's cap (about 1,360/s, set by its database inputs) lies
  between.
