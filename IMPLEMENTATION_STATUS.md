# Implementation status

State as of 2026-10-24 (engine 0.2.0, schema 1.0, report 1.1). This file separates what is
implemented, how it was verified, what is known to be limited, and what comes next. Nothing
listed under "Implemented" is a stub or a mock. The product and UX brief for v2 is
[docs/product-spec-v2.md](docs/product-spec-v2.md).

## Implemented and covered by automated tests

**Engine** (`packages/engine`, 104 tests)

v2 additions:

- **Technologies with behaviour from config** (40 presets, each validated by a test):
  - database: `technology` label plus read replicas (reads over primary + replicas), shards
    (key-hashed partitions over the Zipf keyspace, per-shard counters, hot partitions),
    replication factor + consistency one/quorum/all (k-th fastest replica), provisioned
    throughput per shard (`throttled`); presets PostgreSQL (+ replicas), MySQL, SQL Server,
    distributed SQL, MongoDB replica set and sharded cluster, Cassandra, DynamoDB-style,
    Elasticsearch;
  - cache presets (Redis, Valkey, Memcached, CDN edge, Varnish);
  - service platforms: VM, Docker, Kubernetes with HPA-like autoscaling (interval, target
    utilization, pod start time, gradual scale-down, `replicas` series), serverless
    (concurrency limit, cold starts, keep-warm, `throttled`);
  - load balancer / gateway rate limiting (`rate_limited`) and presets (Nginx, HAProxy, Envoy,
    Kong, ALB-style, API-gateway-style); routing follows autoscaling replicas;
  - new `message_queue` (Kafka, RabbitMQ, SQS-style, Pub/Sub-style, NATS, Redis Streams):
    async acks, partitions cap consumer parallelism, backlog (`lag` series), redelivery and
    dead letters, background work drains before the run ends;
  - new `external_api` (payments, e-mail, SMS, identity, LLM inference, object storage):
    latency, error rate (`upstream_error`), rate and concurrency limits;
  - cost assumptions on every component (`costPerHour`, `costPerMillionRequests`).
- **Faults** in `workload.faults`: outage, slowdown and link latency with time windows,
  validated (`fault_unknown_target`, `fault_wrong_target`, `fault_outside_run`,
  `fault_clipped`), usable from scenarios.
- **Objectives** (`document.objectives`: p95, p99, availability, throughput), stored with the
  design and evaluated by the web app.
- **Report 1.1**: per-link time series (`callRps`, `timeoutRps`, `failureRps`), replica and
  lag series, new rejection reasons, and findings for hot shards, throttling, third-party
  errors and latency, read replicas, fault impact (inside vs. before each window, recovery),
  autoscaling, serverless cold starts and queue lag.
- New tests: data stores (replicas, shards, consistency ordering, throttling, external errors
  and limits, presets, determinism) and dynamics (faults only inside their window, fault
  validation, autoscaling, serverless, queues, rate limiting, link series, determinism).

MVP features:

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

**API** (`services/api`, 16 tests). The catalog now carries technology presets and the API
serves 8 example designs (see below).

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

**Web** (`apps/web`, 114 tests)

- **Studio with three workspaces around one canvas** (keys 1, 2, 3) and a next-step prompt:
  - **Design** (internal key `build`): technology library (families, search, drag or click to add a preset),
    technology picker in the inspector (re-applies a preset, undoable), schematic shapes for
    every kind (including a pipe for queues and a cloud for third-party APIs), catalog-driven
    inspector with key/advanced settings, **design review** (single points of failure, cache
    without fallback, missing timeouts, retry amplification with the math, retries without
    backoff, timeout inversion, unbounded waits, connection-pool exhaustion, serverless
    connection storms, autoscaling range, queue backlog/consumers, optimistic hit ratios,
    hot-partition risk, consistency ALL, unhandled third-party errors, no edge rate limit,
    long synchronous chains), **export** to docker-compose, Kubernetes manifests and Mermaid.
  - **Test** (internal key `stress`): test plan on a timeline (drag the traffic shape; failure
    blocks you move and resize per target), quick failure tests generated from the design, a
    result against the objectives (over the run and inside each failure window), and the
    **replay timeline**: a short header (play/pause, time, "Whole run", speed button; step
    and jump stay on the keyboard), the traffic plan and failure rows, event markers (faults,
    saturation, SLO breach/recovery, scaling, queue growth, timeouts), latency against the
    objective and failures; "More detail" (remembered) adds throughput and a heat lane per
    component. Click or drag any row to scrub, and the canvas shows each component's state at the playhead (fill level,
    queue, rejections, faults, link flow, replica count) plus a clock overlay. Runs started
    from the Test workspace replay automatically; from Design the result lands on the canvas.
  - **Results** (internal key `decide`): summary scorecard (objective tiles with inline-editable targets, error budget and
    burn-down, monthly cost and cost per 1M requests per component), trade-off map (cost vs.
    p95 for every run, SLO line, Pareto frontier, table), breaking-point finder (stepped load
    sweep, knee, max load within objectives), Markdown decision report (with design-review
    items), plus the existing insights, timeline, components, compare, history and
    assumptions views.
- Command palette (every action, workspaces, every technology preset), IDE-style status bar,
  keyboard shortcuts, light and dark schematic themes. Type: Geist Sans and Geist Mono with
  Instrument Serif for display headings (self-hosted at build time by next/font; no runtime
  calls to Google).
