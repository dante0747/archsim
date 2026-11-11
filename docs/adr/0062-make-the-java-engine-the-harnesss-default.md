---
status: proposed
date: 2026-11-11
decision-makers: Majid Abarghooei (owner)
---

# 0062. Make the Java engine the benchmark harness's default

## Context and Problem Statement

[ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md) let the accuracy harness
(`benchmarks/run.py`) simulate on the Java engine when asked (`BENCH_ENGINE=java`), for two
commands only: `resimulate` and `range-fit`, which write under the engine's own label. Every other
command wrote an experiment's own files (calibration, predictions, resources), which named the
frozen Python engine, so it was refused on the Java engine.
[ADR-0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md) moved the engine's
functions the harness calls to the Java engine. Stage 5's exit ([the port
plan](../proposals/java-25-port.md)) has the harness on the Java CLI and then deletes the Python
engine. When the Java engine is the default, what becomes of the switch, of the files each command
writes, of the committed range fit and of the seal on held-out experiments?

## Decision Drivers

- New results are the engine that remains: what a command writes names the engine that ran.
- The frozen engine stays runnable until it is deleted, to compare against.
- Results of the two engines are never mistaken for each other or written over each other's
  (ADR-0051).
- A held-out experiment is still calibrated and measured only on a frozen engine
  ([ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md),
  [ADR-0017](0017-freeze-python-engine-during-port.md)).

## Considered Options

- The Java engine for every command by default; `BENCH_ENGINE=python` for the frozen engine
- The Java engine by default for `resimulate` and `range-fit` only, the rest on the frozen engine
  until it is deleted
- No switch: delete the Python path now

## Decision Outcome

Chosen option: the Java engine for every command by default, because the engine that predicts is
then the one that remains, and the frozen engine is still there to compare against until stage 5
deletes it.

- `BENCH_ENGINE` unset, empty or `java`: the Java engine; `python`: the frozen Python engine; any
  other value is refused before a command starts, rather than taken for either.
- Every command runs on either engine. A prediction and the resources predicted again name the
  engine that ran (`engineVersion`, as `archsim --version` prints it) and its reports' version
  (`reportVersion`, from `archsim functions`' `report_version`), as they named the frozen
  engine's. Nothing on the Java engine's path imports the Python engine.
- `range-fit` on the Java engine writes the committed fit, `benchmarks/knee-range.json`, which
  `predict` reads; on the frozen engine it writes one labelled with its version
  (`knee-range-0.16.0.json`), so neither writes over the other's. The committed fit is 0.16.0's
  until it is fitted again: the Java engine re-simulates every kept calibration with exactly
  0.16.0's knees ([java-parity.md](../validation/java-parity.md)), so it holds for both.
- Development checks keep their labels: each engine writes under its version.
- The seal is unchanged: it knows a frozen engine by the Python engine's tree, so on the Java
  engine the held-out experiments stay sealed. Freezing the Java engine, before the next
  held-out campaign, decides what identifies it (the hash of its source trees, `engineSources`,
  the installed build, `javaBuild`, which may be older than the sources, or both) and changes the
  seal to match.
- The benchmarks' tests run on the Java engine, as CI's Python job does since ADR-0061 installs the
  CLI for them; `scripts/ci_changes.py` runs them for a change to what the CLI is built from (the
  CLI's, the engine's and the model's sources and the Gradle build), not to those modules' tests.

### Consequences

- Good, because a benchmark run from here on predicts with the engine that remains, and says so.
- Good, because the Python engine's deletion removes only the `BENCH_ENGINE=python` branches.
- Good, because the benchmarks' tests run faster on the Java engine, though each simulation
  starts a JVM: all of them took 7.5 minutes locally, against 15 on the frozen engine, and the
  slowest five 423 s against 962 s (that run partly beside other work).
- Bad, because a change to the CLI's, the engine's or the model's sources now also runs the
  benchmarks' tests in CI, about 9 minutes of the Python job.
- Bad, because no held-out experiment can be calibrated or measured on the Java engine until it
  is frozen and the seal knows it.

### Confirmation

The harness's tests check the switch (unset, empty, `java`, `python`, and another value refused
before a stack starts), a prediction's versions on each engine, the engine's label on the Java
engine without the Python engine, where `range-fit` writes on each, that no command is refused on
the Java engine, and the provenance on each. The benchmarks' tests pass on the Java engine.

## Pros and Cons of the Options

### The Java engine for every command by default

- Good, because new results are the engine that remains, while the frozen one stays to compare.
- Bad, because the benchmarks' tests and a benchmark run start a JVM per simulation, about a
  second each, which the Java engine's speed more than makes up for.

### The Java engine by default for `resimulate` and `range-fit` only

- Good, because an experiment's own files keep naming the frozen engine until it goes.
- Bad, because the next benchmark run before the deletion would predict with an engine about to be
  deleted, and the deletion would have to move every command at once.

### No switch: delete the Python path now

- Good, because there is one path.
- Bad, because the frozen engine could no longer be run to compare against before stage 5 deletes
  it, and the deletion's own slice would lose its last check.

## More Information

- [ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md): the switch and the labels.
- [ADR-0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md): the engine's
  functions from the Java engine.
