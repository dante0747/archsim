---
status: proposed
date: 2026-11-09
decision-makers: Majid Abarghooei (owner)
---

# 0052. Record the frozen engine's simulation baselines once, and judge the Java simulator by them

## Context and Problem Statement

Stage 4's parity bar ([the port plan](../proposals/java-25-port.md)) is statistical: for every
template scenario, with 20 seeds each, the Java simulator's median p50, p95, throughput and
utilisation must fall inside the Python engine's seed range and within 2% of its median. The
Python side of that comparison is 2,980 full-length runs of the frozen engine (149 cases, the 25
templates' designs and their scenarios, by 20 seeds): about 90 CPU-hours. Stage 5's exit deletes
the Python engine. Where does the Python side come from, then and later?

## Decision Drivers

- The bar has to be checkable after the Python engine is gone, as the golden files keep stage
  3's functions checkable ([ADR-0020](0020-golden-files-of-the-frozen-engine.md)).
- Ninety CPU-hours cannot run on every change, nor in CI.
- What is committed has to be small enough for the repository, and enough to judge the bar and
  to say how many runs the Java simulator replays exactly
  ([ADR-0049](0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)).

## Considered Options

- Record each run's figures once, with a script, and commit them under `parity/simulation/`.
- Commit each run's whole report.
- Run the Python engine beside the Java one whenever the bar is checked.

## Decision Outcome

Chosen option: record each run's figures once, because it keeps the bar checkable without the
Python engine at a few megabytes.

- `scripts/export_simulation_baselines.py` simulates every template case in full on the frozen
  engine 0.16.0 with its workload's seed and the 19 after it (as a seed group runs them), and
  keeps each run's figures: the events processed, the requests generated, both digests, the
  summary's values and latencies, and each part's summary values. A run that fails is recorded
  with its error. The files are `parity/simulation/<template>.json.gz`, canonical JSON gzipped,
  with a manifest of their SHA-256s, as the golden files are.
- Recording is resumable and watched: each run is bounded (an hour, the engine's request limit),
  each finished run is appended to a log with the time and the progress, the recording stops if
  no run finishes for 75 minutes, and a stopped recording resumes where it was. `--check`
  simulates chosen runs again and compares them with the committed files.
- `./gradlew :archsim-engine:parity` simulates the same runs in Java, judges each case by the bar,
  counts the runs that give exactly Python's figures and digest, and writes
  `build/parity/simulation-parity.json`, with the cases that miss and what they missed, from
  which the parity report (`docs/validation/java-parity.md`) is written. It is hours of
  simulation, so it is not part of `check`.
- A failed Java run is recorded as the script records a Python one, its error's type (the frozen
  engine's name for the engine's own refusals) and message, so a run failing as Python's did
  replays it. A case with a seed that fails otherwise than Python's did (on one side only, or
  with another error) misses the bar, whatever its medians: they would come from different
  seeds, or from none, and a Java crash would pass wherever Python's run had failed.

### Consequences

- Good, because the bar stays checkable, on any machine, after the Python engine is deleted.
- Good, because every run's digest is kept, so the count of exact replays needs nothing more.
- Bad, because the figures are only what the script keeps: a report field it leaves out cannot
  be compared later without the Python engine (the golden runs keep 50 whole reports for that).
- Bad, because the recording takes about twenty hours on four cores, once.

### Confirmation

The parity task's report, published in `docs/validation/java-parity.md`; the script's tests
(`scripts/tests/test_export_simulation_baselines.py`) and the runner's (`ParityBarTests`,
`MetricVerdictTests`, `CaseResultTests`, `RunFiguresTests`, `RunErrorTests`, `BaselinesTests`,
`ParityReportTests`).

## Pros and Cons of the Options

### Record each run's figures once

- Good, because it is a few megabytes, and checkable without the Python engine.
- Bad, because it keeps only the figures the script picks.

### Commit each run's whole report

- Good, because nothing could be missing later.
- Bad, because 2,980 reports of up to a megabyte each are too much for the repository, for
  little more than the figures give.

### Run the Python engine beside the Java one

- Good, because nothing is recorded or committed.
- Bad, because it needs the Python engine for as long as the bar is checked, which outlives it,
  and ninety CPU-hours each time.
