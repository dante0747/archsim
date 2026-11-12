# Architecture Decision Records

An Architecture Decision Record (ADR) is a short document that records one significant decision:
the problem, the options considered, the option chosen and why, and what follows from it.
ArchSim's ADRs use the [MADR 4](https://adr.github.io/madr/) format. Read them in order to see
why the project looks the way it does.

## When to write one

Write an ADR when a decision:

- is hard or costly to reverse (a language, framework, database or module boundary);
- changes a contract other parts rely on (the design document, the HTTP API, the result digests);
- chooses between real alternatives that a later contributor might reasonably reopen;
- changes how ArchSim's accuracy is measured or judged.

Routine choices that follow existing conventions (a library update, a new endpoint in an existing
feature) don't need one.

## How to add one

1. Copy [`template.md`](template.md) to `NNNN-short-kebab-title.md`, using the next free number.
2. Fill it in with `status: proposed` while it's being discussed. Keep it to about one screen.
3. Put it in the same change as the work it governs, so the code and its reasons land together.
4. Once the owner accepts it, set `status: accepted` and the date, and add a row to the index below.

Accepted ADRs are not rewritten. Typos and broken links may be fixed. To change a decision, write
a new ADR and mark the old one `superseded by ADR-NNNN`.

## Status lifecycle

```
proposed ──► accepted ──► superseded by ADR-NNNN
    │            └──────► deprecated  (no longer applies, nothing replaces it)
    └──► rejected
```

ADRs 0002 to 0004 record decisions made before ADRs were adopted. They say so, and they state only
what the repository shows.

## Index

