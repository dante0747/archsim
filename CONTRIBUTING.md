# Contributing to ArchSim

Thanks for helping. ArchSim is an educational, honest architecture simulator: correctness of
the model and clarity about its assumptions matter more than feature count. Please read
[`docs/architecture.md`](docs/architecture.md) and
[`docs/simulation-model.md`](docs/simulation-model.md) before changing behaviour.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues go
through [SECURITY.md](SECURITY.md), not public issues.

## Repository layout

```
benchmarks/        Python: the accuracy benchmarks' harness, on the Java engine's CLI.
scripts/           Python: developer scripts (CI's changed parts, commit messages, prices).
apps/web/          Next.js 16 + React Flow editor and results UI.
examples/          Example architecture documents (served by the API, used by tests).
docs/              Design and reference documentation.

archsim-model/     Java: the design document's records, upgrades, JSON Schema and validation
                   (the Java 25 port, see docs/proposals/java-25-port.md).
archsim-engine/    Java: the engine, the simulator and its deterministic parts.
archsim-app/       Java: the HTTP API on Spring Boot 4 (stage 2 of the port); Flyway owns
                   the database schema.
archsim-api-tests/ Java: black-box tests of the HTTP API.
archsim-cli/       Java: the `archsim` command line.
archsim-mcp/       Java: the MCP server for AI coding agents.
                   build-logic/ and gradle/ hold the build.
parity/            What the Python engine 0.16.0 recorded before stage 5 deleted it (golden
                   files, simulation baselines, test fixtures): the Java tests' reference.
```

## Development setup

