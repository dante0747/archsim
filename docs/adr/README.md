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
