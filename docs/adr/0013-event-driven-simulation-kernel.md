---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0013. An event-driven simulation kernel

## Context and Problem Statement

The Python engine runs on SimPy:

- **Requests are coroutines.** Each simulated request is a generator, and components wait with
  `yield`.
- **About 90 wait points.** There are 76 in the component files (7.8k lines), 41 of them in
  `service.py` and `database.py`, and 15 more in `runtime.py`.
- **Few hard features.** No component uses SimPy's interrupts, and `AllOf` appears twice (in
  `database.py` and `message_queue.py`).
- **Ordering.** SimPy runs events by time, then priority, then insertion order, and the same seed
  must always give the same result digest.

Speed matters too. The Python engine simulates a few thousand requests per second per core
([`docs/performance.md`](../performance.md)). The target, a 6-million-request run in under 60 s,
took 55 minutes in full.

Java has no public coroutine API. The continuations behind virtual threads are internal to the JDK.

How should the Java kernel run components?

## Considered Options

- Event-driven: a priority queue of timed events, with components as state machines whose steps
  are callbacks
- Virtual-thread coroutines: each simulated process is a virtual thread, and the kernel lets
  exactly one run at a time
- An existing discrete-event library, such as DESMO-J or SSJ

## Decision Outcome

Chosen option: "Event-driven", for three reasons.

**Speed.** These are estimates, to be measured in stage 4:

- An event costs a heap operation and a method call, about 0.1–0.2 µs. With virtual threads, every
  wait is a hand-off from the component to the kernel and back through the JVM's scheduler, about
  1–2 µs.
- At 10–20 events per request, the 6-million-request run takes roughly 10–20 s against roughly
  3 minutes.

**Repeatable results.**

- A single thread orders events exactly as SimPy does: by time, then priority, then insertion
  sequence.
- With virtual threads the order is only fixed while every wait goes through the kernel. One stray
  lock or blocking call lets the operating system decide, which gives rare differences nobody can
  reproduce.
- An exception in a process thread has to be carried back to the kernel, and a stuck thread hangs a
  run instead of failing it.

**Debugging and profiling.** One thread has one stack, and a profiler shows where the time goes.

A library would bring its own process model and random streams, which makes parity harder to reach.

Each component gets its own seeded random stream. Each step of a component is named after the
Python block it replaces, so the two can be read side by side.

### Consequences

- Good, because the kernel is small, deterministic and fast enough for full-length runs.
- Bad, because components are rewritten from Python's top-to-bottom flow into steps, especially in
  the service and database models, so a step can change meaning without anyone noticing.
- Bad, because a component's flow is spread over several methods, which is harder to read than
  Python's version.

### Confirmation

- Stage 4's parity bar ([ADR-0005](0005-port-to-java-25-in-stages.md)) compares results over 20
  seeds per template scenario.
- It also compares per-component counters (commits per flush, lock waits) on fixed inputs, so a
  slip can't hide inside the tolerance.
- Speed is measured against [`docs/performance.md`](../performance.md).

## More Information

- [The simulation model](../simulation-model.md)
- [The port proposal](../proposals/java-25-port.md), stage 4
