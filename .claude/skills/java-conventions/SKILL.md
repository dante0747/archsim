---
name: java-conventions
description: ArchSim's Java coding conventions - Spring Framework style, clean-code rules and the Checkstyle limits the build enforces. Use whenever writing, changing or reviewing Java code in this repository (archsim-model, archsim-app, archsim-api-tests, build-logic), before calling the work done.
---

# Java conventions

ArchSim's Java code is a reference for engineers. Every rule here is either checked by
`./gradlew build` (which fails on any finding) or by review. The full text, with the reasons, is
`docs/java-conventions.md`; the decision is ADR-0030. Read them when a rule below is unclear.

## How to work

1. Before writing code, read the neighbouring classes in the package and follow them.
2. Write the test first (the `test-first` skill).
3. Write the code to the rules below.
4. Run `./gradlew format`, then `./gradlew check` (or `:<module>:check`).
5. Fix every finding in the code. Never suppress one, never add `@SuppressWarnings` for
   Checkstyle, never edit `config/checkstyle/` to make your change pass. If a rule truly does
   not fit, stop and ask the owner; a rule changes for everyone, with its reason, or not at all.
6. Walk the checklist at the end before saying the work is done.

## Format (spring-javaformat; don't format by hand)

- Tabs, 120 columns, a blank line after a type's `{` and before its `}`. `./gradlew format`
  does it.
- Imports: `java.*`, `javax.*`, other libraries, `org.springframework.*`, `io.archsim.*`, each
  group separated by a blank line, static imports last. No wildcards, no inline fully qualified
  names.
- Static imports only in tests: AssertJ, BDDMockito, MockMvc builders and matchers, ArchUnit
  rule builders.
- Enums: one constant per line, each with its own `/** Javadoc. */` (the formatter joins
  undocumented constants onto one line).

## Clean code

- Names say what a thing is: classes are nouns, methods verbs or questions, booleans
  statements. No one-letter names except a `for` index; lambda parameters are named and in
  parentheses: `(node) -> node.id()`. No abbreviations; acronyms are words (`JsonCodec`).
- A number with a meaning is a named constant (only `-1`, `0`, `1`, `2` may stand alone).
- One method, one job, one level of abstraction. Guard clauses instead of nested `if`s. No flag
  arguments: two behaviours are two methods. Queries don't change state.
- Ternaries put their condition in parentheses: `(count > 1) ? "runs" : "run"`.
- Fields are `private final` and written `this.field`. Records for data (validate and copy in
  the compact constructor), `final` classes for behaviour, constructor injection only.
- Never return `null` from a public method: `Optional`. `@Nullable` (JSpecify) inside; NullAway
  checks it.
- Throw specific exceptions; never catch `Exception`, `RuntimeException` or `Throwable`. Name a
  caught exception `ex`. No `System.out` or `printStackTrace`: log or throw.
- Say it once: a rule, message or number lives in one place.

### Limits Checkstyle enforces

| What | Limit |
| --- | --- |
| File | 400 lines |
| Method or constructor | 30 lines (no blanks or comments counted) |
| Lambda / anonymous class | 10 / 20 lines |
| Parameters | 4 (more: a record) |
| Cyclomatic / NPath complexity | 10 / 200 |
| Boolean operators in one expression | 3 |
| Nested `if` / `for` / `try` | 2 / 1 / 1 |
| Classes one class uses | 20 |

When you near a limit, split by responsibility (extract a method object, a strategy or a value
type). Don't game it with longer lines or denser expressions.

## Comments and Javadoc

- Every public type, and every public method over two lines, has Javadoc. The first sentence
  says what it is or does and ends with a period; `@param`, `@return`, `@throws` add what the
  signature doesn't say.
- Comments explain why, never what. No commented-out code, no `TODO`/`FIXME`/`XXX`.
- Mention the Python engine only where the Java keeps one of its quirks on purpose. Which class
  ports which Python function belongs in the module README and the parity tests.

## Data

- No JSON in strings in production code (Checkstyle rule `jsonInStrings`): data goes in a
  resource file read once; JSON the code produces is built with Jackson's tree API or the
  model's codec. Tests may use JSON text as input.

## Tests

- Named `method_state_result`: `create_withReadOnlyToken_isForbidden`. Other methods in tests are
  camelCase.
- Classes end in `Tests`; classes and methods are package-private.
- One behaviour per test: arrange, act, assert, separated by blank lines. No loops or conditions
  beyond building the input. AssertJ; BDDMockito when a mock is truly needed.
- Unit tests for classes, Spring slices (`@WebMvcTest`, `@JdbcTest`) for layers, the black-box
  suite for the HTTP contract, the golden files for parity with the Python engine.

## Checklist before done

- [ ] A failing test came first, and now passes.
- [ ] `./gradlew format` and `./gradlew check` pass with no findings.
- [ ] Names read as English; no method over 30 lines, no class doing two jobs.
- [ ] Public types and methods have Javadoc that says what and why.
- [ ] No JSON strings, magic numbers, `null` returns, commented-out code or TODOs.
- [ ] A significant decision has an ADR (`docs/adr/`, `status: proposed`).
- [ ] The commit message follows the `conventional-commits` skill.
