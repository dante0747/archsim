---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0028. Read the golden files with a reader shared as Gradle test fixtures

## Context and Problem Statement

The golden files record the frozen Python engine's functions
([ADR-0020](0020-golden-files-of-the-frozen-engine.md)). Stage 1 compares the model's functions
with them; stages 3 and 4 will compare the engine's. Each needs the same reader: find the files,
walk their entries, resolve `$ref` inputs, and compare JSON by the parity bar (text exactly,
numbers within a relative 1e-9). Where should that reader live?

## Considered Options

- In `archsim-model`'s Gradle test fixtures (`java-test-fixtures`), package `io.archsim.parity`
- A module of its own, `archsim-parity`
- A copy in each module's tests

## Decision Outcome

Chosen option: "test fixtures of `archsim-model`", because it is Gradle's standard way to share
test code, adds no module, and `archsim-engine` depends on `archsim-model` anyway.

- `GoldenFile` reads a file (gzip of canonical JSON) and lists its `GoldenEntry`s, inputs resolved.
- `JsonDiff` compares JSON as the exporter's `--check` does and names where values differ, as
  JSON Pointers.
- `Parity.check(entry, call, error)` runs a Java function on an entry's input and compares its
  output, or its exception recorded as Python records one, with the entry.
- `Repository` finds the repository root (the build passes `-Darchsim.repoRoot`).
- A module maps the Python functions it ports to Java calls (in `archsim-model`,
  `ModelFunctions`), and one test runs every entry of those functions in every file. A second test
  fails if a ported function has no entries, so nothing is silently skipped.

A later module adds `testImplementation(testFixtures(project(":archsim-model")))`.

### Consequences

- Good, because the comparison rules are written once, in the same terms as the Python `--check`.
- Good, because the main jar carries none of it.
- Bad, because the shared reader sits in the model module although it is about the whole port;
  if more than the engine needs it, it can move to a module of its own.

### Confirmation

`GoldenParityTest` uses it in `archsim-model`; `./gradlew build` compiles the fixtures with the
same checks as main code.

## More Information

- Gradle's test fixtures: <https://docs.gradle.org/current/userguide/java_testing.html#sec:java_test_fixtures>
- [`parity/README.md`](../../parity/README.md): the file format.
