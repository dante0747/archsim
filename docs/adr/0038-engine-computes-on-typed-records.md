---
status: proposed
date: 2026-11-04
decision-makers: Majid Abarghooei (owner)
---

# 0038. The engine computes on the model's records and typed configs, not on JSON trees

## Context and Problem Statement

The Python engine's deterministic code works on plain dictionaries: the document a run
simulates (`simulation_input`), each node's `config` merged with its type's defaults, and
results built as nested dicts. It reads them defensively (`num(cfg.get("replicas")) or 1.0`),
because a dict carries no types. The Java port of these parts (stage 3,
[proposal](../proposals/java-25-port.md#stage-3--the-engines-deterministic-parts-12-weeks))
must give the same answers, checked against the frozen engine's
[golden files](0020-golden-files-of-the-frozen-engine.md). What should the Java engine compute
on: Jackson trees, as the Python computes on dicts, or the model's records?

## Decision Drivers

- Golden-file parity: the same numbers and words for every recorded input.
- Code an engineer can read and change safely: a misspelt setting should not compile.
- One place for each default: the model's records already hold them
  ([ADR-0022](0022-records-and-a-pydantic-compatible-codec.md),
  [ADR-0026](0026-component-specs-and-price-snapshots-in-the-model.md)).

## Considered Options

- The model's records: `ArchitectureDocument`, and each node's config read as its type's record
  (`ComponentSpec.parseConfig`), told apart with sealed types and pattern matching; results as
  records written by the model's codec
- Jackson trees throughout, read as the Python reads its dicts
- Trees in, records out

## Decision Outcome

Chosen option: "the model's records", because the compiler then checks every setting the engine
reads, the defaults stay in the model, and the golden files prove the answers did not change.

- A node's config is read as its record wherever the Python engine parses it, and a config that
  is not valid for its type is treated as the Python treats a failed `parse_config` (sizing
  skips the part, for example).
- The exception is code that runs on a design that may not be valid yet and reads raw settings
  without parsing them: the load estimate (which sizing, the review and the editor's live
  inspector run), which kind of machine a part runs on, and the CPU work on the design's host.
  There the engine reads the few settings it needs in one class (`LenientSettings`), with the
  Python's rules: a setting that is not a number counts as missing, and each reader says what
  missing means (Python's `or` fallbacks included). It reads a part's config as the Python
  engine saw it in a normalized design: with every default written out when the config is valid
  for its type, as written otherwise. A design with a setting out of range or of the wrong type
  is then estimated and sized as before; the golden files' invalid documents check it.
- On the record path, Python's fallbacks for settings the schema always fills (`or 1.0`,
  `256 if None`) are not ported: the record's default and its bounds already give them.
- A result with a fixed shape is a record, written by `RecordCodec`; a result that is a map by
  design (per-node sizes, explanations by spec) is a `Map` of records or strings, in the order the
  Python builds it. A result whose items come in shapes told apart by their fields, not a tag
  (the instant estimate's parts: a note, or the figures), is a sealed interface of records; the
  codec's unions need a tag, so the result puts its JSON together by hand, each record written by
  `RecordCodec` (`InstantEstimate.toJson`). A write-only untagged union in the codec could
  replace that.
- Python's arithmetic is kept where it changes digits: `FloatSum` for the built-in `sum()`,
  `ExactSum` for `math.fsum`, the same order of operations.

### Consequences

- Good, because a setting the engine reads is a method of a record: renamed or removed, it no
  longer compiles.
- Good, because a component type's rules are a `switch` over its config record, which the
  compiler checks for every type.
- Bad, because the parity tests read each golden input into records first, and a recorded input
  that is not a valid document (none so far) would need a tree-based path.
- Bad, because on the record path a Python behaviour on an impossible value (a negative
  replica count the schema rejects) is not reproduced; validation refuses such a design before
  the engine sees it. The lenient path reproduces it, as the Python reads it.
- Bad, because a config is read in two ways, as a record and leniently. The lenient rules live
  in one class, so a reader of raw settings cannot spread through the engine unnoticed.

### Confirmation

`GoldenParityTests` in `archsim-engine` runs every recorded call of each ported function;
`ArchitectureTests` keeps the engine free of framework and JDBC classes.

## Pros and Cons of the Options

### Jackson trees throughout

- Good, because the port reads line for line like the Python.
- Bad, because every setting is a string looked up at run time, with its default repeated
  wherever it is read.

### Trees in, records out

- Good, because outputs are typed.
- Bad, because the inputs, where most of the logic reads, stay untyped.

## More Information

- [ADR-0012](0012-separate-model-and-engine-modules.md): the model and the engine as modules.
- [ADR-0036](0036-python-engine-service-for-synchronous-calls.md): the service each ported part
  replaces.
