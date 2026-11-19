# Changelog

Notable changes to ArchSim, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Every simulation report also names the
engine version that made it; see [CONTRIBUTING.md](CONTRIBUTING.md#changing-simulation-semantics)
for when that version changes.

## [Unreleased]

### Changed

- **A vCPU counts as a hyperthread unless the design or its instance family says otherwise (schema
  1.11, engine 1.6.0, ADR-0091).** A service, database or cache with 2 or more vCPU, on a design
  without a `host`, runs on hyperthread pairs at the first sibling speed that applies: its own
  `siblingSpeed` (1 means whole cores); the design's new `architecture.siblingSpeed`; 1 when the
  instance type the cost prices for it is of a family whose vCPUs are whole cores (Graviton and
  other Arm families, AWS T2 and its AMD families from M7a, listed with their sources on
  [assumptions.md](docs/assumptions.md)), when the service runs serverless, or when it runs as
  pods on Kubernetes; else 0.6, the middle of what published measurements of request-serving work
  give, from vendors' documentation and others' benchmarks rather than a measurement of ArchSim's.
  Before, every such vCPU was a whole core, so a part bound by its CPU on most x86 instances was
  predicted up to 1.67 times the capacity it has. One resolver applies the rule when a run's
  document is resolved and writes into each part that sets no speed the one it counts at
  (inferred, or the design's below 1), so the simulation, the instant estimate, the review, the
  cost, sweeps and checks read the same speed, and the run's `input` says what ran. Auto-sizing
  sizes a part on the default for both kinds from the same work and gives it the cheapest type
  that covers its own family's need, a type set on the part while it covers its need (one too
  small gives way to the type the part gets, which the run's input then records, so the cost
  prices that one and the run counts its vCPUs); at generic prices, where a shape has no family,
  it sizes it on pairs. A part that keeps one vCPU but lands on a shape or type of two or more,
  for its memory or because none smaller is sold, now needs that machine to cover its need on
  pairs (at a sibling speed of 0.3 it got 2 vCPU and refused requests; it gets 4). Each sized
  part's summary says how its vCPUs counted (`vcpus`: the speed, why and the words, outside the
  result digest), the report's model notes name the parts on the default and the pods (counted as
  whole cores, which is optimistic on an x86 node pool), and the editor's design overview shows
  the design's sibling speed and clears it in one click. **A saved design keeps its results:** the
  upgrade to 1.11 writes `architecture.siblingSpeed: 1` once and leaves its parts as they are, so
  its runs and their result digests are what they were; clearing the setting puts it on the
  default. The accuracy benchmarks' designs are written at schemas 1.0 and 1.7, so no simulated
  knee moves. An API of 2 replicas (10 ms of CPU a request) and a PostgreSQL at 480 req/s,
  auto-sized at schema 1.11: at generic prices each gets 8 vCPU where it got 4, the month costs
  $747 where it cost $374, and the API's CPU ceiling is 960 req/s where it was 800, its vCPUs 31%
  busy at the peak where they were 60%; with the price list limited to x86 families (c6i, m6i,
  r6i) they get c6i.2xlarge and db.m6i.2xlarge where they got the xlarge, $1,017 a month where it
  was $509; on AWS unrestricted they keep c6g.xlarge and db.m6g.xlarge, whose vCPUs are whole
  cores, at $432. Sized by hand at 4 vCPU on c6i and db.m6i, the same design's API fills at 480
  req/s where it filled at 800, and its p95 at 480 req/s is 244 ms where it was 39 ms.

### Fixed

