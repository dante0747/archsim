# Java conventions

How ArchSim's Java code is written. The aim is code an engineer can hold up as an example: small,
plainly named parts, each doing one thing, in the style Spring engineers read every day. The
decision and the options weighed are in [ADR-0030](adr/0030-spring-style-and-clean-code-checks.md).

`./gradlew build` enforces every rule below that a tool can check, and fails on any finding: the
formatter, Checkstyle ([`config/checkstyle/`](../config/checkstyle/checkstyle.xml)), Error Prone
with NullAway, ArchUnit and the tests. There are no warnings to ignore. Review covers the rest.

```bash
./gradlew format            # formats every Java file in the Spring style
./gradlew check             # the formatter's check, Checkstyle, Error Prone, NullAway, tests
```

AI assistants follow the same rules through the project's skills (see
[Working with AI assistants](#working-with-ai-assistants)).

## Format

- **Spring Framework's style**, applied by [spring-javaformat](https://github.com/spring-io/spring-javaformat):
  tabs for indentation, lines of at most 120 columns (a tab counts as 4), a blank line after a
  type's opening brace and before its closing one. Don't format by hand: run `./gradlew format`.
  IntelliJ IDEA and Eclipse have spring-javaformat plugins that format the same way on save.
- **Imports** in Spring's order: `java.*`, `javax.*`, other libraries (Spring included), then
  `io.archsim.*`, each group separated by a blank line, static imports last. No wildcard
  imports, and no fully qualified names in code where an import would do.
- **Static imports** only in tests, and only for AssertJ, BDDMockito, Spring's MockMvc builders
  and matchers, and ArchUnit's rule builders.
- **Enums:** one constant per line, each with its own Javadoc.

## Clean code

The rules follow Robert C. Martin's *Clean Code*. Most of all:

- **Names say what a thing is or does.** A reader should not need a comment to know what a
  variable holds or what a method returns.
- **Small parts that do one thing.** A method works at one level of abstraction; a class has one
  reason to change.
- **No surprises.** A method named as a query doesn't change state; a method that changes state
  doesn't also answer a question (command-query separation).
- **Say it once.** Duplicated knowledge (a rule, a number, a message) lives in one place.
- **Leave it cleaner.** Code you touch meets these rules when you are done with it.

### Names

- Classes are nouns (`TokenService`, `RunQueue`); methods are verbs or questions
  (`claimNextRun`, `isExpired`); booleans read as statements (`expired`, `hasRole`).
- No one-letter names, except a `for` loop's index; lambda parameters too (`(node) -> ...`, not
  `n -> ...`). `_` is fine for a lambda parameter or pattern variable the code ignores
  (`(_) -> 0`, `case Circle _ ->`, `case Point(var left, var _)`); a local variable, a `try`
  resource or a `catch` parameter still gets a name.
- No abbreviations a newcomer has to decode (`count`, not `cnt`; `request`, not `req`).
  Acronyms are words: `JsonCodec`, `HttpClient`, not `JSONCodec`.
- Constants are `UPPER_SNAKE_CASE`; a number with a meaning is a named constant, never a bare
  literal in the code (`-1`, `0`, `1` and `2` excepted).
- Packages are lower case, under `io.archsim` ([ADR-0009](adr/0009-base-package-io-archsim.md)).

### Size and complexity

Checkstyle fails the build past these limits. They are ceilings, not targets: most methods are far
shorter.

| What | Limit |
| --- | --- |
| A file | 400 lines |
| A method or constructor | 30 lines (blank lines and comments not counted) |
| A lambda | 10 lines; longer, it becomes a named method |
| An anonymous class | 20 lines |
| Parameters of a method or constructor | 4; more, and they become a record |
| Cyclomatic complexity of a method | 10 |
| NPath complexity of a method | 200 |
| Operators in one boolean expression | 3 |
| Nested `if` / `for` / `try` | 2 / 1 / 1 |
| Classes one class uses (fan-out) | 20; the codec's annotations (`@Default`, `@Ui`, ...) don't count |

A record may have as many components as the data it holds: a design document's config records
mirror a contract with dozens of fields.

### Types and methods

- **Records for data**, classes for behaviour. A record checks its invariants in its compact
  constructor and copies the collections it is given.
- **Classes are `final`** unless they are designed for extension, and **immutable** where they
  can be. Fields are `private final`, set by the constructor.
