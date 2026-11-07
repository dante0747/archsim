# archsim-app

ArchSim's HTTP API on Spring Boot 4: stage 2 of the [Java port](../docs/proposals/java-25-port.md),
which replaced the FastAPI API in `services/api` endpoint for endpoint. It serves the same JSON,
cookies and error bodies on the same PostgreSQL tables, so the web app, the CLI and the
[black-box suite](../archsim-api-tests/README.md) work against it unchanged, and a database the
Python API used carries on as it is. Simulations stay in Python until stage 4: runs go to the
Python workers through the shared queue. The engine's synchronous calls run in Java
([archsim-engine](../archsim-engine/README.md)), calibration included; until stage 4, the
simulation a calibration replays goes to a small internal Python engine service,
`services/engine-service` ([ADR-0045](../docs/adr/0045-replay-calibrations-through-an-interim-simulate.md)).

## Running it

```bash
./gradlew :archsim-app:bootJar
uv run archsim-engine-service --port 8001       # the replays of calibrations (ADR-0045)
uv run archsim-api worker                       # simulates the runs the API queues (Python, until stage 4)
java -jar archsim-app/build/libs/archsim-app.jar api --server.port=8000
java -jar archsim-app/build/libs/archsim-app.jar migrate
```

One jar runs in one of its roles, named by the first argument
([ADR-0014](../docs/adr/0014-one-deployable-application-with-roles.md)): `api` serves the API (and
migrates the database first unless `ARCHSIM_AUTO_MIGRATE=false`); `migrate` brings the database
to the latest schema and exits. The rest of the command line is Spring Boot's
(`--server.port=8000`). The API listens on `127.0.0.1` only, as the Python API did; another
address (`--server.address=0.0.0.0`, or `SERVER_ADDRESS`) lets other machines in, so read
[SECURITY.md](../SECURITY.md) first.

