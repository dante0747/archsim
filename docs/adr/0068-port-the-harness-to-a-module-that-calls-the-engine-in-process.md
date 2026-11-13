---
status: accepted
date: 2026-11-13
decision-makers: Majid Abarghooei (owner)
---

# 0068. Port the benchmark harness to its own module, `archsim-benchmarks`, which calls the engine in process

## Context and Problem Statement

Stage 7 of the [Java port](../proposals/java-25-port.md) moves the accuracy benchmarks' harness
to Java ([ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md)): `run.py`
(5,500 lines: Docker, k6, the calibration staircase, the model inputs, the predictions, the
comparisons, the re-simulations and the range fit), `report.py` (the validation page, its charts
and `accuracy.json`) and their 219 tests. Until now the Python harness started `archsim run` for
every simulation ([ADR-0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md)) and asked
one long-lived `archsim functions` process for the engine's functions
([ADR-0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md)).

Where does the Java harness live, and how does it reach the engine?

## Decision Drivers

- The repository's modules sit at its root, one per box of the architecture
  ([ADR-0010](0010-gradle-build-at-repository-root.md),
  [ADR-0012](0012-separate-model-and-engine-modules.md)).
- `benchmarks/` holds what the harness measures and what it recorded: the stacks (Node.js, Go,
  Spring Boot under Maven, Flask), k6's script, `compose.yaml`, and `results/`, which is never
  edited.
- What ArchSim ships (the app, the CLI, the MCP server) holds no benchmark code.
- No process per simulation: a re-simulation of every kept run makes thousands.

## Considered Options

- A module `archsim-benchmarks` at the root, calling `archsim-engine` as a library; the data
  stays in `benchmarks/`
- A Gradle project inside `benchmarks/` (`benchmarks/src/main/java`)
- Subcommands of `archsim-cli` (`archsim bench ...`)
- A Java harness that keeps calling the CLI as a process

## Decision Outcome

Chosen option: "a module `archsim-benchmarks` at the root, calling the engine as a library",
because it is laid out like every other module and keeps code apart from the recorded data, as
the engine's tests are kept apart from `parity/`.

- **Code:** `archsim-benchmarks/`, package `io.archsim.benchmarks`, one package per part of the
  protocol: the experiments, Docker and k6, calibration, the model inputs, prediction,
  measurement, comparison, re-simulation, the knee's range, the machine checks (`envcheck`,
  `contention`), the held-out seals, and the reports.
- **Data:** stays where it is, in `benchmarks/`: the stacks, k6, `compose.yaml`, `engines.json`,
  `knee-range.json` and `results/`. The harness finds it from the repository's root.
- **The engine in process:** a simulation is `Simulator.run` on the design's JSON, as
  `archsim run` did it, and the engine's functions (a distribution's mean, the load-curve,
  memory and CPU fits, a machine's thread speed) are calls of the engine's classes. The design
  and the report are the same JSON values as before, so the results do not change.
- **What only the Python harness needed goes:** `archsim functions` and its tests leave the CLI
  once the Python harness is deleted. `archsim run --out` and `--version` stay: users run them.

### Consequences

- Good, because a re-simulation starts no JVM per run, and the harness needs no `installDist`.
- Good, because the CLI keeps only what users run.
- Good, because the stacks of other languages stay outside any Gradle module.
- Bad, because the harness's code and its data are in two folders; `archsim-benchmarks/README.md`
  says where each is.
- Bad, because the harness now runs the engine of its own build, never an installed one: a
  check of an older engine is a check out of its commit.

### Confirmation

`archsim-benchmarks`' tests: the ported tests of `run.py` and `report.py`, and parity tests that
regenerate what the Python harness wrote from `benchmarks/results` byte for byte
([ADR-0070](0070-write-the-harnesss-files-as-python-wrote-them.md)).

## Pros and Cons of the Options

### A Gradle project inside `benchmarks/`

- Good, because the harness and its data would be in one folder.
- Bad, because a Gradle module would hold a Maven project, a Go module and two npm projects that
  are not part of it, and its `build/` would sit beside recorded results.
- Bad, because it is the one module not at the root.

### Subcommands of `archsim-cli`

- Good, because there would be one command line.
- Bad, because every user's CLI would ship Docker, k6 and campaign code they never run.

### Keep calling the CLI as a process

- Good, because it is what the Python harness did.
- Bad, because it starts a JVM per simulation from a JVM that already holds the engine.

## More Information

- [ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md): the harness after the
  port.
- [ADR-0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md) and
  [ADR-0062](0062-make-the-java-engine-the-harnesss-default.md): how the Python harness called
  the Java engine; this ADR supersedes ADR-0061 once the Python harness is gone.
- [ADR-0069](0069-run-the-harness-from-gradle-with-picocli-commands.md): how the harness is run.
