---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0020. Record the frozen engine's deterministic functions as golden files

## Context and Problem Statement

Stages 1 and 3 port the design model and the engine's deterministic parts: validation, estimate,
sizing, cost, pricing, review, confidence, explain, compare, exports, importers and calibration
fits. Their parity bar is exact text and numbers to a relative 1e-9
([ADR-0005](0005-port-to-java-25-in-stages.md)). The Python engine is frozen at 0.16.0
([ADR-0017](0017-freeze-python-engine-during-port.md)) and deleted at stage 4, while Java tests
must keep checking against it after that.

How should the Java tests get the Python engine's answers?

## Decision Drivers

- Java tests must not need Python, since it goes at stage 4.
- A failure should name the function that differs, not just a wrong end result.
- The reference must not drift while the engine is frozen.

## Considered Options

- Record each function's inputs and outputs as committed golden files
- Call the Python engine from the Java tests at test time
- Record only end results (`analyze_document` per scenario)

## Decision Outcome

Chosen option: "Record each function's inputs and outputs as committed golden files", because the
files outlive the Python engine and each entry tests one function on its own.

What they are ([`parity/README.md`](../../parity/README.md)):

- **Generator.** `scripts/export_golden.py` calls the engine and writes `parity/golden/`. It
  imports the engine and changes nothing in it.
- **Coverage.** Every case of the 25 templates and their 124 scenarios, the catalogs (components,
  schema, pricing, assumptions, number formatting), and fixtures: schema versions, invalid
  documents, importer inputs and calibration data.
- **Entries.** Each entry is `{fn, input, output}`, or `{fn, input, error}`. Intermediate values
  are recorded too, so a function can be checked without the ones before it. A large input
  already in the file is a JSON Pointer reference.
- **Reports as inputs.** Functions that read a simulation report get two short fixed-seed runs
  per template as their input, because the Java kernel will not reproduce Python's reports bit
  for bit.
- **Format.** Gzip of canonical JSON (sorted keys, no whitespace), 34 files and 4.5 MB, with a
  manifest of each file's uncompressed SHA-256.
- **Guard.** `export_golden.py --check` regenerates the files and compares them: text exactly,
  numbers to 1e-9. It runs in the Python CI job.

### Consequences

- Good, because stage 1 and 3 tests read plain files, and a mismatch names the function and the
  JSON Pointer.
- Good, because `--check` in CI catches any change to the frozen engine's answers.
- Bad, because 4.5 MB of binary files sit in git and don't diff. They change only if the frozen
  engine does.
- Bad, because the Java side must resolve references, compare numbers within a tolerance, and
  map Python function names to Java ones.
- Bad, because a few functions need test seams in Java: an injectable clock for calibration, and
  injected simulation results for `check` and calibration replays.

### Confirmation

The Python CI job runs `export_golden.py --check`, and `scripts/tests/test_export_golden.py`
replays one template's entries and checks every file's manifest row. Stages 1 and 3 compare their
Java functions with the files.

## Pros and Cons of the Options

### Call the Python engine at test time

- Good, because there are no files to keep.
- Bad, because it needs Python in every Java build, and stops working at stage 4.

### Record only end results

- Good, because it's small.
- Bad, because a difference deep inside (a rounding in sizing) shows up only as a wrong total,
  with no pointer to the function.

## More Information

- [The port proposal, stages 1 and 3](../proposals/java-25-port.md#stages)
- [ADR-0012](0012-separate-model-and-engine-modules.md) (which module ports which functions)
