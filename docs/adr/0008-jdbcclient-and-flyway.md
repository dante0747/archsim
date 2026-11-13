---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0008. Access PostgreSQL through `JdbcClient`, with Flyway migrations

## Context and Problem Statement

The Java application uses the same PostgreSQL tables as the Python API, with no data migration.
The tables are managed by Alembic today.

The most important queries belong to the run queue ([ADR-0003](0003-postgresql-run-queue-with-leases.md)):

- a claim that is a `SELECT … FOR UPDATE SKIP LOCKED` CTE followed by `UPDATE … RETURNING`;
- updates that only act while the worker holds the run's lease.

The other tables (projects, runs, users, workspaces, tokens) need simple reads and writes.

How should the Java code access the database, and how should its schema change?

## Considered Options

- Spring's `JdbcClient` with explicit SQL, and Flyway
- JPA/Hibernate with Flyway
- jOOQ with Flyway

## Decision Outcome

Chosen option: "`JdbcClient` with explicit SQL, and Flyway", because:

- the queue and lease queries are SQL whichever library runs them, and they read best as plain
  SQL;
- explicit SQL shows exactly what each repository does, with no ORM session, no lazy loading and
  no generated queries to reason about;
- `JdbcClient` is part of Spring, so it adds no dependency.

Flyway takes over from Alembic at the current schema revision. Existing databases carry on without
a data migration.

**Status (10 Nov 2026):** by the time this was written the claim no longer used `SKIP LOCKED`:
since W8's workspace caps (`d892b19`, 27 Oct) it took a PostgreSQL transaction advisory lock
(`pg_advisory_xact_lock`) and ordered the queued runs with `row_number`. The Java worker ports that
claim ([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)), in plain SQL through
`JdbcClient` (`ClaimRepository`).

### Consequences

- Good, because what runs against the database is what the code says.
- Good, because the SQL can be reviewed, explained and tuned as it is.
- Bad, because simple create, read, update and delete operations need hand-written SQL and row
  mapping that JPA would generate.
- Bad, because a broken query fails in a test, not at compile time as it would with jOOQ. Tests run
  against a real PostgreSQL (Testcontainers) to make up for it.

### Confirmation

Repository and queue tests run against PostgreSQL in Testcontainers. The black-box API suite checks
that the Java and Python APIs can share one database (stage 2 of
[ADR-0005](0005-port-to-java-25-in-stages.md)).

## More Information

- [ADR-0016](0016-drop-sqlite-development-mode.md): one SQL dialect only
- Flyway's baseline command marks an existing database as being at a given version, which is how it
  takes over from Alembic.
