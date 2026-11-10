# An SLO gate in CI

`archsim check` simulates a design and answers two questions a pull request raises: does it
still meet its objectives, and is it worse than the base branch?

```bash
uv run archsim check archsim/design.json --baseline-ref origin/main \
  --fail-on objectives --fail-on regression --format markdown --out check.md
```

| Exit code | Meaning |
| --- | --- |
| 0 | Passes: what `--fail-on` names is fine. |
| 1 | Fails: an objective is missed (`--fail-on objectives`, the default) or a metric regressed past its threshold (`--fail-on regression`). Nothing else exits 1. |
| 2 | The check could not run: unreadable JSON, an invalid design (the issues are printed to stderr), a run over the limits, a `--baseline-ref` that cannot be read, a design outside a git repository with `--baseline-ref`, or any unexpected error. |

Regressions compare the design with a baseline: `--baseline other.json`, or the same file at a
git ref with `--baseline-ref origin/main`. A design that does not exist at that ref (a new
design) has no baseline and is checked against its objectives only. A ref that cannot be read —
a typo, or a shallow checkout that does not have it (`actions/checkout` fetches one commit
unless `fetch-depth: 0`) — is an error (exit 2), never "no baseline", so a gate cannot pass for
want of the comparison it was asked to make. Thresholds, all changeable:

| Metric | Regression when | Option |
| --- | --- | --- |
| p95 latency | up more than 10% | `--max-p95-increase 0.10` |
| Success rate | down more than 0.1 percentage point | `--max-success-drop 0.001` |
| Monthly cost | up more than 10% | `--max-cost-increase 0.10` |
| Breaking point (with `--knee`) | down more than 10%, and by more than one step of the load grid | `--max-knee-drop 0.10` |

