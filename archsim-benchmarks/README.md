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

## Packages

| Package | What it holds | Python it ports |
| --- | --- | --- |
| `io.archsim.benchmarks` | the command line (`Benchmarks`, one class per command), the repository's layout, the log | `run.py`'s `main`, `log` |
| `experiments` | the experiments, stacks and service runtimes (`experiments.json`), the objective, the protocol's numbers, each container's model node | `EXPERIMENTS`, `STACKS`, `RUNTIMES`, `CONTAINER_NODES`, the constants |
| `results` | the kept runs and the results' JSON files | `result_dirs`, `read_json`, `write_json` |
| `comparison` | a step from its repeats (`StepAggregation`, `RepeatResources`), the knees (`Knees`), `comparison.json` (`Comparison`, `ResourceComparison`, `RelativeError`), `compare` (`Recomparison`) | `aggregate`, `failed_repeat`, `repeat_value`, `run_resources`, `aggregate_resources`, `grid_knee`, `meets`, `at_or_below_knee`, `at_or_below_both_knees`, `in_range`, `compare`, `resource_rows`, `resources_below_knee`, `err`, `measured_steps`, `comparison_of`, `recompare` |
| `values` | Python's arithmetic on JSON values: `+`, `-`, `*`, `/`, `abs`, `round`, `min`, `max`, `statistics.median`, `dict.get` | the semantics `run.py` relied on |

## Tests

```bash
./gradlew :archsim-benchmarks:test
```

They are ported from `benchmarks/tests`, one Java test per Python assertion group, and the parity
tests read the committed results: `CommittedComparisonsTests` recomputes all 67 kept runs'
comparisons byte for byte.
