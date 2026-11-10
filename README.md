# ArchSim

**A flight simulator for your architecture.**

ArchSim is an open-source, local-first studio for designing distributed systems and testing them
before you build them. Model the stack you actually run (PostgreSQL with read replicas, sharded
MongoDB, Cassandra quorums, Redis, Kafka, Kubernetes clusters and pods, serverless functions,
third-party APIs), push traffic and failures through it with a discrete-event engine that moves
every single request, replay what happened second by second, and decide with SLOs, cost and
capacity curves. Same document and seed, same result.

> **Results are estimates, not production guarantees.** Every number comes from the model you
> drew and the assumptions you entered. Without calibration against real telemetry, trust
> comparisons between scenarios more than any single absolute value. See
> [docs/simulation-model.md](docs/simulation-model.md).

![Demo, about one minute: the cache-aside starter (clients, load balancer, API servers, Redis, PostgreSQL) on AWS us-east-1 prices is opened and simulated, then the "Lose Redis Cache" chaos pad is pressed; the replay shows the outage from 28 to 43 s on the timeline, the API servers saturating while every read goes to PostgreSQL, and the test plan turning to "Didn't hold up": success fell to 92% during the outage](docs/images/cache-outage.gif)

*Losing Redis on the cache-aside starter: press a chaos pad and the verdict flips within seconds.*

![The Test workspace paused at 103.3 s of the Black Friday on Kubernetes ramp (300 → 1,500 req/s): Checkout API has scaled out to 3 pods, link labels show live request rates, the timeline below shows the ramp, events, latency, failures and throughput per part, and the test plan reports that the design did not hold up: p95 561 ms against the 500 ms objective and 98.8% availability against 99.5%](docs/images/editor-results.jpg)

*The Test workspace replaying the Black Friday on Kubernetes template.*

## What you can do

ArchSim has four workspaces around one canvas, switched from the top bar: **Design → Test →
Results**, plus a **Dashboard** (keys 1, 2, 3, 4). One **Simulate** button (Ctrl/⌘ Enter) runs the design from wherever
you are; results land on the canvas right away. A short guided tour opens on the first visit
(and from the ⋯ menu), and a small hint on the canvas says what to do next, for example adding
API servers behind a lone Clients box.

**Design: draw it with real technologies**
- Resource specs on every part: CPU cores, memory and (databases, queues) storage per
  instance, with one-click common sizes and an illustrative price. They change the run where
  it is defensible: fewer cores than workers makes CPU work queue, a service refuses requests
  past its memory, a database whose hot working set does not fit in memory sends reads to
  disk, a cache evicts least recently used keys. Left empty they are **auto-sized**: each part
  gets the CPU, memory and storage it needs for the design's peak load (CPU at a 60% target,
  memory for the requests, buffer cache or keys it holds, storage under 70% full), shown with
  how it was worked out and used in runs, prices and exports until you type your own. The
  engine does the sizing, so the CLI, the API and the web app simulate a design identically
  (same result digest).
- Clients as separate parts: **Mobile app** (iOS, Android, React Native, Flutter), **Web
  browser** (React, Next.js, Vue, Angular, Svelte) and **API consumers** (REST / OpenAPI,
  GraphQL), alongside the generic Clients box. Each has its own timeouts, retries and network
  latency on its link, and a traffic share: put all three in front of one gateway and the
  workload is split between them (50 / 35 / 15 by default). Results show success and latency
  per client.
