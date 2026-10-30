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
| [pg-hot-row](#pg-hot-row) | 814 req/s | 519 req/s | -36% | 38% / 98771% (8 steps) |
| [cache-aside](#cache-aside) | 4,848 req/s | 2,352 req/s | -51% | 33% / 66651% (9 steps) |
| [kafka](#kafka) | 2,040 req/s | 1,602 req/s | -21% | 77% / 6176% (7 steps) |
<!-- END GENERATED: summary -->

The knee is the highest load that still meets the objective (p95 at most 50 ms with at least 99%
of requests successful), interpolated between the two measured steps around it; the predicted
knee uses the same steps and the same interpolation. The p95 error covers the steps at or below
the measured knee: error = (predicted - measured) / measured.

Read these numbers as one run on one laptop, not as a measure of ArchSim's accuracy:

- **One run each, inside run-to-run noise.** Every row is a single run under the final protocol
  (v4): pg-mixed's is run 5 (2026-10-28, UTC), the others run 6 (2026-10-30), after a Windows
  update (build 26200 to 26300, with a new WSL kernel) that made the stacks faster. The measured
  knee itself moves from run to run on the same machine: pg-mixed's was 1,895, 3,162, 2,291 and
  2,430 req/s in runs 2 to 5 (see [Protocol history](#protocol-history)), a spread far wider than
  the -8% error of run 5; across the update, Kafka's went from 945 to 2,040 req/s and
  pg-hot-row's from 225 to 814.
- **pg-mixed and pg-hot-row are in-sample.** The protocol was revised (v2, then v3, then v4)
  after the high-load results of those two experiments had been seen. Each revision fixed a flaw
  in the calibration, but it was chosen with their data in view. cache-aside and Kafka were first
  run under the final protocol, so only their numbers are out-of-sample.
- **ArchSim under-predicts capacity in all four**, by 8% to 51%. For pg-mixed, cache-aside and
  Kafka the predicted knee is where the CPU per request measured in calibration fills the
  service's one core; the real service's CPU per request falls as load rises (see
  [Resources](#resources-cpu-and-memory)).
- **Latency at low load is close, except Kafka's**: p95 +0.07 to +0.61 ms (+2% to +33%) for
  pg-hot-row, cache-aside and pg-mixed's 1,119 req/s step; for Kafka 1.7 and 10.6 ms below the
  measured one (-34% and -77%)
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

Two commands check the model and the machine rather than measure accuracy:

- `run.py resimulate` derives every kept run's inputs again from its calibration passes, with
  today's code, and simulates its measured steps with today's engine (no Docker, about 10
  minutes). It writes `results/dev/<engine>/`, and `report.py --dev` turns that into
  [`docs/validation/dev-checks.md`](validation/dev-checks.md): each run's knee, measured and
  simulated by every engine checked. These checks are in-sample, designed after the
  measurements were seen, so they show what a model change does without claiming accuracy; this
  page's tables never use them. With engine 0.14.0 they reproduce every run calibrated under v3
  or v4 exactly, step by step, which shows the re-simulation itself is faithful.
- `run.py envcheck` characterises the machine (about 8 minutes of Docker): which CPUs share a
  physical core inside Docker's VM, how much CPU the VM loses to steal, and the service's and
  PostgreSQL's CPU per request and p95 across 50 to 1,600 req/s, with k6 on the CPUs the runner
  gives it and again with the service's physical core left to the service alone. It writes
  `results/envcheck/<time>.json`. Each run's `environment.json` also records how its cpusets fall
  on physical cores (`topology`).

`report.py --json` also writes the same numbers as data, to `docs/validation/accuracy.json` and a
copy in `apps/web/src/data/` (the web image is built from `apps/web` alone; a test keeps the two
equal). The web app's public `/accuracy` page is built from that copy.

A GitHub Actions workflow (`.github/workflows/benchmark.yml`) runs everything on a hosted runner
every week, on every release tag (`v*`) and by hand: it archives the previous results, measures,
runs `report.py --json`, uploads the raw results as an artifact and opens a pull request for a
person to review and merge. The repository is private, so each automatic run uses about 1.5 hours
of billed Actions minutes: the weekly and release-tag triggers sit in a marked block at the top of
the workflow, to comment out to turn them off. **It has not completed a run yet:** two manual
runs stopped before measuring (the second asked for more CPUs than the hosted runner's 2; the
workflow now shares the runner's own CPUs between the containers). Every measured run so far was
on Windows with Docker Desktop; on Linux, the local runs have not been tried (the k6 container
writes its summary as uid 12345, so the runner makes `benchmarks/results/.k6` world-writable for
it, untested on Linux).

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
   knee. `predictions.json` is written before the first measured step, with its provenance:
   the commit, the engine's and the harness's git object ids, whether either had uncommitted
   changes, and the machine. Its sha256 goes to the log and into `measured.json` before the
   first step is measured. When the system still
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
   failed). Since run 5, the runner also waits (at most 60 s) between repeats until the service
   answers its health check and PostgreSQL has no query left running (except on the Kafka stack,
   which already waits for its backlog to drain), and each repeat reads every container's CPU
   and memory (cgroup v2: CPU time and working set) every 5 s inside the measured window (run 4
   recorded CPU over the whole k6 command, see [Resources](#resources-cpu-and-memory)).
5. **Compare** step by step (`comparison.json`), the knee, and each part's CPU and memory against
   the model's.

Nothing measured at steps 4 and 5 feeds back into the model: the predictions use only the
calibration passes.

### Protocol history

Earlier runs are kept in `benchmarks/results/<experiment>/run-N/` (cache-aside and Kafka were
first run in run 4, which their `run-1/` folders hold). The protocol changed between runs, each time after
a run exposed a flaw in the calibration. The changes were made with the PostgreSQL experiments'
high-load results in view, so those two experiments are in-sample for the final protocol (v4):

| Run | Protocol | What it showed |
| --- | --- | --- |
| 1 | one pass at 50 req/s, CPU per request as measured | At 50 req/s a laptop's cores sit in low-power states, so CPU per request came out about 4x what it costs under load. |
| 2 | two passes, CPU per request from the slope (v2) | Fixed CPU cancels out; but the database driver's CPU was counted twice (in the CPU per request and inside the measured query time), so the model started about 2x too slow. Measured steps overlapped with other work on the machine. |
| 3 | v2 plus the anchor at the calibration rate (v3) | pg-mixed's first pass met the post-load autovacuum (p95 280 ms at 50 req/s), which inflated CPU per request 3x and sent every input wrong; hot-row's single-row lock turned short host stalls (package installs and test runs in other work) into seconds-long queues. Neither run measured the model. |
| 4 | v3 plus settling and disturbed-pass retries (v4), machine otherwise idle | This page's numbers for pg-hot-row, cache-aside and Kafka. |
| 5 | v4; CPU and memory read inside the measured window; engine 0.13.0 | pg-mixed: this page's numbers, knee 2,430 req/s against 2,238 predicted (-8%). The run of the other three was discarded: other work on the machine (video rendering) took its memory, so pg-hot-row's calibration measured a commit-flush p95 of 417 ms and Docker stalled for 20 minutes during cache-aside; Kafka did not run. They were run again in run 6. |
| 6 | v4, as run 5, after a Windows update (build 26300, with a new WSL kernel) | pg-hot-row, cache-aside and Kafka: this page's numbers, on an otherwise idle machine (no discarded pass; commit-flush p95 1.8 ms). The update made the stacks faster: Kafka's publish p95 in calibration fell from 10.1 to 2.6 ms and its knee rose from 945 to 2,040 req/s; hot-row held 777 req/s, where run 4 stalled at 333. |

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
| pg-hot-row | `run-4/` | v4 | 2026-10-26T11:33:38Z | 225 req/s | 444 req/s | +97% |
| pg-hot-row | latest (`results/pg-hot-row/`) | v4 | 2026-10-30T10:32:56Z | 814 req/s | 519 req/s | -36% |
| cache-aside | `run-1/` | v4 | 2026-10-26T11:53:13Z | 4,016 req/s | 3,457 req/s | -14% |
| cache-aside | latest (`results/cache-aside/`) | v4 | 2026-10-30T10:50:27Z | 4,848 req/s | 2,352 req/s | -51% |
| kafka | `run-1/` | v4 | 2026-10-26T12:22:50Z | 945 req/s | 1,006 req/s | +6% |
| kafka | latest (`results/kafka/`) | v4 | 2026-10-30T11:20:01Z | 2,040 req/s | 1,602 req/s | -21% |
<!-- END GENERATED: history -->

The measured knee moved between runs too, not only the prediction. Each step was measured the
same way in runs 2 to 6 (three repeats with a 20 s measured window), but in runs 2 and 3 the
machine was doing other work and the database was not settled first, and run 6 came after a
Windows update. The run-to-run spread quoted here and on `/accuracy` counts only runs on the
latest run's OS build.

## Environment

<!-- BEGIN GENERATED: environment -->
- CPU for pg-mixed: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz; Docker 29.1.3 (linux/amd64) with 8 CPUs and 7.6 GiB; Windows-11-10.0.26200-SP0.
- CPU for pg-hot-row, cache-aside and kafka: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz; Docker 29.1.3 (linux/amd64) with 8 CPUs and 7.6 GiB; Windows-11-10.0.26300-SP0.
- Images: postgres `postgres:16-alpine`, redis `redis:7-alpine`, kafka `apache/kafka:3.8.0`, k6 `grafana/k6:1.3.0`, service `node:24-alpine + pg 8.23.1, redis 4.7.1, kafkajs 2.2.4`.
- CPUs pinned per container (cpuset; k6 gets the CPUs the stack leaves free): pg-mixed and pg-hot-row: service 1 (`2`), PostgreSQL 2 (`4,5`), k6 5 (`0,1,3,6,7`); cache-aside: service 1 (`2`), PostgreSQL 2 (`4,5`), Redis 1 (`1`), k6 4 (`0,3,6,7`); kafka: producer 1 (`2`), consumer 1 (`3`), PostgreSQL 2 (`4,5`), Kafka 2 (`0,1`), k6 2 (`6,7`).
<!-- END GENERATED: environment -->

**What the machine itself does** (`run.py envcheck`, 30 Oct, Windows build 26300, pg-mixed's
workload, `results/envcheck/`):

- **CPU per request falls steeply with the rate, in every container.** The Node.js service used
  0.96 ms per request at 50 req/s, 0.42 at 200, 0.29 at 400 and 0.21 to 0.22 from 800 to 1,600;
  PostgreSQL 0.93, 0.33, 0.21 and 0.19 ms. The cost settles only from about 800 req/s. The
  calibration passes (50 req/s and a quarter of the pessimistic capacity, a few hundred req/s)
  sit where it is highest and still changing, which is why the model's CPU per request is about
  twice the real one near the knee (see [Resources](#resources-cpu-and-memory)). It looks like
  the host (cores idling into power-saving states at light load) rather than the application.
- **k6 on the service's hyperthread sibling** (CPUs 2 and 3 are one physical core in Docker's
  VM) cost the service 10% to 15% more CPU per request and raised p95 at 1,600 req/s from 1.4 to
  2.2 ms. From the next run the runner leaves that sibling idle (k6 on `0,1,6,7` for the
  PostgreSQL stack and `0,6,7` for the cache stack). PostgreSQL's two CPUs (`4,5`) are one
  physical core too, though the model gives it two cores; its CPU never came close to binding.
- **No CPU steal** in Docker's VM, and no p95 near Windows' 15.6 ms timer tick at these rates.

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
**Node.js + PostgreSQL: every request updates the same row.** Calibrated 2026-10-30T10:32:56Z, predicted 2026-10-30T10:33:21Z (engine 0.13.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![pg-hot-row: predicted and measured p95 and throughput](validation/pg-hot-row.svg)

Model inputs from the calibration passes at 50 and 102 req/s (timings from the 102 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.4051 ms, p95 0.6428 ms | mean = Node's CPU per extra request between the passes: (1855 - 1198 ms) / (3061 - 1501 requests) = 0.4214 ms (at 50 req/s alone: 0.7980 ms); shape (p95/median 0.0242/0.0153 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0.0328 ms | anchored: (measured median 2.393 ms - the model's median without it 2.328 ms at 102 req/s) / 2, one way, at least 0 |
| `writeServiceTimeMs` | lognormal, median 0.6039 ms, p95 1.0147 ms | UPDATE with synchronous_commit off (no WAL flush wait): median 0.604, p95 1.015 ms |
| `commitFlushMs` | lognormal, median 1.2625 ms, p95 1.8312 ms | UPDATE with synchronous_commit on minus off: median 1.866 - 0.604 ms, p95 2.846 - 1.015 ms |
| `dbCpuShare` | 0.1637 | PostgreSQL CPU per extra query between the passes (0.3367 ms) / mean query time (2.057 ms) |

Measured in the calibration pass at 102 req/s: p50 2.39 ms, p95 3.68 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |
| --- | --- | --- | --- | --- | --- | --- |
| 130 | 1.96 / 2.37 | 3.00 / 3.07 | +2% | 4.02 / 3.46 | 100% / 100% | 130 / 130 |
| 259 | 1.52 / 2.37 | 2.46 / 3.07 | +25% | 3.79 / 3.46 | 100% / 100% | 259 / 259 |
| 388 | 1.31 / 2.37 | 2.34 / 3.13 | +34% | 3.56 / 3.60 | 100% / 100% | 388 / 388 |
| 466 | 1.29 / 2.52 | 2.55 / 3.53 | +38% | 34.9 / 4.30 | 100% / 100% | 466 / 466 |
| 518 (unstable) | 1.42 / 9.88 | 3.30 / 17.9 | +443% | 47.4 / 20.6 | 100% / 100% | 518 / 518 |
| 570 | 1.20 / 1,239 | 2.04 / 1,916 | +93900% | 8.88 / 1,994 | 100% / 76% | 570 / 448 |
| 648 | 1.16 / 1,603 | 1.98 / 1,954 | +98771% | 4.72 / 1,994 | 100% / 15% | 648 / 130 |
| 777 | 1.17 / - | 3.23 / - | - | 20.0 / - | 100% / 0% | 777 / 26 |
| 1,010 (unstable) | 61.6 / - | 296 / - | - | 344 / - | 99% / 0% | 997 / 0 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 814 req/s, predicted 519 req/s on the same steps (-36%); ArchSim's own search before measuring put it at 518 req/s. Data: `benchmarks/results/pg-hot-row/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: pg-hot-row -->

## cache-aside

<!-- BEGIN GENERATED: cache-aside -->
**Cache-aside: Node.js, Redis (30 s TTL) and PostgreSQL; Zipf 0.99 over 100,000 keys.** Calibrated 2026-10-30T10:50:27Z, predicted 2026-10-30T10:53:11Z (engine 0.13.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![cache-aside: predicted and measured p95 and throughput](validation/cache-aside.svg)

Model inputs from the calibration passes at 50 and 223 req/s (timings from the 223 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.3728 ms, p95 0.8679 ms | mean = Node's CPU per extra request between the passes: (3894 - 1686 ms) / (6691 - 1501 requests) = 0.4254 ms (at 50 req/s alone: 1.1233 ms); shape (p95/median 0.0393/0.0169 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0.0398 ms | anchored: (measured median 0.927 ms - the model's median without it 0.848 ms at 223 req/s) / 2, one way, at least 0 |
| `readServiceTimeMs` | lognormal, median 0.5038 ms, p95 0.828 ms | SELECT by key as the service sees it (round trip): median 0.504, p95 0.828 ms |
| `dbCpuShare` | 0.3616 | PostgreSQL CPU per extra query between the passes (0.1960 ms) / mean query time (0.542 ms) |
| `cacheServiceTimeMs` | exponential, mean 0.00561 ms | Redis INFO commandstats: 54766 us over 9757 GET/SET calls |
| `cacheLinkLatencyMs` | 0.1036 ms | (GET round trip median 0.213 ms - Redis's own time 0.0056 ms) / 2, one way |

Measured in the calibration pass at 223 req/s: p50 0.93 ms, p95 2.09 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred | Hit ratio meas / pred |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 588 | 0.43 / 0.85 | 1.20 / 1.57 | +31% | 2.53 / 1.95 | 100% / 100% | 588 / 588 | 62% / 61% |
| 1,175 | 0.39 / 0.81 | 1.17 / 1.57 | +33% | 2.57 / 1.99 | 100% / 100% | 1,175 / 1,175 | 68% / 68% |
| 1,762 | 0.47 / 0.90 | 1.75 / 1.80 | +3% | 5.73 / 2.37 | 100% / 100% | 1,762 / 1,762 | 71% / 71% |
| 2,115 | 0.51 / 1.16 | 3.40 / 2.84 | -17% | 12.6 / 4.05 | 100% / 100% | 2,115 / 2,115 | 73% / 73% |
| 2,350 | 0.57 / 19.0 | 5.42 / 36.5 | +573% | 23.7 / 47.2 | 100% / 100% | 2,350 / 2,351 | 74% / 74% |
| 2,585 | 0.49 / 1,264 | 2.87 / 1,916 | +66651% | 11.8 / 1,994 | 100% / 76% | 2,585 / 2,014 | 75% / 74% |
| 2,938 | 0.54 / 1,635 | 5.87 / 1,994 | +33859% | 18.8 / 1,994 | 100% / 15% | 2,938 / 576 | 76% / 74% |
| 3,525 | 0.43 / - | 2.34 / - | - | 8.92 / - | 100% / 0% | 3,525 / 115 | 77% / 74% |
| 4,582 | 0.70 / - | 4.99 / - | - | 31.0 / - | 100% / 0% | 4,578 / 0 | 79% / 74% |
| 5,957 (unstable) | 1.70 / - | 43.1 / - | - | 111 / - | 95% / 0% | 5,669 / 0 | 81% / 74% |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 4,848 req/s, predicted 2,352 req/s on the same steps (-51%); ArchSim's own search before measuring put it at 2,350 req/s. Data: `benchmarks/results/cache-aside/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: cache-aside -->

## kafka

<!-- BEGIN GENERATED: kafka -->
**Kafka: Node.js producer, 6 partitions, 3 consumers inserting into PostgreSQL.** Calibrated 2026-10-30T11:20:01Z, predicted 2026-10-30T11:22:57Z (engine 0.13.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![kafka: predicted and measured p95 and throughput](validation/kafka.svg)

Model inputs from the calibration passes at 50 and 164 req/s (timings from the 164 req/s pass, CPU per request from the slope between the two):

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.59 ms, p95 1.0242 ms | mean = Node's CPU per extra request between the passes: (4419 - 2284 ms) / (4921 - 1501 requests) = 0.6241 ms (at 50 req/s alone: 1.5220 ms); shape (p95/median 0.0385/0.0222 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0 ms | anchored: (measured median 1.156 ms - the model's median without it 1.364 ms at 164 req/s) / 2, one way, at least 0 |
| `publishTimeMs` | lognormal, median 0.7237 ms, p95 2.5988 ms | producer.send with acks=all on one broker: median 0.724, p95 2.599 ms |
| `consumerTimeMs` | lognormal, median 0.0152 ms, p95 0.0249 ms | consumer's own time per message (handling minus the INSERT): median 0.015, p95 0.025 ms |
| `commitFlushMs` | lognormal, median 0.9498 ms, p95 1.3095 ms | UPDATE with synchronous_commit on minus off: median 1.472 - 0.522 ms, p95 2.147 - 0.838 ms |
| `writeServiceTimeMs` | lognormal, median 0.4985 ms, p95 1.1912 ms | consumer's INSERT time (median 1.448, p95 2.141 ms) minus the flush median |
| `dbCpuShare` | 0.2297 | PostgreSQL CPU per extra query between the passes (0.3615 ms) / mean query time (1.574 ms) |

Measured in the calibration pass at 164 req/s: p50 1.16 ms, p95 3.30 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred | Consumed/s meas / pred | Message age p95 meas / pred (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 400 | 1.05 / 1.36 | 4.94 / 3.26 | -34% | 13.8 / 5.04 | 100% / 100% | 400 / 400 | 400 / 400 | 28.0 / 2.47 |
| 800 | 1.10 / 1.36 | 13.9 / 3.26 | -77% | 44.9 / 5.14 | 100% / 100% | 800 / 800 | 800 / 800 | 264 / 4.05 |
| 1,200 | 0.79 / 1.42 | 13.5 / 3.32 | -75% | 29.9 / 5.24 | 100% / 100% | 1,200 / 1,200 | 1,200 / 1,200 | 272 / 11.6 |
| 1,440 | 0.84 / 1.63 | 14.1 / 3.67 | -74% | 51.3 / 5.45 | 100% / 100% | 1,440 / 1,440 | 1,440 / 1,440 | 512 / 74.5 |
| 1,600 | 0.84 / 13.6 | 15.3 / 28.2 | +85% | 43.4 / 35.1 | 100% / 100% | 1,598 / 1,600 | 1,598 / 1,466 | 1,466 / 2,116 |
| 1,760 (unstable) | 0.85 / 1,290 | 31.1 / 1,954 | +6176% | 80.1 / 1,994 | 99% / 76% | 1,751 / 1,386 | 1,749 / 1,466 | 3,761 / 2,116 |
| 2,000 | 1.01 / 1,603 | 45.7 / 1,954 | +4175% | 73.7 / 1,994 | 99% / 15% | 1,982 / 399 | 1,782 / 1,466 | 6,907 / 2,116 |
| 2,400 | 0.97 / - | 61.4 / - | - | 108 / - | 98% / 0% | 2,355 / 79 | 1,787 / 1,466 | 11,481 / 2,116 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 2,040 req/s, predicted 1,602 req/s on the same steps (-21%); ArchSim's own search before measuring put it at 1,600 req/s. Data: `benchmarks/results/kafka/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: kafka -->

## Resources: CPU and memory

Besides latency and capacity, ArchSim predicts how busy each part's CPU is (busy cores) and, for a
part with memory set, how much memory it holds. Auto-sizing picks instance types from those
figures, so they are checked here against each container's own counters (cgroup v2) at every
load step.

<!-- BEGIN GENERATED: resources -->
| Experiment | Part | Load step (req/s) | Busy cores measured | Busy cores predicted | Error | Median error up to both knees (steps) | Memory measured | Memory predicted | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [pg-mixed](#pg-mixed) | Node.js service | 2,238 | 0.46 | 1.00 | +116% | 116% (5) | 188 MiB | 550 MiB | +192% |
| [pg-mixed](#pg-mixed) | PostgreSQL | 2,238 | 0.40 | 0.54 | +36% | 36% (5) | 95 MiB | - | - |
| [pg-hot-row](#pg-hot-row) | Node.js service | 518 | 0.17 | 0.22 | +32% | 32% (5) | 37 MiB | 282 MiB | +663% |
| [pg-hot-row](#pg-hot-row) | PostgreSQL | 518 | 0.18 | 0.05 | -70% | 70% (5) | 81 MiB | - | - |
| [cache-aside](#cache-aside) | Node.js service | 2,350 | 0.59 | 1.00 | +69% | 72% (5) | 108 MiB | 498 MiB | +360% |
| [cache-aside](#cache-aside) | PostgreSQL | 2,350 | 0.18 | 0.12 | -35% | 25% (5) | 96 MiB | - | - |
| [cache-aside](#cache-aside) | Redis | 2,350 | 0.14 | 0.02 | -88% | 88% (5) | 11 MiB | 17 MiB | +50% |
| [kafka](#kafka) | Node.js producer | 1,600 | 0.57 | 1.00 | +76% | 61% (5) | 172 MiB | 380 MiB | +120% |
| [kafka](#kafka) | Node.js consumer | 1,600 | 0.27 | not modelled | - | - | 170 MiB | - | - |
| [kafka](#kafka) | PostgreSQL | 1,600 | 0.42 | 0.19 | -54% | 55% (5) | 117 MiB | - | - |
| [kafka](#kafka) | Kafka broker | 1,600 | 0.93 | not modelled | - | - | 1.1 GiB | - | - |

Each part at the highest load step at or below both knees, the measured one and the model's: past the model's own knee its queue holds every request the real service would time out, so its CPU reads fully busy and its memory grows without bound, and the knee error already counts that. Busy cores are CPU seconds per second; errors are (predicted - measured) / measured.

Measured: each container's CPU and working set (memory less reclaimable page cache), read every 5 s inside the window k6 measures. Predicted: ArchSim's predictions, saved before the measurements.

Not modelled: Node.js consumer (inside the model's queue node, which has no CPU or memory); Kafka broker (the model's queue node has no CPU or memory).
<!-- END GENERATED: resources -->

What the numbers say. All four were read inside the measured window, pg-mixed in run 5 and the
others in run 6, against ArchSim's predictions saved before the measurements.

- **Node.js services: ArchSim's CPU is about right at light load and too high under load, and
  this sets three of the four predicted knees.** The model charges every request the CPU per
  extra request measured between the two calibration passes (50 req/s and a few hundred): 0.45 ms
  in pg-mixed, 0.43 ms in cache-aside and 0.62 ms for Kafka's producer. One core divided by that
  is exactly the predicted knee: 2,238, 2,350 and 1,602 req/s. The real event loop gets cheaper
  per request as load rises: from 0.48 to 0.21 ms in pg-mixed, 0.29 to 0.25 ms in cache-aside and
  0.70 to 0.36 ms for the producer, up to that load. At the lightest step ArchSim is within 20%
  in pg-mixed, hot-row and Kafka (cache-aside: 0.25 cores against 0.17); at the top step it has
  the one core fully busy where the real one used 0.46 to 0.59 cores. The median error up to
  both knees is 116% in pg-mixed, 72% in cache-aside, 61% for Kafka and 32% in hot-row, whose
  knee is set by the row lock, not the CPU. The slope also moves between runs: cache-aside's was
  0.29 ms in run 4 (predicted knee 3,457 req/s). This is the same effect that makes the model's
  latency cliff sharper than the real one (next section), and it means ArchSim sizes such a
  service larger than it needs.
- **PostgreSQL: 25% to 70% off (median), mostly low.** At the top step ArchSim's figure is low in
  hot-row (0.05 cores against 0.18), Kafka (0.19 against 0.42) and cache-aside (0.12 against
  0.18), and high in pg-mixed (0.54 against 0.40). In pg-mixed it was 30% low at 560 req/s and
  36% high near the knee: the real CPU grows more slowly with load than the straight line the
  calibration draws.
- **Redis: about 88% too low.** The model's Redis time per command is what Redis reports as
  command time (`INFO commandstats`), which leaves out reading and writing the network; the
  real Redis used about eight times that CPU (0.14 cores against 0.02 at 2,350 req/s). Latency is
  not affected (the link time carries it), but a Redis node sized from this CPU would be too
  small.
- **Memory: ArchSim's service memory is 2x to 9x too high.** The Node.js services' working set
  grew with the load, up to both knees: pg-mixed from 43 to 188 MiB, cache-aside 45 to 108,
  Kafka's producer 47 to 172 and hot-row 28 to 37 (V8's heap grows with the rate it allocates at
  between garbage collections). ArchSim, from its defaults of 256 MiB at idle plus 2 MiB per
  request held, predicted 258 to 550 MiB: far too much at idle, and growing with requests held
  rather than with throughput. Redis held 10 to 11 MiB against ArchSim's 6 to 17 MiB (1 KiB per
  key). PostgreSQL's working set stayed between 67 and 117 MiB and the Kafka broker's between 0.8
  and 1.1 GiB; the model has no figure for either.
- **Not modelled:** the Kafka broker's CPU (0.9 to 1.0 cores at every step, from 400 req/s up)
  and the consumers' (0.27 to 0.33 cores); queue nodes have no CPU or memory.

Memory is each container's working set: its memory less the page cache the kernel reclaims
first, as Kubernetes counts it (anonymous memory is recorded too). The model's service memory
comes from ArchSim's defaults and Redis's from 1 KiB per key; neither is calibrated yet. Past the
model's own knee its service holds every queued request, so its memory there grows far beyond
the real one (2.7 to 176 GiB in these runs); errors count only steps up to both knees.

How it was measured: runs 5 and 6 read each container's CPU and memory every 5 s inside the
window k6 measures (`ResourceSampler`). Run 4, kept in the `run-N/` folders, recorded each
container's CPU over the whole k6 command, which includes k6's own container start and stop (the
command took 4% to 67% longer than its 25 s of load). `run.py compare` scales those figures to
the window by the service's own CPU counter (Node's `process.cpuUsage`, which matched its
container's CPU within 1% in every calibration pass), assuming the containers idle while k6
starts and stops, and `run.py resources` simulates their predicted CPU again from each run's
calibration (`resources-predicted.json`), since run 4's predictions did not record it; no
measured step feeds into it. The scaled figures had put pg-mixed's PostgreSQL within 8%; read
inside the window it was 36% off, so treat run 4's scaled figures as rough.

## Where the model is wrong, and why

From run 6 (protocol v4, 2026-10-30) and, for pg-mixed, run 5 (2026-10-28), on one Windows laptop
with Docker Desktop:

- **Capacity (knee error): pg-mixed -8%, Kafka -21%, pg-hot-row -36%, cache-aside -51%.** All
  four are on the safe side: ArchSim predicted less capacity than the system had. For pg-mixed,
  cache-aside and Kafka the cause is the service's CPU per request (see
  [Resources](#resources-cpu-and-memory)); for hot-row it is the commit (below). In run 4, on the
  earlier OS build, the errors were -10% (pg-mixed), -14% (cache-aside), +6% (Kafka) and +97%
  (hot-row). One run each: pg-mixed's error is inside its own run-to-run spread and in-sample
  (see the headline).
- **Low-load latency is close, except Kafka's.** At the ladder's low steps (a quarter and half of
  the predicted knee; table after this list) ArchSim's p95 is 0.07 to 0.61 ms above the measured
  one (+2% to +33%) for pg-hot-row, cache-aside and pg-mixed's 1,119 req/s: the model's CPU per
  request is the higher one (above). At pg-mixed's 560 req/s the step's median repeat measured
  5.2 ms (the first repeat stalled at 32 ms, the third measured 2.4 ms) against 2.4 ms predicted.
  Kafka's measured p95 rose with load, from 3.3 ms in the calibration pass at 164 req/s to 4.9 ms
  at 400 req/s, 13.9 ms at 800 and 13 to 15 ms up to 1,600, while the model carries the
  calibration pass's 3.3 ms to every rate below its knee. In run 4 it was the other way round:
  that calibration pass caught a 14.1 ms tail the measured steps did not show, and the model was
  8.7 ms high.
- **The model's cliff is sharper than the real one.** Past its knee ArchSim's p95 jumps to the 2 s
  client timeout within one or two steps; the real systems degrade over several steps (pg-mixed
  46 ms at 2,462 req/s and cache-aside 2.9 ms at 2,585 req/s, where ArchSim predicts 1.9 to 2 s).
  A single-threaded event loop at a fixed CPU cost per request saturates abruptly in the model;
  in the real service the cost per request falls as load rises. So "how far past the knee can I
  go" is answered pessimistically.
- **Commits are cheaper under concurrency than alone.** Kafka's consumers (each message an
  INSERT and a commit) kept up with about 1,785 messages/s; ArchSim capped them at 1,466/s from
  the commit time measured at low load (run 4: 664/s against 1,249/s). PostgreSQL's group commit
  shares WAL flushes between concurrent commits; the model charges every commit its own flush.
  The same likely explains hot-row: it held 777 req/s at p95 3.2 ms where ArchSim predicted the
  row lock would saturate at 519 req/s.
- **Kafka's message age is off both ways.** Below the consumers' limit, real messages waited
  longer than the model's (p95 264 ms against 4 ms at 800 req/s); at 1,600 req/s the model's
  queue was the longer (p95 2.1 s against 1.5 s). The client's p95, which the criteria check,
  does not include this wait.
- **Hot-row is sensitive to host stalls.** Run 6 measured it almost cleanly: every step up to
  777 req/s at p95 2 to 3.3 ms, except one repeat at 518 req/s that stalled (p95 59 ms; the step
  is marked unstable); at 1,010 req/s two repeats queued for hundreds of milliseconds (p95 513 and
  296 ms). Runs 1 to 4 put the knee at 303, 819, 132 and 225 req/s: a single row's lock turns any
  short host stall into a queue that takes seconds to drain. Rerun on a quieter Linux host before
  quoting its knee.

p95 at the ladder's low steps (at most half the predicted knee):

<!-- BEGIN GENERATED: low-load -->
| Experiment | Offered req/s | p95 measured (ms) | p95 predicted (ms) | Predicted - measured |
| --- | --- | --- | --- | --- |
| pg-mixed | 560 (0.25x the predicted knee) | 5.18 | 2.42 | -2.76 ms (-53%) |
| pg-mixed | 1,119 (0.5x the predicted knee) | 1.96 | 2.52 | +0.56 ms (+29%) |
| pg-hot-row | 130 (0.25x the predicted knee) | 3.00 | 3.07 | +0.07 ms (+2%) |
| pg-hot-row | 259 (0.5x the predicted knee) | 2.46 | 3.07 | +0.61 ms (+25%) |
| cache-aside | 588 (0.25x the predicted knee) | 1.20 | 1.57 | +0.37 ms (+31%) |
| cache-aside | 1,175 (0.5x the predicted knee) | 1.17 | 1.57 | +0.39 ms (+33%) |
| kafka | 400 (0.25x the predicted knee) | 4.94 | 3.26 | -1.68 ms (-34%) |
| kafka | 800 (0.5x the predicted knee) | 13.9 | 3.26 | -10.61 ms (-77%) |
<!-- END GENERATED: low-load -->

What would make the model better, in order: let CPU per request fall with load (calibrate at two
higher rates, or fit a curve), model group commit for databases with WAL flushes, and make the
saturation regime gradual (the event loop's latency growth past utilization 1). None of these
is implemented; they are the next model changes this data supports.

Limits of this evidence: one machine (run 6 after an OS update that made it faster), one run per
protocol, Windows with Docker Desktop (a virtual machine between the containers and the
hardware), small synthetic services; pg-mixed and pg-hot-row are in-sample for the final
protocol, and the measured knee varies between runs by more than the errors above; k6's own
summaries are not kept, only the numbers taken from them. It suggests the model's capacity
estimates err on the safe side on this machine, by 8% to 51%; it does not show they hold across
runs, for other technologies, larger machines or real applications.

## Held-out experiments

The four experiments above are in-sample: the model and the calibration protocol were revised
with their results in view. Five more were registered on 30 Oct 2026 (`benchmarks/run.py`,
`Experiment.held_out`, with the commit that added them) to test ArchSim on stacks it was never
tuned on. They are **sealed** until an engine frozen in `benchmarks/engines.json` has predicted
them:

- `run.py` refuses to calibrate or measure one unless the engine's source tree
  (`git rev-parse HEAD:packages/engine`) matches a frozen entry and nothing in the engine or the
  harness is uncommitted. Its predictions are then saved before the first measured step, as for
  every experiment, so no model change can be tuned on its results first.
- Until then `run.py smoke <experiment>` is the only way to start their stacks: 20 req/s for
  10 s, checking that every request succeeded and that the services counted it. It keeps no
  latency and no results.
- `report.py` labels each pair of engine and experiment `held-out` or `tuned-on`. An experiment
  is held out for a frozen engine that does not list it in `tunedOn`. The held-out experiments
  join this page's tables once they are measured.

| Experiment | What it tests | Stack |
| --- | --- | --- |
| java-mixed | The `java` profile: a thread pool that waits on its own database calls, and stop-the-world GC | Spring Boot 3.5 (Tomcat's 200 threads, HikariCP), Java 21 with G1 |
| go-mixed | The `go` profile: a goroutine per request, short GC pauses | net/http and a pgx pool, GOMAXPROCS 2 |
| python-mixed | The `python` profile: worker processes that serve one request each | Flask on gunicorn, 10 sync workers with a pool of 1 connection each |
| chain | Two services called one after the other | Node.js gateway (keep-alive) → Node.js API → PostgreSQL |
| cache-ttl | A TTL cache whose hit ratio rises with load, scored at every step | Node.js, Redis with a 5 s TTL, PostgreSQL; Zipf 1.1 over 1,000,000 keys |

Otherwise each uses pg-mixed's setup: 90% reads and 10% writes over 100,000 rows, a pool of 10
connections and PostgreSQL 16 on CPUs 4,5.

- **CPUs.** The three runtimes run on CPUs 2,3, which are the two hyperthreads of one physical
  core on the laptop. The model gives them 2 cores, a gap the results will show. chain's gateway
  and API get one CPU each (0 and 2), as does cache-ttl's Redis (0). No container shares a
  physical core with k6 or with another container. The cost: chain and cache-ttl leave CPUs 1
  and 3 idle and give k6 one core (6,7), so their ladders need watching for dropped iterations.
- **Service statistics.** Every service exposes the same `/stats` as the Node.js one: CPU per
  request, own time, call timings and garbage collection.
  - gunicorn's workers add theirs up in shared memory and take CPU from the container's cgroup.
  - Java's and Go's GC pauses are calibrated from the runtime's own counters.
  - Python's GC stays in the CPU per request: a gunicorn worker's collection stalls only its own
    request, while a pause in the model would stall all ten.
- **Fan-out.** Parallel fan-out is not modelled, so `chain` calls one service after the other.
- **cache-ttl's skew is 1.1, not 0.8.** With Zipf 0.8 over a million keys and a 5 s TTL, the hit
  ratio would be 9% to 24%. With 1.1 it rises from about 52% at 250 req/s to 69% at 4,000 req/s.
  k6 draws keys from a keyspace this large by rejection-inversion, which gives exactly the same
  distribution without a table in every VU.

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
| pg-hot-row | no (-36%) | no: 518, 570, 648 and 777 req/s miss (4 of 8 steps within) | no: 518 req/s unstable | no: 2 runs under v4, on 1 machine | no |
| cache-aside | no (-51%) | no: 2,350, 2,585, 2,938, 3,525 and 4,582 req/s miss (4 of 9 steps within) | yes | no: 2 runs under v4, on 1 machine | no |
| kafka | yes (-21%) | no: 1,760 and 2,000 req/s miss (5 of 7 steps within) | no: 1,760 req/s unstable | no: 2 runs under v4, on 1 machine | no |

A technology meets the thresholds when every experiment that exercises it meets all three: Node.js (pg-mixed, pg-hot-row, cache-aside, kafka); PostgreSQL (pg-mixed, pg-hot-row, cache-aside, kafka); Redis (cache-aside); Kafka (kafka). None meets them on this data.
<!-- END GENERATED: tiers -->

The criteria check the client's p95 only. Kafka's consumer side (consumed per second, message
age) is further off (above: consumers capped at 1,466 messages/s where they kept up with about
1,785/s, and message age off both ways),
so Kafka would stay experimental for asynchronous questions even if it met all three, until
group commit is modeled. On this data the core set stays a focus list, as the badge says today.
