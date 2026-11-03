# archsim-app

ArchSim's HTTP API on Spring Boot 4: stage 2 of the [Java port](../docs/proposals/java-25-port.md),
which replaces the FastAPI API in `services/api` endpoint for endpoint. It serves the same JSON,
cookies and error bodies on the same PostgreSQL tables, so the web app, the CLI and the
[black-box suite](../archsim-api-tests/README.md) work against either API, and both can share one
database while the port is under way. Simulations stay in Python until stage 4: runs go to the
Python workers through the shared queue, and the engine's synchronous calls (an analysis) go to
a small internal Python engine service, `services/engine-service`.

## Running it

```bash
./gradlew :archsim-app:bootJar
uv run archsim-engine-service --port 8001       # the engine's synchronous calls (ADR-0036)
java -jar archsim-app/build/libs/archsim-app.jar api --server.port=8000
java -jar archsim-app/build/libs/archsim-app.jar migrate
```

One jar runs in one of its roles, named by the first argument
([ADR-0014](../docs/adr/0014-one-deployable-application-with-roles.md)): `api` serves the API (and
migrates the database first unless `ARCHSIM_AUTO_MIGRATE=false`); `migrate` brings the database
to the latest schema and exits. The rest of the command line is Spring Boot's
(`--server.port=8000`).

Settings are the Python API's `ARCHSIM_*` environment variables, in its formats
([ADR-0035](../docs/adr/0035-read-the-python-apis-settings-unchanged.md)): `ARCHSIM_DATABASE_URL`
may be a SQLAlchemy URL (`postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim`, the
default) or a JDBC one. Two settings are the Java API's own: `ARCHSIM_ENGINE_URL`, where the
engine service listens (default `http://127.0.0.1:8001`), and `ARCHSIM_ENGINE_TIMEOUT`, how many
seconds an engine call may take (default 120). The OpenAPI document is at `/api/v1/openapi.json`,
the interactive docs at `/api/v1/docs`.

## The database

Flyway owns the schema. `V1` reproduces Alembic's revision 0008 exactly; a database Alembic made
is taken over at that revision, and one at any other revision is refused with the command that
fixes it ([ADR-0032](../docs/adr/0032-flyway-takes-over-from-alembic.md)). SQL is written by hand
with `JdbcClient`, in repositories only ([ADR-0008](../docs/adr/0008-jdbcclient-and-flyway.md)).

## Packages

One package per feature, holding its controller, service and repository, on a few shared packages
([ADR-0011](../docs/adr/0011-organise-application-code-by-feature.md)). `ArchitectureTests` checks
that controllers never use a repository, repositories are package-private, SQL is only in
repositories, the shared packages know no feature, and features don't depend on each other both
ways.

| Package | What |
| --- | --- |
| `io.archsim.app` | `ArchsimApplication` and its `Role`s |
| `config` | `ArchsimProperties` (the `ARCHSIM_*` settings), the database URL and connection pool, the model's registry and price snapshots |
| `migration` | Flyway's takeover from Alembic |
| `web` | the HTTP contract every endpoint shares: `/api/v1`, error bodies, request bodies read by the model's codec, security headers, CORS, body-size limits ([ADR-0034](../docs/adr/0034-keep-the-fastapi-http-contract-in-spring-mvc.md)) |
| `meta` | `GET /health`, `GET /meta` |
| `catalog` | `GET /components`, `GET /pricing`, `GET /schema/architecture`, from [archsim-model](../archsim-model/README.md) |
| `examples` | `GET /examples`, `GET /examples/{id}` |
| `analysis` | `POST /validate` (archsim-model; a scenario through the engine service), `POST /analyze` (engine service) |
| `projects` | `/projects`: list, create (blank, a design, an example), import, read, update, delete, duplicate, export |
| `access` | who is asking (`Principal`), workspace roles, and the rule that what you may not see answers `404` |
| `audit` | the history of changes, written in the transaction of each change |
| `limits` | what a workspace's kind allows (a sandbox holds five projects) |
| `engine` | the client of the Python engine service ([ADR-0036](../docs/adr/0036-python-engine-service-for-synchronous-calls.md)) |

## Endpoints ported

