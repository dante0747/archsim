---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0039. The engine reads a run's report as records of the parts it reads

## Context and Problem Statement

The rest of stage 3's deterministic parts work on a simulation's report, not on a design:
judging a run against the design's objectives, summarizing a group of runs that differ only in
their seed, a load sweep's rows and verdict, whether a sampled run reached a steady state, the
formulas each part's figures come from, a run's findings and confidence, the cost with a run's
report, comparisons and `archsim check`
([proposal](../proposals/java-25-port.md#stage-3--the-engines-deterministic-parts-12-weeks)).
Until stage 4, the report comes from the Python simulator. Each run stores it as JSON
(`simulation_runs.report`) with its `reportVersion` (1.14 now), so older runs keep reports of
older versions. A report is large: time series for every part and link, sample traces, the
design it ran, its cost, review and estimate. The Python reads the few values each function
needs with `report["summary"]` and `summary.get("p95")`. [ADR-0038](0038-engine-computes-on-typed-records.md)
has the engine compute on records for a design, but says nothing about reports. How should the
Java engine read a report?

## Decision Drivers

- Golden-file parity: the same numbers and words for every recorded report.
- The engine should read a report's values by name in one place, checked by the compiler where
  the engine uses them, as it reads a design's settings
  ([ADR-0038](0038-engine-computes-on-typed-records.md)).
- Stored reports of older versions must still read: a value an older report does not have reads
  as missing, as Python's `get` reads it.
- Stage 4 writes reports from Java, so the reader should be a step towards the writer's types,
  not a second definition that has to be thrown away.

## Considered Options

- Records of the parts the engine reads, decoded by Jackson with unknown keys skipped
- A complete record model of the report, read with the model's codec
- Jackson trees, read as the Python reads its dicts

## Decision Outcome

Chosen option: "records of the parts the engine reads", because the engine then reads every
report value through a record the compiler checks it against, a report of an older version still
reads as long as it has the parts the records require, and decoding skips what no function reads,
such as the traces and most time series.

- `RunReport` and its parts (`RunSummary`, `ReportNode`, `ReportWindow` and so on, in
  `io.archsim.engine.report`) hold only what a ported function reads, under the report's own
  names. A port that needs another value adds it to the record.
- A value the Python indexes (`report["window"]`) is a primitive or a non-null component. A
  value it reads with `get` is `@Nullable`, null when the report omits it or writes `null`; a
  list it reads with `get` (the links, the breakdown of a request's time) is empty then.
  Keys that are data, such as a part's counters or the names of its time series, are maps.
- One reader decodes them: Jackson databind, which skips keys the records do not name, behind each
  record's own `read` (`RunReport.read`, `RunSummary.read`, `ReportNode.read`); the reader itself
  is not public, so it decodes report records only. The model's codec is not used, because it is
  strict where a design must be (it rejects unknown keys with pydantic's errors), and a report is
  the engine's own output, not input to validate.
- A function that takes part of a report in Python takes the same record in Java: the formulas
  of one part take a `ReportNode`, a verdict takes a `RunSummary`. The golden inputs decode
  into the records through the same `read` as the app's stored reports.
- Python's truthiness is written out where the Python relies on it: `summary.get("slots") and
  hold` treats a zero as missing, so the Java tests for a zero there too, and the tests pin
  each case.
- Results are records written by the model's `RecordCodec`, as in ADR-0038.
- A run's compact summary (the run row's flat `p50`, `p95` and `p99`, which the workers write
  for listings) is not a report. `RunFigures.read` reads either shape into the same figures, as
  the Python did.
- The objectives a run is judged by are read from the design stored with it as the Python read
  them, `objectives.get(...)`: a target the design leaves out is off (`StoredObjectives.read`).
  The model's codec would give it its default (300 ms, 0.999), and judge stored runs by targets
  their designs never set.
- A part's settings are read from the design the run simulated, as its type's record
  ([ADR-0038](0038-engine-computes-on-typed-records.md)), not from the copy each part of the
  report carries (`config`). That copy is the same validated settings written out, so the two
  agree, and the engine reads a part's settings in one way, typed, wherever it reads them. The
  same holds for what the report copies from the design: a run's confidence takes its parts'
  technologies and its links' names from the design, not from the report's
  `assumptions.technologies` and `input`.
- A finding's evidence, the figures it rests on by name, is a Jackson `ObjectNode`: which
  figures depend on the kind of finding, as a part's counters depend on its kind, and nothing in
  the engine reads them back. A finding copies its evidence, so it does not change once made.
- In stage 4 the kernel writes reports, and these records grow to cover what it writes.

### Consequences

- Good, because a report value the engine reads is a record component: renamed in the records,
  every reader stops compiling.
- Good, because a report of an older version reads as long as it has the parts the records
  require.
- Good, because decoding builds only the records' values; the rest of the report is skipped.
- Bad, because the engine decodes JSON in three ways: designs with the model's strict codec,
  reports with plain Jackson, and the numbers stored with a run (its compact summary, its
  objectives) by hand (`StoredNumbers`). Each is in one place. A stored value that is not a
  number reads as none there, a target as off, where the Python raised `TypeError` comparing it;
  the stored designs are validated, so none has one.
- Bad, because the records are partial until stage 4, and a reader of a record cannot tell a
  value the report lacks from one the records do not name yet.
- Bad, because the compiler checks the engine against the records, not the records against the
  report: since unknown keys are skipped, a component whose name differs from the report's key
  (a typo, or a rename) would read as null or zero without an error. A test catches it instead,
  `ReportRecordKeysTests` (see Confirmation), but against the frozen engine's reports only: once
  the Java engine writes reports in stage 4, the records must be checked against what it writes,
  or become its types.
- Bad, because `RunReport` requires every part some function indexes (its summary, parts,
  findings, provenance, time series and window), so a report without one fails every function
  that takes a `RunReport`, even one that does not read that part: judging a steady state reads
  neither the findings nor the provenance.
- Bad, because a number the Python indexes but the report lacks reads as zero, where the Python
  raises `KeyError`; one the report writes as `null` fails to decode (Jackson 3 refuses `null`
  for a primitive). The simulator always writes them; the non-null objects are checked.
- Bad, because a finding's evidence is not typed: the compiler does not check the names of its
  figures. The golden parity tests compare every finding's evidence.

### Confirmation

`GoldenParityTests` in `archsim-engine` runs every recorded call of each ported report function,
decoding its input with the records' `read`.

`ReportRecordKeysTests` checks the records against the report, so a misnamed component fails the
build instead of reading as null or zero: every component of every record a `RunReport` holds
names a key the golden files' reports (each template's runs) write where that record is read, and
every record in `io.archsim.engine.report` is `RunReport` or one it holds.

## Pros and Cons of the Options

### A complete record model of the report

- Good, because the whole report would be typed, ready for stage 4's writer.
- Bad, because most of it is read by nothing in stage 3, and the parts that vary by component
  kind (each part's summary, counters and time series, each finding's evidence) would be
  modelled before a writer defines them.
- Bad, because the model's codec rejects unknown keys, so a report of a newer version would not
  read.

### Jackson trees

- Good, because the port reads line for line like the Python.
- Bad, because every value is a string looked up at run time, which ADR-0038 rejected for
  designs for the same reason.

## More Information

- [ADR-0020](0020-golden-files-of-the-frozen-engine.md): the golden files.
- [ADR-0038](0038-engine-computes-on-typed-records.md): the engine computes on records.
