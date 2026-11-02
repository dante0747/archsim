---
status: proposed
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0030. Write Java in Spring Framework's style, with clean-code rules the build enforces

## Context and Problem Statement

The Java code is meant to be a reference for engineers. Stages 0 and 1 formatted it with
palantir-java-format (ADR-0006 and [ADR-0021](0021-java-build-conventions.md)), but nothing
checked how it was written beyond formatting and nullness. Reviewing it, the owner found it not
clean enough:

- test names in two styles (snake_case sentences in the model, camelCase in the API tests);
- methods of 50 lines and more, and classes of nearly 700;
- JSON written as escaped strings in annotations;
- one-letter names, inline fully qualified names, comments that read as notes;
- enum constants run together on one line.

Most of the code to come is a Spring Boot application. How should Java code be written here, and
how is it kept that way, by people and by AI assistants?

## Decision Drivers

- Code a Java and Spring engineer recognises and would hold up as an example.
- Rules the build checks, not habits: every finding fails the build.
- The same rules for people and AI assistants.

## Considered Options

- Format: Google Java Style (google-java-format), palantir-java-format (as before), or Spring
  Framework's style (spring-javaformat)
- Test names: camelCase with `@DisplayName`, `should...`, snake_case sentences, or
  `method_state_result`
- Enforcement: a written convention only, Checkstyle, or Checkstyle and PMD

## Decision Outcome

Chosen options: "Spring Framework's style", "`method_state_result`" and "Checkstyle", because
Spring's style is what the application's engineers read in Spring's own code, and Spring ships a
formatter and a Checkstyle module for it that keep it without effort.

- **Format:** spring-javaformat's Gradle plugin (`./gradlew format`, and `checkFormat` in
  `check`) replaces Spotless with palantir-java-format.
- **Checkstyle** runs on every source set with Spring's checks (`SpringChecks`: import order,
  `this.` for fields, Javadoc form, lambdas, ternaries, `*Tests` classes, ...) and ArchSim's
  clean-code rules: sizes and complexity, names, Javadoc on the public API, no JSON in strings,
  one enum constant per line, and test names. `maxWarnings` is 0; an exception to a rule is a
  change to `config/checkstyle/`, never a suppression in the code.
- Spring's checks are taken whole, except the `@author` tag on every type: git records authors.
- **Tests** are named `method_state_result`. Checkstyle tells test methods from others by their
  JUnit or jqwik annotation, and checks each against its own pattern.
- **The rules are written down** in [`docs/java-conventions.md`](../java-conventions.md), and the
  repository's skills (`.claude/skills/`: `java-conventions`, `test-first`,
  `conventional-commits`), pointed to by `AGENTS.md`, give them to AI assistants.

### Consequences

- Good, because the style is one a Spring engineer already knows, and `./gradlew format` keeps it.
- Good, because the build, not review, catches long methods, vague names and missing Javadoc.
- Bad, because adopting it reformatted every Java file (tabs), and the existing code had to be
  refactored to pass: about 2,700 findings, mostly mechanical.
- Bad, because spring-javaformat has no options: enum constants stay on separate lines only when
  each has its own Javadoc, so the convention requires one.
- Bad, because Spring's checkstyle module is built against an older Checkstyle API; a Checkstyle
  upgrade must be checked with it before it is taken.

### Confirmation

`./gradlew build` runs `checkFormat` and Checkstyle on every source set; the CI job `java` runs it.

## Pros and Cons of the Options

### Google Java Style

- Good, because it is the most widely documented guide and formatter.
- Bad, because it is not what the application's Spring engineers read daily.

### palantir-java-format (as before)

- Good, because nothing would need reformatting.
- Bad, because it formats only; the problems above are not formatting.

### Checkstyle and PMD

- Good, because PMD adds design rules (unused code, complexity, coupling).
- Bad, because much of it overlaps Checkstyle and Error Prone, and it is noisier.

## More Information

- Supersedes the formatting part of [ADR-0006](0006-build-with-gradle-kotlin-dsl.md) and
  [ADR-0021](0021-java-build-conventions.md); the rest of both stands.
- spring-javaformat: <https://github.com/spring-io/spring-javaformat>
- Checkstyle: <https://checkstyle.org/>
- Robert C. Martin, *Clean Code* (2008)
