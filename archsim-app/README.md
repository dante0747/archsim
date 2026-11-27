# archsim-app

ArchSim's HTTP API on Spring Boot 4: stage 2 of the [Java port](../docs/proposals/java-25-port.md),
which replaced the Python API's FastAPI app endpoint for endpoint. It serves the same JSON,
cookies and error bodies on the same PostgreSQL tables, so the web app, the CLI and the
[black-box suite](../archsim-api-tests/README.md) work against it unchanged, and a database the
Python API used carries on as it is. The engine runs in Java
([archsim-engine](../archsim-engine/README.md)): every synchronous call, calibration included,
and since stage 4 the simulation a calibration replays, which runs on the Java simulator in the
API's process ([ADR-0053](../docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)). Runs
go to the workers through the shared queue (the worker role, below).

## Running it

```bash
./gradlew :archsim-app:bootJar
java -jar archsim-app/build/libs/archsim-app.jar api --server.port=8000
java -jar archsim-app/build/libs/archsim-app.jar migrate
java -jar archsim-app/build/libs/archsim-app.jar seed  # one project per example
ARCHSIM_MAX_CONCURRENT_RUNS=3 java -Xmx2g -XX:+ExitOnOutOfMemoryError \
  -jar archsim-app/build/libs/archsim-app.jar worker    # simulates the runs the API queues
```

One jar runs in one of its roles, named by the first argument
([ADR-0014](../docs/adr/0014-one-deployable-application-with-roles.md)): `api` serves the API (and
migrates the database first unless `ARCHSIM_AUTO_MIGRATE=false`); `migrate` brings the database
to the latest schema and exits; `worker` takes the runs the API queues
([ADR-0053](../docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)), without the web
layer, until it is stopped; `seed` makes one project per example in the default workspace (its
design read and written back as the API saves one), unless a project of the example's name exists
in any workspace, and exits. The worker and the seed never migrate: they start only on a
database at their own build's schema, and otherwise say which version the database is at and to
start the API first.
It took the Python worker's place in stage 4: compose, the black-box suite and the end-to-end
launcher start it. The rest of the command line is Spring Boot's
(`--server.port=8000`). The API listens on `127.0.0.1` only, as the Python API did; another
address (`--server.address=0.0.0.0`, or `SERVER_ADDRESS`) lets other machines in, so read
[SECURITY.md](../SECURITY.md) first. Without sign-in it answers only requests addressed to this
machine or a name `ARCHSIM_ALLOWED_HOSTS` lists, so add the name other machines call it by.

In Docker, every role runs from one image, which Jib builds without a Dockerfile, on Eclipse
Temurin's Java 25 runtime, with the examples in `/app/examples`. It listens on every interface of
its container, and exits when it runs out of heap (`-XX:+ExitOnOutOfMemoryError`). A release
publishes it for amd64 and arm64 as `ghcr.io/dante0747/archsim-app:<version>`
(`.github/workflows/release.yml`,
[ADR-0077](../docs/adr/0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)), which the
repository's `docker-compose.yml` runs as `migrate`, `api` and `worker`, publishing the API on
`127.0.0.1` only. `./gradlew :archsim-app:jibDockerBuild` (`make image`) builds it from the checkout
into the local Docker, for this machine's processor, as `archsim-app:local`, which
`docker-compose.local.yml` runs instead.

Settings are the Python API's `ARCHSIM_*` environment variables, in its formats
([ADR-0035](../docs/adr/0035-read-the-python-apis-settings-unchanged.md)): `ARCHSIM_DATABASE_URL`
is a `postgresql://` or `postgres://` URL (`postgresql://archsim:archsim@127.0.0.1:5432/archsim`,
the default), the same with SQLAlchemy's driver name as the Python API took it
(`postgresql+psycopg://`), or a JDBC one; a URL's query goes to the JDBC driver as its
connection properties. The worker reads the Python worker's timings,
`ARCHSIM_LEASE_SECONDS` (30), `ARCHSIM_HEARTBEAT_SECONDS` (2, at most half the lease),
`ARCHSIM_REAP_SECONDS` (10) and `ARCHSIM_CANCEL_GRACE_SECONDS` (10), and takes
`ARCHSIM_MAX_CONCURRENT_RUNS` runs at once (the cores less one). The OpenAPI document is at
`/api/v1/openapi.json`, the interactive docs at `/api/v1/docs`.

