---
status: proposed
date: 2026-11-11
decision-makers: Majid Abarghooei (owner)
---

# 0061. Answer the benchmark harness's engine calls from one Java process

## Context and Problem Statement

The accuracy benchmarks' harness (`benchmarks/run.py`) stays Python until the port is done
([ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md)). Besides simulating,
which it already does on the Java engine through `archsim run`
([ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md)), it calls the engine's
deterministic functions to derive a calibration's inputs: the mean of a distribution
(`Distribution.expected`), the resource fits (`fit_load_curve`, `extend_load_curve`,
`fit_memory`), and a machine's thread speed (`Machine.speed`) in a grid search. Its tests call
`fit_cpu_share` and a curve's factor at a rate (`load_factor`) too. They imported them from the
frozen Python engine, which stage 5 of the [Java port](../proposals/java-25-port.md) deletes. How
do the harness and its tests call the Java engine's?

Deriving the inputs of the 67 kept runs (169 input sets) made 1,038 calls before any anchoring
simulation: 599 means, 157 curve fits, 125 memory fits and 157 reads of a constant (one read now:
the client keeps the constants). A machine fit evaluates 81 machines over its probe points.

## Decision Drivers

- The harness keeps its own logic and asks the engine only for the engine's functions: no Python
  copy of them ([ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md)).
- A JVM takes about a second to start; a thousand calls must not start a thousand.
- The call sites stay as they read: a function called with its arguments, its result used at once
  (a fit feeds the next step).

## Considered Options

- One long-lived `archsim functions` process per harness process, answering a JSON line per call
- One `archsim` command per call
- Batch commands: the harness gathers calls and sends them together
- Copies of the functions in Python

## Decision Outcome

Chosen option: one `archsim functions` process per harness process, because a call then costs
about a millisecond, and the harness's code calls a function as it did.

- `archsim functions`, a hidden command of the CLI, reads a request a line on standard input,
  `{"function": NAME, "input": {...}}`, and writes an answer a line on standard output,
  `{"output": VALUE}` or `{"error": {"type", "message"}}` (`ValueError` for an input refused or
  of the wrong shape, `LookupError` for a function that is not one), each flushed before it reads
  the next line, until its input ends. It answers `expected`, `machine_speed` (a machine and a
  list of busy counts), `load_factor` (a table and a list of rates), `fit_load_curve` (with the
  curve's `serviceTimeByLoad` table), `extend_load_curve`, `fit_memory`, `fit_cpu_share` and
  `constants`, from the engine's classes; a fit is its record as `RecordCodec` writes it, or
  `null` when it finds none. Numbers go each way as Python's `json` writes them, `NaN` and
  `Infinity` included, which the fits leave out as the Python engine's did; a parameter that is
  one number must be finite, where the engine's arithmetic and Python's would part ways. A line
  that is not a request is answered with an error, and the next one still is.
- `benchmarks/engine_functions.py` starts the process on the first call, from the CLI that
  `./gradlew :archsim-cli:installDist` installs, sends each call in turn, and gives the Python
  engine's results back as small dataclasses with its fields and methods. A forked worker starts
  its own; the process ends with the harness. Every harness command, and `uv run pytest` (which
  runs the benchmarks' tests), now needs the CLI installed and a Java 25 on `JAVA_HOME` or the
  `PATH`, whichever engine simulates.
- CI's Python job installs the CLI before the benchmark tests, and `scripts/ci_changes.py` runs
  those tests when a function they call changes (the command, the resource fits, the host
  machine, the load curve, the distributions, and the codec and writer of the answers).

### Consequences

- Good, because the harness asks the engine that will remain, with the answers the golden files
  hold it to: the kept runs' fits, means and speeds, 2,468 values, are the Python engine's.
- Good, because the harness reads as it did: `engine_functions.fit_memory(...)` where it imported
  `fit_memory`.
- Bad, because the harness needs Java and the installed CLI even where the frozen Python engine
  simulates, until that engine is deleted.
- Bad, because the CLI carries a command for one caller, whose protocol the harness and the CLI's
  tests must keep alike.

### Confirmation

`FunctionsCommandTests` checks each function's answer against the Python engine's for the same
call (a fit that finds nothing and a point that is not a number among them), the refusals, and
that an answer is written before the next line is read; `benchmarks/tests/test_engine_functions.py`
checks the client's results the same way, and that a forked process starts its own. The
benchmarks' tests run on the Java functions. A comparison of the previous harness (the Python
engine's functions) with this one on every kept run found no difference in 2,468 values: the
machine fit, every service's memory fit, both forms of every load curve, every distribution's
mean and every pass's thread speed.

## Pros and Cons of the Options

### One long-lived process

- Good, because a call is a line each way, about a millisecond, after one start of the JVM.
- Bad, because the process must be started, kept and ended, and a forked worker needs its own.

### One command per call

- Good, because nothing outlives a call.
- Bad, because the thousand calls of one re-derivation would take over a quarter of an hour.

### Batch commands

- Good, because one start of the JVM per batch.
- Bad, because the harness computes step by step, each result feeding the next: batching would
  turn its functions inside out.

### Copies in Python

- Bad, because two implementations that must agree, which the port exists to end.

## More Information

- [ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md): the harness simulates on
  the Java engine through `archsim run`.
- Stage 5's S5-9 makes the Java engine the harness's default for simulating.
