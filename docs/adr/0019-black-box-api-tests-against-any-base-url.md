---
status: accepted
date: 2026-11-05
decision-makers: Majid Abarghooei (owner)
---

# 0019. Black-box API tests in Java, run against any base URL

## Context and Problem Statement

At stage 2, `archsim-app` replaces the FastAPI service behind the same contract: routes, JSON,
cookies, CSRF, rate limits and the PostgreSQL tables
([ADR-0005](0005-port-to-java-25-in-stages.md), [ADR-0014](0014-one-deployable-application-with-roles.md)).
The 162 Python API tests in `services/api/tests` can't check another implementation. They run the
app in-process through FastAPI's `TestClient` and reach inside it: settings overrides,
dependency overrides that act as another person, a mock transport for OAuth, and ORM writes.

How should the API be tested so that one suite checks both implementations?

## Decision Drivers

- The same tests must pass against the Python API and the Java one.
- The suite has to outlive the Python code, which goes at stage 6.
- A Java engineer should be able to read and extend it.
- Tests must not depend on each other, even on a server that outlives them.

## Considered Options

- Rewrite the tests in Java as black-box HTTP tests, against server profiles
- Keep them in Python, changing `TestClient` to an HTTP client with a base URL
- Generate tests from the OpenAPI document (Schemathesis or similar)
- Test only `archsim-app`, with Spring's in-process test clients

## Decision Outcome

Chosen option: "Rewrite the tests in Java as black-box HTTP tests", because only it checks both
implementations with one suite that stays after Python goes.

How it works ([`archsim-api-tests/README.md`](../../archsim-api-tests/README.md)):

- **Profiles.** A test class names a server profile: a set of `ARCHSIM_*` settings (`local`,
  `hosted`, `constrained`, `small-runs`, `rate-limited`, `allowlist`), the same for every
  implementation.
- **Servers.** The suite starts the API once per profile on a fresh PostgreSQL database
  (`-Parchsim.api.start=java`; `python` until the FastAPI app was deleted), or takes the base URLs
  of running servers.
- **Sign-in.** The `hosted` profile signs people in through a stand-in GitHub that the API calls
  over HTTP.
- **The database.** A step no route offers (expiring a session, making a sandbox workspace,
  checking that only a hash is stored) is SQL on the same tables. The tables are part of the
  contract, since `archsim-app` takes them over unchanged.
- **Isolation.** Every test signs in new people, works in a workspace of its own when it counts
  things, and sends its own client address, so tests never depend on an empty server.
- **What stays in Python.** Tests of in-process internals stay with the code they test: the
  queue, workers, migrations, and `Settings` and function unit tests. The Java app gets its own
  tests for those parts.

### Consequences

- Good, because stage 2's exit runs the same tests against both APIs, unchanged.
- Good, because the suite is ordinary Java (JUnit, AssertJ, the JDK's HTTP client), with no
  framework of its own.
- Bad, because it needs PostgreSQL and real servers: slower than in-process tests, and nothing
  runs without Docker or a database.
- Bad, because database steps tie some tests to table and column names.
- Bad, because Google sign-in can't be tested (the Python API calls Google's real addresses), and
  42 Python tests were not ported (the suite's README lists them and where each is checked now).

### Confirmation

The CI job `java` runs `apiTest` against the Java API it starts (`-Parchsim.api.start=java`): 132
test cases, from the 120 ported Python tests (122 methods, two of them parameterized). The
FastAPI app was deleted at stage 2 (5a557ca), so the suite now checks only `archsim-app`.

Parity with Python was only partly shown. The port plan's stage 0 notes record that 36 of the 120
ported tests had not run against the Python API, and they never did before it was deleted.

## Pros and Cons of the Options

### Keep them in Python with an HTTP client

- Good, because it's the smallest change from today's tests.
- Bad, because the suite would be Python to maintain after stage 6, or would need porting again.

### Generate tests from the OpenAPI document

- Good, because it finds schema mismatches with little code.
- Bad, because it can't check behaviour: roles, sessions, limits, sharing, the run lifecycle.

### Spring's in-process test clients

- Good, because they are fast and mainstream.
- Bad, because they test only the Java app, so parity with Python is never shown.

## More Information

- [ADR-0008](0008-jdbcclient-and-flyway.md) (same tables, Flyway from the current revision)
- [ADR-0016](0016-drop-sqlite-development-mode.md) (PostgreSQL only)
- [The port proposal, stage 0](../proposals/java-25-port.md#stages)