### The worker's heap

A worker's runs share its heap
([ADR-0053](../docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)), so give it a
maximum for the runs it takes at once: `-Xmx` of 512 MB a run (`ARCHSIM_MAX_CONCURRENT_RUNS`)
and 256 MB besides for the application. Three runs at once take 1,792 MB; compose,
`make worker` and the end-to-end launcher give theirs `-Xmx2g`, and the black-box suite works
its workers' heaps out by the rule. Raising the runs at once means raising the heap with them.
The image runs every role with `-XX:+ExitOnOutOfMemoryError`, and so should any other way of
starting a worker: one whose heap runs out stops, compose starts it again, and once its lease
ends the reaper requeues its runs; a run is failed only if its worker stops a second time.

512 MB is about twice what the heaviest run measured needed, at the default
`ARCHSIM_MAX_REQUESTS` of 2 million; a run can hold its requests in flight, so the heap a run
needs grows with that limit, by about 128 MB a million. On 2026-11-10, runs of about 1.9 million
requests were simulated by `archsim run` under smaller and smaller heaps: a message queue with
no backlog limit and one slow consumer, which keeps nearly every request of the hour, needed
246 MB (it ran out at 228); the 12-part `microservices-call-chain` at its own load and at ten
times it, `black-friday-kubernetes` and a queue held at its default backlog limit (100,000)
completed in 34 MB, 200 parts (the most a design may have) in 45 MB, and `social-feed-hot-key`,
whose million keys are the most a design may have, in 67 MB. A design that sends each request
to several queues without a backlog limit can hold more than its requests, and so more than
this.

## The database

