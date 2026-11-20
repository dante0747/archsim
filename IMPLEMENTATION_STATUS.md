# Implementation status

State as of 2026-11-20 (engine 1.6.0, the Java engine: 1.0.0 reproduces the frozen engine 0.16.0
measured on the held-out experiments, 1.1.0 changes only how the cost counts autoscaled parts and
prices the peak, ADR-0074, 1.1.1 where a run's per-request rates at the base load are measured,
1.2.0 how a third-party API without a price is priced, ADR-0081, 1.2.1 how a cache's hit ratio
counts refused lookups, ADR-0082, 1.3.0 a database's busiest instance's CPU and ceiling, ADR-0083,
1.4.0 lost messages and a message's age, ADR-0084, 1.5.0 a Kafka broker's measured size and
idle consumers, ADR-0085, 1.5.1 the instant estimate's count of a commit's log flush as a
wait, not database CPU, ADR-0089, 1.5.2 its count of a cache's commands over its shards
with a read-through cache's fills, ADR-0092, and 1.6.0 a vCPU counted as a hyperthread unless
the design, its instance family or its platform says otherwise, ADR-0091, with the benchmark
harness fitting a service's falling CPU per request as a law of its rate, ADR-0093, and running
its designs on the laptop's fitted host machine, ADR-0095; schema 1.11, report
1.21; the API, the engine, the
CLI, the MCP server, the benchmark harness and the repository's tooling in Java, and no Python
left but the benchmarked stacks and three trial records). This file separates what is
implemented, how it was verified, what is known to be limited, and what comes next. Nothing
listed under "Implemented" is a stub or a mock.

## Implemented and covered by automated tests

**The benchmark harness and the repository's tooling in Java: stage 7 of the Java port
(2026-11-14).**

- The harness is `archsim-benchmarks`, which calls the engine in process
  ([ADR 0068](docs/adr/0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md)),
  runs as `./gradlew :archsim-benchmarks:run --args="<command>"` with `run.py`'s command names
  ([ADR 0069](docs/adr/0069-run-the-harness-from-gradle-with-picocli-commands.md)), and writes
  every file as the Python harness did, byte for byte
  ([ADR 0070](docs/adr/0070-write-the-harnesss-files-as-python-wrote-them.md)), with Python's
  statistics ported exactly ([ADR 0071](docs/adr/0071-python-statistics-in-the-harness.md)):
  - `compare`, the reports and the Java parity page (`9d67b6ae`, `98513c1a`, `9f92296a`);
  - the model inputs and the designs, simulated in process, `resimulate`, `resources` and the
    knee's range (`04f1ff9e`, `3f04edbb`, `10d9a6e9`);
  - `smoke`, then calibration, prediction and measurement on Docker, with the prediction seal's
    fix (`9d014ee5`, `44174af8`); the load curve's log form, the fitted host, `envcheck` and
    `contention` (`690c89a7`, `577d3aba`, `0b588837`);
  - the Python harness's last tests, ported before it was deleted (`6f54456e`).
- The tooling is `archsim-tools`
  ([ADR 0072](docs/adr/0072-move-the-repositorys-tooling-to-java-and-retire-the-cpython-recorders.md)):
  the commit messages' check, which the hook and CI's `commits` job run from its source
  (`62c68ba4`), CI's changed parts (`fe749d6d`) and the price refresh (`aa1633e4`, `1634ba9e`).
  The recorders of CPython's behaviour retired, and the archsim-check action's test is Java
  (`335ffbd7`); the tag `cpython-recorders` keeps them, with the Python harness and the scripts.
  The hook drops `JAVA_HOME`'s trailing separator, which Windows often adds, and its refusal of
  an older Java names the folder whole under dash too (`b0b9a599`); the tooling's tests rerun
  when CI's workflow or the check action changes (`79337fbe`).
- Python is removed (`004950cb`, a breaking change): `benchmarks/*.py` and their tests,
  `scripts/`, `pyproject.toml`, `uv.lock` and `.python-version`, CI's Python job, and the
  Makefile's and the workflows' uv steps. `archsim functions`, which only the Python harness
  called, left the CLI (`73fbedef`, a breaking change;
  [ADR 0073](docs/adr/0073-remove-archsim-functions-with-the-python-harness.md)). No Python is
  left but the benchmarked stacks (their services and `service-python`'s own test of its shared
  statistics, run by hand) and the three trial records.
- The exit, on 14 Nov 2026: `./gradlew build` passes (15,032 tests, 16 skipped: the
  archsim-check action's Linux-only tests and an engine test Windows cannot run); the parity task
  checks every kept run again, the 65 development checks and W27's checks on a host machine all
  as recorded (117 tests); a smoke run passes all nine experiments on Docker (238 s); and the web
  app's lint, build, type check and tests (334) pass.

**After the port: sign-in on Spring Security
([ADR 0065](docs/adr/0065-move-sign-in-to-spring-security-after-the-port.md)), a plain database
URL, and query values read whole (2026-11-12).**

- Spring Security's filter chain answers for who is asking, in six test-first slices; endpoints
  still say whom they need through their parameters, so public endpoints, unknown paths and body
  errors keep their order:
  - the chain, permitting every request, with the security headers and the request firewall in
    it (`dcffff86`);
  - API tokens through the resource server, an `OpaqueTokenIntrospector` over the tokens table,
    with the RFC 9728 metadata, which the web app also serves (`2a892420`, `b62c3012`);
  - browser sessions through a `SecurityContextRepository` over the sessions table (`57824a88`);
  - CSRF through Spring's filter: a random token in the `__Host-archsim_csrf` cookie, sent back
    in `X-ArchSim-CSRF` and read from that header only (`641c8851`, a breaking change;
    `760190c3`);
  - GitHub and Google sign-in through `oauth2Login`, PKCE for both, the request kept in the
    signed `__Host-archsim_oauth` cookie, Google checked through OpenID Connect (`c85c4dd4`);
  - ADR 0037 superseded, and the docs brought up to date (slice 6, `20214707`); once query values
    were read whole (below), the callback's own argument resolver and OpenAPI customizer went:
    the controller declares `code`, `state` and `error` as `@RequestParam`s, as `login` declares
    `next`.
- What changed for callers is in ADR 0065's dated notes and archsim-app's README: the CSRF
  token's format, and a session's change without its CSRF header refused `403` wherever it is
  sent, unknown paths and routes that do not take the method included; a wrong API token
  refused `401` wherever it is sent but on the two sign-in paths; the request firewall's `400`s;
  HSTS spelled as Spring spells it; Spring's sign-in state; and a callback's too long `state` or
  `code` landing on the sign-in page.
- `ARCHSIM_DATABASE_URL` takes a plain `postgresql://` URL, now the documented form
  (`d3cb0337`, a dated note on ADR 0035).
- A query parameter given once is read whole and as given, as FastAPI read a query value, where
  Spring split it at its commas, trimmed the pieces and made an empty one no value, and the last
  piece counted: an import named `Acme, Inc` was named `Inc`, a `limit` of `1,2` read as `2`,
  and without sign-in a long `next` before a comma passed its length check. An empty value now
  answers as FastAPI did: `?limit=` is refused `422` rather than read as not given (the default
  50), and `compare?a=` looks for a run with an empty id (`404`) rather than saying `a` is
  missing.

**The clean-up: stage 6 of the Java port (2026-11-12).**

- Python is gone from what ArchSim ships and needs to run; it stays only for the benchmark
  harness and the repository's scripts until stage 7
  ([ADR 0064](docs/adr/0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md)).
  - `services/api`, the Alembic migrations Flyway took over from, is deleted, with
    `scripts/schema_snapshot.py`, its CI job and its dependencies (`9544b47b`, a breaking change;
    [ADR 0063](docs/adr/0063-retire-alembic-older-databases-upgrade-from-a-tag.md)). A
    database below revision 0008 is refused with how to bring it there from the tag
    `alembic-0008`, a path run by hand from a database at 0007 to Flyway's takeover; the schema
    snapshot is data recorded once, and recording it again from the tag gives the same document.
  - The end-to-end launcher makes its database with the PostgreSQL driver in the API's jar
    (`dfed598b`); the `archsim-check` action posts its comment with `github-script` (`0c3baa8b`);
    the script that made the OTLP calibration fixture once is deleted (`732dda3f`).
- The API ignores a request body's unknown keys, as FastAPI's request models did, where it had
  refused them with `extra_forbidden`; a design document in a body still refuses its own
  (`3d577bf8`, a dated note on ADR 0034).
- Python's `float()` reads a figure of any length: `PythonNumbers` checks the text in one pass,
  where a regex recursed per digit and overflowed the stack at a few thousand digits, so an import
  whose `cpus` held 10,000 digits ended in a server error. It now answers as Python did, the
  infinity a component's settings hold written as `null`, as pydantic's JSON mode wrote it
  (`c1d29ee4`, `69b647a8`). The same recursion, once per group of digits, was in the model's
  numbers read from strings, a schema version's parts, the query parameters' whole numbers and
  the YAML resolver's base-60 numbers: their groups repeat possessively now, matching the same
  texts in a loop, and the YAML resolvers read a scalar of any length, as PyYAML's did, where
  SnakeYAML stopped at 1,024 characters (`4761041c`).
- Stage 5's follow-ups:
  - archsim-model refuses half of a surrogate pair wherever pydantic encoded the string
    (`8fbfa5c1`), and `PythonText.repr` writes the smallest subnormals as Python does
    (`2d9b68a7`);
  - the CLI takes an empty file option as not given (`f46daa60`), and reads `--seed` and its
    float options as Python's `int()` and `float()` did (`1c4a9485`);
  - the build runs `format` and `check` in one command, and registers its tasks as Gradle 10
    will (`4f979fc2`, `60964dc8`); the black-box suite's servers take five database connections
    each, so the whole suite fits PostgreSQL's default 100 (`347369cd`);
  - the `archsim-check` action tags its image for each check and removes only that tag, lets
    builds on one Docker take turns with Gradle's cache (two jobs at once on one Docker both
    pass) and uploads with `upload-artifact` v7 (`ddc5ca62`, `6125ea15`, `2e233b21`); two web
    comments name the Java code (`676a637d`).
- Sign-in moves to Spring Security after the port, in six test-first slices
  ([ADR 0065](docs/adr/0065-move-sign-in-to-spring-security-after-the-port.md), `6aa85dd5`),
  built since (above).
- `NOTICE` lists the third-party software the images and the CLI bundle; none is incompatible with
  the AGPL-3.0 ArchSim is licensed under (`f5953070`; [ADR 0067](docs/adr/0067-license-under-agpl-3.0-with-a-commercial-license.md)). The web image leaves out sharp and its libvips (LGPL-3.0-or-later), which
  only Next.js's image optimizer used: the app turns the optimizer off, and its build and CI fail if
  they come back ([ADR 0066](docs/adr/0066-ship-the-web-image-without-an-image-optimizer.md)).
  README, CONTRIBUTING, `docs/extending-components.md` and the other docs that described
  the Python engine as the product describe the Java one (`070e6f48`).

**The CLI, the MCP server and the action in Java, and the Python engine deleted: stage 5 of the
Java port (2026-11-12).**

- `archsim-cli` has every command of the frozen Python CLI: `validate`, `run`, `compare`,
  `analyze`, `calibrate prometheus` and `otlp`, `import`, `export`, `check`, `catalog` and
  `schema`, with the same flags, the same lines on the same streams, the same exit codes and JSON
  with the same values ([ADR 0055](docs/adr/0055-the-java-clis-output-contract.md)), options
  taken by their full names ([ADR 0056](docs/adr/0056-the-java-cli-takes-options-by-their-full-names.md)).
  It is checked against 307 cases recorded from the Python CLI (S5-1 to S5-4: `bba8dd7`,
  `35debf7`, `99afd7d`, `f34e412`). Where Python crashed with a traceback, the Java answers in
  one line; the departures are listed in [archsim-cli/README.md](archsim-cli/README.md).
- `calibrate prometheus --url` calls the app's Prometheus client, moved into the engine
  ([ADR 0057](docs/adr/0057-move-the-prometheus-client-into-the-engine.md), `36ca1fc`), and the
  CLI and the MCP server read and write files as Python did, through the engine's `files`
  package ([ADR 0059](docs/adr/0059-read-files-as-python-did-in-the-engine.md), `eb569ec`).
- `archsim-mcp` serves the Python MCP server's 12 tools, its resources and its instructions,
  word for word, on the official MCP Java SDK 2.1.0
  ([ADR 0058](docs/adr/0058-serve-mcp-with-the-official-mcp-java-sdk.md), `2215971`): a recorded
  session of 55 requests to the Python server gets the same answers from the Java one.
- The `archsim-check` action builds the Java CLI into an image in the job and runs the check in
  it, without a network; it needs a Linux runner with Docker
  ([ADR 0060](docs/adr/0060-run-the-check-action-in-an-image-the-job-builds.md), `e98d148`, a
  breaking change). Its job's steps ran in Docker on Linux (WSL) on seven cases, with the Python
  action's comment but for the engine's version.
- The price snapshots are `archsim-model`'s own resources, and the test fixtures moved to
  `parity/fixtures/` (`4fd2185`).
- The benchmark harness asks one long-lived `archsim functions` process for the engine functions
  it imported from Python (fits, machine speeds, means;
  [ADR 0061](docs/adr/0061-answer-the-harnesss-engine-calls-from-one-java-process.md),
  `7e733fb`): the old and the new harness derive the same 2,468 values from all 67 kept runs. It
  runs on the Java engine by default since `720090f`
  ([ADR 0062](docs/adr/0062-make-the-java-engine-the-harnesss-default.md)), and on it only since
  `e3a64ff`. On the way, a load curve's factor at a rate that is not a number no longer throws:
  it is the last point's, as in Python (`825c6b7`).
