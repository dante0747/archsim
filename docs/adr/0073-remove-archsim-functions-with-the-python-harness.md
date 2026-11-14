---
status: proposed
date: 2026-11-14
decision-makers: Majid Abarghooei (owner)
---

# 0073. Remove `archsim functions` with the Python harness

## Context and Problem Statement

[ADR-0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md) gave the Python
benchmark harness the Java engine's functions (its constants, the load curve and CPU fits, the
memory fit, the machine's speed, the report version) through one long-lived process, the CLI's
hidden `archsim functions`, which reads a JSON request a line and answers a line. Stage 7 ported
the harness to `archsim-benchmarks`, which calls the engine in process
([ADR-0068](0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md)), and its last
slice deletes the Python harness. Nothing else calls the command. Does the CLI keep it?

## Considered Options

- Remove `archsim functions` and its tests
- Keep it, hidden, for scripts outside the repository

## Decision Outcome

Chosen option: "Remove `archsim functions` and its tests", because its one caller is gone, and
a hidden command no one calls is code to maintain, test and keep word for word with nothing to
show for it. The commands people run, and their output contract
([ADR-0055](0055-the-java-clis-output-contract.md)), are unchanged: the command was hidden from
the CLI's help and documented as the harness's alone.

### Consequences

- Good, because the CLI holds only what people run, and the engine's fits have one way in, the
  engine's own classes, which the harness calls in process.
- Bad, because a script outside the repository that called it would stop working; none is known,
  and the tag `cpython-recorders` keeps the Python harness that did, with the CLI it called.

### Confirmation

The CLI's tests: `archsim functions` is an unknown command, and the remaining commands' recorded
answers are unchanged. The harness's tests and its parity task, which call the engine in process.

## More Information

- Supersedes [ADR-0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md) once
  accepted.
