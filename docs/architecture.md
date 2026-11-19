# Architecture

ArchSim has three layers with stable contracts between them. Each can be developed and
tested on its own. Why it is built this way, decision by decision, is in the
[architecture decision records](adr/README.md).

```mermaid
flowchart LR
  subgraph web["apps/web (Next.js)"]
    UI["Studio: Design / Test / Results<br/>canvas, technology library, replay timeline,<br/>scorecard, trade-offs, exports"]
    Store["Zustand store<br/>(document, history, runs)"]
    Proxy["/api/v1/* route handler<br/>(proxy to ARCHSIM_API_URL)"]
  end
  subgraph app["archsim-app (Java, Spring Boot): one jar, started as api, worker or migrate"]
    Controllers["api: controllers for projects, runs, workspaces,<br/>reviews, shares, sign-in, tokens, calibration"]
    Replay["api: ReplayPool<br/>(a calibration's replay on platform threads)"]
    Worker["worker: claims, leases, a thread per run"]
    Repos["repositories (JdbcClient)<br/>+ Flyway migrations"]
  end
  subgraph jengine["archsim-engine + archsim-model (Java)"]
    Model["the document, read and checked"]
    JEngine["sizing, cost, review, compare,<br/>exports, imports, calibration"]
    Sim["simulator: kernel, components, metrics"]
  end
  DB[("PostgreSQL")]
  UI --> Store --> Proxy --> Controllers
  Controllers --> JEngine --> Model
  Controllers --> Replay --> Sim
  Controllers --> Repos --> DB
  Worker --> Repos
  Worker --> Sim --> Model
```

## Layers

### CLI (`archsim-cli`, Java)

The `archsim` command ([`archsim-cli/README.md`](../archsim-cli/README.md)) on the Java engine,
without the API or a database: `validate`, `run`, `analyze`, `compare`, `calibrate`, `import`,
`export`, `check` (the CI gate, which the `archsim-check` action runs from an image the job
builds; see [`ci.md`](ci.md)), `catalog` and `schema`. It replaced the Python engine's CLI with
the same flags, lines and exit codes
([ADR-0055](adr/0055-the-java-clis-output-contract.md)). The benchmark harness,
`archsim-benchmarks`, calls the engine in process instead
([ADR-0068](adr/0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md)).

### MCP server (`archsim-mcp`, Java)

