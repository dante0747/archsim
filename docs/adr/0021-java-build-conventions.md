---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0021. Java build conventions: one convention plugin, warnings as errors, JUnit 6

## Context and Problem Statement

[ADR-0006](0006-build-with-gradle-kotlin-dsl.md) chooses Gradle and puts settings shared by every
module in convention plugins. It names the quality gates but not how strict they are, how the build
finds Java 25, or which JUnit. ADR-0006 says JUnit 5, but Spring Boot 4 has moved to JUnit 6
([ADR-0007](0007-spring-boot-4.md)).

What exactly does every Java module get from the build?

## Decision Drivers

- The project is meant to be reference code: its build should be one a Java engineer holds up
  as an example.
- Mistakes should fail the build, not appear as warnings nobody reads.
- The build must work on any machine with any `JAVA_HOME`, and the same in CI.

## Considered Options

- JUnit 6 (the Jupiter API) or JUnit 5.14
- Warnings fail the build, or warnings are reported
- NullAway on every `io.archsim` package, or only on packages marked `@NullMarked`

## Decision Outcome

Chosen options: "JUnit 6", "warnings fail the build", and "NullAway on every `io.archsim`
package". Every module applies one convention plugin,
`build-logic/src/main/kotlin/archsim.java-conventions.gradle.kts`, and configures nothing it
sets:

- **Java 25.** A Java 25 toolchain compiles with `--release 25`.
  `gradle/gradle-daemon-jvm.properties` makes Gradle itself run on JDK 25, downloading one if
  none is installed, so the build works whatever `JAVA_HOME` points to.
- **Static analysis.** Error Prone with NullAway in JSpecify mode covers all of `io.archsim`.
  With `-Xlint:all -Werror`, every javac or Error Prone warning fails the build.
- **Tests.** JUnit 6 and AssertJ. JUnit 6 is JUnit 5's Jupiter API, and the version Spring Boot
  4 manages, so the tests won't need to change when stage 2 adds Spring. JaCoCo writes coverage
  reports after the tests.
- **Further test suites**, such as the black-box `apiTest`
  ([ADR-0019](0019-black-box-api-tests-against-any-base-url.md)), are Gradle JVM test suites.
  `check` compiles and analyses them; they run only on request.
- **Build speed.** The configuration cache, the build cache and parallel builds are on
  (`gradle.properties`).

### Consequences

- Good, because one command, `./gradlew build`, gives every module the same checks locally and
  in CI.
- Good, because null-safety and Error Prone's bug patterns are enforced, not advisory.
- Bad, because strictness has a cost: a contributor must follow Error Prone's suggestions. A
  finding is never suppressed; where a rule seems wrong, the owner decides
  ([`AGENTS.md`](../../AGENTS.md), [ADR-0030](0030-spring-style-and-clean-code-checks.md)).
  NullAway's generic checks sometimes need a plain loop where a stream would do.
- Bad, because a few third-party JUnit extensions may support JUnit 6 later than JUnit 5.

### Confirmation

`./gradlew build` fails on a formatting change, a nullness error or any compiler warning. The CI
job `java` runs it.

## More Information

- [ADR-0006](0006-build-with-gradle-kotlin-dsl.md) (Gradle, the Kotlin DSL and the version
  catalog)
- [ADR-0030](0030-spring-style-and-clean-code-checks.md) (formatting and Checkstyle)
- JUnit 6: <https://docs.junit.org/>
- NullAway's JSpecify mode: <https://github.com/uber/NullAway/wiki/JSpecify-Support>
