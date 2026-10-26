# Implementation status

State as of 2026-10-26 (engine 0.8.0, schema 1.1, report 1.9). This file separates what is
implemented, how it was verified, what is known to be limited, and what comes next. Nothing
listed under "Implemented" is a stub or a mock. The product and UX brief for v2 is
[docs/product-spec-v2.md](docs/product-spec-v2.md).

## Implemented and covered by automated tests

**Engine** (`packages/engine`, 187 tests)

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

**API** (`services/api`, 20 tests). The catalog now carries technology presets and the API
serves 10 example designs (see below).

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

**Web** (`apps/web`, 198 tests)

- **Studio with three workspaces around one canvas** (keys 1, 2, 3) and a next-step prompt:
  - **Design** (internal key `build`): technology library (families, search, drag or click to add a preset),
    technology picker in the inspector (re-applies a preset, undoable), uniform flat part cards
    (logo on a family-tinted chip; the kind shapes survive only in tests), catalog-driven
    inspector with key/advanced settings, **design review** (single points of failure, cache
    without fallback, missing timeouts, retry amplification with the math, retries without
    backoff, timeout inversion, unbounded waits, connection-pool exhaustion, serverless
    connection storms, autoscaling range, queue backlog/consumers, optimistic hit ratios,
    hot-partition risk, consistency ALL, unhandled third-party errors, no edge rate limit,
    long synchronous chains), **export** to docker-compose, Kubernetes manifests and Mermaid.
  - **Test** (internal key `stress`): a Test plan panel with the last result (held or not,
    each objective, each failure window, a replay link to the worst moment) and one plan card
    (Traffic, Must stay true, Failures; each row edits inline); quick failure tests generated
    from the design sit at the top of the "Add failure" menu, above custom failures per part
    or link. On the timeline you drag the traffic shape and move and resize failure blocks. The
    **replay timeline**: a short header (play/pause, time, "Whole run", speed button; step
    and jump stay on the keyboard), the traffic plan and failure rows, event markers (faults,
    saturation, SLO breach/recovery, scaling, queue growth, timeouts), latency against the
    objective and failures; "More detail" (remembered, on by default) adds throughput and an
    arrangement lane per component: a rounded region in the part's family colour with its
    utilisation (or arrivals) as a waveform, the queue as a dashed line, refused requests as red
    ticks and its failures as solid red clips. Click or drag any row to scrub, and the canvas shows each component's state at the playhead (fill level,
    queue, rejections, faults, link flow, replica count) plus a clock overlay (on screens 1280 px
    and wider the top bar's LCD shows the clock instead). Runs started
    from the Test workspace replay automatically; from Design the result lands on the canvas.
  - **Results** (internal key `decide`): summary scorecard (objective tiles with inline-editable targets, error budget and
    burn-down, monthly cost and cost per 1M requests per component), trade-off map (cost vs.
    p95 for every run, SLO line, Pareto frontier, table), breaking-point finder (stepped load
    sweep, knee, max load within objectives), Markdown decision report (with design-review
    items), plus the existing insights, timeline, components, compare, history and
    assumptions views.
- Command palette (every action, workspaces, every technology preset, "Theme: …" commands),
  keyboard shortcuts. "Studio" look (2026-10-26, on branch `ui-studio`, replacing "Ops
  console" after the user asked for something premium and named Apple pro apps, Figma/Framer
  and Stripe/Vercel as references): modelled on a pro audio app.
  - Dark (the default): charcoal page (#1c1c1e), a darker canvas with a quiet dot grid, three
    surface steps, part families in Apple's system colours (clients green, network teal,
    compute blue, cache pink, databases orange, messaging purple, third-party grey), solid blue
    for primary actions. Light mirrors it with Apple's light variants.
  - Top bar as a transport: replay buttons (back to start, play/pause; play opens Test, which
    owns the replay loop) and an LCD readout in Geist Mono (time, throughput, p95 against the
    goal, success; red when an objective is missed). It shows the moment under the playhead
    while replaying or scrubbing inside the run, and the run's summary otherwise. Simulate is a
    red record-style button. Workspaces are a segmented control.
  - Library: a three-column grid of solid colour tiles with white logos (description in the
    tooltip). Part cards: the same solid tile, numbers in Geist Mono, a 12-segment level meter
    for utilisation, Figma-style corner handles when selected.
  - Chaos pads: a rack on the canvas (bottom right, Design workspace) with one pad per quick
    failure test the design allows (lose the cache, slow database, link latency, kill the
    service tier, third-party or broker outage, traffic spike). Hitting a pad replaces the
    planned failures with that one and simulates; the pad stays lit while its failure is
    planned; Clear removes it; Ctrl Z undoes it. The rack folds to a small button (remembered)
    and starts folded when the canvas is narrower than 720 px.
  - Landing page product shot redrawn the same way (LCD, meters, arrangement lanes).
  The sun/moon button offers Dark, Light or Match system. The choice is stored in this
  browser's localStorage only (key `archsim-theme-studio`, so earlier choices don't carry
  over) and applied before first paint. The blue utilisation ramp (`heatColor`) is no longer
  drawn; lanes use family colours and the cards use the meter.
