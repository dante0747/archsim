---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0010. One Gradle build at the repository root, one folder per module

## Context and Problem Statement

During the port, Java and Python live side by side. The Java code needs golden files exported from
the Python engine, the black-box API tests, the example designs in `examples/`, and the web app's
API client to check its contract against. After the port, Python remains only in the benchmark
harness, and only until stage 7 ([ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md)).

Where should the Java code live?

## Considered Options

- One Gradle build at the repository root, with one top-level folder per module
- A separate Gradle build under `java/`
- A separate repository

## Decision Outcome

Chosen option: "One Gradle build at the repository root", because:

- it's where a Java developer looks first;
- once the Python code is gone, it's an ordinary Java repository with the web app beside it;
- every shared input (golden files, examples, the web client) stays in the same repository and the
  same change.

```
archsim/
  settings.gradle.kts         the build's modules
  gradle/libs.versions.toml   dependency versions (ADR-0006)
  archsim-model/              the design document (ADR-0012)
  archsim-engine/             the simulation engine (ADR-0012)
  archsim-app/                API, workers and migrations (ADR-0014)
  archsim-cli/                the archsim command
  archsim-mcp/                the MCP server
  apps/web/                   the Next.js web app, unchanged
  benchmarks/                 the benchmark harness (Python until stage 7)
  packages/, services/        the Python code, removed stage by stage
```

Modules are added when a stage needs them. Test-only modules, such as the black-box API tests, sit
beside them.

### Consequences

- Good, because one checkout and one change cover a feature and its parity check.
- Good, because the layout after the port needs no further move.
- Bad, because during the port the top level mixes Java module folders with the Python `packages/`
  and `services/`, which takes a moment to read.
- Bad, because Gradle and the Python tooling share the root, so each must ignore the other's files
  and outputs.

## More Information

- [ADR-0005](0005-port-to-java-25-in-stages.md) (the stages) and
  [ADR-0006](0006-build-with-gradle-kotlin-dsl.md) (the build)
