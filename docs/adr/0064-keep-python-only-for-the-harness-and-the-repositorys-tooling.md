---
status: accepted
date: 2026-11-12
decision-makers: Majid Abarghooei (owner)
---

# 0064. Keep Python only for the benchmark harness and the repository's tooling, until stage 7

## Context and Problem Statement

Stage 6 of the [port](../proposals/java-25-port.md) removes Python, "everything except the
benchmark harness", which stays Python until stage 7
([ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md)). Once `services/api`
goes with Alembic, which stage 6 decides apart, two kinds of Python are left outside
`benchmarks/`:

- the `archsim-check` action's pull-request comment (`sticky_comment.py`), which the action runs
  on the runner's own Python in every repository that uses it;
- `scripts/`: what CI and contributors run to maintain this repository, which ships nothing.

Which of these stays, and until when?

## Decision Drivers

- What ArchSim ships or runs for its users holds no Python.
- No rewrite that buys nothing before stage 7 ports the harness, which needs Python until then.
- The data the Java tests keep from Python stays traceable to how it was made.

## Considered Options

- Keep the tooling until stage 7, move the action's comment off Python, delete the scripts that
  only made data once
- Port all of `scripts/` and the comment to Java now
- Keep everything that runs, the action's comment included, until stage 7

## Decision Outcome

Chosen option: "keep the tooling until stage 7, move the action's comment off Python", because
the action is the only Python that runs where ArchSim's users run it, and the rest is the
repository's own tooling, in the same environment as the harness.

- **The action's comment** moves to `actions/github-script`: JavaScript on the Node.js that runs
  every action, in the action's own module (`sticky-comment.js`), tested with Node's built-in
  test runner (`sticky-comment.test.js`, no dependency). It creates or updates the action's own
  comment, by its marker and author, as `sticky_comment.py` did, through the client
  `github-script` gives, which also knows a GitHub Enterprise server's address.
- **Stays, until stage 7**, run by `uv run` or by CI's `python3`, with nothing beyond the standard
  library and the dev group's `pytest`, `ruff`, `mypy` and `pyyaml`:
  - CI's and the commits' tooling: `check_commits.py` (the `commit-msg` hook and CI) and
    `ci_changes.py` (which CI jobs a change needs);
  - the price refresh, `scripts/prices/` (`prices.yml`);
  - the Java parity page, `java_parity_report.py`, which CI checks against the committed runs;
  - the recorders of CPython 3.12's behaviour that the Java engine and model keep on purpose:
    `export_casefold.py`, `export_erfc_points.py` and `export_printable.py`. They write data the
    Java reads, and their `--check` (and `test_export_printable.py`) is the only proof that the
    data is what Python 3.12 does.
- **Goes now**: `make_otlp_fixture.py`, which made the OTLP trace fixture once; the fixture
  stays in `parity/fixtures/calibration/`, as stage 5 kept the data of the scripts it deleted.
- Once `services/api` goes, the uv workspace has no members left: the root project depends on
  nothing, and its dev group holds the tools above.
- Stage 7's plan gains an item: once the harness is Java, the tooling moves to Java, or to
  whatever runs it then, or goes, so that the last Python goes with the harness.

### Consequences

- Good, because users of ArchSim and of its action need no Python, and the action needs no
  Python on a self-hosted runner.
- Good, because the tooling keeps working, and its tests keep running in CI's Python job, which the
  harness needs anyway.
- Bad, because contributors still install Python and uv to run every check (`AGENTS.md`), until
  stage 7.
- Bad, because the action's comment is JavaScript, a third language in the repository besides
  the web app's TypeScript, though in a form every action author knows.

### Confirmation

The action's comment module has its tests (`node --test`), which CI's `archsim-check` job runs.
Nothing outside `benchmarks/` and `scripts/` holds Python, and `pyproject.toml` has no workspace
members.

## Pros and Cons of the Options

### Port all of `scripts/` and the comment to Java now

- Good, because the repository would be one language but for the harness and the web app.
- Bad, because a commit hook that starts a JVM for every commit is slow, and `ci_changes.py` runs
  before CI installs Java.
- Bad, because it rewrites working tools in the stage meant to clean up, while stage 7 must keep
  Python for the harness anyway.

### Keep everything that runs, the action's comment included, until stage 7

- Good, because nothing changes.
- Bad, because users of the action run ArchSim's Python on their runner, after the port promised
  a Java product.

## More Information

- [ADR-0015](0015-benchmark-harness-stays-python-until-port-completes.md): the harness.
- [ADR-0060](0060-run-the-check-action-in-an-image-the-job-builds.md): the action's image.
