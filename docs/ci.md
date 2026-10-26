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
| 1 | Fails: an objective is missed (`--fail-on objectives`, the default) or a metric regressed past its threshold (`--fail-on regression`). |
| 2 | The input cannot be used: unreadable JSON, an invalid design (the issues are printed), a run over the limits. |

Regressions compare the design with a baseline: `--baseline other.json`, or the same file at a
git ref with `--baseline-ref origin/main` (a design that does not exist there has no baseline
and is checked against its objectives only). Thresholds, all changeable:

| Metric | Regression when | Option |
| --- | --- | --- |
| p95 latency | up more than 10% | `--max-p95-increase 0.10` |
| Success rate | down more than 0.1 percentage point | `--max-success-drop 0.001` |
| Monthly cost | up more than 10% | `--max-cost-increase 0.10` |
| Breaking point (with `--knee`) | down more than 10% | `--max-knee-drop 0.10` |

`--knee` finds both designs' breaking points (the highest load within the objectives, six
short constant-load runs each), so it takes several times longer. `--scenario` checks a scenario
instead of the design as drawn. The Markdown is a pull request comment: a verdict, one sentence
("p95 goes from 180 to 410 ms (objective 300 ms) — fails; breaking point drops from 2.1k to
1.3k req/s; cost +$140 a month"), a table of the compared metrics and one of the objectives.
`--format json` (or `--json-out`) gives the same as data. These are ArchSim's estimates under the
design's assumptions, and the comment says so.

## GitHub Action

`.github/actions/archsim-check` is a composite action: it sets up uv, runs the check, writes the
result to the job summary, uploads it as the `archsim-check` artifact, posts or updates **one**
comment on the pull request (found by a hidden marker, so pushes update it instead of adding
comments), and fails the job when the check fails. It runs ArchSim from a checkout of this
repository (`archsim-directory`, this repository by default); it is not published as a package
or a Marketplace action.

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

Inputs: `design` (required), `scenario`, `baseline-ref`, `fail-on` (comma separated), `knee`,
`max-p95-increase`, `max-cost-increase`, `comment` (`true`), `archsim-directory`. Output:
`passed`. Pull requests from forks get a read-only token, so the comment step may fail there
without failing the job; the job summary always has the result.

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

This repository's CI runs the action on `examples/cache-aside-starter.json` against the previous
commit (`.github/workflows/ci.yml`, job `archsim-check`).
