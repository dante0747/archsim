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
| [pg-mixed](#pg-mixed) | 4,006 req/s | 4,956 req/s | +24% | 22% / 79% (3 steps) |
| [pg-hot-row](#pg-hot-row) | 926 req/s | 783 req/s | -16% | 22% / 16999% (6 steps) |
| [cache-aside](#cache-aside) | 7,324 req/s | 4,851 req/s | -34% | 227% / 24562% (8 steps) |
| [kafka](#kafka) | 2,028 req/s | 2,102 req/s | +4% | 76% / 88% (4 steps) |
<!-- END GENERATED: summary -->

Runs that repeat each experiment's latest conditions:

<!-- BEGIN GENERATED: repeatability -->
| Experiment | Runs repeating the latest | Median knee error | Knee errors | Measured knee inside the predicted range |
| --- | --- | --- | --- | --- |
| [pg-mixed](#pg-mixed) | 3 (v5, engine 0.14.0; 2 disturbed, not counted) | +24% | +20% to +25% | 0 of 3 runs (nominal 50%) |
| [pg-hot-row](#pg-hot-row) | 3 (v5, engine 0.14.0) | -16% | -19% to 0% | 1 of 3 runs (nominal 50%) |
| [cache-aside](#cache-aside) | 3 (v5, engine 0.14.0) | -34% | -34% to -26% | 0 of 3 runs (nominal 50%) |
| [kafka](#kafka) | 3 (v5, engine 0.14.0) | +4% | +3% to +33% | 0 of 3 runs (nominal 50%) |

The tier criteria below judge the median over at least 3 such runs. Protocol v5 predicts a range for the knee from its replicate calibration sets; with R sets a new run's knee should fall inside it (R - 1) / (R + 1) of the time (50% for 3), the nominal coverage. A disturbed run missed the objective at one load step and met it at a higher one; it is kept and listed under the run history, but not counted.
<!-- END GENERATED: repeatability -->

The knee is the load where the ladder first misses the objective (p95 at most 50 ms with at least
99% of requests successful), interpolated between that step and the one below it; the predicted
knee uses the same steps and the same interpolation. A run that met the objective again higher
up was disturbed and is not counted (see [Protocol](#protocol), step 5). The p95 error covers
the steps at or below the measured knee: error = (predicted - measured) / measured.

Read these numbers as three runs on one laptop, all of them in-sample:

- **Three counted runs each, under protocol v5 and engine 0.14.0** (the baseline campaign,
  30-31 Oct 2026, Windows 11 build 26300 set to Best performance). The first table shows each
  experiment's latest run, the second the median over its counted runs: **Kafka +4%, pg-hot-row
  -16%, pg-mixed +24%, cache-aside -34%.** The measured knee now moves less between runs than
  the error: pg-mixed's was 3,783 to 4,160 req/s (±5%), hot-row's 819 to 960, cache-aside's
  6,432 to 7,324 and Kafka's 2,028 to 2,132. pg-mixed's first two runs of the campaign were
  disturbed and are not counted, so it ran twice more (the campaign's own three pg-mixed runs,
  disturbed ones included, have a median of +253%).
- **Not all on the safe side any more.** ArchSim over-predicts pg-mixed's capacity, by 20% to
  25% in each counted run: a design sized on it runs out of headroom sooner than predicted.
  Under protocol v4 the latest runs all under-predicted, because calibration read the CPU per
  request too high; v5 reads it at load and gets the service's CPU right (pg-mixed's within 4%,
  see [Resources](#resources-cpu-and-memory)), which shows what the CPU had been hiding: the real
  service's p95 starts climbing well before its core is full, and the model's does not. Garbage
  collection does not explain it (0.1% to 0.4% of the event loop's time, W21 trial); in a quiet
  trial both Node's and PostgreSQL's CPU per request rose by about a third near the knee, once
  the whole machine was busy (W21, W22).
- **pg-mixed and Kafka meet every threshold one machine can test** (criteria 1, 2 and 3a under
  [Proposed tier thresholds](#proposed-tier-thresholds)); the fourth needs a second machine.
  **Kafka's measurements are not evidence, though:** every Kafka run so far had a broker
  healthcheck that started a Java tool every 3 s on the broker's core, about half a core all
  the time. Without it the same stack held the objective up to about 3,000 req/s (W23 trial,
  31 Oct), against 2,028 to 2,132 measured, so the +4% is where the model's knee happened to
  meet the healthcheck's. Fixed in `compose.yaml`; the next campaign measures Kafka again.
  Criterion 2 is lenient one way too: within ±100% of the measured p95 never fails a
  prediction that is too low, and both pass it with p95s predicted 3 to 9 times too low near
  their knee.
- **ArchSim's predicted range is far too narrow.** It held the measured knee in 1 of 12 runs,
  where about half was expected. The range comes from the three replicate calibration sets of
  one run, which agree closely; between runs the prediction itself moved much more (hot-row
  663 to 958 req/s, Kafka 2,102 to 2,829, cache-aside 4,227 to 5,303).
- **The power mode moves the real knee.** Under Best performance (and with k6 moved off the
  service's hyperthread sibling, worth 10% to 15% of the service's CPU) pg-mixed's knee was
  about 4,000 req/s, where run 5 measured 2,430 under an unrecorded mode, and cache-aside's about
  7,000 against 4,848; hot-row's rose less (814 to 819-960) and Kafka's not at all (the
  healthcheck held it). Runs under different power modes are not
  compared with each other, and since this campaign `environment.json` records the mode.
- **All four are in-sample.** The protocol (up to v5) and engine 0.14.0 were developed with
  these four experiments' results in view. The five [held-out
  experiments](#held-out-experiments) are the test of how ArchSim does on stacks it was not
  tuned on.
- **Engine 0.15.0 (31 Oct) moves one knee, in-sample.** Its model changes (W21 to W24, see
  [below](#where-the-model-is-wrong-and-why)), re-simulated on every kept run
  ([`docs/validation/dev-checks.md`](validation/dev-checks.md)), take pg-hot-row's median knee
  error from -16% to -7%; pg-mixed (+24%), cache-aside (-34%) and Kafka (+4%) stay where they
  were. PostgreSQL's predicted CPU is now 4% to 11% low (was 16% to 70%) and Redis's 14% to 19%
  high (was about 90% low). These are re-simulations of runs the changes were tuned on, not
  predictions: every table on this page is still engine 0.14.0's, predicted before measuring.
- **Latency at low load is within 1 ms, except Kafka's**: at a quarter and half of the predicted
  knee ArchSim's p95 is 0.09 to 0.89 ms off for pg-mixed, hot-row and cache-aside; for Kafka 1.3
  and 7.2 ms below the measured one (see [below](#where-the-model-is-wrong-and-why)).
- **Nothing is validated.** No experiment meets all the [proposed tier
  thresholds](#proposed-tier-thresholds): criterion 3b needs a second machine.

## Reproduce

Each stack is one command (Docker and [uv](https://docs.astral.sh/uv/) needed; 20 to 30 minutes
per experiment on the machine below). Keep the machine otherwise idle while it runs: other work
shows up in the measurements (see the protocol history). On a Windows laptop, keep it on mains
power and set the power mode to Best performance: the runner records the mode, and runs under
different modes are not compared with each other:

```bash
uv run python benchmarks/run.py postgres   # pg-mixed and pg-hot-row
uv run python benchmarks/run.py cache      # cache-aside
uv run python benchmarks/run.py kafka      # kafka
uv run python benchmarks/run.py all --runs 3  # three independent runs of each, rotating
uv run python benchmarks/report.py --json  # rewrite this page's tables, charts and accuracy.json
```

`make benchmark` runs all of them. `uv run python benchmarks/run.py compare` recomputes every
`comparison.json` from the committed measurements and predictions without Docker;
`run.py resources` first predicts CPU and memory for results whose predictions have none
(see [Resources](#resources-cpu-and-memory)). Results depend
on the machine: rerun on yours before quoting numbers. `run.py --archive` first moves each
experiment's latest results into its next `run-N/` folder, so the history below keeps them.

Three commands check the model and the machine rather than measure accuracy:

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
- `run.py contention` measures how this machine turns CPUs into capacity (about 12 minutes of
  Docker): pg-mixed's service at rest, from a quarter of its core to past it and at half of it
  with background work on the other CPUs, with k6's own CPU; then a fixed unit of V8 work on one
  CPU, alone and with its hyperthread sibling, other cores or every CPU busy, and at a part of
  its own time. Windows' counters (each logical processor's busy share as the hypervisor sees
  it, the actual frequency, power and thermal limits) are read every second meanwhile. It writes
  `results/envcheck/<time>-contention.json`. With `BENCH_HOST=on` the benchmark designs take
  their host machine from it (`architecture.host`, engine 0.16.0); by default they have none,
  as before (see [What the machine itself does](#environment)). `run.py contention
  --service-only` takes only the machine at rest and the service at three rates (about 4
  minutes).

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
2. **Calibrate** (protocol v5): 20 s at rest, which gives each container's idle CPU, then a
   staircase of the experiment's traffic: 50 req/s, then each pass at half the capacity the one
   before implies, but never more than 35% of the service's core, and no higher once a pass's
   p95 passes 10 ms (at most three rises). The staircase then comes down again and the top rate
   runs once more: every rate twice, the top three times, so drift on the machine shows. The
   service records its CPU per request (Node's process CPU, including garbage collection and
   driver work), its own time per request (handler minus awaited calls) and the time of every
   PostgreSQL, Redis and Kafka call; the runner reads PostgreSQL's and Redis's own CPU and
   command time and, since W24 (after every kept run), each container's working set at rest and
   in every pass (`ResourceSampler`, every 5 s). Since engine 0.15.0 it also reads PostgreSQL's
   own statistics over each pass (`compose.yaml` loads `pg_stat_statements` and sets
   `track_wal_io_timing`): each statement's time inside the server, the WAL flushes with their
   time and the commits that shared one, the time backends were active, and the CPU of the
   backends and of the background processes (`calibration.json`, `postgres` in each pass). From
   those, a query's time in PostgreSQL is its CPU per query, the rest of the round trip the
   service measured (its driver and the network) is the link to the database, and a WAL flush
   takes what PostgreSQL measured; runs without the statistics fit the flush in the model to the
   wait the service measured (`run.py`, `database_inputs`, W22). Each top pass, with the
   auxiliary mixes run right after it, gives one replicate set of model inputs: CPU per request
   is the pass's CPU net of what the process burns at rest, timings come from the pass, and the
   one input not measured directly (the client-to-service latency) is set so the model
   reproduces the pass's median. Redis's command time is its container's CPU per command, net of
   idle (W24; before, `INFO commandstats`' own time, which leaves out its network reads and
   writes), and each service's memory at idle and per req/s is fitted to its working set over the
   staircase (W24; calibrations without the readings keep ArchSim's defaults). The inputs are the
   replicates' medians; `cpuByLoad` keeps every rate's CPU per request, which since W21 (engine
   0.15.0) becomes the service's `serviceTimeByLoad`, relative to the top rate and kept at the
   top rate's cost above it. Since W21 the Node.js service also reports V8's garbage
   collections: its mark-compact cycles in the top pass become the model's GC pauses
   (`gcPauseMs`, `gcEveryRequests`), and their time comes off the CPU per request, which already
   held it; the frequent short scavenges stay in the CPU per request. A pass whose p95 is more than 10 times
   its median was disturbed: it is recorded, discarded and run again. Each input is derived by a
   formula recorded next to it in `calibration.json`. Protocol v4 (runs up to 6) calibrated in
   two passes, 50 req/s and a quarter of the pessimistic capacity, and took CPU per request as
   the slope between them; the machine check above shows why that read the cost too high.
3. **Predict**: the runner builds the ArchSim design (one Node.js event loop on 1 vCPU, a pool of
   10 database connections, PostgreSQL on 2 vCPU, and so on), searches the knee, and simulates
   every step of the ladder: 0.25, 0.5, 0.75, 0.9, 1.0, 1.1, 1.25 and 1.5 times the predicted
   knee. It also searches the knee of each replicate set: the lowest to the highest is the
   predicted range, which a new run's knee should fall inside about half the time with three
   sets ((R - 1) / (R + 1)). `predictions.json` is written before the first measured step, with its provenance:
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
   the model's. A run whose ladder misses the objective at one step and meets it at a higher
   one was **disturbed**: a stable stack does not get faster with more load, and the knee is set
   by the first miss, so that knee is not the stack's. Such a run is kept and listed, but
   `report.py` leaves it out of the criteria, the repeatability and the run-to-run spread. This
   rule was added on 31 Oct 2026, after the baseline campaign's first two pg-mixed runs: at
   2,112 and 2,550 req/s two of three repeats stalled, with every container's CPU per request
   rising 2 to 3 times at once while none was more than half busy, and the same runs then met
   the objective at 3,169 and 5,100 req/s. It flags no other run under v5, and six of the 14
   earlier runs.

Nothing measured at steps 4 and 5 feeds back into the model: the predictions use only the
calibration passes.

### Protocol history

Earlier runs are kept in `benchmarks/results/<experiment>/run-N/` (cache-aside and Kafka were
first run in run 4, which their `run-1/` folders hold). The protocol changed between runs, each
time after a run exposed a flaw in the calibration. Every change was made with earlier results
in view (the PostgreSQL experiments' for v2 to v4, all four's for v5), so all four experiments are
in-sample for the current protocol (v5):

| Run | Protocol | What it showed |
| --- | --- | --- |
| 1 | one pass at 50 req/s, CPU per request as measured | At 50 req/s a laptop's cores sit in low-power states, so CPU per request came out about 4x what it costs under load. |
| 2 | two passes, CPU per request from the slope (v2) | Fixed CPU cancels out; but the database driver's CPU was counted twice (in the CPU per request and inside the measured query time), so the model started about 2x too slow. Measured steps overlapped with other work on the machine. |
| 3 | v2 plus the anchor at the calibration rate (v3) | pg-mixed's first pass met the post-load autovacuum (p95 280 ms at 50 req/s), which inflated CPU per request 3x and sent every input wrong; hot-row's single-row lock turned short host stalls (package installs and test runs in other work) into seconds-long queues. Neither run measured the model. |
| 4 | v3 plus settling and disturbed-pass retries (v4), machine otherwise idle | The first clean runs of pg-hot-row, cache-aside and Kafka. |
| 5 | v4; CPU and memory read inside the measured window; engine 0.13.0 | pg-mixed: knee 2,430 req/s against 2,238 predicted (-8%). The run of the other three was discarded: other work on the machine (video rendering) took its memory, so pg-hot-row's calibration measured a commit-flush p95 of 417 ms and Docker stalled for 20 minutes during cache-aside; Kafka did not run. They were run again in run 6. |
| 6 | v4, as run 5, after a Windows update (build 26300, with a new WSL kernel) | pg-hot-row, cache-aside and Kafka, on an otherwise idle machine (no discarded pass; commit-flush p95 1.8 ms). The update made the stacks faster: Kafka's publish p95 in calibration fell from 10.1 to 2.6 ms and its knee rose from 945 to 2,040 req/s; hot-row held 777 req/s, where run 4 stalled at 333. |
| 7 | v5 (a staircase with replicate sets and a predicted range; CPU per request net of idle at the top rate), engine 0.14.0, Windows on Best performance, k6 off the service's hyperthread sibling; three runs of each experiment, rotating (the baseline campaign, 30-31 Oct) | This page's numbers. The stacks were faster again under Best performance: pg-mixed's knee about 4,000 req/s, cache-aside's about 7,000. pg-mixed's first two runs (22:30 to 01:05, Western European time) were disturbed, cause unknown (Windows logged no throttling, Defender scan or other event around the first stall): two of three repeats stalled at 2,112 req/s in one, and the other missed at 2,550 to 4,590 req/s and met the objective at 5,100. Both are kept and not counted, and pg-mixed ran twice more on the idle machine (`run-9/` and the latest). Calibration retried five disturbed passes, all between 00:29 and 01:08. |

Every kept run, compared the same way (`run.py compare`):

<!-- BEGIN GENERATED: history -->
| Experiment | Results | Protocol | Calibrated (UTC) | Knee measured | Knee predicted | Knee error | Counted |
| --- | --- | --- | --- | --- | --- | --- | --- |
| pg-mixed | `run-1/` | v1 | 2026-10-26T08:00:55Z | > 1,359 req/s | 906 req/s | - | yes |
| pg-mixed | `run-2/` | v2 | 2026-10-26T08:23:18Z | 1,895 req/s | 2,314 req/s | +22% | no: missed at 2,081 req/s, met higher |
| pg-mixed | `run-3/` | v3 | 2026-10-26T09:46:26Z | 3,162 req/s | 808 req/s | -74% | yes |
| pg-mixed | `run-4/` | v4 | 2026-10-26T11:13:41Z | 2,291 req/s | 2,064 req/s | -10% | yes |
| pg-mixed | `run-5/` | v4 | 2026-10-28T23:21:36Z | 2,430 req/s | 2,238 req/s | -8% | yes |
| pg-mixed | `run-6/` | v5 | 2026-10-30T21:30:44Z | 1,089 req/s | 4,230 req/s | +288% | no: missed at 2,112 req/s, met higher |
| pg-mixed | `run-7/` | v5 | 2026-10-30T23:44:23Z | 1,449 req/s | 5,108 req/s | +253% | no: missed at 2,550, 3,825 and 4,590 req/s, met higher |
| pg-mixed | `run-8/` | v5 | 2026-10-31T01:52:33Z | 4,160 req/s | 5,003 req/s | +20% | yes |
| pg-mixed | `run-9/` | v5 | 2026-10-31T04:02:33Z | 3,783 req/s | 4,729 req/s | +25% | yes |
| pg-mixed | latest (`results/pg-mixed/`) | v5 | 2026-10-31T04:34:12Z | 4,006 req/s | 4,956 req/s | +24% | yes |
| pg-hot-row | `run-1/` | v1 | 2026-10-26T08:11:06Z | 303 req/s | 403 req/s | +33% | no: missed at 363 and 443 req/s, met higher |
| pg-hot-row | `run-2/` | v2 | 2026-10-26T08:42:10Z | 819 req/s | 418 req/s | -49% | yes |
| pg-hot-row | `run-3/` | v3 | 2026-10-26T10:11:09Z | 132 req/s | 504 req/s | +281% | no: missed at 252 and 553 req/s, met higher |
| pg-hot-row | `run-4/` | v4 | 2026-10-26T11:33:38Z | 225 req/s | 444 req/s | +97% | no: missed at 333 req/s, met higher |
| pg-hot-row | `run-5/` | v4 | 2026-10-30T10:32:56Z | 814 req/s | 519 req/s | -36% | yes |
| pg-hot-row | `run-6/` | v5 | 2026-10-30T21:58:50Z | 819 req/s | 663 req/s | -19% | yes |
| pg-hot-row | `run-7/` | v5 | 2026-10-31T00:14:43Z | 960 req/s | 958 req/s | 0% | yes |
| pg-hot-row | latest (`results/pg-hot-row/`) | v5 | 2026-10-31T02:21:39Z | 926 req/s | 783 req/s | -16% | yes |
| cache-aside | `run-1/` | v4 | 2026-10-26T11:53:13Z | 4,016 req/s | 3,457 req/s | -14% | no: missed at 4,312 req/s, met higher |
| cache-aside | `run-2/` | v4 | 2026-10-30T10:50:27Z | 4,848 req/s | 2,352 req/s | -51% | yes |
| cache-aside | `run-3/` | v5 | 2026-10-30T22:22:04Z | 7,138 req/s | 5,303 req/s | -26% | yes |
| cache-aside | `run-4/` | v5 | 2026-10-31T00:38:29Z | 6,432 req/s | 4,227 req/s | -34% | yes |
| cache-aside | latest (`results/cache-aside/`) | v5 | 2026-10-31T02:44:32Z | 7,324 req/s | 4,851 req/s | -34% | yes |
| kafka | `run-1/` | v4 | 2026-10-26T12:22:50Z | 945 req/s | 1,006 req/s | +6% | no: missed at 1,006 req/s, met higher |
| kafka | `run-2/` | v4 | 2026-10-30T11:20:01Z | 2,040 req/s | 1,602 req/s | -21% | yes |
| kafka | `run-3/` | v5 | 2026-10-30T23:00:02Z | 2,132 req/s | 2,829 req/s | +33% | yes |
| kafka | `run-4/` | v5 | 2026-10-31T01:19:14Z | 2,087 req/s | 2,140 req/s | +3% | yes |
| kafka | latest (`results/kafka/`) | v5 | 2026-10-31T03:26:27Z | 2,028 req/s | 2,102 req/s | +4% | yes |
<!-- END GENERATED: history -->

The measured knee moved between runs too, not only the prediction. Each step was measured the
same way in runs 2 to 7 (three repeats with a 20 s measured window), but in runs 2 and 3 the
machine was doing other work and the database was not settled first, run 6 came after a Windows
update and run 7 ran under Best performance. The run-to-run spread quoted here and on
`/accuracy` counts only the counted runs on the latest run's OS build and power mode.

## Environment

<!-- BEGIN GENERATED: environment -->
- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz; Docker 29.1.3 (linux/amd64) with 8 CPUs and 7.6 GiB; Windows-11-10.0.26300-SP0 (Best performance).
- Images: postgres `postgres:16-alpine`, redis `redis:7-alpine`, kafka `apache/kafka:3.8.0`, k6 `grafana/k6:1.3.0`, service `node:24-alpine + pg 8.23.1, redis 4.7.1, kafkajs 2.2.4`.
- CPUs pinned per container (cpuset; k6 gets the CPUs the stack leaves free): pg-mixed and pg-hot-row: service 1 (`2`), PostgreSQL 2 (`4,5`), k6 4 (`0,1,6,7`); cache-aside: service 1 (`2`), PostgreSQL 2 (`4,5`), Redis 1 (`1`), k6 3 (`0,6,7`); kafka: producer 1 (`2`), consumer 1 (`3`), PostgreSQL 2 (`4,5`), Kafka 2 (`0,1`), k6 2 (`6,7`).
<!-- END GENERATED: environment -->

**What the machine itself does** (`run.py envcheck`, 30 Oct, Windows build 26300, pg-mixed's
workload, `results/envcheck/`):

- **CPU per request falls steeply with the rate, in every container.** The Node.js service used
  0.96 ms per request at 50 req/s, 0.42 at 200, 0.29 at 400 and 0.21 to 0.22 from 800 to 1,600;
  PostgreSQL 0.93, 0.33, 0.21 and 0.19 ms. The cost settles only from about 800 req/s. The
  calibration passes (50 req/s and a quarter of the pessimistic capacity, a few hundred req/s)
  sit where it is highest and still changing, which is why protocol v4's CPU per request was
  about twice the real one near the knee. It looks like the host (cores idling into
  power-saving states at light load) rather than the application. Protocol v5 reads the cost at
  the top of a staircase instead, and under Best performance (run 7) the service's predicted CPU
  came within 4% to 34% of the measured one (see [Resources](#resources-cpu-and-memory)).
- **k6 on the service's hyperthread sibling** (CPUs 2 and 3 are one physical core in Docker's
  VM) cost the service 10% to 15% more CPU per request and raised p95 at 1,600 req/s from 1.4 to
  2.2 ms. Since run 7 the runner leaves that sibling idle (k6 on `0,1,6,7` for the
  PostgreSQL stack and `0,6,7` for the cache stack). PostgreSQL's two CPUs (`4,5`) are one
  physical core too, though the model gives it two cores; its CPU never came close to binding.
- **No CPU steal** in Docker's VM, and no p95 near Windows' 15.6 ms timer tick at these rates.
- **Every container shares the laptop's four physical cores, whatever its cpuset says**
  (`run.py contention`, 1 Nov, `results/envcheck/2026-11-01T00-48-59Z-contention.json`).
  Docker's VM numbers its CPUs as hyperthread pairs (2 and 3, 4 and 5, ...), but Windows
  schedules the VM's CPUs itself: a fixed unit of V8 work on CPU 2 with CPU 3 busy did 1.57 to
  1.70 times one CPU's work together, where two threads of one core do about 1.2. What sets how
  fast CPU work runs is how many of the laptop's 8 logical processors are busy, in the VM or in
  Windows: the probe's CPU per unit of work was 0.85 to 1.0 times its cost with 1.8 to 3.5 of
  them busy, 1.2 times with 4.6, 1.5 with 5.5, 1.9 with 6.6, 2.1 with 7.4 and about 3 with all
  8 busy (more work than processors). The clock fell in a straight line from 4,050 MHz with one
  busy to about 2,950 with eight, with no power or thermal limit flagged. Four physical cores,
  that clock (x0.73) and a thread sharing its core at 0.48 of its speed reproduce the probe's 20
  conditions below full load within 5.5% (rms, in log). A container's CPU time includes the time
  Windows takes its processor away (the VM reports no steal), so a container's CPU per request
  rises with the whole machine's load, not only its own. After about 15 minutes near full load
  (this check and other work) the laptop's clock fell to about 1,600 MHz and stayed low for a
  while, again with no limit flagged: runs need the machine cool as well as idle.

## pg-mixed

<!-- BEGIN GENERATED: pg-mixed -->
**Node.js + PostgreSQL: 90% reads, 10% writes over 100,000 rows.** Calibrated 2026-10-31T04:34:12Z, predicted 2026-10-31T04:39:37Z (engine 0.14.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![pg-mixed: predicted and measured p95 and throughput](validation/pg-mixed.svg)

Model inputs from a staircase of calibration passes at 50, 458, 1,129 and 1,674 req/s (each rate up and down again): the median of 3 replicate sets from the 1,674 req/s passes, CPU per request net of the 20 s at rest:

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.1675 ms, p95 0.4558 ms | the median of 3 replicates (first): mean = Node's CPU per request at 1674 req/s net of idle: (10127 - 4 ms at rest) / 50221 requests = 0.2016 ms; shape (p95/median 0.0088/0.0032 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0.0052 ms | the median of 3 replicates (first): anchored: (measured median 0.478 ms - the model's median without it 0.468 ms at 1674 req/s) / 2, one way, at least 0 |
| `readServiceTimeMs` | lognormal, median 0.2379 ms, p95 0.5929 ms | the median of 3 replicates (first): SELECT by key as the service sees it (round trip): median 0.238, p95 0.593 ms |
| `writeServiceTimeMs` | lognormal, median 0.257 ms, p95 0.6191 ms | the median of 3 replicates (third): UPDATE with synchronous_commit off (no WAL flush wait): median 0.257, p95 0.619 ms |
| `commitFlushMs` | lognormal, median 0.9703 ms, p95 1.6784 ms | the median of 3 replicates (second): UPDATE with synchronous_commit on minus off: median 1.224 - 0.254 ms, p95 2.258 - 0.580 ms |
| `dbCpuShare` | 0.4161 | the median of 3 replicates (second): PostgreSQL CPU per query at the top pass net of idle (0.1758 ms) / mean query time (0.422 ms) |

Measured in the calibration pass at 1,674 req/s: p50 0.48 ms, p95 1.63 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |
| --- | --- | --- | --- | --- | --- | --- |
| 1,238 | 0.49 / 0.48 | 1.69 / 1.54 | -9% | 3.13 / 2.15 | 100% / 100% | 1,238 / 1,238 |
| 2,475 | 0.51 / 0.49 | 2.04 / 1.60 | -22% | 6.34 / 2.33 | 100% / 100% | 2,475 / 2,475 |
| 3,712 | 0.67 / 0.55 | 8.67 / 1.80 | -79% | 26.5 / 2.57 | 100% / 100% | 3,712 / 3,712 |
| 4,455 | 1.45 / 0.75 | 65.1 / 2.28 | -96% | 109 / 3.13 | 97% / 100% | 4,342 / 4,456 |
| 4,950 | 74.3 / 9.88 | 122 / 27.1 | -78% | 145 / 33.7 | 90% / 100% | 4,435 / 4,947 |
| 5,445 | 113 / 1,239 | 174 / 1,916 | +1001% | 197 / 1,994 | 79% / 77% | 4,290 / 4,338 |
| 6,188 | 122 / 1,635 | 194 / 1,954 | +910% | 229 / 1,994 | 70% / 16% | 4,310 / 1,270 |
| 7,425 | 140 / - | 211 / - | - | 289 / - | 57% / 0% | 4,233 / 252 |

Knee: measured 4,006 req/s, predicted 4,956 req/s on the same steps (+24%); ArchSim's own search before measuring put it at 4,950 req/s. Its predicted range, from the 3 replicate sets, was 4,800 to 5,000 req/s: the measured knee fell outside it. Data: `benchmarks/results/pg-mixed/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: pg-mixed -->

## pg-hot-row

<!-- BEGIN GENERATED: pg-hot-row -->
**Node.js + PostgreSQL: every request updates the same row.** Calibrated 2026-10-31T02:21:39Z, predicted 2026-10-31T02:22:19Z (engine 0.14.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![pg-hot-row: predicted and measured p95 and throughput](validation/pg-hot-row.svg)

Model inputs from a staircase of calibration passes at 50, 212 and 300 req/s (each rate up and down again): the median of 3 replicate sets from the 300 req/s passes, CPU per request net of the 20 s at rest:

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.2676 ms, p95 0.8651 ms | the median of 3 replicates (first): mean = Node's CPU per request at 300 req/s net of idle: (3110 - 3 ms at rest) / 9001 requests = 0.3451 ms; shape (p95/median 0.0181/0.0056 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0 ms | the median of 3 replicates (second): anchored: (measured median 1.459 ms - the model's median without it 1.536 ms at 300 req/s) / 2, one way, at least 0 |
| `writeServiceTimeMs` | lognormal, median 0.3286 ms, p95 0.8756 ms | the median of 3 replicates (third): UPDATE with synchronous_commit off (no WAL flush wait): median 0.329, p95 0.876 ms |
| `commitFlushMs` | lognormal, median 0.8504 ms, p95 1.3282 ms | the median of 3 replicates (first): UPDATE with synchronous_commit on minus off: median 1.176 - 0.325 ms, p95 2.238 - 0.910 ms |
| `dbCpuShare` | 0.2346 | the median of 3 replicates (first): PostgreSQL CPU per query at the top pass net of idle (0.3174 ms) / mean query time (1.353 ms) |

Measured in the calibration pass at 300 req/s: p50 1.46 ms, p95 2.81 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |
| --- | --- | --- | --- | --- | --- | --- |
| 196 | 1.83 / 1.54 | 3.21 / 2.42 | -25% | 3.71 / 3.01 | 100% / 100% | 196 / 196 |
| 391 | 1.31 / 1.57 | 2.67 / 2.47 | -7% | 3.99 / 3.13 | 100% / 100% | 391 / 391 |
| 586 | 1.23 / 1.63 | 2.63 / 2.67 | +2% | 4.73 / 3.46 | 100% / 100% | 586 / 586 |
| 704 | 1.21 / 1.95 | 3.03 / 3.60 | +19% | 6.75 / 4.47 | 100% / 100% | 704 / 704 |
| 782 | 1.21 / 7.79 | 3.96 / 29.9 | +657% | 26.5 / 37.2 | 100% / 100% | 782 / 781 |
| 860 | 1.34 / 1,239 | 11.2 / 1,916 | +16999% | 35.5 / 1,994 | 100% / 81% | 860 / 713 |
| 978 (unstable) | 2.17 / 1,603 | 80.1 / 1,954 | +2340% | 145 / 1,994 | 100% / 16% | 976 / 208 |
| 1,173 | 1,022 / - | 1,828 / - | - | 1,847 / - | 91% / 0% | 1,069 / 43 |

Unstable: the step's repeats' p95s differ by more than 2x across the objective (a repeat with no successful request counts as slower than any).

Knee: measured 926 req/s, predicted 783 req/s on the same steps (-16%); ArchSim's own search before measuring put it at 782 req/s. Its predicted range, from the 3 replicate sets, was 775 to 788 req/s: the measured knee fell outside it. Data: `benchmarks/results/pg-hot-row/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: pg-hot-row -->

## cache-aside

<!-- BEGIN GENERATED: cache-aside -->
**Cache-aside: Node.js, Redis (30 s TTL) and PostgreSQL; Zipf 0.99 over 100,000 keys.** Calibrated 2026-10-31T02:44:32Z, predicted 2026-10-31T02:49:51Z (engine 0.14.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![cache-aside: predicted and measured p95 and throughput](validation/cache-aside.svg)

Model inputs from a staircase of calibration passes at 50, 338, 831 and 1,335 req/s (each rate up and down again): the median of 3 replicate sets from the 1,335 req/s passes, CPU per request net of the 20 s at rest:

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.0936 ms, p95 0.7344 ms | the median of 3 replicates (first): mean = Node's CPU per request at 1335 req/s net of idle: (8219 - 6 ms at rest) / 40051 requests = 0.2051 ms; shape (p95/median 0.0455/0.0058 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0.0364 ms | the median of 3 replicates (first): anchored: (measured median 0.394 ms - the model's median without it 0.321 ms at 1335 req/s) / 2, one way, at least 0 |
| `readServiceTimeMs` | lognormal, median 0.2614 ms, p95 0.7182 ms | the median of 3 replicates (first): SELECT by key as the service sees it (round trip): median 0.261, p95 0.718 ms |
| `dbCpuShare` | 0.5793 | the median of 3 replicates (first): PostgreSQL CPU per query at the top pass net of idle (0.1997 ms) / mean query time (0.345 ms) |
| `cacheServiceTimeMs` | exponential, mean 0.00301 ms | the median of 3 replicates (first): Redis INFO commandstats: 158057 us over 52573 GET/SET calls |
| `cacheLinkLatencyMs` | 0.0589 ms | the median of 3 replicates (second): (GET round trip median 0.121 ms - Redis's own time 0.0030 ms) / 2, one way |

Measured in the calibration pass at 1,335 req/s: p50 0.40 ms, p95 1.23 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred | Hit ratio meas / pred |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1,212 | 0.42 / 0.39 | 1.39 / 1.48 | +6% | 2.65 / 3.46 | 100% / 100% | 1,212 / 1,212 | 69% / 68% |
| 2,425 | 0.41 / 0.45 | 1.44 / 2.33 | +62% | 4.29 / 4.84 | 100% / 100% | 2,425 / 2,425 | 74% / 74% |
| 3,638 | 0.44 / 0.72 | 2.17 / 5.04 | +132% | 7.59 / 9.88 | 100% / 100% | 3,638 / 3,639 | 77% / 77% |
| 4,365 | 0.45 / 1.73 | 2.74 / 8.95 | +227% | 9.94 / 15.6 | 100% / 100% | 4,365 / 4,368 | 79% / 79% |
| 4,850 | 0.51 / 16.2 | 4.82 / 47.2 | +879% | 21.1 / 56.4 | 100% / 100% | 4,850 / 4,849 | 80% / 80% |
| 5,335 | 0.55 / 1,215 | 7.77 / 1,916 | +24562% | 41.8 / 1,994 | 99% / 87% | 5,308 / 4,767 | 80% / 80% |
| 6,062 | 0.63 / 1,635 | 8.49 / 1,954 | +22923% | 28.3 / 1,994 | 100% / 17% | 6,060 / 1,325 | 81% / 80% |
| 7,275 | 1.04 / - | 25.4 / - | - | 54.1 / - | 99% / 0% | 7,213 / 263 | 83% / 80% |
| 9,458 | 1.66 / - | 60.8 / - | - | 98.2 / - | 93% / 0% | 8,779 / 0 | 84% / 80% |

Knee: measured 7,324 req/s, predicted 4,851 req/s on the same steps (-34%); ArchSim's own search before measuring put it at 4,850 req/s. Its predicted range, from the 3 replicate sets, was 4,525 to 4,850 req/s: the measured knee fell outside it. Data: `benchmarks/results/cache-aside/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).
<!-- END GENERATED: cache-aside -->

## kafka

<!-- BEGIN GENERATED: kafka -->
**Kafka: Node.js producer, 6 partitions, 3 consumers inserting into PostgreSQL.** Calibrated 2026-10-31T03:26:27Z, predicted 2026-10-31T03:30:01Z (engine 0.14.0), measured afterwards. Objective: p95 <= 50 ms with >= 99% success.

![kafka: predicted and measured p95 and throughput](validation/kafka.svg)

Model inputs from a staircase of calibration passes at 50, 228, 423 and 569 req/s (each rate up and down again): the median of 3 replicate sets from the 569 req/s passes, CPU per request net of the 20 s at rest:

| Input | Value | How it was measured |
| --- | --- | --- |
| `serviceTimeMs` | lognormal, median 0.4051 ms, p95 1.0238 ms | the median of 3 replicates (second): mean = Node's CPU per request at 569 req/s net of idle: (9361 - 1255 ms at rest) / 17071 requests = 0.4748 ms; shape (p95/median 0.0242/0.0096 ms) from the handler's own time |
| `clientLinkLatencyMs` | 0 ms | the median of 3 replicates (second): anchored: (measured median 0.857 ms - the model's median without it 1.013 ms at 569 req/s) / 2, one way, at least 0 |
| `publishTimeMs` | lognormal, median 0.4992 ms, p95 2.8254 ms | the median of 3 replicates (first): producer.send with acks=all on one broker: median 0.499, p95 2.825 ms |
| `consumerTimeMs` | lognormal, median 0.0084 ms, p95 0.0201 ms | the median of 3 replicates (second): consumer's own time per message (handling minus the INSERT): median 0.008, p95 0.020 ms |
| `commitFlushMs` | lognormal, median 0.8296 ms, p95 1.4195 ms | the median of 3 replicates (second): UPDATE with synchronous_commit on minus off: median 1.129 - 0.299 ms, p95 2.222 - 0.803 ms |
| `writeServiceTimeMs` | lognormal, median 0.7355 ms, p95 2.0483 ms | the median of 3 replicates (first): consumer's INSERT time (median 1.548, p95 2.861 ms) minus the flush median |
| `dbCpuShare` | 0.1915 | the median of 3 replicates (second): PostgreSQL CPU per query at the top pass net of idle (0.3365 ms) / mean query time (1.758 ms) |

Measured in the calibration pass at 569 req/s: p50 0.86 ms, p95 3.54 ms.

| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred | Consumed/s meas / pred | Message age p95 meas / pred (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 525 | 0.92 / 1.01 | 4.64 / 3.32 | -28% | 9.04 / 6.52 | 100% / 100% | 525 / 525 | 525 / 525 | 14.0 / 3.90 |
| 1,050 | 0.98 / 1.03 | 10.5 / 3.39 | -68% | 23.4 / 6.52 | 100% / 100% | 1,050 / 1,050 | 1,050 / 1,050 | 132 / 10.1 |
| 1,575 | 1.26 / 1.19 | 22.4 / 3.67 | -84% | 63.4 / 6.65 | 100% / 100% | 1,568 / 1,575 | 1,570 / 1,383 | 2,660 / 2,904 |
| 1,890 | 1.55 / 1.60 | 42.5 / 4.94 | -88% | 85.2 / 7.79 | 99% / 100% | 1,877 / 1,890 | 1,671 / 1,383 | 7,440 / 6,413 |
| 2,100 | 1.90 / 15.3 | 54.0 / 33.7 | -38% | 93.1 / 41.9 | 99% / 100% | 2,077 / 2,102 | 1,659 / 1,383 | 11,177 / 8,133 |
| 2,310 | 2.75 / 1,264 | 63.7 / 1,916 | +2908% | 105 / 1,994 | 98% / 78% | 2,273 / 1,853 | 1,632 / 1,383 | 11,300 / 8,295 |
| 2,625 | 3.41 / 1,635 | 80.5 / 1,954 | +2329% | 136 / 1,994 | 96% / 15% | 2,520 / 524 | 1,630 / 1,383 | 10,920 / 8,295 |
| 3,150 | 19.5 / - | 133 / - | - | 220 / - | 85% / 0% | 2,682 / 106 | 1,568 / 1,383 | 12,189 / 8,295 |

Knee: measured 2,028 req/s, predicted 2,102 req/s on the same steps (+4%); ArchSim's own search before measuring put it at 2,100 req/s. Its predicted range, from the 3 replicate sets, was 2,075 to 2,200 req/s: the measured knee fell outside it. Data: `benchmarks/results/kafka/` (each repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).

**Harness caveat.** Every Kafka run so far was measured with a broker healthcheck that started a Java tool every 3 s on the broker's own core (about half a core, all the time). Without it the same stack held the objective up to about 3,000 req/s in a trial on 31 Oct, so these measured knees are the benchmark's limit, not Kafka's. The healthcheck is fixed in compose.yaml; the next campaign measures Kafka again.
<!-- END GENERATED: kafka -->

## Resources: CPU and memory

Besides latency and capacity, ArchSim predicts how busy each part's CPU is (busy cores) and, for a
part with memory set, how much memory it holds. Auto-sizing picks instance types from those
figures, so they are checked here against each container's own counters (cgroup v2) at every
load step.

<!-- BEGIN GENERATED: resources -->
| Experiment | Part | Load step (req/s) | Busy cores measured | Busy cores predicted | Error | Median error up to both knees (steps) | Memory measured | Memory predicted | Error |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| [pg-mixed](#pg-mixed) | Node.js service | 3,712 | 0.77 | 0.75 | -4% | 4% (3) | 145 MiB | 278 MiB | +91% |
| [pg-mixed](#pg-mixed) | PostgreSQL | 3,712 | 0.73 | 0.43 | -41% | 36% (3) | 98 MiB | - | - |
| [pg-hot-row](#pg-hot-row) | Node.js service | 782 | 0.20 | 0.27 | +34% | 25% (5) | 66 MiB | 320 MiB | +386% |
| [pg-hot-row](#pg-hot-row) | PostgreSQL | 782 | 0.23 | 0.07 | -69% | 69% (5) | 105 MiB | - | - |
| [cache-aside](#cache-aside) | Node.js service | 4,850 | 0.80 | 0.99 | +24% | 12% (5) | 182 MiB | 920 MiB | +407% |
| [cache-aside](#cache-aside) | PostgreSQL | 4,850 | 0.21 | 0.18 | -16% | 18% (5) | 97 MiB | - | - |
| [cache-aside](#cache-aside) | Redis | 4,850 | 0.18 | 0.02 | -90% | 91% (5) | 12 MiB | 28 MiB | +130% |
| [kafka](#kafka) | Node.js producer | 1,890 | 0.68 | 0.90 | +31% | 20% (4) | 179 MiB | 292 MiB | +63% |
| [kafka](#kafka) | Node.js consumer | 1,890 | 0.30 | not modelled | - | - | 176 MiB | - | - |
| [kafka](#kafka) | PostgreSQL | 1,890 | 0.47 | 0.23 | -50% | 51% (4) | 122 MiB | - | - |
| [kafka](#kafka) | Kafka broker | 1,890 | 1.02 | not modelled | - | - | 1.1 GiB | - | - |

Each part at the highest load step at or below both knees, the measured one and the model's: past the model's own knee its queue holds every request the real service would time out, so its CPU reads fully busy and its memory grows without bound, and the knee error already counts that. Busy cores are CPU seconds per second; errors are (predicted - measured) / measured.

Measured: each container's CPU and working set (memory less reclaimable page cache), read every 5 s inside the window k6 measures. Predicted: ArchSim's predictions, saved before the measurements.

Not modelled: Node.js consumer (inside the model's queue node, which has no CPU or memory); Kafka broker (the model's queue node has no CPU or memory).
<!-- END GENERATED: resources -->

What the numbers say. All four were read inside the measured window of each experiment's latest
run (run 7, protocol v5, 31 Oct), against ArchSim's predictions saved before the measurements.

- **Node.js services: close, and too high where the real cost falls with load.** Protocol v5
  charges every request the service's CPU per request at the top of its calibration staircase,
  net of what it burns at rest: 0.20 ms in pg-mixed and cache-aside, 0.35 ms in hot-row and
  0.47 ms for Kafka's producer. In pg-mixed the real cost stayed at 0.21 to 0.22 ms per request
  at every step, and ArchSim's busy cores are within 4% (median up to both knees). Elsewhere the
  real event loop kept getting cheaper as load rose: cache-aside from 0.23 to 0.16 ms, Kafka's
  producer from 0.59 to 0.36 ms and hot-row from 0.44 to 0.26 ms, so at the top step ArchSim is
  24% to 34% high (median 12% to 25%). Under v4 the median errors were 32% to 116%. One core
  divided by the calibrated cost still sets the predicted knee in pg-mixed, cache-aside and
  Kafka (4,950, 4,850 and 2,100 req/s).
- **PostgreSQL: 16% to 69% low at the top step.** ArchSim's figure is low in every experiment:
  hot-row 0.07 cores against 0.23, Kafka 0.23 against 0.47, pg-mixed 0.43 against 0.73 and
  cache-aside 0.18 against 0.21. Calibration measures PostgreSQL's CPU per query at load (0.18 ms
  in pg-mixed's top pass, close to the 0.18 to 0.20 ms per request measured at every step), so
  the loss is in how the model turns it into busy cores: as a share of the query time it
  simulates (`dbCpuShare`). The benchmark divided that CPU by the mean query time the service
  measured, which counts each write's WAL flush and the measured tail, while the model spends
  the share of the time it samples: writes without their flush (the flush uses no CPU in the
  model), from a lognormal whose mean is lower. In hot-row the model spent 0.09 ms of CPU per
  query against the 0.32 measured. Since engine 0.15.0 (W22) a query's time in PostgreSQL is
  its CPU per query, so the share is about 1; re-simulated in-sample (development checks, three
  counted runs each), the top step's median error is -11% for pg-mixed and hot-row, -8% for
  cache-aside and -4% for Kafka.
- **Redis: about 90% too low.** The model's Redis time per command is what Redis reports as
  command time (`INFO commandstats`), which leaves out reading and writing the network; the
  real Redis used about nine times that CPU (0.18 cores against 0.02 at 4,850 req/s). Latency is
  not affected (the link time carries it), but a Redis node sized from this CPU would be too
  small.
- **Memory: ArchSim's service memory is 1.6x to 5.9x too high.** The Node.js services' working
  set grew with the load, up to both knees: pg-mixed from 45 to 145 MiB, cache-aside 63 to 182,
  Kafka's producer 48 to 179 and hot-row 49 to 66 (V8's heap grows with the rate it allocates at
  between garbage collections). ArchSim, from its defaults of 256 MiB at idle plus 2 MiB per
  request held, predicted 258 to 920 MiB: far too much at idle, and growing with requests held
  rather than with throughput. Redis held 11 to 12 MiB against ArchSim's 10 to 28 MiB (1 KiB per
  key). PostgreSQL's working set stayed between 92 and 122 MiB and the Kafka broker's about
  1.1 GiB; the model has no figure for either.
- **Not modelled:** the Kafka broker's CPU (0.9 to 1.0 cores at every step) and the consumer's
  (0.30 to 0.33 cores); queue nodes have no CPU or memory.

Memory is each container's working set: its memory less the page cache the kernel reclaims
first, as Kubernetes counts it (anonymous memory is recorded too). In these runs the model's
service memory came from ArchSim's defaults (calibration fits it since W24, after these runs) and
Redis's from 1 KiB per key, which is not calibrated. Past the
model's own knee its service holds every queued request, so its memory there grows far beyond
the real one; errors count only steps up to both knees.

How it was measured: since run 5 the runner reads each container's CPU and memory every 5 s inside the
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

From the baseline campaign (run 7: protocol v5, engine 0.14.0, three counted runs of each,
30-31 Oct 2026), on one Windows laptop with Docker Desktop set to Best performance:

- **Capacity (median knee error): Kafka +4%, pg-hot-row -16%, pg-mixed +24%, cache-aside
  -34%.** Each has its own cause:
  - *pg-mixed (+20% to +25% in each run): latency rises before the CPU fills.* The model's CPU is
    right (above), and its knee is where the event loop's core fills: 4,950 req/s. The real
    service's p95 climbs well before that: 2.0 ms at 2,475 req/s, 8.7 ms at 3,712 (with the
    service 77% busy) and 65 ms at 4,455, where the model predicts 1.6, 1.8 and 2.3 ms. Garbage
    collection, the first suspect, is not the cause: a calibration-only trial with the service
    reporting it (W21, `results/trials/W21-README.md`) found V8's pauses take 0.1% to 0.45% of
    the event loop's time, and its p95 at 3,712 req/s was 1.74 ms. At 4,455 req/s that trial
    overloaded with Node 89% busy and its CPU per request up 31%, and PostgreSQL saturating its
    two CPUs (hyperthread siblings of one physical core) with its CPU per query up from 0.16 to
    0.26 ms. So the likely cause is a CPU cost that rises near full load, in both processes at
    once: on this laptop its clock falls from about 4,050 to 2,950 MHz as its 8 logical
    processors fill, and past one busy thread per core the threads share cores (`run.py
    contention`, [Environment](#environment)). Giving the model's PostgreSQL its one physical
    core instead of two logical CPUs does not move the model's knee, which stays Node's (W22's
    what-if). Engine 0.16.0 can model a machine's threads slowing each other
    (`architecture.host`), but the benchmark designs do not use it yet: re-simulated in-sample
    (with k6's CPU and the machine's background assumed, not measured), it takes pg-mixed from
    +24% to +11% and PostgreSQL's CPU near the knee from 12% low to about right, while
    cache-aside goes from -34% to -41% and Kafka from +4% to -22% (IMPLEMENTATION_STATUS.md,
    W27): the real CPU per request also falls with load, which the model has only up to the top
    of the calibration staircase.
  - *cache-aside (-26% to -34%): the CPU per request falls with load.* The predicted knee is one
    core at the calibrated 0.20 ms per request (4,850 req/s); the real cost fell to 0.16 ms by
    then and kept falling, and the real knee was about 7,000 req/s.
  - *pg-hot-row (-19% to 0%): the row lock is held too long in the model.* The model holds the
    lock for the write and its commit flush as calibrated (0.33 + 0.85 ms, medians), which caps
    the row near 850 req/s; both are timed from the service, so they include network and driver
    time that is not spent holding the lock. The real stack held p95 under 4 ms up to 782 req/s,
    where the model reached 30 ms. PostgreSQL's own statistics (W22's trial, 31 Oct) show how
    much: the UPDATE spends 0.03 to 0.06 ms in the executor and its flush 0.79 ms, while
    PostgreSQL's CPU per UPDATE is 0.19 to 0.23 ms, most of it waking up, reading the query and
    replying, outside the row's lock. The flush also gets faster with load: 1.06 ms per flush at
    196 req/s, 0.79 from about 400 req/s, so a staircase that tops out low (the campaign's first
    hot-row run, `run-6/`: 221 req/s) measures it too long. Engine 0.15.0 takes Node's driver
    and network time off the lock (the query's time in PostgreSQL is its CPU per query; the rest
    of the round trip is the link to the database); in-sample, hot-row's median knee error goes
    from -16% to -7% (-11%, +10% and -7% over the three runs). The protocol work outside the lock is still
    held under it: the engine has no part of a query's server time that holds the connection
    but not the lock.
  - *Kafka (+3% to +33%): two errors that roughly cancel.* The model's knee is the producer's
    CPU (2,100 req/s at 0.47 ms per request), which is 31% high at that load (above). The real
    knee comes from a latency tail that grows with load: the client's p95, which waits for the
    publish, rose from 4.6 ms at 525 req/s to 42 ms at 1,890, while the model's stayed between
    3.3 and 4.9 ms. That tail was the benchmark's, not Kafka's: the broker's healthcheck started
    a JVM every 3 s on the broker's one physical core (about 0.5 cores of CPU). Without it the
    stack's p95 was 5.0 ms at 1,890 req/s (W23 trial, `benchmarks/results/trials/2026-10-31-kafka-w23/`);
    `compose.yaml` runs the check only until it first succeeds from run 8 on, so the kept runs'
    knee is not Kafka's. The first run's +33% came from its calibration: its staircase topped at
    726 req/s rather than about 570, and read 0.35 ms per request rather than 0.46 to 0.47
    (predicted knee 2,829 req/s), the falling cost again.
- **Low-load latency is within 1 ms, except Kafka's.** At a quarter and half of the predicted
  knee (table after this list) ArchSim's p95 is 0.09 to 0.89 ms off for pg-mixed, hot-row and
  cache-aside. Kafka's measured p95 was 4.6 ms at 525 req/s and 10.5 ms at 1,050 against the
  model's 3.3 and 3.4 ms: the healthcheck's tail (above), which the model rightly does not have.
- **Criterion 2 hides under-predicted latency.** It allows ±100% of the measured p95, and a
  prediction that is too low can never be more than 100% below. pg-mixed's 3,712 req/s step
  (1.8 ms predicted, 8.7 measured) and Kafka's steps from 1,050 req/s (3.4 to 4.9 ms against
  10.5 to 42.5) pass it for that reason alone; within a factor of two they would fail.
- **The model's cliff is sharper than the real one.** Past its knee ArchSim's p95 jumps to the 2 s
  client timeout within one or two steps; the real systems degrade over several steps
  (pg-mixed 122 ms at 4,950 req/s and 174 ms at 5,445; cache-aside 7.8 ms at 5,335, where ArchSim
  predicts 1.9 s). So "how far past the knee can I go" is answered pessimistically.
- **Kafka's consumers and message age.** The consumer kept up with about 1,630 to 1,670
  messages/s; ArchSim capped it at 1,383/s from the INSERT and commit times measured at the top
  of calibration. Below that limit real messages waited longer than the model's (p95 132 ms
  against 10 ms at 1,050 req/s); above it the two queues grew alike (7.4 s against 6.4 s at
  1,890). The client's p95, which the criteria check, does not include this wait. The INSERT and
  commit inputs are not the cause: with them timed inside PostgreSQL (engine 0.15.0, W22) the
  cap moves only from 1,383 to 1,404 messages/s in-sample (1,411 to 1,445 and 1,435 to 1,474 in
  the other two runs). In W22's trial the consumer's INSERTs shared more WAL flushes as load
  rose (1.0 commits per flush at 237 req/s, 1.5 at 1,200 to 1,500) while each flush grew (0.94
  to about 1.2 ms); the consumers' fetch cycle is W23's.
- **ArchSim's predicted range is too narrow.** It held the measured knee in 1 of 12 runs (nominal
  50%). Within a run the three replicate calibration sets agree to a few percent, while the
  calibration itself moves between runs: hot-row's prediction was 663, 958 and 783 req/s,
  Kafka's 2,829, 2,140 and 2,102. A range that covers the knee needs that run-to-run variation.

p95 at the ladder's low steps (at most half the predicted knee):

<!-- BEGIN GENERATED: low-load -->
| Experiment | Offered req/s | p95 measured (ms) | p95 predicted (ms) | Predicted - measured |
| --- | --- | --- | --- | --- |
| pg-mixed | 1,238 (0.25x the predicted knee) | 1.69 | 1.54 | -0.16 ms (-9%) |
| pg-mixed | 2,475 (0.5x the predicted knee) | 2.04 | 1.60 | -0.44 ms (-22%) |
| pg-hot-row | 196 (0.25x the predicted knee) | 3.21 | 2.42 | -0.79 ms (-25%) |
| pg-hot-row | 391 (0.5x the predicted knee) | 2.67 | 2.47 | -0.20 ms (-7%) |
| cache-aside | 1,212 (0.25x the predicted knee) | 1.39 | 1.48 | +0.09 ms (+6%) |
| cache-aside | 2,425 (0.5x the predicted knee) | 1.44 | 2.33 | +0.89 ms (+62%) |
| kafka | 525 (0.25x the predicted knee) | 4.64 | 3.32 | -1.32 ms (-28%) |
| kafka | 1,050 (0.5x the predicted knee) | 10.5 | 3.39 | -7.15 ms (-68%) |
<!-- END GENERATED: low-load -->

What engine 0.15.0 changed (the plan's W21 to W24, 31 Oct 2026, each tuned on these four
experiments only; in-sample re-simulation of every kept run in
[`docs/validation/dev-checks.md`](validation/dev-checks.md)):

1. **Service CPU that follows load** (`serviceTimeByLoad`, W21), fitted from the calibration
   staircase. It stays flat above the staircase's top rate, about a third of the knee, so it
   corrects CPU at light load but moves no knee.
2. **Node.js garbage collection** calibrated from the service's own report (W21): too small to
   move a knee.
3. **Database inputs timed inside PostgreSQL** (W22): a query's time in the database is its CPU
   per query, the rest of the measured round trip is the link, and the WAL flush is PostgreSQL's
   own. PostgreSQL's predicted busy cores at the top step went from 16% to 70% low to 4% to 11%
   low, and hot-row's median knee error from -16% to -7%.
4. **Kafka** (W23): message age from the start of the publish, and consumers that poll in
   cycles as kafkajs does. The publish tail that grew with load was the benchmark's healthcheck,
   fixed in `compose.yaml` rather than modelled.
5. **Sizing inputs** (W24): Redis's CPU per command including its network work (from 90% low to
   14% to 19% high) and each service's memory fitted over the staircase (from 1.6 to 5.9 times
   too high to 10% to 76% too low near the knee).

What would make it better next, in order:

1. A CPU cost per request that rises near full load (pg-mixed's +24%), and how hyperthreaded
   vCPUs turn into capacity, for services and databases alike: a cloud vCPU is a hyperthread
   too.
2. A CPU per request that keeps falling with load above the calibration staircase
   (cache-aside's -34%): calibrate higher, or model the event loop's batching under load.
3. A predicted range that includes the calibration's run-to-run variation (it held the
   measured knee in 1 of 12 runs).
4. Memory near the knee (a Node.js heap grows faster than linearly with load), a flush and a CPU
   per query that fall with load, and Kafka's rare consumer stalls (message age at mid load).

Limits of this evidence: one machine, three counted runs per experiment under protocol v5,
Windows with Docker Desktop (a virtual machine between the containers and the hardware) whose
power mode (with k6 moved off the service's core) moved two knees by 45% to 65%, small synthetic
services, and all four
experiments in-sample for the protocol and the engine; k6's own summaries are not kept, only the
numbers taken from them. It shows that on this machine engine 0.14.0 predicts three of the four
knees within 25% (median of three runs), with errors on both sides; it does not show how ArchSim
does on stacks it was not tuned on (the held-out experiments are for that), on other machines or
on real applications.

## Held-out experiments

The four experiments above are in-sample: the model and the calibration protocol were revised
with their results in view. Five more were registered on 30 Oct 2026 in commit `90f067d`
(`benchmarks/run.py`, `Experiment.held_out`) to test ArchSim on stacks it was never
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
- **Changed after registration, before any measurement (31 Oct 2026):** two library updates to
  clear GitHub's dependency alerts. go-mixed's PostgreSQL driver pgx went from 5.7.6 to 5.9.2,
  which also drops `golang.org/x/crypto`, and python-mixed's Flask from 3.1.2 to 3.1.3. None of
  the flagged code could be reached (the SSH packages were never compiled in, the pgx bug is on
  the server side of the protocol, and the service does not use Flask's sessions). No held-out
  experiment had been calibrated or measured, so no result depends on the old versions.

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

A technology is **validated** when every benchmark that exercises it has, over at least three
counted runs under the same protocol, machine, OS build, power mode and engine:

1. a median knee error within ±25%;
2. at or below the measured knee, every step's p95 error within ±100% or 3 ms absolute, whichever
   is larger (so sub-millisecond noise does not fail it), in at least two of three runs;
3. no unstable step at or below the measured knee, in at least two of three runs (3a), and a run
   under the same protocol on a second machine, or the hosted runner (3b).

Criterion 2 is one-sided as written: a predicted p95 that is too low is at most 100% below the
measured one, so it always passes (see [above](#where-the-model-is-wrong-and-why)). A symmetric
rule, within a factor of two of the measured p95 or 3 ms, would fail pg-mixed and Kafka on this
data; the owner decides.

Computed by `benchmarks/report.py` from each experiment's latest run and its kept runs:

<!-- BEGIN GENERATED: tiers -->
| Experiment | 1. Median knee error within ±25% (3+ runs) | 2. p95 within ±100% or 3 ms at or below the measured knee (2 of 3 runs) | 3a. No unstable step at or below the measured knee (2 of 3 runs) | 3b. Reproduced on a second machine, same protocol | All three |
| --- | --- | --- | --- | --- | --- |
| pg-mixed | yes (median +24% over 3 runs; 2 disturbed runs not counted) | yes: 3 of 3 steps within; 3 of 3 runs pass | yes: none unstable; 3 of 3 runs pass | no: 5 runs under v5, on 1 machine | no |
| pg-hot-row | yes (median -16% over 3 runs) | no: 782 and 860 req/s miss (4 of 6 steps within); 1 of 3 runs pass | yes: none unstable; 2 of 3 runs pass | no: 3 runs under v5, on 1 machine | no |
| cache-aside | no (median -34% over 3 runs) | no: 4,365, 4,850, 5,335, 6,062 and 7,275 req/s miss (3 of 8 steps within); 0 of 3 runs pass | yes: none unstable; 3 of 3 runs pass | no: 3 runs under v5, on 1 machine | no |
| kafka | yes (median +4% over 3 runs) | yes: 4 of 4 steps within; 3 of 3 runs pass | yes: none unstable; 3 of 3 runs pass | no: 3 runs under v5, on 1 machine | no |

A technology meets the thresholds when every experiment that exercises it meets all three: Node.js (pg-mixed, pg-hot-row, cache-aside, kafka); PostgreSQL (pg-mixed, pg-hot-row, cache-aside, kafka); Redis (cache-aside); Kafka (kafka). None meets them on this data.
<!-- END GENERATED: tiers -->

The criteria check the client's p95 only. Kafka's consumer side (consumed per second, message
age) is further off (above: the consumer capped at 1,383 messages/s where it kept up with about
1,650/s, and message age 13 times the model's at 1,050 req/s), so Kafka would stay experimental
for asynchronous questions even if it met all three, until the consumers' fetch cycle is
modelled (W23). On this data the core set stays a focus list, as the badge says today.
