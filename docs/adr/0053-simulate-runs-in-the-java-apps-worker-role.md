---
status: proposed
date: 2026-11-09
decision-makers: Majid Abarghooei (owner)
---

# 0053. Simulate runs in the Java app's worker role, one thread per run

## Context and Problem Statement

Stage 4 of [the port plan](../proposals/java-25-port.md) ends with the Java simulator running
the runs the API queues. Today the Java API queues them in `simulation_runs` and the Python
workers (`archsim-api worker`) claim and simulate them on the frozen engine, one child process
per run ([ADR-0003](0003-postgresql-run-queue-with-leases.md)).
[ADR-0014](0014-one-deployable-application-with-roles.md) already says the Java app starts as
`worker`; nothing does yet. The Java simulator replays the frozen engine's runs number by
number ([ADR-0049](0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)), but it
reports no progress and cannot be cancelled. How does a run get simulated in Java, with the same
queue, leases, progress, cancels and outcomes, so the Python worker and the engine service can
be deleted?

## Decision Drivers

- The queue's protocol stays as the code has it (an advisory lock, a fair pick per workspace,
  leases, heartbeats, the reaper), so nothing that reads the table changes.
- A run that is cancelled, outlives its limits or loses its lease stops, and its slot frees.
- A crash, an out-of-memory error or a killed worker fails or requeues its runs as today.
- The worker is the Java app, as ADR-0014 decided: one image, no web layer, never migrating.

## Considered Options

- Threads in the worker's JVM, one platform thread per run, the simulator cooperating with
  cancels.
- A child JVM per run, as the Python worker's child process.
- Keep the Python worker, calling the Java simulator through the CLI.

## Decision Outcome

Chosen option: threads in the worker's JVM, because the reasons for a process per run were
Python's: the GIL kept one interpreter on one core, and a run could only be stopped by killing
it. A JVM runs one simulation per core, and the Java simulator checks a cancel as often as it
checks its wall clock, so a thread per run stops when asked, without a second JVM's start-up
and heap for every run. Runs get a pool of platform threads of their own, as many as
`ARCHSIM_MAX_CONCURRENT_RUNS`, never the virtual threads Spring's executors use in this app
([ADR-0007](0007-spring-boot-4.md)): a simulation never blocks, so on a virtual thread it would
hold a carrier for the whole run, and enough of them would starve the heartbeat and the reaper
until the leases ran out.

