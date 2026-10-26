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

![The Test workspace in the dark Ops console theme, paused at 101.6 s of a Black Friday ramp: Checkout API has scaled out and runs warm, link labels show live request rates, the timeline below shows the traffic ramp, events, latency and failures, and the test plan panel reports that the design held up](docs/images/editor-results.jpg)

*The Test workspace replaying the Black Friday on Kubernetes template. A demo GIF is still to be recorded.*

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
- **Prices from real machines (machinery).** A design can name a price snapshot (provider,
  region, date): parts then run on the cheapest real instance type that fits them and every
  cost line cites it. ArchSim ships no snapshot yet; the generic illustrative rates stay the
  default. See [docs/pricing.md](docs/pricing.md).
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
PostgreSQL (or SQLite), documents
import/export as versioned JSON, and every run stores its input, seed, engine version and
result digest so it can be re-run and verified bit for bit.

| Dashboard (Grafana-style, synced crosshair) | Results → Details → Calculations |
| --- | --- |
| ![The Dashboard workspace in the dark Ops console theme: overview stat panels (947 req/s, p95 434 ms with a warning against the 500 ms objective, 0.24% errors, 99.8% success, 77% max saturation on Checkout API, $1,240 a month), traffic and retry panels, end-to-end latency, time in component and queue wait per component; a crosshair at 48 s shows the same moment on every panel](docs/images/dashboard.jpg) | ![Calculations for the Orders DB (PostgreSQL): formula cards for arrival rate, capacity from slots, utilization, Little's law, the M/M/c queueing model, the hottest row's lock (22% busy), group commit and replica replay capacity (158 writes/s against 192 writes/s per shard), each with this run's numbers filled in](docs/images/calculations.jpg) |
| **Results → Details → Request traces** | **Design: how PostgreSQL behaves** |
| ![A traced 216 ms write on a waterfall: network legs, time in the API gateway and Checkout API, the Orders DB query and its write-ahead log flush (hovered: at 42.7 ms for 0.85 ms), then a slow third-party payment call](docs/images/traces.jpg) | ![Design workspace in the standard light theme: the whole Black Friday diagram on the canvas with Orders DB selected; the side panel shows "How PostgreSQL behaves" with row locks, MVCC reads, group commit and asynchronous replicas, each with a Docs link](docs/images/build.jpg) |
| **Results → Answer** | **Home** |
| ![Results answer in the standard light theme: "Yes, it holds up." Meets all 2 objectives at 950 req/s; 947 req/s handled, p95 434 ms, 99.8% success, $1,240 per month; worth a look: the main cause of failed requests, and "Orders DB replicas lag up to 18.9 s behind the primary" with 3,158 of 8,363 replica reads stale](docs/images/scorecard.jpg) | ![Landing page in the dark theme: "Crash-test your architecture before your users do." with the second line in an orange gradient, above a live product shot of the Black Friday design](docs/images/home.jpg) |
| **Results → Compare** | **Templates gallery** |
| ![Cost vs. speed map in the dark theme: monthly cost against p95 for every scenario run, the p95 objective line and the Pareto frontier, with a table of options](docs/images/tradeoffs.jpg) | ![The templates gallery in the dark theme: counts (8 templates, 35 scenarios, 16 technologies), category filters and search, and the featured Black Friday on Kubernetes template drawn as logo tiles (Shoppers, Kong API gateway, Go checkout, Redis catalog cache, PostgreSQL orders, payments API) with traffic flowing along the links, beside its description, "Built with" list, key numbers and a Use this template button](docs/images/templates.jpg) |

## Quick start

Prerequisites: **Python 3.11+** with [uv](https://docs.astral.sh/uv/), **Node.js 20.9+** (24
recommended) with npm. Docker is optional.

```bash
git clone <your-fork-url> archsim
cd archsim
uv sync
```

Start the API (SQLite by default: no database to install). It applies migrations on startup:

```bash
uv run archsim-api serve
```

In a second terminal, start the web app:

```bash
cd apps/web
npm ci
npm run dev
```

Open <http://localhost:3000>, pick **Cache-aside web API (starter)**, and press **Run**.

### Using PostgreSQL

PostgreSQL is the intended database. Start it with Docker Compose and point the API at it:

```bash
docker compose up -d postgres
```

```bash
ARCHSIM_DATABASE_URL=postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim uv run archsim-api serve
```

Use `127.0.0.1` rather than `localhost`: with Docker Desktop on Windows, `localhost` may try
IPv6 first and stall. On Windows PowerShell, set the variable first with
`$env:ARCHSIM_DATABASE_URL = "postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim"`.
All settings are listed in [.env.example](.env.example).

### Everything in Docker

```bash
docker compose up --build
```

Then open <http://localhost:3000>. Ports are bound to 127.0.0.1 because the MVP has no
authentication.

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
uv run pytest                                   # engine + API
uv run ruff check packages services && uv run ruff format --check packages services
uv run mypy packages/engine/src services/api/src packages/mcp/src
cd apps/web && npm test && npm run lint && npm run typecheck && npm run build
```

The engine suite includes analytic checks against M/M/1 and M/M/c (Erlang C) queues, request
accounting invariants, determinism, and a directional reference benchmark on the starter
example. CI (GitHub Actions) also runs the API tests against PostgreSQL. `make` targets wrap
these commands (see the [Makefile](Makefile)).

## Repository layout

```
packages/engine/   Python simulation engine and domain model (no web dependencies)
services/api/      FastAPI service: projects, background runs, reports, comparison, migrations
apps/web/          Next.js editor: canvas, inspector, results and comparison
examples/          Example architectures (versioned JSON) with scenarios
docs/              Architecture, simulation model, schema, API, extension guide, roadmap
scripts/           Developer scripts (e.g. regenerate the web test fixture)
```

## Documentation

- [Implementation plan and assumptions](docs/plan.md)
- [Architecture](docs/architecture.md)
- [Simulation model: what is and isn't modeled](docs/simulation-model.md)
- [Assumptions: every default, documented or illustrative](docs/assumptions.md) (generated)
- [Calibrating from Prometheus and OpenTelemetry](docs/calibration.md)
- [Importing docker-compose, Kubernetes and Terraform](docs/import.md)
- [Prices and machine shapes](docs/pricing.md)
- [An SLO gate in CI](docs/ci.md)
- [Performance: what is fast, what is not, sampled mode](docs/performance.md)
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
single-user operation; optional CPU, memory and storage specs (CPU contention, memory limits,
database buffer cache, LRU cache eviction); the technology behaviours listed above (row locks,
write-ahead-log flushes, GC pauses, cold starts and so on). Not modeled: OOM kills and
restarts, disk and network bandwidth, packet loss, deadlocks and range locks, random failures
(faults are scheduled), parallel fan-out calls, regions. The full list is in
[docs/simulation-model.md](docs/simulation-model.md#what-is-not-modeled). There is no
authentication; do not expose the API publicly.
Grafana integration and an HTTP transport for the MCP server are on the roadmap, not
implemented; AI agents can use the stdio MCP server and, in the browser, the
[WebMCP tools](docs/agents.md).
Details: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

## Troubleshooting

- **The web app says it cannot reach the API.** Start it with `uv run archsim-api serve`. If it
  runs elsewhere, set `ARCHSIM_API_URL` for the web server (default `http://127.0.0.1:8000`).
- **Windows: `DLL load failed ... The filename or extension is too long`.** The checkout path is
  too deep for Windows' path limit. Clone into a short path such as `C:\src\archsim`, or put the
  virtual environment somewhere short with `UV_PROJECT_ENVIRONMENT`.
- **Next.js telemetry.** The npm scripts disable it by default; see
  [SECURITY.md](SECURITY.md).

## Contributing and license

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md); report vulnerabilities as described in
[SECURITY.md](SECURITY.md). ArchSim is licensed under the [GNU Affero General Public License v3.0](LICENSE).