- Technology behaviour (engine 0.5.0, report 1.5; `archsim_engine/technology.py`): the selected
  technology switches on mechanisms that make each product behave like the real one, each a
  config setting in a "Technology behaviour" group whose default `auto` takes the technology's
  value. Databases: row/document locks on writes (writes to a hot key queue while holding
  their connection), readers waiting for writers (SQL Server), group commit on a log flush,
  asynchronous replicas that replay writes on a limited number of workers (PostgreSQL 1, MySQL
  4, MongoDB 16) with replication lag and stale-read counting, majority / all-replica write
  acknowledgements (MongoDB, Elasticsearch), read routing (MongoDB reads the primary by
  default), WiredTiger tickets (128), scatter-gather reads (every Elasticsearch search; a share
  of MongoDB reads when sharded), SSTable read amplification (Cassandra, Scylla), DynamoDB
  capacity units with per-partition limits (3,000 RCU / 1,000 WCU), table limits, on-demand
  starting capacity and 300 s burst. Caches: single-threaded command execution per shard
  (Redis, Valkey), cluster shards, allkeys-LRU or noeviction (out-of-memory refusals), RDB fork
  stalls. Queues: key-hashed ordered partitions with one consumer each (Kafka), acks=all and
  linger, memory-alarm flow control that blocks publishers (RabbitMQ), visibility timeout /
  ack deadline redelivery (SQS, Pub/Sub), SQS FIFO publish limit, at-most-once drops (core
  NATS). Load balancers: technology upstream timeouts returning 504 (nginx 60 s, Envoy 15 s,
  API Gateway 29 s, ALB and Kong 60 s) and Envoy circuit breakers (503 past 1,024 in-flight
  requests per target). Services: Node.js event loops (CPU work queues for one thread per
  process), stop-the-world GC pauses by runtime, JIT warm-up of replicas started during the run
  and of new serverless instances, runtime-specific serverless cold starts and a scale-out rate
  (1,000 per 10 s). Every documented default and link was checked against the vendor docs on
  2026-10-25 (one research pass; corrections applied: Elasticsearch 9 search queue size, Go
  pause figure, Redis fork figure, MongoDB ticket wording, moved doc URLs). GC pause sizes and
  frequencies, JIT warm-up factors, per-runtime cold starts, replay cost and log flush times
  are illustrative and labelled so. The catalog lists each technology's traits and auto
  defaults; the inspector shows "How <technology> behaves" with doc links and "Auto: on for
  PostgreSQL"-style hints. New findings: `replication_lag` and `lock_contention`. Switching a
  part to another technology preset now resets settings the previous preset set (they used to
  linger). Covered by 24 tests in `test_technology.py`; all earlier engine tests pass
  unchanged apart from the report version.