Prerequisites: a JDK 25, Python 3.11+ (3.12 recommended), [uv](https://docs.astral.sh/uv/),
Node.js 24 with npm, and PostgreSQL 16 (Docker Compose starts one).

```bash
# Python environment (the dev tools the harness and scripts use) into .venv
uv sync

# Web app
cd apps/web && npm ci && cd ../..

# The database and the API's jar
docker compose up -d postgres
./gradlew :archsim-app:bootJar

# The API, a worker and the web app, each in a terminal of its own
java -jar archsim-app/build/libs/archsim-app.jar api   # http://127.0.0.1:8000 (docs: /api/v1/docs)
make worker                                            # simulates the runs the API queues
cd apps/web && npm run dev                             # http://localhost:3000

java -jar archsim-app/build/libs/archsim-app.jar seed  # optional: one project per example file
```

The API and the worker both default to compose's PostgreSQL
(`postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim`); set `ARCHSIM_DATABASE_URL` for
both to use another. The `Makefile` wraps these commands (`make setup`, `make dev-db`,
`make api`, `make worker`, `make web`, `make test`, ...). On Windows without
`make`, run the underlying commands shown in the Makefile directly; nothing depends on a POSIX
shell.

**Windows path length.** If the checkout lives in a very deep directory, loading compiled
Python extensions (for example mypy's) can fail with "The filename or extension is too
long". Clone into a short path such as `C:\src\archsim`, or point uv at a short environment
directory: `set UV_PROJECT_ENVIRONMENT=C:\venvs\archsim` before `uv sync`.

## Checks

Run everything CI runs before opening a pull request:

```bash
# Python
uv run ruff check benchmarks scripts
uv run ruff format --check benchmarks scripts
uv run mypy benchmarks
./gradlew :archsim-cli:installDist             # the benchmarks' tests call it: a Java 25 on JAVA_HOME or the PATH
uv run pytest                                   # the benchmarks and the scripts

# Web
cd apps/web
npm run lint
npm run typecheck
npm test
npm run build
npx playwright install chromium   # once
npm run e2e                       # the main path in Chromium, against the build above

# Java (JDK 25; Gradle finds it, or downloads one, whatever JAVA_HOME says)
./gradlew build                   # formatting, Checkstyle, Error Prone and NullAway, tests, coverage
./gradlew :archsim-model:test     # the design model against the golden files
./gradlew :archsim-engine:test    # the engine's deterministic parts against the golden files
./gradlew :archsim-app:test       # the Java API, on an embedded PostgreSQL (no Docker needed)
docker compose up -d postgres
./gradlew :archsim-api-tests:apiTest -Parchsim.api.start=java     # black-box API tests
```

`./gradlew format` fixes the Java formatting; `./gradlew build` fails on any Checkstyle finding.
Java code follows [docs/java-conventions.md](docs/java-conventions.md): Spring Framework's style,
clean-code limits on size and complexity, tests named `method_state_result` and written first.
The black-box API tests start the chosen API once per server profile, each on a fresh database
of its own (`archsim_api_tests_*`) in the PostgreSQL above;
[archsim-api-tests/README.md](archsim-api-tests/README.md) says how to run them against servers
that are already running.

`npm run e2e` starts its own back end on :8002 (the Java API and a Java worker, on a throwaway
database `archsim_e2e_8002` in the PostgreSQL above, with the templates shortened
to 20 s) and `next start` on :3002, so a stack running on :3000/:8000 is left alone; set
`E2E_API_PORT`/`E2E_WEB_PORT` to move them, and `E2E_POSTGRES_URL` for another PostgreSQL. It
needs the API's jar (`./gradlew :archsim-app:bootJar`), a JDK 25 (`JAVA_HOME`, else `java` on
PATH) and a fresh `npm run build`; it makes its database with the PostgreSQL driver in the jar.
On a failure, `npx playwright show-trace apps/web/test-results/<test>/trace.zip` replays it step
by step. `npm run e2e:capture` retakes the README images in `docs/images/` and
records the demo GIF (it needs ffmpeg for the GIF and takes a few minutes).

`uv run ruff format benchmarks scripts` fixes the Python formatting. Tests must not
depend on wall-clock speed beyond generous limits, and simulations in tests should use short
durations.

## Working with AI assistants

[AGENTS.md](AGENTS.md) gives AI coding assistants this project's rules, through three skills in
`.claude/skills/`: `java-conventions`, `test-first` and `conventional-commits`. Their output is
held to the same checks and review as anyone's.

## Pull requests

- Keep each PR focused on one change. Explain *why*, not only *what*.
- Add or update tests for behaviour changes. Simulation changes need tests that check
  behaviour (direction and accounting), not just that code runs.
- Update docs when you change the schema, API, modeling assumptions or the UI workflow.
- A change that makes a significant decision (hard to reverse, a contract others rely on, or
  how accuracy is judged) adds an [architecture decision record](docs/adr/README.md) in the
  same PR, or supersedes an existing one.
- Write commit messages as [Conventional Commits](https://www.conventionalcommits.org):
  `type(scope): description`, in the imperative and starting in lower case, at most 100
  characters (`feat(engine): add a circuit breaker component`). Types: `feat`, `fix`,
  `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`; mark a
  breaking change with `!` after the type or scope. Commits carry no AI co-author trailer.
  `make hooks` (or `git config core.hooksPath .githooks`) checks each message as you commit,
  and CI checks every commit of a pull request (`scripts/check_commits.py`).
- Do not commit secrets, `.env` files, local databases (`*.db`) or generated reports.
- New dependencies need a reason in the PR description. No telemetry, no paid services.
- If you change component metadata (fields, ports, defaults), regenerate the web test fixture
  with `./gradlew :archsim-engine:generate`.

## Bugs

Report bugs with the bug report form, which labels the issue `bug`; if you open one another
way, add the label yourself. Keep the label for things that do not work as documented (crashes,
wrong results, broken accounting, results that are not reproducible from the same seed), not
for feature requests. The weekly count of opened and closed bugs in
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md#bugs-per-week) comes from this label.

## Changing simulation semantics

Users compare runs and rely on reproducibility, so engine changes are held to a higher bar:

1. Any change that can alter results for the same document and seed (event ordering, RNG
   stream usage, distributions, metric definitions, analysis thresholds) **must bump the engine's
   version**, `EngineVersions.ENGINE` in
   `archsim-engine/src/main/java/io/archsim/engine/version/EngineVersions.java`. Every report
   records the engine version, and comparisons warn when two runs used different versions.
2. The Java engine's tests replay the runs and golden files the Python engine 0.16.0 recorded
   ([parity/README.md](parity/README.md)), so a change to simulated results fails them; if
   expectations legitimately change, explain why in the PR. The analytic checks must keep
   passing: `QueueingTheoryTests` (M/M/c against Erlang C, Little's law),
   `StarterReferenceTests` (the starter's documented ranges) and `EstimateAgreementTests` (the
   instant estimate against the simulation), in archsim-engine's `simulator` tests.
3. Keep request accounting exact: every request ends in exactly one outcome, and the per-node
   invariant `arrivals == completed + failedDownstream + rejected + inProgress` must hold.
4. Update `docs/simulation-model.md` so the documented model matches the code.

Changing the document schema requires bumping `ArchitectureDocument.SCHEMA_VERSION`, adding an
upgrade step at the end of `Upgrades.CHAIN` (archsim-model's `io.archsim.model.upgrade`,
[ADR-0023](docs/adr/0023-upgrade-chain-on-the-json.md)), and a test that an old document still
loads. Changing the database schema requires a new Flyway migration in
`archsim-app/src/main/resources/db/migration/` (Flyway owns the schema,
[ADR-0032](docs/adr/0032-flyway-takes-over-from-alembic.md)). The Alembic migrations the schema
started from are gone; a database below their revision 0008 upgrades from the tag `alembic-0008`
first ([ADR-0063](docs/adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)).

## Adding a component type

New components (message broker, CDN, circuit breaker, ...) are added in the model and the
engine only: a config record, ports and validation in `archsim-model` (a `ComponentSpec`
registered in `StandardComponents`, with its catalog entry), and runtime behaviour in
`archsim-engine` (a `RuntimeComponent`, built in `Parts`). The API exposes it and the web UI
renders its palette entry and inspector form automatically.
[`docs/extending-components.md`](docs/extending-components.md) walks through the parts, on the
external API's.

## Questions

Open a GitHub Discussion or an issue labelled `question`.