- **Constructor injection only** in Spring code: no field or setter injection.
- **Fields are referred to as `this.field`** (Spring's style), so a field never looks like a
  local variable.
- **A member only an annotation refers to is package-private**, not private: a constant named in
  `@Description(...)` or `@DefaultFrom(...)`. Error Prone does not count an annotation's
  reference as a use, and would report the private member as unused.
- **Optional for a value that may be missing** in a return type; never return `null` from a
  public method. Inside a class, `@Nullable` (JSpecify) marks what may be null, and NullAway
  checks it.
- **No flag arguments.** Two behaviours are two methods.
- **Guard clauses** instead of nested `if`s: deal with the special case and return.
- **Ternaries** have their condition in parentheses: `(count > 1) ? "runs" : "run"`.
- **Exceptions, not error codes.** Throw the most specific exception; never catch `Exception`,
  `RuntimeException` or `Throwable`. A caught exception is named `ex`, and an empty `catch`
  says why it is empty.
- **No printing.** Log through SLF4J, or throw. A logger is a constant:
  `private static final Logger LOGGER = LoggerFactory.getLogger(Owner.class)`.

### Comments and Javadoc

- **Every public type has Javadoc**, and so has every public method longer than two lines. The
  first sentence says what it is or does, ends with a period, and stands alone (it is the summary
  in the API docs). `@param`, `@return` and `@throws` describe what the code does not already
  say.
- **Comments say why**, never what: the code says what. A comment that restates the code goes.
- **No commented-out code, and no `TODO`, `FIXME` or `XXX`.** Finish the work, or open an issue.
- **The Python engine** is mentioned only where the Java code keeps one of its quirks on purpose,
  so the code would otherwise look wrong. Which Java class ports which Python function is
  recorded once, in the module's README and its parity tests, not in every Javadoc.

### Data in code

- **Setting descriptions and form groups** of a package's records (`@Description`, the `group`
  of `@Ui`) are constants in the package's package-private `Descriptions` class, so each
  setting's annotations stay short. When several records share the class, each text is named
  after its record (`FAULT_START_S`, `WORKLOAD_SEED`). A record's own description (its
  docstring) stays on the record, as a text block. Checkstyle lets an annotated record
  component's line run past 120 columns when its strings are short, because the formatter
  cannot wrap between annotations.
- **No JSON in strings** in production code. Data (presets, catalogs, fixtures) is a resource file
  read at start-up; JSON the code produces is built with Jackson's tree API or written by the
  codec. Tests may use JSON text as input.
- **Text people read** (messages, descriptions) is a constant or a message template next to the
  code that uses it, not scattered literals.

## Tests

- **Test first.** Write the failing test, make it pass with the simplest code, then refactor
  (see the [test-first skill](../.claude/skills/test-first/SKILL.md)).
- **Names are `method_state_result`:** what is called, in which state, and what happens:
  `create_withReadOnlyToken_isForbidden`, `load_schemaOnePointZero_upgradesToCurrent`.
- **Test classes end in `Tests`** (`TokenServiceTests`) and, like their methods, are
  package-private.
- **One behaviour per test**, in three parts separated by a blank line: arrange, act, assert.
  No logic (loops, conditions) in a test beyond building its input. The one exception is the
  black-box suite's port of the Python API tests (`archsim-api-tests`), which keeps one method per
  Python test so each can be traced to its source; its multi-step methods are not a model for
  new tests.
- **AssertJ** for assertions, **BDDMockito** (`given`, `then`) when a mock is needed. Prefer real
  objects and slice tests over mocks.
- **The right kind of test:** a unit test for a class; a Spring slice test (`@WebMvcTest`,
  `@JdbcTest`) for a layer; the black-box suite (`archsim-api-tests`) for the HTTP contract; the
  golden files for parity with the Python engine.
- Tests may use JSON text and literal numbers as their input, and need no Javadoc: their names
  are the specification.

## Review

Every change to Java code, the build or a migration is reviewed before it is committed. The
repository's [`java-reviewer`](../.claude/agents/java-reviewer.md) agent is a strict senior Java
reviewer for AI-assisted work: it checks the staged diff against these rules, the API and
database contracts and mainstream Spring practice, runs the checks, and answers APPROVE or
CHANGES REQUESTED. People's changes get the same review in their pull request.

## Commits

[Conventional Commits](https://www.conventionalcommits.org), as [CONTRIBUTING.md](../CONTRIBUTING.md)
describes and the `commit-msg` hook and CI check (`archsim-tools`' `CheckCommits`):
`type(scope): description`, imperative, starting in lower case, at most 100 characters, with no AI
co-author trailer. One logical change per commit, and each commit builds.

## Exceptions to a rule

A rule that does not fit a case is changed in [`config/checkstyle/`](../config/checkstyle/), for
everyone, with the reason written next to it. There is no suppressing a finding in the code.

## Working with AI assistants

The repository carries skills that give AI assistants these rules while they work, and
[`AGENTS.md`](../AGENTS.md) points every assistant at them:

| Skill | When |
| --- | --- |
| [`java-conventions`](../.claude/skills/java-conventions/SKILL.md) | writing or changing Java code |
| [`test-first`](../.claude/skills/test-first/SKILL.md) | adding behaviour or fixing a bug |
| [`conventional-commits`](../.claude/skills/conventional-commits/SKILL.md) | writing a commit message |

And one agent: [`java-reviewer`](../.claude/agents/java-reviewer.md), the strict review every
Java change gets before it is committed.