- What the Python engine still did moved to Java: the sizing audit is archsim-engine's
  `SizingAuditTests`, whose 301 checks on the 25 templates are `scripts/sizing_audit.py`'s
  (`ff9cbb1`); `archsim-app`'s `seed` role seeds the examples, and `archsim-api seed` is gone
  (`08bd1e7`); `./gradlew :archsim-engine:generate` writes the web app's engine fixtures
  (`b810c68`) and `docs/assumptions.md` (S5-10d), which `GeneratedFileTests` holds to the Java
  engine's output; the tests of the price scripts and the action's sticky comment moved to
  `scripts/tests` (`b33e151`); and the checks against queueing theory are archsim-engine's
  `QueueingTheoryTests`, `StarterReferenceTests` and `EstimateAgreementTests`, with the
  tolerances of the Python engine's reference benchmark and estimate tests (S5-10d).
- Deleted at the exit (S5-10d; git history keeps them, and the tag `engine-0.16.0` the engine as
  it was frozen): `packages/engine` (the Python engine 0.16.0, its CLI and its tests),
  `packages/mcp` (the Python MCP server), the scripts that recorded the frozen engine's answers
  for the Java tests (`export_golden.py` with `scripts/golden/`, the `export_*_cases.py` scripts
  and `export_simulation_baselines.py`) and their tests, and the scripts only that engine ran
  (`perf.py`, `validate_sampled.py`, `make_calibration_fixture.py`, `sizing_audit.py`,
  `gen_assumptions.py`, `export_web_fixtures.py`). What they recorded stays as data, not
  recorded again: `parity/golden/`, `parity/simulation/` and the case files under
  `archsim-*/src/test/resources`, which the Java tests read, and the speeds in
  `benchmarks/perf/` and runs in `benchmarks/results/`; the fixtures stay in `parity/fixtures/`.
  The uv workspace keeps `services/api`, and CI's Python job no longer runs the engine's tests or
  the golden-file check.
- Not run during stage 5: CI on GitHub, which has started no job since 2026-11-05. Every slice
  passed CI's steps locally.

**The simulator in Java, engine 1.0.0: stage 4 of the Java port (2026-11-10).**

- `archsim-engine` simulates: the kernel and every component, runs with their faults and
  autoscaling, sweeps and the breaking point, sampled mode and seed groups. It ships as engine
  1.0.0: new runs, `GET /meta`, reports and `archsim --version` name it, and runs stored before
  keep 0.16.0. `benchmarks/engines.json` records it, not frozen yet (its source trees are pinned
  at its freeze, before the next held-out campaign).
- It replays the frozen engine 0.16.0 figure for figure
  ([docs/validation/java-parity.md](docs/validation/java-parity.md)): all 149 template cases meet
  the parity bar, every one of their 2,980 runs gives the frozen engine's figures and digests,
  and all 65 kept calibrations' knees are the frozen engine's. (Engine 1.6.0, 2026-11-20: with
  the changes made on purpose since read as the frozen engine's, 2,860 of the 2,980 runs replay
  it exactly; the other 120 depart only where a cache refused lookups, which the baselines keep
  too little of to read;
  [ADR-0096](docs/adr/0096-read-the-simulation-paritys-runs-as-the-frozen-engines-where-the-engine-changed-on-purpose.md).)
  It runs the templates about 14 times faster (34 s for all 25 against 484 s, best of 5;
  [docs/performance.md](docs/performance.md)).
- The Java app's worker role simulates every run
  ([ADR 0053](docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)): each run is
  claimed under the advisory lock the Python worker took and simulated on a platform thread,
  with the same lease, heartbeat, cancel-grace and deadline timings. Compose starts it, and the
  black-box suite (132 tests) and the Playwright end-to-end tests pass with it.
- A calibration's replay runs on the Java simulator in the API's process, on platform threads of
  its own ([ADR 0054](docs/adr/0054-replay-calibrations-on-a-pool-of-platform-threads.md)).
- The Python worker, `POST /simulate` and the engine service are deleted; `services/api` keeps
  the Alembic migrations, `migrate` and `seed` until stage 6. Since stage 5's S5-10a (12 Nov) the
  seed is `archsim-app seed`, and `services/api` keeps the migrations and `migrate`.
- /accuracy and docs/validation.md say, in one sentence labelled re-simulated and in-sample, that
  1.0.0 re-simulates every kept calibration with exactly 0.16.0's knees (65 of 65): the latest
  predictions they show are 0.16.0's, which 1.0.0 reproduces (older runs' are older engines').
- The harness's move to the Java CLI and the Python engine's deletion moved to stage 5's exit
  (8 Nov): the MCP server and the `archsim check` action import the Python engine until stage 5
  ports them. Until then the harness simulates on the Java engine when asked (`BENCH_ENGINE=java`)
  and names those checks by its version. Since stage 5's S5-9 (11 Nov) the Java engine is the
  harness's default for every command, and `BENCH_ENGINE=python` runs the frozen engine
  ([ADR-0062](docs/adr/0062-make-the-java-engine-the-harnesss-default.md)); since S5-10c
  (12 Nov) it runs the Java engine only.
- Not run during stage 4: CI on GitHub, which has started no job since 2026-11-05. Every slice
  passed CI's steps locally.

**The engine's deterministic parts in Java: stage 3 of the Java port (2026-11-07).**

- `archsim-engine` (Java 25, a plain library over `archsim-model`) works out what ArchSim
  computes without simulating: sizing, cost and pricing, the instant estimate, the design review
  and findings, confidence, comparisons and seed summaries, exports, importers, calibration, the
  assumptions page and the `archsim check` gate (the last two as libraries for stage 5).
- Checked against the frozen engine's golden files: all 117 functions they record are ported and
  their 6,018 entries match, with one listed departure (a YAML library's words for text that is
  not YAML). Eight areas (analysis, cost, estimate, review, findings, reports, exports, imports)
  also have case files recorded from Python for what the golden files miss, and the number, text,
  time and URL rules were compared with Python on hundreds of thousands of generated inputs. The
  departures are listed in [archsim-engine/README.md](archsim-engine/README.md).
- The API answers from Java what the engine service answered: validating a scenario,
  `POST /analyze`, preparing a run, `GET /compare`, seed-group summaries, `POST /export`,
  `POST /import` and `POST /calibrate`.
- Calibrations call Prometheus from the API, pinned to the address they check
  ([ADR 0046](docs/adr/0046-call-prometheus-with-apache-httpclient-5.md)). Tests cover DNS
  rebinding, every redirect status, special addresses (unique-local IPv6, IPv4-mapped,
  link-local, 169.254.169.254, loopback) and TLS at the pinned address, and the address and URL rules matched
  Python on 320,000 generated cases.
- The engine service keeps one call, `POST /simulate`, the simulation a calibration replays,
  until stage 4 ports the simulator
  ([ADR 0045](docs/adr/0045-replay-calibrations-through-an-interim-simulate.md)). It no longer
  calls out.
- Not run during stage 3: the Playwright end-to-end tests, and CI on GitHub, which has started
  no job since 2026-11-05. Every slice passed CI's other steps locally.

**The held-out campaign: engine 0.16.0 out of sample, three runs each (2026-11-04).**

- The five held-out experiments, predicted by the frozen engine before they were measured
  (3-4 Nov, rotating). Median knee error: cache-ttl -6%, python-mixed +29%, chain +30%,
  go-mixed +67%, java-mixed +70%. One of five meets the ±25% target and three of five have the
  measured knee inside the predicted range in at least 2 of 3 runs (the plan's targets were all
  five). The range held 9 of 15 runs for a nominal 80%; every miss is java-mixed's or
  go-mixed's.
- Four of five over-predict capacity, the unsafe side, while their CPU per request is predicted
  within 8% (cache-ttl's service and Redis +22% and +25%). Unchecked lead: the three runtimes
  ran on the two hyperthreads of one core, which the model counts as two full cores; chain's
  +30% needs another cause. Model work waits for the Java port.
- cache-ttl meets criteria 1, 2 and 3a; only 3b (a second machine) is missing.
- The four tuned-on experiments ran three more times under the same engine (4 Nov): over six
  runs Kafka -3%, pg-hot-row -10%, pg-mixed +16%, cache-aside -23% (the in-sample target was
  ±15%). Two runs were disturbed and are not counted; pg-mixed's first, after a Docker Desktop
  restart, measured 2,875 req/s against 4,371 and 4,483 and counts by the rule.
- `report.py`: the held-out experiments lead docs/validation.md, accuracy.json and /accuracy,
  and the headline tables and /accuracy's cards label each experiment held out or tuned on.

**The Java API replaces the Python API: stage 2 of the Java port (2026-11-03).**

- `archsim-app` (Java 25, Spring Boot 4) serves every endpoint the FastAPI app served, with the
  same JSON, cookies, CSRF checks, rate limits, outbound block, token hashes and PostgreSQL
  tables. Flyway took the schema over at Alembic's revision 0008
  ([ADR 0032](docs/adr/0032-flyway-takes-over-from-alembic.md)). Its differences from the Python
  API, and the Python bugs it does not copy, are listed in
  [archsim-app/README.md](archsim-app/README.md).
- The engine stays Python: an internal engine service answers the API's synchronous calls
  ([ADR 0036](docs/adr/0036-python-engine-service-for-synchronous-calls.md)), and
  `archsim-api worker` processes simulate the runs it queues, until stage 4.
- The black-box suite (`archsim-api-tests`, ported from the Python API's tests) and the
  Playwright end-to-end tests pass against it. Docker Compose runs it from an image Jib builds
  (`make image`).
- The FastAPI app, its HTTP tests and the suite's Python mode are deleted; `services/api` keeps
  the workers, `migrate` and `seed`. With the Python API went its SQLite mode
  ([ADR 0016](docs/adr/0016-drop-sqlite-development-mode.md)): running ArchSim locally needs
  PostgreSQL, and the API listens on 127.0.0.1 unless told otherwise.

**Engine 0.16.0 frozen, a symmetric p95 criterion, and architecture decision records
(2026-11-02).**

- **Engine 0.16.0 is frozen** in `benchmarks/engines.json` (tree `4da78ff`, tag `engine-0.16.0`),
  for the held-out campaign and as the Java port's parity reference
  ([ADR 0017](docs/adr/0017-freeze-python-engine-during-port.md)).
  - The held-out experiments can now be calibrated and measured from a clean tree; they were on
    3-4 Nov (above).
  - Model and accuracy work waits until the Java port is complete.
  - `test_the_last_engine_entry_is_the_engine_in_this_tree` fails on any commit under
    `packages/engine` until it has a new version and entry (until stage 5 of the port deleted
    `packages/engine` on 12 Nov; the tag keeps the frozen tree).
- **Criterion 2 is symmetric** ([ADR 0018](docs/adr/0018-p95-within-a-factor-of-two.md)): a
  step's p95 must be within a factor of two either way, or within 3 ms. Before, a prediction that
  was too low always passed.
  - On the v6 runs no experiment meets it now. pg-mixed fails at 3,788 req/s (2.0 ms predicted,
    30.7 measured) and Kafka at 1,894 and 2,272 req/s, where both passed before.
  - /accuracy ranks the worst step by the same factor. `accuracy.json`'s `thresholds.p95Error` is
    now `p95Factor`.
- **Architecture decision records** in [`docs/adr/`](docs/adr/README.md), 18 of them in MADR 4:
  - recording decisions (0001);
  - three retroactive ones: the design document as the contract, the PostgreSQL run queue, and
    sealed held-out experiments;
  - the Java port and its decisions (0005–0016);
  - the freeze (0017) and the p95 rule (0018).
  - CONTRIBUTING.md asks for an ADR with every significant decision, and the README and
    docs/architecture.md link to them.

**W29: the service time's spread anchored at the calibration pass (2026-11-02; harness only,
engine still 0.16.0).**

- The cause of cache-aside's light-load p95 (2 to 3 times too high in the v6 campaign): the
  service time's shape (p95 over median) came from the handler's own time, which is under a
  tenth of the CPU per request, and cache-aside's also held the wait behind Redis's batched
  replies, which the model simulates again: 11 to 13 times from median to p95, against 2 to 3
  for the other three experiments.
- `run.py` `anchor_spread` (called by `anchor`, before the client latency): when the model's
  p95 - p50 at the top calibration pass is wider than k6's, the spread narrows, keeping the
  mean (the CPU per request), until they match (bisection on the log of the ratio, 10 s per
  try, at most 7 tries per replicate). Never widened: a tail the model misses can be the
  database's, the broker's or the network's.
- Re-simulated on every kept run (in-sample, `results/dev/0.16.0-w29/`): 31 of 38 checks are
  identical to 0.16.0's (all of pg-mixed's, hot-row's and Kafka's but pg-mixed's disturbed
  `run-3/` from 26 Oct, -72% to -78%). Cache-aside's v6 runs:

  | Cache-aside, 3 v6 runs | before | after | measured |
  | --- | --- | --- | --- |
  | p95 at the first step (1,231 to 1,325 req/s) | 2.57 to 3.39 ms | 1.39 to 1.54 ms | 1.42 to 1.44 ms |
  | p95 at the second step (2,462 to 2,650 req/s) | 3.32 to 4.56 ms | 1.39 to 1.60 ms | 1.50 to 1.54 ms |
  | Median p95 error below the knee | 225% to 328% | 8% to 23% | |
  | Knee error (median of 3) | -24.7% | -24.5% | |

  The spread falls to 5.1 to 5.2 times. The knee moves -1% to +6% in the model's own search;
  one v5 run drops a whole step of its measured ladder (-34% to -39%) from a 1% move. The
  committed v6 predictions keep the inputs they were made with; the next predictions use it.
