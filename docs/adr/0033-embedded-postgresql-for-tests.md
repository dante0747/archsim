---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0033. Run the database tests on an embedded PostgreSQL, without Docker

## Context and Problem Statement

[ADR-0008](0008-jdbcclient-and-flyway.md) and [ADR-0016](0016-drop-sqlite-development-mode.md)
run the persistence tests against PostgreSQL in Testcontainers, which needs Docker. Some
contributors' machines, and the session that builds stage 2, cannot run Docker; without a
database, the migration, repository and queue tests and the black-box suite would not run before
a commit. How do the Java tests get a real PostgreSQL wherever `./gradlew build` runs?

## Decision Drivers

- A real PostgreSQL, the same major version as `docker-compose.yml` (16): no in-memory stand-in
  whose SQL differs.
- `./gradlew build` runs the database tests on any machine with a JDK, Docker or not.
- Nothing to install: dependencies come from Maven Central, like the rest of the build.
- Tests only: production and `docker compose` keep their PostgreSQL container.

## Considered Options

- Embedded PostgreSQL binaries from Maven Central (`io.zonky.test:embedded-postgres` with
  `embedded-postgres-binaries-bom` pinned to 16.x)
- Testcontainers, as ADR-0008 says
- H2 in PostgreSQL mode

## Decision Outcome

Chosen option: "embedded PostgreSQL binaries", because it gives a real PostgreSQL 16 server with
nothing but the Gradle dependency, so the database tests run everywhere the build runs.

- `TestDatabases` (archsim-app's tests) starts one server per test JVM on a free port and hands
  each test class a fresh database (`archsim_test_N`), so classes don't see each other's rows.
- The PostgreSQL version is pinned in the version catalog (`postgres-binaries`), next to the
  compose image's.
- The black-box suite needs only a PostgreSQL to create its databases in
  (`archsim.api.postgres`); such an embedded server, started by hand, serves for it too.

### Consequences

- Good, because the migration, repository and queue tests run on every `./gradlew build`.
- Good, because a test database starts in about a second, faster than a container.
- Bad, because the binaries (about 10 MB per platform) are downloaded once per machine.
- Bad, because extensions the image has and the binaries lack would not be testable; the schema
  uses none.
- Bad, because the embedded server takes the host's locale (`TestDatabases` sets none), so text
  ordering could differ from the compose image's.
- Bad, because PostgreSQL refuses to run as root, so the database tests fail in a container that
  runs the build as root.
- Neutral: Testcontainers stays a valid option for CI should a test need the exact image.

### Confirmation

`MigrationTests` and `ArchsimApplicationTests` start on the embedded server in `./gradlew build`.

## Pros and Cons of the Options

### Testcontainers

- Good, because it runs the exact compose image.
- Bad, because it needs Docker, so the database tests would not run on machines without it.

### H2 in PostgreSQL mode

- Good, because it is a plain JAR and fast.
- Bad, because its SQL is not PostgreSQL's (`FOR UPDATE SKIP LOCKED`, `jsonb`, partial indexes),
  so the tests would not prove what runs in production.

## More Information

This ADR refines the test setup of [ADR-0008](0008-jdbcclient-and-flyway.md) and
[ADR-0016](0016-drop-sqlite-development-mode.md); their decisions (JdbcClient, Flyway, PostgreSQL
only) stand.