- Onboarding and guidance: a single top bar (project name, Design · Test · Results switch,
  Simulate, Save, ⋯ menu); no status bar; one Simulate button that never switches workspace; a
  five-step guided tour (parts library, canvas, side panel, Simulate, workspaces) on the first
  visit and from the ⋯ menu; a short next-step hint floating on the canvas (including "Add API
  servers", which adds and wires the part behind a lone Clients box); no error flags on an
  empty canvas; the canvas reframes to keep the whole design in view when panels or
  workspaces change, until you pan or zoom yourself.
- Parts library: an always-open, collapsible panel with search and logo tiles grouped by
  family. Logos come from Simple Icons (CC0, bundled at build time; no network at runtime); 21
  of the 39 built-in presets show a product logo, the rest (generic parts, "-style" managed
  services, SaaS APIs and a few products Simple Icons does not carry) a family icon.
  Canvas components show the logo, name, technology and at most two key settings; after a run
  they show only how busy they were. Link labels appear only when they carry information.
- Results: four tabs (Answer, Compare, Breaking point, Details) and a Report button; one run
  picker for all tabs. Answer leads with one yes/no sentence, the failing objective and the
  bottleneck, the key numbers, what to fix first, objective tiles and monthly cost.
- Home page: animated replay illustration (scripted, labelled "illustration"), three
  workspaces, a technology wall read from the live catalog, example cards with mini diagrams,
  projects.
- Unit tests: document conversion, connection rules, distributions, editor store, workload
  math, form controls, theme and shapes, command ranking, recorder (sampling, events, heat),
  faults and experiment verdicts, cost, load sweeps, decision report, design review rules and
  the three exporters. A Python test keeps the web catalog fixture in sync with the registry.

**Examples** (`examples/`, 8): cache-aside starter, queueing lab, retry storm with a TTL cache,
and five technology templates: Black Friday on Kubernetes (HPA, Kong rate limit, PostgreSQL
replicas, payments API; scenarios without autoscaling, slow payments, cache outage, tighter
limit), async order pipeline with Kafka (partitions vs. consumers, DB slowdown), product
catalog on sharded MongoDB (hot shard; key cardinality, more shards, cache in front),
serverless API under a burst (cold starts, concurrency limit vs. provisioned throughput), IoT
telemetry on Cassandra (consistency ONE/QUORUM/ALL, slow ring). Every scenario of every
example validates and runs; the numbers in them are illustrative assumptions.

**Repository**: AGPL-3.0 license, README, CONTRIBUTING, Code of Conduct, SECURITY, docs
(plan, architecture, simulation model, schema, API, extension guide, roadmap), issue/PR
templates, Dependabot, CI workflow, Docker Compose, Dockerfiles, `.env.example`, Makefile.

## Verified manually

v2, on 2026-10-24, in the desktop app's browser pane (Chromium) at 1440x900 in dark and light
themes, against the API run locally (SQLite) and against the rebuilt Docker stack
(PostgreSQL 16, engine 0.2.0): home page; Black Friday template end to end (Build with design
review and technology picker, Run in Stress with automatic replay, scrubbing, verdict,
scorecard, Run all & compare, trade-off map, breaking-point sweep), export dialog, command
palette (including adding presets). Bugs found this way were fixed: command ranking, a
render loop in the breaking-point page, sweep runs opening as the default run, a `.gitignore`
rule that hid `components/build/` from git and from Tailwind, the theme script warning.

MVP, on Windows 11 with Python 3.12, Node 24, SQLite, in the desktop app's browser pane:
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
- **Browsers other than Chromium**, very large graphs (replay smoothness above ~50 nodes),
  touch devices, and screen readers beyond basic labels; no automated accessibility audit.

## Known limitations

- One traffic source per design; synchronous, acyclic call graphs; dependencies are called
  sequentially (no parallel fan-out); no closed-loop users.
- Not modeled: CPU/memory contention, GC, bandwidth and packet loss, locks and disk I/O, cache
  eviction (TTL cache has unbounded capacity), replication lag, health checks and failover,
  circuit breakers; faults are scheduled (outage, slowdown, link latency), not random.
- Technology presets are illustrative starting points, not vendor benchmarks; costs are
  assumptions you should replace with your own prices.
- The breaking-point sweep uses short steps: autoscaled services start each step at their
  configured replica count, so it measures capacity before the scaler reacts (the page says
  so).
- Replay resolution is the report bucket (1 s); playback is throttled by the browser when the
  tab is in the background.
- Heavy designs take a while: the Black Friday template (up to 1,500 req/s for 120 s) takes
  about 15 s per run, and "Run all & compare" runs at most 2 simulations at a time.
- Exports (docker-compose, Kubernetes, Mermaid) are scaffolding to review, not deployable
  production configuration.
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
- The UI was checked by hand in one browser engine (Chromium in the desktop app) at 1280x800
  and 1440x900, in both themes; there are no automated visual or end-to-end tests yet.
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
5. Circuit breakers and retry budgets as link settings, random failures (MTBF/MTTR), health
   checks and failover, multi-region latency.
6. Prometheus calibration of service-time distributions and arrival rates, Grafana export,
   and an agent/MCP surface on top of the validated run API (see [docs/roadmap.md](docs/roadmap.md)).
