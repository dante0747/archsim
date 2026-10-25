# ArchSim

**A flight simulator for your architecture.**

ArchSim is an open-source, local-first studio for designing distributed systems and testing them
before you build them. Model the stack you actually run (PostgreSQL with read replicas, sharded
MongoDB, Cassandra quorums, Redis, Kafka, Kubernetes autoscaling, serverless functions,
third-party APIs), push traffic and failures through it with a discrete-event engine that moves
every single request, replay what happened second by second, and decide with SLOs, cost and
capacity curves. Same document and seed, same result.

> **Results are estimates, not production guarantees.** Every number comes from the model you
> drew and the assumptions you entered. Without calibration against real telemetry, trust
> comparisons between scenarios more than any single absolute value. See
> [docs/simulation-model.md](docs/simulation-model.md).

![The Test workspace replaying a Black Friday ramp: the Kubernetes checkout service glows red while the autoscaler catches up, the timeline below shows the traffic ramp, events, latency against the objective and failures, and the test plan panel reports the result](docs/images/editor-results.jpg)

*The Test workspace replaying the Black Friday on Kubernetes template. A demo GIF is still to be recorded.*

## What you can do

ArchSim has three workspaces around one canvas, switched from the top bar: **Design → Test →
Results** (keys 1, 2, 3). One **Simulate** button (Ctrl/⌘ Enter) runs the design from wherever
you are; results land on the canvas right away. A short guided tour opens on the first visit
(and from the ⋯ menu), and a small hint on the canvas says what to do next, for example adding
API servers behind a lone Clients box.

**Design: draw it with real technologies**
- A parts library on the left with each technology's logo and a search box: relational (PostgreSQL, MySQL, SQL Server,
  distributed SQL), document (MongoDB replica sets and sharded clusters), wide-column
  (Cassandra), key-value (DynamoDB-style provisioned throughput), search (Elasticsearch),
  caches (Redis, Valkey, Memcached, CDN edge, Varnish), message queues (Kafka, RabbitMQ,
  SQS-style...), gateways (Nginx, HAProxy, Envoy, Kong...), services on VMs, Docker, Kubernetes
  (HPA autoscaling) or serverless (cold starts, concurrency limits), and third-party APIs
  (payments, e-mail, identity, LLM inference...). Behaviour comes from the numbers (replicas,
  shards, replication factor and consistency, partitions, rate limits), never from the name,
  and every default is marked as an assumption.
- Each component kind has its own schematic shape and shows its technology logo, its name and
  one or two key settings; after a run it shows how busy it was. Links are right-angle wires.
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
- **Details**: findings in plain language (bottlenecks, hot shards, throttling, autoscaling
  lag, consumer lag, wasted work), error budget, metrics over time, per-component drill-down
  and the assumptions behind every number. A Markdown report is one click away.

**Everywhere:** a command palette (Ctrl/⌘ K) with every action, keyboard shortcuts, light and
dark themes. Projects live in PostgreSQL (or SQLite), documents
import/export as versioned JSON, and every run stores its input, seed, engine version and
result digest so it can be re-run and verified bit for bit.

| Design: parts library with logos, a selected part | Results → Answer |
| --- | --- |
| ![Design workspace: the parts library on the left lists technologies with their logos (Nginx, Envoy, Kong, Node.js, Go, Python...); Checkout API is selected and the side panel shows its Go logo, technology menu, last-run status (warm, 76% busy), three settings and a collapsed More settings](docs/images/build.jpg) | ![Results answer: "Yes, it holds up." Meets all 2 objectives at 950 req/s; 947 req/s handled, p95 434 ms, 99.8% success, $1,235 per month; two findings worth a look; objective tiles](docs/images/scorecard.jpg) |
| **Results → Compare** | **Home** |
| ![Cost vs. speed map: monthly cost against p95 for five scenarios, the SLO line, and the Pareto frontier](docs/images/tradeoffs.jpg) | ![Home page with the animated replay illustration](docs/images/home.jpg) |

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
2. Press **Run in Stress**. The run is recorded and replays automatically: around t = 60 s the
   checkout service goes red while new pods start, then recovers. Scrub the timeline to any
   second; the canvas shows that moment. The experiment panel reports whether the hypothesis
   (p95 ≤ 500 ms, availability ≥ 99.5%) held.
3. Click a one-click experiment such as **Lose Catalog cache for 15 s** and run again:
   the verdict flips, and the fault window on the timeline shows exactly when and where it
   broke.
4. Press **3** for **Decide**: the scorecard shows the objectives, error budget and a monthly
   cost estimate. **Run all & compare** puts every scenario on the trade-off map (no
   autoscaling is cheaper but misses the SLO).
5. **Breaking point** sweeps the load and finds the highest load that still meets the
   objectives. **Report** gives you a Markdown write-up; **Ctrl/⌘ K → Export** gives
   docker-compose, Kubernetes or Mermaid scaffolding.

The numbers come from the shipped examples with seed 42 and are illustrative assumptions.

## Command line

The engine works without the web stack:

```bash
uv run archsim validate examples/cache-aside-starter.json
uv run archsim run examples/cache-aside-starter.json --scenario one-api-replica --out report.json
uv run archsim compare examples/cache-aside-starter.json --a baseline --b three-api-replicas
```

## Tests and checks

```bash
uv run pytest                                   # engine + API (82 tests)
uv run ruff check packages services && uv run ruff format --check packages services
uv run mypy packages/engine/src services/api/src
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
- [Document schema](docs/schema.md)
- [HTTP API](docs/api.md) (OpenAPI at `/api/v1/openapi.json`, interactive docs at `/api/v1/docs`)
- [Adding a component type](docs/extending-components.md)
- [Roadmap: calibration, Grafana, AI/MCP, more components](docs/roadmap.md)
- [Implementation status](IMPLEMENTATION_STATUS.md)

## Scope and limitations

Supported today: one traffic source per design; five component types (traffic source, load
balancer, service, cache, database); synchronous acyclic call graphs; open-loop synthetic
workloads; local single-user operation. Not modeled: CPU/memory contention, garbage
collection, autoscaling, network bandwidth and packet loss, locks and disk I/O, cache eviction,
failures and failure injection. There is no authentication; do not expose the API publicly.
Prometheus calibration, Grafana integration and an agent/MCP API are on the roadmap, not
implemented. Details: [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

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
