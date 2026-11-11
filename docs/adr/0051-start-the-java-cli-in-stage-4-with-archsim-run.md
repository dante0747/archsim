---
status: accepted
date: 2026-11-09
decision-makers: Majid Abarghooei (owner)
---

# 0051. Start the Java CLI in stage 4 with `archsim run`

## Context and Problem Statement

Stage 4's exit ([the port plan](../proposals/java-25-port.md)) says the benchmark harness calls the
Java CLI, and its parity bar holds every kept calibration's knee to within 2% of the frozen
engine's. But the CLI is stage 5's, and the harness has no CLI call to switch: `simulate` in
`benchmarks/run.py` imports the Python engine's `run_simulation` and runs it in-process. How does
the harness run the Java simulator in stage 4?

## Decision Drivers

- The calibration-knee bar must be measured before the Python engine goes.
- Stage 5's CLI keeps the Python CLI's flags and output; what is built now should be its start, not
  a stopgap thrown away a stage later.
- The harness stays Python until stage 7: it runs the Java engine as another process either way.
- Whatever the harness writes must say which engine wrote it, so results of the two engines are
  never mistaken for each other or written over each other.

## Considered Options

- An `archsim-cli` module (picocli, as the plan's stage 5 names) with one command, `archsim run`,
  and an engine switch in the harness that runs it.
- A main class in `archsim-engine`, for the harness alone.
- No engine switch until stage 5.

## Decision Outcome

Chosen option: an `archsim-cli` module with `archsim run`, because it is the first command of stage
5's CLI rather than a stopgap, and it lets the harness measure the knee bar in stage 4.

- `archsim run FILE [--scenario ID] [--seed N] [--sampled] [--out FILE]` takes the Python CLI's
  flags and prints its output word for word: the summary line, a quick check's note, how far to
  trust the run, the findings and the digests; an invalid design's issues, and the refusal on
  standard error with exit code 1. `--seed` replaces the design's seed before its scenario is
  applied, as the Python CLI does (a scenario that sets its own seed keeps it), unlike a seed
  group's run.
- Two flags the Python `run` does not have, `--max-requests` and `--max-wall-seconds`, set the
  run's limits (the engine's own when left out; a value that is not a number above zero is a usage
  error): the harness lets a run take an hour.
- `--out` writes the report as Jackson's pretty printer writes JSON (`"key" : value`, arrays on one
  line). It holds what the Python report holds, as the recorded simulation cases compare them, not
  the Python text: the keys' order and the floats' spelling may differ. Stage 5 decides whether
  that text has to match.
- The harness's `simulate` runs `archsim run --out` (built by `./gradlew :archsim-cli:installDist`,
  and run on the Java 25 that `JAVA_HOME` names) when `BENCH_ENGINE=java`; without it, the frozen
  Python engine, until stage 4's exit. On the Java engine only `resimulate` and `range-fit` run:
  any other command writes an experiment's own files, which name the frozen engine, so it is a
  usage error before it starts a stack or writes a file. Their development checks and range fits
  are labelled `0.16.0-java` (the `engineVersion`, the folder under `results/dev/`, the range
  fit's cache and its file, `knee-range-0.16.0-java.json`), and provenance hashes the Java
  engine's three source trees and the jars installed (`javaBuild`), which may be older.

**Status (10 Nov 2026):** the Java engine is engine 1.0.0, so its checks and fits are labelled
with its own version, as `archsim --version` prints it: `1.0.0`, `results/dev/1.0.0/`,
`knee-range-1.0.0.json`. `results/dev/0.16.0-java/`, written before, stays as the record that
[java-parity.md](../validation/java-parity.md) reads, and the frozen engine never writes a label
that starts with `0.16.0-java`. "Until stage 4's exit" above is now until stage 5's: the
harness's move to the Java CLI and the Python engine's deletion moved there on 8 Nov 2026, since
the MCP server and the `archsim check` action import the Python engine until stage 5 ports them,
so `BENCH_ENGINE` stays explicit until then.

**Status (10 Nov 2026, later):** `--out` writes the report as Python's `json.dumps(report,
indent=2)` writes it, as every command that writes JSON does
([ADR-0055](0055-the-java-clis-output-contract.md), proposed): the same values as before, in the
Python layout rather than Jackson's.

**Status (11 Nov 2026):** the Java engine is the harness's default for every command, and
`BENCH_ENGINE=python` runs the frozen engine until stage 5 deletes it; the committed range fit is
the Java engine's to write
([ADR-0062](0062-make-the-java-engine-the-harnesss-default.md), proposed).

### Consequences

- Good, because the harness simulates the same designs on both engines, so the knee bar can be
  measured in stage 4, and the two engines' results sit side by side under their own labels.
- Good, because stage 5 grows this module instead of replacing it.
- Bad, because a module and a dependency (picocli) arrive a stage early.
- Bad, because each simulation the harness runs on the Java engine starts a JVM (about a second),
  small against runs of seconds to minutes.

### Confirmation

`RunCommandTests` compares `archsim run`'s exit code and output with the frozen engine's CLI on
recorded designs (`scripts/export_cli_cases.py`); the harness's tests run its engine switch with a
stand-in for the command, and check the labels and the refusals.

## Pros and Cons of the Options

### An `archsim-cli` module with `archsim run`

- Good, because it is stage 5's CLI begun: its command, flags and output carry over.
- Good, because the harness runs the Java engine as a user would, through the command it ships.
- Bad, because stage 5's module arrives early, with its dependency.

### A main class in `archsim-engine`

- Good, because it needs no new module or dependency.
- Bad, because it would be thrown away in stage 5, and the engine, a library, would carry a
  program's argument parsing and output.
- Bad, because the harness would test a path no user runs.

### No engine switch until stage 5

- Good, because nothing is built early.
- Bad, because the calibration-knee bar could not be measured in stage 4, whose exit deletes the
  Python engine it is measured against.