Flyway owns the schema. `V1` reproduces Alembic's revision 0008 exactly; a database Alembic made
is taken over at that revision ([ADR-0032](../docs/adr/0032-flyway-takes-over-from-alembic.md)).
One at an older revision is refused before Flyway writes anything, with the way to bring it to
0008: the Alembic migrations are gone, but the tag `alembic-0008` keeps them
([ADR-0063](../docs/adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)). They are
Python, so this one step needs Python 3.11 or later and [uv](https://docs.astral.sh/uv/), which
nothing else in this version does. Run them from a worktree of that tag, not from this checkout,
with the database's URL in SQLAlchemy's form (`postgresql+psycopg://`), the one the tag reads:

```bash
git worktree add ../archsim-alembic-0008 alembic-0008
cd ../archsim-alembic-0008   # the tag's checkout, which has the Python migrations
uv sync
ARCHSIM_DATABASE_URL=postgresql+psycopg://user:password@host:5432/archsim uv run archsim-api migrate
cd - && git worktree remove --force ../archsim-alembic-0008
```

Without a checkout (running the image, say), clone the tag instead:
`git clone --branch alembic-0008 --depth 1 <repository URL> archsim-alembic-0008`. Then migrate
again with this version.

After `V1`, `V2` adds `users.expires_at`: an account with an expiry is a sandbox's guest's, which
the API deletes with its sandbox once it expires
([ADR-0079](../docs/adr/0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)). `V3`
changes no schema: it hides the telemetry addresses that share links made before stored, as the
API now hides them. `V4` stores each run's design once, in `run_designs` under its digest, instead
of a copy in every run (`simulation_runs.input_document`, which it moves and drops); a trigger
deletes a design with the last run that refers to it, however the runs are deleted
([ADR-0110](../docs/adr/0110-store-a-runs-design-once-per-digest-and-delete-it-with-its-last-run.md)).

SQL is written by hand with `JdbcClient`, in repositories only
([ADR-0008](../docs/adr/0008-jdbcclient-and-flyway.md)).

## Packages

One package per feature, holding its controller, service and repository, on a few shared packages
([ADR-0011](../docs/adr/0011-organise-application-code-by-feature.md)). `ArchitectureTests` checks
that controllers never use a repository, repositories are package-private, SQL is only in
repositories, the shared packages know no feature, and features don't depend on each other both
ways.

| Package | What |
| --- | --- |
| `io.archsim.app` | `ArchsimApplication` and its `Role`s |
| `config` | `ArchsimProperties` (the `ARCHSIM_*` settings), the database URL and connection pool, the model's registry and price snapshots, and the `Simulator` the whole application shares |
| `migration` | Flyway's takeover from Alembic; the worker's and the seed's check that the database is at their build's schema |
| `web` | the HTTP contract every endpoint shares: `/api/v1`, error bodies, request bodies read by the model's codec, security headers, CORS, body-size limits ([ADR-0034](../docs/adr/0034-keep-the-fastapi-http-contract-in-spring-mvc.md)); what the OpenAPI document says of the API as a whole (title, version, license, a relative server) |
| `meta` | `GET /health`, `GET /meta` |
| `catalog` | `GET /components`, `GET /pricing`, `GET /schema/architecture`, from [archsim-model](../archsim-model/README.md) |
| `examples` | `GET /examples`, `GET /examples/{id}` |
| `analysis` | `POST /validate` (archsim-engine resolves the design and its scenario, archsim-model validates it), `POST /analyze` (archsim-engine's `DesignAnalyzer`) |
| `projects` | `/projects`: list, create (blank, a design, an example), import, read, update, delete, duplicate, export; the `seed` role's `ExampleSeeding` |
| `runs` | `/projects/{id}/runs` (start, batch, list), `/runs/{id}` (read, report, input, cancel, rerun, delete), `/compare`, `/run-groups/{id}`: runs are prepared by archsim-engine's `RunPreparer`, queued in `simulation_runs`, and simulated by the worker role (`worker`); two runs are compared by archsim-engine's `RunComparer`, and a seed group is summarized by its `SeedSummary` |
| `security` | Spring Security's filter chain ([ADR-0065](../docs/adr/0065-move-sign-in-to-spring-security-after-the-port.md)): it authorizes every request, leaving each endpoint to say whom it needs; it writes the security headers, runs CORS and the request firewall, and puts who is asking in the security context (`RequestAuthentication`): with sign-in, API tokens as a resource server (`ApiTokens`, over `access`'s `ApiTokenIntrospector`) and browser sessions (`access`'s `BrowserSessions`, read when an endpoint first asks or a change needs its CSRF token), with CSRF on a session's changes and, where sandboxes are offered, on a sandbox's start (`BrowserCsrf`, `SessionChanges`, `signin`'s `SandboxOffer`, `CsrfRefusal`) and sign-in through OAuth2 login (`signin`'s `OAuthSignIn`), without it the local user (`LocalUserFilter`); Spring's HTTP sessions, login and logout pages and anonymous user are switched off |
| `access` | who is asking: `Principal` (local, a session's cookie with its CSRF header, or an API token; a session may be a sandbox's guest's, who has no account of their own), read from the security context by `Principals` (for a controller's parameters through `PrincipalResolver`), a sandbox guest's bodies capped by `GuestBodyFilter`, `Person` (neither a token nor a guest: what managing access takes), `Visitor` (address and browser, also from `Visitors` outside Spring MVC); the adapters the chain uses (`ApiTokenIntrospector`, `BrowserSessions`, `LocalUserFilter`, `BrowserCsrf`, `SessionChanges`, `CsrfRefusal`); workspace roles, and the rule that what you may not see answers `404` ([ADR-0065](../docs/adr/0065-move-sign-in-to-spring-security-after-the-port.md)) |
| `signin` | `/auth`: GitHub and Google sign-in through Spring Security's OAuth2 login with PKCE (`OAuthSignIn`: the providers' registrations in `OAuthProviders`, the start in `SignInStarts`, the signed state cookie in `StateCookies`, the token request and user services in `OAuthClients`, GitHub's email rule in `GitHubUsers`, Google's OpenID Connect claims in `GoogleUsers`, the session or the sign-in page in `Callbacks`), sessions, signing out, `GET /auth/me`, `GET /auth/config`; with `ARCHSIM_SANDBOXES`, sandboxes for visitors who do not sign in (`SandboxOffer`: whether they are offered and the CSRF token that starts one; `SandboxController` and `Sandboxes`: `POST /auth/sandbox`, its limit per address and the admission; `GuestAdmissions`: the guest, the sandbox and the session in one transaction; `Guests`: the cap and deleting a guest; `SandboxPruning`: in the `api` role, deletes expired sandboxes every minute) |
| `tokens` | `/tokens`: personal API tokens, made, listed and revoked in person |
| `workspaces` | `/workspaces`: the asker's workspaces with their limits; team workspaces made, renamed and deleted; members and their roles; invitation links (`/invitations/preview`, `/invitations/accept`) |
| `conversion` | `POST /export`: a design as docker-compose, Kubernetes manifests or a Mermaid diagram (archsim-engine's `DesignExporter`); `POST /import`: a design from docker-compose, Kubernetes manifests or a Terraform plan (archsim-engine's `DesignImporter`) |
| `calibration` | `POST /calibrate`: archsim-engine's Prometheus and OpenTelemetry calibrations, whose replay the Java simulator runs in-process on `ReplayPool`'s platform threads, the cores less one ([ADR-0054](../docs/adr/0054-replay-calibrations-on-a-pool-of-platform-threads.md)); the engine's Prometheus client (`HttpPrometheus`, [ADR-0046](../docs/adr/0046-call-prometheus-with-apache-httpclient-5.md), [ADR-0057](../docs/adr/0057-move-the-prometheus-client-into-the-engine.md)) under the workspace's outbound policy, `OutboundPolicy`: no calls when outbound calls are off, only listed hosts and public addresses under an allowlist |
| `reviews` | comments (`/projects/{id}/comments`, `/comments/{id}`), sign-offs (`/runs/{id}/signoffs`, `/projects/{id}/signoffs`, `/signoffs/{id}`) and the history pages (`/projects/{id}/activity`, `/workspaces/{id}/activity`) |
| `shares` | share links: made, listed and revoked (`/runs/{id}/shares`, `/projects/{id}/shares`, `/shares`), opened by anyone (`/public/shares/{slug}`), and copied into a project (`/projects/from-share`) |
| `audit` | the history of changes, written in the transaction of each change |
| `worker` | the `worker` role (ADR-0053), its beans under the `worker` profile only: `Worker`, its life from start to stop, registered and beating in `job_workers` (`WorkerRepository`) as a `WorkerIdentity`, on a `PeriodicTask` that outlives a failed beat; `WorkerProperties`, its timings; the queue as the Python worker kept it, but with sandboxes' runs last (ADR-0106): `RunClaims` (a `ClaimRequest` answered with `ClaimedRun`s, in turn on an advisory lock, `FairPick` keeping each workspace under its cap, from `ClaimRepository`), and the leases in `LeaseRepository` (a `Renewal`, progress, a `RunOutcome`, a run `Released` at stop, and what a reap settles, `Reaped`); an `Execution`, one claimed run simulated by a `RunSimulator` on a platform thread of the worker's (`RunParts`), observed for its progress and asked to stop, its hard deadline and cancel grace on `RunTimers`, its outcome shaped by `RunOutcomes` and written by a `RunWriter` that tries three times; and the worker at work: `Worker` starts and stops it, a `ClaimLoop` claims runs into its `Executions` on `WorkerThreads` while its `Heart` renews their leases, passes on cancels, beats and reaps, all wired in `WorkerConfiguration` with the Java `Simulator` as its `RunSimulator` and a `WorkerContext` |
| `store` | values as the shared database holds them, made as the Python API makes them: `Ids`, `Times` (to the microsecond), `Text` (counted, cut and trimmed as Python does), and the app's clock, `StrictlyIncreasingClock`, which never tells the same microsecond twice, so the rows one server makes sort in the order it made them ([ADR-0041](../docs/adr/0041-application-clock-never-repeats-a-microsecond.md)) |
| `limits` | what a workspace's kind allows (runs at once and per hour, projects, seeds), the rate limits every instance shares (`rate_limits`), whose ended windows the API role prunes every minute, and the request slots (`RequestSlots`): how many requests with work, the engine's routes (`@EngineRoute`) and other routes with a body, are in memory at once ([ADR-0108](../docs/adr/0108-bound-the-engines-routes-by-slots-per-person-limits-and-a-sandbox-body-cap.md)) |
| `retention` | rows kept only for a while ([ADR-0111](../docs/adr/0111-prune-the-runs-past-a-projects-limit-and-expired-rows-every-hour.md)): each feature's `Retention` says which and deletes them in batches (`runs`' `RunRetention`, the runs past a project's `ARCHSIM_RUN_HISTORY_LIMIT` that nothing still needs; `signin`'s `SessionRetention`, `workspaces`' `InvitationRetention` and `shares`' `ShareRetention`, expired sessions, invitations and share links' copies; `audit`'s `HistoryRetention`, the history past `ARCHSIM_HISTORY_DAYS`), and `Pruning`, in the `api` role, runs them all when the API starts and every hour after |

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
[archsim-engine](../archsim-engine/README.md), analyze and validate included, and stage 4 the
last, a calibration's replay: the API no longer calls the service.

