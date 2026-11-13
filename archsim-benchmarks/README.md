# archsim-benchmarks

The accuracy benchmarks' harness: it calibrates a stack at low load, predicts it with the engine,
measures it with k6, compares, and writes the validation page from the results
([`docs/validation.md`](../docs/validation.md) describes the protocol). It is the Java port of the
Python harness (`benchmarks/run.py`, `benchmarks/report.py` and `scripts/java_parity_report.py`),
stage 7 of the [Java port](../docs/proposals/java-25-port.md). It is not shipped.

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
| `resimulate [--only <experiment>] [--label <label>]` | checks today's model against every kept run, in-sample: its inputs derived and anchored again, its measured steps and its knee simulated, its comparison; writes `results/dev/<engine or label>/` (no Docker). It does not yet add the host machine `BENCH_HOST=on` gives the designs: that comes with `envcheck` and `contention` | `run.py resimulate` |
| `report [--json] [--dev]` | rewrites `docs/validation.md`'s generated blocks and its charts; `--json` also `docs/validation/accuracy.json` and the web app's copy; `--dev` also `docs/validation/dev-checks.md` and `knee-range.md` | `report.py` |
| `java-parity [--check]` | writes `docs/validation/java-parity.md`, stage 4's parity page, from `simulation-parity.json`, both engines' development checks and their speed; `--check` fails when the page is stale | `scripts/java_parity_report.py` |
| `tree-ids --before <clone> --commit-map <file>` | writes `benchmarks/results/tree-ids.tsv`, today's tree of each engine tree `engines.json` records, through a clone of the history before a rewrite and its commit map | none: new for the 13 Nov rewrite |

## Packages

| Package | What it holds | Python it ports |
| --- | --- | --- |
| `io.archsim.benchmarks` | the command line (`Benchmarks`, one class per command), the repository's layout, the log | `run.py`'s `main`, `log` |
| `experiments` | the experiments, stacks and service runtimes (`experiments.json`), the objective and whether a step met it (`Objective`), the protocol's numbers, each container's model node | `EXPERIMENTS`, `STACKS`, `RUNTIMES`, `CONTAINER_NODES`, `meets`, the constants |
| `results` | the kept runs and the results' JSON files; each kept calibration's knee on the frozen and the Java engine (`KneePairs`) | `result_dirs`, `read_json`, `write_json`, `java_parity_report.py`'s `knees` and `_same_knee` |
| `comparison` | a step from its repeats (`StepAggregation`, `RepeatResources`), the knees (`Knees`), `comparison.json` (`Comparison`, `ResourceComparison`, `RelativeError`), `compare` (`Recomparison`) | `aggregate`, `failed_repeat`, `repeat_value`, `run_resources`, `aggregate_resources`, `grid_knee`, `at_or_below_knee`, `at_or_below_both_knees`, `in_range`, `compare`, `resource_rows`, `resources_below_knee`, `err`, `measured_steps`, `comparison_of`, `recompare` |
| `values` | Python's arithmetic on JSON values: `+`, `-`, `*`, `/`, `abs`, `round`, `min`, `max`, `sum` (3.12's compensated), `==` (`equal`), `statistics.median`, `dict.get` | the semantics `run.py` relied on |
| `values` (`PythonStatistics`) | `statistics.NormalDist().inv_cdf`, `math.comb` | the statistics `report.py` and `run.py` used |
| `inputs` | the model inputs from a calibration's passes, one class per part (`ServiceInputs` with `GcInputs`, `LoadCurveInput` and `ChainInputs`; `QueueInputs`; `DatabaseInputs` with `PostgresCpu`, `QueryPasses`, `ServerTimes`, `CommitFlush` and `FlushFit`; `CacheInputs`; `MemoryInputs`), anchored at the top pass (`Anchoring`), the median of the replicates (`MedianInputs`), a run's inputs derived again (`ModelInputs`), the lognormals (`ModelDistributions`), a trial's GC for runs whose service reported none (`TrialGc`) | `model_inputs`, `service_inputs`, `queue_inputs`, `database_inputs`, `cache_inputs`, `memory_inputs`, `anchor`, `anchor_spread`, `median_inputs`, `rederive_inputs`, `trial_gc`, `with_trial_gc`, `fit_flush`, `commit_flush`, `server_time`, `link_leg`, `lognormal`, `lognormal_with_mean` and the rest of `run.py`'s calibration-to-inputs functions but three it has yet to take: `machine_inputs` (the host machine `BENCH_HOST=on` adds, with `envcheck` and `contention`), and `staircase_cpu` and `service_cpus` with the `log` form of `CURVE_FORM` (`extend_load_curve`, off by default; with the calibration staircase). Until then the inputs are those of their defaults: no host machine, a flat curve above the staircase |
| `designs` | an experiment's design at a load (`Designs`, `Nodes`, `Edge`), PostgreSQL alone (`DatabaseAlone`), simulations on the engine in process and what the harness reads from them (`Simulations`), the knee search (`KneeSearch`) | `design`, `service_node`, `database_node`, `cache_node`, `queue_node`, `_edge`, `database_only`, `simulate`, `node_resources`, `predicted_knee` |
| `resimulation` | a kept run's development check (`DevCheck`), every kept run's (`Resimulation`), their folder (`DevLabels`), predictions' later resources (`LaterResources`) | `dev_check`, `dev_path`, `dev_label`, `resimulate`, `predict_resources` |
| `provenance` | what produced a check: the commit, the engine's and the harness's trees (`Provenance`), the engine's build that ran (`EngineBuild`), the machine (`MachineName`) | `provenance`, `java_build`, `machine_id`, `cpu_name`, `git` |
| `docker` | each stack's containers and k6's CPUs (`stacks.json`), the cpusets `compose.yaml` declares | `STACK_SERVICES`, `K6_CPUS`, `compose_cpusets`, `cpusets`, `cpu_count` |
| `report` | the validation page's blocks (`ValidationPage`, `SummaryBlocks`, `TiersBlock`, `ResourcesBlock`, `EnvironmentBlock`, `ExperimentBlock`, `LadderTable`), the charts (`Chart`, `ChartPanel`, `ValueAxis`), `accuracy.json` (`AccuracyData`, `AccuracyExperiment`, `AccuracySteps`), the run history and criteria (`RunHistory`, `Tiers`, `Repeatability`, `KneeSpread`), the Java engine's sentence (`JavaEngineCheck`), `dev-checks.md` (`DevChecksPage`), `knee-range.md` (`KneeRangePage`), the page's words (`PageTexts`, `texts.json`) and numbers (`Figures`) | all of `report.py` |
| `history` | commits, folder trees and changes through `git` (`GitRepository`), a rewrite's commit map (`CommitMap`), today's engine trees (`TreeIds`) | `git` |
| `parity` | stage 4's parity page (`JavaParity`, `JavaParityPage`, `ParityTables`, `ParityNumbers`), both engines' speed (`Speed`, `SpeedRows`) | the rest of `java_parity_report.py` |

