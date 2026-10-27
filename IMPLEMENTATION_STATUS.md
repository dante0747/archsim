# Implementation status

State as of 2026-10-27 (engine 0.10.0, schema 1.3, report 1.12). This file separates what is
implemented, how it was verified, what is known to be limited, and what comes next. Nothing
listed under "Implemented" is a stub or a mock. The product and UX brief for v2 is
[docs/product-spec-v2.md](docs/product-spec-v2.md).

## Implemented and covered by automated tests

**Product review fixes (2026-10-26, engine 0.10.0, schema 1.3).**

- Cache-aside is modelled as applications do it. A cache with no `miss` link answers "miss" to
  the calling service, which reads the data store itself over a link set to `readsOnMiss`
  ("Reads only on a cache miss") and writes the value back; a link set to `continueOnFailure`
  ("Carry on if this call fails") turns a failed lookup into a miss and skips a failed
  invalidation. Read-through (a `miss` link) works as before. The "Cache-aside web API"
  starter now uses cache-aside: Redis no longer calls PostgreSQL, and losing Redis sends reads
  to PostgreSQL instead of failing them (with the starter's 8 API workers, a 15 s outage at
  400 req/s now ends at 88.7% success from API saturation, where reads used to fail
  outright). The static load estimate, design review, findings, validation, import guesses
  and export round trips follow the new links (`test_cache_aside.py`, import tests).
- Breaking point: latency percentiles cover successful requests only, so the throughput chart
  now draws failed requests, both charts shade the steps that failed more requests than the
  availability objective allows (1% without one), failing p95 points are marked, the tooltip
  gives the success rate, and the Markdown report says so. The Compare map leaves runs with
  too many failures off the Pareto frontier.
- The Test plan no longer says "the plan changed since" right after a run on PostgreSQL:
  JSONB reorders keys, and the check compared `JSON.stringify` output. It now compares the
  values (`sameExperiment` in `lib/faults.ts`, tested with a JSONB-ordered run).

**Product review quick wins (2026-10-27, report 1.12).**

- Reports add `summary.latencyWithFailuresMs` (same shape as `latencyMs`): successes at their
  latency and each failed request as a full client timeout (as long as it took when retries ran
  past it). Failures that ended within the timeout are kept exactly, so a p95 that lands on them
  reads as the timeout. The breaking-point chart draws it dashed when any step failed requests,
  the table and the Markdown report add a "p95 with failures" column, and the Results page shows
  it next to p95 when some requests failed. Sweep rows (CLI and web) carry `p95WithFailures`.
- The starter's PostgreSQL allows 20 concurrent queries instead of 6. Every starter scenario
  gives the same success and latency; PostgreSQL's utilisation drops and the "fewer connections
  than callers" review note goes away.
- The landing-page animation shows cache-aside: the API reads PostgreSQL itself on a miss.
- No file paths in the UI: the pricing card and the engine's pricing messages and hints no
  longer point to `docs/pricing.md` or `scripts/prices/`. The tour says to drag from any of the
  dots on a part's edges.
