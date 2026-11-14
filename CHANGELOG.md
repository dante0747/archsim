# Changelog

Notable changes to ArchSim, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every simulation report also names the
engine version that made it; see [CONTRIBUTING.md](CONTRIBUTING.md#changing-simulation-semantics)
for when that version changes.

## [Unreleased]

### Changed

- **Cost of autoscaling (engine 1.1.0, report 1.16).** An autoscaled service is billed at the
  replicas its autoscaler settles at for the base load's work, within `minReplicas`–`maxReplicas`,
  no longer at the run's average replicas scaled to the base load and floored at the count it
  starts with; a Kubernetes cluster with its autoscaler is billed the nodes its pods need there,
  and one without it its `nodes`. Beside the month, every cost now prices an hour at the peak
  (`cost.hourly`), with each line's instances there (`peakInstances`), so the peak's cost is in
  sight ([ADR-0074](docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md),
  [What a month is](docs/pricing.md#what-a-month-is)). No template's monthly cost changes.

## [1.0.0] - Draft, not released yet

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
- **Running it:** a Docker Compose stack (PostgreSQL, migrations, the API, workers and the web
  app), and workers on any number of machines sharing a run queue in PostgreSQL.

### Changed

- The engine, the API, the CLI and the MCP server moved from Python to Java 25
  ([the port's plan](docs/proposals/java-25-port.md)). The Java engine 1.0.0 reproduces the
  frozen Python engine 0.16.0's recorded runs figure for figure
  ([java-parity.md](docs/validation/java-parity.md)), about 14 times faster on the templates
  ([docs/performance.md](docs/performance.md)).
- The accuracy benchmarks' harness and the repository's tools moved from Python to Java as well:
  [archsim-benchmarks](archsim-benchmarks/README.md), run as
  `./gradlew :archsim-benchmarks:run --args="<command>"`, and [archsim-tools](archsim-tools/README.md)
  (the commit-message check, CI's changed parts and the price refresh). Building ArchSim and
  contributing to it no longer need Python or uv ([CONTRIBUTING.md](CONTRIBUTING.md#development-setup)).
  The one Python left is a benchmarked service, `benchmarks/service-python`, which builds in its
  own container.
- ArchSim is licensed under the GNU AGPL-3.0-only, with a commercial license available from the
  copyright holder ([ADR-0067](docs/adr/0067-license-under-agpl-3.0-with-a-commercial-license.md)).

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

[Unreleased]: https://github.com/dante0747/archsim/commits/main
