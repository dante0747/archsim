# Changelog

Notable changes to ArchSim, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every simulation report also names the
engine version that made it; see [CONTRIBUTING.md](CONTRIBUTING.md#changing-simulation-semantics)
for when that version changes.

## [Unreleased]

### Added

- **A privacy notice for a hosted server.** With `ARCHSIM_PRIVACY_URL`, which the web server
  reads at runtime, the home page's footer and the sign-in page link "Privacy" to it
  (`.env.example`, `deploy/hosted.env.example`).
- **The demo's privacy notice** at [archsim.io/privacy/](https://archsim.io/privacy/), linked from
  the website's footer: who runs app.archsim.io and where, what it stores and for how long, its
  cookies and logs, and how to have your data deleted.

### Fixed

- "Try it out" in the API docs (`/api/v1/docs`) calls the origin the page came from, where it
  called the host the API saw, which behind the web server is an address no browser can reach.
- The API docs name the ArchSim API at ArchSim's version, with what it is, that its results are
  estimates, its license and a link to the guide, where they said "OpenAPI definition" at "v0".
- A hosted server's home page no longer says ArchSim "runs on your machine" with "no account":
  it offers trying it there, or running your own with one command, and "no account needed" only
  where it offers sandboxes. A local install says what it said.
- On a phone, a sandbox's guest is offered the design's JSON to take to a computer, not a link
  that opens only in the browser the sandbox belongs to.
- The accuracy page says near its top that every run was measured on one laptop under Docker
  Desktop, and that the benchmarks rerun when a person starts them (the weekly and release runs
  stay off until a run passes on Linux), where it said they rerun every week. It and the
  website's home page call the experiments small synthetic services on real PostgreSQL, Redis and
  Kafka.
- Smaller things in the app: run pickers name each run's short id; the projects page counts a
  sandbox's projects against its cap ("4 of 5 in Sandbox"); a sandbox's end names your time zone;
  the browser tab names the open project; and Results' queueing-formulas line links "What the
  formulas leave out" instead of a stray "not simulated".
- A finding that counts one request says so: `1 request failed at API`, `1 client request failed
  because of them`, `refused 1 call`, where it said `1 requests`. Only words change; the engine
  stays at 1.1.1.
- The landing page's "Decide" figures and the hero's p95 goal are a real run's of Black Friday on
  Kubernetes, which the engine's tests keep current: $1,434 a month where it said $1,278, and the
  template's 500 ms goal where the hero said 300.

## [1.1.0] - 2026-11-16

Host ArchSim on a public server of your own, and let visitors try it there without an account.

### Added

- **A kit for hosting a public server**, in `deploy/`: the release's images with sign-in behind
  Caddy, which serves HTTPS with certificates it renews itself, or a Cloudflare Tunnel, which opens
  no port; a nightly database backup; and run limits for a public demo, on any server with Docker
  from 2 cores and 4 GB, amd64 or arm64.
  [Hosting a public server](docs/deployment.md#hosting-a-public-server) goes from an empty server to a
  running site, and from one release to the next; an optional workflow deploys the project's own
  demo over SSH. A release now also checks that the kit runs its images.
- **Try it without signing in.** With `ARCHSIM_SANDBOXES=true` on a hosted server, the sign-in
  page offers a sandbox for 24 hours with no account: a guest owns a sandbox workspace (five
  projects, small runs) until the API deletes it with everything in it. `POST /auth/sandbox`
  starts one; `GET /auth/config` and `GET /auth/me` say whether sandboxes are offered and when a
  guest's sandbox goes, which the app shows under its header. Starts are limited per address and
  to 500 at once; guests make no team workspaces, API tokens, invitations or share links
  ([ADR-0079](docs/adr/0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)).

### Changed

- The database gains its second migration (V2: `users.expires_at`), so the API migrates it
  before workers of this release start.
- The accuracy page links the files it names (the validation report, the calibration guide, the
  parity report, the benchmark results and their workflow) to the repository on GitHub.

### Fixed

- On a phone, the home page's feature grid fits the screen instead of cutting its tiles' right
  edges.

## [1.0.0] - 2026-11-15

The first public release. ArchSim predicts how a system design behaves under load, failures and
cost, and publishes how accurate it is. Start with [Getting started](docs/getting-started.md).

### Highlights

- **A studio for distributed systems.** Four workspaces around one canvas (Design, Test, Results
  and a Dashboard) and one Simulate button. 25 ready-made templates, from a cache-aside starter to
  Kafka pipelines, Cassandra quorums and serverless bursts.
- **Real technology behaviour.** PostgreSQL, MySQL, SQL Server, MongoDB, Cassandra, DynamoDB-style
  stores, Elasticsearch, Redis, Valkey, Memcached, Kafka, RabbitMQ, SQS-style queues, Nginx, Envoy,
  Kong, Kubernetes, serverless functions and third-party APIs, each with the behaviour its
  documentation describes and a link to it. A core set is ArchSim's focus; the rest is marked
  experimental.
- **A deterministic Java engine.** A discrete-event simulator on Java 25 moves every request
  through every hop, queue, lock and retry. The same document and seed give the same result digest
  in the web app, the API, the CLI and the MCP server.
- **A straight answer.** A verdict against your objectives, the bottleneck, what to fix first, the
  breaking point and the monthly cost on real AWS instance types.
- **Published accuracy.** [docs/validation.md](docs/validation.md) checks predictions made before
  measuring against nine experiments on real services, misses included.
- **One command to run it.** `docker compose up` starts the whole stack from ArchSim's release
  images, for amd64 and arm64, with nothing but Docker installed.

### Added

- **Design:** a parts library with technology logos; mobile, web and API-consumer clients with
  their own timeouts, retries and traffic shares; CPU, memory and storage per part, auto-sized for
  the peak load when left empty; a design review for single points of failure, missing timeouts,
  retry amplification, timeout inversions and pool exhaustion.
- **Test:** a test plan with traffic, objectives and failures (outages, slowdowns, link latency,
  spikes), chaos pads, and a second-by-second replay on the canvas and the timeline.
- **Results:** the answer, a cost-against-latency comparison of every scenario, a breaking-point
  sweep, findings in plain language, the calculations behind each number, sampled request traces
  and a Markdown report.
- **Dashboard:** Grafana-style panels with a synced crosshair, per-component signals, annotations
  and data inspection.
- **Calibration** from Prometheus metrics or an OpenTelemetry trace export, with every fitted input
  marked measured and a replay of what was observed ([docs/calibration.md](docs/calibration.md)).
- **Import** from docker-compose, Kubernetes manifests or a Terraform plan, and **export** to
  docker-compose, Kubernetes manifests or Mermaid ([docs/import.md](docs/import.md)).
- **Prices** from an AWS us-east-1 on-demand snapshot: parts run on the cheapest real instance
  type that fits, and every cost line cites it ([docs/pricing.md](docs/pricing.md)).
- **An SLO gate for CI:** `archsim check` and a GitHub Action that comments on pull requests
  ([docs/ci.md](docs/ci.md)).
- **The `archsim` command line:** validate, analyze, run, compare, calibrate, import, export,
  check, catalog and schema ([archsim-cli](archsim-cli/README.md)).
- **AI agents:** an MCP server over stdio for coding agents, and WebMCP tools in the web app for
  agents in the browser ([docs/agents.md](docs/agents.md)).
- **Teams (optional):** GitHub and Google sign-in, personal and team workspaces with viewer, editor
  and owner roles, invitations, API tokens, rate limits, comments, sign-offs, a history of every
  change and read-only share links ([docs/deployment.md](docs/deployment.md)).
- **Peak hours a month (schema 1.8).** `workload.peakHoursPerMonth` says how many hours a month
  the traffic runs at its pattern's peak; the monthly cost bills those hours at the peak and the
  rest at the base load, line by line, and the cost per million requests counts both loads'
  requests; `cost.peakHours` reports the hours it billed. It defaults to 0 (the whole month at
  the base load), is left out of a document while 0, and is set in Design → Traffic for a burst, a
  ramp or a trace ([ADR-0074](docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)).
- **ArchSim's own version**, 1.0.0, kept apart from the engine's: `archsim --version` prints
  `archsim 1.0.0, engine 1.1.1`, `GET /meta` gives `version` beside `engineVersion`, and the MCP
  server's `serverInfo.version` is ArchSim's
  ([ADR-0076](docs/adr/0076-version-archsim-apart-from-its-engine.md)).
- **Running it:** a Docker Compose stack (PostgreSQL, migrations, the API, workers and the web
  app) from release images on GitHub's container registry, `ghcr.io/dante0747/archsim-app` and
  `archsim-web`, for amd64 and arm64; `make up-local` runs your own build instead
  ([ADR-0077](docs/adr/0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)). Workers
  run on any number of machines, sharing a run queue in PostgreSQL.

### Changed

- The engine, the API, the CLI and the MCP server moved from Python to Java 25
  ([the port's plan](docs/proposals/java-25-port.md)). The Java engine 1.0.0 reproduces the
  frozen Python engine 0.16.0's recorded runs figure for figure
  ([java-parity.md](docs/validation/java-parity.md)), about 14 times faster on the templates
  ([docs/performance.md](docs/performance.md)).
- **Cost of autoscaling (engine 1.1.0, report 1.16).** An autoscaled service is billed at the
  replicas its autoscaler settles at for the base load's work, within `minReplicas`–`maxReplicas`,
  no longer at the run's average replicas scaled to the base load and floored at the count it
  starts with; a Kubernetes cluster with its autoscaler is billed the nodes its pods need there,
  and one without it its `nodes`. Beside the month, every cost prices an hour at the peak
  (`cost.hourly`), with each line's instances there (`peakInstances`)
  ([ADR-0074](docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md),
  [What a month is](docs/pricing.md#what-a-month-is)).
- **Black Friday's sale day.** The Black Friday on Kubernetes template bills 24 peak hours a
  month, and a new scenario, "No autoscaling (14 fixed pods)", sizes the checkout for the peak all
  month: $843.63 a month with the HPA against $1,434.02 for 14 fixed pods. The templates hold 125
  scenarios.
- The accuracy benchmarks' harness and the repository's tools moved from Python to Java as well:
  [archsim-benchmarks](archsim-benchmarks/README.md), run as
  `./gradlew :archsim-benchmarks:run --args="<command>"`, and [archsim-tools](archsim-tools/README.md)
  (the commit-message check, CI's changed parts and the price refresh). Building ArchSim and
  contributing to it no longer need Python or uv ([CONTRIBUTING.md](CONTRIBUTING.md#development-setup)).
  The one Python left is a benchmarked service, `benchmarks/service-python`, which builds in its
  own container.
- ArchSim is licensed under the GNU AGPL-3.0-only, with a commercial license available from the
  copyright holder ([ADR-0067](docs/adr/0067-license-under-agpl-3.0-with-a-commercial-license.md)).

### Fixed

- **The base load's rates after a run (engine 1.1.1).** When no time bucket of a run's window is
  at the base load (a ramp that passes it during the warm-up), the base's per-request rates come
  from the buckets nearest it, no longer from the whole window's mean scaled down, which the
  failures and timeouts at the peak moved. The peak's rates do the same when no bucket is at the
  peak. No template's monthly cost changes.
- **Labels on the Cost vs. speed map.** A run's label no longer covers another run's marker or
  label, or the objective line's label: it moves to its point's other side, or a line down or up
  with a leader to its point. Runs drawn at one point share one label that names each.

### Removed

- The Python engine, its API and its MCP server. The tag `engine-0.16.0` keeps the engine and the
  MCP server.
- The SQLite development mode: the API needs PostgreSQL
  ([ADR-0016](docs/adr/0016-drop-sqlite-development-mode.md)).
- Plugin entry points for third-party component models.
- The Python benchmark harness and the repository's Python scripts. The tag `cpython-recorders`
  keeps them.
- **Breaking:** the CLI's hidden `archsim functions` command, which answered the Python benchmark
  harness's calls into the engine. A script that called it now gets an unknown command (exit
  code 2); call the engine's classes in process instead, as `archsim-benchmarks` does
  ([ADR-0073](docs/adr/0073-remove-archsim-functions-with-the-python-harness.md)).

### Upgrading from a pre-release

- A database below the Alembic revision 0008 upgrades from the tag `alembic-0008` first; Flyway
  takes it from there ([ADR-0063](docs/adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)).
- An MCP client set up with `uv run --directory /path/to/archsim archsim-mcp` needs the Java
  server's command instead ([docs/agents.md](docs/agents.md)).
- `docker compose up` runs the release images and builds nothing: run your own build with
  `make image` and `make up-local` (`docker-compose.local.yml`) instead of
  `docker compose up --build` ([docs/getting-started.md](docs/getting-started.md#your-own-build)).
- The `commit-msg` hook checks messages with Java's source launcher and needs JDK 22 or later: it
  runs `$JAVA_HOME`'s Java when that is set, else `java` on the `PATH`
  ([CONTRIBUTING.md](CONTRIBUTING.md#development-setup)).

### Known limitations

- No technology counts as validated yet. On the five held-out experiments the median error in the
  predicted breaking point ran from -6% to +70%, and four of the five over-predicted capacity
  ([docs/validation.md](docs/validation.md)).
- Not modeled: OOM kills and restarts, bandwidth and packet loss, deadlocks and range locks,
  random failures (faults are scheduled), parallel fan-out calls and regions
  ([docs/simulation-model.md](docs/simulation-model.md#what-is-not-modeled)).
- Sign-in is covered by automated tests but has not been tried with real GitHub or Google OAuth
  apps yet.
- Calibration has been checked on ArchSim's own fixtures, not yet against a live Prometheus or a
  real OpenTelemetry export.
- Exports are scaffolding to review, not production configuration.
- The editor is designed for desktop screens.

[Unreleased]: https://github.com/dante0747/archsim/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/dante0747/archsim/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/dante0747/archsim/releases/tag/v1.0.0
