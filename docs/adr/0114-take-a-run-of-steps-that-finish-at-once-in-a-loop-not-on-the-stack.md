---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0114. Take a run of steps that finish at once in a loop, not on the stack

## Context and Problem Statement

The kernel runs a simulated process as continuations: where a SimPy process yields an event, a Java
step calls `event.then(next)`, and `Simulation.after(delay, next)` runs `next` at once when the
delay is 0
([ADR-0013](0013-event-driven-simulation-kernel.md),
[ADR-0048](0048-keep-simpys-order-of-events.md)). "At once" means inside the call that finished:
one Java frame deeper for every step that takes no time.

A component that loops over its work by continuing from each item's finish goes deeper with every
item that finishes at once. A Kafka consumer that works 0 ms on each message (a constant
`consumerTimeMs` of 0 is valid) and calls nothing took a poll's messages one message deeper than
the last, and a poll's batches, one for each of its partitions, one batch deeper than the last; a
consumer that takes one message after another went one message deeper for each message of a
backlog it drained. 20,000 messages a second with a 250 ms fetch overhead put about 5,000 messages
in a poll, and the run failed with a `StackOverflowError`, a crash where a result was due; so did a
poll from 2,000 partitions, and a backlog that built behind an occasional slow message. Neither ADR
weighed the depth.

How should a component take a run of steps, each of which may finish at once or later?

## Decision Drivers

- No load, however many messages or batches it brings at once, may overflow the Java stack.
- The order of events, and so every recorded run's result digest, stays as it is.
- A component's code still reads as the Python engine's blocks it replaces.

## Considered Options

- (a) A loop for each run of steps: when a step finishes at once, the next starts in a loop
- (b) A trampoline in the kernel: every continuation that would run at once while another runs
  waits in a queue, run when the outer one returns
- (c) A larger thread stack for the simulation

## Decision Outcome

Chosen option: (a), "a loop for each run of steps", through one kernel helper, `Steps.each(items,
step, done)`: it takes a step on each item in turn, and when a step finishes at once (while it is
being started), it starts the next in its own loop rather than from inside the finish; a step that
finishes later resumes the loop from its finish. The consumers' three runs of steps use it: the
messages of a batch and the batches of a poll (`PollingConsumer`), and the messages a consumer takes
one after another from its partitions (`Partitions`).

It keeps the order of events because a step must finish exactly once and last: its finish is the
last thing each frame does, in tail position, so nothing runs between a step's finish and the start
of the next, whether the next starts inside the finish, as before, or once the finish returns, as
now.

### Consequences

- Good, because a consumer's batches, polls and backlogs are simulated at any size: the depth no
  longer grows with the load.
- Good, because no event moves: every recorded run gives the same result digest.
- Neutral, because the other continuations that run at once still go one frame deeper each. A
  database operation's disk reads stop at 50 (`sstablesPerRead`). A request's calls through parts
  that take no time do not: a line of about 200 such services, the most a design may have, joined
  by links that take no time, overflows the stack, about 30 frames a hop. That chain predates this
  decision and is outside it; option (b) would cover it, at the price below.
- Bad, because the loop is right only while a step's finish comes exactly once and last. `Steps`
  says so, and the recorded runs would show a step whose finish did not.

### Confirmation

`ZeroDelayTests` simulates a Kafka consumer that works 0 ms on 5,000 messages a poll, one that polls
2,000 partitions, and one that drains a backlog at 0 ms a message, and each processes every message;
before, each run overflowed the stack. `StepsTests` takes a million steps that finish at once in
their order, steps that finish later from their finish, and none. The engine's recorded and golden
runs give the same results and digests.

## Pros and Cons of the Options

### (b) A trampoline in the kernel

- Good, because it would bound the depth of every continuation at once, the request's calls through
  parts that take no time too.
- Bad, because it changes when a continuation runs relative to the code after the call that ran
  it, wherever that code is not in tail position; the order of events would move for any such
  step, and with it the recorded runs.

### (c) A larger thread stack

- Good, because no code changes.
- Bad, because it moves the limit instead of removing it: twice the stack holds twice the batch.

## More Information

- [ADR-0013](0013-event-driven-simulation-kernel.md) and
  [ADR-0048](0048-keep-simpys-order-of-events.md), which this narrows: a step that finishes at once
  still runs at once, in its loop.