## Tests

```bash
./gradlew :archsim-app:check
./gradlew :archsim-api-tests:apiTest -Parchsim.api.start=java --tests '*EndpointsTests.meta*'
```

Unit tests for classes, `@WebMvcTest` slices for each controller and the web contract, and
`ArchsimApplicationTests` for the whole application. The database tests run on an embedded
PostgreSQL 16 from Maven Central, so they need no Docker
([ADR-0033](../docs/adr/0033-embedded-postgresql-for-tests.md)). `MigrationTests` compare the
schema Flyway builds with the one Alembic built at its revision 0008 (`parity/schema/`).

## Differences from the Python API

- A number that is not finite is refused wherever a request body holds a float (a design, a
  calibration's range and mapping, an imported design), with pydantic's
  `finite_number` error, "Input should be a finite number", at its place, before any bound: the
  words `nan`, `inf` and `infinity` in a string, and a number with a fraction or an exponent too
  large for a double (`1e999`). Python read them as floats and refused them, if at all, by a
  bound: `less_than_equal`, say, for a calibration `step` of `"inf"`
  ([ADR-0103](../docs/adr/0103-refuse-a-number-that-is-not-finite-when-a-design-is-read.md)).
- Without sign-in, the API answers only requests addressed to this machine (`localhost`,
  `127.x.x.x`, `[::1]`) or a name `ARCHSIM_ALLOWED_HOSTS` lists, and refuses a change whose
  `Origin` is neither the page it was addressed to nor one `ARCHSIM_CORS_ORIGINS` lists, both with
  `403`; the web app's proxy passes the browser's host and origin on for these checks. A body
  must be `application/json` or an `application/*+json` type, else `415`. The Python API served
  any host and origin, and read a body without a type as JSON.
- Whole numbers in `GET /components` (cold-start defaults, one `step`) and the bounds of the
  distributions in `GET /schema/architecture` are spelled `600.0` where Python writes `600`, or
  the other way round. Clients read the same number; the golden files compare numbers by value
  too.
- The database defaults to PostgreSQL on this machine; Python defaulted to a SQLite file, which
  is gone ([ADR-0016](../docs/adr/0016-drop-sqlite-development-mode.md)). A `.env` file in the
  working directory is not read; compose's `.env` and `env_file` still apply.
- `GET /meta` also names ArchSim's own version (`version`,
  [ADR-0076](../docs/adr/0076-version-archsim-apart-from-its-engine.md)), which the Python API did
  not.
