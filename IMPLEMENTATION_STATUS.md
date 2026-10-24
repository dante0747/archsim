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

**Web** (`apps/web`, 30 tests)

- Projects page (examples, blank, import JSON, duplicate, export, delete).
- Editor: React Flow canvas, searchable palette (drag or click to add), custom nodes with
  catalog-driven summaries, typed handles, link labels (request types, latency, timeout,
  measured calls/s), instant connection checks with explanations, server-side live validation
  with a problems list, ELK auto-layout, undo/redo with coalescing, multi-select, duplicate,
  delete, keyboard shortcuts, unsaved-changes guard, dark/light theme.
- Inspector generated from the catalog (numbers with units and range validation, nullable
  "no limit" fields, enums, switches, distribution editor, request-type toggles), workload
  panel with an input preview, scenario editor (overrides on any node/link/workload field).
- Results drawer: KPIs, findings, request accounting, provenance and "reproduce"; time-series
  charts with warm-up shading and a table view; per-component table and drill-down charts;
  comparison (explanations, input diff, KPI table, component table, overlay charts); run
  history; assumptions vs. simulation-derived values. Canvas heat overlay with legend.
- Unit tests: document conversion, connection rules, distributions, editor store (undo/redo,
  coalescing, add/connect/delete), workload math, form controls. A Python test keeps the web
  catalog fixture in sync with the registry.

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

## Not verified yet

- **Docker**: the images and the Compose stack were written but not built or run (the Docker
  daemon was not running on the development machine). `docker compose config` parses.
- **PostgreSQL**: not run locally. The CI job `api-postgres` runs the API tests against
  PostgreSQL 16 but has not executed yet (the repository has not been pushed).
- **CI on GitHub**: the workflow has not run; action versions and the Dependabot `uv`
  ecosystem are untested.
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
- README screenshots are low-resolution captures; a demo GIF has not been recorded.
- The canvas layout is usable at tablet widths, but the editor is designed for desktop
  screens.

## Next steps

1. Run CI on GitHub (including PostgreSQL) and build the Docker images; fix anything they
   surface.
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
