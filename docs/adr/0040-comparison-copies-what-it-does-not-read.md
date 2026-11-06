---
status: accepted
date: 2026-11-06
decision-makers: Majid Abarghooei (owner)
---

# 0040. A run comparison copies the report parts it shows and diffs the simulated inputs as stored

## Context and Problem Statement

`GET /compare` answers `compare_reports` of two finished runs
([proposal](../proposals/java-25-port.md#stage-3--the-engines-deterministic-parts-12-weeks)). Besides
the figures it compares, the answer carries three parts of each report whole: the run's
provenance, its first bottleneck finding and its time series (the web app shows them). Of these it
reads only the seed, the engine version, the result's digest and the bottleneck's part. Its input
diff flattens each run's simulated design (`input`) to paths and compares the values at each path,
whatever the component type or schema version, and writes them in its explanations as Python's
`str()` does (`True`, `None`, `['a']`).
[ADR-0039](0039-engine-reads-reports-as-partial-records.md) has the engine read a report's values
through records of the parts it reads, and
[ADR-0038](0038-engine-computes-on-typed-records.md) has it compute on a design's records. How
should the Java comparison handle what it copies and the inputs it diffs?

## Decision Drivers

- Golden-file parity: the same answer, word for word, for every recorded pair of runs.
- A value the engine compares or puts in words is read by name in one place, checked by the
  compiler (ADR-0039).
- A comparison of two stored runs must not depend on the design reading under today's schema: a
  run keeps the input it simulated, of the schema version it had.

## Considered Options

- Copy the shown parts and diff the inputs as the reports store them (JSON)
- Model every shown part as records, and diff the inputs as the model's records
- Leave the shown parts to the app, which adds them to the engine's answer

## Decision Outcome

Chosen option: "copy the shown parts and diff the inputs as the reports store them", because
what the comparison only passes on needs no type, and the input diff is about what was stored,
path by path.

- The figures a comparison compares or names are read through the report's records (ADR-0039):
  the summary's figures, each part's figures and status, the bottleneck finding's part, the
  seeds and engine versions.
- The provenance, the first bottleneck finding and the time series of each report are copied
  whole as the report stores them, as `JsonNode` (`ReportParts`); what the comparison reads of
  them, it reads through the records.
- The input diff (`InputDiff`) flattens each report's `input` JSON as Python does and compares the
  values at each path with Python's `==` (`2 == 2.0`, `True == 1`), so a value stored as `2.0`
  in one run and `2` in the other is no difference. Its values are passed on as stored.

### Consequences

- Good, because a comparison of runs whose designs were stored under an older schema still diffs
  what each run simulated, with no migration or defaults in between.
- Good, because the web app gets each report's parts unchanged, with no type to keep in step with
  the simulator's output.
- Good, because the stored inputs are compared with Python's `==` and written with its `str()`,
  so the input diff and its explanation read as they did.
- Bad, because the engine reads report JSON in a second way, beside the records: the input diff
  walks JSON trees, and a mistake in its paths is found by the golden parity tests, not by the
  compiler.
- Bad, because what the copied parts hold is not checked: whatever the report stores there is
  passed on. (A report without its provenance or time series fails to read, as ADR-0039's
  records require them; only a run without a bottleneck finding has none to copy.)

### Confirmation

`GoldenParityTests` runs `compare_reports` and `input_diff` on every recorded pair of runs, and
`ReportCasesTests` on cases that reach the branches the golden files do not (identical inputs,
other seeds or engines, a bottleneck that moved, went or came, values equal only as Python
compares them).

## Pros and Cons of the Options

### Model every shown part as records, and diff the inputs as the model's records

- Good, because every value would be typed.
- Bad, because the time series and a finding's evidence depend on the kind of part and finding,
  and nothing reads them.
- Bad, because reading a stored input with the model's codec applies today's defaults and
  validation: a run of an older design could fail to read, or differ in settings it never had.

### Leave the shown parts to the app, which adds them to the engine's answer

- Good, because the engine would only return what it computes.
- Bad, because `compare_reports` would be split between two modules, and its golden parity test
  would have to rebuild the app's half.

## More Information

- [ADR-0039](0039-engine-reads-reports-as-partial-records.md): the engine reads reports as records.
- [ADR-0038](0038-engine-computes-on-typed-records.md): the engine computes on records.
