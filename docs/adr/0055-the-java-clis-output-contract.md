---
status: accepted
date: 2026-11-10
decision-makers: Majid Abarghooei (owner)
---

# 0055. The Java CLI keeps the Python CLI's words, exit codes and JSON values

## Context and Problem Statement

Stage 5 of the [Java port](../proposals/java-25-port.md) gives `archsim` every command of the
frozen Python CLI (`archsim_engine/cli.py`, engine 0.16.0): validate, run, compare, analyze,
calibrate, import, export, check, catalog and schema, "with the same flags and output". Scripts and
CI jobs read what the commands print and their exit codes, and the `archsim-check` action fails a
job on them. But some of the Python CLI's output cannot be reproduced exactly, or should not be:

- the JSON a command prints (`json.dumps(value, indent=2)`) has Python's key order, which the Java
  engine does not always keep (the schema's keys are sorted, some catalog maps are in another
  order), and a few sums differ in their last digits;
- argparse writes the help and usage errors, in its own words;
- where the Python CLI crashed (a missing file, text that is not JSON, a `KeyError`), it printed a
  traceback.

What does "the same output" mean for the Java CLI, and how is it checked?

## Decision Drivers

- Anything a caller acts on must not change: the lines it reads, which stream they go to, the
  exit codes.
- It has to be checkable after the Python engine is deleted, as the golden files are
  ([ADR-0020](0020-golden-files-of-the-frozen-engine.md)).
- A user should never see a stack trace for a mistake they can fix.

## Considered Options

- Lines word for word, JSON as values, picocli's help and usage, a message for each crash
- Everything byte for byte, help and usage included
- Values only, for text and JSON alike

## Decision Outcome

Chosen option: "Lines word for word, JSON as values, picocli's help and usage, a message for each
crash", because it keeps everything a caller reads or branches on, and is checkable without Python.

- **Lines.** Every line the Python CLI printed, word for word and on the same stream: summaries,
  issues, refusals, notes, verdicts. The same exit codes, including `check`'s 0, 1 and 2.
- **JSON.** A command that prints JSON writes it as `json.dumps(value, indent=2)` does
  (`PythonJson.writeIndented`: two spaces, non-ASCII escaped, floats as their repr, `": "`), with
  the Java engine's key order. It holds the values the Python CLI printed, compared as the golden
  files compare them (`JsonDiff`: text exactly, numbers to a relative 1e-9, an integer and a float
  alike when equal, keys in any order). A report `archsim run --out` writes is written the same
  way, which settles what [ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md) left
  open.
- **Help and usage.** picocli's own words, without colour; a usage error still exits with code 2,
  as argparse's did. An option may be named by the start of its name, as argparse allowed
  (`--scen` for `--scenario`); picocli also takes the start of each word (`--m-r` for
  `--max-requests`), which argparse refused.
- **Crashes.** Where the Python CLI crashed, the Java CLI does not: a file it cannot read or write,
  or that is not JSON, is refused in one line that names the file and the reason, with exit
  code 1, as Python's crash exited (`archsim check` answers 2, its code for anything but pass or
  fail).
  Where the Python crashed on a design the Java engine can work on, the Java CLI answers as the
  Java API does. Each is a listed departure in `archsim-cli/README.md`, with its own test.
- **The check.** `scripts/export_cli_cases.py` records each command of the frozen CLI, one file per
  command, and the Java tests run every case and compare it as above. The recordings stay when the
  Python engine is deleted.

### Consequences

- Good, because every line a script or CI job greps, and every exit code, is the Python CLI's.
- Good, because the comparison outlives the Python engine.
- Good, because a mistake gets a message, not a stack trace.
- Bad, because the JSON text is not byte for byte the Python CLI's: a diff of the two outputs shows
  reordered keys and `1` against `1.0`, though no JSON reader sees a difference.
- Bad, because help texts read differently from argparse's.

### Confirmation

The `*CommandTests` classes in `archsim-cli` replay every recorded case and check that printed
JSON is laid out as `json.dumps(value, indent=2)` lays it out. The departures' tests check the
Java CLI's answers where Python crashed: `RecordedCases.unreadableFiles` replays each file
recorded as unreadable, and `CommandFilesTests`, `ArchsimTests` (a refusal prints no stack trace)
and `AnalyzeCommandTests` the rest.

## Pros and Cons of the Options

### Lines word for word, JSON as values, picocli's help and usage, a message for each crash

- Good, because what callers read and branch on is the Python CLI's, and the check needs no
  Python.
- Bad, because the JSON text and the help differ from the Python CLI's.

### Everything byte for byte

- Good, because a recorded output could be compared with `diff`.
- Bad, because the Java engine would have to keep every Python key order and every intermediate
  rounding of its sums, for text no reader parses as text, and argparse's help layout besides.

### Values only

- Good, because it is the simplest comparison.
- Bad, because a changed word in a line a script greps, or a line moved to the other stream,
  would pass.

## More Information

- [ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md): `archsim run`, the first
  command, whose report text this settles.
- [ADR-0020](0020-golden-files-of-the-frozen-engine.md): the comparison the JSON shares.
- [`archsim-cli/README.md`](../../archsim-cli/README.md): the commands and their departures.