- `ARCHSIM_CORS_ORIGINS` may also be comma-separated; Python reads only a JSON array.
- A CORS preflight from an origin that is not listed is refused with `403`; Starlette answers
  `400`. Browsers treat both the same.
- `413` and `411` answers carry the security headers and, for an allowed origin, the CORS
  headers; Python's body-size check answered before its header and CORS middleware ran.
- Spring Security's request firewall answers `400` with `{"detail": "Bad Request"}` to:
  - a method other than `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE` and `OPTIONS`
    (`PROPFIND`), which Starlette answered `405` on a route it had and `404` elsewhere;
  - a path that is not normalized (`//`, `/./`, `/../`), or holds an encoded `%`, `.`,
    backslash, NUL, CR or LF, or a character that is not printable ASCII, which Starlette
    answered as it found it, usually `404`;
  - a parameter name, or a header value an endpoint reads, with a control character in it
    (a `User-Agent` whose UTF-8 bytes Tomcat reads as C1 controls), which Starlette served.

  An encoded slash and a path parameter pass, as before. A refused method or path is answered
  before the chain writes the security headers, so, like what Tomcat refuses itself, it carries
  none.
- With sign-in, a wrong API token (revoked, expired, mistyped or malformed `Bearer`) is refused
  `401` wherever it is sent, public endpoints and unknown paths included, by Spring Security's
  bearer token filter (ADR-0065); FastAPI ignored a token where no endpoint needed one. A
  request with no token, or a `Basic` header, reaches the endpoint as before. The exception is
  the sign-in paths of a provider set up here (`/auth/{provider}/login` and `/callback`), which
  Spring Security's OAuth2 login answers first: a wrong token there gets the same `302` as none,
  as it did in Python.
  `GET /.well-known/oauth-protected-resource/api/v1` answers the resource server's metadata
  (RFC 9728), on the API's own address.