- Calculations (report 1.5, `archsim_engine/explain.py`): every node carries `explain`
  formulas with the run's values substituted (arrival rate, capacity, utilization, Little's
  law, the M/M/c wait via Erlang C, and component-specific ones) and the report adds system
  formulas plus a latency breakdown (mean time per component excluding downstream calls). A
  test checks the M/M/4 prediction against the simulated wait (within 30%) and Little's law.
  Shown in Results → Details → Calculations, under the selected part in Design ("How it's
  calculated"), and in the dashboard's panel info popovers.
- Request traces (report 1.5): every n-th request (about 1,200 per run, by request number, no
  random draws) records spans for network legs, time in each component, waits, work, log
  flushes, replica acks, GC pauses, cold starts, forks and cache hits/misses; the report keeps
  about 30 (spread, slowest, median, failures). Results → Details → Request traces shows a
  list and a waterfall. Traces are deterministic (same digest and traces on re-run). Engine
  speed is unchanged by the bookkeeping (the starter runs as fast as on engine 0.4.0).
- Dashboard (4th workspace, key 4): Grafana-style rows (overview stat panels with sparklines
  and thresholds, traffic, latency, saturation, errors with a failure table, one row per
  component with its technology signals), synced crosshair, table legends (click to isolate),
  drag-to-zoom and range presets, warm-up/failure/event annotations, fullscreen and Inspect
  data (table + CSV) per panel, Show on canvas, component filter, Group by kind, and Replay at
  1x/4x/10x sharing the Test workspace's playhead. A metric registry (`lib/metrics-catalog.ts`)
  describes every series (label, unit, how it is calculated, thresholds). 36 unit tests.
- Saved projects moved off the home page to `/projects` (search, open, duplicate, export,
  delete); the nav link and "Back to projects" go there.
- Templates gallery at `/templates` (the landing page shows three of them and links there): a
  hero with counts (templates, scenarios, technologies), category filters (Starter, Scale,
  Data, Async, Serverless, Resilience, Learn) and search, a full-width featured template, and
  cards whose preview draws the design as logo tiles on graph paper with traffic flowing along
  the links in the category's colour, plus the category, level, a one-line "what you'll see",
  the description and parts / scenarios / peak load. Parts without a technology show the
  engine's default (PostgreSQL, Redis). Hovering lifts the card and offers "Use template".
- Kubernetes clusters and pods (engine 0.6.0, report 1.6, 2026-10-25): a `kubernetes_cluster`
  part (presets Kubernetes, EKS-, GKE-, AKS-style, k3s; illustrative sizes and provisioning
  times) with nodes, allocatable CPU and memory, a scheduler that binds each pod to the least
  allocated node that fits its requests, Pending pods, the cluster autoscaler (scan interval,
  provisioning delay, min/max nodes, removal of empty nodes) and image pulls. Services on the
  Kubernetes platform run their replicas as pods on it (`cluster` picks one of several);
  starting pods that do not fit start Pending; a load balancer with no ready pod behind it
  answers `no_ready_pods`. With a CPU size the HPA now scales on the higher of CPU use and
  busy workers (like the Kubernetes HPA's CPU target); scaling down deletes Pending pods first. Validation (pod bigger than a node,
  pods that start Pending, missing or ambiguous cluster), a `pods_pending` finding, cluster
  status from its pods, calculations (allocatable per node, time to new capacity, pods per
  node) and documentation links. 9 engine tests (scheduling, Pending, node adds after the
  delay, no autoscaler, headroom, shared pool starving a service into 503s, several
  clusters, Pending pods deleted on scale-down, determinism).
- AI agents through WebMCP (web, 2026-10-26): the app registers WebMCP tools with the
  browser's model context (`document.modelContext` from the draft, or `navigator.modelContext`
  from Chrome's early preview and polyfills; unregistered by aborting a signal or with
  `unregisterTool`; looked for again for 5 s in case a polyfill loads late). On every page:
  list_templates, open_template, new_project, list_projects, open_project. In the editor:
  get_design, list_part_types, add_part, connect_parts, update_part, update_link, remove,
  set_traffic, set_objectives, review_design, simulate (waits for the result), get_results,
  tidy_layout, undo, save_design. Results are JSON (MCP-style text content); mistakes come back
  as errors that list the valid choices. Edits use the editor's store actions (undoable) and show
  a toast; read-only tools carry `readOnlyHint`, saving `consequentialHint`. 9 unit tests (the
  adapter in both API shapes, the tools on a loaded design). Checked in the browser with a
  stand-in model context: the 5 app tools registered after load, opening a template registered
  the 15 editor tools, and an agent session added a PostgreSQL audit database, raised traffic to
  800 req/s, simulated (65% success, API servers the bottleneck), set 4 API replicas, tidied
  and simulated again (100%, both objectives met), with toasts for each step. Not tested with a
  real browser agent: none was available here (WebMCP is behind a flag in Chrome).