- Tests: `test_service_spread.py` (narrowing keeps the mean, never widens, stops at the
  narrowest ratio, leaves a tail the spread does not move), and
  `test_split_inputs_reproduce_every_latest_calibration` re-derives every latest calibration
  with it (cache-aside's spread narrowed, the other three as calibrated).

**The protocol v6 campaign: engine 0.16.0 in-sample, three runs each (2026-11-02).**

- The four in-sample experiments ran three times each overnight (1-2 Nov, 23:41 to 06:14,
  Windows on Best performance, held-out experiments sealed, no run disturbed; four disturbed
  calibration passes retried, all pg-mixed's). Median knee error: **Kafka -3%, pg-hot-row -10%,
  pg-mixed +11%, cache-aside -25%** (baseline v5 with engine 0.14.0: +4%, -16%, +24%, -34%); the
  median absolute error over the 12 runs fell from 22% to 10%, and the 80% range held the
  measured knee in 11 of 12 (engine 0.14.0's: 1 of 12). All four meet criterion 1; none meets
  all of them (`docs/validation.md`).
- Not all of it is the model: pg-mixed's prediction is where it was and its real knee rose
  about 10% between the campaigns on the same laptop and settings; Kafka's real knee rose
  because the broker's healthcheck is gone (W23's fix, first measured here).
- New problem: cache-aside's p95 at a quarter and half of the knee is predicted 2 to 3 times
  too high in all three runs (2.8 to 4.7 ms against 1.4 to 1.5), from the v6 service time's
  spread (lognormal p95 11x its median) and the load curve's factor at those rates (1.4 to
  1.7). Fixed after the campaign in the harness (W29, above).
- Fixed: `run.median_inputs` took its keys from the first replicate only, so when that pass
  saw no mark-compact, `gcEveryRequests` was dropped and the model paused every 1,500 requests
  (Node's default) instead of about 32,500 (cache-aside's latest run, and hot-row's `run-9/`,
  where it moved p95 by at most 0.14 ms below the knee). Inputs only some
  replicates have now take the median of those, and the spacing goes when the median pause is
  0 (test `test_median_inputs_keeps_an_input_the_first_replicate_lacks`). The committed
  predictions keep the inputs they were made with.
- `report.py` renders a load curve input (`serviceTimeByLoad`), which the v5 campaign's
  calibrations did not have.
- W26's curve extension and W27's host machine stay off. Re-simulated on the 12 v6 runs
  (in-sample; W27 with `results/trials/2026-11-01-w27/what-if-fit.json`, k6's CPU and the
  background assumed):

  | Median knee error (3 v6 runs) | defaults | W26 `CURVE_FORM = "log"` | W27 `BENCH_HOST=on` | both |
  | --- | --- | --- | --- | --- |
  | pg-mixed | +11% | +11% | 0% | 0% |
  | pg-hot-row | -10% | -10% | -12% | -12% |
  | cache-aside | -25% | -23% | -34% | -33% |
  | Kafka | -3% | -3% | -27% | -27% |
  | Median absolute error, 12 runs | 10% | 10% | 14% | 14% |

  The extension adds little once the staircase reaches about two thirds of the core; the host
  model raises the CPU per request with the machine's load, while the measured cost keeps
  falling with load, and Kafka's broker and consumer join that load.

**Engine 0.16.0: accuracy round 2 together (2026-11-01; W26 to W28).**

- **W28, an honest predicted range:** a nominal 80% range from how much a fresh calibration
  moves the knee and from the model's own error, fitted on the in-sample runs
  (`run.py range-fit`, `benchmarks/knee-range.json`). It holds the measured knee in 9 of the 12
  counted v5 runs in-sample, leaving out each run and leaving out each experiment, where the old
  replicate range held 1. The committed results keep their old ranges until runs predicted with
  the new one exist; the held-out campaign is the real test.
- **W26, protocol v6:** the calibration staircase climbs to 65% of the service's CPU (v5: 35%);
  estimated from the kept ladders to take cache-aside from -34% to about -20%. The curve's
  extension above the staircase (`extend_load_curve`) is built and off (`CURVE_FORM`) until a
  v6 campaign's own runs support it. The trial script waits for the CPU to boost.
- **W27, a shared machine and hyperthreads:** `architecture.host` and `siblingSpeed` (schema
  1.7, empty by default, digests unchanged) model parts sharing one machine's threads; `run.py
  contention` measured the laptop (its VM's hyperthread pairs are not real pairs; work slows
  1.5x at 5.5 of 8 threads busy and 2.1x at 7.4; after about 15 minutes near full load the clock
  fell to about 1.6 GHz). Off in the benchmark designs: in-sample it took pg-mixed from +24% to
  +11% but cache-aside to -41% and Kafka to -22%.
- No knee moves in-sample with the defaults: engine 0.16.0 re-simulates every kept run as 0.15.0
  did. The v6 in-sample campaign comes next, then a decision on W26's extension and W27's host,
  then the freeze and the held-out campaign.

**The knee's predicted range: run-to-run variation and the model's own error (2026-11-01; accuracy plan W28).**

- The problem: engine 0.14.0's range was the lowest to highest knee of one run's three
  replicate calibration sets (nominal 50%), and it held the measured knee in 1 of the 12
  counted v5 runs. Within a run the replicates agree closely (the service's CPU per request to
  about 5%), but a calibration on another day moves more, and the model misses on the same side
  in every run of an experiment.
- Run-to-run variation of the calibrated inputs, over 25 v5 calibrations of the four in-sample
  experiments (14 kept runs and 11 calibration-only trials, one machine, OS build and power
  mode), one standard deviation: the service's CPU per request about 17% (13% to 14% for
  pg-mixed, cache-aside and Kafka, 24% for hot-row), a query's time in PostgreSQL 17% to 18%,
  the link to PostgreSQL 22%, the WAL flush 9%, Redis's command time 12%, Kafka's publish 11%;
  inputs move together with a common correlation of 0.49. Much of it is where the staircase
  stopped, since costs fall with load: pg-mixed's trial that topped at 1,024 req/s read 0.29 ms
  of CPU per request, the one that topped at 1,931 read 0.20 ms; hot-row's flush read 1.01 ms
  at a 221 req/s top and 0.74 ms at 381, which moved its predicted knee from 744 to 1,062 req/s.
  A calibration closer to the knee (W26) should cut it.
- The method (`run.py` `predict`, `knee_sensitivity`, `knee_range_of`): an **80% range**, a
  lognormal around the predicted knee whose log standard deviation adds two parts:
  - the calibration's: how much the knee moves with each input (one knee search with the input
    22% higher against one without, on 2 s + 8 s simulations, `RANGE_SIM_S`) times that input's
    variation, with the common correlation: 0.17 (log standard deviation) where the service's
    CPU sets the knee (pg-mixed, cache-aside, Kafka: the knee falls 1% per 1% of CPU), 0.10
    for hot-row, set by the WAL flush (-0.8) and the write's time (-0.26);
  - the model's own error: 0.15 (log standard deviation of measured over predicted knee, by
    maximum likelihood over the 12 counted runs after each one's calibration spread).
  The two are about the same size; the 80% range is about x0.75 to x1.33 of the knee.
  Both are fitted on the in-sample experiments only, by `run.py range-fit`, into
  `benchmarks/knee-range.json` (part of the harness's provenance). 80% rather than 50%: a range
  missed every other run is no planning number, and its low end is now a load about one run in
  ten falls below; 90% would rest on the one or two largest errors of a dozen runs. Sensitivity
  times variation rather than random draws: the leave-one-out check refits the variation for
  each run left out, which needs no new simulation this way, and the result is deterministic and
  says which input moves the knee.
- Coverage of the measured knee, the 12 counted v5 runs, engine 0.16.0-w28 (= 0.15.0's model)
  re-deriving each run's inputs and knee from its calibration:

  | Range | In-sample | Leaving out the run | Leaving out its experiment |
  | --- | --- | --- | --- |
  | Engine 0.14.0's (replicate sets, nominal 50%), as predicted | 1 of 12 | - | - |
  | W28, nominal 50% | 5 of 12 | 5 of 12 | 5 of 12 |
  | **W28, nominal 80% (the range predicted)** | **9 of 12** | **9 of 12** | **9 of 12** |
  | W28, nominal 90% | 10 of 12 | 10 of 12 | 10 of 12 |

  Slightly narrow at every level, within what 12 runs can tell (9 of 12 at 80% is "as
  claimed" by the binomial test). The three misses at 80% are all cache-aside's, whose measured
  knee was 35% to 51% above the prediction in every run; two runs sit within 1% of an end
  (Kafka's run 3 inside, cache-aside's run 3 outside), so the count is fragile by one, and
  Kafka's measured knees were limited by the broker healthcheck. Leaving out a whole experiment
  changes no count: the others' errors set a similar model error. Per run and per input:
  `docs/validation/knee-range.md` (`report.py --dev`).
- Limits: the model's error is fitted on runs the model was tuned on, from four
  experiments, so the stated 80% is an in-sample claim; the held-out campaign is the test
  (`summary.coverageHeldOut` in accuracy.json, and a sentence on /accuracy, once they are
  measured). The committed results keep their engine-0.14.0 ranges, so docs/validation.md's
  tables and /accuracy still say 1 of 12 (too narrow) until runs predicted with this range exist.
- Report and page: `repeatability` and `summary.coverage` count the hits each range's own
  nominal coverage expects and give a verdict from the binomial tails at 5% ("too narrow", "too
  wide", "as claimed"; with 12 runs at 50%, 2 or fewer hits are too narrow, at 80% 6 or fewer);
  the page's coverage sentence reads them instead of a hard-coded 50%, and the knee scale's
  tooltip says where a range came from.
- Engine label `0.16.0-w28` (no engine or schema change: the assumptions page and the check
  snapshot show the label only). Checks: pytest (942 passed, 24 expected failures, 1 skipped),
  ruff check and format, mypy (engine, API, MCP; benchmarks), web lint, vitest 324, build,
  tsc, Playwright 6.

**Calibration protocol v6, and an experimental CPU curve above the staircase (2026-11-01; accuracy plan W26, label 0.16.0-w26).**

- Protocol v6 (`benchmarks/run.py`: `PROTOCOL`, `STAIRCASE_*`, `next_rise`, `cpu_bound`,
  `other_bound`): each rise of the calibration staircase goes to 65% of the service's core at
  the cost the pass before measured (v5: 35%), still at most half of any other capacity (pool,
  hot row's lock, Kafka consumers), with the 10 ms p95 stop, rises of at least 10% (v5: 25%) and
  at most five (v5: three). `calibration.json` records `"protocol": "v6"` and `staircaseRule`;
  v5 calibrations stay readable and re-derive exactly as before. 65% is the most the in-sample
  stacks took with p95 still a few milliseconds (kept ladders: 1.4 to 2.2 ms around half a
  core, pg-mixed 8 to 11 ms by three quarters).
- `extend_load_curve` (`archsim_engine.calibration.resources`) carries a clear fall of CPU per
  request above the staircase's top: fitted against log(rate) over every pass at the rates that
  kept the core at least 30% busy, only when the top kept it at least 45% busy and the fall
  exceeds two standard errors of the passes' scatter, continued at most one measured stretch
  and never past where the core fills (one more `serviceTimeByLoad` point; the engine
  interpolates to it and stays flat beyond). **Off by default** (`run.py` `CURVE_FORM = "flat"`,
  today's behaviour): the only evidence is in-sample staircases built from the kept ladders, not
  a v6 calibration. A fall linear in busy share (each event-loop wake-up shared by more requests)
  fitted cache-aside slightly better but extrapolated further, and was dropped.
- No engine simulation change, no new setting, schema and report unchanged: the check snapshot's
  result digest (`6f115d28853f`) is unchanged, so are every example's (the engine's simulation
  code is untouched). Every kept v5 calibration tops out at 9% to 38% busy (47% for the
  disturbed pg-mixed `run-7`, whose fit is flat anyway), so its `serviceTimeByLoad` is
  byte-identical with the curve on or off. `run.py resimulate` (`results/dev/0.16.0-w26/`)
  gives every one of the 26 kept runs it can re-derive (28 less the two one-pass runs) the same
  inputs, simulated steps and knee as engine 0.15.0 (`docs/validation/dev-checks.md`).
- Trials: `benchmarks/results/trials/W26-trial.py` (calibration only, holds the benchmark lock,
  waits for a quiet host and now for a boosting CPU) and `W26-score.py` (a trial's calibration
  against the kept runs' ladders). The one v6 trial, 1 Nov 02:13, ran on a throttled laptop
  (busy cores at 74% to 92% of the 2.8 GHz base clock; the service's CPU per request read twice
  its usual) and was stopped; it is kept as `2026-11-01-cache-aside-w26-throttled/`, not used.
  No v6 calibration exists yet: Docker was down for the rest of the session.
- Before and after, in-sample (three counted v5 runs each). "Core full" is where one core fills
  at the model's CPU per request above the staircase's top, which is the model's knee for the
  CPU-bound three (engine 0.15.0's simulated knees sit within 1% of it). The v6 columns replay
  v6's rise rule on each run's own costs (its staircase, then its measured ladder above the v5
  top): estimates from data the protocol was designed with, not measurements.

  | Median over 3 runs | Engine 0.15.0 (v5), simulated knee error | W26 on the kept runs (resimulate) | v6 staircase top (replayed) | v6, flat above the top: core full vs measured knee | v6 with the log extension (ladder staircases) |
  | --- | --- | --- | --- | --- | --- |
  | pg-mixed | +24% | +24% (unchanged) | 3,030 to 3,130 req/s, 63% to 65% busy | +20% (+16%, +27%, +20%) | unchanged: does not fire |
  | pg-hot-row | -7% | -7% (unchanged) | 214 to 443 req/s (half the lock's bound) | not CPU-bound; the lock's inputs come from a top 3% lower to 23% higher than v5's, effect not measured | does not fire (12% busy) |
  | cache-aside | -34% | -34% (unchanged) | 3,120 to 3,470 req/s, 60% to 61% busy | -20% (-20%, -20%, -29%) | -10% where flat gives -18% (ladder staircases cut at 70% busy); cut at 65%, it fires in one run of three (-20% to -16%) |
  | Kafka | +4% (healthcheck-limited knees) | +4% (unchanged) | 856 to 880 req/s, 30% to 38% busy (half the consumers' bound) | +12% (+35%, +10%, +12%) | does not fire (under 45% busy) |

  CPU per request (ms) at each step of the latest run, measured / engine 0.15.0 (simulated) /
  v6 with the flat rule (the replayed staircase's curve): cache-aside 0.233 / 0.216 / 0.253 at
  1,212 req/s, 0.208 / 0.203 / 0.203 at 2,425, 0.183 / 0.203 / 0.193 at 3,638, 0.170 / 0.203 /
  0.193 at 4,365, 0.164 / 0.203 / 0.193 at 4,850, 0.144 / 0.165 / 0.193 at 6,062 (past 0.15.0's
  knee its simulated cost falls only because its queue holds requests); pg-mixed 0.223 / 0.207
  / 0.228 at 1,238, 0.207 / 0.201 / 0.211 at 2,475, 0.209 / 0.201 / 0.208 at 3,712; Kafka's
  producer 0.516 / 0.483 / 0.485 at 525, 0.425 / 0.475 / 0.441 at 1,050, 0.368 / 0.473 / 0.441
  at 1,575, 0.343 / 0.474 / 0.441 at 1,890. v6's first rise jumps from 50 to about 630 req/s, so
  its curve is coarser at light load (cache-aside +9% at 1,212 req/s, where 0.15.0 was -7%).
- What did not improve, or is unproven: no v6 run exists, so none of the v6 numbers is measured;
  Kafka's estimated error grows from +4% to +12% against knees the old healthcheck set (the
  fixed stack held about 3,000 req/s, W23, which v6's 2,270 to 2,890 would under-predict by 4%
  to 24%); hot-row's v6 top moves its lock inputs a little, untested; between half and three
  quarters of the core pg-mixed's cost falls like cache-aside's (0% to 7% against 5% to 12%) and
  only stops above that, so a staircase capped at two thirds cannot tell the two apart, which
  is why the extension stays off until the v6 campaign's runs can be re-simulated with it.

**CPU threads that slow each other down: a host machine and hyperthreads (2026-11-01; accuracy round 2, W27; engine label 0.16.0-w27, schema 1.7). Shipped off in the benchmark designs.**

- **What the laptop does** (`run.py contention`, new; `results/envcheck/2026-11-01T00-48-59Z-contention.json`):
  a fixed unit of V8 work on CPU 2 with burners on chosen CPUs, Windows' counters read every
  second (each logical processor's busy share as the hypervisor sees it, actual frequency,
  limit flags). Docker Desktop's VM numbers its CPUs as hyperthread pairs, but Windows
  schedules them itself: the probe and its in-VM "sibling" together did 1.57 to 1.70 times one
  CPU's work (a real pair does about 1.2), so cpusets do not decide who shares a core. What
  sets the speed is how many of the 8 logical processors the whole machine has busy: CPU per
  unit of work 0.85 to 1.0x at 1.8 to 3.5 busy, 1.2x at 4.6, 1.5x at 5.5, 1.9x at 6.6, 2.1x at
  7.4, about 3x with all 8 (oversubscribed); the clock fell in a straight line from 4,050 MHz
  (1 busy) to about 2,950 MHz (8 busy), no limit flagged. Four physical cores, that clock (x0.73)
  and a thread sharing its core at 0.48 of its speed fit 20 conditions within 5.5% (rms, log).
  A container's CPU time includes what Windows takes away (no steal reported), so its CPU per
  request rises with the whole machine's load. After about 15 minutes near full load (the check
  plus other sessions' jobs) the clock fell to 1,525 to 1,775 MHz under full load and read 2,350
  to 2,850 MHz at a quarter to a third of it (4,000 to 4,300 before), for over an hour, still
  with no limit flag, on mains and Best performance (W26 saw about 2.1 GHz overnight).
- **Not measured**: k6's own CPU per request and the machine's background at rest. The short
  check that reads them (`run.py contention --service-only`, about 4 minutes) needs a quiet,
  cool machine and never got one (two attempts: other sessions' jobs, then throttling); its
  first service part ran at 6 to 8 busy threads and is not used.
- **Engine** (`components/host.py`; both settings empty by default, left out of the JSON; no-op
  migration 1.6 -> 1.7): `architecture.host` (`physicalCores`, `threadsPerCore`, `siblingSpeed`,
  `allCoreClock`, `otherBusyThreads`, `clientCpuMs`): every part with a CPU size shares that
  machine's threads with its background and the CPU the clients' traffic costs on it. A thread's
  speed = clock (straight line from 1 busy thread to all) x sharing (past one thread per core,
  a thread sharing its core runs at `siblingSpeed`) x turns (past every thread). In the
  simulation each piece of CPU work runs at the speed for the busy threads when it starts (part
  executors, background, clients at that moment). `siblingSpeed` on a service, database or
  cache: its own vCPUs as hyperthread pairs (2 vCPU at 0.6 serve 1.2 cores' work). Every
  analytic copy uses it: `resource_capacity` (service, database, cache: the load multiple at
  which the part's threads fill, everything growing in proportion), the event-loop `explain`
  formula, the instant estimate, auto-sizing and the review's CPU finding. Report: a top-level
  `host` (busy threads, mean speed) when the design has one. All 25 examples' result digests
  (8 s runs) are identical to main's engine with the settings empty, and so is the check
  snapshot's (`6f115d28853f`). The web editor keeps a document's `host` through edits (no form
  for it yet); `siblingSpeed` shows as a field.
- **Harness** (`run.py`): `machine_inputs` gives a design its host from the contention check
  (`host_fit`) and the top pass's own machine load (every container's CPU, k6's, the
  background): the CPU-time inputs (`serviceTimeMs`, `dbCpuShare`, `cacheServiceTimeMs`) are
  divided out by the machine's speed during that pass; containers the model has no CPU for
  (Kafka's broker and consumer) join the host's other load. `pass_speed(entry)` gives the
  machine's speed for any calibration pass, for W26's curve. **Only with `BENCH_HOST=on`**
  (default off: the designs are as before); `BENCH_HOST_FIT` takes a fit file, `BENCH_HOST_CLOCK`
  a clock, for what-ifs.
- **In-sample what-if** (`BENCH_HOST=on` with `results/trials/2026-11-01-w27/what-if-fit.json`:
  the probe's fit, k6 0.25 ms per request and 0.3 background threads ASSUMED;
  `results/dev/0.16.0-w27/`, `docs/validation/dev-checks.md`), against engine 0.15.0:

  | | engine 0.15.0 | 0.16.0-w27, host on (what-if) |
  | --- | --- | --- |
  | pg-mixed median knee error (3 counted v5 runs) | +24% (+20, +25, +24) | +11% (+8, +13, +11) |
  | pg-hot-row | -7% (-11, +10, -7) | -7% (-11, +10, -7) |
  | cache-aside | -34% (-26, -34, -34) | -41% (-33, -41, -45) |
  | Kafka | +4% (+33, +3, +4) | -22% (0, -23, -22) |
  | pg-mixed p95 at 2,475 / 3,712 / 4,455 req/s (measured 2.04 / 8.67 / 65.1 ms) | 1.57 / 1.76 / 2.37 | 1.57 / 1.91 / 17.2 |
  | pg-mixed busy cores there, Node (measured 0.51 / 0.77 / 0.89) | 0.50 / 0.75 / 0.90 | 0.52 / 0.80 / 0.99 |
  | pg-mixed busy cores there, PostgreSQL (measured 0.44 / 0.73 / 0.86) | 0.42 / 0.64 / 0.76 | 0.44 / 0.70 / 0.90 |

  Median absolute knee error over all 19 counted runs 24% -> 22%, over the latest runs 15% ->
  17%. pg-mixed moves halfway toward 0 and PostgreSQL's CPU near the knee becomes right, but
  partly for an offsetting reason: Node's CPU per request measured flat from 1,674 to 4,455
  req/s while Windows' clock fell about 11% over that range, so its work per request fell about
  as much (more requests per event-loop wake-up), which the model does not have above the
  staircase; with only the machine's slowdown, Node's CPU at 4,455 req/s is 11% high and the
  model's capacity lands near the real knee because its latency cliff is sharper than the real
  one. Cache-aside gets worse for the same missing fall (its real CPU per request falls from
  0.21 to 0.13 ms with load). Kafka gets worse because the broker's healthcheck JVM (0.42 cores
  at rest) and the consumer join the machine's load, and its kept knees are the healthcheck's
  anyway (W23). **The gate is not met, so the benchmark designs ship without a host.**
- **What would settle it**: (1) a quiet, cool `run.py contention --service-only` (k6's CPU per
  request and the background, instead of assumed values); (2) W26's falling cost per request
  fitted to `cost x pass_speed(pass)` on a staircase that reaches nearer the knee, then the four
  experiments re-simulated with `BENCH_HOST=on`; (3) for Kafka, a run after the healthcheck fix.
  The held-out stacks are where hyperthreads matter most (java, go and python on 2 vCPU) and
  stay sealed.
- Checks: pytest (engine `test_host_machine.py`, harness `test_machine_inputs.py`), ruff, mypy,
  web lint/vitest/tsc/build and Playwright.

**Engine 0.15.0: the accuracy plan's four model changes together (2026-10-31; W21 to W24).**

- The four workstreams below, merged on one branch: a single 1.5 -> 1.6 migration covers W21's
  `serviceTimeByLoad` and W23's `consumerConcurrency` and `fetchOverheadMs`; each calibration
  pass keeps W24's resource readings and W22's PostgreSQL statistics; each re-derived replicate
  carries both the staircase (W24's memory fit) and `cpuByLoad` (W21).
- In-sample re-simulation of every kept run with engine 0.15.0 (`docs/validation/dev-checks.md`,
  clean tree at `75777df`, a column per workstream and one for all four): median knee error over
  the counted v5 runs pg-mixed +24%, pg-hot-row -7% (was -16%), cache-aside -34%, Kafka +4%;
  median absolute knee error over the latest runs 20% -> 15% (24% over all 19 counted runs, as
  before). These are runs the changes were tuned on, not predictions.
- `report.py` flags a harness caveat (`HARNESS_CAVEATS`): every kept Kafka run had the broker
  healthcheck W23 found, so its measured knees are the benchmark's. /accuracy shows the caveat
  on Kafka's card and in the short version, and docs/validation.md in Kafka's section, until a
  Kafka run is calibrated after the fix.
- Not frozen in `benchmarks/engines.json` yet: the held-out experiments stay sealed.

**Kafka: message age, fetch cycles, and a publish tail that was the harness's (2026-10-31; accuracy plan W23).**

- Message age now starts when the publish starts (`Message.created_at`), as the benchmark's
  producer stamps it; until now it started at the acknowledgement. `queueWaitMs` is unchanged.
- Message queues gain `consumerConcurrency` and `fetchOverheadMs` (schema 1.6, both empty by
  default, as before). Set either and consumers poll in cycles, as kafkajs does: take the
  partitions' whole backlog, process it in N slots (a partition's messages one after another),
  wait for all, pay the fetch and commit overhead, poll again (`consumerSlots`; warning
  `queue.idle_slots`; the instant estimate counts slots). The Kafka benchmark design is one
  consumer with concurrency 3, as the real stack. Example digests (all 25 templates, 8 s runs):
  17 unchanged; the 8 with a queue change only in their message age.
- The benchmark service reports kafkajs's fetch cycle (`cycle_overhead`, `cycle_fetch`,
  `cycle_after`, batches) and the producer's sends in flight; `run.py` calibrates
  `fetchOverheadMs` from it. Kept runs lack it and take the W23 trial's figure (median 0.93 ms,
  p95 3.88 ms per poll at 569 req/s).
- The publish tail that grew with load (client p95 42 ms at 1,890 req/s) was the benchmark's:
  the broker's healthcheck started a JVM every 3 s on the broker's one physical core (about 0.5
  cores). Run once, the stack's p95 was 5.0 ms at 1,890 req/s, which engine 0.14.0 predicted
  (4.9 ms); GC (1 to 4 pauses per pass) and the producer's single connection were ruled out.
  `compose.yaml` now runs the check only until it first succeeds. All five kept Kafka runs (v4
  and v5) ran with it, so their measured knees are harness-limited. Trial data:
  `benchmarks/results/trials/2026-10-31-kafka-w23/` (development data, not on /accuracy).
- In-sample re-simulation of the kept Kafka runs (`docs/validation/dev-checks.md`), engine
  0.14.0 against this branch, latest run (525 / 1,050 / 1,575 / 1,890 req/s):

  | | measured | engine 0.14.0 | W23 |
  | --- | --- | --- | --- |
  | median knee error, 3 counted v5 runs | | +4% | +4% (unchanged: the client's path is) |
  | client p95 (ms) | 4.6 / 10.5 / 22.4 / 42.5 | 3.3 / 3.4 / 3.7 / 4.9 | 3.3 / 3.4 / 3.7 / 4.9 |
  | consumed per second, 1,890 req/s and above | 1,568 to 1,671 | 1,383 | 1,360 to 1,372 |
  | message age p95 (ms) | 14 / 132 / 2,660 / 7,440 | 3.9 / 10.1 / 2,904 / 6,413 | 10.3 / 29.4 / 4,067 / 8,631 |

  The measured p95 is the healthcheck's (the fixed stack: 1.7 / 3.3 / 5.2 / 5.0 ms). Message age
  at light load moves from a quarter to three quarters of the measured; at 1,050 req/s it stays
  4.5x short (rare consumer stalls of 0.2 to 0.8 s set the real p95). The consumer's cap stays
  low: the model's INSERT takes 2.1 ms (0.89 ms write, 1.2 ms waiting for the WAL flush) where
  the consumer measured 1.73 ms, a database input (W22). In the trial the real consumer kept up
  with only 1,320 to 1,440/s.
- Kafka's knee with W21: the producer's CPU 30% cheaper at high load puts the predicted knee at
  3,000 / 3,050 / 4,050 req/s (latest, run 4, run 3): +48% median against the kept runs, whose
  knees are the harness's, and -9% to +35% against the fixed stack's knee (about 3,000 to
  3,300 req/s, where the producer's core fills; single trial passes). Judge W21's Kafka effect
  on the prospective campaign's new measurements, not on the kept runs.
- Engine label `0.15.0-w23`, schema 1.6 (a no-op 1.5 -> 1.6 migration, as on W21's branch). The
  Kafka design now carries 1.6 settings, which engine 0.14.0 refuses: its baseline predictions
  need the 0.14.0 harness. Checks: pytest (870 passed in a full run on a host other sessions held
  at 100% CPU; of its 11 failures, 5 passed when run again (timing and process tests, a Node
  crash under load, and a provenance check that saw files change during the run) and 6 were
  this change's, now updated: the CI snapshot's engine label (4), and the Kafka design and
  inputs that W19's and W18's tests pin), ruff, mypy, web lint, vitest 319, build, tsc,
  Playwright 6.

**Sizing inputs from what the containers use: Redis CPU, service memory (2026-10-31; accuracy plan W24).**

- **Redis's command time is its CPU per command**, at the top calibration pass net of idle
  (`run.cache_inputs`; v4 calibrations: per extra command between the two passes): 0.032 to
  0.038 ms per GET or SET in the v5 runs, where `INFO commandstats` reported 0.002 to 0.003 ms.
  Commandstats leaves out reading requests and writing replies, which Redis does on the same
  thread. The Redis link is what is left of the GET round trip's median, and the anchor derives
  the client latency again, so each calibrated p50 is still reproduced exactly (residual 0 ms).
  ArchSim's own Redis default (0.035 ms) was already this size; only the benchmark under-counted.
- **Service memory is fitted at calibration**: every calibration pass and the idle pass read each
  container's working set (`ResourceSampler`, from the pass's start), and each service's memory
  at idle and per req/s is W17's `fit_memory` over rest and the whole staircase
  (`run.memory_inputs` → `service_node`; chain fits its gateway and API apart; memory per request
  held is the 0.01 MiB floor). A calibration without the readings, or whose staircase never rose
  to two rates, keeps ArchSim's defaults, so every kept run's design is unchanged.
- No engine change (`0.15.0-w24` is a label for the dev checks), so the example `resultDigest`s
  and the 25-template sizing audit are untouched. **No predicted knee moved** on any of the 26
  re-simulated runs (`results/dev/0.15.0-w24/`; median knee error 20% latest, 24% over the 19
  counted runs, as with 0.14.0). pg-mixed, pg-hot-row and Kafka simulate identically at every
  step. Cache-aside's p50, p95 or p99 moved by one histogram bucket (2%, 0.01 to 0.08 ms) at some
  steps, from the longer Redis command time's spread; its success rates are unchanged.
- The kept runs have no memory readings, so a calibration-only trial (31 Oct, quiet host,
  `benchmarks/results/trials/2026-10-31-*-w24/`, development data) supplied each service's fit,
  scored against every counted kept run's measured working set (`test_sizing_inputs.py`).

| Part (3 counted v5 runs each) | Latest run's top step | Measured | Before (0.14.0) | After (W24) | Top-step error, counted runs | Median error up to both knees |
| --- | --- | --- | --- | --- | --- | --- |
| Redis busy cores (cache-aside) | 4,850 req/s | 0.18 | 0.018 (-90%) | 0.21 (+19%) | -90% to -92% → +14% to +19% | 91%-93% → 10%-14% |
| Node.js API memory (pg-mixed) | 3,712 req/s | 145 MiB | 278 MiB (+91%) | 109 MiB (-25%) | +76% to +91% → -25% to -34% | 199%-282% → 20%-27% |
| Node.js API memory (pg-hot-row) | 782 req/s | 66 MiB | 320 MiB (+386%) | 44 MiB (-33%) | +57% to +487% → -11% to -76% | 41%-501% → 2%-76% |
| Node.js API memory (cache-aside) | 4,850 req/s | 182 MiB | 920 MiB (+407%) | 68 MiB (-63%) | +358% to +412% → -63% to -64% | 312%-328% → 32%-59% |
| Node.js producer memory (Kafka) | 1,890 req/s | 179 MiB | 292 MiB (+63%) | 138 MiB (-23%) | +63% to +86% → -10% to -23% | 76%-138% → 15%-26% |

- **Memory is now low near the knee.** It is far closer than the defaults (256 MiB at idle plus 2
  MiB per request held) at almost every step, but 10% to 76% low at the top step. The staircase
  stops at 35% of the service's core, a third to a sixth of the knee, and V8's heap grows faster
  than the rate beyond it (pg-mixed measured 45, 70 and 145 MiB at 1,238, 2,475 and 3,712 req/s).
  The heap also lags the rate (it is collected lazily): cache-aside's warm-up heap at rest
  (65 MiB) pinned its fit flat at 0 MiB per req/s. And hot-row's run 7 measured 187 MiB because
  it ran on the Node.js process pg-mixed's ladder had just used. A design sized on these figures
  would be too small near the knee, where the defaults made it 1.6x to 5.9x too large.
- **Redis is now slightly high** at the top step (+14% to +19%): its CPU per command falls with
  load (0.043 ms at 1,212 req/s in the trial, about 0.03 ms at 4,850), the effect W21 models for
  services.

**Service CPU that follows load, and Node.js garbage collection (2026-10-31, schema 1.6;
accuracy plan W21).**

- **`serviceTimeByLoad`** (services, schema 1.6, no-op migration; left out of the JSON when
  empty, so documents and result digests are unchanged): `{rps, factor}` points per replica.
  Each request's service time is multiplied by the factor at its replica's arrival rate over
  the last second (bias-corrected EWMA; serverless: the function's rate over its busy
  instances), interpolated in log(rate) and flat outside the points: a fall measured up to some
  rate is never extrapolated. Helpers in `components/load_curve.py`; every analytic copy uses
  them: the report's capacity and the event-loop explanation solve where the cores or loops fill
  at that rate's factor (a fixed point, through a new `slot_capacity` hook), as does the
  review's CPU finding; the instant estimate and sizing take the factor at their rate per
  replica. The web inspector edits it as "req/s: factor" pairs.
- **Calibration:** `load_dependence` became `fit_load_curve` (up to 6 quantile bins by rate per
  replica, never rising with rate, relative to CPU per request over the range); Prometheus
  calibration sets the table on a service when it spans more than 10%, and notes it for a
  database, an autoscaled or a serverless service. On pg-mixed run 5 every step below the knee
  is within 15% with it (the share alone: 51% low at 560 req/s).
- **Benchmarks:** the curve comes from the staircase's `cpuByLoad` (relative to the top rate).
  `server.js` reports V8's collections by kind (PerformanceObserver 'gc': main-thread pause,
  and when each pause other than a scavenge starts). A mark-compact cycle (its
  incremental-marking step and final pause, about 10 ms apart) becomes the model's GC pause
  (`gcPauseMs`, `gcEveryRequests`) and its time comes off the CPU per request, which held it,
  so nothing is counted twice; scavenges, frequent and about 1 ms, stay in the CPU per request.
  Kept runs reported no GC: `resimulate` takes it from four calibration-only trials
  (`results/trials/2026-10-31-*-w21/`, W21-README.md). The harness allows a simulation 1 h of
  wall clock (the engine's 300 s default stopped an overloaded pg-mixed step on a shared CPU).
- **Result: no knee moved.** Median knee error over the three counted v5 runs (in-sample
  re-simulation, `docs/validation/dev-checks.md`), engine 0.14.0 → this branch: pg-mixed
  +24% → +24%; pg-hot-row -16% → -16%; cache-aside -34% → -34%; Kafka +4% → +4%. Every knee lies 3
  to 5 times above its staircase's top rate, where the curve is flat by design, and the GC
  pauses are small: 0.1% to 0.45% of the event loop's time, a 4 to 16 ms mark-compact every
  7,500 (Kafka's producer) to 58,000 requests. pg-mixed's p95 at 2,475 / 3,712 / 4,455 req/s
  (measured 2.04 / 8.67 / 65.1 ms): engine 0.14.0 1.60 / 1.80 / 2.28 ms, this branch 1.63 /
  1.84 / 2.47 ms. The predicted knees moved by at most 1 req/s. What changed is CPU at light
  load: the Node.js services' predicted CPU at the ladder's lowest step went from -22% to -1%
  (hot-row), -12% to -8% (cache-aside) and -10% to -7% (pg-mixed).
- **GC does not explain pg-mixed's early latency rise.** In the quiet trial pg-mixed's p95 at
  3,712 req/s was 1.74 ms (the baseline step: 8.67 ms; predicted 1.80). At 4,455 req/s the
  stack overloaded with Node 89% busy and PostgreSQL saturating its two CPUs, hyperthread
  siblings of one core (1.0 core busy, CPU per query 0.162 → 0.257 ms, the service's reads
  waiting 34 ms): pg-mixed's knee looks set by PostgreSQL's CPU (W22). cache-aside's CPU per
  request kept falling above its staircase (0.22-0.25 ms at the top, 0.170 at 4,850 req/s),
  which the curve, flat above its last point, leaves out: its -34% stays.
- **Checks:** all 25 examples give the same result digest as main (200b545) with the new setting
  empty; the 25-template sizing audit passes; the engine simulates an overloaded pg-mixed step
  as fast as before (17.9 s against 18.1 s). `uv run pytest` (the one failure, the engine label
  in the generated docs/assumptions.md, regenerated), ruff, mypy, and in apps/web build, lint,
  vitest (323), tsc and Playwright (6, on 3002/8002) pass. Checked in the browser: the inspector
  edits the curve, a run carries it, and the explanation reads "Service time at this load:
  10.18 ms x 2.2831 at 201 req/s per replica".

**Database inputs from PostgreSQL's own statistics (2026-10-31; accuracy plan W22, label
0.15.0-w22).**

- In-sample, re-simulated on the counted v5 runs (three each; `docs/validation/dev-checks.md`
  for the knees). PostgreSQL busy cores: the error at the highest load step at or below both
  knees, median of the runs.

  | Experiment | Median knee error, 0.14.0 → W22 | PostgreSQL busy cores, 0.14.0 → W22 |
  | --- | --- | --- |
  | pg-mixed | +24% → +24% (each run moved 0%) | -39% → -11% |
  | pg-hot-row | -16% → -7% (runs: -19/0/-16% → -11/+10/-7%) | -70% → -11% |
  | cache-aside | -34% → -34% (each run moved 0%) | -16% → -8% |
  | Kafka | +4% → +4% (each run moved 0%) | -50% → -4% |

  Median absolute knee error: 20% → 15% over the latest run of each experiment, 24% → 24% over
  all 19 counted runs. Runs before protocol v5 have no CPU figure for the pass without the
  flush, so their database keeps the measured round trips, as in 0.14.0, and only the flush is
  fitted: their knees are 0.14.0's within 1%, except pg-mixed run 3 (808 → 873 req/s), whose
  flush's p95 was 14 times its median. The engine is unchanged (only the label), so example
  `resultDigest`s are too.

- **PostgreSQL's CPU (fixed).** `dbCpuShare` was PostgreSQL's CPU per query over the mean
  query time the service measured, a write's WAL flush and the measured tail included, while
  the model spends the share of the time it samples (writes without the flush, which uses no
  CPU, from a lognormal whose mean is lower). It now divides by the model's own mean
  (`mean_ms`, the engine's `expected()`), as `calibration/apply.py` already did for customers.
- **A query's time in PostgreSQL is its CPU per query** (net of idle, shaped like the measured
  round trip), and the rest of the round trip (Node's driver, the network) is a new input,
  `dbLinkLatencyMs`, on the service-to-database edges: two lognormal legs that keep the round
  trip's mean and variance, so the model's round trip is unchanged. W22's trial found the
  backends busy on the CPU for nearly all of PostgreSQL's time (the WAL writer and other
  processes use under 0.01 ms per query). hot-row's row lock is no longer held for the driver
  and network time; the share comes out at about 1.
- **The WAL flush.** With PostgreSQL's statistics, `commitFlushMs` is pg_stat_wal's mean flush
  time (write + sync per sync) on the top pass. Without them (every kept run), it is fitted in
  a PostgreSQL-only copy of the model (`fit_flush`) so the model's writes wait as long for
  their row lock and flush as the service measured (synchronous_commit on minus off, from the
  medians: a mean takes in Node's stalls). On the trial's passes PostgreSQL's flush was 3% to
  16% longer than the fitted one (hot-row 0.79 against 0.73 ms), so kept runs score slightly
  better than calibrations with the statistics will. The model's commits per flush are kept
  next to PostgreSQL's in `calibration.json` (both about 1.0 at every top pass).
- **Calibration reads PostgreSQL's own statistics** (`compose.yaml` loads `pg_stat_statements`
  and sets `track_wal_io_timing`; `init.sql` creates the extension): each pass records the
  statements' executor time, the WAL flushes and their time, commits, the backends' active
  time and each kind of PostgreSQL process's CPU (`pg_begin`, `pg_end`). Statements are matched
  by what they do, not their text, so the held-out services' drivers are read too.
- **Trial** (`benchmarks/results/trials/2026-10-31-*-w22/`, development data): the UPDATE takes
  0.03 to 0.06 ms in the executor and its flush 0.79 ms, while PostgreSQL's CPU per UPDATE is
  0.19 to 0.23 ms; the flush falls with load (1.6 ms at 50 req/s, 1.06 at 196, 0.79 from about
  400); Kafka's INSERTs share flushes as load rises (1.5 commits per flush at 1,200 req/s).
- Not improved: Kafka's consumer cap (1,383 → 1,404 messages/s against about 1,650 measured;
  W23's fetch cycle), pg-mixed's +24% (the model's knee is Node's CPU, W21), hot-row's lock
  still held for PostgreSQL's own protocol work (about 0.15 ms per UPDATE: the engine has no
  server time outside a row lock), and a flush and CPU per query that fall with load.
- Hyperthreads (W21's trial: at 4,455 req/s PostgreSQL used 1.0 of its two CPUs, hyperthread
  siblings, at 0.257 ms per query against 0.162 at 3,712): giving the model's PostgreSQL its one
  physical core instead of two CPUs moves pg-mixed's p95 by 0.07 to 0.5 ms at 3,712 to 4,950
  req/s and not its knee, which is Node's CPU in the model (0.90 cores at 4,455, 1.00 at 4,950).
  How vCPUs that are hyperthreads become capacity is an engine question for services and
  databases alike (the held-out services also run on one sibling pair), so it is left to a
  workstream of its own.

**Baseline campaign under protocol v5, and disturbed runs (2026-10-31; accuracy plan).**

- Three runs of each of the four experiments with engine 0.14.0 (tag `bench-baseline-0.14.0`),
  Windows on Best performance, held-out experiments sealed. Median knee error over three counted
  runs: Kafka +4%, pg-hot-row -16%, pg-mixed +24%, cache-aside -34%. pg-mixed and Kafka meet
  criteria 1, 2 and 3a (3b needs a second machine). The measured knee moved less between runs
  than the error in every experiment (pg-mixed 3,783 to 4,160 req/s).
- `report.py` leaves out disturbed runs: a ladder that misses the objective at one step and meets
  it at a higher one (a stable stack does not get faster with more load). Added after pg-mixed's
  first two campaign runs stalled at 2,112 and 2,550 req/s and then met the objective at 3,169
  and 5,100; pg-mixed ran twice more. Disturbed runs stay in the history (/accuracy marks them
  "not counted"); the rule also flags six of the 14 earlier runs.
- `environment.json` records Windows' power mode (`run.power_mode()`); runs under different
  modes are not compared. Best performance moved pg-mixed's knee from 2,430 to about 4,000 req/s
  and cache-aside's from 4,848 to about 7,000. The campaign's 14 v5 runs had it filled in
  afterwards, with a note saying when it was checked.
- `run.rederive_inputs` now re-derives a v5 calibration's replicate sets and takes their medians,
  as `calibrate()` does, so `run.py resimulate` reproduces v5 runs too (before, it used the
  first replicate set only). All 24 kept runs calibrated under v3, v4 or v5 now re-simulate
  exactly (inputs and every step), and `docs/validation/dev-checks.md` leaves disturbed runs
  out of its medians: engine 0.14.0 is 24% off (median absolute knee error) over the 19 counted
  runs.
- Findings for W21 to W24: v5 gets the Node.js services' CPU within 4% to 34% (v4: 32% to 116%);
  pg-mixed's p95 rises well before its core fills (8.7 ms at 3,712 req/s against 1.8 predicted);
  the predicted range held the measured knee in 1 of 12 runs (nominal 50%), because calibration
  moves more between runs than between replicates; criterion 2 (p95 within ±100%) can never fail
  an under-prediction, which is how pg-mixed and Kafka pass it.

**Held-out experiments, sealed (2026-10-30; accuracy plan W19).**

- Five experiments ArchSim was never tuned on, registered in `ce2d109`: java-mixed (Spring Boot
  on Tomcat, HikariCP), go-mixed (net/http, pgx), python-mixed (Flask on 10 gunicorn sync
  workers), chain (Node.js gateway → API → PostgreSQL) and cache-ttl (5 s TTL, Zipf 1.1 over
  1M keys: 0.8 would give only a 9-24% hit ratio).
- Sealed: `run.py` calibrates or measures them only with an engine frozen in
  `benchmarks/engines.json` (none yet) and a clean tree; `run.py smoke` (20 req/s, keeps nothing)
  passes on all five. `report.py` labels each engine and experiment held-out or tuned-on.
- Java and Go GC pauses are calibrated from the runtimes' counters; no container shares a
  physical core with k6 or another (chain and cache-ttl give k6 one core).
- 31 Oct, before any measurement: go-mixed's pgx 5.7.6 -> 5.9.2 (drops `golang.org/x/crypto`)
  and python-mixed's Flask 3.1.2 -> 3.1.3, clearing GitHub's 19 dependency alerts, none of which
  was reachable (docs/validation.md, "Held-out experiments"). The staircase caps the
  service under test (listed first in each stack) by its CPUs, and chain by its busier hop.

**Calibration protocol v5: staircase, replicates, predicted ranges (2026-10-30; accuracy plan W20).**

- Calibration measures 20 s at rest, then climbs a staircase from 50 req/s (each pass at half the
  capacity the last implies, at most 35% of the service's core, stopping once p95 passes 10 ms),
  comes down again and repeats the top rate: every rate twice, the top three times. Each top
  pass gives a replicate set of inputs (CPU per request net of idle; a pass still disturbed
  after its retry is replaced); each input comes whole from its median replicate. `cpuByLoad`
  keeps every rate's CPU per request for the CPU-by-load model (W21). v4 calibrations keep
  their formulas, so every kept run still re-simulates exactly.
- Each replicate's knee is predicted too: `kneeRange` (nominal coverage (R - 1) / (R + 1)) and
  `containsMeasured` in the comparison. `run.py --runs N` runs each experiment N times, each on
  a fresh stack, rotating between experiments.
- report.py judges criteria 1, 2 and 3a over the runs that repeat the latest (same protocol,
  machine, OS build and engine), at least 3 of them: the median knee error within ±25%, p95 and
  stability in 2 of 3 runs. A new "Runs repeating the latest" table, and on /accuracy the
  predicted range on each knee chart and the repeat runs in "Run to run".
- A trial calibration of pg-mixed on the real stack (`benchmarks/results/trials/`) measured the
  service at 0.23 ms per request at 1,193 req/s (v4 read 0.45): with it, engine 0.14.0 predicts a
  knee of 4,375 req/s against about 2,430 measured, so the other limits (tail latency, GC,
  PostgreSQL's real cores) are what W21 to W24 must model.

**Benchmark harness, environment check and provenance (2026-10-30; accuracy plan W18).**

- `run.py resimulate` derives every kept run's inputs again from its calibration passes and
  simulates its measured steps with today's engine (`results/dev/<engine>/`); `report.py --dev`
  writes `docs/validation/dev-checks.md`. In-sample, never on the validation page or /accuracy.
  With engine 0.14.0 it reproduces every run calibrated under v3 or v4 exactly, inputs and
  latency at every step (including predictions first made with engine 0.7.0); the median
  absolute knee error is 29%.
- `model_inputs()` and `design()` are split into one function per part (service, queue,
  database, cache), so the model changes that follow edit separate functions; `simulate()` also
  reports the database's commits per flush, lock wait and commit wait.
- `run.py envcheck` characterises the machine: CPU siblings inside Docker's VM, steal, and CPU
  per request and p95 across 50 to 1,600 req/s in two layouts. On the laptop (30 Oct) the
  service's CPU per request fell from 0.96 ms at 50 req/s to 0.21 ms at 1,600, and PostgreSQL's
  from 0.93 to 0.19, settling only from about 800 req/s: the calibration passes sit where the
  cost is highest. k6 on the service's hyperthread sibling cost 10-15% more CPU per request, so
  the runner now leaves that sibling idle. Each run's `environment.json` records its topology.
- `predictions.json` records its provenance (commit, engine and harness git object ids, a dirty
  flag, `machineId`); its sha256 is logged and stored in `measured.json` before the first step.

**Measured CPU and memory inputs (2026-10-30, engine 0.14.0, schema 1.5; sizing plan W17).**

- Services get `cpuShare` (the share of service time that needs a core, default 1) and
  `memoryPerRpsMb` (memory that follows the request rate, default 0; a no-op migration).
  Garbage-collected runtimes hold the garbage made between collections, so their memory follows
  the rate: it counts in the memory peak and in sizing, not in the out-of-memory limit.
- Calibration fits them: `cpuPerRequest` sets `cpuShare`; `memoryWorkingSet` sets memory at
  idle, per req/s and per request held (a term not fitted is set to zero); a size gauge with a
  `unit` sets `workingSetGib`; traces set `cpuShare` where spans record CPU time. The replay
  check also compares each fitted part's CPU and memory, and `cpuShare` counts as a primary
  measured input in confidence. `docs/calibration.md` has a "CPU and memory" section with
  example PromQL.
- On pg-mixed run 5 (`benchmarks/tests/test_resource_fit.py`), the Node.js service's CPU at the
  knee goes from 1.00 to 0.52 cores (measured 0.46) and its memory from 550 to 182 MiB (measured
  188); simulated latency is unchanged. CPU per request falling with load is a known limit
  (below) that calibration reports when the quiet and busy quarters of the range differ by more
  than 25%.

**Benchmark run 6 (2026-10-30).** pg-hot-row, cache-aside and Kafka measured again with CPU and
memory read inside the window, on the same laptop after a Windows update (build 26300, new WSL
kernel) that made the stacks faster. Knees measured / predicted: pg-hot-row 814 / 519 (-36%),
cache-aside 4,848 / 2,352 (-51%), Kafka 2,040 / 1,602 (-21%); with pg-mixed's -8% (run 5), all
four under-predict capacity, and for three of them the predicted knee is one core divided by the
calibrated CPU per request. The report now counts a machine by its CPU (an OS update is not a
second machine), keeps the knee spread to runs on one OS build, and compares CPU and memory up
to both knees (past the model's own knee its queue holds every request).

**Sizing and pricing that match reality (2026-10-28, engine 0.13.0, schema 1.4; sizing plan W14).**

- New AWS snapshot `aws-us-east-1-2026-10-28` with burstable baselines, surplus-credit prices
  and managed-service rates; 2026-10-27 is kept unchanged (stored runs pin its digest).
- Each part reports what it needs beside the instance it gets (`sizing.need`/`needText`), shown
  in the inspector and on node cards: TTL cache hit ratios, a database CPU cap from its
  connections, database memory from its hot data, Lambda's 1,769 MB per vCPU, Kubernetes
  millicore requests.
- Optional `instanceType` (schema 1.4), so re-running a run's input reproduces its instance
  types and cost. Burstable t4g for light parts with a CPU-credit check and
  `pricing.burstable: auto|never`; the `families` bug is fixed.
- Managed and per-request pricing at the steady base load (`base_rps`): DynamoDB, API Gateway,
  ALB capacity units, SQS x3, RDS storage, implied Kubernetes nodes plus the control plane; the
  cost panel shows units and unit prices.
- Fixed the "86,545 workers" finding and the instant estimate's API gateway (rate-limit use is
  reported apart from busy).
- `scripts/sizing_audit.py` and `test_sizing_audit.py` check the acceptance criteria on all 25
  templates. Starter $449 -> $334/month; retry storm $2,587 -> $992; serverless $1,771 -> $2,006
  (API Gateway now priced; Lambda and DynamoDB down).

**CPU and memory against real stacks (2026-10-28; sizing plan W16).**

- The benchmark runner reads each container's CPU time and working set (cgroup v2: memory less
  reclaimable page cache) every 5 s inside the window k6 measures (`ResourceSampler`; from the
  host on Linux, through `docker exec` on Docker Desktop). The benchmark designs give the service
  and Redis a memory size that never binds, so the model reports the memory it predicts;
  latency predictions are unchanged. `comparison.json` gains measured against predicted busy
  cores and memory per part at every step, and median errors up to the measured knee.
- Run 4's results recorded CPU only over the whole k6 command (4% to 67% longer than the load):
  they are scaled to the window by the service's own CPU counter, and their predicted CPU was
  simulated again from the stored calibration (`run.py resources`, `resources-predicted.json`;
  `predictions.json` untouched).
- `docs/validation.md` has a generated "Resources: CPU and memory" table and notes; `/accuracy`
  has a "CPU and memory" section (a card per experiment, every step, what is not modelled) and a
  line in the short version. Up to the knee, the predicted CPU was off by a median of 8%
  (PostgreSQL, mixed) to 88% (Redis): too high for the Node.js services (37% to 75%), too low
  for Redis (command time only). Memory was measured in runs 5 and 6 (above): ArchSim's
  service memory was 2x to 9x the real working set before W17's fitted inputs.

**Seed ranges and quick checks (2026-10-27, migration 0006, report 1.14; inspection report C-1, H-2 part).**

- `RunCreate.seeds` (5 hosted, 10 locally) runs one input with seeds base ... base + N - 1 as a
  run group (`run_groups`; runs carry `group_id`, `seed_index` and `mode`). `GET
  /run-groups/{id}` returns the runs and the engine's `summarize_seeds`: the verdict split and
  p50/p95/p99, success and throughput ranges with medians. `run_simulation(seed=...)` makes
  reruns reproduce exactly.
- Results reads "Meets the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds
  42–46", shows an amber Borderline and caps confidence at medium when runs disagree, and offers
  "Check 5 seeds" when a single run's p95 is within 15% of its objective. The Markdown report
  carries the range.
- `RunCreate.mode = full|sampled`, stored on the run (reruns match). The Simulate menu's "Quick
  check (estimated)" is enabled only when `/analyze` `quickCheck` says the design qualifies,
  and says why otherwise. Sampled reports are labelled "Estimated" everywhere and charts say they
  cover only the sample; a quick check whose sample is not steady runs in full and the report
  says so (`simulationMode.requestedMode`), hence report 1.14.

**Share links and phones (2026-10-27, migration 0007; inspection report C-2 part, H-6).**

- Frozen share links (`shared_reports`): a run's report without traces (calibration addresses
  hidden), its objectives and project name, behind a random 128-bit link that editors make and
  revoke, viewers list, and anyone opens without signing in. Revoked or expired links answer
  404 at once; public answers are rate-limited per address, never cached and not indexed.
  Making and revoking a link is recorded in the project's history.
- `/r/<slug>`: server-rendered, phone-first: the verdict with confidence, key numbers,
  objectives, findings, latency and error budget, link-preview text and image (`next/og`), and
  "Open a copy" into your workspace. Results has a Share dialog; the editor's phone screen
  offers "View latest results". Report pieces live in `components/report-view/`.

**Comments, review sign-off and history (2026-10-27, migration 0008; inspection report C-3).**

- Comments anchored to a part (node or link), the project or a run, in threads anyone in the
  workspace can reply to and resolve; comments whose part or run was deleted stay, shown as
  detached. Viewers comment; authors edit and delete their own; owners may delete any;
  read-only tokens only read.
- Review sign-off on a finished run (approve or request changes, with a note), recording the
  project revision and the run's digests; it goes stale once the design is saved again.
- `audit_events`, written in the same transaction as each change: project edits, runs, share
  links, comments, sign-offs, members, invitations and workspaces; project and workspace
  activity endpoints, paged, IP addresses for owners only.
- Web: comment badges on parts and links, a Comments section in the side panel, an Activity
  drawer, a Review panel on Results and a review badge on run rows; polls every 10 s. Works as
  the single local user without sign-in. Not yet shown on share pages.

**Sign-in, workspaces and security (2026-10-27, migrations 0004-0005; inspection report C-3 part, M-4).**

- `ARCHSIM_AUTH_MODE=none|oauth`. `none` keeps today's local, single-user use: every request
  is the owner of the `default` workspace. `oauth` adds GitHub and Google sign-in in the API
  (PKCE, Authlib), database sessions in the `__Host-archsim_session` cookie, and a CSRF header
  (`X-ArchSim-CSRF`) on changes made with the cookie. The first sign-in matching
  `ARCHSIM_ADMIN_EMAILS` owns `default`.
- Migrations 0004-0005: users, identities, workspaces (personal, team, sandbox), memberships
  (owner, editor, viewer), invitations, sessions, hashed `asim_pat_` personal tokens (read or
  write, optionally one workspace) and `rate_limits`. Projects and runs carry `workspace_id` and
  `created_by`, backfilled into `default`.
- Every router is scoped by workspace: non-members get 404, viewers get 403 when they change
  anything. Opening a template no longer hands one visitor another visitor's copy
  (`reuseUnedited` is scoped to the workspace and creator).
- Limits by workspace kind: runs at once (the queue enforces them and shares workers fairly),
  runs per hour (429 with `Retry-After`), the largest run, and a 20 s hosted calibration replay.
- `ARCHSIM_OUTBOUND`: hosted calibration calls only allowlisted hosts on public addresses, with
  the connection pinned to the checked address and TLS still verified; sandbox workspaces never
  call out. The Prometheus client no longer fails when a server closes the connection right
  after replying. The API sends security headers.
- The web app has sign-in, settings (members, invitations, tokens), invitation pages, a user
  menu and a workspace switcher; the proxy forwards cookies, tokens, the CSRF header and the
  client address. Tested against a stand-in OAuth provider (`services/api/tests/fake_oauth.py`)
  and on SQLite and PostgreSQL. Not tried yet with real GitHub or Google apps; the hosted compose
  stack and deploy guide are W11.

**Confidence and instant estimate (2026-10-27, report 1.13; inspection report C-1 and H-2, part).**

- Inputs equal to a vendor-documented technology or preset value are classified `documented`
  and cite their source (derived; schema 1.3 unchanged). `report.confidence` and
  `analysis.confidence` give high, medium or low with the reason that decided it, the inputs to
  replace first and how to raise it. Parts are weighted by their share of latency in runs and
  by estimated utilisation in analysis. The starter is low ("31 of 35 inputs are illustrative
  defaults"); the queueing lab with every input calibrated is high. There is no numeric score.
- `analysis.estimate` and `report.estimate` come from queueing formulas (M/M/c waits, path
  latency, a rough p95, the saturation point) in about 1 ms. They are within 7% of the
  simulation on the starter and the queueing lab (tests allow 15%); burst templates are
  evaluated at their peak. A TTL cache used cache-aside counts its lookups as load even
  while its hit ratio depends on the run (only the write-backs wait for it), so its hit ratio
  can be estimated: the retry-storm template's Redis comes out at 73%.
- Results shows the confidence badge, its reason, "how to raise it" (opens the inputs) and a
  link to the measured accuracy on every verdict, and the estimate while a run is queued or
  running, then estimate against simulation. The Markdown report, CLI, CI-gate comment and MCP
  tools carry confidence too. A compiled core is out of scope (docs/performance.md).

**Public accuracy page (2026-10-27; inspection report C-1, part).**

- `benchmarks/report.py --json` writes `docs/validation/accuracy.json` and an identical copy in
  `apps/web/src/data/` (the web image only copies `apps/web`). It holds each experiment's knee
  error, p95 error below the knee, proposed tier, run-to-run spread of the measured knee on the
  same machine, date, engine and environment, plus a summary and the run history, from the same
  helpers as `docs/validation.md`; tests check the JSON is fresh, the copies match and it agrees
  with the doc's tables.
- `/accuracy` is a static page built from it: good and bad results in plain words, a knee chart
  per experiment with the range over runs, how to read the numbers, every run with its date and
  machine, and how to calibrate your own design. It links nowhere outside the site (the
  repository is private). The landing page and the confidence badge link to it.
- `run.py --archive` keeps previous results in `run-N/`; `.github/workflows/benchmark.yml` runs
  weekly, on `v*` tags and by hand, and opens a pull request for a person to merge. The
  automatic triggers sit in a marked block that can be switched off. Not run yet (never on
  Linux): the first run should be a manual dispatch.

**Template audit (2026-10-27, engine 0.12.0; inspection report H-4).**

- All 10 templates audited and simulated with every scenario; `docs/templates-review.md`
  records the checks, changes and results, with unsigned "Reviewed by" lines for outside
  reviewers (who reply to whoever shared it). `reviewedBy` in `templates-meta.ts` shows on the
  card once signed.
- Redis is cache-aside in every template (read-through remained in Black Friday, MongoDB,
  pods-pending and retry storm). The Kafka, MongoDB and serverless templates now show their
  stories (before: the API saturated, consumers could never keep up, the table never
  throttled). Black Friday's payment timeouts and replica replay, the shared pool's Search CPU
  and the queueing lab's sizing are fixed, and copy and insights match the runs.
- A chaos pad stays lit after a reload (`presetActive` compares only the fields the pad sets).
  A planned outage is no longer named the bottleneck; its finding says what the part refused
  and how many requests failed there. The hot-shard finding ignores scattered searches and
  estimates CPU; database CPU capacity counts read-serving instances. Node status changes for
  runs with an outage, hence engine 0.12.0.
- Follow-ups: DynamoDB capacity-unit pricing (the serverless table is priced as an instance),
  consistency-level CPU, rate limiting reported as a symptom, and the `docs/performance.md`
  template table.

**Real prices and shapes (2026-10-27, engine 0.11.0; inspection report H-3, L-2).**

- ArchSim ships AWS us-east-1 on-demand list prices:
  `packages/engine/src/archsim_engine/pricing/aws-us-east-1-2026-10-27.json` (about 270 KB, in
  the wheel), read from the public AWS Price List bulk API by `scripts/prices/aws.py`: 1,107 EC2,
  315 RDS, 188 ElastiCache and 16 MSK types, plus EBS gp3, Lambda, SQS and ALB rates. The first
  run against the live lists found what the hand-written samples could not: a region's file
  also lists Outposts prices (cache.m5.large Redis at $0.06 next to the regional $0.156, and
  snapping picks the cheapest), Capacity Blocks, extended-support surcharges and an ALB trust
  store row; Lambda's and SQS's rates depended on row order or took the high-volume tier; MSK's
  file names brokers by compute family with the unit "hours", so no broker was read. The script
  now keeps only the region's own current-generation on-demand rows, stores a type once per
  price (engines merged) and stops on a type with two prices. Source, offer file versions,
  AWS's disclaimer and hand-checked prices are in `docs/pricing.md`.
- Burstable types (t families) are marked in snapshots and never picked by snapping unless the
  design names their family: they sustain a fraction of their vCPUs, and auto-sizing aims at 60%
  CPU on full cores.
- Templates and new projects (`blank_document()` in the API) pin
  `{provider: aws, region: us-east-1, snapshotDate}`; the schema default stays `generic`, so
  saved documents keep their prices and results. The starter now runs on c6g.medium (load
  balancer), 2 × c6g.xlarge (API), cache.m6g.large (Redis) and db.m6g.large (PostgreSQL):
  $449 a month, every line citing its SKU and the snapshot date.
- Without a snapshot, auto-sizing rounds CPU and memory up together to the cheapest standard
  shape of 2, 4 or 8 GiB per vCPU: the starter's API Servers are 4 vCPU and 8 GiB, not 4 vCPU
  and 0.5 GiB. Pods and serverless functions keep their computed size.
- Every dollar figure names its source ("AWS us-east-1 · Oct 2026", "illustrative rates",
  "+ illustrative" when some parts are not in the snapshot): the Results "per month" stat and
  cost panel (which also tags user and default prices per line), the Compare map (once, or per
  row when runs differ), the dashboard's cost stat and the Markdown report.
- `.github/workflows/prices.yml` rebuilds the snapshot on the 2nd of each month and opens a pull
  request with a price-diff summary (`scripts/prices/diff.py`); none when no price changed.
- Engine 0.11.0: sizes change for any design with a snapshot or with odd generic shapes, so
  results do too. The starter's CI-gate snapshot, the web fixtures and the pricing tests were
  regenerated. The `docs/performance.md` tables predate this and were not re-measured.

**End-to-end tests (2026-10-27).**

- A Playwright test (`apps/web/e2e/main-path.spec.ts`, `npm run e2e`) drives the main path in
  Chromium against a production build and the real API on a throwaway SQLite database: open
  the cache-aside starter from the gallery, simulate, read the verdict, hit the "Lose Redis
  Cache" chaos pad (which plans the outage and simulates again), compare the two runs, save,
  reload, and check the outage is still planned and stored. It also fails on any Content
  Security Policy violation in the console. The templates are shortened to 20 s of simulated
  time with their bursts and faults moved to the same fraction of the run, so the test takes
  about 20 s. CI runs it in an `e2e` job and keeps the trace when it fails.
- Every template in `examples/` is simulated (`test_templates_simulate.py`, 8 s with a 2 s
  warm-up and the timeline squeezed to fit): each finishes with balanced accounting for the
  clients and every part, and a non-empty report. Before, only the starter was ever simulated.
- `npm run e2e:capture` (`e2e/capture.spec.ts`, not part of the default run) retakes the
  README screenshots into `docs/images/` and records the starter's cache-outage demo as
  `docs/images/cache-outage.gif` (ffmpeg converts the video; without it the `.webm` is kept and
  the command printed).

**Durable job queue (2026-10-27, migration 0003).** Closes M-3 of the product review.

- Runs are queued in the database: `simulation_runs` gains `attempts`, `lease_owner`,
  `lease_expires_at` and `cancel_requested_at`, and `job_workers` records each worker's
  heartbeat (`jobs/queue.py`). Workers claim the oldest queued runs under a 30 s lease
  (PostgreSQL: `FOR UPDATE SKIP LOCKED` in a CTE evaluated once; SQLite: `BEGIN IMMEDIATE`, one
  host) and renew it every 2 s. Progress and outcomes are written only by the lease holder, so a
  worker that lost a run cannot overwrite the one that took it over (`test_api_queue.py`,
  SQLite and PostgreSQL, including 4 threads claiming 24 runs: each taken once).
- Runs survive restarts: an API or worker that stops (Ctrl+C, SIGTERM) requeues its runs without
  counting the attempt; one that dies leaves them to be requeued when the lease runs out (reaped
  every 10 s and at start-up), then failed after a second attempt, or cancelled if a cancel was
  requested. This replaces marking every active run failed at start-up, which broke the runs of
  other instances sharing the database.
- One `spawn` process per run with a pipe each way, replacing the shared process pool and the
  multiprocessing manager: a process that crashes fails only its own run, the others carry on
  (tested by killing one of two). One process is started ahead, so a run does not wait about a
  second for the engine to import on Windows. A cancelled run still going 10 s later is
  terminated, then killed; any run is killed 30 s past `ARCHSIM_MAX_WALL_SECONDS`.
- Cancel works from any instance: it sets `cancel_requested_at`, and the worker holding the run
  stops it at its next heartbeat (tested with two apps sharing one database, on SQLite and
  PostgreSQL).
- No orphans: a simulation process exits when its worker's pipe closes, gets SIGKILL when its
  worker dies on Linux (`PR_SET_PDEATHSIG`), and sits in a Windows Job Object with
  kill-on-close. Tests kill a worker process outright and check its simulation process is gone,
  and interrupt a worker's whole process group (Ctrl+Break on Windows, SIGINT elsewhere) and
  check the run is given back rather than failed. A new CI job runs the API tests on Windows.
- `archsim-api worker --slots N` runs extra workers on any host; `archsim-api serve` keeps one
  inside by default (`ARCHSIM_EMBEDDED_WORKER`), so the one-command local setup is unchanged.
  Default slots: CPU count - 1. The API reads progress from the database only.
- Migrations take a PostgreSQL advisory lock in one transaction, so instances starting together
  take turns (tested with three waiting on the lock); `archsim-api migrate` and a one-shot
  Compose `migrate` service run them once. Compose runs the API under `init: true` and has an
  optional `worker` service (`--profile workers`).

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

**W1 · UI polish (2026-10-27, plan for the inspection report).**

- Labs: experimental technologies are offered only with "Labs parts" on (More menu, the
  command palette, or the parts panel's footer; stored as `archsim-labs`, off by default). The
  parts panel, the command palette's "Add technology" and the inspector's technology picker
  filter them (`offeredWithLabs` in `lib/tier.ts`); a search that only matches Labs parts offers
  to show them. Parts already on the canvas keep their technology (the picker still lists it),
  and templates that use experimental parts open as before, with a "Labs" badge on their card
  (`experimentalParts`).
- Part cards at small zoom: the canvas marks its zoom band (`data-zoom` near/mid/far). Below
  0.75 secondary text uses `--ink-2`; below 0.5 the fact rows and resource line hide, leaving
  name, technology and status. Dark `--ink-3` is now `#96969e` (4.87:1 on `--surface-2`, was
  4.40).
