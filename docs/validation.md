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
| [pg-mixed](#pg-mixed) | 2,430 req/s | 2,238 req/s | -8% | 81% / 216% (5 steps) |
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
  (v4): pg-mixed's is run 5 (2026-10-28, UTC), the others run 4. The measured knee itself moves from
  run to run on the same machine: pg-mixed's was 1,895, 3,162, 2,291 and 2,430 req/s in runs 2
  to 5 (see [Protocol history](#protocol-history)), a spread far wider than the -8% error of run
  5; pg-hot-row's ranged from 132 to 819 req/s over runs 1 to 4.
- **pg-mixed and pg-hot-row are in-sample.** The protocol was revised (v2, then v3, then v4)
  after the high-load results of those two experiments had been seen. Each revision fixed a flaw
  in the calibration, but it was chosen with their data in view. cache-aside and Kafka were first
  run under the final protocol, so only their numbers are out-of-sample.
- **Latency at low load is high, and far off for Kafka**: p95 +0.1 to +1.4 ms for pg-mixed,
  pg-hot-row and cache-aside (all but pg-mixed's lowest step), about +8.7 ms (+303% and +332%)
  for Kafka
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
uv run python benchmarks/report.py --json  # rewrite this page's tables, charts and accuracy.json
```

`make benchmark` runs all of them. `uv run python benchmarks/run.py compare` recomputes every
`comparison.json` from the committed measurements and predictions without Docker;
`run.py resources` first predicts CPU and memory for results whose predictions have none
(see [Resources](#resources-cpu-and-memory)). Results depend
on the machine: rerun on yours before quoting numbers. `run.py --archive` first moves each
experiment's latest results into its next `run-N/` folder, so the history below keeps them.

`report.py --json` also writes the same numbers as data, to `docs/validation/accuracy.json` and a
copy in `apps/web/src/data/` (the web image is built from `apps/web` alone; a test keeps the two
equal). The web app's public `/accuracy` page is built from that copy.

A GitHub Actions workflow (`.github/workflows/benchmark.yml`) runs everything on a hosted runner
every week, on every release tag (`v*`) and by hand: it archives the previous results, measures,
runs `report.py --json`, uploads the raw results as an artifact and opens a pull request for a
person to review and merge. The repository is private, so each automatic run uses about 1.5 hours
of billed Actions minutes: the weekly and release-tag triggers sit in a marked block at the top of
the workflow, to comment out to turn them off. **It has not run yet, and the first run should be a
manual dispatch to check it:** every run so far was on Windows with Docker Desktop; on Linux,
neither the workflow nor the local runs have been tried (the k6 container writes its summary as
uid 12345, so the runner makes `benchmarks/results/.k6` world-writable for it, untested on Linux).

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
   Each repeat also reads every container's CPU and memory (cgroup v2: CPU time and working set)
   every 5 s inside the measured window; this too has not run against the stacks yet (run 4
   recorded CPU over the whole k6 command, see [Resources](#resources-cpu-and-memory)).
5. **Compare** step by step (`comparison.json`), the knee, and each part's CPU and memory against
   the model's.

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
| 4 | v3 plus settling and disturbed-pass retries (v4), machine otherwise idle | This page's numbers for pg-hot-row, cache-aside and Kafka. |
| 5 | v4; CPU and memory read inside the measured window; engine 0.13.0 | pg-mixed: this page's numbers, knee 2,430 req/s against 2,238 predicted (-8%). The run of the other three was discarded: other work on the machine (video rendering) took its memory, so pg-hot-row's calibration measured a commit-flush p95 of 417 ms and Docker stalled for 20 minutes during cache-aside; Kafka did not run. They are to be run again on an idle machine. |

Every kept run, compared the same way (`run.py compare`):

<!-- BEGIN GENERATED: history -->
| Experiment | Results | Protocol | Calibrated (UTC) | Knee measured | Knee predicted | Knee error |
| --- | --- | --- | --- | --- | --- | --- |
| pg-mixed | `run-1/` | v1 | 2026-10-26T08:00:55Z | > 1,359 req/s | 906 req/s | - |
| pg-mixed | `run-2/` | v2 | 2026-10-26T08:23:18Z | 1,895 req/s | 2,314 req/s | +22% |
| pg-mixed | `run-3/` | v3 | 2026-10-26T09:46:26Z | 3,162 req/s | 808 req/s | -74% |
| pg-mixed | `run-4/` | v4 | 2026-10-26T11:13:41Z | 2,291 req/s | 2,064 req/s | -10% |
| pg-mixed | latest (`results/pg-mixed/`) | v4 | 2026-10-28T23:21:36Z | 2,430 req/s | 2,238 req/s | -8% |
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
**Node.js + PostgreSQL: 90% reads, 10% writes over 100,000 rows.** Calibrated 2026-10-28T23:21:36Z, predicted 2026-10-28T23:23:09Z (engine 0.13.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![pg-mixed: predicted and measured p95 and throughput](validation/pg-mixed.svg)

Model inputs from the calibration passes at 50 and 346 req/s (timings from the 346 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.377 ms, p95 0.9837 ms | mean = Node's CPU per extra request between the passes: (5052 - 1083 ms) / (10381 - 1500 requests) = 0.4469 ms (at 50 req/s alone: 0.7218 ms); shape (p95/median 0.0289/0.0111 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0 ms | anchored: (measured median 0.856 ms - the model's median without it 0.936 ms at 346 req/s) / 2, one way, at least 0 |
| `readServiceTimeMs` | lognormal, median 0.4295 ms, p95 1.2444 ms | SELECT by key as the service sees it (round trip): median 0.430, p95 1.244 ms |
| `writeServiceTimeMs` | lognormal, median 0.3595 ms, p95 0.7843 ms | UPDATE with synchronous_commit off (no WAL flush wait): median 0.359, p95 0.784 ms |
| `commitFlushMs` | lognormal, median 1.2799 ms, p95 2.4407 ms | UPDATE with synchronous_commit on minus off: median 1.639 - 0.359 ms, p95 3.225 - 0.784 ms |
| `dbCpuShare` | 0.4701 | PostgreSQL CPU per extra query between the passes (0.3434 ms) / mean query time (0.730 ms) |

Measured in the calibration pass at 346 req/s: p50 0.86 ms, p95 2.53 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |
| --- | --- | --- | --- | --- | --- | --- |
| 560 | 0.83 / 0.94 | 5.18 / 2.42 | -53% | 30.4 / 3.26 | 100% / 100% | 560 / 560 |
| 1,119 | 0.60 / 0.95 | 1.96 / 2.52 | +29% | 4.51 / 3.46 | 100% / 100% | 1,119 / 1,119 |
| 1,678 | 0.48 / 1.10 | 1.57 / 2.84 | +81% | 2.76 / 3.90 | 100% / 100% | 1,678 / 1,678 |
| 2,014 | 0.48 / 1.51 | 1.72 / 4.22 | +145% | 4.43 / 5.90 | 100% / 100% | 2,014 / 2,014 |
| 2,238 | 0.50 / 27.1 | 15.0 / 47.2 | +216% | 69.2 / 61.1 | 100% / 100% | 2,231 / 2,239 |
| 2,462 | 0.93 / 1,290 | 45.9 / 1,954 | +4162% | 86.1 / 1,994 | 99% / 75% | 2,434 / 1,914 |
| 2,798 (unstable) | 1.04 / 1,635 | 89.7 / 1,954 | +2079% | 144 / 1,994 | 95% / 14% | 2,666 / 530 |
| 3,357 (unstable) | 1.43 / - | 133 / - | - | 173 / - | 89% / 0% | 2,980 / 108 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 2,430 req/s, predicted 2,238 req/s on the same steps (-8%); ArchSim's own search before measuring put it at 2,238 req/s. Data: `benchmarks/results/pg-mixed/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
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

## Resources: CPU and memory

Besides latency and capacity, ArchSim predicts how busy each part's CPU is (busy cores) and, for a
part with memory set, how much memory it holds. Auto-sizing picks instance types from those
figures, so they are checked here against each container's own counters (cgroup v2) at every
load step.

<!-- BEGIN GENERATED: resources -->
| Experiment | Part | Load step (req/s) | Busy cores measured | Busy cores predicted | Error | Median error up to the knee (steps) | Memory measured | Memory predicted | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [pg-mixed](#pg-mixed) | Node.js service | 2,238 | 0.46 | 1.00 | +116% | 116% (5) | 188 MiB | 550 MiB | +192% |
| [pg-mixed](#pg-mixed) | PostgreSQL | 2,238 | 0.40 | 0.54 | +36% | 36% (5) | 95 MiB | - | - |
| [pg-hot-row](#pg-hot-row) | Node.js service | 222 | 0.08 | 0.09 | +8% | 27% (2) | - | - | - |
| [pg-hot-row](#pg-hot-row) | PostgreSQL | 222 | 0.09 | 0.05 | -43% | 58% (2) | - | - | - |
| [cache-aside](#cache-aside) | Node.js service | 3,795 | 0.69 | 1.00 | +44% | 37% (6) | - | - | - |
| [cache-aside](#cache-aside) | PostgreSQL | 3,795 | 0.20 | 0.12 | -41% | 39% (6) | - | - | - |
| [cache-aside](#cache-aside) | Redis | 3,795 | 0.16 | 0.02 | -87% | 88% (6) | - | - | - |
| [kafka](#kafka) | Node.js producer | 905 | 0.39 | 0.90 | +128% | 75% (4) | - | - | - |
| [kafka](#kafka) | Node.js consumer | 905 | 0.32 | not modelled | - | - | - | - | - |
| [kafka](#kafka) | PostgreSQL | 905 | 0.28 | 0.36 | +26% | 36% (4) | - | - | - |
| [kafka](#kafka) | Kafka broker | 905 | 1.12 | not modelled | - | - | - | - | - |

Each part at the highest load step at or below the measured knee. Busy cores are CPU seconds per second; errors are (predicted - measured) / measured.

Measured: each container's CPU over the whole k6 command, scaled to the measured window by the service's own CPU counter (these runs predate reading CPU inside the window; memory was not measured); each container's CPU and working set (memory less reclaimable page cache), read every 5 s inside the window k6 measures. Predicted: ArchSim's predictions, saved before the measurements; simulated again with engine 0.12.0 on 2026-10-28 from each run's calibration alone, because the original predictions did not record CPU or memory (the latency predictions are the original ones).

Not modelled: Node.js consumer (inside the model's queue node, which has no CPU or memory); Kafka broker (the model's queue node has no CPU or memory).
<!-- END GENERATED: resources -->

What the numbers say. pg-mixed's are from run 5, which read CPU and memory inside the measured
window; the other three are run 4's CPU, scaled as described below. Memory is measured for
pg-mixed only so far.

- **Node.js services: ArchSim's CPU is right at light load and too high under load.** In
  pg-mixed the service used 0.27 cores at 560 req/s (ArchSim: 0.25) but only 0.46 at 2,238 req/s,
  where ArchSim had its one core fully busy: its CPU per request fell from 0.48 to 0.21 ms as the
  load rose. The model charges every request the CPU per extra request measured between the two
  calibration passes (50 req/s and a few hundred); the real event loop gets cheaper per request
  as load rises. The median error up to the knee is 116% in pg-mixed and 37% to 75% in the other
  three (27% over hot-row's two light steps). This is the same effect that makes the model's
  latency cliff sharper than the real one (next section), and it means ArchSim sizes such a
  service larger than it needs.
- **PostgreSQL: 36% to 58% off (median).** In pg-mixed ArchSim's figure was 30% low at
  560 req/s and 36% high near the knee: the real CPU grows more slowly with load than the
  straight line the calibration draws. Run 4's scaled figures had put pg-mixed within 8%; read inside the
  window, it is not, so treat the other three's scaled figures as rough. Run 4 has cache-aside
  (-41% at the top step) and hot-row (-43%) low and Kafka's inserts (+26%) high.
- **Redis: about 88% too low.** The model's Redis time per command is what Redis reports as
  command time (`INFO commandstats`), which leaves out reading and writing the network; the
  real Redis used about eight times that CPU. Latency is not affected (the link time carries it),
  but a Redis node sized from this CPU would be too small.
- **Not modelled:** the Kafka broker's CPU (about 1.1 cores at 905 req/s, over half a core even
  at 50 req/s) and the consumers' (queue nodes have no CPU or memory).

How the CPU was measured: run 5 reads each container's CPU and memory every 5 s inside the
window k6 measures (`ResourceSampler`). Run 4 recorded each container's CPU over the whole k6
command, which includes k6's own container start and stop (the command took 4% to 67% longer
than its 25 s of load); its figures above are scaled to the window by the service's own CPU
counter (Node's
`process.cpuUsage`, which matched its container's CPU within 1% in every calibration pass),
assuming the containers idle while k6 starts and stops. That holds for the services and
PostgreSQL (about 0.02 cores of background work) but not for the Kafka broker, whose figure
reads high. The predicted CPU was simulated again from each run's calibration (`run.py
resources`, `resources-predicted.json`), since the original predictions did not record it; no
measured step feeds into it.

- **Memory (pg-mixed): ArchSim's service memory is 1.8x to 6x too high.** The Node.js service's
  working set grew with the load, from 43 MiB at 560 req/s to 188 MiB at 2,238 req/s (V8's heap
  grows with the rate it allocates at between garbage collections). ArchSim, from its defaults of
  256 MiB at idle plus 2 MiB per request held, predicted 262 to 550 MiB: far too much at idle, and
  growing with requests held rather than with throughput. PostgreSQL's working set stayed at about
  95 MiB at every step; the model has no database memory figure.

Memory is each container's working set: its memory less the page cache the kernel reclaims
first, as Kubernetes counts it (anonymous memory is recorded too). The model's service memory
comes from ArchSim's defaults and Redis's from 1 KiB per key; neither is calibrated yet. Past the
predicted knee the model's service holds every queued request, so its memory there grows far
beyond the real one (11 to 56 GiB in pg-mixed); errors count only steps up to the measured knee.

## Where the model is wrong, and why

From run 4 (protocol v4, 2026-10-26) and, for pg-mixed, run 5 (2026-10-28), on one Windows laptop
with Docker Desktop:

- **Capacity (knee error): pg-mixed -8% (-10% in run 4), cache-aside -14%, Kafka +6% (the client side: publish
  latency), pg-hot-row +97%** (not reliably measurable here, below). One run each: pg-mixed's
  error is inside its own run-to-run spread and in-sample (see the headline). The pg-mixed and
  cache-aside misses are on the safe side: ArchSim predicted less capacity than the system had.
- **Low-load latency is high, by different amounts per experiment.** At the ladder's low steps
  (a quarter and half of the predicted knee; table after this list) ArchSim's p95 is above the
  measured one at every step but pg-mixed's lowest: by 0.1 to 1.4 ms (+3% to +55%) for pg-mixed,
  pg-hot-row and cache-aside, and by about 8.7 ms (+303% and +332%) for Kafka. At pg-mixed's
  560 req/s the step's median repeat measured 5.2 ms (the first repeat stalled at 32 ms, the
  third measured 2.4 ms) against 2.4 ms predicted. For the PostgreSQL and Redis
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
| pg-mixed | 560 (0.25x the predicted knee) | 5.18 | 2.42 | -2.76 ms (-53%) |
| pg-mixed | 1,119 (0.5x the predicted knee) | 1.96 | 2.52 | +0.56 ms (+29%) |
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
| pg-mixed | yes (-8%) | no: 2,238 req/s miss (4 of 5 steps within) | yes | no: 2 runs under v4, on 1 machine | no |
| pg-hot-row | no (+97%) | yes (2 of 2 steps) | yes | no: 1 run under v4, on 1 machine | no |
| cache-aside | yes (-14%) | no: 3,450 and 3,795 req/s miss (4 of 6 steps within) | yes | no: 1 run under v4, on 1 machine | no |
| kafka | yes (+6%) | no: 252, 503 and 905 req/s miss (1 of 4 steps within) | yes | no: 1 run under v4, on 1 machine | no |

A technology meets the thresholds when every experiment that exercises it meets all three: Node.js (pg-mixed, pg-hot-row, cache-aside, kafka); PostgreSQL (pg-mixed, pg-hot-row, cache-aside, kafka); Redis (cache-aside); Kafka (kafka). None meets them on this data.
<!-- END GENERATED: tiers -->

The criteria check the client's p95 only. Kafka's consumer side (consumed per second, message
age) is further off (above: consumers capped at 664 messages/s where they kept up with 1,249/s),
so Kafka would stay experimental for asynchronous questions even if it met all three, until
group commit is modeled. On this data the core set stays a focus list, as the badge says today.