- One source of truth and limits that hold (engine 0.7.0, report 1.7, 2026-10-26; Phase 0 of
  docs/prompts/how-to-win.md). The static load estimate, auto-sizing, the cost estimate and the
  design review moved from the web app to the engine (`load.py`, `sizing.py`, `cost.py`,
  `review.py`); before deleting the TypeScript copies, both versions ran on all ten templates
  and gave identical sizes (every explanation string), identical costs and identical review
  findings except one intended fix (a Kubernetes cluster was flagged as "runs a single replica").
  Other fixes while porting: CPU is capped at exactly the handler count (a common size below it,
  e.g. 2 vCPU for 3 workers, made the CPU the bottleneck), a thread-pool service's handlers are
  its workers (not workers x processes), a Redis-style cache's cap is the commands it runs at
  once, a service on a Kubernetes cluster is never sized above what one node can allocate, and
  the review's retry path walk has a deterministic budget (layered fan-out has exponentially
  many paths). The engine fills empty specs before every run (sizes from the design as drawn,
  scenarios on the same sizes); `architecture.autoSize: false` opts a document out (compatible
  field, schema 1.0), which the queueing-theory and spec-semantics tests use. Reports add
  `sizing` (value and source set/auto/scenario per spec, auto sizes, how they were worked out,
  price), `cost` and `review`; `POST /api/v1/analyze` and `archsim analyze` return the same
  without simulating. The web app gets sizes, cost and review only from the engine (live,
  with validation, and from reports); its tests use engine output written by
  `scripts/export_web_fixtures.py` (a Python test keeps it current). Verified: the CLI, the API
  job runner and `run_simulation` give the same result digest for the starter, plain and with a
  scenario (tests); in the browser, a starter run from the web app (dev server against this
  branch's API) and `archsim run` gave the same digest (`b03ca1028e89`), the inspector showed
  the engine's sizes and price (typing 8 vCPU re-priced it to $0.30/h), and Results priced the
  run at $311 a month from `report.cost`. Limits: the wall clock and cancellation are checked
  every 4,096 events inside the event loop (a SimPy-identical step loop: Black Friday with
  auto-sizing off gives the old engine's digest `e27e9920...`; overhead best of 3, 22.0 s vs
  22.8 s, +3.5%, timings noisy); a 35-part layered fan-out design now stops 1.7 s after start
  against a 1 s limit (it used to run one request for ~7 s); validation rejects designs whose one
  request would cause more than 10,000 calls (`design.amplification`, 88,575 for that design,
  naming the path), warning above 1,000. Reports count `eventsProcessed` (Black Friday ~4.0M).
  Not verified: older reports have no `cost` (Results say to re-run); the amplification limit
  is not a setting of the API yet (default 10,000 everywhere); overhead measured on one machine
  with other work running.
- Technology tiers and discoverable assumptions (report 1.8, 2026-10-26; Phase 1 of
  docs/prompts/how-to-win.md). Every technology profile has a tier: core (the roadmap's
  suggested set: generic, Node.js, Java and Go services, PostgreSQL, Redis, Kafka, SQS-style,
  Nginx and ALB-style load balancers, upstream Kubernetes, and the generic part of each kind,
  which adds no technology behaviour; 15 profiles) or experimental (the other 36; none deleted).
  The catalog carries `technologyTiers`, `presets[].tier` and `tierNote` (the badge text);
  `/analyze` and reports add each part's tier (`technologies`, `assumptions.technologies`) and
  reports a model note naming experimental parts. Tiers are metadata: all ten templates give
  the same result digests as before (checked against main, e.g. starter `b03ca1028e89`). Where
  defaults come from is now data: a profile value is documented when a trait with a link
  `covers` it (PostgreSQL-linked traits count for PostgreSQL only, so MySQL, MariaDB, Oracle
  and others sharing them are illustrative), a preset number when the preset's `sources` links
  it (17 numbers; the new links were read to check the values: Tomcat threads and accept
  queue, Lambda concurrency and memory per vCPU, S3 request rate, and for the engine constants
  DynamoDB's on-demand start; the rest reuse trait links);
  sizing's inline ratios became named constants. `scripts/gen_assumptions.py` writes
  [docs/assumptions.md](docs/assumptions.md) from the code: tiers, every profile value, preset
  number, assumption default and engine constant, each documented (with link) or
  illustrative; a test fails when it is out of date. The web app shows a small "experimental"
  badge (tooltip from the engine) in the parts library, the inspector's technology line, the
  verdict, Details > Calculations and a new Technologies table under Assumptions, marks
  experimental technologies in the Markdown report (Design table and Assumptions) and in
  exports (Compose label `archsim.tier`, Kubernetes annotation `archsim/tier`, header line),
  and every Auto value in the inspector says documented (linked) or illustrative. Agent tools
  return tiers. Landing copy no longer says each technology "behaves like the real thing": it
  names the core set and says no technology is validated against measurements yet. 6 engine
  tests, 6 web tests. Checked in the browser (dev server against this branch's API): library
  badges in dark and light, MongoDB on the starter shows the badge with its tooltip and Auto
  hints ("on for MongoDB, documented", "16 (MongoDB, illustrative)"), and its run shows the
  note under "Yes, it holds up." and in Assumptions; no console errors. Not verified: the Markdown
  report and exports in the browser (unit-tested only); the 13 core / 25 experimental preset
  counts on the landing are computed from the catalog.
- Calibration from telemetry (engine 0.8.0, schema 1.1, report 1.9, 2026-10-26; Phase 3 of
  docs/prompts/how-to-win.md). Schema 1.1 adds `provenance` (per input: illustrative, assumed or
  measured, with source, query, time range, fit error and when) and a `calibration` record;
  1.0 documents migrate on load, newer ones are refused. New `empirical` distribution (a
  quantile table) and `trace` traffic pattern (rate points, linear between them).
  `archsim calibrate prometheus` reads histogram, gauge and ratio queries named in a YAML
  mapping (no metric names are guessed), fits service times (lognormal by default, empirical on
  request; fit error = largest relative error at p50/p75/p90/p95/p99), sets hit ratios, link
  latency (client minus server) and traffic (mean rate or a trace), then replays the observed
  traffic and compares p50/p95/p99 and throughput end to end and per part. `--record` saves the
  responses and the range they cover; `--recorded` replays them offline, and a recording answers
  for its own range (a different range is noted, not used). `archsim calibrate otlp` reads OTLP
  JSON traces: a service's own time (server span minus its outgoing calls), database client
  span times by operation, and the call graph (reported, not drawn). API `POST /calibrate`;
  the web app has a Calibrate dialog (Prometheus URL or recording, or a trace file; a table of
  before/measured/fit error and the replay check; nothing changes until Apply) and a
  "measured" tag on each calibrated input whose tooltip shows source, range, fit error and
  query; reports count inputs by provenance. Fixtures are generated from known distributions
  (`scripts/make_calibration_fixture.py`, `scripts/make_otlp_fixture.py`), not recorded from a
  live system: the starter calibrates back to the truth (api p50 6.00 / p95 18.6 ms against
  6 / 18) and replays the observed end-to-end p95 within 5%. 12 engine tests, 2 API tests,
  5 web tests. Checked in the browser (dev server against this branch's API): the dialog with
  the recorded fixture, the result table and replay check, Apply, and the measured tag and its
  tooltip on the API part. Bug found this way and fixed: a recording did not store its range,
  so the dialog's default "last day" range was cited as the measured window. Not verified:
  against a live Prometheus or a real OpenTelemetry export (no live system here); the OTLP tab
  in the browser (API-tested only). Choices made without the owner: an explicit mapping file
  rather than metric-name discovery (names vary; guessing would claim more than was measured);
  lognormal as the default fit; a trace replays in real time when its range fits in an hour,
  else compressed into the run.
- Auto-sizing (2026-10-26; computed in the web app until engine 0.7.0, see above): an empty
  CPU, memory or storage spec is "Auto": the part gets the size it needs for the design's peak
  load (now archsim_engine/sizing.py: CPU at a 60% target,
  memory for what the engine's memory model needs plus 25%, storage under 70% full; rules in
  docs/simulation-model.md), shown on the canvas and in the inspector with how it was worked
  out, and filled in for runs, sweeps, batch runs, prices and exports. Typed values win; "Back
  to auto" clears them. The engine is unchanged. Effect on the templates, every scenario run
  with and without auto sizes: seven of ten are unchanged (Kafka, both Kubernetes, MongoDB,
  queueing lab, retry storm, serverless). Black Friday's checkout is now CPU-bound while the
  autoscaler catches up during the ramp (baseline success 99.8% to 98.8%, p50 16 to 41 ms).
  Scenarios that add work run on the design's sizes: the cache-aside starter's "20% hit
  ratio" overloads a database sized for the normal ratio (100% to 73% success), Cassandra's
  consistency ALL raises p95 from 16 to 48 ms and its slow-ring fault drops success to 93%.
  Elasticsearch-style scatter reads are counted on every shard. 6 sizing tests.
- Light theme toned down (2026-10-26, "too white"): a slate-grey page and canvas, off-white
  panels and white cards that stand out on the canvas, slightly stronger borders.
- Kubernetes in the web app: cluster logos (Kubernetes, k3s, OpenShift), a "Cluster" select
  in the inspector for services on Kubernetes (`nodeRef` fields), pod squares on Kubernetes
  service cards (ready, starting, Pending; where the starting pods land in the design, the
  recorded state during replay), a node view on the cluster card (pods per node, nodes being
  added, Pending pods, CPU requested), a dashed frame around each cluster and its services,
  dashboard panels (Pods, Nodes, Requested of allocatable) and replay events (node added or
  removed, pods Pending), node and pod numbers in the cluster's inspector and dashboard row
  header, cost per node with member services at no extra cost, and exports that deploy the
  workloads but not the cluster part itself. Two
  templates in a new Kubernetes category: "Kubernetes spike: pods wait for nodes" (baseline
  78% success, 94% with five nodes up front, 90% with 40 s nodes, 66% without the cluster
  autoscaler) and "Shared node pool: a greedy neighbour" (Search's 30% HPA target leaves
  Checkout at two pods and 74% of checkouts succeed; a normal target 97%, a cap on Search or
  twice the nodes 96%). Unit tests for membership, placement and pricing.
- Flat redesign (2026-10-25, see docs/ui-redesign.md for the critique and the DOs and DON'Ts):
  solid surfaces in three tonal steps, hairlines instead of boxes, no gradients, glows, glass or
  aurora, shadows only on menus and dialogs; panels docked edge to edge; one typeface (Geist;
  numbers in tabular figures, Geist Mono only for code and YAML) on a short type scale with no
  text below 11 px; sentence-case labels (no uppercase or `//` prefixes); a one-column parts
  library with logos and descriptions; uniform part cards with a utilisation bar and the status
  shown only when it needs attention; the last run's verdict, throughput and p95 in the top bar
  instead of a card over the diagram; flatter inspector, Results and home page; short entrance
  animations for new results.
- Landing page (the home page): a large headline, a live product shot (a scripted replay of a
  cache outage drawn like the editor: an LCD readout, part cards with level meters, moving
  traffic, autoscaling, saturation, verdict and arrangement lanes) on a dark console band, key numbers read from the catalog, a drifting
  marquee of every technology logo, "How it works" with three small animated vignettes, a feature
  grid of nine tiles (including the dashboard and the calculations), three template cards with
  a link to the gallery, a closing call to action and a footer; saved projects are on their own page. Sections fade up as they enter the view; all motion stops for
  reduced motion. Fixed on the way: the global `p { text-wrap }` rule sat outside Tailwind's
  layers and overrode `truncate` on every paragraph (long names wrapped instead of ending in
  "..."); it now lives in the base layer. Fonts are self-hosted at build time by next/font (no runtime calls
  to Google).
- Client groups (engine 0.3.0, report 1.2): besides the generic Clients part there are Mobile
  app, Web browser and API consumers parts, each with its own defaults (timeout, retries,
  backoff, traffic share) and a technology label with its logo (iOS, Android, React Native,
  Flutter; React, Next.js, Vue, Angular, Svelte; REST / OpenAPI, GraphQL). A design can have
  several client groups: each arrival goes to one group in proportion to its traffic share.
  Links drawn from a client start with that client's typical network latency (catalog
  `linkDefaults`, editable). The inspector shows a "Built with" logo picker, the share and the
  split between client parts; canvas parts show their share; Results and the Markdown report
  add a per-client table (`summary.clients`). Single-client designs give the same simulation
  results as before (no extra random draws). The network and SDK defaults are assumptions.
- Canvas parts show their technology logo larger (26 px in a 40 px tile).
- Resource specs (engine 0.4.0, report 1.3): optional CPU cores, memory and storage per
  instance on services, databases, caches, load balancers and message queues. Effects when set:
  CPU contention (services, databases via a CPU share of query time, caches), a memory
  refusal limit on service replicas (`out_of_memory`), a database buffer cache against a hot
  working set (misses pay a disk read), LRU eviction for TTL caches, storage checks against the
  data size; load balancers and queues are display and price only. Unset specs change nothing
  (all earlier tests pass unchanged). Technology presets carry illustrative sizes. The
  inspector has a Resources block (inputs, common sizes, illustrative price, "Use as cost"),
  canvas parts show "2 vCPU · 8 GiB · 200 GiB", and the last-run panel shows CPU busy, memory
  peak, buffer cache share and evictions.
- Specs in the calculations (report 1.4): capacity figures take the lowest of the slot, CPU and
  memory ceilings and say which (findings and the Components table name it); the cost estimate
  prices parts without a set price from their size (marked "from size"); the design review
  compares a static peak-load estimate with each part's CPU (and flags hot data larger than a
  database's memory, TTL caches smaller than the keyspace); Kubernetes exports set container
  requests/limits and volume sizes, Compose exports set deploy resource limits and reservations.
  The static load estimate ignores retries and TTL-cache misses (no estimate below a TTL cache).
- Links attach on any of the four sides of a component: drag from any side to any side, in
  either direction (if only the reverse direction is valid, the link is drawn that way). The
  chosen sides are saved in `layout.edges` (rendering only; simulation input and digests are
  unchanged), and documents without it default to right → left.
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
  they show only how busy they were. The selected-part panel shows the logo, name, Duplicate and
  Delete icons, the technology menu, a one-line last-run status with three numbers, at most
  three key settings (distributions as a one-line summary that expands to the editor) and
  "More settings" (the rest, notes, and what the model does and does not include). The
  Traffic tab leads with the shape (Steady, Ramp, Burst) and only the inputs that shape needs,
  a small preview, a reads/writes slider and run length with warm-up; arrivals, seed, drain
  and keyspace sit under "More settings". The Scenarios tab is a compact list (name, number
  of changes, simulate, a menu with edit, duplicate and delete); click a row to edit it. Link labels appear only when they carry information.
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

CI on GitHub, on 2026-10-25, in the private repository `dante0747/archsim`: the first run
(commit `e4febe3`) passed all three jobs: Python engine + API on SQLite (ruff, format check,
mypy, pytest; 5 min), API tests on PostgreSQL 16 (37 s), and web lint, types, tests and
production build (2 min). It warned that `actions/checkout@v4`, `astral-sh/setup-uv@v6` and
`actions/setup-node@v4` target the deprecated Node.js 20; the workflow now uses v7 of all
three (Node.js 24), the versions Dependabot proposed, whose CI runs passed with the same
inputs. Dependabot's npm, uv and GitHub Actions update jobs all ran (uv found nothing to
update). Its web dependency group (PR #4, seven updates including TypeScript 7.0) fails lint
because typescript-eslint does not support TypeScript 7 yet, so it stays open.

v2, on 2026-10-24, in the desktop app's browser pane (Chromium) at 1440x900 in dark and light
themes, against the API run locally (SQLite) and against the rebuilt Docker stack
(PostgreSQL 16, engine 0.2.0): home page; Black Friday template end to end (Build with design
review and technology picker, Run in Stress with automatic replay, scrubbing, verdict,
scorecard, Run all & compare, trade-off map, breaking-point sweep), export dialog, command
palette (including adding presets). Bugs found this way were fixed: command ranking, a
render loop in the breaking-point page, sweep runs opening as the default run, a `.gitignore`
rule that hid `components/build/` from git and from Tailwind, the theme script warning.

Themes, on 2026-10-25, same setup: the editor in all six themes, the home page in Midnight and
Dracula, switching from the top bar, the home page and the command palette, and the saved
theme being applied on reload. Bug found and fixed: the theme button crashed the home page
(its tooltip needed a provider only the editor had); a unit test now covers it.

Client groups, on 2026-10-25, against the rebuilt Docker stack (engine 0.3.0): added Mobile
app, Web browser and API consumers to the starter next to its Clients part, checked the parts
panel, the "Built with" logo picker and share split in the inspector, the share on each canvas
part, linked all three to the load balancer, ran it and read the "By client" table (mobile p50
94 ms against 48 ms for the starter's clients, from the mobile link latency). Bug found and
fixed: a part's content box covered its connection points, so the left and right points of
slanted parts (clients) could not be grabbed; the points now sit above the content.

Resource specs, on 2026-10-25, same stack (engine 0.4.0): set the starter's API servers to
1 vCPU / 2 GiB with a size chip, checked the Resources block (inputs, sizes, price hint) and the
spec line on the canvas part, ran it: throughput fell to about 196 req/s (2 replicas x 1 core /
10 ms of CPU work), the API servers were flagged as the bottleneck and the last-run panel showed
"CPU 100% busy". Bug found and fixed: memory below 1 GiB showed as "0 GiB" (now MiB).

Specs in the calculations, on 2026-10-25, same setup (report 1.4): the design review flagged
"API Servers needs more CPU at peak" before running; after the run the finding said "Its CPU
cores can do about 196 req/s", the last-run panel "CPU caps it at 196 req/s", the cost table
priced the API servers "from size" ($62.20 instead of the $146 placeholder), and the Kubernetes
export set cpu "1" / memory "2Gi" requests and limits. Bugs found and fixed: at saturation the
slot and CPU ceilings tie and the finding named the slots (a resource ceiling within 3% now
wins); saved runs store every default, so a default price looked "set" (a price equal to the
catalog default now counts as not set).

Flat redesign, on 2026-10-25, against the rebuilt Docker stack: home page, Design (parts library,
part cards, top-bar run status, inspector), Test (live replay at 4x), Results (answer, cost vs.
speed after simulating all five scenarios) in Midnight, and the editor in Light. The README
screenshots were retaken from this build.

Blueprint and Drafting paper themes, on 2026-10-25 (since replaced by Ops console): home page
and editor in both themes, switching, reload, legacy values.

Studio look, on 2026-10-26, in the browser pane (Chromium) against the dev server and the Docker
API, at 1440x900 in dark and light, plus 1100x760 (editor) and 375x812 (landing page): the
landing page and its product shot, templates gallery, projects page; the editor's top bar
(transport, LCD with the run's summary, Simulate), library grid, part cards with meters,
chaos pads ("Lose Redis Cache" planned the outage, simulated, success dropped to 69.5% in red
and the pad lit), the Test workspace's arrangement lanes, replay from the transport (LCD live
at 00:24.1: 386 req/s, p95 88.9 ms, with the playhead across the lanes), Results and
Dashboard. Fixed on the way: the top bar squeezed the LCD and overlapped at 1440 px, the
chaos rack covered the canvas toolbar (narrower rack; folded on narrow canvases), the LCD
showed the drain bucket's numbers after a replay ended, the hero's part name was squeezed to
one letter by its status badge, the hero's LCD ran off a phone screen, and the hero's lane
paths caused a hydration warning (floating-point noise between server and browser; now
rounded). `npm test` (223 tests, 10 of them new for the meter, transport and chaos pads), `npm run lint`, `npx tsc --noEmit` and `npm run build`
pass.

Technology behaviour, calculations, traces and dashboard, on 2026-10-25, against the dev server
and the Docker API rebuilt with engine 0.5.0: simulated the Black Friday template; the
dashboard's rows, per-component panels (including GC pauses and time in component), crosshair
and annotations; Results → Details → Calculations (system formulas, latency breakdown: the
payments API takes 54% of a typical request; Orders DB shows row locks, group commit and a
replica replay capacity of 158 writes/s against 192 writes/s per shard, so its replicas lag)
and Request traces (a write's query, log flush and slow payment call on the waterfall); the
inspector's "How PostgreSQL behaves" panel and Auto hints. Fixed on the way: a duplicated
"λ =" in a formula, third-party calls had no span. The dashboard helper also checked every row,
drag-zoom, replay, fullscreen, Inspect data and Show on canvas in both themes; replay frame
rate in a visible window was not measured (the pane was hidden). The README screenshots were retaken from the
rebuilt Docker stack (eight images, dark and light, now including the dashboard, calculations
and traces).

Kubernetes, on 2026-10-25, against the rebuilt Docker stack, in the browser pane at about
800x450 in light and dark: the templates gallery's Kubernetes filter and both templates; the
dashed cluster frame (it follows a dragged cluster card); pod squares and the node view in the
design (3 pods, "42% CPU requested") and during replay of the spike template at 90 s (6 ready,
4 Pending, 2 nodes being added); the inspector's Cluster select ("Auto (prod)") and "How
Kubernetes behaves"; the dashboard's Pods panel on the API and the cluster row (Pods, Nodes,
Requested of allocatable: 12 running, 12 Pending at peak, 83% CPU requested). Fixed on the
way: the frame wrapped the Ingress in the shared-pool layout, "Any kubernetes cluster" lost
its capital, the cluster row showed empty request panels and "0 req/s" numbers, Pending pods
were never deleted when the HPA scaled down, and the longest Pending wait ignored pods that
never got a node. On the final rebuild the cluster's inspector shows 4 nodes at peak, 12
Pending at peak, a 256 s longest wait and 80% of node CPU requested for the shared pool. Not
checked: a wide desktop window.

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

- **Browsers other than Chromium**, very large graphs (replay smoothness above ~50 nodes),
  touch devices, and screen readers beyond basic labels; no automated accessibility audit.

## Known limitations

- Client groups share one workload (one read/write mix and pattern); a group cannot have its
  own read ratio or traffic pattern yet. Synchronous, acyclic call graphs; dependencies are
  called sequentially (no parallel fan-out); no closed-loop users.
- Not modeled: OOM kills and restarts, bandwidth and packet loss, deadlocks and range locks,
  vacuum/compaction/checkpoints, page-cache dynamics (the buffer cache is a fixed hit share),
  cache memory for the fixed-ratio model, health checks and failover, consumer group
  rebalancing, DynamoDB adaptive capacity; circuit breakers only as Envoy-style request limits
  on a load balancer; faults are scheduled (outage, slowdown, link latency), not random. CPU and
  memory only matter where their specs are set; resource sizes in presets and prices are
  illustrative.
- Calibration fits one time range at a time (peaks and quiet periods blend unless you pick
  the range), needs a hand-written mapping and reads OTLP as JSON only (not protobuf);
  histogram buckets limit how precise the fitted quantiles are. See [docs/calibration.md](docs/calibration.md).
- Only a core set of technologies is ArchSim's focus, and none is validated against
  measurements yet (Phase 2 of docs/prompts/how-to-win.md); the others are marked
  experimental. docs/assumptions.md lists every illustrative number.
- Technology behaviour uses documented defaults where vendors publish them; GC pauses, JIT
  warm-up, per-runtime cold starts, replica replay cost and log flush times are illustrative
  typical values (labelled in the UI), not measurements. Row locks follow the workload's Zipf
  keyspace, so write contention depends on the key skew you assume.
- Dashboard replay redraws panels about every 60 ms; smoothness with very large designs was
  not measured in a visible window.
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
- README screenshots are 800x500 captures of the previous Ops console look; they need retaking
  if the Studio look is kept. A demo GIF has not been recorded.
- The UI was checked by hand in one browser engine (Chromium in the desktop app): earlier looks
  at 1280x800 and 1440x900, the Studio look at 1440x900, 1100x760 and 375x812, in dark and
  light. There are no automated visual or end-to-end tests yet.
- At 1100 px wide with both side panels open, the Design review pill overlaps the canvas zoom
  toolbar (it did before the Studio look too).
- The canvas layout is usable at tablet widths, but the editor is designed for desktop
  screens.

## Next steps

1. Phase 2 of docs/prompts/how-to-win.md: reproducible benchmarks (PostgreSQL, cache-aside,
   Kafka) that publish predicted against measured latency and knee, then tier thresholds from
   that data (the owner decides the thresholds).
2. Take Dependabot's web dependency group (PR #4) once typescript-eslint supports
   TypeScript 7, or hold TypeScript below 7 until then.
3. Add a Playwright end-to-end test of the main journey (open example, edit, run, compare,
   save, reload).
4. Move simulations to a process pool or a Redis/RQ worker (the `JobRunner` protocol is the
   seam) for parallelism and hard cancellation.
5. Total client deadline across retries, retry budgets / circuit breaker component, and
   parallel fan-out calls.
6. Circuit breakers and retry budgets as link settings, random failures (MTBF/MTTR), health
   checks and failover, multi-region latency.
7. Calibrate against a live Prometheus and a real OpenTelemetry export, export of the
   dashboard to real Grafana, and an agent/MCP surface on top of the validated run API (see
   [docs/roadmap.md](docs/roadmap.md)).
8. More technology behaviour: deadlocks and range locks, vacuum/compaction pauses, consumer
   group rebalancing, adaptive capacity, per-key hot-row splitting.
