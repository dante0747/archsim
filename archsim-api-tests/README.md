# Black-box API tests

These tests speak HTTP to a running ArchSim API, so the same suite checks today's Python API
and, from stage 2 of the [Java port](../docs/proposals/java-25-port.md), the Java one. They are
the Python API tests in `services/api/tests`, rewritten against a base URL. Where a Python test
writes or reads the database directly (an expired session, a sandbox workspace, a stored
hash), the black-box test does the same over JDBC: the PostgreSQL tables are part of the
contract, since the Java API takes them over unchanged.

## Running them

PostgreSQL must be running (`docker compose up -d postgres`) and the Python environment
synced (`uv sync`). Then:

```bash
./gradlew :archsim-api-tests:apiTest -Parchsim.api.start=python
```

starts the Python API once for each [profile](#profiles) the tests use, each on its own port
(8101 and up) and its own database (`archsim_api_tests_<profile>`, dropped and created afresh),
runs the tests and stops the servers. Each server's log is in
`archsim-api-tests/build/api-servers/<profile>.log`.

Against servers that are already running, give their URLs instead; tests of a profile without
a URL are skipped:

```bash
./gradlew :archsim-api-tests:apiTest -Parchsim.api.url.local=http://127.0.0.1:8000 \
  -Parchsim.api.jdbc.local=jdbc:postgresql://127.0.0.1:5432/archsim
```

| Property (`-P`) | Default | Meaning |
| --- | --- | --- |
| `archsim.api.start` | | `python`: start the Python API per profile |
| `archsim.api.url.<profile>` | | base URL of a running server of that profile |
| `archsim.api.jdbc.<profile>` | | its database, for the tests that set up or check rows |
| `archsim.api.port` | `8101` | first port for started servers (profile offsets follow) |
| `archsim.api.fakeGitHubPort` | `8190` | where the stand-in GitHub listens |
| `archsim.api.postgres` | `jdbc:postgresql://127.0.0.1:5432/archsim` | where started servers' databases are created |
| `archsim.api.postgresUser`, `…Password` | `archsim` | credentials for those databases |
| `archsim.api.databasePrefix` | `archsim_api_tests` | started databases are `<prefix>_<profile>` |

`./gradlew build` compiles and checks these tests but does not run them; it runs only the
unit tests of the harness itself (`src/test`).

## Profiles

A profile is a server configuration: a set of `ARCHSIM_*` settings, the same for every
implementation of the API ([`Profile.java`](src/main/java/io/archsim/apitests/Profile.java)).

| Profile | Settings |
| --- | --- |
| `local` | no sign-in (everyone is the local owner of `default`); two extra price snapshots of a made-up provider (`ARCHSIM_PRICES_DIR`) |
| `hosted` | GitHub and Google sign-in, as a hosted server runs it; GitHub is the suite's stand-in, Google is configured but never reached; public URL `https://archsim.test`; admin email `admin@example.com` |
| `constrained` | no sign-in; one run at a time; request bodies up to 10,000 bytes |
| `small-runs` | no sign-in; runs of at most 1,000 requests; Prometheus only at `prom.example:9090` |
| `rate-limited` | no sign-in, with a hosted server's rate limits |
| `allowlist` | no sign-in; Prometheus calls in allowlist mode (`ARCHSIM_OUTBOUND=allowlist`), only to `localhost:9090`, which must resolve to public addresses (it does not) |

A hosted server must call the suite's stand-in GitHub: `ARCHSIM_GITHUB_URL` and
`ARCHSIM_GITHUB_API_URL` are `http://127.0.0.1:<archsim.api.fakeGitHubPort>`.

## Writing a test

```java
@ApiTest(Profile.HOSTED)
class TokensTests {
    @Test
    void request_withReadToken_onlyReads(ApiServer server) {
        ApiClient ada = server.signedIn("ada");   // a new person, signed in, CSRF header set
        ...
    }

    @Test
    void change_withoutSignIn_needsNoCsrfHeaderOrToken(@On(Profile.LOCAL) ApiClient client) { ... }
}
```

- Test classes end in `Tests`; each test is named `method_state_result` (what it calls, in which
  state, what happens), like every Java test in ArchSim.
- A test method takes an `ApiServer` (clients, sign-in, the database) or a fresh `ApiClient`
  (cookies, default headers; paths are relative to `/api/v1`).
- A server is shared by every test of its profile and may outlive a run, so a test never
  assumes it is empty. People are new on every sign-in (`server.signedIn("ada")` makes a unique
  login, display name `Ada`). Without sign-in everyone is the same person in `default`, so a
  test that lists or counts what it made works in a workspace of its own
  (`Workspaces.enter(client)`).
- Every client sends its own `X-ArchSim-Client-IP` (the servers trust it from 127.0.0.1), so one
  test's limits per address never touch another's.
- `Runs.createProject`, `Runs.waitFor`, `Examples.starter(duration, warmup)` and `Json` are the
  usual steps; `server.db()` runs SQL on the server's database.

## Layout

| Path | What |
| --- | --- |
| `src/main/java` | the harness: client, servers and profiles, the stand-in GitHub, JDBC |
| `src/test/java` | unit tests of the harness (run by `./gradlew build`) |
| `src/apiTest/java` | the black-box tests (run by `apiTest`), and the steps some of them share (`Reviews`, `Shares`, `LoopbackSignIn`) |
| `fixtures/` | inputs a profile needs (a second price snapshot) |

## What came from the Python tests

Each class here comes from one or two files of `services/api/tests` (`test_api_review.py` became
two classes), with one method per Python test. The methods are named `method_state_result`, like
every Java test in ArchSim, rather than after the Python tests. Of the 162 Python API tests, 120
are here, as 122 methods: two are split in two because they need two server configurations. The
other 42 stay in Python:

- **In-process internals (26):** `test_api_queue.py`, `test_api_worker.py`,
  `test_api_process_runner.py` and `test_api_migrations.py`. They drive the run queue, workers,
  simulation processes and Alembic from inside the process. The Java app gets its own tests for
  these parts.
- **Not observable over HTTP (16),** listed below with the reason in each class's Javadoc.

| Python file | Class | Here | Stays in Python |
| --- | --- | --- | --- |
| `test_api_endpoints.py` | `EndpointsTests` | 17 | the database going down mid-run (the test swaps the server's engine in-process); two restarts of the API with leases written to the database |
| `test_api_export.py`, `test_api_import.py` | `ImportExportTests` | 4 | |
| `test_api_calibration.py` | `CalibrationTests` | 5 | the parsing of `ARCHSIM_PROMETHEUS_ALLOWED_HOSTS` (a `Settings` unit test) |
| `test_api_oauth.py` | `OAuthTests` | 16 | the validation of the sign-in settings (a `Settings` unit test) |
| `test_api_tokens.py` | `TokensTests` | 12 | |
| `test_api_workspaces.py` | `WorkspacesTests` | 15 | three tests of `queue.claim`, called directly |
| `test_api_limits.py` | `LimitsTests` | 13 | four tests of the rate-limit counter (`limits.hit`, `limits.prune`), called directly |
| `test_api_run_groups.py` | `RunGroupsTests` | 8 | two quick-check tests that shrink the engine's sampling thresholds in-process |
| `test_api_shares.py` | `SharesTests` | 15 | unit tests of `new_slug` and `public_report`; the API tests check both through the routes |
| `test_api_review.py` | `ReviewTests`, `ReviewHistoryTests` | 15 | |

Some tests check less than the Python test, or check it another way:

- Google sign-in can't be faked over HTTP (the Python API calls Google's real addresses), so a
  second GitHub account with the same email stands in where a Python test signs in with
  Google.
- The untrusted-proxy case signs in from 127.0.0.2 rather than starting a second server.
- An allowlisted Prometheus name must resolve to public addresses. Without patching DNS in
  the process, only `localhost` is checked (the `allowlist` profile), not the five private
  ranges.
- `test_profiles_per_kind` and `test_seed_limits_per_workspace_kind` are checked through what
  the workspaces report and refuse, not by calling `limits` directly.
- The engine digest check compares two runs of the same input, not a run with the engine called
  in-process.

## Notes for the Java app (stage 2)

Found while porting the tests. The suite checks neither of these, so the Java app can't copy
them by accident:

- **A bug in the Python API (not fixed; the owner decides).** `_charge` in
  `services/api/src/archsim_api/routers/runs.py` counts new runs against the person's hourly
  counter (`user:<id>:runs`) before the workspace's (`ws:<id>:runs`). When the workspace's
  limit then answers 429, the person's counter keeps the cost of runs that were never queued.
  That breaks the rule in `limits.py` that a refused request costs nothing. `archsim-app` must
  not copy it: check both limits before charging either.
- **Revoking a share stores a JSON `null` in `shared_reports.payload`, not SQL `NULL`** (the
  default of SQLAlchemy's JSON type). `SharesTests` accepts both. Both APIs use one database, so
  `archsim-app` should decide deliberately which one it writes, and readers should treat both
  as "no copy".