- **The instant estimate counts a cache's commands as a run does (engine 1.5.2,
  [ADR-0092](docs/adr/0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md)).**
  A sharded cache's CPU was its whole load over one shard's cores, where each shard has its own;
  it is now the share of every shard's cores busy, as a run reports it and as the estimate's
  slots already were. A read-through cache's fill of a key it missed is one more command on the
  shard's executor and cores: it now counts in the cache's slots, its CPU and the queue its
  commands wait in, where it counted only in a read's time. The hot-key feed's six-shard Redis
  reads 4.3% CPU at its 2,000 req/s, beside the 5.3% its run measures, where it read 26%, and
  the design saturates at about 11,217 req/s where it read 7,692; the benchmark designs' Redis
  reads 22.8% and 23.1% busy at the estimate's knee, where it read 19.3% and 17.9%, and neither
  knee moves. A hot key's shard is still busier than the estimate's even spread says. A report's
  `estimate` changes for the same design and seed, so the engine moves from 1.5.1 to 1.5.2;
  nothing a run simulates changes, and its result digest is the same.
- **The instant estimate no longer counts a commit's log flush as database CPU (engine 1.5.1,
  [ADR-0089](docs/adr/0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md)).**
  A write waits for the log flush on its connection with no core at work, as a run models it
  and as the sizing counts it. The estimate applied the CPU share to the query and the flush
  together, which filled a database's cores too early wherever it writes. A write still holds
  its connection through the flush, and the flush still counts in the latency; on threads a host
  machine slows, only the query's CPU share takes longer. Black Friday's Orders DB reads 52%
  busy at its 480 req/s peak, as its sizing counts it, where it read 56%, and the starter's
  PostgreSQL 27%, where it read 28%. A report's `estimate` changes for the same design and
  seed, so the engine moves from 1.5.0 to 1.5.1; nothing a run simulates changes, and its result
  digest is the same. On the accuracy benchmarks the estimate's knee for go-mixed moves from
  8,650 to 9,900 req/s (the simulator's is 9,916) and no longer calls Kafka's PostgreSQL
  overloaded; on pg-hot-row, where a row lock the estimate does not model binds, it moves from
  1,712 to 3,000 req/s, further from the measured 947
  ([docs/validation.md](docs/validation.md#against-simple-estimates)).

## [1.3.0] - 2026-11-17

Results that agree with themselves and with the design, after a review of the live demo, and a
freshness objective for asynchronous designs. Results change: the engine moves from 1.1.1 to
1.5.0, so a design simulated again can give other figures. Designs move to schema 1.10: older ones
are upgraded when read, which leaves a third-party API's price of 0 unset (the last entry under
Changed).

### Added

- **A freshness objective, and lost messages (engine 1.4.0, schema 1.10, report 1.19,
  [ADR-0084](docs/adr/0084-judge-asynchronous-designs-by-their-messages-too.md)).** A new
  objective, `messageAgeP95Ms`, judges how old a message is when processed at the design's slowest
  queue, and a run with queues reports its `lostMessages` (dead-lettered or dropped). The
  asynchronous templates set a freshness target: the Kafka order pipeline, whose orders wait
  21.0 s (p95) for a consumer, now reads "No, it doesn't hold up" against its 10 s, where it said
  "Yes, it holds up".

### Changed

- **A third-party API's price says where it came from (engine 1.2.0, schema 1.9, report 1.17,
  [ADR-0081](docs/adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)).**
  `costPerMillionRequests` may be left empty: the API then costs its technology preset's price,
  with a note naming the preset, else it is not priced, and the cost lists it in `unpriced` and
  says the month leaves it out. The app marks the total ("leaves out Payments API (no price)"),
  labels a price "set on the part", "preset price" or "not priced", and no longer says "your
  price" for a figure nobody set, and a price that is a preset's, written on the part or not, is
  labelled the preset's, as a part's hourly price is. The Kafka template's E-mail API bills
  144.92 M calls a month at Amazon SES's list price, $100 a million ($14,492), where it billed
  $0.00; the templates' other free APIs set $0 and say why. The payments and identity-provider
  presets set no price per call.
- **A cache's hit ratio counts the lookups it refused (engine 1.2.1,
  [ADR-0082](docs/adr/0082-count-a-caches-refused-lookups-in-its-hit-ratio.md)).** A read the
  cache turned away, down or by its queue, counts against its hit ratio (`refusedLookups` among
  its counters), and its finding says what became of those reads: the starter losing Redis for
  15 s reports a hit ratio of 57%, 50 reads/s missed and 99 refused, which API Servers read from
  PostgreSQL, where it said 80% and 50 reads/s while PostgreSQL's arrivals rose from 112 to 188
  req/s.
