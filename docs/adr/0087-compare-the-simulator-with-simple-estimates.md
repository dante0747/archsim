---
status: accepted
date: 2026-11-19
decision-makers: Majid Abarghooei (owner)
---

# 0087. Compare the simulator with simple estimates of the knee

## Context and Problem Statement

[docs/validation.md](../validation.md) sets each experiment's predicted knee beside the measured
one. A strict review of that evidence found that nothing in it shows the simulator predicting a
stack's knee better than simple arithmetic would: a knee error of +16% says how far the
simulator is from the measurement, not what the simulation adds to a division anyone can do on
the same calibration.

What should the simulator's knee be compared with, where are those figures kept, and how does the
page show them?

## Decision Drivers

- A baseline uses what the prediction used, the run's calibration, and no measured load step.
- A reader can check each figure by hand from the run's own files.
- The recorded results are never edited
  ([ADR-0068](0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md)).
- The page reports whatever comes out, a baseline that beats the simulator included.
- A name says what a method is: no estimate is called by the name of a stronger one.

## Considered Options

- (a) Three baselines for every kept run, written beside its comparison as a new derived file,
  `baselines.json`, by a new command, and shown in one new block of the page: bottleneck
  arithmetic on the calibrated inputs, the engine's own analytic estimate, and a straight line
  through the staircase's measured CPU
- (b) Bottleneck arithmetic only
- (c) A Universal Scalability Law fit to the calibration staircase as the model-free baseline
- (d) Compute the baselines inside `report`, with no file
- (e) Add the baselines to `comparison.json`

## Decision Outcome

Chosen option: (a).

**The command.** `baselines` (no Docker) writes `baselines.json` beside each kept run's
`comparison.json`. It reads `calibration.json` (and `environment.json` for the cpusets) and
nothing else of the run, so it cannot use a measured step. Each estimate reads the inputs the
calibration recorded, the ones the run's predictions used, in the design today's harness builds
from them (`Designs`); nothing is derived again. The file records the engine's version, the
provenance a run's predictions record (commit, engine and harness hashes, the build, whether
anything was uncommitted, the machine) and the date. It also records the day the estimates'
rules were fixed (`rulesFixed`, 19 Nov 2026) and whether its run was calibrated before that day
(`designedAfterMeasurements`): for the 67 runs kept until then the rules were written with the
results in view, and for a later run they predate the measurement. A file whose figures are
today's stays as it is, under the stamp of what first found them, so running the command again
changes nothing; `--rewrite` stamps every file anew. A new run deletes the baselines its
calibration replaces.

**The three estimates**, each with how it was computed and what bounds it:

- **Bottleneck arithmetic** (`bottleneckArithmetic`). Each hard limit of the design's parts is
  what the part has times 1,000 over the milliseconds of one unit a request takes, from the
  calibrated inputs' means: a service's or a cache's cores over its CPU per request at the top
  calibration rate; a pool of workers that wait for their calls over the time a request holds
  one; a database's cores over its queries' CPU and its connections over their time, a write's
  with its commit flush; the lock of a row that every write updates over the time a write holds
  it; a queue's consumers over the time a message keeps one, a consumer that sets no
  concurrency taking one message at a time, as the model reads it. A cache passes on to its
  database the share of lookups that missed in the top calibration pass. The knee is the
  smallest limit. No queueing, no spread, no garbage collection pauses, no simulation.
- **The engine's analytic estimate** (`analyticEstimate`). The highest load at which the
  engine's instant estimate of the design (`DesignAnalyzer`, the queueing formulas the app and
  the command line show before a run) gives a p95 within the objective, searched as the simulated
  knee is (`KneeSearch`: doubling from 100 req/s, then bisection to 1%). The estimate fails no
  request, so a load meets the objective when the estimate has a p95 there and it is within 50
  ms. When it gives no p95 at any load, the knee is the load it says the design saturates at,
  and the file says so (`basis`); when its p95 is over the objective at every load tried, the
  file has no knee and says why, where the search would give zero. The file keeps every load
  tried with its p95, and what the estimate says at the knee.
- **Utilisation extrapolation** (`utilisationExtrapolation`). Each container's CPU in the
  calibration staircase's passes, in busy cores net of what it burns at rest, against the offered
  rate; a least-squares line through them; and the load at which the line reaches the
  container's CPUs (its cpuset, as `environment.json` recorded it) less its CPU at rest. The knee
  is the lowest such load. It reads no model input. A run calibrated before the staircase
  (protocols up to v4) has no value and says why.

**What a request does not wait for does not bound a knee.** The objective judges the clients'
requests, so a Kafka consumer and the PostgreSQL it inserts into are listed in the file
(`onRequestPath: false`) but leave the arithmetic's and the extrapolation's knee alone, as they
leave the simulated and the measured one.

**The page.** One generated block, "Against simple estimates" (`BaselinesBlock`, its words in
`baseline-texts.json`): what each estimate is, each experiment's latest run with its measured
knee and the simulator's and each estimate's knee and error, and the median absolute error of
each over the held-out and over the tuned-on experiments. Under it the block says when and by
which engine the estimates were computed, when their rules were fixed and which runs shown were
calibrated after that day, and quotes the engine's estimate where it calls a design overloaded at
its knee. `accuracy.json` and the web app are unchanged.