- With sign-in, a change a session cookie signs in is refused `403` without its CSRF header
  wherever it is sent, an unknown path or a route that does not take the method included, by
  Spring Security's CSRF filter (ADR-0065); FastAPI checked the header only on an endpoint that
  needed someone, and answered such a request `404` or `405`.
- With sign-in, Spring Security's OAuth2 login signs people in (ADR-0065). Its state is Spring's,
  44 characters of base64url with padding. Google signs in through OpenID Connect, its ID
  token's signature, issuer, audience and nonce checked, where Python read `userinfo` with the
  access token. A callback for a provider set up here whose `state` is too long lands on
  `/signin?error=state`, and one whose `code` is too long on `/signin?error=provider` (the
  provider refuses it), where FastAPI refused both `422`.
- The CSRF token is a random value in the `__Host-archsim_csrf` cookie, sent back in
  `X-ArchSim-CSRF` (a double-submit cookie), where Python's was an HMAC of the session under a
  key derived from `ARCHSIM_SECRET_KEY`: a change must send the cookie as well as the header.
  A browser signed in before the change keeps its cookie's old value as its token. Signing in
  sets the cookie for as long as the session lasts, as Python did; when it is gone,
  `GET /auth/me` sets a new one, which lasts the browser's session.
- HSTS is written `max-age=31536000 ; includeSubDomains`, Spring Security's spelling; Python
  wrote no space before the `;`. Browsers read both the same.
- `/api/v1/docs` redirects to Swagger UI under `/api/v1/swagger-ui/`; FastAPI served the page at
  `/api/v1/docs` itself.
- A `422` for a request body lists `type`, `loc`, `msg` and `input` for each error, as FastAPI
  does, but not pydantic's `ctx` (the bound a value broke). A body that is not JSON has a `ctx`
  with the JSON reader's own message and offset, which differ from Python's.
