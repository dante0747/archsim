---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0005. Port ArchSim to Java 25 in stages

## Context and Problem Statement

ArchSim's back end is Python:

| Part | Size |
| --- | --- |
| Engine | 28.8k lines, plus 9.6k of tests |
| FastAPI API | 7.9k lines, plus 4.1k of tests |
| MCP server | 0.5k lines |
| CLI and the `archsim check` GitHub Action | — |
| Benchmark harness | 11.8k lines |

The web app is TypeScript. The owner works in Java and wants to maintain ArchSim in it.

A port has to keep what customers rely on (the design format, the HTTP API, and the accuracy
evidence) and must not leave `main` broken for weeks.

Should ArchSim move to Java, and if so, how?

## Considered Options

- Stay on Python
- Rewrite the back end in Java in one go, then switch over
- Port in stages, replacing Python one part at a time, after each Java part passes a parity check
  against the Python version (the strangler pattern)

## Decision Outcome

Chosen option: "Port in stages", because each step can be verified against working code, `main`
works and deploys throughout, and a mistake is found in the stage that made it.

The principles:

1. **Strangler, not big bang.** Python code is deleted only after its Java replacement has passed
   parity.
2. **Parity before replacement.** Each stage has an exit check against the Python version.
3. **No model changes during the port.** A port that also changes behaviour can't be verified
   ([ADR-0017](0017-freeze-python-engine-during-port.md)).
4. **Mainstream and documented.** A Java developer should find the code where they expect it, built
   and checked the way they're used to.

The stages:

| Stage | Scope |
| --- | --- |
| 0 | Foundations: the build, CI, golden files and black-box API tests |
| 1 | The design model and schema |
| 2 | The API on Spring Boot, with the engine still in Python |
| 3 | The engine's deterministic parts |
| 4 | The simulation kernel and components |
| 5 | The CLI, the MCP server and the GitHub Action |
| 6 | Clean-up |
| 7 | The benchmark harness, once the rest is done ([ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md)) |

**Unchanged:** the web app, the design document ([ADR-0002](0002-design-document-is-the-contract.md)),
the HTTP API contract, and the PostgreSQL tables. Flyway takes over at the current schema revision.

### Consequences

- Good, because every stage ends with evidence: golden files that must match exactly (numbers to a
  relative 1e-9) for the deterministic parts, and a statistical bar for the kernel.
- Good, because the work can stop after any stage with a working product.
- Bad, because Python and Java run side by side for a while, which adds complexity until each stage
  removes its Python.
- Bad, because the effort is estimated at 6–10 weeks, plus overnight parity runs on the benchmark
  laptop.
- Bad, because the Java engine can't reproduce Python's results bit for bit: tiny floating-point
  differences change the order of events. Its accuracy numbers are labelled re-simulated, never
  prospective.

### Confirmation

- **Stage 4's parity bar:**
  - for every template scenario, with 20 seeds, Java's median p50, p95, throughput and
    utilisation fall inside Python's seed range and within 2% of Python's median;
  - for every kept benchmark calibration, the re-simulated knee is within 2%;
  - internal counters match per component on fixed inputs.
- The parity report goes in `docs/validation/java-parity.md`.

## More Information

- [The port proposal](../proposals/java-25-port.md), with durations and risks
- The decisions that shape the Java code: ADR-0006 to ADR-0016