**Status (19 Nov 2026):**
[ADR-0089](0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md) (proposed, engine
1.5.1) takes out of the estimator the term the last-but-one consequence below describes, and
`baselines` wrote again the 17 files whose figures moved. The figures below are the estimate's
before that change. After it the analytic estimate's knee is the service's core on the three
experiments the term had set: go-mixed 9,900 req/s (+66%), where it was 8,650 (+45%); java-mixed
7,900, where it was 7,850; pg-hot-row 3,000 (+217%), where it was 1,712 (+81%), so the two
estimates that do not know of the row's lock are +217% and +299%. Its Kafka note names the
consumers falling behind at 1,959 req/s, not PostgreSQL. Its medians did not move (30% held out,
16% tuned on), and its knee is within 2% of the simulator's on eight experiments.

### Consequences

- Good, because the page now answers the review's question, and the answer is the unwelcome
  one: on the nine experiments the simulator's knee is the bottleneck arithmetic's, within two
  points of error on each latest run (median absolute error 32% against 32% held out, 14%
  against 14% tuned on) and within 3% of the knee the simulator searched in 65 of the 67 kept
  runs. The engine's analytic estimate is at 30% and 16%, the utilisation line at 34% and 17%.
- Good, because the baselines show where knowing the design matters: on pg-hot-row the two
  estimates that do not know of the row's lock are +81% and +299%.
- Good, because every figure can be recomputed by hand from `calibration.json`, and
  `CommittedBaselinesTests` recomputes all of them.
- Bad, because for the 67 runs kept on 19 Nov 2026 the baselines are not predictions. The
  simulator's knees were sealed before each run was measured; the baselines' rules were written
  with those results in view (which limits count, that work behind a queue is left out, the
  miss share of the top pass). The file and the page say so, and the rules are simple enough to
  check, but such a baseline's error is not evidence of the same kind as a prediction's. For a
  run calibrated later the rules predate the measurement; its estimates are still computed
  after the run and are not sealed before it.
- Bad, because the analytic estimate is today's engine's (1.5.0) on designs the frozen engine
  0.16.0 predicted, and it moves when the estimator does: a change to it means running
  `baselines` again, which the committed files' test will ask for.
- Bad, because the analytic estimate's knee is set, in three of the nine experiments, by a term
  the calibration does not support: the estimator counts a commit's flush as PostgreSQL CPU
  (`PartShapes`), which fills the database's cores at 8,676 req/s on go-mixed, at 7,897 on
  java-mixed (6 req/s under the service's core, so its agreement with the simulator there is a
  coincidence) and at 1,716 on pg-hot-row (exactly twice the lock's 858; without the term its
  +81% would be the service's core, +224%). The same term is the overload its Kafka note
  quotes. This decision reports the estimate as the engine gives it and leaves the estimator
  alone; the term deserves an issue of its own.
- Bad, because nine experiments with one limit each cannot show what a simulation adds where
  limits interact; an experiment built so that the arithmetic and the simulator disagree is
  still to be run.

### Confirmation

- `BottleneckArithmeticTests`, `AnalyticEstimateTests`, `UtilisationExtrapolationTests`,
  `StraightLineTests`, `CacheMissesTests` and `ContainerCpusTests` cover each estimate on small
  hand-made inputs; `EstimatesTests` the engine's estimate of a kept run's design.
- `BaselinesTests` checks kept runs' figures against the same arithmetic done apart from the
  harness; `BaselineFilesTests` and `BaselinesCommandTests` the files and the command, a second
  run that writes nothing and recorded files that do not change.
- `CommittedBaselinesTests` writes every kept run's committed `baselines.json` again, byte for
  byte under the file's own stamp.
- `BaselinesBlockTests` covers the block; `ReportParityTests` regenerates the page.

## Pros and Cons of the Options

### (b) Bottleneck arithmetic only

- Good, because it is the baseline the review asked about, and the simplest.
- Bad, because it reads the same calibrated inputs as the simulator, so it cannot tell an error
  in the inputs from an error in the model; the utilisation line reads the measured CPU instead.
  It would also leave out the engine's own cheaper answer, which a user sees before a run.

### (c) A Universal Scalability Law fit

- Good, because it is the usual name for a model-free capacity estimate.
- Bad, because it would be the name without the method. The law is fitted to throughput against
  concurrency up to near saturation, where contention and coherency bend the curve. The
  calibration staircase is open-loop, at a few rates, and stops at about two thirds of the
  service's core by design: throughput equals the offered rate at every pass, so the fit's two
  coefficients are not identified. What the staircase does give is CPU against rate, which a
  straight line describes; the baseline is named for that.

### (d) Compute the baselines inside `report`

- Good, because there is no file to keep in step with the results.
- Bad, because the page would show figures no file records, with no provenance, and the report
  would have to run the engine's estimate, where today it only reads files.

### (e) Add the baselines to `comparison.json`

- Good, because one file would hold a run's whole comparison.
- Bad, because `comparison.json` is recomputed from `predictions.json` and `measured.json` alone
  and checked byte for byte against what the Python harness wrote
  ([ADR-0070](0070-write-the-harnesss-files-as-python-wrote-them.md)); the baselines read
  neither file.

## More Information

[docs/validation.md](../validation.md), "Against simple estimates" and step 6 of the protocol;
[archsim-benchmarks/README.md](../../archsim-benchmarks/README.md) for the command and the
`baselines` package. `resources-predicted.json`
(`LaterResources`) is the earlier derived file written beside a run's results without touching
them.
