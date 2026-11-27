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
| [0037](0037-sign-in-without-spring-security.md) | Sign people in with plain Spring MVC, not Spring Security | Superseded by [0065](0065-move-sign-in-to-spring-security-after-the-port.md) | 2026-11-05 |
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
| [0061](0061-answer-the-harnesss-engine-calls-from-one-java-process.md) | Answer the benchmark harness's engine calls from one Java process | Superseded by [0073](0073-remove-archsim-functions-with-the-python-harness.md) | 2026-11-11 |
| [0062](0062-make-the-java-engine-the-harnesss-default.md) | Make the Java engine the benchmark harness's default | Accepted | 2026-11-11 |
| [0063](0063-retire-alembic-older-databases-upgrade-from-a-tag.md) | Retire Alembic: a database below revision 0008 upgrades first from the tag `alembic-0008` | Accepted | 2026-11-12 |
| [0064](0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md) | Keep Python only for the benchmark harness and the repository's tooling, until stage 7 | Accepted | 2026-11-12 |
| [0065](0065-move-sign-in-to-spring-security-after-the-port.md) | Move sign-in to Spring Security after the port, keeping its HTTP contract | Accepted | 2026-11-12 |
| [0066](0066-ship-the-web-image-without-an-image-optimizer.md) | Ship the web image without Next.js's image optimizer, and so without sharp and libvips | Accepted | 2026-11-12 |
| [0067](0067-license-under-agpl-3.0-with-a-commercial-license.md) | License ArchSim under the AGPL-3.0, and offer a commercial license | Accepted | 2026-11-13 |
| [0068](0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md) | Port the benchmark harness to its own module, `archsim-benchmarks`, which calls the engine in process | Accepted | 2026-11-13 |
| [0069](0069-run-the-harness-from-gradle-with-picocli-commands.md) | Run the harness from Gradle, as picocli commands named as `run.py` named them | Accepted | 2026-11-13 |
| [0070](0070-write-the-harnesss-files-as-python-wrote-them.md) | Write the harness's files as the Python harness wrote them, byte for byte | Accepted | 2026-11-13 |
| [0071](0071-python-statistics-in-the-harness.md) | Reduce the results with Python's statistics, ported exactly | Accepted | 2026-11-13 |
| [0072](0072-move-the-repositorys-tooling-to-java-and-retire-the-cpython-recorders.md) | Move the repository's tooling to Java, and retire the recorders of CPython's behaviour | Accepted | 2026-11-13 |
| [0073](0073-remove-archsim-functions-with-the-python-harness.md) | Remove `archsim functions` with the Python harness | Accepted | 2026-11-14 |
| [0074](0074-bill-the-peak-for-the-hours-a-month-it-lasts.md) | Bill a workload's peak for the hours a month it lasts, and autoscaled parts at the count they settle at | Accepted | 2026-11-14 |
| [0075](0075-ask-outside-contributors-to-sign-a-contributor-license-agreement.md) | Ask outside contributors to sign a contributor license agreement on their pull request, AI-assisted work included | Proposed | 2026-11-15 |
| [0076](0076-version-archsim-apart-from-its-engine.md) | Version ArchSim as a product, apart from its engine, in `gradle.properties` | Accepted | 2026-11-15 |
| [0077](0077-publish-release-images-to-ghcr-and-run-them-from-compose.md) | Publish release images to GHCR, and run them from `docker-compose.yml` | Accepted | 2026-11-15 |
| [0078](0078-build-the-website-as-a-static-export-of-the-web-app.md) | Build archsim.io as a static export of the web app, from the repository's own docs | Accepted | 2026-11-15 |
| [0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md) | Let visitors try ArchSim in a sandbox for a day, without signing in | Accepted | 2026-11-16 |
| [0080](0080-host-a-public-server-from-a-compose-kit.md) | Host a public server from a Compose kit in `deploy/`, behind Caddy or a Cloudflare Tunnel | Accepted | 2026-11-16 |
| [0081](0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md) | Price a third-party API without a price at its preset's, or leave it out of the month and say so | Accepted | 2026-11-17 |
| [0082](0082-count-a-caches-refused-lookups-in-its-hit-ratio.md) | Count a cache's refused lookups in its hit ratio, and advise only the fallback a caller lacks | Accepted | 2026-11-17 |
| [0083](0083-name-each-utilisation-and-compare-like-with-like.md) | Name each utilisation, and compare like with like | Accepted | 2026-11-17 |
| [0084](0084-judge-asynchronous-designs-by-their-messages-too.md) | Judge asynchronous designs by their messages too | Accepted | 2026-11-17 |
| [0085](0085-size-a-kafka-broker-as-measured-and-name-idle-consumers.md) | Size a Kafka broker as measured, and name idle consumers | Accepted | 2026-11-17 |
| [0086](0086-keep-the-load-open-loop-past-the-objective-and-close-in-on-the-measured-knee.md) | Keep the load open-loop past the objective, and close in on the measured knee | Accepted | 2026-11-19 |
| [0087](0087-compare-the-simulator-with-simple-estimates.md) | Compare the simulator with simple estimates of the knee | Accepted | 2026-11-19 |
| [0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md) | Freeze the Java engine by its sources, and fix the criteria before a campaign | Accepted | 2026-11-19 |
| [0089](0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md) | Count a commit's log flush as a wait in the instant estimate, not as CPU | Accepted | 2026-11-19 |
| [0090](0090-register-a-second-held-out-set-before-the-next-model-change.md) | Register a second held-out set before the next model change | Accepted | 2026-11-19 |
| [0091](0091-count-a-vcpu-as-a-hyperthread-unless-the-design-or-its-instance-family-says-otherwise.md) | Count a vCPU as a hyperthread unless the design or its instance family says otherwise | Accepted | 2026-11-20 |
| [0092](0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md) | Spread a cache's commands over its shards, and count its fills, in the instant estimate | Accepted | 2026-11-19 |
| [0093](0093-fit-a-services-falling-cpu-per-request-as-a-law-of-its-rate.md) | Fit a service's falling CPU per request as a law of its rate | Accepted | 2026-11-20 |
| [0094](0094-fit-the-knees-range-on-every-experiment-the-engine-is-tuned-on-with-an-error-each-experiment-shares.md) | Fit the knee's range on every experiment the engine is tuned on, with an error each experiment shares | Accepted | 2026-11-20 |
| [0095](0095-run-the-benchmark-designs-on-the-laptops-fitted-host-machine-and-let-a-freeze-name-it.md) | Run the benchmark designs on the laptop's fitted host machine and let a freeze name it | Accepted | 2026-11-20 |
| [0096](0096-read-the-simulation-paritys-runs-as-the-frozen-engines-where-the-engine-changed-on-purpose.md) | Read the simulation parity's runs as the frozen engine's where the engine changed on purpose | Accepted | 2026-11-20 |
| [0097](0097-give-the-websites-home-page-its-own-narrative-and-take-its-facts-from-the-repository.md) | Give the website's home page its own narrative, and take its facts from the repository | Accepted | 2026-11-25 |
| [0098](0098-serve-the-website-at-a-second-address-from-cloudflare-pages.md) | Serve the website at a second address, archsim.coderator.dev, from Cloudflare Pages | Proposed | 2026-11-25 |
| [0099](0099-present-results-as-hypotheses-to-test-not-verdicts-or-gates.md) | Present results as hypotheses to test, not verdicts or gates | Proposed | 2026-11-25 |
| [0100](0100-give-the-website-a-stylesheet-of-its-own-built-from-the-files-it-renders.md) | Give the website a stylesheet of its own, built from the files it renders | Proposed | 2026-11-25 |
| [0101](0101-show-the-held-out-runs-against-the-simple-estimates-on-the-accuracy-page.md) | Show the held-out runs against the simple estimates on the accuracy page, each experiment one vote | Proposed | 2026-11-25 |
| [0102](0102-lead-each-screen-with-the-answer-and-make-every-input-explain-itself.md) | Lead each screen with the answer, and make every input explain itself | Proposed | 2026-11-26 |
| [0103](0103-refuse-a-number-that-is-not-finite-when-a-design-is-read.md) | Refuse a number that is not finite when a design is read, where the frozen engine took it | Proposed | 2026-11-27 |
| [0104](0104-restart-a-worker-whose-run-outlives-its-hard-deadline.md) | Restart a worker whose run outlives its hard deadline | Proposed | 2026-11-27 |
| [0105](0105-charge-every-rate-limit-before-its-transaction-begins.md) | Charge every rate limit before its transaction begins, and size the connection pool explicitly | Proposed | 2026-11-27 |
| [0107](0107-without-sign-in-answer-only-this-machines-own-clients.md) | Without sign-in, answer only this machine's own clients | Proposed | 2026-11-27 |
