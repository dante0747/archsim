---
status: accepted
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0103. Refuse a number that is not finite when a design is read, where the frozen engine took it

## Context and Problem Statement

The codec reads a design as the Python engine's pydantic models did, lax input included
([ADR-0022](0022-records-and-a-pydantic-compatible-codec.md)): the strings `"nan"`, `"inf"` and
`"infinity"` were floats, as Python's `float(text)` reads them, and so was a number with a fraction
or an exponent too large for a double. Nothing a design sets means NaN or an infinity. A field with
no upper bound kept an infinity, a field with no bound at all kept NaN, and a check written as a
comparison is false for NaN whichever way it asks, so the checks after the reader (a record's own,
a run's request limit) let such a value through to code that was never written for it.

Should the reader go on taking these values because the frozen engine did?

## Decision Drivers

- A design the validator calls valid is one the engine can simulate: every bound and every limit
  holds for every number it read.
- One place decides, not each of about 130 float fields.
- The error says what is wrong and where, in the words the other errors use.
- Parity with the frozen engine ([ADR-0020](0020-golden-files-of-the-frozen-engine.md),
  ADR-0022) is kept wherever it does not cost correctness.

## Considered Options

- Keep the lax reading, and make every later check hold for NaN and the infinities.
- Refuse the three words where a string is read as a float.
- Refuse any float that is not finite where a float is read, however the input writes it.

## Decision Outcome

Chosen option: "refuse any float that is not finite where a float is read", because it is the one
rule that covers every field and every way in.

- **`FloatShape` refuses it.** Every float of every record the codec reads is read there. A value
  that is not finite is an error of type `finite_number`, "Input should be a finite number", at the
  value's place: pydantic's own error for a model that forbids such values, and the one an integer
  field already gave. It comes before the bounds, so NaN in a bounded field is "not finite", not
  "above the maximum". It covers the words in any case, a number with a fraction or an exponent too
  large for a double, as a JSON number or as a string (`1e999`), and a float node that JSON text
  cannot hold (from YAML's `.inf`, or built in code). A whole number too large for a double,
  written as a JSON number, is refused as before, as "Input should be a valid number"; the same
  digits as a string read as an infinity, and are "not finite".
- **Its scope is every record the codec reads**, not designs alone: request bodies (a design, a
  calibration's range), calibration mappings, imported designs and price snapshots.
- **Settings kept as plain JSON** (a part's `config`, a scenario override's value) pass the same
  reader when their type's record reads them, so they need no rule of their own.
- **The records' own checks hold for NaN too.** A record can be made in Java without the codec, so
  each cross-field check of a design's records (a workload's warm-up and burst start, a trace's
  times, a quantile table, a lognormal's and a uniform distribution's) is written to fail for NaN;
  the engine counts a run's requests against its limit the same way
  (`SimulationLimits.allowsRequests`), and the arrival routine refuses a peak rate that is not
  finite. These are a second line; the reader is the rule.
- **The schema version stays 1.11.** A document that held such a value was valid and is refused
  now, which the contributing guide counts as a schema change. No upgrade step can repair one:
  there is no finite number a NaN meant, and putting a bound in an infinity's place would change
  what the design says. A new version with no upgrade step would mark every document as old and
  change none. A stored design that holds one still opens (the API returns what it stored);
  validation names the value's path, and saving or simulating it is refused until it is corrected.
- **The engine version stays.** No design that validates simulates differently.

This narrows ADR-0022: the codec reports "the same errors as pydantic" for everything but a float
that is not finite. Two recorded cases of the frozen CLI's `calibrate`, with a step that is NaN or
infinite, pinned the old reading and are departures now (`CalibrateCommandTests`); so did the
black-box test of a CPU figure of 10,000 digits imported through the API (`ImportExportTests`),
whose issue named the setting's bound and now says it is not finite. In the golden files such floats
appear only among the inputs of the number-formatting functions
([parity/README.md](../../parity/README.md)). The API's and the importers' lists of differences from
Python say so (archsim-app's and archsim-engine's READMEs).

### Consequences

- Good, because the rule is in one place and holds for a field added later without anyone
  remembering it.
- Good, because the JSON Schema is unchanged: JSON has no literal for these values, so the schema's
  `number` never included them; the strings were the way in.
- Good, because an author who typed `inf` for "no limit" is told at once, at the field, instead of
  reading results computed from it.
- Bad, because the codec no longer matches pydantic's lax mode in this one thing; `FloatShape` and
  [docs/schema.md](../schema.md) say so.
- Bad, because a design that used an infinity on purpose (a timeout that never fires) must name a
  large number instead.

### Confirmation

- `FiniteNumbersTests` finds every float of every record a design is read with, the component
  types' settings included, by reflection, and reads each way of writing a value that is not
  finite into each: about 900 cases, none listed by hand.
- `FiniteFloatsTests` covers the reader itself, extreme finite values and too large whole numbers
  included.
- `LoadingPropertiesTests` replaces one number of an example with an extreme or a value that is not
  finite, a thousand times a build, and requires that a design which still loads holds only finite
  floats, in its settings as their types read them and in the arrivals its workload expects.
- `WorkloadTests`, `TracePatternTests`, `EmpiricalTests`, `LogNormalTests`, `UniformTests`,
  `SimulationLimitsTests`, `ArrivalsTests` and `SimulatorTests` cover the checks after the reader;
  the black-box suite covers `POST /validate`, a run that carries its own design and an import.

## Pros and Cons of the Options

### Keep the lax reading, and make every later check hold

- Good, because the codec stays pydantic's, word for word.
- Bad, because every comparison in the model and the engine, present and future, would have to be
  written for NaN, and one that is not fails silently.
- Bad, because an infinity that passes every check still means nothing.

### Refuse the three words where a string is read

- Good, because it is the smallest change.
- Bad, because the words are one way in among several: `1e999` as a number or a string, and a float
  node from YAML, give the same values.

## More Information

- [ADR-0022](0022-records-and-a-pydantic-compatible-codec.md): the codec this narrows.
- [ADR-0023](0023-upgrade-chain-on-the-json.md): the upgrade chain, which gets no step.
- [ADR-0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md): the API's contract, whose
  differences archsim-app's README lists.
- [docs/schema.md](../schema.md): its conventions say what a design's numbers may be.
