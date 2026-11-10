---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0032. Flyway takes over from Alembic at revision 0008, proven by a schema snapshot

## Context and Problem Statement

[ADR-0008](0008-jdbcclient-and-flyway.md) moves the schema to Flyway, which takes over from
Alembic at the current revision. During stage 2 the Python and Java APIs must share one database:
a database Alembic made must keep working under Flyway, an empty one must come out the same
whichever API migrates it, and nothing may be lost if an operator starts the Java API on a
database that is not at the expected revision. How does Flyway take over, and how do we know its
first migration builds what Alembic builds?

## Decision Drivers

- No data migration: existing databases carry on as they are.
- The Python API keeps working on a database the Java API has migrated, until it is deleted.
- A wrong or partly migrated database is refused with a message that says what to do, never
  "repaired".
- The claim "same schema" is checked by a test, not by reading two migration histories.

## Considered Options

- A Flyway `V1` that reproduces Alembic's revision 0008, a baseline at `1` for databases Alembic
  made, and a JSON snapshot of the schema compared in a test
- Flyway's baseline only, with no `V1` (empty databases still migrated by Alembic)
- A `V1` written from scratch to "the schema we want", with a data migration from Alembic's

## Decision Outcome

Chosen option: "a `V1` that reproduces revision 0008, a baseline, and a schema snapshot", because
it is the only one that needs no data migration and lets either API set up an empty database.

- `V1__schema_at_alembic_0008.sql` creates exactly the tables, constraints, indexes and sequences
  Alembic's head (0008) creates, seeds the same local user, default workspace and owner
  membership, and writes `alembic_version = '0008'`, so the Python API sees a database at its
  own head.
- On a database with tables but no Flyway history, Flyway records a baseline at version `1`
  (`baselineOnMigrate`). A `BEFORE_BASELINE` callback (`AlembicTakeover`) first reads
  `alembic_version`: it lets the baseline through only at revision 0008, and otherwise stops with
  a message naming the revision found and the command that brings it to 0008. `alembic_version`
  is kept, not dropped, while the Python API exists.
- `parity/schema/describe.sql` describes the public tables (columns in order with type,
  nullability and default; constraints; indexes; sequences) as one canonical JSON document.
  `scripts/schema_snapshot.py` migrates a fresh database with Alembic and writes that document to
  `parity/schema/alembic-0008.json`. `MigrationTests` migrate a fresh database with Flyway,
  describe it with the same query and expect the same document.
- The `api` role migrates at start unless `ARCHSIM_AUTO_MIGRATE=false`; the `migrate` role
  migrates and exits ([ADR-0014](0014-one-deployable-application-with-roles.md)).
- The schema-snapshot test migrates only to `V1` (target version `1`), so a later `V2` doesn't
  break the comparison.

**Status (5 Nov 2026):** the FastAPI app is deleted. Alembic survives only in `services/api`,
with the Python workers, until stage 4: `archsim-api migrate` brings an older database to
revision 0008 for Flyway to take over, and the workers' tests and `scripts/schema_snapshot.py`
build their schema with it.

**Status (10 Nov 2026):** the Python workers are deleted
([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)). `services/api` keeps the models,
the Alembic migrations, `archsim-api migrate` and `seed` until stage 6, and the migration tests and
`scripts/schema_snapshot.py` still build their schema with it.

### Consequences

- Good, because a database moves between the APIs with no data migration and no downtime.
- Good, because a schema difference between `V1` and Alembic fails a test that names the table and
  column.
- Good, because a database at another Alembic revision is refused before Flyway writes anything.
- Bad, because `V1` copies Alembic's schema, quirks included; changing them is a later migration
  (`V2`…), after the FastAPI API is deleted.
- Bad, because a schema change made in Alembic during stage 2 needs a new snapshot and a matching
  Flyway migration. The engine and API freeze makes that unlikely.

### Confirmation

`MigrationTests` (archsim-app) run on a real PostgreSQL ([ADR-0033](0033-embedded-postgresql-for-tests.md)):
an empty database matches the snapshot; a database at Alembic 0008 is baselined and keeps its rows;
one at another revision, or with tables and no `alembic_version`, is refused with its message.

## More Information

- [ADR-0008](0008-jdbcclient-and-flyway.md) chose Flyway; this ADR says how it takes over.
- [parity/README.md](../../parity/README.md) describes the schema snapshot.
- When the FastAPI API is deleted (stage 2's last slice), a later migration may drop
  `alembic_version`.
