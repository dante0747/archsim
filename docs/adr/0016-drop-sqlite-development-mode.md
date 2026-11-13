---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0016. Drop the SQLite development mode

## Context and Problem Statement

The Python API targets PostgreSQL but defaults to a SQLite file (`sqlite:///./archsim.db`), so it
runs locally with no setup. Supporting both databases costs code in several places:

- **The run queue:** it claims runs with `BEGIN IMMEDIATE` instead of
  `SELECT … FOR UPDATE SKIP LOCKED`, which is safe on one host only
  ([ADR-0003](0003-postgresql-run-queue-with-leases.md)).
- **The schema:** primary keys need a SQLite variant, and connections need SQLite pragmas.
- **Tests:** the API tests run on SQLite by default, and on PostgreSQL only when
  `ARCHSIM_TEST_DATABASE_URL` is set.

Should the Java application keep a SQLite mode?

## Considered Options

- Drop it: PostgreSQL everywhere, in Docker locally and in Testcontainers for tests
- Keep it

## Decision Outcome

Chosen option: "Drop it", because:

- one SQL dialect means each Flyway migration and each queue query is written once;
- tests run on the database that production uses;
- Docker is already needed for `docker compose up`, the benchmark stacks and Testcontainers.

**Status (10 Nov 2026):** by the time this was written the PostgreSQL claim no longer used
`SELECT … FOR UPDATE SKIP LOCKED`: since W8's workspace caps (`d892b19`, 27 Oct) it took a
transaction advisory lock (`pg_advisory_xact_lock`) and ordered the runs with `row_number`, as the
Java worker does ([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)).

### Consequences

- Good, because there is no second, weaker claim path in the queue, and no dialect switches in the
  persistence code.
- Good, because a test that passes has passed on PostgreSQL.
- Bad, because running the application or its tests locally needs Docker or a local PostgreSQL.
- Bad, because the zero-setup local start (`archsim-api serve` with a SQLite file) goes away when
  the Python API is removed.

### Confirmation

The Java build has no SQLite driver, and its persistence tests use Testcontainers.

## More Information

- [ADR-0008](0008-jdbcclient-and-flyway.md) (data access)
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) (today's development setup)
