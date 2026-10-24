# Implementation status

State of the MVP as of 2026-10-24 (engine 0.1.0, schema 1.0). This file separates what is
implemented, how it was verified, what is known to be limited, and what comes next. Nothing
listed under "Implemented" is a stub or a mock.

## Implemented and covered by automated tests

**Engine** (`packages/engine`, 66 tests)

- Versioned document schema (architecture / workload / scenarios / layout), JSON Schema export,
  migration framework (newer-version and missing-path errors).
- Component registry with typed ports, config models carrying UI metadata (unit, group,
  assumption flag, visibility rules), per-type validation and runtime behavior. Built-ins:
  traffic source, load balancer (round-robin, least-connections, random), service (replicas,
  worker pools, bounded queues, queue timeout, blocking/non-blocking downstream I/O), cache
  (fixed hit ratio or TTL over a Zipf keyspace, miss port, populate, write invalidation),
  database (connection limit, wait queue, acquire timeout, read/write times).
- Entry-point plugin loading (opt-in, local packages only) and a tested example of a
  third-party component registered without touching other layers.
- Graph validation with stable codes and hints: unknown types/ports, self links, duplicates,
  port arity, request-type compatibility, cycles, missing/multiple traffic sources, unroutable
  request types, unrouted cache misses, unreachable nodes and unused links (warnings), config
  range errors located to the field.
- SimPy engine: seeded per-stream RNG (SHA-256 derived), Poisson (thinning) and deterministic
  arrivals, constant/ramp/burst patterns, read/write mix, per-link latency both ways, caller
  timeouts that keep the callee working, bounded retries with exponential backoff, client
  timeouts and retries, warm-up exclusion, automatic drain, early stop when idle, chunked
  execution with progress, cooperative cancellation, request and wall-clock limits.
- Metrics with bounded memory: bucketed counters, exact time-weighted gauges, log histograms;
  end-to-end and per-node summaries and time series; request accounting with invariants;
  wasted-work detection.
- Bottleneck analysis (saturated/hot/warm/ok, root cause vs. symptom via own-processing share,
  throughput gap, failure causes, retry amplification, cache findings) and A/B comparison
  (input diff from snapshots, KPI verdicts, bottleneck shift, explanations, seed warnings).
- Provenance in every report: engine/schema/report versions, seed, scenario and overrides,
  input digest, result digest.
- CLI: `validate`, `run`, `compare`, `catalog`, `schema`.
- Tests include: no traffic, below capacity, saturation with and without bounded queues,
  replicas, cache hit ratio, writes and invalidation, seed determinism, scenario == hand-edited
  document (same digests), dependency timeouts, bounded retries, blocking vs. async workers,
  load-balancer policies, TTL warm-up, burst and ramp patterns, limits, cancellation, progress,
  accounting invariants, Little's law, **M/M/1 and M/M/4 against Erlang C**, and a directional
  reference benchmark on the starter example.

**API** (`services/api`, 16 tests)

- FastAPI v1: health, meta, component catalog, examples, JSON Schema, validate; projects CRUD
  with optimistic concurrency (revision), duplicate, import (with migration) and export; runs
  (async enqueue, batch scenarios, list, status with live progress, report, input snapshot,
  cancel, reproduce, delete); comparison. OpenAPI at `/api/v1/openapi.json`.
- Background execution in a bounded thread pool; interrupted runs marked failed on restart;
  progress written at most once per second; reports stored once per run.
- SQLAlchemy 2 models, Alembic migration (a test asserts models and migrations agree),
  SQLite and PostgreSQL support (JSONB on PostgreSQL), request body limit, CORS.
- Tests cover the full lifecycle, persistence across app restarts, reproducibility of reruns,
  unsaved-document runs, comparison, limits, cancellation, and a seed above 2^31.

**Web** (`apps/web`, 33 tests)

- Visual design: a dark "blueprint" theme (navy grid canvas, cyan-violet-pink accent) and a
  light theme. Each component kind has its own color (traffic, network, compute, cache,
  storage), reads and writes have their own colors, and status colors (OK, warm, hot,
  saturated) are reserved for results. Chart colors were checked for contrast on both themes.
- Projects page: hero with an illustration, "how it works", example cards with a live
  mini-diagram of each example, blank project, import JSON, duplicate, export, delete.
- Guided workflow bar under the top bar (Design, Traffic, Simulate, Insights, Compare) that
  checks off steps and always suggests one next action (fix the first problem, run, open the
  insights, run all scenarios and compare).
- Editor: React Flow canvas with a component rail (drag or click to add) and a searchable
  component library; floating toolbar (add, undo/redo, tidy up, zoom, results overlay, live
  flow); node cards with kind color, key settings and, after a run, status, utilization and
  calls/s; links animate request dots scaled by measured calls/s; a "last run" card with the
  verdict and KPIs that the canvas reframes around. Instant connection checks with
  explanations, server-side live validation with a problems menu, ELK auto-layout, undo/redo
  with coalescing, multi-select, duplicate, delete, keyboard shortcuts, unsaved-changes guard.
