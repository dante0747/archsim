---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0015. Keep the benchmark harness in Python until the port is complete

## Context and Problem Statement

The benchmark harness (`benchmarks/`, 11.8k lines of Python) measures how close ArchSim's
predictions come to real systems:

- it starts the benchmark stacks in Docker and drives them with k6;
- it calibrates the model's inputs and does the statistics;
- it writes the results;
- `report.py` generates `docs/validation/` and the data behind the `/accuracy` page.

Customers never run it. During the port it's the instrument that checks the Java engine against the
Python one ([ADR-0005](0005-port-to-java-25-in-stages.md)).

When, if ever, should the harness move to Java?

## Considered Options

- Keep it in Python permanently
- Port it to Java now, alongside the rest
- Keep it in Python during the port, calling the Java engine through `archsim-cli` from stage 4,
  then port it once the migration is complete (stage 7)

## Decision Outcome

Chosen option: "Keep it in Python during the port, then port it", in the owner's words: "Keep it now
with python, but when we make sure that the migration is complete migrate that to java as well."

- **During the port:** changing the measuring instrument while the thing it measures changes
  would make any difference hard to attribute. The harness stays as it is and only switches from
  importing the Python engine to calling the Java CLI.
- **After the port:** porting it leaves the repository in one language, which suits the owner and
  the project's aim of being a reference codebase.

### Consequences

- Good, because the port's parity checks and the accuracy evidence come from a harness that hasn't
  changed.
- Good, because the end state is one language for the whole back end and its tooling.
- Bad, because Python stays in the repository longer, with its own tooling and CI job.
- Bad, because stage 7 is extra work. The ported harness must reproduce the committed results'
  statistics before the Python one is removed.
- Bad, because calling the engine through its CLI starts a process per call, where today it is an
  import.

### Confirmation

Stage 7's exit check: the Java harness re-derives the inputs and re-simulates every kept run, with
the same results as the Python harness.

## More Information

- [`docs/validation.md`](../validation.md) (the protocol the harness implements)
- [ADR-0017](0017-freeze-python-engine-during-port.md) (the engine freeze during the port)