## Tests

```bash
./gradlew :archsim-benchmarks:test
```

They are ported from `benchmarks/tests` and `scripts/tests/test_java_parity_report.py`, one Java
test per Python assertion group, and the parity
tests read the committed results: `CommittedComparisonsTests` recomputes all 67 kept runs'
comparisons byte for byte, `ReportParityTests` regenerates `docs/validation.md`, its nine
charts, both `accuracy.json` and both development pages, `CommittedParityPageTests`
regenerates `docs/validation/java-parity.md`, and `CommittedTreeIdsTests` checks
`benchmarks/results/tree-ids.tsv` against the engines' tags. The inputs' and the designs' tests
read kept runs too: each part's inputs from a run's passes (`RecordedPartsTests`), whole runs'
inputs derived again (`RecordedInputsTests`), steps and knees simulated (`RecordedStepsTests`)
and whole development checks (`DevCheckTests`), each against what the Python harness derived
with the Java engine before it was 1.0.0 (`results/dev/0.16.0-java/`), byte for byte.

Every kept run checked again takes several minutes, so a task of its own runs it (the tests
tagged `parity`): all 65 development checks against the recorded ones, the same inputs, knee,
steps and comparison but for what names a check.

```bash
./gradlew :archsim-benchmarks:parity
```

## What the port changed in the files it writes

Only where a file names the command that writes it
([ADR-0070](../docs/adr/0070-write-the-harnesss-files-as-python-wrote-them.md)), in the commit
that added the command:

- `accuracy.json` (both copies): `generatedBy` is
  `./gradlew :archsim-benchmarks:run --args="report --json", from benchmarks/results`;
- `dev-checks.md` and `knee-range.md`: their opening paragraph names the Gradle command that
  writes them, and "today's harness" where it said "today's `run.py`". `dev-checks.md` names
  `resimulate`'s Gradle command as the writer of its input; `knee-range.md` still names
  `run.py range-fit` until its Gradle successor lands;
- a development check (`results/dev/<engine>/`): its `how` derives the inputs "with today's
  harness", not "today's run.py"; its provenance's `harnessHash` is over `archsim-benchmarks`
  where it was over `benchmarks/run.py`, and its `javaBuild` hashes the jars of the engine and
  its model that run in the harness's process, where it hashed the installed command line's;
- `java-parity.md`: "Generated by" names `./gradlew :archsim-benchmarks:run --args="java-parity"`.

On the committed results everything else, the validation page and its charts included, is the
Python harness's output to the byte, as `ReportParityTests` checks. Words that only other results
bring out keep Python's too, and their tests pin them: an experiment without results still says
to run `benchmarks/run.py`, and runs that predate recording their cpusets name it for the
runner's defaults, until the Gradle harness runs the experiments itself.