- Side panel with Overview (design summary, traffic, checks, how to read the canvas),
  Traffic (requests per second with presets, read/write slider, load shape with a preview,
  run length, advanced arrival settings) and Scenarios. The inspector is generated from the
  catalog and splits each component into key settings and advanced settings; assumptions are
  marked with a "≈" tag.
- Run button with a scenario menu, progress and stop. Results panel: Insights (verdict,
  KPI tiles with sparklines, findings with suggestions, request accounting, provenance and
  "reproduce"), Timeline charts with warm-up shading and a table view, Components (table and
  drill-down charts), Compare (explanations, input diff, KPI and component tables, overlay
  charts), History, and Assumptions vs. simulation-derived values.
- Unit tests: document conversion, connection rules, distributions, editor store (undo/redo,
  coalescing, add/connect/delete), workload math, form controls, theme helpers and the run
  verdict. A Python test keeps the web catalog fixture in sync with the registry.

**Repository**: AGPL-3.0 license, README, CONTRIBUTING, Code of Conduct, SECURITY, docs
(plan, architecture, simulation model, schema, API, extension guide, roadmap), issue/PR
templates, Dependabot, CI workflow, Docker Compose, Dockerfiles, `.env.example`, Makefile.

## Verified manually

On Windows 11 with Python 3.12, Node 24, SQLite, in the desktop app's browser pane:
creating a project from an example and from a blank canvas; adding components; drawing links
by dragging handles (and a refused self-link with its message); live validation messages;
editing replicas; running the design and an unsaved edit; real progress; results, charts and
canvas overlay; "Run all & compare" across six scenarios; saving and reloading; light and dark
themes. CLI and API results matched (same input and result digests). `npm run build`
succeeds.

With Docker Desktop (Docker 29, Compose 2.40) on the same machine:

- `docker compose build` builds both images (`archsim-api` about 495 MB, `archsim-web` about
  305 MB) and `docker compose up -d` brings up PostgreSQL 16, the API and the web app with
  passing health checks. Migrations run on startup against PostgreSQL.
- Smoke test through the web container's proxy (web -> API -> PostgreSQL): create a project
  from an example, run two scenarios in a batch, compare them, open the editor page. The API
  reports `"database": "postgresql"`.
- All 16 API tests pass against PostgreSQL 16 (`ARCHSIM_TEST_DATABASE_URL`), including the
  check that Alembic migrations match the ORM models.
- The "3 API replicas" scenario simulated inside the Linux container produced the same result
  digest as the identical model simulated on Windows.

## Not verified yet

- **CI on GitHub**: the workflow has not run (the repository has not been pushed); action
  versions and the Dependabot `uv` ecosystem are untested.
- **Browsers other than Chromium**, very large graphs, and screen readers beyond basic labels.

## Known limitations

- One traffic source per design; synchronous, acyclic call graphs; dependencies are called
  sequentially (no parallel fan-out); no closed-loop users.
- Not modeled: CPU/memory contention, GC, autoscaling, bandwidth and packet loss, locks and
  disk I/O, cache eviction (TTL cache has unbounded capacity), replication, failures.
- Client `timeoutMs` applies per attempt, not as a total deadline. Link retries also fire on
  instant rejections (`queue_full`).
- Wasted work is counted only for requests whose client gave up, not for work continuing after
  an internal dependency timeout.
- Cancellation and the wall-clock limit are checked between progress chunks (1/200 of the
  simulated time), so a run can overshoot by up to one chunk.
- Simulations run in threads inside the API process and share the GIL: two concurrent runs
  each run slower, and the API stays responsive but not instant during heavy runs. Throughput
  is roughly 5,000-10,000 simulated requests per second per run on a laptop.
- `throughputRps` counts by completion time and can be slightly above `offeredRps`.
- "Queue kept growing" needs at least 4 time buckets inside the measurement window.
- No authentication or multi-user support; bind to localhost only.
- README screenshots are 800x500 captures; a demo GIF has not been recorded.
- The redesigned UI was checked by hand in one browser engine (Chromium in the desktop app)
  at 1280x800, in both themes; it has no automated visual or accessibility tests yet.
- The canvas layout is usable at tablet widths, but the editor is designed for desktop
  screens.

## Next steps

1. Push to GitHub and run CI (including the PostgreSQL job); fix anything it surfaces.
2. Add a Playwright end-to-end test of the main journey (open example, edit, run, compare,
   save, reload).
3. Move simulations to a process pool or a Redis/RQ worker (the `JobRunner` protocol is the
   seam) for parallelism and hard cancellation.
4. Total client deadline across retries, retry budgets / circuit breaker component, and
   parallel fan-out calls.
5. More components (message queue/broker, API gateway, CDN, object storage) and failure
   injection.
6. Prometheus calibration of service-time distributions and arrival rates, Grafana export,
   and an agent/MCP surface on top of the validated run API (see [docs/roadmap.md](docs/roadmap.md)).