- A parts library on the left with each technology's logo and a search box: relational (PostgreSQL, MySQL, SQL Server,
  distributed SQL), document (MongoDB replica sets and sharded clusters), wide-column
  (Cassandra), key-value (DynamoDB-style provisioned throughput), search (Elasticsearch),
  caches (Redis, Valkey, Memcached, CDN edge, Varnish), message queues (Kafka, RabbitMQ,
  SQS-style...), gateways (Nginx, HAProxy, Envoy, Kong...), services on VMs, Docker, Kubernetes
  (HPA autoscaling, pods on a cluster's nodes) or serverless (cold starts, concurrency limits),
  Kubernetes clusters (EKS-, GKE- and AKS-style, k3s), and third-party APIs
  (payments, e-mail, identity, LLM inference...).
- **Each technology brings the behaviour its documentation describes.** PostgreSQL locks rows, makes commits wait for
  its write-ahead log (sharing flushes between concurrent commits) and replays writes on
  replicas with a single worker, so heavy writes make replicas lag and serve stale reads.
  MySQL replays on four workers; SQL Server's reads wait for writers; MongoDB waits for a
  majority of its replica set, reads from the primary and caps concurrency with 128 tickets;
  DynamoDB meters capacity units and throttles a hot key at 1,000 writes per second per
  partition; Elasticsearch sends every search to every shard. Redis runs commands on one thread
  per shard and stalls while forking for snapshots; Kafka keeps each key in order on one
  partition and one consumer; RabbitMQ blocks publishers at its memory watermark; SQS delivers
  slow messages twice after the visibility timeout; Envoy answers 503 past 1,024 requests per
  target and nginx gives up after 60 s; Node.js runs CPU work on one event loop; JVMs pause for
  garbage collection and start slow until the JIT warms up; Lambda cold starts depend on the
  runtime and scale-out is rate limited. On a Kubernetes cluster every replica is a pod placed
  by its CPU and memory requests: when the nodes are full new pods wait as Pending until the
  cluster autoscaler's node is ready minutes later, then pull their image and start. The
  canvas draws each service's pods (ready, starting, Pending) and the cluster's nodes, live
  during replay. The inspector shows how the selected technology
  behaves, each behaviour links to the vendor's documentation, and every one is a setting
  (Auto by default) that you can override; each Auto value says whether it is documented or
  illustrative. [docs/assumptions.md](docs/assumptions.md) lists every such number, generated
  from the code.
- **Core and experimental technologies.** A core set comes first: generic, Node.js, Java and
  Go services (on VMs, containers, Kubernetes with HPA or serverless), PostgreSQL, Redis, Kafka,
  SQS-style queues, and Nginx and ALB-style load balancers (plus the generic part of each kind,
  which adds no technology behaviour). Every other technology carries an "experimental" badge
  in the parts library, the inspector, reports and exports: its behaviour rests on
  documentation and illustrative numbers. No technology is validated against measurements yet;
  that is the next step.
- **Calibrate from your telemetry.** Prometheus metrics (with a mapping file) or an
  OpenTelemetry trace export set service times, hit ratios, link latency and the traffic curve;
  each input is marked measured with its source, query, time range and fit error, and the
  calibrated model replays what was observed and reports its largest error, not only p95 (the
  bundled fixtures are generated by ArchSim itself, so they show the method, not real-world
  accuracy). Ctrl/⌘ K
  → Calibrate from telemetry, or `archsim calibrate`. See [docs/calibration.md](docs/calibration.md).
- **Import what you run.** A docker-compose file, Kubernetes manifests or a Terraform plan
  become a design, with a report of what was mapped, what was guessed (marked assumed), what
  was ignored and which links to confirm. ArchSim's own exports come back with the same
  structure (parts, technologies, replicas, autoscaling, links); timings, hit ratios, timeouts,
  workload and scenarios do not. Menu →
  Import from compose, Kubernetes, Terraform, or `archsim import`. See
  [docs/import.md](docs/import.md).
- **Prices from real machines.** A design names a price snapshot (provider, region, date):
  parts then run on the cheapest real instance type that fits them and every cost line cites
  it. ArchSim ships AWS us-east-1 on-demand list prices (EC2, RDS, ElastiCache, MSK, EBS,
  Lambda, SQS, ALB), refreshed monthly; templates and new projects use them, and every dollar
  figure names its source. Designs saved without a snapshot keep the generic illustrative
  rates. See [docs/pricing.md](docs/pricing.md).
- **An SLO gate in CI.** `archsim check` fails a pull request when a design misses its
  objectives or regresses against the base branch (p95, success rate, cost, breaking point),
  and a GitHub Action posts one sticky comment with the comparison. See [docs/ci.md](docs/ci.md).
- Every part is the same card: its technology logo on a solid tile in its family's colour, its
  name, one or two key settings, and after a run a 12-segment level meter (green, then yellow,
  then red, like an audio meter) that shows how busy it was. Links are rounded
  wires that attach on any of the four sides: drag from a dot on any side of one part to any side
  of another, in either direction. Traffic moves along them while a run replays.
- A "Studio" look, modelled on pro audio and design apps: charcoal panels, part families in
  Apple's system colours, a dot-grid canvas, and a transport bar at the top with replay buttons
  and an LCD readout (time, throughput, p95 against the goal, success) next to a red
  record-style Simulate button. Chaos pads on the canvas plan a failure and simulate in one hit,
  and the replay timeline shows one arrangement lane per part, in its family colour. Dark by
  default, with a matching light theme (see [docs/ui-redesign.md](docs/ui-redesign.md)).
- Click a part to edit it: its technology, a one-line last-run status, at most three key
  settings (response-time distributions as a one-line summary you click to edit), and "More
  settings" for everything else, notes and what the model covers.
- A design review flags single points of failure, missing timeouts, retry amplification along
  a path, timeout inversions, connection-pool exhaustion and more, with the numbers.
- Export the design as docker-compose, Kubernetes manifests (Deployments, Services, HPAs,
  StatefulSets) or Mermaid, as reviewed starting points.

**Test: traffic, failures and replay**
- The Test plan panel holds the last result and one plan card with three rows: Traffic,
  Must stay true (your objectives) and Failures. "Add failure" offers quick tests generated
  from the design ("lose the cache for 15 s", "database 4× slower", "+300 ms on a link", "3×
  spike") and custom outages, slowdowns and link latency. You can also drag the traffic line
  and the failure blocks on the timeline.
- Every run is recorded second by second and replays on the canvas: each component's
  utilization, queue and traffic at that second. The timeline below stays short: play, the
  time, speed, the traffic plan, events, latency against the objective and failures; "More
  detail" adds throughput and a heat lane per component.
- Every test gets a result against its objectives (what must stay true), including what
  happened during each failure.

**Results: four tabs**
- **Answer**: one sentence ("Yes, it holds up." or "No, it doesn't hold up." with the objective
  that failed and the bottleneck), the key numbers, what to fix first, objective tiles with
  editable targets, and monthly cost from per-component cost assumptions.
- **Compare**: cost against latency for every run and scenario (Pareto frontier, objective
  line), two runs side by side with the exact input differences, and the run history.
- **Breaking point**: stepped load sweeps that find where latency turns into a hockey stick
  and the highest load that still meets the objectives.
- **Details**: findings in plain language (bottlenecks, hot shards, hot rows, replica lag,
  throttling, autoscaling lag, consumer lag, wasted work), error budget, metrics over time,
  per-component drill-down, **Calculations** and **Request traces**, and the assumptions behind
  every number. A Markdown report is one click away.
  - **Calculations** shows the maths behind the numbers: each formula written out, again with
    the run's own values, and the result, next to what the simulation measured (utilization,
    Little's law, the M/M/c queueing prediction, the hottest row's lock, group commit, replica
    replay capacity, DynamoDB partitions, event-loop capacity...), plus where a typical
    request's time goes. The same formulas fold out under the selected part in Design.
  - **Request traces** follows about thirty sampled requests (evenly spread, the slowest, the
    median, failures) through every hop, wait, lock, log flush and GC pause on a waterfall.

**Dashboard: a Grafana-style view of every run**
- Rows of panels for the overview (throughput, p95 against the objective, error and success
  rate, the most saturated part, monthly cost), traffic, latency, saturation and errors, then
  one row per component with its own signals: replication lag, lock and commit waits, consumed
  capacity units, evictions, partition lag, redeliveries, GC pauses, event-loop lag, warming
  replicas, 504s and 503s.
- A crosshair synced across every panel, table legends you can click to isolate a series,
  drag-to-zoom on any chart, warm-up and failure windows as annotations, fullscreen and
  "Inspect data" (table and CSV) per panel, and Replay at 1x/4x/10x that draws the run live,
  shared with the Test workspace. Each panel's info popover says how its value is calculated.

**AI agents (WebMCP):** the app registers [WebMCP](https://webmachinelearning.github.io/webmcp/)
tools, so an agent in your browser can open a template, read the design, add and connect parts,
set traffic and objectives, run the design review, simulate and read the verdict, without
clicking. Every agent edit shows on the canvas, is announced in a toast and can be undone. WebMCP
is an experimental draft standard; see [docs/agents.md](docs/agents.md) for the tools and
browser support.

**AI coding agents (MCP):** `packages/mcp` is an MCP server over stdio for Claude Code, Claude
Desktop, Cursor and others: list part types and templates, validate, analyze, simulate (with
the verdict, findings and result digest), compare, find the breaking point, export and
calibrate, with the engine's limits and no browser or API server
(`claude mcp add archsim -- uv run --directory /path/to/archsim archsim-mcp`).

**Everywhere:** a command palette (Ctrl/⌘ K) with every action, keyboard shortcuts, and the
Studio theme, dark by default, in dark and light (the sun/moon button at the top right picks one
or follows the system). The screenshots in this README still show the previous look. Templates have their own gallery (`/templates`): each card draws the design with
its technology logos and flowing traffic, says what you will see happen, its level and key
numbers, with category filters, search and a featured template. Your saved projects are on their
own page (`/projects`). Projects live in
PostgreSQL, documents
import/export as versioned JSON, and every run stores its input, seed, engine version and
result digest so it can be re-run and verified bit for bit.

| Dashboard (Grafana-style, synced crosshair) | Results → Details → Calculations |
| --- | --- |
| ![The Dashboard workspace: overview panels (937 req/s, p95 561 ms against the 500 ms objective, 1.2% errors, 99% success, 79% peak saturation on Checkout API, $1,129 a month at AWS us-east-1 prices of October 2026), request and retry panels with a crosshair at 48 s, end-to-end latency, time in each component and queue wait per component](docs/images/dashboard.jpg) | ![Results → Details for the Orders DB (PostgreSQL): the technology behaviour in effect (row locks on writes, readers that never wait for writers, group commit, asynchronous replicas with lag), then calculation cards with this run's numbers filled in: arrival rate 303 req/s, capacity from slots, utilization 1.9%, Little's law 3.46 requests, the M/M/c queueing model and capacity from CPU](docs/images/calculations.jpg) |
| **Results → Details → Request traces** | **Design: how PostgreSQL behaves** |
| ![A traced 169 ms write on a waterfall: network legs, time in the API gateway and Checkout API, the Orders DB query and its write-ahead log flush, then a slow third-party payment call; the list beside it mixes evenly spread, slowest and failed requests](docs/images/traces.jpg) | ![The Design workspace in the light theme: the Black Friday diagram with Orders DB selected; the side panel shows its technology (PostgreSQL), how it did in the last run (2% busy, CPU caps it at 2,404 req/s, replica lag up to 18.3 ms), its settings and its resources sized as a real db.m6g.xlarge at $0.32 an hour (AWS us-east-1 prices of 2026-10-27), with the chaos pads below the canvas](docs/images/build.jpg) |
| **Results → Answer** | **Home** |
| ![Results → Answer in the light theme: "No, it doesn't hold up." Latency p95 561 ms against at most 500 ms and availability 98.8% against at least 99.5%, a note that p95 is within 15% of the objective from one seed with a Check 5 seeds button, Checkout API as the primary bottleneck, "Low confidence: 29 of 42 inputs are illustrative defaults" with how to raise it and the measured accuracy, then 937 req/s handled, p95 561 ms (608 ms counting failures as timeouts), 98.8% success and $1,129 a month at AWS prices, the queueing-formula estimate, a Review panel and the first findings](docs/images/scorecard.jpg) | ![Landing page in the dark theme: "Crash-test your architecture before your users do." with the second line in a blue-to-orange gradient, above a live product shot of the Black Friday design](docs/images/home.jpg) |
| **Results → Compare** | **Templates gallery** |
| ![Results → Compare: monthly cost against p95 for every scenario of the Black Friday template at AWS us-east-1 prices, with the p95 objective line and the Pareto frontier (which leaves out runs where more than 0.5% failed), and a table of the options; every scenario misses the objectives](docs/images/tradeoffs.jpg) | ![The templates gallery in the dark theme: counts (10 templates, 45 scenarios to compare, 18 technologies), category filters and search, and the featured Black Friday on Kubernetes template, marked Labs, drawn as logo tiles (Shoppers, Kong API gateway, Go checkout, Redis catalog cache, PostgreSQL orders, payments API) beside its description and Built with list](docs/images/templates.jpg) |

## How accurate is it?

Measured, not claimed: [docs/validation.md](docs/validation.md) calibrates ArchSim from
low-load runs of four real stacks, saves its predictions, then measures them with k6. In one run
on one Windows laptop (Docker Desktop) the predicted breaking point was off the measured one by
-10% for Node.js + PostgreSQL, -14% for cache-aside with Redis, +6% for Kafka and +97% for a single
hot row, which could not be measured reliably on that machine. Treat these as one run, not as an
accuracy figure: the measured breaking point itself moves between runs (Node.js + PostgreSQL:
1,895, 3,162 and 2,291 req/s in three runs), and the protocol was revised after the two
PostgreSQL experiments' high-load results had been seen, so only cache-aside and Kafka were first
run under the final protocol. At low load the predicted p95 was about 8.7 ms (+300%) high for
Kafka and 0.1 to 1.4 ms high for the other three, and the model's collapse past the knee is
sharper than the real one. No technology meets the page's proposed thresholds for "validated".
The page explains each gap; every measured repeat's numbers are committed, but k6's raw
summaries are not.

## Quick start

Prerequisites: **Java 25** (a JDK), **Python 3.11+** with [uv](https://docs.astral.sh/uv/),
**Node.js 20.9+** (24 recommended) with npm, and **PostgreSQL 16**, which Docker Compose can
start for you.

```bash
git clone <your-fork-url> archsim
cd archsim
uv sync
docker compose up -d postgres
./gradlew :archsim-app:bootJar
```

Then start the back end, each part in a terminal of its own: the API (it migrates the database
when it starts) and a simulation worker (three runs at once, with the heap
[archsim-app's README](archsim-app/README.md#the-workers-heap) asks for).

```bash
java -jar archsim-app/build/libs/archsim-app.jar api
```

```bash
ARCHSIM_MAX_CONCURRENT_RUNS=3 java -Xmx2g -XX:+ExitOnOutOfMemoryError -jar archsim-app/build/libs/archsim-app.jar worker
```

In another terminal, start the web app:

```bash
cd apps/web
npm ci
npm run dev
```

Open <http://localhost:3000>, pick **Cache-aside web API (starter)**, and press **Run**.

The parts find each other with their default: PostgreSQL at
`postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim` (compose's). For another database,
set `ARCHSIM_DATABASE_URL` for the API and the worker alike (on Windows PowerShell:
`$env:ARCHSIM_DATABASE_URL = "postgresql+psycopg://..."`, and likewise
`$env:ARCHSIM_MAX_CONCURRENT_RUNS = "3"` before the worker's `java` command; `make worker` runs
that command on a POSIX shell).
Use `127.0.0.1` rather than `localhost`: with Docker Desktop on Windows, `localhost` may try
IPv6 first and stall.
All settings are listed in [.env.example](.env.example).

### Everything in Docker

```bash
./gradlew :archsim-app:jibDockerBuild
```

```bash
docker compose up --build
```

The first command builds the Java API's image into your local Docker with Jib (`make image`; run
it again after changing the Java code); compose builds the rest. Then open
<http://localhost:3000>. Ports are bound to 127.0.0.1 because the stack runs without sign-in by
default. A one-shot `migrate` service updates the database first, and a `worker` simulates the
runs the API queues.

### Several API instances and workers

Runs wait in a queue in the database, and workers simulate them. To share the work, start more
workers against the same PostgreSQL (`docker compose up --build --scale worker=3`, or
`java -jar archsim-app.jar worker` on any machine that reaches the database, with the heap
[archsim-app's README](archsim-app/README.md#the-workers-heap) asks for). A run survives a
restart (it is queued again), and cancel works from any instance. A worker's runs share its
heap: a run that exhausts it stops the worker, whose runs are then requeued. Details and limits:
[docs/api.md](docs/api.md#background-runs-and-several-instances).

### Sign-in, teams and API tokens

Locally there is no sign-in: you are the owner of one `default` workspace. To let other people
in, run the API with `ARCHSIM_AUTH_MODE=oauth`, a secret key, the public address and a GitHub
or Google OAuth app (all listed in [.env.example](.env.example)). Then:

- people sign in with GitHub or Google and get a personal workspace; whoever's verified email
  is in `ARCHSIM_ADMIN_EMAILS` also owns `default`;
- the person menu at the top right switches workspaces, and **Settings** creates team
  workspaces, invites people as viewers, editors or owners, and makes **API tokens** for the
  CLI, CI and MCP (`Authorization: Bearer asim_pat_...` to `https://<host>/api/v1`);
- runs per hour and runs at once are limited per workspace (429 with `Retry-After`), and the
  server calls a Prometheus only on an allowlist of public hosts.

Use PostgreSQL and HTTPS, and keep the API's port private (the web server proxies it). The
threat model is in [SECURITY.md](SECURITY.md); the endpoints in
[docs/api.md](docs/api.md#sign-in-workspaces-and-tokens).

## A five-minute tour

1. On the home page, open **Black Friday on Kubernetes**: Shoppers → Kong gateway → a Go
   checkout service on Kubernetes (HPA 3→14 pods) → Redis, PostgreSQL with two read replicas,
   and a payments API. The **design review** (bottom left) already warns that reads fail when
   the cache is down, because there is no fallback path.
2. Press **2** for **Test**, then **Simulate** (Ctrl/⌘ Enter). The run is recorded and replays
   automatically: around t = 60 s the checkout service goes red while new pods start, then
   recovers. Scrub the timeline to any second; the canvas shows that moment. The **Test plan**
   panel reports whether what must stay true (p95 ≤ 500 ms, availability ≥ 99.5%) held.
3. In the Test plan, **Add failure** offers quick tests such as **Lose Catalog cache for 15 s**.
   Pick one and simulate again: the verdict flips, and the failure's block on the timeline shows
   exactly when and where it broke.
4. Press **3** for **Results**. **Answer** gives the verdict, the objectives and a monthly cost
   estimate; **Compare → Simulate all scenarios** puts every scenario on the cost-against-latency
   map (no autoscaling is cheaper but misses the objective).
5. **Breaking point** sweeps the load and finds the highest load that still meets the
   objectives. **Report** gives you a Markdown write-up; **Ctrl/⌘ K → Export** gives
   docker-compose, Kubernetes or Mermaid scaffolding.

The numbers come from the shipped examples with seed 42 and are illustrative assumptions.

## Command line

The engine works without the web stack, with the same answers as the web app:

```bash
uv run archsim validate examples/cache-aside-starter.json
uv run archsim analyze examples/cache-aside-starter.json
uv run archsim run examples/cache-aside-starter.json --scenario one-api-replica --out report.json
uv run archsim run long-steady-design.json --sampled   # answer a long steady run from a sample
uv run archsim compare examples/cache-aside-starter.json --a baseline --b three-api-replicas
uv run archsim calibrate prometheus examples/cache-aside-starter.json \
  --map packages/engine/tests/fixtures/calibration/starter-mapping.yaml \
  --recorded packages/engine/tests/fixtures/calibration/starter-prometheus.json -o calibrated.json
uv run archsim import compose docker-compose.yml -o design.json --report import-report.json
uv run archsim export k8s examples/cache-aside-starter.json -o manifests.yaml
uv run archsim check examples/cache-aside-starter.json --baseline-ref origin/main --fail-on regression
```

`analyze` prints the auto sizes, the cost estimate, the design review and each part's
technology tier without simulating.
Untrusted documents are bounded: the wall-clock limit and cancellation hold inside the event
loop, and a design whose one request would fan out into more than 10,000 calls is rejected
(`design.amplification`).

## Tests and checks

```bash
uv run pytest                                   # the engine, its tools and the migrations
uv run ruff check packages services benchmarks
uv run ruff format --check packages services benchmarks
uv run mypy packages/engine/src services/api/src packages/mcp/src
./gradlew build                                 # the Java code: format, Checkstyle, tests
cd apps/web && npm test && npm run lint && npm run typecheck && npm run build
```

The engine suite includes analytic checks against M/M/1 and M/M/c (Erlang C) queues, request
accounting invariants, determinism, and a directional reference benchmark on the starter
example. CI (GitHub Actions) also runs the migrations against PostgreSQL and the black-box
API tests against the Java API and its worker. `make` targets wrap
these commands (see the [Makefile](Makefile)).

## Repository layout

```
packages/engine/   Python simulation engine and domain model (no web dependencies)
archsim-app/       Java API (Spring Boot): projects, runs, workspaces, reviews, sharing, sign-in
archsim-model/     Java design model: the document's records, upgrades and validation
archsim-engine/    Java engine: the simulator and its deterministic parts (sizing, cost, ...)
services/api/      Python Alembic migrations and seed, until stage 6 of the Java port
apps/web/          Next.js editor: canvas, inspector, results and comparison
examples/          Example architectures (versioned JSON) with scenarios
docs/              Architecture, simulation model, schema, API, extension guide, roadmap
scripts/           Developer scripts (e.g. regenerate the web test fixture)
```

## Documentation

- [Implementation plan and assumptions](docs/plan.md)
- [Architecture](docs/architecture.md)
- [Architecture decision records: why ArchSim is built the way it is](docs/adr/README.md)
- [Simulation model: what is and isn't modeled](docs/simulation-model.md)
- [Assumptions: every default, documented or illustrative](docs/assumptions.md) (generated)
- [Calibrating from Prometheus and OpenTelemetry](docs/calibration.md)
- [Importing docker-compose, Kubernetes and Terraform](docs/import.md)
- [Prices and machine shapes](docs/pricing.md)
- [An SLO gate in CI](docs/ci.md)
- [Performance: what is fast, what is not, sampled mode](docs/performance.md)
- [How accurate is ArchSim? Benchmarks against real stacks](docs/validation.md)
- [Document schema](docs/schema.md)
- [HTTP API](docs/api.md) (OpenAPI at `/api/v1/openapi.json`, interactive docs at `/api/v1/docs`)
- [Adding a component type](docs/extending-components.md)
- [AI agents: the MCP server and the WebMCP tools](docs/agents.md)
- [Roadmap: calibration, Grafana, AI/MCP, more components](docs/roadmap.md)
- [Implementation status](IMPLEMENTATION_STATUS.md)

## Scope and limitations

Supported today: several client groups per design (Clients, mobile app, web browser, API
consumers) sharing one workload; load balancers, services (with autoscaling and serverless),
caches, databases, message queues and external APIs; synchronous acyclic call graphs;
open-loop synthetic workloads; scheduled faults (outage, slowdown, link latency); local
use without sign-in, or GitHub/Google sign-in with workspaces, roles and API tokens; optional
CPU, memory and storage specs (CPU contention, memory limits,
database buffer cache, LRU cache eviction); the technology behaviours listed above (row locks,
write-ahead-log flushes, GC pauses, cold starts and so on). Not modeled: OOM kills and
restarts, disk and network bandwidth, packet loss, deadlocks and range locks, random failures
(faults are scheduled), parallel fan-out calls, regions. The full list is in
[docs/simulation-model.md](docs/simulation-model.md#what-is-not-modeled). Without
`ARCHSIM_AUTH_MODE=oauth` there is no authentication: keep it on your machine. A hosted stack
and deploy guide are still to come; until then, read [SECURITY.md](SECURITY.md) before exposing
a server.
Grafana integration and an HTTP transport for the MCP server are on the roadmap, not
implemented; AI agents can use the stdio MCP server and, in the browser, the
[WebMCP tools](docs/agents.md).
Details: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## Troubleshooting

- **The web app says it cannot reach the API.** Start it as the [quick start](#quick-start)
  shows. If it runs elsewhere, set `ARCHSIM_API_URL` for the web server (default
  `http://127.0.0.1:8000`).
- **Runs stay queued.** No worker is running: start one (`make worker`, or the `worker` role of
  archsim-app's jar), with the API's `ARCHSIM_DATABASE_URL`.
- **Windows: `DLL load failed ... The filename or extension is too long`.** The checkout path is
  too deep for Windows' path limit. Clone into a short path such as `C:\src\archsim`, or put the
  virtual environment somewhere short with `UV_PROJECT_ENVIRONMENT`.
- **Next.js telemetry.** The npm scripts disable it by default; see
  [SECURITY.md](SECURITY.md).

## Contributing and license

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md); report vulnerabilities as described in
[SECURITY.md](SECURITY.md). ArchSim is licensed under the [GNU Affero General Public License v3.0](LICENSE).
