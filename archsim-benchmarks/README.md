# archsim-benchmarks

The accuracy benchmarks' harness: it calibrates a stack at low load, predicts it with the engine,
measures it with k6, compares, and writes the validation page from the results
([`docs/validation.md`](../docs/validation.md) describes the protocol). It is the Java port of the
Python harness (`benchmarks/run.py` and `benchmarks/report.py`), stage 7 of the
[Java port](../docs/proposals/java-25-port.md). It is not shipped.

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
| `report [--json] [--dev]` | rewrites `docs/validation.md`'s generated blocks and its charts; `--json` also `docs/validation/accuracy.json` and the web app's copy; `--dev` also `docs/validation/dev-checks.md` and `knee-range.md` | `report.py` |

## Packages

| Package | What it holds | Python it ports |
| --- | --- | --- |
| `io.archsim.benchmarks` | the command line (`Benchmarks`, one class per command), the repository's layout, the log | `run.py`'s `main`, `log` |
| `experiments` | the experiments, stacks and service runtimes (`experiments.json`), the objective, the protocol's numbers, each container's model node | `EXPERIMENTS`, `STACKS`, `RUNTIMES`, `CONTAINER_NODES`, the constants |
| `results` | the kept runs and the results' JSON files | `result_dirs`, `read_json`, `write_json` |
| `comparison` | a step from its repeats (`StepAggregation`, `RepeatResources`), the knees (`Knees`), `comparison.json` (`Comparison`, `ResourceComparison`, `RelativeError`), `compare` (`Recomparison`) | `aggregate`, `failed_repeat`, `repeat_value`, `run_resources`, `aggregate_resources`, `grid_knee`, `meets`, `at_or_below_knee`, `at_or_below_both_knees`, `in_range`, `compare`, `resource_rows`, `resources_below_knee`, `err`, `measured_steps`, `comparison_of`, `recompare` |
| `values` | Python's arithmetic on JSON values: `+`, `-`, `*`, `/`, `abs`, `round`, `min`, `max`, `sum` (3.12's compensated), `statistics.median`, `dict.get` | the semantics `run.py` relied on |
| `values` (`PythonStatistics`) | `statistics.NormalDist().inv_cdf`, `math.comb` | the statistics `report.py` and `run.py` used |
| `docker` | each stack's containers and k6's CPUs (`stacks.json`), the cpusets `compose.yaml` declares | `STACK_SERVICES`, `K6_CPUS`, `compose_cpusets`, `cpusets`, `cpu_count` |
| `report` | the validation page's blocks (`ValidationPage`, `SummaryBlocks`, `TiersBlock`, `ResourcesBlock`, `EnvironmentBlock`, `ExperimentBlock`, `LadderTable`), the charts (`Chart`, `ChartPanel`, `ValueAxis`), `accuracy.json` (`AccuracyData`, `AccuracyExperiment`, `AccuracySteps`), the run history and criteria (`RunHistory`, `Tiers`, `Repeatability`, `KneeSpread`), the Java engine's sentence (`JavaEngineCheck`), `dev-checks.md` (`DevChecksPage`), `knee-range.md` (`KneeRangePage`), the page's words (`PageTexts`, `texts.json`) and numbers (`Figures`) | all of `report.py` |

## Tests

```bash
./gradlew :archsim-benchmarks:test
```

They are ported from `benchmarks/tests`, one Java test per Python assertion group, and the parity
tests read the committed results: `CommittedComparisonsTests` recomputes all 67 kept runs'
comparisons byte for byte, and `ReportParityTests` regenerates `docs/validation.md`, its nine
charts, both `accuracy.json` and both development pages.

## What the port changed in the files it writes

Only where a file names the command that writes it
([ADR-0070](../docs/adr/0070-write-the-harnesss-files-as-python-wrote-them.md)), in the commit
that added `report`:

- `accuracy.json` (both copies): `generatedBy` is
  `./gradlew :archsim-benchmarks:run --args="report --json", from benchmarks/results`;
- `dev-checks.md` and `knee-range.md`: their opening paragraph names the Gradle command that
  writes them, and "today's harness" where it said "today's `run.py`". The commands that write
  their input (`run.py resimulate`, `run.py range-fit`) stay Python's until their Gradle
  successors land.

On the committed results everything else, the validation page and its charts included, is the
Python harness's output to the byte, as `ReportParityTests` checks. Words that only other results
bring out keep Python's too, and their tests pin them: an experiment without results still says
to run `benchmarks/run.py`, and runs that predate recording their cpusets name it for the
runner's defaults, until the Gradle harness runs the experiments itself.
