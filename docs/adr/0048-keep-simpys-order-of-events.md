---
status: proposed
date: 2026-11-08
decision-makers: Majid Abarghooei (owner)
---

# 0048. Keep SimPy's order of events in the Java kernel

## Context and Problem Statement

[ADR-0013](0013-event-driven-simulation-kernel.md) chose an event-driven, single-threaded kernel
for the Java simulator, with each component's flow written as steps between events. The Python
engine runs on SimPy 4.1.2, whose order of events decides what happens when two things are due
at the same moment, and which events a run processes at all: a process starts with an urgent
event; a timeout is scheduled when it is made, whether or not anything waits for it; a resource
grants a waiting request only when a release is processed, or when a new request is made
meanwhile; a store serves its oldest getter the same way; a condition triggers itself one event
after the event that satisfies it. With ADR-0047 the Java simulator draws Python's random numbers.
How closely should the kernel follow SimPy's order, and in what form are component flows written?

## Decision Drivers

- A Java run of a Python seed should follow the Python run, so the recorded runs can be replayed
  and compared number by number, and per-component counters match on fixed inputs.
- Ties are common: constant service times, deterministic arrivals and zero delays put many events
  at the same moment, and their order changes queues, grants and timeouts.
- The kernel must stay small, fast and readable.

## Considered Options

- SimPy's order and semantics, ported: one heap ordered by time, priority and insertion, and
  SimPy's events, processes, conditions, resources and stores, quirks included
- A plain event queue ordered by time and insertion, with components' own simpler queues
- An existing Java discrete-event library

## Decision Outcome

Chosen option: "SimPy's order and semantics, ported", because only it lets a Java run follow the
Python run event for event.

- **Time** stays a `double` of simulated milliseconds, computed with the same expressions as the
  Python (`now + (until - now)` included), so the same floats compare alike.
- **The queue**: one `PriorityQueue` ordered by time, then priority (a routine starts URGENT, every
  other event is NORMAL), then the order events were scheduled in, as SimPy's heap of
  `(time, priority, id)` orders them.
- **Events** (`io.archsim.engine.kernel`): `Signal` (`env.event()`), `Promise` (an event with a
  value), `Timeout`, `Routine` (SimPy's process; `Process` would clash with `java.lang.Process`),
  `Condition` (`anyOf`, `allOf`), `Resource` with its requests and releases, `Store` with its puts
  and gets, each as SimPy 4.1.2 writes it: what it schedules, when, and the callbacks it adds and
  removes.
- **Processes are continuations.** Where a SimPy process yields an event, a Java step calls
  `event.then(next)`: the next step runs at once if the event has been processed, else when it is,
  exactly as SimPy's `_resume` continues. `yield from` becomes a call that takes the continuation;
  a routine's body is given the `finish` that schedules the routine's own event. Each step is named
  after the Python block it replaces.
- **Chunked runs**: `runUntil` processes events until a later time, checking every N events, as
  the engine's `_run_until`, and puts its stop event back on the queue ahead of everything else
  at that time, as SimPy does; the next chunk processes it first and counts it.
- **Results.** A routine has no value. Where the Python engine reads a process's value after
  waiting for it (a database's scatter read waits for every shard's operation and reads each
  result), the step that finishes the routine sets the result its waiters read, at the same
  moment the Python process returned it.
- **Left out**, because the engine uses none of it: failed events (`fail`, `ok`, defusing),
  interrupts and preemption, condition values, priority and filter stores and resources,
  bounded stores, and the check that a condition's events share one environment. A callback
  that can never act (a request's or a get's trigger of a queue that is always empty) is left
  out too: it schedules nothing, so the order is the same.

### Consequences

- Good, because a Java run and a Python run of the same seed process the same events in the same
  order, and the events-processed count is comparable.
- Good, because the kernel is about 400 lines with no dependency.
- Bad, because the quirks are kept on purpose (the oldest waiter is granted by a stranger's
  request; a condition costs an extra event); simplifying any of them changes results.
- Bad, because a component's flow reads as a chain of steps rather than top to bottom, as
  ADR-0013 already accepted.

### Confirmation

`scripts/export_kernel_cases.py` runs small SimPy programs (timeouts and process starts at equal
times, processes waiting on processes, resource contention, a request granting the oldest waiter,
queue timeouts with cancellation, stores, conditions, a condition nested in another, a chunked
run) and records what each saw,
when, and how many events it processed. `TimingCasesTests` and `QueueCasesTests` run each
program's Java twin and expect the same lines and counts.

## Pros and Cons of the Options

### A plain event queue

- Good, because it is simpler.
- Bad, because ties would resolve differently, so no Java run could be compared with a Python
  run event for event, and counters on fixed inputs would differ.

### An existing library

- Good, because the queue and resources are written already.
- Bad, because its own order of ties and its process model would differ from SimPy's in the very
  places that matter here.

## More Information

- [ADR-0013](0013-event-driven-simulation-kernel.md), [ADR-0047](0047-draw-python-random-streams-in-java.md).
- SimPy 4.1.2: `simpy/core.py`, `events.py`, `resources/base.py`, `resource.py`, `store.py`.
