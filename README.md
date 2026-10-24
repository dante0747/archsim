# ArchSim

**Draw a distributed system, give it traffic, and watch where it breaks.**

ArchSim is an open-source visual designer and discrete-event simulator for software
architectures. You sketch a system (clients, load balancers, services, caches, databases),
describe the workload and your performance assumptions, and run a real simulation that moves
every request through queues, worker pools and network links. You get throughput, latency
percentiles, queueing, utilization, timeouts and a plain-language explanation of the
bottleneck, and you can compare alternatives side by side.

> **Results are estimates, not production guarantees.** Every number comes from the model you
> drew and the assumptions you entered. Without calibration against real telemetry, trust
> comparisons between scenarios more than any single absolute value. See
> [docs/simulation-model.md](docs/simulation-model.md).

![The editor after simulating the starter with one API replica: components are drawn as schematic shapes (parallelogram clients, hexagon load balancer, stacked API servers, memory-chip cache, cylinder database), the saturated API is outlined in red, and the last-run card reads "API Servers is the primary bottleneck" with throughput, p95 latency and success rate](docs/images/editor-results.jpg)

*Captured at 800x500. A demo GIF is still to be recorded.*

## What you can do

- **Follow the steps**: a workflow bar (Design, Traffic, Simulate, Insights, Compare) checks
  off what you have done and always suggests the next action, so a first-time user is never
  stuck wondering what to click.
- **Design** on a schematic canvas: each kind of component has its own shape (clients are a
  parallelogram, load balancers a hexagon, services stacked boxes with one layer per replica,
  caches a memory chip, databases a cylinder), links are right-angle wires, and color is kept
  for what needs attention. Drag or click components from the rail (or search the library),
  link them, tidy up with ELK auto-layout, undo/redo, multi-select, keyboard shortcuts.
- **Drive it from the keyboard**: <kbd>Ctrl</kbd>/<kbd>⌘</kbd> <kbd>K</kbd> opens a command
  palette with every action (run a scenario, add a component, jump to a results tab, export...),
  and a status bar shows validity, model size, workload, run progress and zoom.
- **Configure** every component in an inspector generated from the component catalog, with the
  key settings first and the rest under *Advanced*: replicas, workers, queue limits and
  timeouts, service-time distributions, cache hit ratio or TTL over a Zipf keyspace, database
  connections, load-balancing policy, link latency, timeouts and retries. Values that are *your
  assumptions* carry a "≈" tag.
- **Describe traffic** in the Traffic tab: requests per second (with presets), a read/write
  slider, constant / ramp / burst patterns with a live preview, run length, and advanced
  settings (Poisson or evenly spaced arrivals, warm-up, seed).
- **Simulate** with a SimPy discrete-event engine running in the background, with real progress,
  cancellation and limits. Invalid graphs (cycles, unrouted cache misses, unreachable nodes,
  incompatible links...) are rejected with actionable messages before anything runs.
- **Read the results**: right on the canvas (status and a utilization meter on every
  component, ● read / ■ write dots whose density follows the measured calls/s, a verdict card),
  and in the Insights tab:
  offered vs. successful throughput, p50/p95/p99 latency, failures and timeouts,
  per-component utilization, queue depth, queue wait and rejections, and findings such as *"API Servers is the primary bottleneck"*, *"API Servers is
  saturated as a consequence of PostgreSQL"*, or *"100% of completed work at Service was wasted"*
  (it finished requests whose clients had already timed out).
- **Compare** two runs: KPI deltas, the exact input differences, and explanations (*"Successful
  throughput fell 27% (399 → 292 req/s); API Servers becomes saturated"*).
- **Save, export and reproduce**: projects persist in PostgreSQL (or SQLite), documents
  import/export as versioned JSON, and every run stores its input snapshot, seed, engine version
  and result digest so it can be re-run and verified bit for bit.

| Insights after a run | Scenario comparison |
| --- | --- |
| ![Insights: verdict, KPI tiles with sparklines and findings](docs/images/insights.jpg) | ![Compare: what changed and why, with the input differences](docs/images/compare.jpg) |
| **Charts over simulated time** | **Projects page** |
| ![Timeline charts of throughput and latency](docs/images/charts.jpg) | ![Projects page with the hero illustration and steps](docs/images/home.jpg) |

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

1. Open the starter example. Reads go Clients → Load Balancer → API Servers → Redis; misses
   continue to PostgreSQL through the cache's **miss** port; writes go to PostgreSQL and then
   invalidate the cache.
2. Press **Run**. With 2 API replicas at 400 req/s, nothing is saturated (API about 68% busy,
   p95 about 89 ms).
3. Select **API Servers**, set **Replicas** to 1, and run again: the API saturates, its queue
   fills, about 27% of requests are rejected and p95 rises to about 265 ms.
4. Open **Scenarios** and press **Run all & compare**, or pick any two runs in **Compare**.
5. **Save** (Ctrl/⌘ S), reload the page, and use **Runs → Reproduce** to re-simulate a stored
   run. Identical input and seed give an identical result digest.

The numbers above come from the shipped example with seed 42 and are illustrative.

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
