---
name: test-first
description: Test-first development (TDD) for ArchSim - write a failing test, make it pass with the simplest code, then refactor. Use whenever adding behaviour, fixing a bug, porting a Python function or changing an API endpoint in this repository, in Java, Python or TypeScript.
---

# Test first

No production code without a test that failed first. The test is the specification: it says
what the code must do before the code exists, and it proves the code does it.

## The cycle

1. **Red.** Write one test for the next small piece of behaviour. Run it and watch it fail, for
   the reason you expect (an assertion, not a compile error you didn't intend). Show the failure.
2. **Green.** Write the simplest code that makes it pass. Nothing the test doesn't ask for.
3. **Refactor.** With the test green, clean the code and the test: names, duplication, size
   (the `java-conventions` skill). Run the tests again.
4. Repeat until the feature is done. Each cycle is minutes, not hours.

## Choosing the test

| Change | Write first |
| --- | --- |
| A class's behaviour | a unit test of that class |
| A controller, repository or other Spring layer | a slice test: `@WebMvcTest`, `@JdbcTest` |
| The HTTP contract (routes, JSON, status codes, cookies) | a black-box test in `archsim-api-tests` (`src/apiTest`), which runs against the running API |
| Parity with the Python engine | the golden-file entry (`parity/golden/`) through the module's parity test |
| A bug | a test that reproduces it and fails, before any fix |
| Python code (`benchmarks/`, `scripts/`) | a pytest test |
| The web app | a Vitest test, or a Playwright test for a user path |

## Writing the test

- Name it `method_state_result`: `claim_withAnExpiredLease_requeuesTheRun`.
- One behaviour per test, in three parts separated by blank lines: arrange, act, assert.
- Assert on behaviour the caller can see, not on private details.
- Use real objects; mock only what you can't run (a remote service), with BDDMockito.
- Keep it fast and independent: no order between tests, no shared mutable state, no sleeps
  (poll with a deadline when waiting is unavoidable).

## Commands

```bash
./gradlew :archsim-model:test --tests 'io.archsim.model.catalog.ComponentCatalogTests'
./gradlew :archsim-app:test --tests '*TokenServiceTests'
./gradlew :archsim-api-tests:apiTest -Parchsim.api.start=java --tests '*TokensTests'
uv run pytest scripts/tests/test_ci_changes.py -k python_environment
cd apps/web && npx vitest run src/lib/tier.test.ts
```

## Commits

The test and the code that makes it pass go in the same commit, so every commit builds
(`conventional-commits` skill). A bug fix's commit carries the test that reproduced the bug.

## When the cycle doesn't fit

- A spike to learn an API: throw the code away, then start again test first.
- Generated or purely declarative code (configuration, a migration): test what it does when run
  (the migration's schema, the configuration's effect), not its text.