| Slice | Endpoints | Status |
| --- | --- | --- |
| 1 | health, meta, components, pricing, architecture schema, examples, OpenAPI | done |
| 2 | validate, analyze (Python engine service), projects, documents | done |
| 3 | sign-in, sessions, CSRF, tokens | next |
| 4 | runs and the queue | |
| 5 | shares, reviews, workspaces, limits, import and export, calibration | |
| 6 | compose runs the Java API; FastAPI deleted | |

## Tests

```bash
./gradlew :archsim-app:check
./gradlew :archsim-api-tests:apiTest -Parchsim.api.start=java --tests '*EndpointsTests.meta*'
```

Unit tests for classes, `@WebMvcTest` slices for each controller and the web contract, and
`ArchsimApplicationTests` for the whole application. The database tests run on an embedded
PostgreSQL 16 from Maven Central, so they need no Docker
([ADR-0033](../docs/adr/0033-embedded-postgresql-for-tests.md)). `MigrationTests` compare the
schema Flyway builds with the one Alembic builds (`parity/schema/`).

## Differences from the Python API

- Whole numbers in `GET /components` (cold-start defaults, one `step`) and the bounds of the
  distributions in `GET /schema/architecture` are spelled `600.0` where Python writes `600`, or
  the other way round. Clients read the same number; the golden files compare numbers by value
  too.
- The database defaults to PostgreSQL on this machine; Python defaulted to a SQLite file, which
  is gone ([ADR-0016](../docs/adr/0016-drop-sqlite-development-mode.md)). A `.env` file in the
  working directory is not read; compose's `.env` and `env_file` still apply.
- `ARCHSIM_CORS_ORIGINS` may also be comma-separated; Python reads only a JSON array.
- A CORS preflight from an origin that is not listed is refused with `403`; Starlette answers
  `400`. Browsers treat both the same.
- `413` and `411` answers carry the security headers; Python's body-size check answered before
  its header middleware ran.
- `/api/v1/docs` redirects to Swagger UI under `/api/v1/swagger-ui/`; FastAPI served the page at
  `/api/v1/docs` itself.
- A `422` for a request body lists `type`, `loc`, `msg` and `input` for each error, as FastAPI
  does, but not pydantic's `ctx` (the bound a value broke). A body that is not JSON has a `ctx`
  with the JSON reader's own message and offset, which differ from Python's.
- A request body is read as JSON whatever its `Content-Type`; FastAPI read any other type as
  raw bytes and answered `422`.
- A `405` that Tomcat answers itself (`TRACE`) lists Tomcat's methods in `Allow`.
- A SQLAlchemy URL without a host (a Unix socket, `postgresql+psycopg:///archsim`) is refused;
  the JDBC driver needs a host.
- Until slice 3, a server with `ARCHSIM_AUTH_MODE=oauth` answers `401` to every request that
  needs to know who is asking: sign-in, sessions and API tokens are not ported yet.
- The engine's synchronous calls go to the engine service at `ARCHSIM_ENGINE_URL`; when it cannot
  be reached, or takes over `ARCHSIM_ENGINE_TIMEOUT` seconds, `/analyze` and scenario validation
  answer `503` saying which. The Python API computed in-process, with no time limit.
- The order of refusals differs. Java reads a request's body before it checks access, so a body
  that does not fit is `422` where FastAPI first answered `404` or `403` (a project or workspace
  the asker may not use). In a server with sign-in, a body that is not JSON is `401` signed out,
  where FastAPI answered `422`.
- Numbers in a stored design come back spelled as Java writes them (`0.0005` as `5.0E-4`,
  `1e-05` as `1.0E-5`); clients read the same numbers.
- `/api/v1/projects/` (a trailing slash) is `404`; Starlette redirected it (`307`) to
  `/api/v1/projects`. The same holds for every route.
- A request Tomcat cannot parse at all (a bad percent escape, oversized headers) gets Tomcat's
  plain `400` page.

### Python bugs not copied

- **Lost saves.** Python checked `expectedRevision` and then saved, so two saves made from the
  same revision at once both succeeded and one silently replaced the other. Java saves only if the
  stored revision is still the one read, and answers the other `409`.
- **A sandbox past its cap.** Python counted a workspace's projects and then added one, so
  requests at once could take a sandbox past five projects. Java locks the workspace first.

More are listed here as their endpoints are ported.
