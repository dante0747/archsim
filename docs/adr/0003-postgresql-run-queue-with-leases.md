---
status: accepted
date: 2026-10-27
decision-makers: Majid Abarghooei (owner)
---

# 0003. Run simulations from a PostgreSQL queue with leases

*Recorded retroactively on 2026-11-02. The decision dates from commits `a32ac7c` and `9733ae0`
(27 Oct 2026), which added the queue (migration 0003) and the worker that uses it.*

## Context and Problem Statement

A simulation takes from seconds to minutes. The API has to answer at once, keep runs through
restarts, work with more than one API instance, and accept a cancel request on any of them. A
simulation that crashes should fail only its own run.

The first version ran simulations inside the API process (`ThreadJobRunner`, `ProcessJobRunner`
with a shared process pool). On start-up it failed every run that was still active, whichever
process had owned it.

How should runs be queued and executed?

## Considered Options

- In-process runners inside the API, as in the first version
- The run row in the database is the job: workers claim queued runs under a lease and renew it

## Decision Outcome

Chosen option: "The run row in the database is the job", because the database is already shared
by every instance. The queue adds no new infrastructure, and a lease says who owns a run at any
moment.

How it works (`services/api/src/archsim_api/jobs/`):

- **Claim:** `claim` takes the oldest queued runs. On PostgreSQL it uses a
  `SELECT … FOR UPDATE SKIP LOCKED` CTE, then `UPDATE … RETURNING`. On SQLite it uses
  `BEGIN IMMEDIATE`, which is safe on one host only.
- **Lease:** a run's `lease_owner` and `lease_expires_at` are renewed with each heartbeat (every
  2 s), and cancel requests are picked up at the same time.
- **Ownership:** progress and outcomes are written only while the worker holds the lease, so a
  worker that lost a run can't overwrite the one that took it over.
- **Expired leases:** they are reaped every 10 s. The run is requeued after a first attempt,
  failed after a second, and cancelled if a cancel was requested.
- **Isolation:** each run simulates in a process of its own (`jobs/child.py`), so a crash fails
  only that run.

**Status (10 Nov 2026):** the queue works as decided here, but its worker is `archsim-app worker`,
in Java, since stage 4 of the port ([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)),
and `jobs/` went with the Python worker. A run simulates on a thread of its worker, not in a process
of its own, so a run that exhausts the worker's heap stops its other runs too; the reaper settles
them like the runs of any worker that stops: requeued once, then failed. The claim above is its
first version (`a32ac7c`). The same day W8's workspace caps (`0b4550c`) replaced `SKIP LOCKED`:
to count each workspace's running runs and pick consistently, claims take turns under a
transaction advisory lock (`pg_advisory_xact_lock`), and a `row_number` pick takes every
workspace's oldest queued run before any workspace's second. The Java worker ports that claim.

### Consequences

- Good, because API instances and workers scale independently, with nothing to run but the
  database.
- Good, because any instance can report progress or cancel a run, since both go through the
  database.
- Bad, because queue throughput is bounded by the database. That's fine for runs that take
  seconds, but not for a high-volume job stream.
- Bad, because SQLite needs a second claim path that is limited to one host
  ([ADR-0016](0016-drop-sqlite-development-mode.md) removes it from the Java version).

## More Information

- [`docs/architecture.md`, "Run lifecycle"](../architecture.md#run-lifecycle)
- The Java port keeps the queue as plain SQL ([ADR-0008](0008-jdbcclient-and-flyway.md)), served by
  the `worker` role of one application ([ADR-0014](0014-one-deployable-application-with-roles.md)).
