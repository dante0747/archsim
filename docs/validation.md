# How accurate is ArchSim?

ArchSim predicts latency and capacity from a model. This page checks those predictions against
real systems running on one machine: for each stack, the model is calibrated from low-load runs,
ArchSim predicts every higher load **before** it is measured, and then k6 measures it. The tables
show the latest run of each experiment, good or bad. `benchmarks/results/` keeps the calibration,
the predictions and every measured repeat's numbers as extracted from k6's end-of-run summary; the
k6 summaries themselves are scratch output and are not committed.

## Headline

<!-- BEGIN GENERATED: summary -->
| Experiment | Knee measured | Knee predicted | Knee error | p95 error at or below the measured knee (median / worst) |
| --- | --- | --- | --- | --- |
| [pg-mixed](#pg-mixed) | 2,291 req/s | 2,064 req/s | -10% | 67% / 18316% (6 steps) |
| [pg-hot-row](#pg-hot-row) | 225 req/s | 444 req/s | +97% | 29% / 55% (2 steps) |
| [cache-aside](#cache-aside) | 4,016 req/s | 3,457 req/s | -14% | 29% / 101649% (6 steps) |
| [kafka](#kafka) | 945 req/s | 1,006 req/s | +6% | 211% / 332% (4 steps) |
<!-- END GENERATED: summary -->

The knee is the highest load that still meets the objective (p95 at most 50 ms with at least 99%
of requests successful), interpolated between the two measured steps around it; the predicted
knee uses the same steps and the same interpolation. The p95 error covers the steps at or below
the measured knee: error = (predicted - measured) / measured.

Read these numbers as one run on one laptop, not as a measure of ArchSim's accuracy:

- **One run each, inside run-to-run noise.** Every row is a single run under the final protocol
  (v4). The measured knee itself moves from run to run on the same machine: pg-mixed's was 1,895,
  3,162 and 2,291 req/s in runs 2, 3 and 4 (see [Protocol history](#protocol-history)), a spread
  far wider than the -10% error of run 4; pg-hot-row's ranged from 132 to 819 req/s over runs 1
  to 4.
- **pg-mixed and pg-hot-row are in-sample.** The protocol was revised (v2, then v3, then v4)
  after the high-load results of those two experiments had been seen. Each revision fixed a flaw
  in the calibration, but it was chosen with their data in view. cache-aside and Kafka were first
  run under the final protocol, so only their numbers are out-of-sample.
- **Latency at low load is high in every experiment, and far off for Kafka**: p95 +0.1 to
  +1.4 ms for pg-mixed, pg-hot-row and cache-aside, about +8.7 ms (+303% and +332%) for Kafka
  (see [below](#where-the-model-is-wrong-and-why)).
- **Nothing is validated.** No experiment meets the [proposed tier
  thresholds](#proposed-tier-thresholds), which are computed from this data.

## Reproduce

Each stack is one command (Docker and [uv](https://docs.astral.sh/uv/) needed; 20 to 30 minutes
per experiment on the machine below). Keep the machine otherwise idle while it runs: other work
shows up in the measurements (see the protocol history):

```bash
uv run python benchmarks/run.py postgres   # pg-mixed and pg-hot-row
uv run python benchmarks/run.py cache      # cache-aside
uv run python benchmarks/run.py kafka      # kafka
uv run python benchmarks/report.py         # rewrite this page's tables and charts
```

`make benchmark` runs all of them. `uv run python benchmarks/run.py compare` recomputes every
`comparison.json` from the committed measurements and predictions without Docker. Results depend
on the machine: rerun on yours before quoting numbers. A manual GitHub Actions workflow
(`.github/workflows/benchmark.yml`) runs them on a hosted runner. Every run so far was on Windows
with Docker Desktop; on Linux, neither the workflow nor the local runs have been tried (the k6
container writes its summary as uid 12345, so the runner makes `benchmarks/results/.k6`
world-writable for it, untested on Linux).

## Protocol

Each experiment (`benchmarks/run.py`) runs on a fresh stack (`benchmarks/compose.yaml`): a small
Node.js service (`benchmarks/service/server.js`, no framework), PostgreSQL 16, Redis 7 and Kafka
3.8 as each experiment needs, and k6 as the open-loop load generator (constant arrival rate,
like ArchSim's clients). Every container is pinned to its own CPUs.

1. **Settle** (protocol v4): after the data load, `VACUUM ANALYZE` and a checkpoint, then a
   discarded 30 s warm-up at the calibration rate, so autovacuum and cold caches do not land in a
   calibration pass.
2. **Calibrate** in two passes of the experiment's traffic: 50 req/s, then a quarter of the most
   pessimistic capacity the first pass implies (still low load). The service records its CPU per
   request (Node's process CPU, including garbage collection and driver work), its own time per
   request (handler minus awaited calls) and the time of every PostgreSQL, Redis and Kafka call;
   the runner reads PostgreSQL's and Redis's own CPU and command time. CPU per request is the
   slope between the passes (the fixed CPU a process burns at any rate cancels out); timings come
   from the second pass, and the one input not measured directly (the client-to-service
   latency) is set so the model reproduces the second pass's median. A pass whose p95 is more
   than 10 times its median was disturbed: it is recorded, discarded and run again. Each input is
   derived by a formula recorded next to it in `calibration.json`.
3. **Predict**: the runner builds the ArchSim design (one Node.js event loop on 1 vCPU, a pool of
   10 database connections, PostgreSQL on 2 vCPU, and so on), searches the knee, and simulates
   every step of the ladder: 0.25, 0.5, 0.75, 0.9, 1.0, 1.1, 1.25 and 1.5 times the predicted
   knee. `predictions.json` is written before the first measured step. When the system still
   meets the objective at the top of the ladder, the runner climbs by 30% (at most four times),
   predicting and saving each new step before measuring it.
4. **Measure** each step three times: 5 s of warm-up, then 20 s measured, 2 s client timeout;
   each metric is the median of the three (robust to one stall), and a step whose three p95s
   differ by more than 2x across the objective is marked unstable. p50, p95 and p99 are over
   successful requests started in the window, as in ArchSim's reports; requests k6 could not
   send in time count as failures. A repeat in which k6 sent nothing (the load generator failed)
   is recorded, discarded and run again. A repeat in which requests were sent and none succeeded
   is a failed repeat: it has no latency, and in the step's medians it counts as slower than any
   measured repeat (run 4 recorded two such repeats as 0 ms; the comparison now treats them as
   failed). Since run 4, the runner also waits (at most 60 s) between repeats until the service
   answers its health check and PostgreSQL has no query left running (except on the Kafka stack,
   which already waits for its backlog to drain); this wait has not run against the stacks yet.
5. **Compare** step by step (`comparison.json`), and the knee.

Nothing measured at steps 4 and 5 feeds back into the model: the predictions use only the
calibration passes.

### Protocol history

Earlier runs of pg-mixed and pg-hot-row are kept in `benchmarks/results/<experiment>/run-N/`;
cache-aside and Kafka were first run in run 4. The protocol changed between runs, each time after
a run exposed a flaw in the calibration. The changes were made with the PostgreSQL experiments'
high-load results in view, so those two experiments are in-sample for the final protocol (v4):

| Run | Protocol | What it showed |
| --- | --- | --- |
| 1 | one pass at 50 req/s, CPU per request as measured | At 50 req/s a laptop's cores sit in low-power states, so CPU per request came out about 4x what it costs under load. |
| 2 | two passes, CPU per request from the slope (v2) | Fixed CPU cancels out; but the database driver's CPU was counted twice (in the CPU per request and inside the measured query time), so the model started about 2x too slow. Measured steps overlapped with other work on the machine. |
| 3 | v2 plus the anchor at the calibration rate (v3) | pg-mixed's first pass met the post-load autovacuum (p95 280 ms at 50 req/s), which inflated CPU per request 3x and sent every input wrong; hot-row's single-row lock turned short host stalls (package installs and test runs in other work) into seconds-long queues. Neither run measured the model. |
| 4 | v3 plus settling and disturbed-pass retries (v4), machine otherwise idle | This page's numbers. |

Every kept run, compared the same way (`run.py compare`):

<!-- BEGIN GENERATED: history -->
| Experiment | Results | Protocol | Calibrated (UTC) | Knee measured | Knee predicted | Knee error |
| --- | --- | --- | --- | --- | --- | --- |
| pg-mixed | `run-1/` | v1 | 2026-10-26T08:00:55Z | > 1,359 req/s | 906 req/s | - |
| pg-mixed | `run-2/` | v2 | 2026-10-26T08:23:18Z | 1,895 req/s | 2,314 req/s | +22% |
| pg-mixed | `run-3/` | v3 | 2026-10-26T09:46:26Z | 3,162 req/s | 808 req/s | -74% |
| pg-mixed | latest (`results/pg-mixed/`) | v4 | 2026-10-26T11:13:41Z | 2,291 req/s | 2,064 req/s | -10% |
| pg-hot-row | `run-1/` | v1 | 2026-10-26T08:11:06Z | 303 req/s | 403 req/s | +33% |
| pg-hot-row | `run-2/` | v2 | 2026-10-26T08:42:10Z | 819 req/s | 418 req/s | -49% |
| pg-hot-row | `run-3/` | v3 | 2026-10-26T10:11:09Z | 132 req/s | 504 req/s | +281% |
| pg-hot-row | latest (`results/pg-hot-row/`) | v4 | 2026-10-26T11:33:38Z | 225 req/s | 444 req/s | +97% |
| cache-aside | latest (`results/cache-aside/`) | v4 | 2026-10-26T11:53:13Z | 4,016 req/s | 3,457 req/s | -14% |
| kafka | latest (`results/kafka/`) | v4 | 2026-10-26T12:22:50Z | 945 req/s | 1,006 req/s | +6% |
<!-- END GENERATED: history -->

The measured knee moved between runs too, not only the prediction. Each step was measured the
same way in runs 2 to 4 (three repeats with a 20 s measured window), but in runs 2 and 3 the
machine was doing other work and the database was not settled first.

## Environment

<!-- BEGIN GENERATED: environment -->
- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz; Docker 29.1.3 (linux/amd64) with 8 CPUs and 7.6 GiB; Windows-11-10.0.26200-SP0.
- Images: postgres `postgres:16-alpine`, redis `redis:7-alpine`, kafka `apache/kafka:3.8.0`, k6 `grafana/k6:1.3.0`, service `node:24-alpine + pg 8.23.1, redis 4.7.1, kafkajs 2.2.4`.
- CPUs pinned per container (cpuset; k6 gets the CPUs the stack leaves free): pg-mixed and pg-hot-row: service 1 (`2`), PostgreSQL 2 (`4,5`), k6 5 (`0,1,3,6,7`); cache-aside: service 1 (`2`), PostgreSQL 2 (`4,5`), Redis 1 (`1`), k6 4 (`0,3,6,7`); kafka: producer 1 (`2`), consumer 1 (`3`), PostgreSQL 2 (`4,5`), Kafka 2 (`0,1`), k6 2 (`6,7`). These are the runner's defaults (`benchmarks/run.py`, `compose.yaml`): the runs shown predate recording the cpusets in `environment.json`.
<!-- END GENERATED: environment -->

## pg-mixed

<!-- BEGIN GENERATED: pg-mixed -->
**Node.js + PostgreSQL: 90% reads, 10% writes over 100,000 rows.** Calibrated 2026-10-26T11:13:41Z, predicted 2026-10-26T11:15:51Z (engine 0.7.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![pg-mixed: predicted and measured p95 and throughput](validation/pg-mixed.svg)

Model inputs from the calibration passes at 50 and 265 req/s (timings from the 265 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.4236 ms, p95 0.985 ms | mean = Node's CPU per extra request between the passes: (4532 - 1415 ms) / (7951 - 1501 requests) = 0.4832 ms (at 50 req/s alone: 0.9430 ms); shape (p95/median 0.0351/0.0151 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0 ms | anchored: (measured median 0.972 ms - the model's median without it 1.013 ms at 265 req/s) / 2, one way, at least 0 |
| `readServiceTimeMs` | lognormal, median 0.4911 ms, p95 1.1314 ms | SELECT by key as the service sees it (round trip): median 0.491, p95 1.131 ms |
| `writeServiceTimeMs` | lognormal, median 0.6525 ms, p95 1.4308 ms | UPDATE with synchronous_commit off (no WAL flush wait): median 0.652, p95 1.431 ms |
| `commitFlushMs` | lognormal, median 1.0968 ms, p95 2.2908 ms | UPDATE with synchronous_commit on minus off: median 1.749 - 0.652 ms, p95 3.722 - 1.431 ms |
| `dbCpuShare` | 0.4458 | PostgreSQL CPU per extra query between the passes (0.3314 ms) / mean query time (0.744 ms) |

Measured in the calibration pass at 265 req/s: p50 0.97 ms, p95 2.74 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |
| --- | --- | --- | --- | --- | --- | --- |
| 516 | 0.53 / 1.01 | 1.57 / 2.47 | +58% | 2.22 / 3.39 | 100% / 100% | 516 / 516 |
| 1,031 | 0.46 / 1.03 | 1.44 / 2.52 | +75% | 2.27 / 3.60 | 100% / 100% | 1,031 / 1,031 |
| 1,546 | 0.76 / 1.14 | 4.18 / 2.73 | -35% | 20.9 / 3.82 | 100% / 100% | 1,546 / 1,546 |
| 1,856 | 0.72 / 1.45 | 3.98 / 3.74 | -6% | 11.4 / 5.24 | 100% / 100% | 1,856 / 1,856 |
| 2,062 | 0.80 / 11.6 | 12.4 / 27.7 | +123% | 37.6 / 34.4 | 100% / 100% | 2,058 / 2,064 |
| 2,268 (unstable) | 0.75 / 1,264 | 10.4 / 1,916 | +18316% | 34.0 / 1,994 | 100% / 79% | 2,260 / 1,835 |
| 2,578 (unstable) | 4.54 / 1,635 | 116 / 1,954 | +1585% | 144 / 1,994 | 91% / 15% | 2,335 / 519 |
| 3,093 (unstable) | 8.62 / - | 101 / - | - | 128 / - | 93% / 0% | 2,867 / 108 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 2,291 req/s, predicted 2,064 req/s on the same steps (-10%); ArchSim's own search before measuring put it at 2,062 req/s. Data: `benchmarks/results/pg-mixed/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: pg-mixed -->

## pg-hot-row

<!-- BEGIN GENERATED: pg-hot-row -->
**Node.js + PostgreSQL: every request updates the same row.** Calibrated 2026-10-26T11:33:38Z, predicted 2026-10-26T11:34:16Z (engine 0.7.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![pg-hot-row: predicted and measured p95 and throughput](validation/pg-hot-row.svg)

Model inputs from the calibration passes at 50 and 88 req/s (timings from the 88 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.3443 ms, p95 0.8089 ms | mean = Node's CPU per extra request between the passes: (2205 - 1756 ms) / (2641 - 1501 requests) = 0.3940 ms (at 50 req/s alone: 1.1696 ms); shape (p95/median 0.0357/0.0152 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0.1191 ms | anchored: (measured median 2.808 ms - the model's median without it 2.570 ms at 88 req/s) / 2, one way, at least 0 |
| `writeServiceTimeMs` | lognormal, median 0.7592 ms, p95 1.5531 ms | UPDATE with synchronous_commit off (no WAL flush wait): median 0.759, p95 1.553 ms |
| `commitFlushMs` | lognormal, median 1.3784 ms, p95 2.0806 ms | UPDATE with synchronous_commit on minus off: median 2.138 - 0.759 ms, p95 3.634 - 1.553 ms |
| `dbCpuShare` | 0.2644 | PostgreSQL CPU per extra query between the passes (0.6344 ms) / mean query time (2.399 ms) |

Measured in the calibration pass at 88 req/s: p50 2.81 ms, p95 4.63 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |
| --- | --- | --- | --- | --- | --- | --- |
| 111 | 2.45 / 2.78 | 3.73 / 3.82 | +3% | 4.95 / 4.47 | 100% / 100% | 111 / 111 |
| 222 | 1.64 / 2.78 | 2.51 / 3.90 | +55% | 7.06 / 4.47 | 100% / 100% | 222 / 222 |
| 333 (unstable) | 2.02 / 2.84 | 1,639 / 4.05 | -100% | 1,951 / 4.75 | 71% / 100% | 238 / 333 |
| 400 | 1.26 / 3.13 | 1.97 / 5.04 | +156% | 3.18 / 6.27 | 100% / 100% | 400 / 400 |
| 444 | 1.32 / 15.6 | 2.03 / 39.5 | +1844% | 4.84 / 45.4 | 100% / 100% | 444 / 443 |
| 488 | 1.24 / 1,239 | 1.90 / 1,916 | +100808% | 3.51 / 1,994 | 100% / 76% | 488 / 382 |
| 555 | 1.27 / 1,635 | 1.96 / 1,954 | +99435% | 5.52 / 1,994 | 100% / 15% | 555 / 111 |
| 666 | 1.20 / - | 1.87 / - | - | 7.99 / - | 100% / 0% | 666 / 22 |
| 866 | 1.25 / - | 7.87 / - | - | 34.7 / - | 100% / 0% | 866 / 0 |
| 1,126 (unstable) | - / - | - / - | - | - / - | 0% / 0% | 0 / 0 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).
At 1,126 req/s, 2 of 3 repeats had no successful request (every request failed or was dropped). Such a repeat counts as failed: it has no latency, and in the step's median it counts as slower than any measured repeat, so this step has no measured latency.

Knee: measured 225 req/s, predicted 444 req/s on the same steps (+97%); ArchSim's own search before measuring put it at 444 req/s. Data: `benchmarks/results/pg-hot-row/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: pg-hot-row -->

## cache-aside

<!-- BEGIN GENERATED: cache-aside -->
**Cache-aside: Node.js, Redis (30 s TTL) and PostgreSQL; Zipf 0.99 over 100,000 keys.** Calibrated 2026-10-26T11:53:13Z, predicted 2026-10-26T11:57:39Z (engine 0.7.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![cache-aside: predicted and measured p95 and throughput](validation/cache-aside.svg)

Model inputs from the calibration passes at 50 and 216 req/s (timings from the 216 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.2607 ms, p95 0.5474 ms | mean = Node's CPU per extra request between the passes: (3175 - 1738 ms) / (6481 - 1501 requests) = 0.2886 ms (at 50 req/s alone: 1.1579 ms); shape (p95/median 0.0285/0.0136 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0.0771 ms | anchored: (measured median 0.823 ms - the model's median without it 0.669 ms at 216 req/s) / 2, one way, at least 0 |
| `readServiceTimeMs` | lognormal, median 0.4616 ms, p95 0.7043 ms | SELECT by key as the service sees it (round trip): median 0.462, p95 0.704 ms |
| `dbCpuShare` | 0.3126 | PostgreSQL CPU per extra query between the passes (0.1480 ms) / mean query time (0.474 ms) |
| `cacheServiceTimeMs` | exponential, mean 0.00475 ms | Redis INFO commandstats: 44505 us over 9362 GET/SET calls |
| `cacheLinkLatencyMs` | 0.0916 ms | (GET round trip median 0.188 ms - Redis's own time 0.0048 ms) / 2, one way |

Measured in the calibration pass at 216 req/s: p50 0.82 ms, p95 1.72 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred | Hit ratio meas / pred |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 862 | 0.38 / 0.71 | 1.00 / 1.31 | +32% | 1.54 / 1.51 | 100% / 100% | 862 / 862 | 65% / 65% |
| 1,725 | 0.40 / 0.68 | 1.01 / 1.29 | +27% | 1.66 / 1.51 | 100% / 100% | 1,725 / 1,725 | 71% / 71% |
| 2,588 | 0.44 / 0.71 | 1.21 / 1.36 | +13% | 2.98 / 1.63 | 100% / 100% | 2,588 / 2,588 | 74% / 75% |
| 3,105 | 0.48 / 0.88 | 2.04 / 1.76 | -13% | 7.90 / 2.33 | 100% / 100% | 3,105 / 3,105 | 76% / 76% |
| 3,450 | 0.42 / 4.84 | 1.24 / 13.3 | +968% | 2.91 / 16.5 | 100% / 100% | 3,450 / 3,451 | 77% / 77% |
| 3,795 | 0.43 / 1,239 | 1.88 / 1,916 | +101649% | 10.9 / 1,994 | 100% / 79% | 3,795 / 3,100 | 78% / 77% |
| 4,312 | 0.69 / 1,635 | 20.2 / 1,954 | +9591% | 68.4 / 1,994 | 98% / 16% | 4,211 / 902 | 79% / 77% |
| 5,175 | 0.69 / - | 7.65 / - | - | 49.6 / - | 99% / 0% | 5,133 / 177 | 80% / 77% |
| 6,728 | 0.80 / - | 32.5 / - | - | 95.0 / - | 95% / 0% | 6,414 / 0 | 82% / 77% |

Knee: measured 4,016 req/s, predicted 3,457 req/s on the same steps (-14%); ArchSim's own search before measuring put it at 3,450 req/s. Data: `benchmarks/results/cache-aside/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: cache-aside -->

## kafka

<!-- BEGIN GENERATED: kafka -->
**Kafka: Node.js producer, 6 partitions, 3 consumers inserting into PostgreSQL.** Calibrated 2026-10-26T12:22:50Z, predicted 2026-10-26T12:24:22Z (engine 0.7.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![kafka: predicted and measured p95 and throughput](validation/kafka.svg)

Model inputs from the calibration passes at 50 and 143 req/s (timings from the 143 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.8805 ms, p95 1.9421 ms | mean = Node's CPU per extra request between the passes: (5381 - 2623 ms) / (4291 - 1501 requests) = 0.9884 ms (at 50 req/s alone: 1.7476 ms); shape (p95/median 0.0568/0.0257 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0 ms | anchored: (measured median 1.426 ms - the model's median without it 1.948 ms at 143 req/s) / 2, one way, at least 0 |
| `publishTimeMs` | lognormal, median 0.9014 ms, p95 10.1154 ms | producer.send with acks=all on one broker: median 0.901, p95 10.115 ms |
| `consumerTimeMs` | lognormal, median 0.0163 ms, p95 0.0293 ms | consumer's own time per message (handling minus the INSERT): median 0.016, p95 0.029 ms |
| `commitFlushMs` | lognormal, median 0.9563 ms, p95 1.3354 ms | UPDATE with synchronous_commit on minus off: median 1.511 - 0.555 ms, p95 2.222 - 0.886 ms |
| `writeServiceTimeMs` | lognormal, median 0.7051 ms, p95 12.7449 ms | consumer's INSERT time (median 1.661, p95 13.701 ms) minus the flush median |
| `dbCpuShare` | 0.168 | PostgreSQL CPU per extra query between the passes (0.6617 ms) / mean query time (3.939 ms) |

Measured in the calibration pass at 143 req/s: p50 1.43 ms, p95 14.1 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred | Consumed/s meas / pred | Message age p95 meas / pred (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 252 | 0.86 / 1.99 | 2.87 / 11.6 | +303% | 7.43 / 29.9 | 100% / 100% | 252 / 252 | 252 / 252 | 6.00 / 48.2 |
| 503 | 0.77 / 2.03 | 2.63 / 11.3 | +332% | 5.93 / 31.2 | 100% / 100% | 503 / 503 | 503 / 503 | 10.0 / 221 |
| 754 | 1.50 / 2.28 | 28.7 / 11.6 | -60% | 96.2 / 29.4 | 100% / 100% | 751 / 754 | 738 / 664 | 432 / 3,206 |
| 905 | 0.88 / 3.01 | 5.71 / 12.5 | +120% | 14.1 / 29.9 | 100% / 100% | 902 / 905 | 903 / 664 | 41.0 / 6,541 |
| 1,006 | 3.11 / 26.1 | 90.5 / 47.2 | -48% | 171 / 57.6 | 98% / 100% | 985 / 1,006 | 987 / 665 | 787 / 8,133 |
| 1,107 (unstable) | 1.10 / 1,215 | 20.2 / 1,954 | +9595% | 51.0 / 1,994 | 100% / 77% | 1,105 / 874 | 1,100 / 664 | 513 / 8,295 |
| 1,258 (unstable) | 1.75 / 1,635 | 51.8 / 1,954 | +3670% | 115 / 1,994 | 99% / 15% | 1,247 / 252 | 1,249 / 664 | 2,369 / 8,461 |
| 1,509 | 105 / - | 317 / - | - | 468 / - | 68% / 0% | 1,025 / 50 | 886 / 664 | 11,837 / 8,461 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 945 req/s, predicted 1,006 req/s on the same steps (+6%); ArchSim's own search before measuring put it at 1,006 req/s. Data: `benchmarks/results/kafka/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: kafka -->

## Where the model is wrong, and why

From run 4 (protocol v4, 2026-10-26, one Windows laptop with Docker Desktop):

- **Capacity (knee error): pg-mixed -10%, cache-aside -14%, Kafka +6% (the client side: publish
  latency), pg-hot-row +97%** (not reliably measurable here, below). One run each: pg-mixed's
  error is inside its own run-to-run spread and in-sample (see the headline). The pg-mixed and
  cache-aside misses are on the safe side: ArchSim predicted less capacity than the system had.
- **Low-load latency is high, by different amounts per experiment.** At the ladder's low steps
  (a quarter and half of the predicted knee; table after this list) ArchSim's p95 is above the
  measured one at every step: by 0.1 to 1.4 ms (+3% to +75%) for pg-mixed, pg-hot-row and
  cache-aside, and by about 8.7 ms (+303% and +332%) for Kafka. For the PostgreSQL and Redis
  experiments the relative errors look large because the latencies are small; the likely cause
  is the calibration: CPU per request is measured at 50 req/s and a few hundred req/s, where the
  laptop's cores are clocked down, and the model keeps that cost at every rate (run 1 showed the
  same effect at four times the size). Kafka's error is of another size: its calibration pass at
  143 req/s measured p95 14.1 ms (producer send p95 10.1 ms, the consumer's INSERT p95 13.7 ms),
  a tail the measured steps at 252 and 503 req/s did not show (p95 2.9 and 2.6 ms); the model
  carries the calibration pass's tail to every rate.
- **The model's cliff is sharper than the real one.** Past its knee ArchSim's p95 jumps to the 2 s
  client timeout within one or two steps; the real systems degrade over several steps (pg-mixed
  10 ms at 2,268 req/s where ArchSim predicts 1.9 s). A single-threaded event loop at a fixed CPU
  cost per request saturates abruptly in the model; in the real service the cost per request
  falls as load rises. So "how far past the knee can I go" is answered pessimistically.
- **Commits are cheaper under concurrency than alone.** Kafka's consumers (each message an
  INSERT and a commit) kept up with 1,249 messages/s; ArchSim capped them at 664/s from the
  commit time measured at low load, so its message-age predictions are far too high (8 s against
  0.5 s at 1,107 req/s). PostgreSQL's group commit shares WAL flushes between concurrent commits;
  the model charges every commit its own flush. The same likely explains why hot-row held 866
  req/s with p95 under 8 ms where ArchSim predicted the lock would saturate near 450 req/s.
- **Hot-row could not be measured reliably here.** At 333 req/s two of three repeats stalled for
  1.6 s (success 71%) while 400 to 666 req/s were clean (p95 about 2 ms) and 866 req/s nearly so
  (7.9 ms). At 1,126 req/s the first repeat had p95 332 ms and in the other two no request
  succeeded, so that step has no measured latency (run 4's measurements recorded 0 ms for it;
  the comparison now counts those repeats as failed). Its runs 1 to 3 put the knee at 303, 819 and
  132 req/s. A single row's lock turns any short host stall into a queue that takes seconds to
  drain, and this laptop has such stalls even when idle. The protocol's knee (the first failing
  step) is 225 req/s; read the table instead of that number, and rerun on a quieter Linux host
  before quoting it.

p95 at the ladder's low steps (at most half the predicted knee):

<!-- BEGIN GENERATED: low-load -->
| Experiment | Offered req/s | p95 measured (ms) | p95 predicted (ms) | Predicted - measured |
| --- | --- | --- | --- | --- |
| pg-mixed | 516 (0.25x the predicted knee) | 1.57 | 2.47 | +0.90 ms (+58%) |
| pg-mixed | 1,031 (0.5x the predicted knee) | 1.44 | 2.52 | +1.08 ms (+75%) |
| pg-hot-row | 111 (0.25x the predicted knee) | 3.73 | 3.82 | +0.09 ms (+3%) |
| pg-hot-row | 222 (0.5x the predicted knee) | 2.51 | 3.90 | +1.38 ms (+55%) |
| cache-aside | 862 (0.25x the predicted knee) | 1.00 | 1.31 | +0.31 ms (+32%) |
| cache-aside | 1,725 (0.5x the predicted knee) | 1.01 | 1.29 | +0.28 ms (+27%) |
| kafka | 252 (0.25x the predicted knee) | 2.87 | 11.6 | +8.70 ms (+303%) |
| kafka | 503 (0.5x the predicted knee) | 2.63 | 11.3 | +8.72 ms (+332%) |
<!-- END GENERATED: low-load -->

What would make the model better, in order: let CPU per request fall with load (calibrate at two
higher rates, or fit a curve), model group commit for databases with WAL flushes, and make the
saturation regime gradual (the event loop's latency growth past utilization 1). None of these
is implemented; they are the next model changes this data supports.

Limits of this evidence: one machine, one run per protocol, Windows with Docker Desktop (a
virtual machine between the containers and the hardware), small synthetic services; pg-mixed
and pg-hot-row are in-sample for the final protocol, and the measured knee varies between runs by
more than the errors above; k6's own summaries are not kept, only the numbers taken from them. It
suggests the model's capacity estimates are in the right range for pg-mixed, cache-aside and
Kafka on this machine; it does not show they hold across runs, for other technologies, larger
machines or real applications.

## Proposed tier thresholds

The owner decides tiers (docs/prompts/how-to-win.md); this is a proposal, not applied anywhere.

A technology is **validated** when every benchmark that exercises it has, in its latest run on a
clean protocol:

1. a knee error within ±25%;
2. at or below the measured knee, a p95 error within ±100% or 3 ms absolute, whichever is
   larger (so sub-millisecond noise does not fail it);
3. no unstable step at or below the measured knee, reproduced in a second run (on another
   machine, or the hosted runner).

Computed by `benchmarks/report.py` from each experiment's latest run and its kept runs (3 is
split: 3a stability, 3b a second run under the same protocol on another machine):

<!-- BEGIN GENERATED: tiers -->
| Experiment | 1. Knee error within ±25% | 2. p95 within ±100% or 3 ms at or below the measured knee | 3a. No unstable step at or below the measured knee | 3b. Reproduced on a second machine, same protocol | All three |
| --- | --- | --- | --- | --- | --- |
| pg-mixed | yes (-10%) | no: 2,062 and 2,268 req/s miss (4 of 6 steps within) | no: 2,268 req/s unstable | no: 1 run under v4, on 1 machine | no |
| pg-hot-row | no (+97%) | yes (2 of 2 steps) | yes | no: 1 run under v4, on 1 machine | no |
| cache-aside | yes (-14%) | no: 3,450 and 3,795 req/s miss (4 of 6 steps within) | yes | no: 1 run under v4, on 1 machine | no |
| kafka | yes (+6%) | no: 252, 503 and 905 req/s miss (1 of 4 steps within) | yes | no: 1 run under v4, on 1 machine | no |

A technology meets the thresholds when every experiment that exercises it meets all three: Node.js (pg-mixed, pg-hot-row, cache-aside, kafka); PostgreSQL (pg-mixed, pg-hot-row, cache-aside, kafka); Redis (cache-aside); Kafka (kafka). None meets them on this data.
<!-- END GENERATED: tiers -->

The criteria check the client's p95 only. Kafka's consumer side (consumed per second, message
age) is further off (above: consumers capped at 664 messages/s where they kept up with 1,249/s),
so Kafka would stay experimental for asynchronous questions even if it met all three, until
group commit is modeled. On this data the core set stays a focus list, as the badge says today.