| ADR | Title | Status | Date |
| --- | --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | 2026-11-02 |
| [0002](0002-design-document-is-the-contract.md) | The versioned design document is the contract between web app, API and engine | Accepted | 2026-10-24 |
| [0003](0003-postgresql-run-queue-with-leases.md) | Run simulations from a PostgreSQL queue with leases | Accepted | 2026-10-27 |
| [0004](0004-prove-accuracy-on-sealed-held-out-experiments.md) | Prove accuracy on held-out experiments, sealed until a frozen engine predicts them | Accepted | 2026-10-30 |
| [0005](0005-port-to-java-25-in-stages.md) | Port ArchSim to Java 25 in stages | Accepted | 2026-11-02 |
| [0006](0006-build-with-gradle-kotlin-dsl.md) | Build with Gradle, Kotlin DSL and a version catalog | Accepted | 2026-11-02 |
| [0007](0007-spring-boot-4.md) | Use Spring Boot 4 for the application | Accepted | 2026-11-02 |
| [0008](0008-jdbcclient-and-flyway.md) | Access PostgreSQL through `JdbcClient`, with Flyway migrations | Accepted | 2026-11-02 |
| [0009](0009-base-package-io-archsim.md) | Use `io.archsim` as the base package | Accepted | 2026-11-02 |
| [0010](0010-gradle-build-at-repository-root.md) | One Gradle build at the repository root, one folder per module | Accepted | 2026-11-02 |
| [0011](0011-organise-application-code-by-feature.md) | Organise the application's code by feature | Accepted | 2026-11-02 |
| [0012](0012-separate-model-and-engine-modules.md) | Separate the design model from the simulation engine | Accepted | 2026-11-02 |
| [0013](0013-event-driven-simulation-kernel.md) | An event-driven simulation kernel | Accepted | 2026-11-02 |
| [0014](0014-one-deployable-application-with-roles.md) | One deployable application, started as `api`, `worker` or `migrate` | Accepted | 2026-11-02 |
| [0015](0015-benchmark-harness-stays-python-until-port-completes.md) | Keep the benchmark harness in Python until the port is complete | Accepted | 2026-11-02 |
| [0016](0016-drop-sqlite-development-mode.md) | Drop the SQLite development mode | Accepted | 2026-11-02 |
| [0017](0017-freeze-python-engine-during-port.md) | Freeze the Python engine during the port; run the held-out campaign before stage 4 | Accepted | 2026-11-02 |
| [0018](0018-p95-within-a-factor-of-two.md) | Judge p95 within a factor of two either way | Accepted | 2026-11-02 |
| [0019](0019-black-box-api-tests-against-any-base-url.md) | Black-box API tests in Java, run against any base URL | Accepted | 2026-11-05 |
| [0020](0020-golden-files-of-the-frozen-engine.md) | Record the frozen engine's deterministic functions as golden files | Accepted | 2026-11-05 |
| [0021](0021-java-build-conventions.md) | Java build conventions: one convention plugin, warnings as errors, JUnit 6 | Accepted | 2026-11-05 |
| [0022](0022-records-and-a-pydantic-compatible-codec.md) | Map the design document with records and a pydantic-compatible codec over Jackson trees | Accepted | 2026-11-05 |
| [0023](0023-upgrade-chain-on-the-json.md) | Upgrade older documents with a chain of steps on the JSON, and model only the current schema | Accepted | 2026-11-05 |
| [0024](0024-validation-issues-with-stable-codes.md) | Report what is wrong with a design as issues with stable codes and the Python engine's words | Accepted | 2026-11-05 |
| [0025](0025-json-schema-generated-from-the-records.md) | Generate the design document's JSON Schema from the records, as pydantic writes it | Accepted | 2026-11-05 |
| [0026](0026-component-specs-and-price-snapshots-in-the-model.md) | Component specifications and the price-snapshot lookup belong to the model | Accepted | 2026-11-05 |
| [0027](0027-python-text-conventions-in-one-package.md) | Reproduce Python's text conventions for numbers and values in one package | Accepted | 2026-11-05 |
| [0028](0028-golden-file-reader-as-test-fixtures.md) | Read the golden files with a reader shared as Gradle test fixtures | Accepted | 2026-11-05 |
| [0029](0029-model-packages-in-layers-checked-by-archunit.md) | Build the model's packages in layers, one direction only, checked by ArchUnit | Accepted | 2026-11-05 |
| [0030](0030-spring-style-and-clean-code-checks.md) | Write Java in Spring Framework's style, with clean-code rules the build enforces | Accepted | 2026-11-05 |
| [0031](0031-component-catalog-facts-and-presentation.md) | Build the component catalog from the specifications, with its presentation as data | Accepted | 2026-11-05 |
| [0032](0032-flyway-takes-over-from-alembic.md) | Flyway takes over from Alembic at revision 0008, proven by a schema snapshot | Accepted | 2026-11-05 |
| [0033](0033-embedded-postgresql-for-tests.md) | Run the database tests on an embedded PostgreSQL, without Docker | Accepted | 2026-11-05 |
| [0034](0034-keep-the-fastapi-http-contract-in-spring-mvc.md) | Keep the FastAPI API's HTTP contract in Spring MVC | Accepted | 2026-11-05 |
| [0035](0035-read-the-python-apis-settings-unchanged.md) | Read the Python API's `ARCHSIM_*` settings unchanged | Accepted | 2026-11-05 |
| [0036](0036-python-engine-service-for-synchronous-calls.md) | Run the engine's synchronous calls in a small internal Python service | Accepted | 2026-11-05 |
| [0037](0037-sign-in-without-spring-security.md) | Sign people in with plain Spring MVC, not Spring Security | Accepted | 2026-11-05 |
| [0038](0038-engine-computes-on-typed-records.md) | The engine computes on the model's records and typed configs, not on JSON trees | Accepted | 2026-11-05 |
| [0039](0039-engine-reads-reports-as-partial-records.md) | The engine reads a run's report as records of the parts it reads | Accepted | 2026-11-05 |
| [0040](0040-comparison-copies-what-it-does-not-read.md) | A run comparison copies the report parts it shows and diffs the simulated inputs as stored | Accepted | 2026-11-06 |
| [0041](0041-application-clock-never-repeats-a-microsecond.md) | The application's clock never tells the same microsecond twice | Accepted | 2026-11-06 |
| [0042](0042-exports-read-settings-leniently-and-write-their-own-yaml.md) | Exports read settings leniently and write their own YAML | Accepted | 2026-11-06 |
| [0043](0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md) | Read imported YAML with SnakeYAML, resolved as PyYAML resolves it | Accepted | 2026-11-06 |
| [0044](0044-read-terraform-json-with-jackson-as-python-json-reads-it.md) | Read Terraform JSON with Jackson, as Python's json reads it | Accepted | 2026-11-06 |
| [0045](0045-replay-calibrations-through-an-interim-simulate.md) | Replay calibrations through an interim `/simulate` on the engine service | Accepted | 2026-11-07 |
| [0046](0046-call-prometheus-with-apache-httpclient-5.md) | Call Prometheus with Apache HttpClient 5, pinned to the address it checks | Accepted | 2026-11-07 |
| [0047](0047-draw-python-random-streams-in-java.md) | Draw the Python engine's random streams in Java | Accepted | 2026-11-08 |
| [0048](0048-keep-simpys-order-of-events.md) | Keep SimPy's order of events in the Java kernel | Accepted | 2026-11-08 |
| [0049](0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md) | Measure the Java simulator by replaying the frozen engine's runs | Accepted | 2026-11-08 |
| [0050](0050-one-report-per-seed-with-pythons-digest-definitions.md) | One report per seed on every platform, with the Python engine's digest definitions | Accepted | 2026-11-08 |
| [0051](0051-start-the-java-cli-in-stage-4-with-archsim-run.md) | Start the Java CLI in stage 4 with `archsim run` | Accepted | 2026-11-09 |
| [0052](0052-record-the-frozen-engines-simulation-baselines-once.md) | Record the frozen engine's simulation baselines once, and judge the Java simulator by them | Accepted | 2026-11-09 |
| [0053](0053-simulate-runs-in-the-java-apps-worker-role.md) | Simulate runs in the Java app's worker role, one thread per run | Accepted | 2026-11-09 |
| [0054](0054-replay-calibrations-on-a-pool-of-platform-threads.md) | Replay calibrations on a pool of platform threads, never on the request's thread | Accepted | 2026-11-09 |
| [0055](0055-the-java-clis-output-contract.md) | The Java CLI keeps the Python CLI's words, exit codes and JSON values | Accepted | 2026-11-10 |
| [0056](0056-the-java-cli-takes-options-by-their-full-names.md) | The Java CLI takes options by their full names | Accepted | 2026-11-10 |
| [0057](0057-move-the-prometheus-client-into-the-engine.md) | Move the Prometheus client into the engine, for the app and the CLI | Accepted | 2026-11-10 |
| [0058](0058-serve-mcp-with-the-official-mcp-java-sdk.md) | Serve ArchSim's MCP tools with the official MCP Java SDK | Accepted | 2026-11-11 |
| [0059](0059-read-files-as-python-did-in-the-engine.md) | Read files as Python did in the engine, for the CLI and the MCP server | Accepted | 2026-11-11 |
| [0060](0060-run-the-check-action-in-an-image-the-job-builds.md) | Run the archsim-check action's CLI in an image the job builds | Accepted | 2026-11-11 |
| [0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md) | Answer the benchmark harness's engine calls from one Java process | Accepted | 2026-11-11 |
| [0062](0062-make-the-java-engine-the-harnesss-default.md) | Make the Java engine the benchmark harness's default | Accepted | 2026-11-11 |
| [0064](0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md) | Keep Python only for the benchmark harness and the repository's tooling, until stage 7 | Proposed | 2026-11-12 |
| [0065](0065-move-sign-in-to-spring-security-after-the-port.md) | Move sign-in to Spring Security after the port, keeping its HTTP contract | Proposed | 2026-11-12 |