`--knee` finds both designs' breaking points (the highest load within the objectives) on **one**
load grid: six short constant-load runs from the lowest to the highest of the two designs'
suggested ranges (half to three times each design's load, at most 100,000 req/s and what the
engine's request limit allows), then six more on a finer grid shared by both, from the lower
breaking point to the first load above the higher one. So a change in the design's own load
alone does not move its breaking point; it takes up to twelve extra runs per design. A
breaking point is known to one grid step (the comment says how large), so a drop of one step is
not a regression; a design that misses its objectives even at the lightest load, where the
baseline did not, has regressed ("< 200 req/s" in the comment).

`--scenario` checks a scenario instead of the design as drawn. The Markdown is a pull request
comment: a verdict, one sentence ("p95 goes from 180 to 410 ms (objective 300 ms) — fails;
breaking point drops from 2.1k to 1.3k req/s; cost +$140 a month"), a table of the compared
metrics and one of the objectives. Text from the design (its name, part labels) is escaped, so
it cannot hide or fake the verdict. `--format json` (or `--json-out`) gives the same as data.
Output is UTF-8 on every platform. These are ArchSim's estimates under the design's
assumptions, and the comment says so.

## GitHub Action

`.github/actions/archsim-check` is a composite action: it sets up uv, runs the check, writes the
result to the job summary, uploads it as an artifact (`archsim-check-<design>`), posts or
updates **one** comment per design (and scenario) on the pull request, and fails the job when
the check fails or cannot run. The comment is found by a hidden marker naming the design, and
only a comment the job's own account posted (`comment-author`, `github-actions[bot]` for the
workflow's token) is updated: pushes update it instead of adding comments, several designs (or
a matrix) each keep their own, and a comment someone else writes with the marker is left alone.
When the check cannot run (exit 2), the comment says so instead of keeping the last verdict.
The action is not published as a package or a Marketplace action.

In **this** repository, use it by path:

```yaml
# .github/workflows/archsim.yml
name: ArchSim
on:
  pull_request:
    paths: ["archsim/*.json", "k8s/**", "terraform/**"]
permissions:
  contents: read
  pull-requests: write   # the sticky comment
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with: {fetch-depth: 0}   # the base branch, for --baseline-ref
      - uses: ./.github/actions/archsim-check
        with:
          design: archsim/design.json
          baseline-ref: origin/${{ github.base_ref }}
          fail-on: objectives,regression
```

`./.github/actions/archsim-check` only exists in a checkout of ArchSim itself. From **another**
repository, reference the action in the ArchSim repository with a commit you trust (GitHub
downloads that whole repository with the action, and the action runs ArchSim from it):

```yaml
      - uses: actions/checkout@v7
        with: {fetch-depth: 0}
      - uses: <owner>/archsim/.github/actions/archsim-check@<commit-sha>
        with:
          design: archsim/design.json
          baseline-ref: origin/${{ github.base_ref }}
          fail-on: objectives,regression
```

Inputs: `design` (required, relative to the workspace), `scenario`, `baseline-ref`, `fail-on`
(comma separated), `knee`, `max-p95-increase`, `max-cost-increase`, `comment` (`true`),
`comment-author`, `archsim-directory` (another ArchSim checkout to run, relative to the
workspace; empty runs the action's own repository). Outputs: `passed`, `exit-code`. Pull
requests from forks get a read-only token, so the comment step may fail there without failing
the job; the job summary always has the result.

## Checking infrastructure changes

With [import](import.md), a change to Kubernetes manifests, a compose file or a Terraform plan
can be checked by regenerating the design on both sides and comparing them:

```bash
git show origin/main:k8s/app.yaml | uv run archsim import k8s - -o /tmp/base.json
uv run archsim import k8s k8s/app.yaml -o /tmp/head.json
uv run archsim check /tmp/head.json --baseline /tmp/base.json --fail-on regression
```

An imported design has default traffic and no objectives (infrastructure files state neither),
so regressions are the useful gate there: replicas, sizes and topology changes move p95, the
breaking point and cost. A team that keeps `archsim/design.json` with its own traffic and
objectives can check that file instead.

This repository's CI runs the action on `examples/cache-aside-starter.json`
(`.github/workflows/ci.yml`, job `archsim-check`): pull requests against their base branch
(`origin/<base>`), pushes against the previous commit (`HEAD~1`).

## This repository's Actions minutes

The repository is private, so its workflows run on the account's 2,000 included Actions minutes
a month (a Windows minute counts as two). When they run out, GitHub stops starting jobs until
the month resets ("The job was not started because ... your spending limit needs to be
increased"). In November 2026 a full CI run on every push to main, about 44 billed minutes
each, used the month's minutes in five days. So:

- **CI runs only the jobs a change can affect.** The `changes` job runs
  `scripts/ci_changes.py` on the files a pull request or push changes; its log says which file
  needs each job. A change to docs (other than the ones the Python tests read), the README or
  `.claude/` runs no job but `Commit messages`; a web change runs Web and End-to-end; a Java
  change runs Java and End-to-end. A path no rule names runs every job, and so does a change to
  CI itself, a push that starts a branch or rewrites history, and a run started by hand
  (Actions > CI > Run workflow). When a test starts reading a file from another part of the
  repository, add it to the script's `RULES`.
- **The engine's and the benchmarks' tests run only when they can fail.** They are about 7 and
  9 of the Python job's minutes. The engine is frozen at 0.16.0, and the benchmarks' tests
  calibrate, simulate and render `docs/validation.md` with it, so the Python job runs them
  (steps `Engine tests` and `Benchmark tests`) only on a change to the engine, the Python
  environment, the templates or a file they read (`engine_tests` and `benchmark_tests` in the
  script's `RULES`). A change to the migrations (`services/api`), the MCP server or a script
  runs the rest of the Python tests, about a minute.
- **Pushes to main never cancel one another**, since each push's run tests only what that push
  changed. A pull request's new commits still cancel its older run. A later push that skips a
  job does not retest it: when a job fails, its failure is in that push's run, and the job only
  runs again on a change it reads, or a run by hand.
- **The accuracy benchmarks run by hand only** until a run passes on a hosted runner; the weekly
  and release-tag runs are commented out in `.github/workflows/benchmark.yml`.
- **Dependabot's version updates are monthly.** Every update pull request, and every rebase of
  one when main moves, runs CI.

Usage so far this month: Settings > Billing and licensing > Usage, or the run times under
Actions.