- Half of a surrogate pair in a body (a JSON escape of U+D800 to U+DFFF without its partner):
  - in a key, the body is refused as not JSON (`422`, `json_invalid`), because Jackson reads
    only whole pairs in a key. FastAPI read it: a request model ignored the key, and a design
    was refused with a `string_unicode` issue for the object holding it;
  - in a value a request model checks (its length, pattern or literal, or a number or boolean
    given as text), the answer is `422` with pydantic's `string_unicode` and the input written
    back as the escape. FastAPI answered `500`: its `422` repeated the input, which Starlette
    could not encode. In a design, the issue is Python's, but for a node's or link's id: the
    issue names the part by it, which Python could not encode either (`500`), and Java answers
    `422` with the issue, the id written as the escape.
- A request body is read as JSON whatever its `Content-Type`; FastAPI read any other type as
  raw bytes and answered `422`.
- A `405` that Tomcat answers itself (`TRACE`) lists Tomcat's methods in `Allow`.
- A database URL without a host, or with a socket directory for one (a Unix socket:
  `postgresql:///archsim`, `postgresql://%2Fvar%2Frun%2Fpostgresql/archsim`), is refused; the
  JDBC driver connects over TCP.
- A database URL's query goes to the JDBC driver as its connection properties, where psycopg
  gave it to libpq: a parameter the driver names differently (`connect_timeout`,
  `application_name`, `target_session_attrs`) has no effect, so give the driver's
  (`connectTimeout`, `ApplicationName`, `targetServerType`). `sslmode`, `sslrootcert`, `sslcert`,
  `sslkey` and `options` keep their names, but the driver reads `sslkey` as a PKCS-8 (`.pk8`) or
  PKCS-12 file, not libpq's PEM key.
- The sign-in state cookie's expiry is written as Java writes a number (`1.79369E9`); Python
  wrote `1793690103.358`. Each API reads the other's.
- An OAuth provider whose profile names no account id (`"id": null`) fails the sign-in
  (`/signin?error=provider`); Python stored the account under the subject `None`.
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
- The Java API has no embedded worker: the runs it queues are simulated by `archsim-app worker`
  processes on the same database. `ARCHSIM_EMBEDDED_WORKER` is gone.
- With the hosted limits on, the Java worker gives a workspace of a kind it does not know the
  personal cap of runs at once (2), as the API's admission does; the Python worker gave it no
  cap.
- The routes that make the engine work on a design answer `ARCHSIM_MAX_ENGINE_REQUESTS` requests
  at once, and `/calibrate` one per replay thread; other routes with a body take up to 64 KB, 32
  at once. Another request gets `503` with `Retry-After`. With the hosted limits on, a person may
  send the engine's routes 600 requests a minute, and a sandbox's guest sends bodies of up to
  256 KB (ADR-0108). The Python API answered every request at once.
- Each workspace has a quota of stored bytes (a sandbox 10 MB; with the hosted limits a personal
  workspace 100 MB and a team 250 MB) and, with the hosted limits, a project cap (100 and 500); a
  workspace at its quota gets `409` for a new project, design, run or share link, checked with the
  workspace locked (ADR-0109). The Python API capped only a sandbox's projects.
- The API, not the worker, deletes the rate-limit counters of windows that ended (`rate_limits`),
  when it starts and every minute after; the Python worker's reaper did it every reap.
- When it starts and every hour after, the API deletes the runs past a project's
  `ARCHSIM_RUN_HISTORY_LIMIT` (but for runs still queued or running, runs with a sign-off or a
  comment, and the rest of their seed group), expired sessions and invitations, expired share
  links' copies, and the history older than `ARCHSIM_HISTORY_DAYS` (365 days). The Python API kept
  them all, and its run limit only shortened a project's list.
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
- **Tokens managing workspaces.** Python let an API token rename a workspace, change a member's
  role, remove a member or leave (a read token too), and revoke an invitation. Java takes a person
  for all of them, as for making a workspace or an invitation: a token gets `403`.
- **One invitation, two members.** Python read an invitation and later marked it accepted, so two
  people accepting the same link at once both joined. Java claims the link before it adds the
  member; the second answers `404`, as for a link already used.

More are listed here as their endpoints are ported.
