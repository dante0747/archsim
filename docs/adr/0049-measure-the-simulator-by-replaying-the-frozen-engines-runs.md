---
status: proposed
date: 2026-11-08
decision-makers: Majid Abarghooei (owner)
---

# 0049. Measure the Java simulator by replaying the frozen engine's runs

## Context and Problem Statement

Stage 4 of the [Java port](../proposals/java-25-port.md) ports the simulator. The plan set a
statistical bar, because it assumed a port could not reproduce Python's runs: over 20 seeds per
template case, Java's median p50, p95, throughput and utilisation within Python's seed range and
2% of its median, the calibrated knees within 2%, and per-component counters on fixed inputs. The
golden files' README says the same of their recorded runs: compare with them statistically, not
number by number. Since then, the Java simulator draws Python's random numbers
([ADR-0047](0047-draw-python-random-streams-in-java.md)) and keeps SimPy's order of events
([ADR-0048](0048-keep-simpys-order-of-events.md)). How should the port's parity be measured?

## Decision Drivers

- A slip in a 10,000-line port should fail a test that names the field and the case.
- A statistical bar alone lets a slip hide inside its tolerance (the plan's own risk).
- The checks must run without Python once the Python engine is gone.
- Floating-point functions (`log`, `exp`, `pow`) may round apart in the last place
  (ADR-0047).

## Considered Options

- Replay: simulate in Java what the frozen engine simulated and compare the reports, with the
  statistical bar on top
- The statistical bar alone, as the plan set it
- Replay alone

## Decision Outcome

Chosen option: "Replay, with the statistical bar on top".

- **Replay.** Java simulates the designs the frozen engine simulated and compares the reports:
  counts, events processed and every integer exactly, every other number to a relative 1e-9
  (the golden files' tolerance), part by part, so a failure names the case and the JSON pointer.
  - `scripts/export_simulation_cases.py` records small designs, each reaching a feature (clients'
    timeouts and retries, client groups, patterns, faults, and each component as later slices
    port it), with the frozen engine's whole report; `SimulationCasesTests` replays them.
  - The golden files' 50 recorded runs (two per template) are replayed too, once every component
    they use is ported.
  - Per-component counters on fixed inputs (commits per flush, lock waits, redeliveries, fetch
    cycles) are fields of these reports, so they are checked exactly by the same replays, on
    designs made to reach them.
- **The statistical bar stays**, as the plan set it, recorded once from the frozen engine (its own
  decision) and reported in `docs/validation/java-parity.md`: it covers whole template runs, longer
  than the replays, where a last-place difference could in principle change the order of two
  events. The calibrated knees are compared as the plan says.
- **What a replay may not match**: a report is checked part by part as the port grows; a part
  the Java does not write yet is left out of the comparison and named in the test, never
  compared loosely.

### Consequences

- Good, because a slip fails a test at the field it changes, on a design small enough to read.
- Good, because the replays run in the ordinary build, in seconds, without Python.
- Good, because the plan's bar is still measured, and with a faithful simulator it should hold
  by a wide margin.
- Bad, because the Java simulator is held to the frozen engine's quirks, as the golden files
  already hold the rest of the engine; a quirk worth fixing is a listed departure, with a test.
- Bad, because a case recorded on Windows may differ on another platform's C library in the last
  place of a `log`; the tolerance absorbs it, and a flip of an event order (about 1e-16 per draw)
  would show as a failure to look into, not to tolerate.

### Confirmation

`SimulationCasesTests` replays `simulator/cases.json.gz`; with a counter broken on purpose it
fails at that counter (checked). The golden runs' replay and the statistical bar's report follow
in later slices of stage 4.

## Pros and Cons of the Options

### The statistical bar alone

- Good, because it needs no faithful order of events or random numbers.
- Bad, because a slip that moves a median by less than 2%, or a counter nobody summarizes, passes.

### Replay alone

- Good, because it is the strictest check.
- Bad, because the replays are short; the plan's bar covers whole template runs and is what the
  owner set.

## More Information

- [ADR-0020](0020-golden-files-of-the-frozen-engine.md): the golden files, whose recorded runs
  this replays.
- [The port proposal, stage 4](../proposals/java-25-port.md).
