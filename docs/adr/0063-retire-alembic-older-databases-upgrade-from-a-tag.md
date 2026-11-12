---
status: proposed
date: 2026-11-12
decision-makers: Majid Abarghooei (owner)
---

# 0063. Retire Alembic: a database below revision 0008 upgrades first from the tag `alembic-0008`

## Context and Problem Statement

Flyway took over ArchSim's schema at Alembic's revision 0008
([ADR-0032](0032-flyway-takes-over-from-alembic.md)). Its `V1` builds what that revision built,
and `AlembicTakeover` lets Flyway baseline a database Alembic made only at that revision. A
database at an older revision is refused, with a message that says to run
`uv run archsim-api migrate` from `services/api`. `services/api` holds nothing else now: the
SQLAlchemy models, Alembic's migrations 0001 to 0008, that command and their tests. Stage 6 of the
[port](../proposals/java-25-port.md) removes Python, except the benchmark harness. So how does a
database below 0008 reach Flyway once `services/api` is gone? And what becomes of the schema
snapshot that `scripts/schema_snapshot.py` recorded with Alembic, which `MigrationTests` compares
`V1` with?

Revision 0008 landed on 27 Oct 2026, three days after the first commit, and every start of the
API since has migrated its database to it. So a database below it was made before 27 Oct and has
not been opened since.

## Decision Drivers

- No Python in what ArchSim ships or needs to run.
- No database is lost: an old one still has a way to reach Flyway, which its refusal names.
- No code is kept for a case nobody is likely to meet.
- `MigrationTests` keep proving that `V1` builds what Alembic built.

## Considered Options

- Delete `services/api`; a database below 0008 upgrades first with `archsim-api migrate` from a
  git tag on the last commit that has it, named in the refusal
- Port Alembic's revisions 0001 to 0007 to Flyway, as migrations before `V1`
- Keep `services/api` until stage 7, when the harness leaves Python too

## Decision Outcome

Chosen option: "delete `services/api`, and upgrade from a tag", because it removes the last
Python ArchSim runs and still gives an old database a way to reach Flyway.

- The annotated tag `alembic-0008` marks commit 5def7c5 on main, which has `services/api` at
  revision 0008 and `scripts/schema_snapshot.py`. Its Flyway `V1` and takeover are today's.
- `AlembicTakeover` refuses a database at another revision in the same words, and then says:
  check out the tag `alembic-0008`, run `uv sync` and `uv run archsim-api migrate` there to bring
  the database to 0008, then migrate again with this version.
  [`archsim-app`'s README](../../archsim-app/README.md) says the same.
- `services/api` goes, and so do its tests, CI's job for them (`Migrations on PostgreSQL`), and
  its member of the uv workspace.
- `parity/schema/alembic-0008.json` becomes data recorded once, like the frozen engine's answers
  in stage 5 ([ADR-0020](0020-golden-files-of-the-frozen-engine.md)). `scripts/schema_snapshot.py`,
  which needs Alembic, goes. `describe.sql` stays, because `MigrationTests` describe Flyway's
  database with it. [`parity/README.md`](../../parity/README.md) says how to record the snapshot
  again from the tag.
- The `alembic_version` table stays. `AlembicTakeover` reads it, and dropping it is a migration
  with nothing to gain until the takeover goes.

### Consequences

- Good, because nothing ArchSim ships or runs needs Python; uv and Python are needed only for the
  harness and the repository's scripts.
- Good, because a database at any Alembic revision still reaches Flyway, and its refusal says
  how.
- Bad, because that one upgrade needs Python 3.11 or later, uv and a checkout of the tag.
- Bad, because the snapshot can no longer be recorded again from the working tree. It never needs
  to be: Alembic's revision 0008 doesn't change.
- Bad, because `AlembicTakeover` stays for as long as a database Alembic made may still turn up.

### Confirmation

`MigrationTests` check that a database at an older revision is refused before Flyway writes
anything, with a message that names the tag. An empty database still matches the snapshot.

## Pros and Cons of the Options

### Port revisions 0001 to 0007 to Flyway

- Good, because the Java app would upgrade any database Alembic made by itself.
- Bad, because it rewrites seven migrations, and their SQLAlchemy-generated steps, for databases
  that have not been opened since 27 Oct 2026.
- Bad, because Flyway would then need two baselines (an empty database starts at `V1`; an old one
  starts below it), which is hard to read and to test.

### Keep `services/api` until stage 7

- Good, because nothing changes for an old database.
- Bad, because ArchSim would keep a Python package, its CI job and its dependencies (SQLAlchemy,
  Alembic, psycopg) for that one case. The harness, which stage 7 ports, doesn't use them.

## More Information

- [ADR-0032](0032-flyway-takes-over-from-alembic.md): Flyway's takeover, which this keeps.
- [ADR-0064](0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md): what Python
  stays until stage 7.
- [ADR-0014](0014-one-deployable-application-with-roles.md): the `migrate` role.
