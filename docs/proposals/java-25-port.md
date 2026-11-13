# Proposal: ArchSim on Java 25, ported in stages

**Status:** approved by the owner on 2 Nov 2026, with the [decisions](#decisions) below. Stage 0
(foundations) is built; its exit check is still open (see [Stage 0](#stage-0--foundations-35-days)).
Stage 1 (the design model) is built and its exit check passes (see
[Stage 1](#stage-1--design-model-and-schema-35-days)). Stage 2 (the API on Spring Boot) is built
and its exit check passes (see
[Stage 2](#stage-2--the-api-on-spring-boot-engine-still-python-12-weeks)). Stages 3 to 6 are
built, and the port ends there; [where the port ended](#where-the-port-ended) sums it up.
Stage 7, the benchmark harness, follows it.

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
   worker: a Spring profile without the web layer; claims runs from the queue (advisory lock)

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

**The stack** (each choice with a real alternative has an ADR; see [Decisions](#decisions)):

- **Java 25 (LTS)**: records, sealed interfaces for component types, pattern-matching `switch`,
  virtual threads for request handling.
- **Gradle 9 with Kotlin DSL**, a version catalog, and one Gradle module per box above, each
  added by the stage that first needs it.
- **Spring Boot 4** (Spring Framework 7, Jackson 3), Spring Security's OAuth2 client for GitHub
  and Google sign-in, CSRF protection and hashed API tokens, as today (ADR-0037 did sign-in in
  plain Spring MVC instead, to keep the shared sessions and the HTTP contract, until ADR-0065
  moved it to Spring Security after the port). **One app, `archsim-app`**, organised by feature:
  one package per feature, holding that feature's controller, service and repository classes.
  It builds one image, started as `api`, `worker` or `migrate` like today's Python image; the
  worker is a Spring profile without the web layer.
- **PostgreSQL** through Spring's `JdbcClient` with explicit SQL. The run queue's claim (an
  advisory lock and a `row_number` pick) and its leases read best as plain SQL. **Flyway** takes
  over from Alembic at the current schema revision.
- **Model and engine without Spring.** `archsim-model` holds the design document's records, the
  upgrades from schema 1.0 to 1.7 and validation, so the app, CLI and MCP server can read and
  check a design without the simulator. `archsim-engine` holds the rest: the kernel, components,
  analysis, sizing, cost, pricing, calibration, importers and export.
- **Event-driven simulation kernel.** It's a single-threaded priority queue of timed events,
  ordered like SimPy: time, then priority, then insertion order. Each component is a state
  machine whose steps are named after the Python blocks they replace; random streams are seeded
  per component, and component types sit behind a sealed interface. Why not virtual threads:
  [ADR-0013](../adr/0013-event-driven-simulation-kernel.md).
- **Quality gates in CI:**
  - formatting and style: Spring's formatter (spring-javaformat) and Checkstyle with clean-code
    rules ([ADR-0030](../adr/0030-spring-style-and-clean-code-checks.md),
    [Java conventions](../java-conventions.md));
  - null safety: Error Prone with NullAway and JSpecify annotations;
  - layering: ArchUnit rules (controllers never use repositories directly; the model and the
    engine never import Spring or JDBC; the model never imports the engine);
  - tests: JUnit and AssertJ, jqwik for engine invariants, and an embedded PostgreSQL for the
    database tests, with no Docker ([ADR-0033](../adr/0033-embedded-postgresql-for-tests.md)). The
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
| `build-logic/` | the convention plugin every module applies: Java 25, spring-javaformat, Checkstyle, Error Prone with NullAway, JUnit, JaCoCo |
| `archsim-model/`, `archsim-engine/`, `archsim-app/`, `archsim-cli/`, `archsim-mcp/` | one module per box above, each added by the stage that needs it. Maven's standard directories inside: `src/main/java/io/archsim/<module>/` (in the app, `io.archsim.app.<feature>`), `src/main/resources`, `src/test/java` |
| `archsim-api-tests/` | a test module: the black-box API tests (stage 0) |
| `parity/golden/` | the frozen Python engine's golden files, the reference for stages 1 and 3 ([`parity/README.md`](../../parity/README.md)) |

## Stages

### Stage 0 · Foundations (3–5 days)
- **Build and CI:** the Gradle build and a Java CI job.
- **Golden files:** a Python exporter records the inputs and outputs of every deterministic engine
  function across the 25 templates and their 124 scenarios.
- **Black-box API tests:** the 4.1k lines of API tests become HTTP tests that run against any
  base URL, so the same suite checks both the Python and the Java API.
- **Exit:** the Java CI is green, and the black-box suite passes against today's Python API.
- **Built (2 Nov 2026):**
  - the Gradle build (see [Code layout](#code-layout)) and the CI job `java`;
  - golden files in [`parity/golden/`](../../parity/README.md), checked against the engine by
    the Python CI job;
  - the black-box suite in [`archsim-api-tests/`](../../archsim-api-tests/README.md): 120 of
    the 162 Python API tests, with the other 42 listed there. It needs PostgreSQL and starts the
    Python API itself (`-Parchsim.api.start=python`).
- **Still to run before the exit is met:**
  - the whole black-box suite against the Python API; Docker was stopped while it ran, so 36
    of the tests have not run yet;
  - the golden-file check on Linux (the files were written on Windows);
  - the CI job on GitHub (a Linux container run of its steps passed).

### Stage 1 · Design model and schema (3–5 days)
- **Model:** `archsim-model`: Java records for the design document, with upgrades from schema 1.0
  to 1.7.
- **Validation:** messages match Python's word for word, because the web app shows them.
- **JSON Schema:** generated from the records and compared with today's.
- **Exit:** all 25 examples and every test fixture read and write back identically, and every
  invalid fixture gives the same errors.
- **Built (2 Nov 2026):** [`archsim-model/`](../../archsim-model/README.md): the records, the
  upgrades, reading and writing, the JSON Schema and validation, with the component types'
  specifications and the price-snapshot lookup validation needs
  ([ADR-0026](../adr/0026-component-specs-and-price-snapshots-in-the-model.md)). Every golden
  entry of the functions it ports passes: loading, upgrading, validating and normalizing every
  template, scenario and fixture, applying overrides, the JSON Schema, the distributions and the
  price snapshots. The exporter became a package, `scripts/golden/`.
- **Left for later stages:** the component catalog the editor renders (stage 2); pinning prices
  and sizing "auto" parts when a scenario is resolved (stage 3).

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
- **Built (3 Nov 2026):** [`archsim-app/`](../../archsim-app/README.md), in six slices. The whole
  black-box suite (132 tests) and the Playwright end-to-end tests pass against it. Flyway's `V1`
  builds Alembic's schema and takes over a database Alembic made
  ([ADR-0032](../adr/0032-flyway-takes-over-from-alembic.md)). Compose runs it from an image
  Jib builds, with the engine service
  ([ADR-0036](../adr/0036-python-engine-service-for-synchronous-calls.md)) and the Python
  workers. The FastAPI app is deleted; `services/api` keeps the workers until stage 4. Its
  differences from the Python API, and the Python bugs it does not copy, are in its README.

### Stage 3 · The engine's deterministic parts (1–2 weeks)
- **What it covers:** in `archsim-engine`, estimate, sizing, cost, pricing, design review and
  findings, confidence, explain, compare, exports, importers and calibration fits.
- **Parity bar:** the golden files from stage 0. Text must match exactly, and numbers to a relative
  1e-9.
- **Exit:** each part moves from the Python engine service to Java when it passes. When the last
  one moves, the service keeps only the simulation a calibration replays, an interim
  `POST /simulate` within the replay's limits, until stage 4's exit removes it and the service
  with it ([ADR-0045](../adr/0045-replay-calibrations-through-an-interim-simulate.md)).
- **Built (7 Nov 2026):** [`archsim-engine/`](../../archsim-engine/README.md), on the model's
  records ([ADR-0038](../adr/0038-engine-computes-on-typed-records.md)), in slices that each moved
  calls off the engine service.
  - All 117 functions the golden files record are ported to Java (archsim-model and
    archsim-engine), and their 6,018 entries match, with one listed departure: a YAML library's
    own words for text that is not YAML.
  - The API answers from Java what the service answered: validating a scenario, `POST /analyze`,
    preparing a run, `GET /compare`, seed-group summaries, `POST /export`, `POST /import` and
    `POST /calibrate`, with the engine's versions in `GET /meta`. Departures from the Python
    engine are listed in the engine's README, and from the Python API in the app's.
  - Calibrations call Prometheus from the Java API, under its outbound policy, pinned to the
    address they check ([ADR-0046](../adr/0046-call-prometheus-with-apache-httpclient-5.md)).
  - What stays: the engine service with `POST /simulate` alone (ADR-0045). Compose, the
    black-box suite, CI and the end-to-end launcher still start it, for calibrations' replays.
  - The assumptions page and the `archsim check` gate are ported as libraries, for stage 5's
    commands.

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
  - **Replays, on top (decided during the stage):** the Java simulator draws Python's random
    numbers and keeps SimPy's order of events
    ([ADR-0047](../adr/0047-draw-python-random-streams-in-java.md),
    [ADR-0048](../adr/0048-keep-simpys-order-of-events.md)), so it replays the frozen engine's
    runs number by number, counters included, and the statistical bar stays as above
    ([ADR-0049](../adr/0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)).
    A Java report repeats itself on every platform; its digests keep their definitions and
    name a result within one engine
    ([ADR-0050](../adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)). The
    harness runs the Java engine through `archsim run`, the first command of stage 5's CLI
    ([ADR-0051](../adr/0051-start-the-java-cli-in-stage-4-with-archsim-run.md)).
- **Speed:** measure against `docs/performance.md`. A Java kernel should be several times faster,
  but that's an estimate until it's measured.
- **Exit:**
  - the parity report is published in `docs/validation/java-parity.md`;
  - the Java engine ships as engine 1.0.0;
  - /accuracy shows its in-sample results, labelled re-simulated;
  - calibrations replay on the Java simulator, and `/simulate` and the engine service are
    deleted ([ADR-0045](../adr/0045-replay-calibrations-through-an-interim-simulate.md)).

  The harness's move to the Java CLI and the Python engine's deletion were in this exit until
  8 Nov 2026; they are stage 5's now (below), because the MCP server and the `archsim check`
  action import the Python engine until stage 5 ports them.
- **Built (10 Nov 2026):** [`archsim-engine/`](../../archsim-engine/README.md)'s simulator, in
  slices that each replayed the frozen engine. Every item of the exit is met.
  - The parity report is published ([java-parity.md](../validation/java-parity.md)): all 149
    template cases meet the bar, every one of their 2,980 runs replays the frozen engine figure
    for figure with its digests, and all 65 kept calibrations' knees are the frozen engine's.
    The Java simulator runs the templates about 14 times faster
    ([performance.md](../performance.md)).
  - The Java worker simulates every run
    ([ADR-0053](../adr/0053-simulate-runs-in-the-java-apps-worker-role.md)): compose, the
    black-box suite (132 tests) and the end-to-end tests run on it. A calibration's replay runs
    on the Java simulator in the API's process, on platform threads of its own
    ([ADR-0054](../adr/0054-replay-calibrations-on-a-pool-of-platform-threads.md)). The Python
    worker, `/simulate` and the engine service are deleted; `services/api` keeps the Alembic
    migrations, `migrate` and `seed` until stage 6.
  - The Java engine is engine 1.0.0: new runs, `GET /meta`, reports and `archsim --version`
    name it, and runs stored before keep 0.16.0. `benchmarks/engines.json` records it, not
    frozen yet: its source trees are pinned when it is frozen, before the next held-out
    campaign.
  - /accuracy and `docs/validation.md` say in one sentence, labelled re-simulated and in-sample
    and never in the headline, that 1.0.0 re-simulates every kept calibration with exactly
    0.16.0's knees (65 of 65), so the latest predictions they show are 0.16.0's, which 1.0.0
    reproduces. Per-experiment figures would repeat 0.16.0's numbers beside themselves.
  - The harness simulates on the Java engine through `archsim run` when asked
    (`BENCH_ENGINE=java`), and names its checks by the engine's version
    ([ADR-0051](../adr/0051-start-the-java-cli-in-stage-4-with-archsim-run.md)).

### Stage 5 · CLI, MCP server and GitHub Action (3–5 days)
- **What it covers:** `archsim` commands with the same flags and output, and the MCP tools with the
  same names and schemas.
- **The `archsim-check` action** runs the CLI from a container image. A GraalVM native binary is
  optional, for faster start-up.
- **Exit** (from stage 4's, 8 Nov 2026): the harness calls the Java CLI, and the Python engine is
  deleted, with the scripts that recorded its oracles (their data stays). Before it goes, the
  price snapshots move into `archsim-model`'s resources and the test fixtures out of
  `packages/engine`, and the harness takes the fits, the machine speed and the versions it
  imports from the Python engine from the Java CLI
  ([ADR-0015](../adr/0015-benchmark-harness-stays-python-until-port-completes.md)).
- **Built (12 Nov 2026):** [`archsim-cli/`](../../archsim-cli/README.md) and
  [`archsim-mcp/`](../../archsim-mcp/README.md), then the exit, in slices S5-1 to S5-10. Every
  item of the exit is met.
  - The CLI has every command of the Python CLI, with its flags, lines, exit codes and JSON
    values, checked against 307 cases recorded from it
    ([ADR-0055](../adr/0055-the-java-clis-output-contract.md),
    [ADR-0056](../adr/0056-the-java-cli-takes-options-by-their-full-names.md)); where Python
    crashed, the Java answers in one line, as its README lists. `calibrate prometheus --url`
    calls the app's Prometheus client, moved into the engine
    ([ADR-0057](../adr/0057-move-the-prometheus-client-into-the-engine.md)), and the CLI and the
    MCP server read files as Python did
    ([ADR-0059](../adr/0059-read-files-as-python-did-in-the-engine.md)).
  - The MCP server serves the Python server's tools, resources and instructions word for word,
    on the official MCP Java SDK, checked against a recorded session of 55 requests
    ([ADR-0058](../adr/0058-serve-mcp-with-the-official-mcp-java-sdk.md)).
  - The `archsim-check` action runs the CLI in an image each job builds from the repository and
    never pushes; it needs a Linux runner with Docker
    ([ADR-0060](../adr/0060-run-the-check-action-in-an-image-the-job-builds.md)). No native
    binary was built.
  - The price snapshots are `archsim-model`'s resources, and the test fixtures are in
    `parity/fixtures/`.
  - The harness takes its fits, machine speeds, means and the report's version from one
    long-lived Java process, `archsim functions`
    ([ADR-0061](../adr/0061-answer-the-harnesss-engine-calls-from-one-java-process.md)), and
    simulates on the Java engine only
    ([ADR-0062](../adr/0062-make-the-java-engine-the-harnesss-default.md)).
  - What else needed the Python engine moved to Java: the sizing audit and the checks against
    queueing theory (M/M/c, Little's law, the starter's reference ranges, the estimate against
    the simulation) are tests of `archsim-engine`, `archsim-app`'s `seed` role seeds the
    examples, and `./gradlew :archsim-engine:generate` writes the web app's engine fixtures and
    `docs/assumptions.md`, which a test holds to the Java engine's output.
  - The Python engine (`packages/engine`) and the Python MCP server (`packages/mcp`) are
    deleted, with the scripts that recorded the engine's oracles and the scripts only it ran; the
    tag `engine-0.16.0` keeps the engine as it was frozen, and git history the scripts. What they
    recorded stays as the Java tests' data and is not recorded again: the golden files, the
    simulation baselines and the recorded cases, beside the fixtures they were made from
    ([`parity/README.md`](../../parity/README.md)).

### Stage 6 · Clean-up (2–3 days)
- **Remove Python:** everything except the benchmark harness.
- **Docs:** CONTRIBUTING, the README and `IMPLEMENTATION_STATUS.md` rewritten for the Java build.
- **Sign-in:** weigh moving it to Spring Security, now that the API contract can change. Stage 2
  signed people in with plain Spring MVC to keep FastAPI's contract
  ([ADR-0037](../adr/0037-sign-in-without-spring-security.md));
  [ADR-0065](../adr/0065-move-sign-in-to-spring-security-after-the-port.md) moved it after the
  port.
- **Built (12 Nov 2026):** in slices S6-1 to S6-8. Every item is met.
  - **Python removed** from what ArchSim ships and needs to run. `services/api`, the Alembic
    migrations Flyway took over from, is deleted with `scripts/schema_snapshot.py`; a database
    below Alembic's revision 0008 is brought there from the tag `alembic-0008` first
    ([ADR-0063](../adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)),
    and the schema snapshot is data recorded once. The end-to-end launcher makes its database
    with the PostgreSQL driver in the API's jar, and the action posts its comment with
    `github-script`. Python stays for the benchmark harness and the repository's scripts until
    stage 7
    ([ADR-0064](../adr/0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md)):
    uv's project keeps no dependency but the development tools.
  - **Docs:** the README, CONTRIBUTING (its "Changing simulation semantics" included),
    `IMPLEMENTATION_STATUS.md`, `docs/extending-components.md` and the docs that described the
    Python engine as the product describe the Java build.
  - **Sign-in:** [ADR-0065](../adr/0065-move-sign-in-to-spring-security-after-the-port.md)
    moves it to Spring Security after the port, keeping its HTTP contract but for the changes
    it records, in six test-first slices, all built (12 Nov 2026).
  - Also: `NOTICE` lists the third-party software the images and the CLI bundle, none of it
    incompatible with the AGPL-3.0 ArchSim is licensed under
    ([ADR-0067](../adr/0067-license-under-agpl-3.0-with-a-commercial-license.md)), and the web image leaves out sharp and its libvips
    ([ADR-0066](../adr/0066-ship-the-web-image-without-an-image-optimizer.md)); stage 5's
    follow-ups are closed; and two gaps in the contract found in review are closed. The API ignores
    a request body's unknown keys, as FastAPI's request models did, and a number's text of any
    length is read as Python's `float()` and pydantic read it, where a few thousand digits or groups
    of them had overflowed the stack.

### Stage 7 · The benchmark harness (after the port)
- **What it covers:** `benchmarks/run.py`, `report.py` and their tests, rewritten in Java once
  stages 0–6 are done.
- **Parity bar:** the committed results under `benchmarks/results` reduce to the same numbers
  (medians, knees, spreads, digests of the reduced summaries), because past and future runs must
  stay comparable.
- **Exit:** the harness runs from Gradle, and the last Python code is deleted.
- **Also:** the repository's tooling in `scripts/` moves to Java or goes
  ([ADR-0064](../adr/0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md)).
- **Decisions** (proposed, 13 Nov 2026): the harness is a module, `archsim-benchmarks`, calling
  the engine in process, with its data left in `benchmarks/`
  ([ADR-0068](../adr/0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md));
  it runs as `./gradlew :archsim-benchmarks:run --args="<command>"`, with `run.py`'s command
  names ([ADR-0069](../adr/0069-run-the-harness-from-gradle-with-picocli-commands.md)); it writes
  every file as the Python harness did, byte for byte, charts included
  ([ADR-0070](../adr/0070-write-the-harnesss-files-as-python-wrote-them.md)), with Python's
  statistics ported exactly ([ADR-0071](../adr/0071-python-statistics-in-the-harness.md)); the
  tooling moves to a module `archsim-tools`, and the recorders of CPython's behaviour retire
  ([ADR-0072](../adr/0072-move-the-repositorys-tooling-to-java-and-retire-the-cpython-recorders.md)).
- **Slices**, each test first (its tests ported from the Python ones), each commit that touches
  Java, the build or CI reviewed before it lands:
  - **S7-1 · The module and the reduction of a run:** `archsim-benchmarks`, the experiments,
    Python's statistics, the results' JSON, and `compare`. Parity: every kept run's
    `comparison.json` recomputed byte for byte.
  - **S7-2 · The reports:** `report [--json] [--dev]`: the validation page's blocks, the charts,
    both `accuracy.json`, `dev-checks.md` and `knee-range.md`. Parity: all regenerated from the
    committed results, byte for byte but for the command names
    ([ADR-0070](../adr/0070-write-the-harnesss-files-as-python-wrote-them.md)).
  - **S7-3 · The Java engine's parity page:** `java-parity [--check]`, from
    `scripts/java_parity_report.py`; CI's check becomes the module's test.
  - **S7-4 · Model inputs, designs and simulation in process:** the calibration's inputs, the
    design, the knee search, `resimulate` and `resources`. Parity: re-simulating every kept
    calibration gives the Java engine's recorded checks (`results/dev/0.16.0-java/`).
  - **S7-5 · The knee's range:** the sensitivity, the variation fit and `range-fit`. Parity:
    `benchmarks/knee-range.json` reproduced from the same checks.
  - **S7-6 · Docker, k6 and a campaign:** stacks, cpusets, cgroup readings, k6, the calibration
    staircase, measurement, the held-out seals and `smoke`; the seal fix below. Proof: one smoke
    run and one experiment on Docker.
  - **S7-7 · The machine checks:** `envcheck` and `contention` (host counters, probes).
  - **S7-8 · The tooling:** `archsim-tools` (commit messages, CI's changes, the price refresh);
    the hook and CI on Java; the CPython recorders retire.
  - **S7-9 · Python removed:** `benchmarks/*.py` and their tests, `scripts/`, `pyproject.toml`,
    `uv.lock`, `.python-version`, CI's Python job, the Makefile's and the workflows' uv steps,
    `archsim functions` in the CLI; the docs.
- **The prediction seal:** a run's `predictionsSha256` seals `predictions.json` before the first
  measured step. Of the 53 sealed runs, 27 match their file and 24 were edited by the 2 Nov 2026
  history rewrite (commit IDs and the harness's hash), their originals matching. The other two,
  cache-aside's runs 4 and 5, show a harness bug: a ladder that still met the objective at its
  top was extended, and each extension step was saved into the sealed file after the seal, so
  the file no longer matches it. S7-6 fixes it in the Java harness, test first.

## Where the port ended

- **In Java:** the design model, the engine (1.0.0), the API with its worker, seed and migrate
  roles, the CLI, the MCP server and the `archsim-check` action's CLI. The web app stays Next.js
  and talks to the Java API unchanged.
- **Kept:** the design format (schema 1.7), the HTTP API's contract
  ([ADR-0034](../adr/0034-keep-the-fastapi-http-contract-in-spring-mvc.md)), the CLI's output
  ([ADR-0055](../adr/0055-the-java-clis-output-contract.md)) and the MCP tools. The Java
  engine's tests replay the runs the frozen Python engine 0.16.0 recorded, figure for figure,
  and match its golden files ([`parity/README.md`](../../parity/README.md)).
- **Departures, each listed:** the API's in
  [archsim-app's README](../../archsim-app/README.md#differences-from-the-python-api), the
  CLI's in [archsim-cli's README](../../archsim-cli/README.md), and the Unicode version (Java
  25's 16.0 against Python 3.12's 15.0) in `IMPLEMENTATION_STATUS.md`'s known limitations.
- **Still Python:** the benchmark harness (`benchmarks/`, stage 7) and the repository's scripts
  (`scripts/`); the benchmarked stacks under `benchmarks/service-*` are what the harness measures,
  not part of ArchSim.
- **Tags:** `engine-0.16.0` keeps the frozen engine, its MCP server and the Python API's tests;
  `alembic-0008` keeps the Alembic migrations, `archsim-api migrate` and the schema-snapshot
  script.
- **Not done:** stage 0's exit was never run in full against the Python API, which is gone; the
  black-box suite passes in full against the Java API (137 tests). CI has not run on GitHub
  since Actions stopped starting jobs on 5 Nov 2026; every CI command runs locally.
- **Decided by the owner on 12 Nov 2026, before anything is published:** ADR-0065's slices are
  built first, and `ARCHSIM_DATABASE_URL` takes a plain `postgresql://` URL, the form the
  documentation now shows, besides SQLAlchemy's and JDBC's
  ([ADR-0035](../adr/0035-read-the-python-apis-settings-unchanged.md)'s note of that day).

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
- **No SQLite mode.** Local development uses PostgreSQL in Docker, and the tests an embedded
  PostgreSQL ([ADR-0033](../adr/0033-embedded-postgresql-for-tests.md)).
- **Effort.** The durations are rough estimates at this project's pace (169 commits in nine
  days). Parity runs need the laptop overnight, like the benchmark campaigns.

## Decisions

The owner decided these on 2 Nov 2026. Each has an Architecture Decision Record in
[`docs/adr/`](../adr/README.md), which gives its reasons, the options weighed and what follows;
the port itself is [ADR-0005](../adr/0005-port-to-java-25-in-stages.md).

| # | Decision | ADR |
| --- | --- | --- |
| 1 | Build with Gradle, the Kotlin DSL and a version catalog | [0006](../adr/0006-build-with-gradle-kotlin-dsl.md) |
| 2 | Spring Boot 4 for the application | [0007](../adr/0007-spring-boot-4.md) |
| 3 | PostgreSQL through `JdbcClient` with explicit SQL; Flyway migrations | [0008](../adr/0008-jdbcclient-and-flyway.md) |
| 4 | Base package `io.archsim` | [0009](../adr/0009-base-package-io-archsim.md) |
| 5 | The benchmark harness stays Python during the port, then moves to Java (stage 7) | [0015](../adr/0015-benchmark-harness-stays-python-until-port-completes.md) |
| 6 | No SQLite development mode: PostgreSQL only | [0016](../adr/0016-drop-sqlite-development-mode.md) |
| 7 | The Python engine is frozen at 0.16.0; the held-out campaign runs before stage 4 | [0017](../adr/0017-freeze-python-engine-during-port.md) |
| 8 | One Gradle build at the repository root, one folder per module | [0010](../adr/0010-gradle-build-at-repository-root.md) |
| 9 | The app's code is organised by feature | [0011](../adr/0011-organise-application-code-by-feature.md) |
| 10 | The design model (`archsim-model`) is a module apart from the engine | [0012](../adr/0012-separate-model-and-engine-modules.md) |
| 11 | An event-driven, single-threaded simulation kernel | [0013](../adr/0013-event-driven-simulation-kernel.md) |
| 12 | One deployable, `archsim-app`, started as `api`, `worker` or `migrate` | [0014](../adr/0014-one-deployable-application-with-roles.md) |

Stage 0 proposes three more, for the owner to accept:

| Decision | ADR |
| --- | --- |
| Black-box API tests in Java, run against any base URL | [0019](../adr/0019-black-box-api-tests-against-any-base-url.md) |
| The frozen engine's deterministic functions recorded as golden files | [0020](../adr/0020-golden-files-of-the-frozen-engine.md) |
| The Java build's conventions: one convention plugin, warnings as errors, JUnit 6 | [0021](../adr/0021-java-build-conventions.md) |

Stage 1 proposes eight more:

| Decision | ADR |
| --- | --- |
| Records and a pydantic-compatible codec over Jackson trees | [0022](../adr/0022-records-and-a-pydantic-compatible-codec.md) |
| Upgrades as a chain of steps on the JSON; only the current schema is modelled | [0023](../adr/0023-upgrade-chain-on-the-json.md) |
| Issues with stable codes and the Python engine's words | [0024](../adr/0024-validation-issues-with-stable-codes.md) |
| The JSON Schema generated from the records, as pydantic writes it | [0025](../adr/0025-json-schema-generated-from-the-records.md) |
| Component specifications and the price-snapshot lookup belong to the model | [0026](../adr/0026-component-specs-and-price-snapshots-in-the-model.md) |
| Python's text conventions for numbers and values in one package | [0027](../adr/0027-python-text-conventions-in-one-package.md) |
| The golden-file reader shared as Gradle test fixtures | [0028](../adr/0028-golden-file-reader-as-test-fixtures.md) |
| The model's packages in layers, checked by ArchUnit | [0029](../adr/0029-model-packages-in-layers-checked-by-archunit.md) |

The Java conventions and stage 2 added eight:

| Decision | ADR |
| --- | --- |
| Java in Spring Framework's style, with clean-code rules the build enforces | [0030](../adr/0030-spring-style-and-clean-code-checks.md) |
| The component catalog built from the specifications, its presentation as data | [0031](../adr/0031-component-catalog-facts-and-presentation.md) |
| Flyway takes over from Alembic at revision 0008 | [0032](../adr/0032-flyway-takes-over-from-alembic.md) |
| The database tests on an embedded PostgreSQL, without Docker | [0033](../adr/0033-embedded-postgresql-for-tests.md) |
| The FastAPI API's HTTP contract kept in Spring MVC | [0034](../adr/0034-keep-the-fastapi-http-contract-in-spring-mvc.md) |
| The Python API's `ARCHSIM_*` settings read unchanged | [0035](../adr/0035-read-the-python-apis-settings-unchanged.md) |
| The engine's synchronous calls in a small internal Python service | [0036](../adr/0036-python-engine-service-for-synchronous-calls.md) |
| Sign-in with plain Spring MVC, not Spring Security | [0037](../adr/0037-sign-in-without-spring-security.md), superseded by [0065](../adr/0065-move-sign-in-to-spring-security-after-the-port.md) |

Stage 3 added nine:

| Decision | ADR |
| --- | --- |
| The engine computes on the model's records and typed configs, not on JSON trees | [0038](../adr/0038-engine-computes-on-typed-records.md) |
| A run's report read as records of the parts the engine reads | [0039](../adr/0039-engine-reads-reports-as-partial-records.md) |
| A comparison copies the report parts it shows and diffs the inputs as stored | [0040](../adr/0040-comparison-copies-what-it-does-not-read.md) |
| The application's clock never tells the same microsecond twice | [0041](../adr/0041-application-clock-never-repeats-a-microsecond.md) |
| Exports read settings leniently and write their own YAML | [0042](../adr/0042-exports-read-settings-leniently-and-write-their-own-yaml.md) |
| Imported YAML read with SnakeYAML, resolved as PyYAML resolves it | [0043](../adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md) |
| Terraform JSON read with Jackson, as Python's `json` reads it | [0044](../adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md) |
| Calibrations replayed through an interim `/simulate` on the engine service | [0045](../adr/0045-replay-calibrations-through-an-interim-simulate.md) |
| Prometheus called with Apache HttpClient 5, pinned to the address it checks | [0046](../adr/0046-call-prometheus-with-apache-httpclient-5.md) |

**Rough total:** 6–10 weeks of sessions for stages 0–6, plus overnight parity runs, then the
harness port.