In Docker, both roles run from one image, which Jib builds into the local Docker without a
Dockerfile (`./gradlew :archsim-app:jibDockerBuild`, or `make image`): `archsim-app:local`, on
Eclipse Temurin's Java 25 runtime, with the examples in `/app/examples`. It listens on every
interface of its container. The repository's `docker-compose.yml` runs it as `migrate` and `api`,
next to the engine service and a Python worker, and publishes the API on `127.0.0.1` only.

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
| `analysis` | `POST /validate` (archsim-engine resolves the design and its scenario, archsim-model validates it), `POST /analyze` (archsim-engine's `DesignAnalyzer`) |
| `projects` | `/projects`: list, create (blank, a design, an example), import, read, update, delete, duplicate, export |
| `runs` | `/projects/{id}/runs` (start, batch, list), `/runs/{id}` (read, report, input, cancel, rerun, delete), `/compare`, `/run-groups/{id}`: runs are prepared by archsim-engine's `RunPreparer`, queued in `simulation_runs`, and simulated by the Python workers until stage 4; two runs are compared by archsim-engine's `RunComparer`, and a seed group is summarized by its `SeedSummary` |
| `access` | who is asking: `Principal` (local, a session's cookie with its CSRF header, or an API token), `Person` (not a token), `Visitor` (address and browser); workspace roles, and the rule that what you may not see answers `404` ([ADR-0037](../docs/adr/0037-sign-in-without-spring-security.md)) |
| `signin` | `/auth`: GitHub and Google sign-in (OAuth with PKCE), sessions, signing out, `GET /auth/me`, `GET /auth/config` |
| `tokens` | `/tokens`: personal API tokens, made, listed and revoked in person |
| `workspaces` | `/workspaces`: the asker's workspaces with their limits; team workspaces made, renamed and deleted; members and their roles; invitation links (`/invitations/preview`, `/invitations/accept`) |
| `conversion` | `POST /export`: a design as docker-compose, Kubernetes manifests or a Mermaid diagram (archsim-engine's `DesignExporter`); `POST /import`: a design from docker-compose, Kubernetes manifests or a Terraform plan (archsim-engine's `DesignImporter`) |
| `calibration` | `POST /calibrate`: archsim-engine's Prometheus and OpenTelemetry calibrations, whose replay the engine service simulates; `HttpPrometheus`, the Prometheus client, under the workspace's outbound policy: one lookup per query, the connection pinned to the address checked, public addresses only on a hosted server, one deadline and a size cap ([ADR-0046](../docs/adr/0046-call-prometheus-with-apache-httpclient-5.md)) |
| `reviews` | comments (`/projects/{id}/comments`, `/comments/{id}`), sign-offs (`/runs/{id}/signoffs`, `/projects/{id}/signoffs`, `/signoffs/{id}`) and the history pages (`/projects/{id}/activity`, `/workspaces/{id}/activity`) |
| `shares` | share links: made, listed and revoked (`/runs/{id}/shares`, `/projects/{id}/shares`, `/shares`), opened by anyone (`/public/shares/{slug}`), and copied into a project (`/projects/from-share`) |
| `audit` | the history of changes, written in the transaction of each change |
| `store` | values as the shared database holds them, made as the Python API makes them: `Ids`, `Times` (to the microsecond), `Text` (counted, cut and trimmed as Python does), and the app's clock, `StrictlyIncreasingClock`, which never tells the same microsecond twice, so the rows one server makes sort in the order it made them ([ADR-0041](../docs/adr/0041-application-clock-never-repeats-a-microsecond.md)) |
| `limits` | what a workspace's kind allows (runs at once and per hour, projects, seeds) and the rate limits every instance shares (`rate_limits`) |
| `engine` | the client of the Python engine service ([ADR-0036](../docs/adr/0036-python-engine-service-for-synchronous-calls.md)), which simulates a calibration's replay ([ADR-0045](../docs/adr/0045-replay-calibrations-through-an-interim-simulate.md)) |

## Endpoints ported

| Slice | Endpoints | Status |
| --- | --- | --- |
| 1 | health, meta, components, pricing, architecture schema, examples, OpenAPI | done |
| 2 | validate, analyze (Python engine service), projects, documents | done |
| 3 | sign-in, sessions, CSRF, tokens, the workspace list | done |
| 4 | runs and the queue (the workers stay Python) | done |
| 5 | workspaces, members and invitations; comments, sign-offs and history; share links; import and export; calibration | done |
| 6 | compose runs the Java API; FastAPI deleted | done |

Stage 3 then moved every call these endpoints made to the engine service into
[archsim-engine](../archsim-engine/README.md), analyze and validate included; the service now
only simulates a calibration's replay.

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
- The sign-in state cookie's expiry is written as Java writes a number (`1.79369E9`); Python
  wrote `1793690103.358`. Each API reads the other's.
- An OAuth provider whose profile names no account id (`"id": null`) fails the sign-in
  (`/signin?error=provider`); Python stored the account under the subject `None`.
- A calibration's replay goes to the engine service at `ARCHSIM_ENGINE_URL`; when it cannot be
  reached, or takes over `ARCHSIM_ENGINE_TIMEOUT` seconds, `POST /calibrate` answers `503`, saying
  why. The Python API simulated in-process, with no time limit. A call whose connection drops
  before an answer (uvicorn closes one idle for 5 s, and the client may reuse it just then) is sent
  once more: the call is a stateless POST.
- `POST /calibrate` answers in Java what the Python API answered, numbers spelled as Java writes
  them, with these departures:
  - its refusals never repeat what a request sent, where Python's did: a port that is not one
    (`Port could not be cast to integer value as '...'`, under an allowlist) is
    `the Prometheus URL has an invalid port`; a bracketed host that is no address is
    `Invalid IPv6 URL`; a netloc that NFKC normalization turns into a delimiter is
    `netloc contains invalid characters under NFKC normalization`;
  - what Python's runtime failed on, which it answered `500` for, is refused with `422`: a
    mapping's YAML scalar PyYAML failed to read, and a Prometheus answer nested deeper than
    Python's recursion limit (`did not answer like a Prometheus server`);
  - a Prometheus answer that is not HTTP names Java's failure in its reason
    (`not a valid HTTP response (ParseException)`, Python's `BadStatusLine`); a TLS failure other
    than the certificate's is `the TLS handshake failed`, not OpenSSL's words; and a URL path
    a URI may not hold unescaped is refused before anything is sent, as Python refused control
    characters, and every refusal of a path comes before the host is looked up, where Python's
    came after connecting;
  - a deprecated site-local IPv6 address (`fec0::/10`) is not public, so a hosted server never
    calls it; Python's `ipaddress` counts it as global;
  - a host name with several addresses is called at the first Java's resolver gives, which may
    not be the one `getaddrinfo` gave first; every one of them is checked on a hosted server.
- The order of refusals differs. Java reads a request's body and query before it checks access,
  so a body or query parameter that does not fit is `422` where FastAPI first answered `404` or
  `403` (a project or workspace the asker may not use). In a server with sign-in, a body that is
  not JSON is `401` signed out, where FastAPI answered `422`.
- A `422` for query parameters names the first one that does not fit; FastAPI listed every one.
- `POST /calibrate` with a `mapping` that is neither text nor an object answers `422` with
  `Cannot calibrate: ...`; pydantic listed both types it expected.
- A share page that is not found (`GET /public/shares/{slug}`) still carries `Cache-Control:
  no-store` and `X-Robots-Tag: noindex`; FastAPI sent them only with a report.
- A share link made from a run whose report's `reportVersion` is not a string (the engine always
  writes one) keeps `unknown`; Python kept `str()` of it.
- The Java API has no embedded worker: the runs it queues are simulated by `archsim-api worker`
  processes (Python) on the same database. `ARCHSIM_EMBEDDED_WORKER` is gone.
- There is no `--reload`, which `archsim-api serve` had: rebuild the jar, or run the API from an
  IDE.
- A run's report and input are passed on as PostgreSQL stores them (`0.00001` where Python wrote
  `1e-05`); `GET /compare` and `GET /run-groups/{id}` spell the engine's numbers as Java writes
  them. Clients read the same numbers.
- `GET /compare` of a run whose stored report lacks a part every report has (its summary,
  parts, findings, provenance, time series or window, or a design's metadata and name in its
  input) fails (`500`). The Python failed the same way without the summary or the parts, and
  compared what it had without the others. Every report the engine writes has them.
- Numbers in a stored design, and in `POST /analyze`'s and `POST /import`'s answers, come back
  spelled as Java writes them (`0.0005` as `5.0E-4`, `1e-05` as `1.0E-5`); clients read the same
  numbers.
- `POST /import` reads YAML with SnakeYAML and Terraform's JSON with Jackson, set to read them as
  PyYAML and `json` did, with the departures archsim-engine's README lists
  ([ADR-0043](../docs/adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md),
  [ADR-0044](../docs/adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)):
  - text that is not YAML, or not JSON past where its value starts, is described in the library's
    words;
  - a number Python's runtime could not convert, which it answered `500` for, is refused with
    `422`;
  - a few rare forms read otherwise: keys that are not text, `!!binary`, `!!set` and `!!omap`
    values, `-INF`, `+INF`, and JSON nested deeper than 1,000 levels, which is refused.
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

- **A crash on odd sign-in input.** Python compared the callback's `state` and the
  `X-ArchSim-CSRF` header with `hmac.compare_digest`, which raises on text that is not ASCII, so
  such a request answered `500`. Java compares bytes: the callback lands on
  `/signin?error=state`, and the change is refused with `403`.

- **Refused runs counted against the hour.** Python counted a run, or a rerun, against the hourly
  limits before the engine prepared it, so a design it could not simulate, or a run over its
  limits, still used up one of the hour's runs. Java prepares and checks first.
- **Runs a workspace refused, charged to the person.** Python counted new runs against the
  person's hourly limit, then the workspace's, each on its own, so when the workspace's limit
  answered `429` the person's count kept runs that were never queued. Java counts them against
  both limits together, or against neither.
- **Seed groups and batches half queued.** Python queued a group's runs, and a batch's, one by
  one, each in its own transaction, so a failure part-way left a group with fewer runs than its
  size, or part of a batch. Java queues a group with its runs, and a batch, in one transaction.

- **A workspace with no owner.** Python counted a workspace's owners and then changed a role or
  removed a member, so two owners demoting or removing each other at once both succeeded and left
  the workspace with no one to manage it. Java locks the workspace first.
- **Refused comments counted against the hour.** Python counted a comment against the hourly limit
  before it checked what the comment was on, so a reply to a thread of another project, or a
  comment on a run of another one, still used up one of the hour's comments. Java checks first.
- **Addresses left in share links.** Python hid the addresses in a calibration's source with a
  Unicode word boundary, so an address right after a letter of another script
  (`監視https://user:pass@prom.internal`) was kept in the link's copy. Java's boundary is ASCII,
  and hides it.
- **A revoke recorded twice.** Python read a share link and then stamped it revoked, so two revokes
  at once (a double click) wrote two `share.revoke` lines in the history. Java stamps it only if it
  is not stamped yet, and records the change only then.
- **One invitation, two members.** Python read an invitation and later marked it accepted, so two
  people accepting the same link at once both joined. Java claims the link before it adds the
  member; the second answers `404`, as for a link already used.

More are listed here as their endpoints are ported.
