# Developing ArchSim: layout and tests

How the repository is organised and how ArchSim is tested. To set up a development environment and
run every check before a pull request, follow [CONTRIBUTING.md](../CONTRIBUTING.md); why the code is
built the way it is, decision by decision, is in the
[architecture decision records](adr/README.md).

## Repository layout

```
archsim-model/      Java design model: the document's records, upgrades, JSON Schema and validation
archsim-engine/     Java engine: the simulator and its deterministic parts (sizing, cost, review, ...)
archsim-app/        Java API (Spring Boot): projects, runs, workspaces, reviews, sharing, sign-in;
                    started as api, worker or migrate
archsim-api-tests/  Java black-box tests of the HTTP API
archsim-cli/        Java command line, `archsim`
archsim-mcp/        Java MCP server for AI coding agents
archsim-benchmarks/ Java accuracy benchmarks' harness; its data stays in benchmarks/
archsim-tools/      Java tooling: the commit message check, CI's changed parts, the price refresh
build-logic/        The Gradle build's shared conventions (with gradle/ and config/checkstyle/)
apps/web/           Next.js editor: canvas, inspector, results, dashboard and comparison
examples/           Example architectures (versioned JSON) with scenarios: the templates
parity/             What the Python engine 0.16.0 recorded: the Java tests' reference data
benchmarks/         The accuracy benchmarks' stacks, k6 load and results
docs/               Documentation, the ADRs and the accuracy report
```

Each Java module has a README of its own: [archsim-model](../archsim-model/README.md),
[archsim-engine](../archsim-engine/README.md), [archsim-app](../archsim-app/README.md),
[archsim-api-tests](../archsim-api-tests/README.md), [archsim-cli](../archsim-cli/README.md),
[archsim-mcp](../archsim-mcp/README.md), [archsim-benchmarks](../archsim-benchmarks/README.md) and
[archsim-tools](../archsim-tools/README.md);
so do the [web app](../apps/web/README.md) and the [parity data](../parity/README.md). How to run
the benchmarks is in [validation.md](validation.md#reproduce).

## Tests and checks

```bash
./gradlew build                                 # the Java code: format, Checkstyle, Error Prone, tests
java archsim-tools/src/main/java/io/archsim/tools/commits/CheckCommits.java --range main..HEAD
cd apps/web && npm test && npm run lint && npm run typecheck && npm run build
```

`make` targets wrap several of these (see the [Makefile](../Makefile)), and
[CONTRIBUTING.md](../CONTRIBUTING.md#checks) lists the rest: the web app's end-to-end tests and the
black-box API tests.

## What the tests check

- **Parity with the frozen engine.** The Java engine's tests replay the runs the Python engine 0.16.0
  recorded, figure for figure, and match its golden files ([parity/README.md](../parity/README.md)).
- **Queueing theory.** The simulator is checked against M/M/1 and M/M/c (Erlang C) queues and
  Little's law, the instant estimate against the simulation, and the starter example's scenarios
  against a directional reference benchmark.
- **The HTTP API, from outside.** The black-box suite ([archsim-api-tests](../archsim-api-tests/README.md))
  speaks HTTP to the Java API and its worker on PostgreSQL.
- **The main path in a browser.** Playwright drives the web app against a real back end
  (`npm run e2e` in `apps/web`).
- **Accuracy against real systems.** The benchmark harness calibrates ArchSim on real stacks,
  saves its predictions and measures them with k6; the results are in
  [validation.md](validation.md).

Changing what a simulation produces for the same document and seed is held to a higher bar: see
[Changing simulation semantics](../CONTRIBUTING.md#changing-simulation-semantics).