- The web app sends security headers: a content security policy (same-origin only, inline
  scripts allowed because Next.js bootstraps with them, `frame-ancestors 'none'`),
  `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, a
  restrictive `Permissions-Policy` and `Cross-Origin-Opener-Policy: same-origin`, and no
  `X-Powered-By`. Checked in a production build: no policy violations on the landing page,
  editor, Results and breaking point.

**Review fixes (2026-10-26, branch review-fixes on top of phases 1-8).** A review of phases
1-8 found bugs the tests did not cover; each fix below has a regression test that failed
before it.

- Untrusted YAML (imports, calibration mappings, CLI, MCP) goes through one loader,
  `archsim_engine.safe_yaml`: aliases are allowed (Compose merge keys) but capped at 200,000
  expanded values; recursive aliases, nesting past 100 levels and text over 5 MB are refused. A
  600-byte alias bomb that held the importer for 19 s is refused in milliseconds. PyYAML is a
  declared engine dependency.
- Imports classify images by full repository name and list image-based types under `guessed`;
  exporters, UIs and migrators become plain services and `server` is no longer SQL Server.
  Imported designs validate and run (one entry for clients, never the cluster part; cache misses
  wired; small memory limits fit; Terraform load balancers get guessed targets; a test checks
  every fixture). Replicas default to 1, real `500m`/`512Mi` requests are kept, HPA targets are
  CPU only (v1 supported), Terraform references match exact addresses. Bad input is a 422 / exit
  2, never a 500; the CLI reads UTF-16 and BOM files.
- Calibration: the API never reads files from a mapping and its errors never echo input; the
  Prometheus client has one deadline, a 50 MiB cap and no redirects, and
  `ARCHSIM_PROMETHEUS_ALLOWED_HOSTS` must be set before the API is reachable from elsewhere.
  "Measured" requires the input to still hold the fitted value (stored in provenance), so
  scenarios, sweeps and edits show as assumed. Calibration never changes the run's duration;
  replays run under limits (API: 60 s wall clock) and a limit gives a 422. OTLP parent-link
  loops end. Empirical histogram tables end where the data ends and report the share past the
  last bucket; the replay summary always names the worst error.
- The process runner survives a crashed worker: runs on the broken pool fail with "The
  simulation worker stopped unexpectedly", the pool is replaced once per break and later runs
  succeed without a restart (tested by crashing a worker); a worker's death is noticed within
  0.5 s. `DocumentInvalidError` crosses process boundaries, so parallel sweeps report a
  design's issues instead of `BrokenProcessPool`.
- `archsim check`: an unreadable `--baseline-ref` (typo, shallow clone, not a repository) exits
  2 instead of passing; only a file missing at a valid commit means "no baseline". `--knee`
  sweeps design and baseline on one shared grid (then a finer one) and flags a drop only past
  the threshold and one grid step; missing the objectives at the lightest load is a
  regression. Any error exits 2; output is UTF-8 on Windows. The CI action posts one comment
  per design, updates only its own bot's comment, escapes design text and works from other
  repositories.
- Pricing: a technology preset's `costPerHour` is illustrative (`priceSource: "preset"`) and a
  price snapshot reprices it; a user price beats managed rates; API gateways are not priced at
  the load balancer hourly rate; the generic-rates note stays next to snapshot lines.
  `pricing.snapshotDate` / `snapshotSha256` pin a snapshot (exactly that one, or a warning and
  generic rates), and runs record the snapshot used (date and content hash) in
  `input.pricing`. Documents without `pricing` keep identical results.

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
- Accuracy benchmarks (2026-10-26; Phase 2 of docs/prompts/how-to-win.md). `benchmarks/`
  runs four experiments on real stacks in Docker (a small Node.js service, PostgreSQL 16, Redis
  7, Kafka 3.8, k6 as the open-loop load generator, every container pinned to its own CPUs):
  90/10 reads and writes on PostgreSQL, every request updating one row, cache-aside with Redis,
  and a Kafka producer with consumers writing to PostgreSQL. Each run settles the database,
  calibrates ArchSim from two low-load passes (every input with the formula that derived it),
  writes its predictions for a load ladder before measuring, measures each step three times
  (medians), climbs further when the system still holds, and compares (`comparison.json`).
  `benchmarks/report.py` writes the tables and charts of [docs/validation.md](docs/validation.md);
  `make benchmark` runs everything; `.github/workflows/benchmark.yml` runs it by hand on a
  hosted runner (no schedule). Run 4 (protocol v4, this laptop), one run each: knee error -10%
  (pg-mixed), -14% (cache-aside), +6% (Kafka), +97% (hot-row, not measurable here: stalls at one
  step, measured knee 132 to 819 req/s across runs). These are single runs inside run-to-run
  noise (pg-mixed's measured knee was 1,895, 3,162 and 2,291 req/s in runs 2 to 4), and partly
  in-sample: the protocol was revised after the pg experiments' high-load results were seen, so
  only cache-aside and Kafka were first run under the final protocol. Low-load p95 is 0.1 to
  1.4 ms high for pg-mixed, hot-row and cache-aside and about 8.7 ms (+303%, +332%) high for
  Kafka (its calibration pass had a send/INSERT tail the measured steps did not), the model's
  cliff past the knee is sharper than the real systems', and Kafka consumers (group commit) ran
  about 2x faster than modeled; validation.md explains each and lists the model changes the data
  supports. Runs 1 to 3 of the pg experiments are kept with what each exposed (low-load CPU power
  states, a double-counted driver CPU, the post-load autovacuum and host stalls). Committed per
  run: calibration, predictions and each measured repeat's numbers; k6's raw summaries are not
  kept. `report.py` computes the proposed tier criteria per experiment (no experiment meets
  them, so tiers are unchanged), the run history and the low-load table; `run.py compare`
  recomputes the comparisons from the committed data (failed repeats, which run 4 recorded as
  0 ms, now count as failed; the median below the knee is the true median). 34 tests in
  `benchmarks/tests` (in `uv run pytest`), including one that fails when docs/validation.md is
  stale; CI lints and type-checks `benchmarks/`. Not verified: a second machine, the hosted-runner workflow (never run:
  nothing is pushed), Linux (k6's output directory is now made writable for its uid, untested),
  the drain and health check between repeats (added after run 4, never run).
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
- Performance (report 1.11, 2026-10-26; Phase 8 of docs/prompts/how-to-win.md). Measured first
  (`scripts/perf.py`, numbers in `benchmarks/perf/` and [docs/performance.md](docs/performance.md)):
  full simulation runs about 2,000 to 5,000 requests per second per core on this laptop, with no
  single hot spot. Runs now execute in worker processes (`ProcessJobRunner`, the API default;
  `ARCHSIM_RUNNER=thread` for the old behaviour): progress and cancellation cross the process
  boundary through a multiprocessing manager, results are identical to a thread's, and the API
  answers in under a second during a 10-minute run (tested). Breaking-point sweeps run their
  steps in parallel processes (`archsim check --knee`, the MCP tool; same curve, tested). A
  least-connections load balancer stops at the first idle replica (same pick; every template's
  digest unchanged; 2-6% faster side by side). Sampled (hybrid) mode, opt-in (`archsim run
  --sampled`, `run_simulation(mode="sampled")`, the MCP `simulate` tool): a steady load (constant
  rate, no faults) much longer than it needs to settle is answered from the warm-up plus a
  steady sample (at least 10 s and 50,000 arrivals) once the sample passes stationarity checks
  (throughput, keeping up, p95 drift, no growing queue, backlog or replica count); anything else
  is simulated whole, and the report's `simulationMode` says which. Validated against full runs
  of a 10-minute constant variant of every template: the six sampled answers are within 2% on p95
  and 0.5% on throughput (bar ±5%), and the two not-steady cases (a growing Kafka backlog, an
  overloaded MongoDB) fell back to full runs as they should. The brief's target, a six-part
  design at 10k req/s for 10 minutes (6M requests), took 3,306 s in full and 65.6 s sampled (same
  p95, throughput +0.15%): 50 times faster but **5.6 s over the 60 s target** on this laptop.
  Not done: a faster event loop or compiled core; sampled mode as the default for long runs
  (waits for the web app to show sampled reports); sampled mode through the API.
- An SLO gate in CI (2026-10-26; Phase 7 of docs/prompts/how-to-win.md). `archsim check
  design.json [--scenario] [--baseline other.json | --baseline-ref origin/main] [--fail-on
  objectives|regression] [--knee] --format markdown|json` exits 0 when it passes, 1 when an
  objective is missed or a metric regressed past its threshold (p95 +10%, success rate -0.1 pp,
  monthly cost +10%, breaking point -10%; all settable), 2 for unusable input. The Markdown is a
  pull request comment: a verdict, one sentence ("p95 goes from 180 to 410 ms (objective 300 ms)
  — fails; ... cost +$140 a month"), a comparison table and the objectives, with the engine
  version and result digest. `.github/actions/archsim-check` is a composite action: uv, the
  check, the job summary, an `archsim-check` artifact, one sticky PR comment updated on every
  push (found by a hidden marker; inputs passed through environment variables, not
  interpolated into the script), and a failing job when the check fails. This repository's CI
  runs it on the starter template against the base branch or the previous commit.
  [docs/ci.md](docs/ci.md) has an example workflow, and the flow for infrastructure changes
  (import both sides, then compare). Tests: objectives pass and fail, regressions against a
  file and against a git ref in a temporary repository, user thresholds, CLI exit codes 0/1/2, a
  Markdown snapshot, and the sticky comment's update-or-create logic with a fake GitHub API.
  Not verified: the action on GitHub (nothing is pushed), so the brief's acceptance (a PR in
  this repository gets a correct comment and fails on a missed objective) is checked locally
  only: the same command, comment and exit code the action uses.
- Price snapshots and real machine shapes (engine 0.9.0, schema 1.2, report 1.10, 2026-10-26;
  Phase 6 of docs/prompts/how-to-win.md). A design names its price source in `pricing`
  (`provider`, `region`, `purchaseOption`, `families`). `generic`, the default, is the
  illustrative per-vCPU, per-GiB and storage rates as before: no shapes, the same sizes and the
  same result digests. Any other provider names a price snapshot (`archsim.prices/1`: on-demand
  prices of instance types, managed databases, caches and Kafka brokers per engine, and rates
  for storage, serverless, managed queues and load balancers, with sources and a date). With a
  snapshot, auto-sizing snaps each part's CPU and memory to the cheapest instance type that
  covers them (a value set on the part counts as the need; `families` narrows the choice), an
  engine with no managed type runs on compute instances (self-managed), and every cost line
  cites its SKU and the snapshot date; the inspector, `/analyze`, the report, the scorecard and
  the Markdown report show the SKU, and the inspector's overview has a price selector fed by
  `GET /pricing`. A design naming a snapshot this machine lacks gets a warning and generic
  rates. Refresh scripts in `scripts/prices/` read the AWS Price List bulk API (EC2, RDS,
  ElastiCache, MSK, EBS gp3, Lambda, SQS, ALB; CSV, streamed), the Azure Retail Prices API (D,
  E and F series VMs) and the GCP Cloud Billing Catalog (N2, N2D, E2, C3); they are never called
  at runtime. Tests: 6 engine tests on a made-up snapshot of a fictional provider (format,
  cover, families, self-managed engines, snapped sizes and cited costs, a run on snapped shapes,
  a missing snapshot, the 1.1 -> 1.2 migration), the scripts' parsers on small hand-written
  samples of each API's documented layout, web tests for keeping `pricing` through edits.
  Checked in the browser (API started with the test snapshot directory): the selector lists
  generic and the snapshot; choosing it snaps the starter's API servers to `cpu.4x8` and the
  inspector cites the price, provider, region and date. Not done, waiting on
  the owner: no provider snapshot ships (which provider and region first is the owner's
  decision), so the brief's acceptance (Black Friday priced on AWS us-east-1 with real instance
  types) is not met yet; the refresh scripts have not been run against the live price lists.
  Savings plans and reserved terms are not modeled.
- Import from docker-compose, Kubernetes and Terraform (2026-10-26; Phase 5 of
  docs/prompts/how-to-win.md). `archsim import compose|k8s|terraform FILE -o design.json
  --report report.json`, `POST /import`, an "Import from compose, Kubernetes, Terraform" dialog
  in the editor (format detected from the file; shows the report; replaces the design, undoable)
  and an MCP tool `import_design`. Compose services become parts by image, `deploy.replicas`,
  resource limits and connection limits in the command are read, links come from `depends_on`
  and URL environment values; Kubernetes reads Deployments, StatefulSets, DaemonSets, Knative
  Services, Services, HPAs and Ingresses; Terraform (`terraform show -json`) maps databases,
  caches, functions, queues, streams, load balancers, CDNs and clusters on AWS, GCP and Azure,
  sizing instances from their type (AWS families, GCP machine types, Azure D/E v3-v6 and F v2).
  The import report lists what was mapped, what was guessed (inferred links, the Clients part,
  a cluster part, read replicas counted from pods), what was ignored, links to confirm
  (Terraform rarely states calls) and validation issues; every number the import set is
  provenance `assumed` with its source and, where derived, a note. Round trip: exports now
  carry each part's id, technology, platform, runtime and outgoing links (Compose labels,
  Kubernetes annotations) and the parts nothing deploys (an `x-archsim` block, a ConfigMap), so
  every template exported to Compose or Kubernetes imports back with the same parts, types,
  technologies, replicas, autoscaling and links and no guesses (a test over all 10 templates in
  both formats). `archsim export` was added for the round trip from the command line. Tests:
  20 round trips, 3 fixtures, error cases and malformed `x-archsim` parts (engine), 2 API
  tests, 2 web tests, the MCP stdio session. Choice made without the owner: the fixtures are
  written for these tests in the style of common examples (a voting app, a shop on Kubernetes,
  an AWS plan) instead of vendored third-party files, which would need their licences added to
  the repository (listed as an owner decision).
- MCP server for AI coding agents (2026-10-26; Phase 4 of docs/prompts/how-to-win.md).
  `packages/mcp` (`archsim-mcp`) is a Model Context Protocol server over stdio on the official
  `mcp` Python SDK (v2, `MCPServer`), running the engine directly: tools `list_part_types`,
  `list_templates`, `get_template`, `validate_design`, `analyze_design`, `simulate`,
  `get_results`, `compare`, `breaking_point`, `export` and `calibrate` (files only, no
  Prometheus queries), resources for the document schema, the templates and the simulation
  model doc. Results are compact JSON (verdict, key numbers, findings, per-part status, cost,
  run id and digest); unusable documents come back as errors listing each issue and its fix;
  runs keep the engine's limits (120 s, 2 million requests by default). To serve both the MCP
  server and the web app from one source, the docker-compose, Kubernetes and Mermaid exporters
  moved from TypeScript to the engine (`archsim_engine.export`, `POST /export`; the web app's
  Export dialog calls it and the TypeScript copies are deleted), and the engine gained the
  breaking-point sweep (`sweep.py`) and objective checks (`slo.py`) the MCP tools need. Tests:
  an agent session over stdio with the SDK's own client (tools and resources listed, simulate
  twice with the same digest, analyze, export, an invalid design's validation and error,
  get_results, compare two scenarios, a three-step breaking point, calibrate from the recorded
  fixture), 9 exporter tests, 1 API test. Not verified: Claude Code, Claude Desktop or Cursor
  driving the server (only the SDK client); the package is not published (`uvx` does not work
  yet). The web app keeps TypeScript copies of the SLO verdict and sweep analysis for its own
  pages (moving them onto the engine is open).
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
  6 / 18) and replays the observed end-to-end p95 within 5%, with a largest error of -14%
  (PostgreSQL p99). The fixture is ArchSim's own simulation of known parameters, so this checks
  the method, not real-world accuracy. 12 engine tests, 2 API tests,
  5 web tests. Checked in the browser (dev server against this branch's API): the dialog with
  the recorded fixture, the result table and replay check, Apply, and the measured tag and its
  tooltip on the API part. Bug found this way and fixed: a recording did not store its range,
  so the dialog's default "last day" range was cited as the measured window. Not verified:
  against a live Prometheus or a real OpenTelemetry export (no live system here); the OTLP tab
  in the browser (API-tested only). Choices made without the owner: an explicit mapping file
  rather than metric-name discovery (names vary; guessing would claim more than was measured);
  lognormal as the default fit; the observed traffic curve is always fitted into the run's
  existing duration (review fix: ranges up to an hour used to replay in real time, which made
  every later run of the document that long).
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
  faults and experiment verdicts, cost, load sweeps, decision report and design review rules
  (the exporters are tested in the engine). A Python test keeps the web catalog fixture in sync
  with the registry.

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

- Still open after the review fixes: `recover_interrupted` marks every active run failed when
  any API process starts, so several API processes on one database break each other's runs;
  a hard kill of the API leaves worker and manager processes running on Windows; when a pool
  breaks, every run on it fails, not only the one whose worker died. Sampled mode checks the
  2M request limit against the full run, so the CLI and MCP refuse the 6M-request design
  (`scripts/perf.py` runs it with the limit raised). A price someone types that equals a
  preset's value counts as illustrative. Terraform target groups and listeners are not read
  (load balancer targets are guesses).
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
- Only a core set of technologies is ArchSim's focus, and none is validated yet: on one
  machine and one run, the benchmarks (docs/validation.md) put the knee error at -10%
  (pg-mixed), -14% (cache-aside), +6% (Kafka) and +97% (hot-row), inside run-to-run noise and
  short of the proposed thresholds; the others are marked experimental. docs/assumptions.md
  lists every illustrative number.
- Technology behaviour uses documented defaults where vendors publish them; GC pauses, JIT
  warm-up, per-runtime cold starts, replica replay cost and log flush times are illustrative
  typical values (labelled in the UI), not measurements. Row locks follow the workload's Zipf
  keyspace, so write contention depends on the key skew you assume.
- Dashboard replay redraws panels about every 60 ms; smoothness with very large designs was
  not measured in a visible window.
- Technology presets are illustrative starting points, not vendor benchmarks. Costs use
  generic illustrative rates until a price snapshot is added (none ships yet); with one, they
  are on-demand list prices without discounts, data transfer or load balancer capacity units.
  See [docs/pricing.md](docs/pricing.md).
- The breaking-point sweep uses short steps: autoscaled services start each step at their
  configured replica count, so it measures capacity before the scaler reacts (the page says
  so).
- Replay resolution is the report bucket (1 s); playback is throttled by the browser when the
  tab is in the background.
- Heavy designs take a while: full simulation runs about 2,000-5,000 requests per second per
  core on a laptop (the Black Friday template takes 35-45 s; a 10k req/s, 10-minute design
  about 55 minutes). Sampled mode answers long steady runs much faster (65 s for that design)
  but is opt-in and engine/CLI/MCP only. "Run all & compare" runs at most
  `ARCHSIM_MAX_CONCURRENT_RUNS` (default 2) simulations at a time.
- Imports read what infrastructure files state: no latency, traffic or call graph beyond
  links and URLs (Terraform states almost none); Helm and Kustomize must be rendered first and
  HCL is not read (only `terraform show -json`); image matching is by name. See
  [docs/import.md](docs/import.md).
- Exports (docker-compose, Kubernetes, Mermaid) are scaffolding to review, not deployable
  production configuration.
- Client `timeoutMs` applies per attempt, not as a total deadline. Link retries also fire on
  instant rejections (`queue_full`).
- Wasted work is counted only for requests whose client gave up, not for work continuing after
  an internal dependency timeout.
- Cancellation and the wall-clock limit are checked between progress chunks (1/200 of the
  simulated time), so a run can overshoot by up to one chunk.
- Simulations run in worker processes on the API's machine: they do not survive an API
  restart (they are marked interrupted) and do not scale across machines. Cancellation is still
  cooperative (between chunks).
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

1. Rerun the benchmarks on a quieter Linux host (or the hosted-runner workflow) to reproduce
   run 4 and measure hot-row, then decide tier thresholds (the owner; a proposal is in
   docs/validation.md). Model changes the data supports: CPU per request that falls with load,
   group commit, a gradual saturation regime.
2. Take Dependabot's web dependency group (PR #4) once typescript-eslint supports
   TypeScript 7, or hold TypeScript below 7 until then.
3. Add a Playwright end-to-end test of the main journey (open example, edit, run, compare,
   save, reload).
4. A Redis/RQ worker (the `JobRunner` protocol is the seam) so runs survive API restarts and
   scale across machines; hard cancellation of a worker process.
5. Total client deadline across retries, retry budgets / circuit breaker component, and
   parallel fan-out calls.
6. Circuit breakers and retry budgets as link settings, random failures (MTBF/MTTR), health
   checks and failover, multi-region latency.
7. Calibrate against a live Prometheus and a real OpenTelemetry export, export of the
   dashboard to real Grafana, and an agent/MCP surface on top of the validated run API (see
   [docs/roadmap.md](docs/roadmap.md)).
8. More technology behaviour: deadlocks and range locks, vacuum/compaction pauses, consumer
   group rebalancing, adaptive capacity, per-key hot-row splitting.
