---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0006. Build with Gradle, Kotlin DSL and a version catalog

## Context and Problem Statement

The Java code has several modules ([ADR-0010](0010-gradle-build-at-repository-root.md)). It needs:

- quality gates in CI:
  - Spotless with palantir-java-format;
  - Error Prone with NullAway and JSpecify;
  - JUnit 5 with AssertJ and jqwik;
  - Testcontainers;
  - JaCoCo;
  - ArchUnit;
- build steps of its own, such as checking golden files from the Python engine and checking the
  generated OpenAPI document against the web app's `api.ts`.

Which build tool should the Java code use?

## Considered Options

- Gradle 9 with the Kotlin DSL and a version catalog
- Maven

## Decision Outcome

Chosen option: "Gradle 9 with the Kotlin DSL and a version catalog", because:

- incremental builds and the build cache keep a multi-module build fast;
- Kotlin build scripts are typed, so IDEs complete and check them;
- custom checks such as the golden-file comparison are ordinary tasks;
- a version catalog (`gradle/libs.versions.toml`) keeps every dependency version in one place.

How it's used:

- The Gradle wrapper is committed, so nobody needs Gradle installed. A Java 25 toolchain makes the
  build use Java 25 whatever `JAVA_HOME` points to.
- Settings shared by every module live in convention plugins rather than being copied into each
  module's build file.

### Consequences

- Good, because one command (`./gradlew build`) runs compilation, formatting checks, static
  analysis and tests the same way locally and in CI.
- Good, because adding a module means applying a convention plugin, not copying configuration.
- Bad, because Gradle's flexibility lets build logic sprawl. Keeping it in convention plugins, not
  in module scripts, is the rule that prevents that.
- Bad, because Maven is more widespread in enterprise Java, so some readers will know it better.

## More Information

- [The port proposal, "Target architecture"](../proposals/java-25-port.md#target-architecture)
- Gradle version catalogs: <https://docs.gradle.org/current/userguide/version_catalogs.html>