- **The engine reports progress and honours cancels.** `Simulator.run` takes a run observer
  beside the request (the request stays a plain value): progress after each chunk it simulates
  (at most 200) and 1.0 when the report is built, and a cancel check wherever the wall clock is
  checked (every 4,096 events and at each chunk's end), which ends the run with a
  `SimulationCancelledException`. A sampled run reports the sample's progress, then the full
  run's when the sample is not steady, as the frozen engine does. The observer never changes
  the run: it draws no random numbers and moves no event, so an observed run's report and
  digests are an unobserved one's.
- **The `worker` role** (`Role.WORKER`) starts without the web layer and keeps running until
  stopped (a role says whether it exits once started, rather than every role without a web
  layer exiting); it never migrates the database (`ARCHSIM_AUTO_MIGRATE` applies to `api`
  alone). Instead it checks at start-up that Flyway's history is at the version its build
  expects, and stops at once if not ("the database is at V…, this worker needs V…; start the
  API first", or, for a database a later release migrated, "start a worker of the API's
  release"), rather than meet a column it does not know in the middle of a run. Its beans
  live in a new feature package, `io.archsim.app.worker`, under the `worker` profile: `runs`
  keeps the API's side of `simulation_runs` (queueing, reading, asking for a cancel) and
  `worker` owns the rest (the lease columns, progress and the outcome), with `job_workers`.
- **The claim is the Python worker's, ported exactly:** `pg_advisory_xact_lock`, a
  `row_number()` pick per workspace in queue order, the per-workspace caps, then the lease.
  The caps come from the app's `WorkspaceLimits`, so the API's admission and the worker agree;
  a workspace of a kind the app does not know gets the personal hosted cap, where Python gave
  none.
- **Leases, heartbeats and the reaper keep their timings and their settings' names**
  (`ARCHSIM_LEASE_SECONDS`, `ARCHSIM_HEARTBEAT_SECONDS`, `ARCHSIM_REAP_SECONDS`,
  `ARCHSIM_CANCEL_GRACE_SECONDS`, `ARCHSIM_MAX_CONCURRENT_RUNS`, its default the cores less
  one): a lease of 30 s, renewed every 2 s with the cancel pickup and the `job_workers` row; a
  reap every 10 s; progress written at most once a second; an outcome written only by the
  worker that holds the lease, in the shapes the API reads today (the report, its summary, both
  digests, the wall time; the error shapes, crashes named by their Java type).
- **A run that does not stop:** a cancel or a lost lease asks the simulator to stop; past the
  hard deadline (the run's wall-clock limit and 30 s) the worker fails the run with today's
  message, its observer keeps asking the run to stop, and its slot stays held until the thread
  ends.
- **Memory is sized per run.** Runs share the worker's heap, so its maximum is set
  deliberately, in compose and in the image's JVM flags, from the heap one run needs at most
  and `ARCHSIM_MAX_CONCURRENT_RUNS`; the rule is in the app's README. The JVM runs with
  `-XX:+ExitOnOutOfMemoryError`, checked by a test of the worker's command line, so an
  out-of-memory error stops the worker and the reaper requeues its runs once, then fails them
  as today.
- **A stop** (Spring's lifecycle, within compose's 30 s grace) stops claiming, asks each run to
  stop and requeues it without counting an attempt, as the Python worker does.
- **The worker is ready** when it logs `worker <id> takes up to N run(s) at once`, the line the
  black-box suite and the end-to-end launcher wait for.
- **Calibration replays** call the Java simulator in-process, and `POST /simulate`, the engine
  service and the API's client for it are deleted
  ([ADR-0045](0045-replay-calibrations-through-an-interim-simulate.md)'s exit).
- **The cut-over is at once:** compose, the black-box suite and the end-to-end launcher start
  the Java worker in one change, and the Python worker is deleted in the next. The two never
  share a deployment's queue, so the engine version the API writes at queueing stays right.
  Pruning the rate-limit counters, which the Python reaper did, moves to the API, which owns
  them.

### Consequences

- Good, because one image and one language run the whole service; the engine service, the
  Python worker's process management and its orphan guards are gone.
- Good, because a run starts at once: no interpreter or JVM to start, no engine to import.
- Bad, because a run shares the worker's heap: one run that exhausts it stops every run of
  that worker, which the reaper then requeues once. The heap is sized for the most runs the
  worker takes at once (`ARCHSIM_MAX_CONCURRENT_RUNS`) times the heap one run needs at most,
  with room for the app; raising the runs at once means raising the heap with it.
- Bad, because a run that ignores a cancel holds its slot until it ends; only a fault in the
  simulator could make it.
- Neutral: ADR-0003's process per run, and every document that says the queue uses
  `SKIP LOCKED` (it never did), get a dated note.

### Confirmation

The engine's tests of the observer (the frozen engine's progress for the same designs, where
a cancel stops a run, and the same report observed or not); the queue's tests, ported from the
Python worker's to embedded PostgreSQL (concurrent claims never hand a run out twice; caps
across workers; fairness; release and reap); the worker's, with a stand-in simulator that
cooperates, ignores cancels or runs until cancelled, and one real run each of full, sampled
and seeded; its start-up refusing a database at another schema version; a check that its
command line carries `-XX:+ExitOnOutOfMemoryError` and its heap; the black-box suite and the
end-to-end tests against the Java worker.

## Pros and Cons of the Options

### Threads in the worker's JVM

- Good, because it needs no process management, pipes or orphan guards.
- Good, because a run starts without a second JVM.
- Bad, because isolation is cooperative: an out-of-memory error or a stuck run affects the
  whole worker.

### A child JVM per run

- Good, because a run can be killed, and its memory is its own.
- Bad, because every run pays a JVM's start-up and a heap, and the worker needs the process
  management and orphan guards the Python worker has.

### Keep the Python worker, calling the Java CLI

- Good, because the worker does not change.
- Bad, because Python stays in the service, and progress and cancels would need a protocol
  between the two.
