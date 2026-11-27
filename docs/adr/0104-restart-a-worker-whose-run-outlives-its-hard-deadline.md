---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0104. Restart a worker whose run outlives its hard deadline

## Context and Problem Statement

A worker simulates each run on one of its platform threads and asks it to stop through its
observer: on a cancel, on a lost lease, and at the run's wall-clock limit. The simulator looks at
the observer every 4,096 events and after each chunk
([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)). A run that does not look, because
it is busy inside one event, cannot be told anything. Today its outcome is written at the hard
deadline (the wall-clock limit and 30 s) and "its slot stays held until the thread ends"
([architecture.md](../architecture.md)): if the thread never ends, the slot is gone until someone
restarts the worker, and a worker whose every slot is gone claims nothing while its heartbeat
still says it is alive.

Java cannot stop a thread from outside: `Thread.stop` is gone, and an interrupt is one more thing
a busy loop does not look at. What should the worker do with a run thread that is still running
after its hard deadline?

## Decision Drivers

- A fault in one run must not take capacity away for good, and never without a trace.
- No run is lost or simulated twice because of another run's fault.
- The remedy is one the hosting kit already has: a process that exits is started again
  (`restart: unless-stopped`), and a dead worker's runs are requeued by the reaper.
- No new moving part per run: ADR-0053 chose threads over a child JVM per run for their cost.

## Considered Options

- Keep the slot held (today).
- Free the slot and leave the thread running.
- Exit the worker once the run has had a last grace, giving its other runs back first.
- Simulate every run in a child process that can be killed.

## Decision Outcome

Chosen option: "exit the worker once the run has had a last grace", because it is the only one
that takes the thread's processor back, and it uses what the worker already does when it is
stopped.

- At the hard deadline the worker writes the run `failed`, as today, and goes on asking it to
  stop.
- If the run's thread is still alive one cancel grace later (`ARCHSIM_CANCEL_GRACE_SECONDS`,
  10 s), the worker stops as it does on SIGTERM: it claims no more, gives its other runs back to
  the queue without counting an attempt, logs which run would not stop, and exits with a status
  other than 0.
- Whatever started the worker starts it again: compose's restart policy in the hosting kit, the
  process manager elsewhere. [deployment.md](../deployment.md) says a worker needs one.
- The run that would not stop is settled before the exit, so the new worker does not claim it.

The decision is not implemented by the change that proposes it: until it is accepted, the worker
behaves as ADR-0053 says.

### Consequences

- Good, because a stuck thread costs one restart, not a slot for the life of the worker.
- Good, because the other runs lose only their progress: they are requeued as on any stop.
- Good, because the exit is loud: a log line, a non-zero status, a restart count to alert on.
- Bad, because every run on that worker starts again, so one faulty design can delay others each
  time it is simulated. The sandbox limits (20 s of wall clock, 20 runs an hour) bound how often.
- Bad, because a worker with no supervisor stays down. That is today's outcome with every slot
  held, reached sooner and in the open.
- Bad, because it treats the symptom. A run that cannot be stopped is an engine fault each time,
  to be fixed where it is; budgets a run checks itself, such as a cap on the events it processes or
  the requests it holds in flight, would end more such runs in the engine itself.

### Confirmation

When implemented: a unit test of `Execution` and `Executions` with a simulator that never
returns (the outcome is written at the deadline, the others are given back, the exit is asked for
after the grace), and a black-box test that the worker's process ends and a second worker
finishes the queue.

## Pros and Cons of the Options

### Keep the slot held

- Good, because nothing changes and no healthy run is disturbed.
- Bad, because capacity only ever goes down, silently, and a processor stays busy for nothing.

### Free the slot and leave the thread running

- Good, because the queue keeps moving at once.
- Bad, because each stuck thread keeps a processor and its heap, so the worker runs more
  threads than it was sized for and slows every run it takes.
- Bad, because it needs a cap on abandoned threads, and at the cap the question returns.

### Simulate every run in a child process

- Good, because a process can be killed, and its memory is its own.
- Bad, because of a JVM start and a heap per run: ADR-0053 weighed it and chose threads.
- Bad, because it is a redesign of the worker for a case that should not happen.

## More Information

- [ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md): the worker, its threads, its
  hard deadline and `-XX:+ExitOnOutOfMemoryError`, the same remedy for a heap that is full.
- [ADR-0003](0003-postgresql-run-queue-with-leases.md): leases and the reaper that settle a dead
  worker's runs.
