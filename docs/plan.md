# Implementation plan and assumptions (MVP)

This is the short plan the MVP was built against. Current progress lives in
[`IMPLEMENTATION_STATUS.md`](../IMPLEMENTATION_STATUS.md).

## Goal

One complete, honest vertical slice: draw a distributed system, configure it, simulate a
workload with a real discrete-event engine, read explainable metrics, compare scenarios, save
and reload, and reproduce any result from its stored seed and input.

## Build order (vertical slices, each with tests)

1. **Schema and domain model** - versioned JSON document (`schemaVersion` 1.0), distributions,
   component registry with typed ports and config models.
2. **Executable simulator** - SimPy engine, request lifecycle, metrics, accounting invariants,
   bottleneck analysis, scenario overrides, comparison, CLI. Validated against analytic M/M/c
   results.
3. **API and persistence** - FastAPI, SQLAlchemy, Alembic, background runs with progress,
   cancellation and limits; projects with import/export.
4. **Interactive editor** - Next.js, React Flow canvas, palette, inspector generated from the
   component catalog, validation feedback, auto-layout.
5. **Simulation output** - KPI cards, time-series charts, per-node heat overlay, findings.
6. **Scenario comparison** - side-by-side KPIs, input diff, explanations.
7. **Docs, CI, Docker** - this documentation, GitHub Actions, Docker Compose.

## Technology choices and reasons

| Area | Choice | Reason |
| --- | --- | --- |
| Simulation | **SimPy** on a thin runtime layer (`runtime.py`) | Mature, deterministic event ordering, process-style code maps naturally onto request lifecycles (queue wait, timeouts via `event \| timeout`). A custom core would be faster but adds risk; the runtime layer keeps SimPy swappable. |
| Engine packaging | Separate Python package `archsim_engine` | The simulator has no dependency on FastAPI or the database, so it can be tested, used from the CLI, or embedded elsewhere. |
| API | FastAPI + Pydantic v2 | Typed request/response models and OpenAPI for free; Pydantic is already the engine's schema layer. |
| Background runs | Worker processes (`ProcessJobRunner`; `ThreadJobRunner` with `ARCHSIM_RUNNER=thread`) | No extra infrastructure for a single-user local tool; CPU-bound runs use their own cores and leave the API responsive. Runs are bounded and cancellable. A `JobRunner` protocol is the seam for Redis/RQ or Celery later. (Threads were the first design; they shared the API's GIL.) |
| Persistence | **PostgreSQL** (documented default, JSONB) with **SQLite** fallback | PostgreSQL via Docker Compose for realistic setups; SQLite means `uv run archsim-api serve` works with zero setup and keeps tests hermetic. CI also runs the API tests against PostgreSQL. |
| Migrations | Alembic, applied automatically on startup (`ARCHSIM_AUTO_MIGRATE`) | Safe schema evolution; a test checks ORM models and migrations agree. |
| Reports | One JSON document per run, written once when the run finishes | No per-event database writes; memory is bounded by time buckets and histogram bins. |
| Frontend | Next.js 16, React Flow (`@xyflow/react`), Zustand, Tailwind CSS v4, Radix primitives, Recharts, elkjs | As specified in the brief. React Flow handles the editable graph; ELK provides layered auto-layout. Recharts covers the time-series needs, so PixiJS was not needed. |
| UI forms | Generated from field descriptors served by `GET /api/v1/components` | Each config field carries unit, range, group and an "assumption" flag in Python. New components appear in the palette and inspector without frontend changes. |

## Modeling assumptions (MVP)

- **Units:** all latencies and service times are in milliseconds; workload durations in
  seconds; rates in requests per second.
- **One traffic source** per document. The workload drives it. Multiple client groups are a
  documented limitation. (Lifted in engine 0.3.0: several client groups split the workload by
  their traffic share; see docs/simulation-model.md.)
- **Open-loop arrivals** (Poisson by default, or evenly spaced), with constant, ramp or burst
  patterns.
- **Synchronous, acyclic call graphs.** Cycles are rejected. A service calls its dependencies
  sequentially for each request type.
- **Assumptions are labelled.** Service times, hit ratios, TTLs and latencies are flagged as
  user assumptions in the schema, the UI and every report.
- **No calibration yet.** Results are synthetic estimates. Calibration from telemetry is on the
  roadmap ([`roadmap.md`](roadmap.md)).

## Out of scope for the MVP

Authentication, multi-tenancy, payments or licensing, telemetry, nested subsystems, failure
injection, Prometheus/Grafana integration, AI/MCP endpoints. Extension seams for these are
described in [`roadmap.md`](roadmap.md).