- **A part's shares busy are named, and the estimate is compared like with like (engine 1.3.0,
  report 1.18, [ADR-0083](docs/adr/0083-name-each-utilisation-and-compare-like-with-like.md)).**
  A database of several instances reports its busiest instance's CPU
  (`busiestInstanceCpuUtilization`) and sets its CPU ceiling where that instance fills, where it
  spread every request's CPU time evenly over the primary and its replicas. The Results page sets
  the estimate's CPU against the run's cores busy (it read the slots) at the load the run sent
  the part (it set the peak's against the run's average), and the components table names its
  columns "Slots busy", "CPU busy" and "Ceiling". Black Friday's Orders DB read "56% CPU busy
  (simulated 2%)", "Util 2%" and a ceiling of 2,404 req/s at 303; it now reads "Orders DB's
  busiest instance 35% CPU busy at the 303 req/s the run sent it (simulated 33%; 56% at the
  480 req/s peak)", CPU busy "16% · busiest 33%" and a ceiling of 927 req/s. The dashboard's
  "CPU utilization" panel, which plots the share of a part's own work, is "Own processing".
- **Availability counts lost messages (engine 1.4.0, report 1.19,
  [ADR-0084](docs/adr/0084-judge-asynchronous-designs-by-their-messages-too.md)).** In a run with
  queues, `availability` counts a request that was answered but lost a message it published
  (dead-lettered, or dropped when published) as failed, and the availability objective judges it.
- **A Kafka broker is sized as ArchSim measured it, and idle consumers are named (engine 1.5.0,
  report 1.20,
  [ADR-0085](docs/adr/0085-size-a-kafka-broker-as-measured-and-name-idle-consumers.md)).** A Kafka
  broker's size comes from ArchSim's Kafka experiment, a rough fit over the steps the broker kept
  up with: 0.39 fixed cores, 0.053 ms of CPU per message and a 973 MiB working set, listed as
  measured on the assumptions page. The Kafka order pipeline's broker needs 0.65 vCPU and 973 MiB,
  where it said 0.01 vCPU and 11 MiB, on the same kafka.m7g.large. A queue with more consumers
  than partitions reports the idle ones, and its finding says why, in the design review's words:
  "16 of the 32 consumers sat idle: only one consumer per partition is active, so 16 partitions
  keep at most 16 of them busy". It advises adding partitions, never consumers that would sit idle
  too.
- **Advice after a cache's outage asks only for what is missing.** The suggested fix names the
  fallback settings the cache's callers lack; when they already carry on and read the database
  on a miss, it asks for capacity for the fallback's reads, or a replicated cache, where it told
  you to turn on both settings again. The design review does the same for a part that already
  reads the data store on a miss.
- Upgrading a design to schema 1.9 reads a third-party API's price of 0 as unset, unless its
  provenance says a person stated it: saving wrote the old default of 0 out, so it cannot be told
  from a price nobody set. The app reads a project it opens, and a file it imports, the same way.
  Type 0 again for an API that is free.

## [1.2.0] - 2026-11-16

A privacy notice for hosted servers, pages that claim only what is true, and working API docs.
Results do not change: the engine's version (1.1.1) does not move.

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
- The website's browser bar takes the colour of the theme it shows, dark unless light is chosen,
  where it followed the system's; and its home page's description fits a search result.
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

[Unreleased]: https://github.com/dante0747/archsim/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/dante0747/archsim/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/dante0747/archsim/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/dante0747/archsim/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/dante0747/archsim/releases/tag/v1.0.0