A stdio [MCP](https://modelcontextprotocol.io) server on the official MCP Java SDK
([`archsim-mcp/README.md`](../archsim-mcp/README.md)) that calls the Java engine directly: tools
to list, validate, analyze, simulate, compare, sweep, export, import and calibrate, plus
resources for the templates, the JSON Schema and the simulation model. It replaced the Python
server (`packages/mcp`, deleted at the end of the Java port's stage 5) tool for tool, with the
same schemas and values ([ADR-0058](adr/0058-serve-mcp-with-the-official-mcp-java-sdk.md)). See
[`agents.md`](agents.md).

### API (`archsim-app`, Java)

A Spring Boot application ([`archsim-app/README.md`](../archsim-app/README.md)). It stores
projects, runs, workspaces, reviews and share links in PostgreSQL, with SQL written by hand
(`JdbcClient`) and a schema Flyway owns; it reads and checks documents with `archsim-model`,
and analyzes, prepares, compares, exports, imports and calibrates them with `archsim-engine`;
it signs people in and enforces roles, CSRF, rate limits and the outbound policy, under which it
calls the Prometheus servers calibrations name. Runs are only queued: the workers simulate them.
All endpoints live under `/api/v1`; see [`api.md`](api.md).
OpenAPI is served at `/api/v1/openapi.json` and interactive docs at `/api/v1/docs`. It replaced
the Python (FastAPI) API endpoint for endpoint, with the same JSON, cookies and tables
([the Java port](proposals/java-25-port.md)).

### Engine in Java (`archsim-engine`)

A plain Java library over `archsim-model`, with no framework: the engine's deterministic parts,
what ArchSim works out from a design without simulating it and from a run's report
([`archsim-engine/README.md`](../archsim-engine/README.md)). Sizing, cost and pricing, the instant
estimate, the design review and findings, confidence, comparisons and seed summaries, exports,
importers, calibration and the assumptions page are ported from the frozen engine and checked
against its [golden files](../parity/README.md), with their departures listed in the README. The
API calls it directly, in the API's process, and since stage 4 simulates on it too: a
calibration's replay runs on its `Simulator` through `ReplaySimulator.of`, on the API's pool of
replay threads ([ADR-0054](adr/0054-replay-calibrations-on-a-pool-of-platform-threads.md)), and
the worker role simulates the runs it claims
([ADR-0053](adr/0053-simulate-runs-in-the-java-apps-worker-role.md)). It is engine 1.5.1: 1.0.0
replays the frozen engine 0.16.0's runs figure for figure
([java-parity.md](validation/java-parity.md)), 1.1.0 changes only how the cost counts
autoscaled parts and prices the peak
([ADR-0074](adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)), 1.1.1 measures a
run's per-request rates at the base load in the buckets nearest it when none is at it, and 1.2.0
prices a third-party API without a price at its preset's price or leaves it out of the month
([ADR-0081](adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)), 1.2.1
counts a cache's refused lookups in its hit ratio
([ADR-0082](adr/0082-count-a-caches-refused-lookups-in-its-hit-ratio.md)), 1.3.0 reports a
database's busiest instance's CPU and sets its CPU ceiling where that instance fills
([ADR-0083](adr/0083-name-each-utilisation-and-compare-like-with-like.md)), and 1.4.0 counts a
design's lost messages against its availability and judges how old its messages are when
processed ([ADR-0084](adr/0084-judge-asynchronous-designs-by-their-messages-too.md)), 1.5.0
sizes a Kafka broker from what ArchSim's Kafka experiment measured and reports a queue's idle
consumers ([ADR-0085](adr/0085-size-a-kafka-broker-as-measured-and-name-idle-consumers.md)), and
1.5.1 counts the log flush a database's write waits for as a wait in the instant estimate, not
as work on its cores
([ADR-0089](adr/0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md)).

That frozen engine was the Python package `archsim_engine` (`packages/engine`, on SimPy and
Pydantic), frozen at 0.16.0 for the port
([ADR-0017](adr/0017-freeze-python-engine-during-port.md)). Until stage 5 it served the CLI, the
MCP server and the benchmark harness; at stage 5's exit it was deleted, and the tag
`engine-0.16.0` keeps it. What it recorded stays as the Java tests' data, not recorded again:
the golden files, the simulation baselines and the recorded cases
([parity/README.md](../parity/README.md)).

### Workers (`archsim-app`'s `worker` role)

`archsim-app worker` processes take the runs the API queues in PostgreSQL and simulate each with
the Java simulator, on a thread of their own
([ADR-0053](adr/0053-simulate-runs-in-the-java-apps-worker-role.md)): the run lifecycle below
says how. Run as many as needed, on any host that reaches the database, each with a heap for the
runs it takes at once ([archsim-app's README](../archsim-app/README.md#the-workers-heap)). Until
stage 4 the Python workers (`archsim-api worker`) did this, and a Python engine service simulated
a calibration's replay for the API
([ADR-0036](adr/0036-python-engine-service-for-synchronous-calls.md),
[ADR-0045](adr/0045-replay-calibrations-through-an-interim-simulate.md)); both are deleted.

### Web app (`apps/web`)

A Next.js App Router application. The browser only talks to its own origin (`/api/v1/*`);
a route handler forwards those requests to the API at `ARCHSIM_API_URL`
(default `http://127.0.0.1:8000`). The editor keeps the working document in a Zustand store,
renders it with React Flow, and builds the technology library, the inspector forms and the
technology presets from the component catalog returned by `GET /api/v1/components`. Domain
rules live in the engine, not the web app (in the API, its Java port, held to the frozen engine's
golden files): auto sizes, cost estimates and the design review come from
`POST /api/v1/analyze` (called with live validation) and from each run's report, so the CLI, the
API and the web app give the same answers; the docker-compose / Kubernetes / Mermaid exports
come from `POST /api/v1/export`. SLO evaluation, replay events and load sweep
analysis still have TypeScript modules in `apps/web/src/lib/` (the engine has its own, `slo`
and `sweep`, for the CLI and the MCP server; moving the web app onto them is open)
with unit tests (web tests use engine fixtures written by
`./gradlew :archsim-engine:generate`); the simulation itself only ever runs in the engine.

## The document: four separate concerns

A project is one versioned JSON document (details in [`schema.md`](schema.md)):

| Section | Meaning | Affects simulation? |
| --- | --- | --- |
| `architecture` | Typed nodes and routing edges: what exists and how requests flow. | yes |
| `workload` | Traffic: RPS, pattern, duration, warm-up, seed, read/write mix, keyspace. | yes |
| `scenarios` | Named lists of overrides applied on top of the base document. | when selected |
| `layout` | Node positions and viewport. | never |

Node `config` objects are validated by the component model registered for the node `type`,
so the document schema itself does not depend on the component catalog.

## Component registry

Every component type is a `ComponentSpec` (`archsim-model`'s `io.archsim.model.component`)
registered in a `ComponentRegistry`; `StandardComponents.registry()` holds ArchSim's own:

- **ports** (`Port`): direction, accepted/emitted request types (`read`, `write`), min and max
  number of links;
- **config**: a record whose fields carry UI metadata in `@Ui` hints (unit, group, assumption
  flag, conditional visibility), with the type's description, presets and what it models in its
  `catalog/<type>.json` ([ADR-0031](adr/0031-component-catalog-facts-and-presentation.md));
- **validation**: `emittedTypes()` for request-type routing and `validate()` for type-specific
  rules;
- **runtime**: `archsim-engine`'s `components` package, whose `RuntimeComponent` the simulator
  builds for each part from its config, its steps run by the event kernel.

`ComponentCatalog` turns all of this into JSON for the UI (`GET /api/v1/components`). Adding a
component therefore needs no API or frontend changes; see
[`extending-components.md`](extending-components.md).

## Run lifecycle

```mermaid
sequenceDiagram
  participant B as Browser
  participant A as API
  participant D as Database
  participant W as Worker (any instance)
  participant R as Run thread (one per run)
  B->>A: POST /projects/{id}/runs {scenarioId, document?}
  A->>A: prepare in archsim-engine (migrate, resolve scenario, validate, size check)
  alt invalid or too large
    A-->>B: 422 {message, issues[]}
  else ok
    A->>D: insert run (status=queued, input snapshot)
    A-->>B: 202 run
  end
  W->>D: claim: status=running, lease_owner=W, lease 30 s, attempts+1
  W->>R: simulate the run
  loop while it simulates
    R-->>W: progress after each chunk
    W->>D: progress (at most once a second)
    W->>D: heartbeat every 2 s: renew lease, read cancel requests
  end
  R-->>W: report
  W->>D: status=succeeded + report JSON + digests (only while W holds the lease)
  B->>A: GET /runs/{id} (polling)
  B->>A: GET /runs/{id}/report
```

- **States:** `queued -> running -> succeeded | failed | cancelled`.
- **Validation first.** A run is only created if the document resolves and validates, and the
  expected number of requests is under `ARCHSIM_MAX_REQUESTS`. Errors come back immediately as
  `422` with structured issues.
- **Unsaved edits.** A run request may include the current (unsaved) document. It is stored
  with the run, so results stay reproducible without forcing a save.
- **Progress** is simulated time divided by total simulated time, told after each of a run's 200
  chunks. The worker writes it to the database at most once per second; the API reads it from
  there, so any instance can answer.
- **Queue** (`archsim-app`'s `worker` package): the run row is the job. A worker claims as many
  queued runs as it has free slots, oldest first and every workspace's oldest before any
  workspace's second, one claim at a time under a PostgreSQL advisory lock, each workspace kept
  under its cap of runs at once, and leases them; it renews the leases every heartbeat. Progress
  and the outcome are written only while the worker still holds the lease, so a worker that lost
  a run cannot overwrite the one that took it over.
- **Workers** (`archsim-app worker`), as many as needed, on any host that reaches the database;
  the API simulates nothing but a calibration's replay. Each run simulates on a platform thread
  of its worker's, never a virtual one, and a claim waits for a slot to free, a run to be
  requeued, or a second. The runs share their worker's heap, sized for them, and the worker runs
  with `-XX:+ExitOnOutOfMemoryError`: a run that exhausts the heap stops the worker, whose runs
  the reaper then settles as below (compose starts it again).
- **Cancellation:** a queued run is cancelled immediately, by any instance. A running one gets
  `cancel_requested_at`; its worker sees it at the next heartbeat (2 s) and asks the run to
  stop, which it does at its next check (every 4,096 events and after each chunk). A run still
  going `ARCHSIM_CANCEL_GRACE_SECONDS` (10 s) later is written `cancelled`; its thread is asked
  to stop until it does.
- **Limits:** `ARCHSIM_MAX_CONCURRENT_RUNS` runs per worker (default: CPU count - 1); per-run
  wall-clock limit `ARCHSIM_MAX_WALL_SECONDS`, checked by the simulator every 4,096 events and
  after each chunk, and a hard deadline 30 s later at which the worker writes the run `failed`;
  a run's slot stays held until its thread ends.
- **Workers that stop:** a worker that is stopped (SIGTERM, Ctrl+C) claims no more and gives its
  runs back to the queue without counting the attempt, keeping their leases while they stop
  (15 s at most). One that dies stops renewing its leases; every worker reaps expired leases
  every 10 s (and at start-up): requeued after a first attempt, `failed` with "The simulation
  worker stopped unexpectedly…" after a second, `cancelled` if a cancel was requested. Workers
  silent for an hour leave `job_workers`.
- **No orphans:** a run is a thread of its worker's process and ends with it.
- **Reproduce:** `POST /runs/{id}/rerun` re-simulates the stored input with the same scenario
  and seed. Equal `resultDigest` values confirm reproducibility.

## Persistence model

The tables a run goes through (the others hold people, workspaces, reviews and share links):

**`projects`** - `id`, `name`, `description`, `document` (JSON/JSONB), `schema_version`,
`revision`, timestamps. `revision` increments on each update; clients can send
`expectedRevision` and receive `409 Conflict` if someone else saved in between (optimistic
concurrency). Documents that are schema-valid but fail graph validation (for example a missing
link) can be saved, so work in progress is never lost; they cannot be simulated until fixed.

**`simulation_runs`** - status, progress, error, seed, engine version, input/result digests, a
small denormalised `summary` for fast listing, timestamps, plus two large **deferred**
columns: `input_document` (exactly what was submitted) and `report` (the full result). Deferred
columns are not loaded when listing runs. The report is written once, when the run finishes.
Deleting a project deletes its runs (`ON DELETE CASCADE`). Queue columns: `attempts`,
`lease_owner`, `lease_expires_at`, `cancel_requested_at`, with an index on
`(status, created_at)`.

**`job_workers`** - one row per worker: host, process id, slots, runs in flight, status
(`running` or `draining`) and its latest heartbeat.

Flyway owns the schema: its `V1` reproduces revision 0008 of the Alembic migrations the schema
started from ([ADR-0032](adr/0032-flyway-takes-over-from-alembic.md)). Stage 6 deleted those
migrations; a database below their revision 0008 upgrades first from the tag `alembic-0008`
([ADR-0063](adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)). Documents are
`JSONB`.

## Determinism and provenance

Every report carries `provenance`: engine, schema and report versions, seed, scenario id and
name, the exact overrides applied, `inputDigest` (SHA-256 of the canonical resolved input) and
`resultDigest` (SHA-256 of the canonical results). The same input, scenario, seed and engine
version produce the same `resultDigest` on every platform with the Java simulator, which takes
its transcendental functions from `StrictMath`; the frozen Python engine gave it on the same
platform only ([ADR-0050](adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)). See
[`simulation-model.md`](simulation-model.md#determinism).
