# archsim-api

The SQLAlchemy models and Alembic migrations ArchSim's schema started from, and two commands
that use them. Flyway's `V1` in `archsim-app` reproduces their revision 0008, and a test checks
they still agree ([ADR-0032](../../docs/adr/0032-flyway-takes-over-from-alembic.md)). The HTTP
API (stage 2 of the [port](../../docs/proposals/java-25-port.md)) and the simulation workers
(stage 4, [ADR-0053](../../docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)) are
`archsim-app`'s now; this package stays until stage 6.

```bash
uv run archsim-api seed               # one project per example, in the default workspace
uv run archsim-api migrate            # Alembic, up to revision 0008, where Flyway takes over
```

`seed` never migrates the database: start the API first. Settings are `ARCHSIM_DATABASE_URL`
(compose's PostgreSQL by default) and `ARCHSIM_EXAMPLES_DIR`, as environment variables or in a
`.env` file in the working directory ([.env.example](../../.env.example)).

`scripts/schema_snapshot.py` migrates a fresh PostgreSQL database with these migrations and
records the schema the Java migration tests compare Flyway's with
([parity/README.md](../../parity/README.md)).

## Tests

```bash
uv run pytest services/api/tests
ARCHSIM_TEST_DATABASE_URL=postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim_test \
  uv run pytest services/api/tests   # on PostgreSQL; every table in that database is dropped
```

The migrations build the models' schema, on SQLite and PostgreSQL, and processes that migrate
at once take turns.
