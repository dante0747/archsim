# Proposal: ArchSim on Java 25, ported in stages

**Status:** approved by the owner on 2 Nov 2026, with the [decisions](#decisions) below. Stage 0
(foundations) is under way.

**Why:** the owner works in Java and wants to maintain ArchSim. This is a port, not a redesign of
what ArchSim does: the simulation model, the design format and the web app's behaviour stay the
same. The structure and tooling become standard modern Java.

## What moves and what stays

| Part | Today | After the port |
| --- | --- | --- |
| Simulation engine, analysis, sizing, cost, calibration, importers | Python, 28.8k lines (+9.6k tests) | two Java libraries, no framework: `archsim-model` and `archsim-engine` |
| HTTP API, sign-in, run queue, workers | Python FastAPI, 7.9k lines (+4.1k tests) | one Spring Boot 4 app, `archsim-app` |
| MCP server for AI agents | Python, 0.5k lines | Java (official MCP Java SDK) |
| CLI and `archsim check` GitHub Action | Python | Java (picocli) |
| Web app | TypeScript / Next.js, 39k lines | **unchanged** |
| Design document format (schema 1.7 JSON) | shared contract | **unchanged** |
| HTTP API contract (routes, JSON, cookies) | shared contract | **unchanged** |
| PostgreSQL data | Alembic migrations | **same tables**, Flyway from the current revision; no data migration |
| Benchmark harness (k6, Docker, statistics) | Python, 11.8k lines | stays Python during the port and calls the Java engine's CLI; ported to Java after stage 6 |

## Principles

1. **Strangler, not big bang.** At every step main works and deploys. Python code is deleted only
   after its Java replacement has passed parity.
2. **Parity before replacement.** Each stage has an exit check against the Python version.
3. **No model changes during the port.** A port that also changes behaviour can't be verified.
   Accuracy improvements happen before or after, never in the same stage.
4. **Mainstream and documented.** A Java developer should find the code where they expect it,
   built and checked the way they're used to.

## Target architecture

```
apps/web (Next.js, unchanged)
   │  HTTP + JSON (same contract)
   ▼
archsim-app (Spring Boot 4: one image, started as api, worker or migrate)
   one package per feature: io.archsim.app.projects · .runs · .auth · .tokens · .shared …
   each holds its controller, service and repository (JdbcClient, PostgreSQL)
   worker: a Spring profile without the web layer; claims runs from the queue (SKIP LOCKED)

archsim-cli (picocli)      archsim-mcp (MCP Java SDK)      benchmarks (Python → archsim-cli)

   │ the app, CLI and MCP use both libraries below
   ▼
archsim-engine (plain Java library)
   kernel (event-driven, single-threaded) · components · analysis · sizing · cost · pricing
   calibration · importers · export
   │ uses
   ▼
archsim-model (plain Java library, usable without the simulator)
   design document records · upgrades from schema 1.0 to 1.7 · validation
```

**Recommended stack** (each has a decision below where there's a real alternative):

- **Java 25 (LTS)**: records, sealed interfaces for component types, pattern-matching `switch`,
  virtual threads for request handling.
- **Gradle 9 with Kotlin DSL**, a version catalog, and one Gradle module per box above, each
  added by the stage that first needs it.
- **Spring Boot 4** (Spring Framework 7, Jackson 3), Spring Security's OAuth2 client for GitHub
  and Google sign-in, CSRF protection and hashed API tokens, as today. **One app,
  `archsim-app`**, organised by feature: one package per feature, holding that feature's
  controller, service and repository classes. It builds one image, started as `api`, `worker`
  or `migrate` like today's Python image; the worker is a Spring profile without the web layer.
- **PostgreSQL** through Spring's `JdbcClient` with explicit SQL. The run queue already relies on
  `SELECT … FOR UPDATE SKIP LOCKED` and leases, which read best as plain SQL. **Flyway** takes over
  from Alembic at the current schema revision.
- **Model and engine without Spring.** `archsim-model` holds the design document's records, the
  upgrades from schema 1.0 to 1.7 and validation, so the app, CLI and MCP server can read and
  check a design without the simulator. `archsim-engine` holds the rest: the kernel, components,
  analysis, sizing, cost, pricing, calibration, importers and export.
- **Event-driven simulation kernel.** It's a single-threaded priority queue of timed events,
  ordered like SimPy: time, then priority, then insertion order. Each component is a state
  machine whose steps are named after the Python blocks they replace; random streams are seeded
  per component, and component types sit behind a sealed interface. It doesn't use virtual
  threads as coroutines: an event costs roughly 0.1–0.2 µs against 1–2 µs for a thread
  hand-off, the same seed gives the same results, and it's easier to debug and profile.
- **Quality gates in CI:**
  - formatting: Spotless with palantir-java-format;
  - null safety: Error Prone with NullAway and JSpecify annotations;
  - layering: ArchUnit rules (controllers never use repositories directly; the model and the
    engine never import Spring or JDBC; the model never imports the engine);
  - tests: JUnit and AssertJ, jqwik for engine invariants, Testcontainers for PostgreSQL. The
    build uses JUnit 6: the same Jupiter API as JUnit 5, and the version Spring Boot 4 manages;
  - coverage: JaCoCo;
  - the web contract: an OpenAPI document generated by springdoc, checked against the web
    app's `api.ts`.
- **Packaging:** one Docker image of `archsim-app`, built with Jib. `docker compose up` works as
  it does today.

## Code layout

One Gradle build at the repository root, with the standard multi-project layout: one top-level
folder per module. It sits beside the Python code, whose folders go stage by stage; `apps/web`
doesn't change.

| Path | What |
| --- | --- |
| `settings.gradle.kts`, `gradlew`, `gradle/wrapper/` | the build and its wrapper (Gradle 9.8) |
| `gradle/libs.versions.toml` | every version, in one place |
| `gradle/gradle-daemon-jvm.properties` | Gradle runs on JDK 25 whatever `JAVA_HOME` says, and downloads one if none is installed |
| `build-logic/` | the convention plugin every module applies: Java 25, Spotless, Error Prone with NullAway, JUnit, JaCoCo |
| `archsim-model/`, `archsim-engine/`, `archsim-app/`, `archsim-cli/`, `archsim-mcp/` | one module per box above, each added by the stage that needs it. Maven's standard directories inside: `src/main/java/io/archsim/<module>/` (in the app, `io.archsim.app.<feature>`), `src/main/resources`, `src/test/java` |
| `archsim-api-tests/` | a test module: the black-box API tests (stage 0) |

## Stages

### Stage 0 · Foundations (3–5 days)
- **Build and CI:** the Gradle build and a Java CI job.
- **Golden files:** a Python exporter records the inputs and outputs of every deterministic engine
  function across the 25 templates and their 124 scenarios.
- **Black-box API tests:** the 4.1k lines of API tests become HTTP tests that run against any
  base URL, so the same suite checks both the Python and the Java API.
- **Exit:** the Java CI is green, and the black-box suite passes against today's Python API.

### Stage 1 · Design model and schema (3–5 days)
- **Model:** `archsim-model`: Java records for the design document, with upgrades from schema 1.0
  to 1.7.
- **Validation:** messages match Python's word for word, because the web app shows them.
- **JSON Schema:** generated from the records and compared with today's.
- **Exit:** all 25 examples and every test fixture read and write back identically, and every
  invalid fixture gives the same errors.

### Stage 2 · The API on Spring Boot, engine still Python (1–2 weeks)
- **Endpoints:** `archsim-app` serves all 69 of them. Same JSON, cookies, CSRF, rate limits and
  outbound block, and tokens keep working because the hash format is unchanged.
- **Simulation runs:** they keep executing in Python workers through the shared PostgreSQL queue,
  which is already the seam between the API and the workers.
- **Synchronous engine calls:** validate, estimate, review, export, import, calibrate and pricing
  go to a small internal Python engine service inside compose until stage 3 replaces each one.
- **Exit:**
  - the black-box suite and the Playwright end-to-end tests pass against the Java API;
  - one database works with both APIs;
  - compose switches to Java, and the FastAPI code is deleted.

### Stage 3 · The engine's deterministic parts (1–2 weeks)
- **What it covers:** in `archsim-engine`, estimate, sizing, cost, pricing, design review and
  findings, confidence, explain, compare, exports, importers and calibration fits.
- **Parity bar:** the golden files from stage 0. Text must match exactly, and numbers to a relative
  1e-9.
- **Exit:** each part moves from the Python engine service to Java when it passes. When the last
  one moves, the service is gone.

### Stage 4 · The simulation kernel and components (2–4 weeks, including parity runs)
- **What it covers:**
  - the kernel and every component, including database locks and group commit, cache, Kafka
    fetch cycles, load balancer, Kubernetes, shared-host contention and external APIs;
  - the run runtime, sweeps, sampled mode and multi-seed ranges.
- **Parity bar.** A port can't reproduce Python's results bit for bit: tiny floating-point
  differences change the order of events, so result digests will differ. The bar is statistical:
  - **Every template scenario:** with 20 seeds each, Java's median p50, p95, throughput and
    utilisation fall inside Python's seed range and within 2% of Python's median.
  - **Every kept benchmark calibration:** the re-simulated knee is within 2% of Python's.
  - **Internal counters** such as commits per flush and lock wait match per component on fixed
    inputs, so a semantic slip can't hide inside the tolerance.
- **Speed:** measure against `docs/performance.md`. A Java kernel should be several times faster,
  but that's an estimate until it's measured.
- **Exit:**
  - the parity report is published in `docs/validation/java-parity.md`;
  - the Java engine ships as engine 1.0.0;
  - /accuracy shows its in-sample results, labelled re-simulated;
  - the harness calls the Java CLI, and the Python engine is deleted.

### Stage 5 · CLI, MCP server and GitHub Action (3–5 days)
- **What it covers:** `archsim` commands with the same flags and output, and the MCP tools with the
  same names and schemas.
- **The `archsim-check` action** runs the CLI from a container image. A GraalVM native binary is
  optional, for faster start-up.

### Stage 6 · Clean-up (2–3 days)
- **Remove Python:** everything except the benchmark harness.
- **Docs:** CONTRIBUTING, the README and `IMPLEMENTATION_STATUS.md` rewritten for the Java build.

### Stage 7 · The benchmark harness (after the port)
- **What it covers:** `benchmarks/run.py`, `report.py` and their tests, rewritten in Java once
  stages 0–6 are done.
- **Parity bar:** the committed results under `benchmarks/results` reduce to the same numbers
  (medians, knees, spreads, digests of the reduced summaries), because past and future runs must
  stay comparable.
- **Exit:** the harness runs from Gradle, and the last Python code is deleted.

## Accuracy work during the port

Decided order: the Python engine is frozen at 0.16.0, and the held-out campaign runs on it
**before stage 4 starts**. Accuracy and model work stop until the port is complete.

- **Why first:** the held-out results then describe the engine customers use today. The Java engine
  is checked against the same measurements afterwards. Because the port changes no behaviour, that
  check is fair, but it's labelled re-simulated, never prospective.
- **What waits:** stages 0–3 don't touch the simulation, so they can run alongside the accuracy work.

## Risks

- **Hidden simulation differences.** A statistical bar can hide a small semantic slip. The
  per-component counter checks and invariant tests are there for that.
- **Two stacks for a while.** Python and Java side by side add complexity, so each stage should be
  short, and Python goes as soon as a stage passes.
- **Sessions.** Signed-in users may have to sign in once more after stage 2. API tokens keep working.
- **No SQLite mode.** Local development uses PostgreSQL in Docker or Testcontainers.
- **Effort.** The durations are rough estimates at this project's pace (169 commits in nine
  days). Parity runs need the laptop overnight, like the benchmark campaigns.

## Decisions

The owner's answers, 2 Nov 2026:

1. **Build tool:** Gradle with Kotlin DSL and a version catalog.
2. **Framework:** Spring Boot 4.
3. **Data access:** Spring's `JdbcClient` with explicit SQL, and Flyway for migrations.
4. **Base package:** `io.archsim` (for example `io.archsim.model`, `io.archsim.app.projects`).
5. **Benchmark harness:** it stays Python during the port and calls the Java CLI from stage 4.
   Once the migration is complete, it's ported to Java as well (stage 7).
6. **SQLite development mode:** dropped. Local development and tests use PostgreSQL, in Docker or
   Testcontainers.
7. **Order:** the held-out campaign runs on the frozen Python engine 0.16.0 before stage 4
   starts. Stages 0–3 go ahead in the meantime. The campaign itself needs the owner's go-ahead.

Five more, the same day:

8. **Repository layout:** one Gradle build at the repository root, with one top-level folder per
   module: `archsim-model`, `archsim-engine`, `archsim-app`, `archsim-cli` and `archsim-mcp`, plus
   test modules such as `archsim-api-tests`. `apps/web` doesn't change, and the Python folders go
   stage by stage.
9. **The app is organised by feature**, not in hexagonal layers. Each feature has one package
   (`io.archsim.app.projects`, `.runs`, `.auth`, `.tokens`, `.shared`...), which holds its
   controller, service and repository classes. An ArchUnit rule stops controllers from using
   repositories directly.
10. **Model and engine are separate modules.** `archsim-model` holds the design document's
    records, the upgrades from schema 1.0 to 1.7 and validation. `archsim-engine` holds the
    kernel, components, analysis, sizing, cost, pricing, calibration, importers and export. The
    app, CLI and MCP server can use the model without the simulator.
11. **The simulation kernel is event-driven:** a single-threaded priority queue of timed events,
    ordered like SimPy (time, then priority, then insertion order). Components are state machines,
    and each step is named after the Python block it replaces. Not virtual-thread coroutines,
    for three reasons:
    - **speed:** roughly 0.1–0.2 µs per event, against 1–2 µs per thread hand-off;
    - **repeatability:** the same seed gives the same results;
    - **tooling:** debugging and profiling are simpler.
12. **One deployable:** a single Spring Boot app, `archsim-app`, replacing the separate
    `archsim-api` and `archsim-worker` first proposed. It builds one image, started as `api`,
    `worker` or `migrate`, as today's compose runs the Python image. The worker skips the web
    layer through a Spring profile.

**Rough total:** 6–10 weeks of sessions for stages 0–6, plus overnight parity runs, then the
harness port.