- Small screens: the landing page, projects page and templates gallery have a menu button below
  their breakpoint (`components/ui/MobileNav.tsx`), and the landing footer links Templates and
  Projects. Below 768 px the editor shows "Continue on a larger screen" (copy link, open anyway,
  back to projects). Below 1280 px the top bar moves Save and the theme switch into the More
  menu and narrows the project name, so nothing overlaps (checked at 800 and 1024 px).

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

**Engine** (`packages/engine`, 187 tests; the Python engine, deleted at stage 5 of the Java port)

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
- Background execution through a queue in the database (see "Durable job queue" above);
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
  the October 2026 roadmap). The static load estimate, auto-sizing, the cost estimate and the
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
- Accuracy benchmarks (2026-10-26; Phase 2 of the October 2026 roadmap). `benchmarks/`
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
  the October 2026 roadmap). Every technology profile has a tier: core (the roadmap's
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
- Performance (report 1.11, 2026-10-26; Phase 8 of the October 2026 roadmap). Measured first
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
- An SLO gate in CI (2026-10-26; Phase 7 of the October 2026 roadmap). `archsim check
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
  Phase 6 of the October 2026 roadmap). A design names its price source in `pricing`
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
  the October 2026 roadmap). `archsim import compose|k8s|terraform FILE -o design.json
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
- MCP server for AI coding agents (2026-10-26; Phase 4 of the October 2026 roadmap).
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
  the October 2026 roadmap). Schema 1.1 adds `provenance` (per input: illustrative, assumed or
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

Durable job queue, on 2026-10-27, on Windows 11 with real `archsim-api serve` processes and in
Docker:
- Two `serve` processes on one PostgreSQL 16, one without a worker: a run queued through the
  second and simulated by the first was cancelled through the second; the first stopped it
  1.4 s later.
- Killing one of two simulation processes (`taskkill /F`) failed only that run
  ("The simulation worker stopped unexpectedly…"); the other kept running and then cancelled.
- A run in flight when the API was stopped with Ctrl+Break was requeued and succeeded after a
  restart. After `taskkill /F` of the API mid-run, `tasklist` showed none of its three
  simulation processes (two runs and the spare); after a restart the run was requeued once its
  lease ran out and simulated again.
- Compose (`migrate`, the API under `init: true` with one slot, `--scale worker=2`, a 6 s
  lease): `migrate` exited 0 before the API started; three runs spread over the three workers;
  `docker kill` of a worker requeued its run when the lease ran out; a cancel through the API
  stopped a run held by another worker, whose free slot then took the requeued run (attempt
  2); `docker compose stop worker` gave its run back in 4.8 s.

CI on GitHub, on 2026-10-25, in the private repository `dante0747/archsim`: the first run
(commit `dc251a6`) passed all three jobs: Python engine + API on SQLite (ruff, format check,
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

- Java 25 reads text by Unicode 16.0, where the frozen engine read it by Python 3.12's Unicode
  15.0: 80 more characters are digits (Garay, Gurung Khema, Kirat Rai, Sunuwar, Ol Onal, the
  Myanmar Extended-C digits and the outlined digits), 4,924 more are letters and 54 change case
  differently. So `int()` and `float()` read those digits where Python refused them, and names in
  those scripts match `\w` and change case where Python's did not. `repr()` already follows
  Python's own table. Matching would take Python's Unicode 15.0 tables for digits, letters and
  case; only text in scripts encoded in 2023 and 2024 is affected.
- A service's CPU per request follows its calibrated load curve (`serviceTimeByLoad`) only
  inside the measured range: above it the last point's cost holds, although real stacks often
  keep getting cheaper, so calibrate up to the load you size for
  ([docs/simulation-model.md](docs/simulation-model.md)). A part without that setting keeps one
  CPU cost per request, weighted to the busiest times, and calibration notes when that cost
  changes with load. In the benchmarks this is why cache-aside's knee is predicted 23% low
  (median, tuned on), and pg-mixed's latency climbs before its core fills (+16%); see
  [Where the model is wrong, and why](docs/validation.md#where-the-model-is-wrong-and-why). The
  Kafka broker and its consumers have no CPU or memory in the model.
- The instant estimate spreads a sharded part's load evenly over its shards, a cache's slots and
  CPU as a database's: a hot key's shard fills before it says (in the hot-key feed's celebrity
  scenario it reads the cache's slots 49% busy, where the hot shard needs about 1.4 times what
  its one thread can do), and it has no row-lock wait (pg-hot-row). The sizing, the design
  review's CPU check and a host machine's speeds count a read-through cache's requests and not
  the commands that fill the keys it missed
  ([ADR-0092](docs/adr/0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md)).
- A vCPU counts as a hyperthread at a sibling speed of 0.6 by default (ADR-0091), a figure from
  vendors' documentation and others' benchmarks, mostly on hardware of 2002 to 2019, whose spread
  by workload (0.45 to 0.86) is wider than any default covers; the accuracy benchmarks cannot
  show it, since the laptop's Docker VM is scheduled by Windows and has no real pairs, and their
  designs stay on whole cores. Pods on Kubernetes count whole cores until a node is the machine,
  which is optimistic on an x86 node pool. A saved design stays on whole cores until its
  `architecture.siblingSpeed` is cleared. A design imported from a Terraform plan carries its
  `instanceType` but no `pricing`, so at generic prices its parts count pairs even on a Graviton
  type.
- Still open after the review fixes: sampled mode checks the
  2M request limit against the full run, so the CLI and MCP refuse the 6M-request design
  (`scripts/perf.py` ran it on the Python engine with the limit raised; it has not been run on
  the Java engine). A price someone types that equals a
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
- Only a core set of technologies is ArchSim's focus, and none is validated yet; the others are
  marked experimental. On one laptop, three to six runs per experiment, the median error in the
  predicted knee is -6% to +70% on the five held-out experiments, four of which over-predict
  capacity (java-mixed +70%, go-mixed +67%, chain +30%, python-mixed +29%; cache-ttl -6%), and
  -23% to +16% on the four tuned on (Kafka -3%, pg-hot-row -10%, pg-mixed +16%, cache-aside
  -23%). No experiment meets every proposed tier threshold: criterion 3b needs a second machine
  ([docs/validation.md](docs/validation.md)). [docs/assumptions.md](docs/assumptions.md) lists
  every illustrative number.
- Technology behaviour uses documented defaults where vendors publish them; GC pauses, JIT
  warm-up, per-runtime cold starts, replica replay cost and log flush times are illustrative
  typical values (labelled in the UI), not measurements. Row locks follow the workload's Zipf
  keyspace, so write contention depends on the key skew you assume.
- Dashboard replay redraws panels about every 60 ms; smoothness with very large designs was
  not measured in a visible window.
- Technology presets are illustrative starting points, not vendor benchmarks. Prices are AWS
  us-east-1 on-demand list prices for templates and new projects (other regions and providers
  have refresh commands but no shipped snapshot), and generic illustrative rates for documents
  without a snapshot; neither includes discounts, savings plans, data transfer, load balancer
  capacity units or multi-AZ pricing. See [docs/pricing.md](docs/pricing.md).
- The breaking-point sweep uses short steps: autoscaled services start each step at their
  configured replica count, so it measures capacity before the scaler reacts (the page says
  so).
- Replay resolution is the report bucket (1 s); playback is throttled by the browser when the
  tab is in the background.
- Heavy designs take a while: the frozen Python engine simulated about 2,000-5,000 requests per
  second per core on a laptop (the Black Friday template took 35-45 s; a 10k req/s, 10-minute
  design about 55 minutes), and the Java simulator, the only one now, is about 14 times faster on
  the templates (the 10k req/s design has not been timed in Java). Sampled mode answers long
  steady runs much faster but is opt-in. "Run all & compare" runs at most
  `ARCHSIM_MAX_CONCURRENT_RUNS` (default: the cores less one) simulations at a time per worker.
- A worker's runs are threads sharing its heap
  ([ADR 0053](docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)), so a run that
  exhausts the heap stops every run of its worker: the reaper requeues them once, then fails them. A run that ignores a
  cancel, or runs past its hard deadline, is written cancelled or failed but holds its slot until
  its thread ends. Under the Python workers a crash failed only its own run (2026-10-27).
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
- A run requeued after a restart starts again from the beginning (simulations have no
  checkpoints). Cancelling a run held by another instance takes up to one heartbeat (2 s).
  Workers poll the queue once a second, and hosts sharing PostgreSQL need synchronised clocks
  for leases.
- `throughputRps` counts by completion time and can be slightly above `offeredRps`.
- "Queue kept growing" needs at least 4 time buckets inside the measurement window.
- Sign-in is optional (`ARCHSIM_AUTH_MODE=oauth`, GitHub and Google); without it the app is
  single-user, so keep it bound to localhost. Real OAuth apps have not been tried yet.
- README screenshots and the demo GIF (`docs/images/cache-outage.gif`, 46 s, 7 MB) were
  retaken on 2026-11-14 with `npm run e2e:capture`, on engine 1.0.0 and the current logo;
  retake them after visible changes.
- The UI was checked by hand in one browser engine (Chromium in the desktop app): earlier looks
  at 1280x800 and 1440x900, the Studio look at 1440x900, 1100x760 and 375x812, in dark and
  light. The one end-to-end test covers the main path in Chromium at 1440x900; there are no
  visual-regression tests, and other paths (import, scenarios, breaking point, dashboard) are
  covered only by unit tests.
- After a reload, a chaos pad whose failure is planned is no longer lit: the API stores the
  fault with its default fields (`factor`, `extraMs`, `label`), and the pad compares the
  stored fault with its own patch exactly. The failure is still planned and simulated.
- At 1100 px wide with both side panels open, the Design review pill overlaps the canvas zoom
  toolbar (it did before the Studio look too).
- The canvas layout is usable at tablet widths, but the editor is designed for desktop
  screens.

## Next steps

1. The Java 25 port ([proposal](docs/proposals/java-25-port.md), ADRs 0005–0016): stages 0–7
   are built (engine 1.0.0; everything ArchSim ships, its benchmark harness and its tooling in
   Java, and no Python left but the benchmarked stacks and three trial records). Next, before
   anything is published, the owner's review: ADR 0063, sign-in on Spring Security (ADR 0065),
   and what `docs/proposals/java-25-port.md`'s "where the port ended" lists. The price refresh
   (archsim-tools' `prices`) matched `aws.py` on live offer files: on 14 Nov 2026 both wrote
   the same us-east-1 snapshot, byte for byte, from the same cached files. That snapshot was
   not taken. Its 70 extra entries for earlier database generations came from the RDS file's
   new rows for unused capacity reservations, which `prices aws` now skips on purpose, unlike
   `aws.py`: on that file it would add only the 9 new `db.r9g` types (ADR-0072's note).
2. W25 (accuracy over time on /accuracy). Engine 0.14.0 did not predict the held-out
   experiments before they were measured (the harness can't predict with a second engine), so a
   before-and-after on them can only re-simulate 0.14.0, labelled as such.
3. Decide the rest of the tier thresholds (the owner; a proposal is in docs/validation.md;
   criterion 2 is settled by ADR 0018), and rerun on a second machine (a quieter Linux host or the
   hosted-runner workflow, which has not completed a run yet) for criterion 3b.
4. Move to TypeScript 7 and ESLint 10 once typescript-eslint supports TypeScript 7 and
   eslint-config-next's plugins accept ESLint 10; `.github/dependabot.yml` ignores both until
   then (the rest of Dependabot's PR #4 is merged: React 19.3.1, simple-icons 16.35.0,
   jsdom 30, @types/node 26).

After the Java port (ADR 0017), so that none of it is written twice:

5. The model work the campaign points to, in order:
   - why the held-out stacks over-predict capacity: java-mixed +70%, go-mixed +67% and
     python-mixed +29% ran on the two hyperthreads of one core, which the model counts as two
     full cores; chain's +30% needs a cause of its own;
   - a CPU cost that rises near full load (pg-mixed), and how hyperthreaded vCPUs become
     capacity;
   - a CPU per request that keeps falling above the calibration staircase: since 20 Nov 2026 the
     benchmark harness fits it as a law of the rate (ADR-0093; cache-aside -22% to -7% in-sample,
     the median of the 37 counted v6 runs' absolute knee errors 16.3% to 12.9%). The machine's
     own slowdown, which that exposes in cache-ttl, chain, Kafka and pg-mixed, is W27's host
     machine: with it every experiment is within 20% in-sample (median 11.9%), but only with
     the what-if fit's assumed k6 CPU per request and background. Measured on a quiet laptop on
     20 Nov 2026 (`contention --service-only`: k6 0.32 ms per request, 0.95 threads busy at rest),
     the host machine over-corrects (median 16.2%, all but python-mixed predicted low;
     `results/dev/1.6.0-law-host-measured/`): the probe of 1 Nov, taken while the laptop
     throttled, gives a clock that falls faster with busy threads than the check of 20 Nov read
     (4,040 MHz with 3.8 threads busy, where its line gives about 3,600). A full contention check
     the same evening refitted it (clock 0.805 where it was 0.729, sibling 0.47, 0.90 threads at
     rest, k6 0.33 ms): with it the median is 14.5% (`1.6.0-law-host-refit`), every experiment but
     python-mixed (+16%) predicted low, by up to 29%, where the law alone over-promises by up to
     72%. Since 20 Nov 2026 the benchmark designs run on it (ADR-0095; `BENCH_HOST=off` for
     none), and a freeze names its fit (`hostFit`), which the seal of the held-out experiments
     checks;
   - the predicted range: refitted on 20 Nov 2026 on all nine experiments engine 1.6.0 is
     tuned on, with an error each experiment's runs share (ADR-0094), then on the host
     machine's knees (ADR-0095; leaving out each experiment it holds 37 of 49 runs, ten of the
     twelve misses above it). Still open: ranking
     replicates by mean, and an ensemble of simulations for the calibration part;
   - a CPU model for the Kafka broker and consumers.
6. Right-size from a run (sizing plan W15): suggest cheaper instance types from the measured CPU
   and memory, apply with one click, and verify over 5 seeds.
7. Total client deadline across retries, retry budgets / circuit breaker component, and
   parallel fan-out calls.
8. Circuit breakers and retry budgets as link settings, random failures (MTBF/MTTR), health
   checks and failover, multi-region latency.
9. Calibrate against a live Prometheus and a real OpenTelemetry export, export of the
   dashboard to real Grafana, and an agent/MCP surface on top of the validated run API (see
   [docs/roadmap.md](docs/roadmap.md)).
10. More technology behaviour: deadlocks and range locks, vacuum/compaction pauses, consumer
   group rebalancing, adaptive capacity, per-key hot-row splitting.

## Bugs per week

Every bug is a GitHub issue with the `bug` label (the bug report form adds it; see
CONTRIBUTING.md). Each Monday, add a row for the week before (ISO weeks, Monday to Sunday):

```bash
gh issue list --label bug --state all --search "created:2026-10-26..2026-11-01" --limit 1000 --json number --jq length
gh issue list --label bug --state closed --search "closed:2026-10-26..2026-11-01" --limit 1000 --json number --jq length
gh issue list --label bug --state open --limit 1000 --json number --jq length
```

| Week | Opened | Closed | Open at the end |
|---|---|---|---|
| 2026-W44 (Oct 26 – Nov 1, so far) | 0 | 0 | 0 |
