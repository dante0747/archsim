# archsim-benchmarks

The accuracy benchmarks' harness: it calibrates a stack at low load, predicts it with the engine,
measures it with k6, compares, and writes the validation page from the results
([`docs/validation.md`](../docs/validation.md) describes the protocol). It is the Java port of the
Python harness (`benchmarks/run.py`, `benchmarks/report.py` and `scripts/java_parity_report.py`),
stage 7 of the [Java port](../docs/proposals/java-25-port.md), whose last slice deleted the Python
(the tag `cpython-recorders` keeps it). It is not shipped.

- **Code** here; **data** in [`benchmarks/`](../benchmarks): the stacks, k6's script,
  `compose.yaml`, `engines.json`, `knee-range.json` and the recorded `results/`, which are never
  edited ([ADR-0068](../docs/adr/0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md)).
- **Run** from the repository's root
  ([ADR-0069](../docs/adr/0069-run-the-harness-from-gradle-with-picocli-commands.md)):

  ```bash
  ./gradlew :archsim-benchmarks:run --args="compare"
  ```

- **Bytes:** every file is written as the Python harness wrote it: JSON values kept as Jackson
  trees with Python's arithmetic on them (an integer stays an integer), Python's number formats
  and `json.dumps` ([ADR-0070](../docs/adr/0070-write-the-harnesss-files-as-python-wrote-them.md)),
  and Python's statistics ([ADR-0071](../docs/adr/0071-python-statistics-in-the-harness.md)). The
  parity tests regenerate the committed files from `benchmarks/results` and compare their bytes.

## Commands

| Command | What it does | Python |
| --- | --- | --- |
| `compare` | recomputes every kept run's `comparison.json` from its `predictions.json` and `measured.json` (no Docker) | `run.py compare` |
| `resources` | predicts CPU and memory for each experiment's latest results whose predictions have none (`resources-predicted.json`), then `compare` (no Docker) | `run.py resources` |
| `baselines [--rewrite]` | estimates every kept run's knee from its calibration alone, three simple ways, to set beside the simulator's prediction: bottleneck arithmetic on the calibrated inputs, the engine's own instant estimate, and a straight line through the staircase's measured CPU ([ADR-0087](../docs/adr/0087-compare-the-simulator-with-simple-estimates.md)). It writes `baselines.json` beside each run's `comparison.json` where there is none or today's harness and engine give other figures, and leaves a file whose figures are today's as it is; `--rewrite` writes every file again, stamped anew. It reads no measured step and changes no recorded file (no Docker). Run it before `report` after new results | none: new on 19 Nov 2026 |
| `resimulate [--only <experiment>] [--label <label>]` | checks today's model against every kept run, in-sample: its inputs derived and anchored again, its measured steps and its knee simulated, its comparison; writes `results/dev/<engine or label>/` (no Docker). With `BENCH_HOST=on` the designs run on this machine as its latest quiet contention check fitted it, or as the fit `BENCH_HOST_FIT` names (`machine`); `range-fit` and a calibration derive their inputs the same way | `run.py resimulate` |
| `range-fit [--reuse-dev <label>]` | fits the predicted range of the knee from the in-sample runs and checks its coverage, in-sample and leaving out each run and each experiment; writes `benchmarks/knee-range.json`. The kept runs' inputs and knees come from the engine's development checks, or the label's (only when nothing but the label changed since); the rest is derived and simulated, its finished work kept in a cache in the system's temporary folder (no Docker) | `run.py range-fit` |
| `smoke <experiment> [--keep]` | starts the experiment's stack afresh, sends its traffic at 20 req/s for 10 s and checks that every request was sent, succeeded and was counted by the service, PostgreSQL, the cache, Redis or the producer; keeps nothing (k6's summary is deleted) and stops the stack unless `--keep`. The one command that starts a sealed experiment's stack (Docker) | `run.py smoke` |
| `envcheck` | characterises this machine on pg-mixed's workload: which CPUs share a physical core, how much CPU Docker's VM loses to steal, and the service's and PostgreSQL's CPU per request and the latency at six rates, with k6 on the runner's CPUs and again off the service's physical core; writes `results/envcheck/<at>.json` (about 8 minutes of Docker) | `run.py envcheck` |
| `contention [--probe-only \| --service-only]` | how this machine turns CPUs into capacity: pg-mixed's service at rest, as benchmarked up a ladder of rates and with background work, with k6's own CPU; then a fixed unit of V8 work on one CPU alone and beside busy siblings, cores and itself, while Windows' counters give the cores' frequency and each logical processor's busy share; writes `results/envcheck/<at>-contention.json` (about 15 minutes of Docker; `--service-only` about 4) | `run.py contention` |
| `<stack> [--only <experiment>] [--archive] [--runs <n>] [--keep]`, `all` | runs a stack's experiments, or every stack's (`postgres`, `cache`, `kafka`, `java-mixed`, `go-mixed`, `python-mixed`, `chain`, `cache-ttl`): each stack started afresh, each experiment calibrated (protocol v7), predicted (the knee, its range, the ladder), measured (the ladder, then steps between the two either side of the knee until they are within 3%, each predicted and sealed first) and compared, its files written under `benchmarks/results/<experiment>/`. A held-out experiment runs only on a frozen engine with nothing uncommitted: asked for by its stack it stops the command before Docker starts, and `all` leaves it out. `--archive` first moves the latest results into the next `run-N/`; `--runs` rotates independent runs through the experiments (Docker) | `run.py <stack>`, `run.py all` |
| `report [--json] [--dev]` | rewrites `docs/validation.md`'s generated blocks and its charts; `--json` also `docs/validation/accuracy.json` and the web app's copy; `--dev` also `docs/validation/dev-checks.md` and `knee-range.md` | `report.py` |
| `java-parity [--check]` | writes `docs/validation/java-parity.md`, stage 4's parity page, from `simulation-parity.json`, both engines' development checks and their speed; `--check` fails when the page is stale | `scripts/java_parity_report.py` |
| `tree-ids --before <clone> --commit-map <file>` | writes `benchmarks/results/tree-ids.tsv`, today's tree of each engine tree `engines.json` records, through a clone of the history before a rewrite and its commit map | none: new for the 13 Nov rewrite |

