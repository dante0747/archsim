# archsim-api

ArchSim's simulation workers. The API (`archsim-app`, in Java) queues runs in PostgreSQL;
`archsim-api worker` processes take them, simulate each with the engine in a process of its own,
and write the progress and the report back. Run as many as you need, on any host that reaches
the database ([docs/api.md](../../docs/api.md#background-runs-and-several-instances)).

```bash
uv run archsim-api worker --slots 4   # runs at once (default: ARCHSIM_MAX_CONCURRENT_RUNS)
uv run archsim-api seed               # one project per example, in the default workspace
uv run archsim-api migrate            # Alembic, up to revision 0008, where Flyway takes over
```

The package also holds the SQLAlchemy models and Alembic migrations the schema started from:
Flyway's `V1` in `archsim-app` reproduces their revision 0008, and a test checks they still
agree ([ADR-0032](../../docs/adr/0032-flyway-takes-over-from-alembic.md)). Its HTTP API, which
the Java API replaced in stage 2 of the [port](../../docs/proposals/java-25-port.md), is gone; the
workers move to Java in stage 4. A worker never migrates the database: start the API first.

Settings are `ARCHSIM_*` environment variables, or a `.env` file in the working directory
([.env.example](../../.env.example)): the database (`ARCHSIM_DATABASE_URL`, compose's
PostgreSQL by default), the runner and slots, leases, the simulation limits, and
`ARCHSIM_AUTH_MODE` / `ARCHSIM_RATE_LIMITS` for the runs at once per workspace kind.
