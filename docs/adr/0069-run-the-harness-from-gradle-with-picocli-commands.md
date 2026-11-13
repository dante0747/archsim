---
status: proposed
date: 2026-11-13
decision-makers: Majid Abarghooei (owner)
---

# 0069. Run the harness from Gradle, as picocli commands named as `run.py` named them

## Context and Problem Statement

The Python harness had two entry points: `uv run python benchmarks/run.py <command>` (a stack,
`all`, `smoke`, `compare`, `resources`, `resimulate`, `range-fit`, `envcheck`, `contention`) and
`uv run python benchmarks/report.py [--json] [--dev]`; `scripts/java_parity_report.py` wrote and
checked the Java engine's parity page. Stage 7's exit is that "the harness runs from Gradle"
([ADR-0068](0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md) puts it in
`archsim-benchmarks`). How is it started, and how are its commands named?

Its commands differ in length: `report` takes a second, `compare` a few, `resimulate` and
`range-fit` minutes of simulation, and a campaign (`all --runs 3`) hours of Docker.

## Decision Drivers

- One documented way to run it, the way a Java developer expects.
- The commands, options and log lines `docs/validation.md`, the notes and the workflows name stay
  recognisable.
- A long campaign stopped with Ctrl-C still stops its stack, as Python's `finally` did.

## Considered Options

- The `application` plugin's `run` task with picocli subcommands:
  `./gradlew :archsim-benchmarks:run --args="<command> ..."`
- One Gradle task per command (`./gradlew benchmarkReport`, `benchmarkCompare`, ...)
- An installed launcher only (`installDist`, then `archsim-benchmarks/build/install/...`)

## Decision Outcome

Chosen option: "the `run` task with picocli subcommands", because it is how `archsim-cli` is
built and run already, and one task takes every command and option.

- **Commands:** the main class `io.archsim.benchmarks.Benchmarks` takes `run.py`'s commands by
  their names (`postgres`, `cache`, `kafka`, the held-out stacks, `all`, `smoke EXPERIMENT`,
  `compare`, `resources`, `resimulate`, `range-fit`, `envcheck`, `contention`) with their options
  (`--only`, `--runs`, `--archive`, `--keep`, `--label`, `--reuse-dev`, `--probe-only`,
  `--service-only`), and two more: `report [--json] [--dev]` (`report.py`) and
  `java-parity [--check]` (`scripts/java_parity_report.py`).
- **Where it runs:** the task starts in the repository's root, so `benchmarks/` and `docs/` are
  found as before, and with the same JVM options every time (heap, `-XX:+ExitOnOutOfMemoryError`).
- **Stopping:** the harness registers a shutdown hook that brings down the stack it started, so
  Ctrl-C leaves no stack behind.
- `./gradlew :archsim-benchmarks:installDist` stays available for a campaign that should not hold
  a Gradle build open for hours; it runs the same main class.
- The Makefile's `benchmark` target and the `benchmark` workflow call the same task.

### Consequences

- Good, because there is nothing to install before running the harness, and every command reads
  as `run.py`'s did.
- Good, because the CI job that checks the parity page runs the module's tests, with the rest of
  the build.
- Bad, because `--args="..."` quoting is Gradle's, longer than a script's arguments.
- Bad, because Gradle prints its own lines around the harness's log.

### Confirmation

The command line's tests (picocli, as `archsim-cli`'s are tested); the smoke run of one
experiment on Docker through the task.

## Pros and Cons of the Options

### One Gradle task per command

- Good, because `./gradlew benchmarkReport` is short.
- Bad, because every option needs a Gradle property, and the commands live in the build script
  as well as in the code.

### An installed launcher only

- Good, because a long run holds no Gradle build.
- Bad, because it is two steps, and a stale install runs an older harness.

## More Information

- [ADR-0068](0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md): the module.
- [ADR-0056](0056-the-java-cli-takes-options-by-their-full-names.md): the CLI's options, which
  the harness's follow.