## Packages

| Package | What it holds | Python it ports |
| --- | --- | --- |
| `io.archsim.benchmarks` | the command line (`Benchmarks`, one class per command), what the commands work with (`HarnessSetup`: the repository's layout, the log, the environment, Docker, the services and the clock, which tests replace, and the model inputs' derivation, on the host machine `BENCH_HOST=on` asks for), what every command keeps to (`HarnessRules`: `run.py`'s commands refuse a `BENCH_ENGINE` other than `java`, a stop prints its reason), a stop with its reason (`HarnessStopException`), a command's files and its end (`ScratchFolder`, `Processes`, which git's commands use too) | `run.py`'s `main`, `log`, `ENGINE_VARIABLE`, its `SystemExit`s |
| `experiments` | the experiments, stacks and service runtimes (`experiments.json`), the objective and whether a step met it (`Objective`), the protocol's numbers (`Protocol`, k6's and the model's request timeout among them), each container's model node, the service that calls PostgreSQL (`Experiment.postgresClient`) | `EXPERIMENTS`, `STACKS`, `RUNTIMES`, `CONTAINER_NODES`, `meets`, `db_client`, the constants |
| `results` | the kept runs and the results' JSON files; each kept calibration's knee on the frozen and the Java engine (`KneePairs`) | `result_dirs`, `read_json`, `write_json`, `java_parity_report.py`'s `knees` and `_same_knee` |
| `comparison` | a step from its repeats (`StepAggregation`, `RepeatResources`), the knees (`Knees`), `comparison.json` (`Comparison`, `ResourceComparison`, `RelativeError`), `compare` (`Recomparison`) | `aggregate`, `failed_repeat`, `repeat_value`, `run_resources`, `aggregate_resources`, `grid_knee`, `at_or_below_knee`, `at_or_below_both_knees`, `in_range`, `compare`, `resource_rows`, `resources_below_knee`, `err`, `measured_steps`, `comparison_of`, `recompare` |
| `values` | Python's arithmetic on JSON values: `+`, `-`, `*`, `/`, `abs`, `round`, `min`, `max`, `sum` (3.12's compensated), `==` (`equal`), `statistics.median`, `dict.get` | the semantics `run.py` relied on |
| `values` (`PythonStatistics`) | `statistics.NormalDist().inv_cdf`, `math.comb`, `math.fsum` (Shewchuk's exact partials, as CPython keeps them), `statistics.fmean` | the statistics `report.py` and `run.py` used |
| `inputs` | the model inputs from a calibration's passes, one class per part (`ServiceInputs` with `GcInputs`, `LoadCurveInput` (its form above the staircase, `CurveForm`) and `ChainInputs`; `QueueInputs`; `DatabaseInputs` with `PostgresCpu`, `QueryPasses`, `ServerTimes`, `CommitFlush` and `FlushFit`; `CacheInputs`; `MemoryInputs`; with a host machine, the design's host and the CPU-time inputs at full speed: `MachineInputs`), anchored at the top pass (`Anchoring`), the median of the replicates (`MedianInputs`), each staircase rate's CPU per request (`CpuByLoad`), a run's inputs derived again (`ModelInputs`), the lognormals (`ModelDistributions`), a trial's GC for runs whose service reported none (`TrialGc`) | `model_inputs`, `service_inputs`, `queue_inputs`, `database_inputs`, `cache_inputs`, `memory_inputs`, `anchor`, `anchor_spread`, `median_inputs`, `cpu_by_load`, `net_cpu_ms`, `rederive_inputs`, `trial_gc`, `with_trial_gc`, `fit_flush`, `commit_flush`, `server_time`, `link_leg`, `lognormal`, `lognormal_with_mean`, `service_time_by_load` with `staircase_cpu` and `CURVE_FORM` (its `log` form through the engine's `extend_load_curve`; the derivation keeps the curve flat above the staircase and passes neither the staircase's passes nor the service's CPUs, which the log form needs: `CurveForm` says where they would come from), `machine_inputs` with `pass_speed`, `scale_value` and `MACHINE_SCALED`, and the rest of `run.py`'s calibration-to-inputs functions |
| `machine` | this machine's checks: the environment check (`EnvironmentCheck`, the VM's CPU times `CpuTimes`) and the contention check (`ContentionCheck` with its conditions `ContentionConditions`, the service's steps `ServiceSteps`, the probe's conditions `ProbeConditions`, the probe containers `Probes`, Windows' counters `HostCounters` and their means `HostSamples`), what they run with (`CheckTools`, `ContentionTools`); the contention checks kept (`ContentionChecks`), the thread model fitted to one (`ContentionFit`, `MachineFit`: physical cores, the all-core clock, a thread sharing its core, the background at rest, k6's CPU per request), and whether the benchmark designs run on it (`HostSwitch`: `BENCH_HOST=on`, a what-if fit `BENCH_HOST_FIT` names, `BENCH_HOST_CLOCK`) | `envcheck`, `sweep_step`, `vm_cpu_times`, `ENVCHECK_RATES`, `TIMER_TICK_MS`, `contention`, `contention_step`, `K6Cpu`, `probe_condition`, `probe_launch`, `probe_collect`, `probes_clear`, `probe_rates`, `vm_clock_offset_ms`, `HostCounters`, `PROBE_JS`, `PROBE_CONDITIONS`, `SERVICE_CONDITIONS`, `CONTENTION_LADDER`, `CONTENTION_QUICK_LADDER`, `CONTENTION_RATES`, `host_checks`, `host_fit`, `machine_fit`, `fitted_machine`, `HOST_QUIET_THREADS`, `HOST_FIT_MAX_BUSY`, `HOST_SWITCH` |
| `designs` | an experiment's design at a load (`Designs`, `Nodes`, `Edge`), PostgreSQL alone (`DatabaseAlone`), simulations on the engine in process and what the harness reads from them (`Simulations`), the engine's instant estimates of a design, which no simulation runs for (`Estimates`), the knee search (`KneeSearch`) | `design`, `service_node`, `database_node`, `cache_node`, `queue_node`, `_edge`, `database_only`, `simulate`, `node_resources`, `predicted_knee`; `Estimates` is new |
| `resimulation` | a kept run's development check (`DevCheck`), every kept run's (`Resimulation`), their folder (`DevLabels`), predictions' later resources (`LaterResources`) | `dev_check`, `dev_path`, `dev_label`, `resimulate`, `predict_resources` |
| `baselines` | simple estimates of a kept run's knee from its calibration alone (ADR-0087): bottleneck arithmetic (`BottleneckArithmetic`: each part's limits `PartLimits` and `Limit`, what one request sends each part and takes of it `RequestLoads` and `PartTimes`, the share of a cache's lookups that missed in the top pass `CacheMisses`), the engine's analytic estimate (`AnalyticEstimate`, on `designs`' `Estimates`), utilisation extrapolation (`UtilisationExtrapolation`, the line `StraightLine`, each container's CPUs `ContainerCpus`), a run's `baselines.json` with the day its rules were fixed (`Baselines`), the kept runs' files (`BaselineFiles`) and the figures' units and precision (`Units`) | none: new on 19 Nov 2026 |
| `range` | the predicted range of the knee (W28): the inputs it varies (`RangeInputs`), the knee's sensitivity to each (`Sensitivity`), their run-to-run variation (`VariationFit`), the model's own error (`ModelError`), the range around a knee (`KneeRanges`), its fit and check (`RangeCheck`), the calibrations it is fitted on (`RangeSources`), `range-fit` (`RangeFit`, `FitDocument`, `RangeCache`) | `RANGE_INPUTS`, `RANGE_FIXED`, `input_scale`, `scaled`, `perturbed`, `range_keys`, `knee_near`, `sensitivity_jobs`, `sensitivity_of`, `knee_sensitivity`, `input_sigma`, `calibration_sigma`, `knee_range_of`, `variation_fit`, `model_sigma`, `fold_fit`, `range_check`, `protocol_number`, `conditions`, `range_sources`, `today_inputs`, `range_cache_path`, `run_cached`, `range_fit` |
| `provenance` | what produced a check: the commit, the engine's and the harness's trees (`Provenance`), the engine's build that ran (`EngineBuild`), the machine (`MachineName`) | `provenance`, `java_build`, `machine_id`, `cpu_name`, `git` |
| `docker` | each stack's containers, the stacks on which every request calls PostgreSQL, the containers that work behind a queue and k6's CPUs (`stacks.json`), the cpusets `compose.yaml` declares and how they fall on physical cores (`CpuTopology`); `docker compose` and `docker` (`Compose`) through a runner of commands (`ProcessRunner`, `SystemProcesses`: output to files, never pipes, and a shell's script as input rather than an argument), the containers' cgroup counters (`Cgroups`, `CgroupReading`) and their CPU and memory through a measured window (`ResourceSampler`, `WindowResources`), which is counted from the service's first counted request (`LoadStart`), the services' endpoints (`ServiceHttp`, `HttpServices`: HTTP/1.1, 30 s), the clock (`Ticker`), starting and stopping a stack (`StackLifecycle`) | `STACK_SERVICES`, `K6_CPUS`, `DB_STACKS`, `compose_cpusets`, `cpusets`, `cpu_count`, `cpu_list`, `cpu_siblings`, `core_of`, `topology`, `compose`, `container`, `cpu_usec`, `cpu_ms`, `read_cgroup`, `reading`, `parse_cgroup`, `cgroup_dir`, `window_resources`, `ResourceSampler`, `http_json`, `wait_http`, `stack_up`, `stack_down`, `stack_items` |
| `load` | k6 in its container (`K6`, with the traffic it sends and the virtual users it holds ready: `Traffic`), the window it measured (`K6Measured`), k6's own CPU through it, counted from the load's start (`K6Cpu`) | `k6`, `k6_measured`, `target_for`, `K6Cpu` |
| `probes` | what PostgreSQL did during a calibration pass: its statistics and its processes' CPU before and after (`PostgresProbe`, `PostgresSnapshot`, `PostgresProcesses`, `PostgresWindow`), and Redis's command statistics (`RedisStats`) | `PG_STATS_SQL`, `PG_PROCESSES_SH`, `pg_process_kind`, `pg_processes`, `pg_snapshot`, `pg_begin`, `pg_end`, `pg_window`, `statement_kind`, `redis_cmdstats` |
| `calibration` | the calibration on a running stack (protocol v7, which calibrates as v6 did): its passes (`DockerPasses`: settling (`Settling`, which the machine's checks share), the rest, one pass of a mix at a rate), the staircase's rule (`Staircase`), a disturbed pass (`Disturbance`), the staircase up, down and at its top with its replicate sets (`Calibration`, `Calibrations`) | `settle`, `idle_pass`, `calibration_pass`, `steady_pass`, `disturbed`, `hops`, `capacity_bound`, `cpu_bound`, `other_bound`, `service_cpus`, `next_rise`, `staircase_rule`, `calibrate` |
| `campaign` | a stack's smoke test (`Smoke`, `SmokeProblems`); an experiment's predictions (`Prediction`), measurement (`Measurement`), the knee at other seeds (`SeedKnees`), run with its seals (`ExperimentRun`, on `ExperimentSteps`: `DockerSteps` on a stack), where a run measures next to close in on its knee (`KneeBracket`), the held-out experiments' seal (`Seals`), the latest results kept (`Archive`), the machine as `environment.json` records it (`MachineEnvironment`), a campaign over stacks (`Campaign`, `CampaignTools`, `CampaignMachine`) | `smoke`, `smoke_problems`, `predict`, `measure_once`, `drain`, `measure_step`, `run_experiment`, `seal`, `require_unsealed`, `frozen_engines`, `current_tree`, `archive`, `save`, `environment`, `power_mode`, `exp_image`, `runnable`, `main`'s stack loop |
| `report` | the validation page's blocks (`ValidationPage`, `SummaryBlocks`, `TiersBlock`, `ResourcesBlock`, `EnvironmentBlock`, `ExperimentBlock`, `LadderTable`), the charts (`Chart`, `ChartPanel`, `ValueAxis`), `accuracy.json` (`AccuracyData`, `AccuracyExperiment`, `AccuracySteps`), the run history and criteria (`RunHistory`, `Tiers`, `Repeatability`, `KneeSpread`), the Java engine's sentence (`JavaEngineCheck`), `dev-checks.md` (`DevChecksPage`), `knee-range.md` (`KneeRangePage`), the page's words (`PageTexts`, `texts.json`) and numbers (`Figures`); the simulator beside the simple estimates (`BaselinesBlock`, its words `BaselineTexts`, `baseline-texts.json`) | all of `report.py`; the baselines' block is new |
| `history` | commits, folder trees and changes through `git` (`GitRepository`), a rewrite's commit map (`CommitMap`), today's engine trees (`TreeIds`) | `git` |
| `parity` | stage 4's parity page (`JavaParity`, `JavaParityPage`, `ParityTables`, `ParityNumbers`), both engines' speed (`Speed`, `SpeedRows`) | the rest of `java_parity_report.py` |

## Tests

```bash
./gradlew :archsim-benchmarks:test
```

They are ported from the Python harness's `benchmarks/tests` and
`scripts/tests/test_java_parity_report.py`, one Java test per Python assertion group, and the parity
tests read the committed results: `CommittedComparisonsTests` recomputes all 67 kept runs'
comparisons byte for byte, `CommittedBaselinesTests` writes all 67 `baselines.json` again, byte
for byte under each file's own stamp, `ReportParityTests` regenerates `docs/validation.md`, its nine
charts, both `accuracy.json` and both development pages, `CommittedParityPageTests`
regenerates `docs/validation/java-parity.md`, and `CommittedTreeIdsTests` checks
`benchmarks/results/tree-ids.tsv` against the engines' tags. The inputs' and the designs' tests
read kept runs too: each part's inputs from a run's passes (`RecordedPartsTests`), whole runs'
inputs derived again (`RecordedInputsTests`), steps and knees simulated (`RecordedStepsTests`)
and whole development checks (`DevCheckTests`), each against what the Python harness derived
with the Java engine before it was 1.0.0 (`results/dev/0.16.0-java/`), byte for byte.
`CommittedRangeFitTests` refits `benchmarks/knee-range.json` from what it records itself (the
calibrations' scales, each checked run's knees, sensitivity and the range its comparison
records): the whole file comes out byte for byte but for what names it. The file was fitted on
1 Nov 2026 by the Python harness on the frozen engine 0.16.0, whose knees and sensitivities and
that day's derivation of the inputs no harness of today reproduces, so that is the parity a
refit from the calibrations themselves can have. Where Java's arithmetic and CPython's on Windows
differ in a last bit (MSVC's `exp`, and its `pow` behind `x ** 2`, which the port writes as
`x * x`), no committed number reaches the difference; ADR-0071's status note lists each one
found, with its values.

Every kept run checked again takes several minutes, so a task of its own runs it (the tests
tagged `parity`): all 65 development checks against the recorded ones, the same inputs, knee,
steps and comparison but for what names a check, and W27's 26 checks on a host machine
(`HostParityTests`: with the trial's what-if fit, the same host and thread speed at the pass).

```bash
./gradlew :archsim-benchmarks:parity
```

## What the port changed in the files it writes

Where a file names the command that writes it, in the commit that added the command, and in two
fields a new run records (ADR-0070's proposed note of 13 Nov 2026,
[ADR-0070](../docs/adr/0070-write-the-harnesss-files-as-python-wrote-them.md)):

- `accuracy.json` (both copies): `generatedBy` is
  `./gradlew :archsim-benchmarks:run --args="report --json", from benchmarks/results`;
- `dev-checks.md` and `knee-range.md`: their opening paragraph names the Gradle command that
  writes them, and "today's harness" where it said "today's `run.py`"; each names the Gradle
  command that writes its input (`resimulate`, `range-fit`);
- `knee-range.json`: its `how` says "today's harness" where it said "today's run.py", and its
  provenance is a development check's (below);
- a development check (`results/dev/<engine>/`): its `how` derives the inputs "with today's
  harness", not "today's run.py"; its provenance's `harnessHash` is over `archsim-benchmarks`
  where it was over `benchmarks/run.py`, and its `javaBuild` hashes the jars of the engine and
  its model that run in the harness's process, where it hashed the installed command line's;
- `java-parity.md`: "Generated by" names `./gradlew :archsim-benchmarks:run --args="java-parity"`.
- `measured.json`: a ladder that climbs above its prediction records `extensionSeals`, one
  `{offeredRps, predictionsSha256}` per step above it, saved before that step is measured: each
  seals `predictions.json` as it was then, so every seal matches the bytes it sealed. run.py
  saved each extension into the sealed file after its one seal (cache-aside's runs 4 and 5);
  `predictionsSha256` still seals the predictions the ladder began with;
- `environment.json`: `java` (the harness's runtime) in place of `python`, which `accuracy.json`
  republishes as the run recorded it; `os` is Windows'
  `platform.platform()` as Python wrote it (`Windows-11-10.0.26300-SP0`), which the range fit
  compares runs by;
- the logs and the held-out seal's reason name the Gradle commands where run.py's named
  `run.py`.

Protocol v7
([ADR-0086](../docs/adr/0086-keep-the-load-open-loop-past-the-objective-and-close-in-on-the-measured-knee.md))
is the first change to how a run measures since the port, so a run from it on records more than
run.py's did: `calibration.json`'s `protocol` reads `v7`; each measured step has `generator`
(the virtual users k6 held ready) and `droppedRepeats`, and each repeat `k6Cores`;
`predictions.json` has `kneeBySeed`, the knee searched again at nine more seeds; a step the run
adds to close in on its knee is marked `refinement` in `predictions.json` and in `measured.json`
(the mark the comparison reads), sealed in `extensionSeals` and compared in `comparison.json`'s
`kneeSearchRows`, apart from the ladder's `rows`; a step above the ladder is marked `extension`
in `measured.json` too, as it was in `predictions.json`; and the validation page shows, for
every run, the share of each step's requests k6 dropped and names the steps either side of the
measured knee. Every k6 run holds the virtual users ready, the smoke test's and the machine's
checks' too.

Two commands reach their containers differently on Windows, with the same results:
`contention`'s probe gets its script through a variable in base64 (`node -e
(0,eval)(atob(process.env.PROBE_CODE))`, at global scope as `node -e` runs a script), where
run.py passed it as an argument, and PowerShell gets
the host counters' script as `-EncodedCommand`: Windows' command line cannot carry their quotes
intact as Java passes arguments. `contention --probe-only --service-only` is refused before Docker
starts, where run.py ran neither part and wrote an empty check. A probe's `docker wait` has no
time limit, where run.py gave it 120 s: the harness's processes have none, and a probe ends after
about 17 s.

On the committed results everything else, the validation page and its charts included, is the
Python harness's output to the byte, as `ReportParityTests` checks. Two sentences that only other
results bring out name the Java harness now that it runs the experiments: an experiment without
results says to run `./gradlew :archsim-benchmarks:run --args="<its stack>"`, and runs that
predate recording their cpusets name `compose.yaml` and the harness's `stacks.json` for the
runner's defaults.
