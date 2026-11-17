# An SLO gate in CI

`archsim check` simulates a design and answers two questions a pull request raises: does it
still meet its objectives, and is it worse than the base branch?

```bash
archsim check archsim/design.json --baseline-ref origin/main \
  --fail-on objectives --fail-on regression --format markdown --out check.md
```

`archsim` is the Java CLI (`./gradlew :archsim-cli:installDist`, then
`archsim-cli/build/install/archsim/bin/archsim`; see
[archsim-cli/README.md](../archsim-cli/README.md#archsim-check)).

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

`.github/actions/archsim-check` is a composite action: it builds ArchSim's Java CLI into a Docker
image from the ArchSim checkout, runs the check in it, writes the result to the job summary, uploads
it as an artifact (`archsim-check-<design>`), posts or updates **one** comment per design (and
scenario) on the pull request, and fails the job when the check fails or cannot run. The comment is
found by a hidden marker naming the design, and only a comment the job's own account posted
(`comment-author`, `github-actions[bot]` for the workflow's token) is updated: pushes update it
instead of adding comments, several designs (or a matrix) each keep their own, and a comment someone
else writes with the marker is left alone. When the check cannot run (exit 2), the comment says so
instead of keeping the last verdict. The comment is posted with `actions/github-script` and the
job's token ([ADR-0064](adr/0064-keep-python-only-for-the-harness-and-the-repositorys-tooling.md)):
the runner needs no Python. The action is not published as a package or a Marketplace
action, and its image is never pushed to a registry: each job builds it
([ADR-0060](adr/0060-run-the-check-action-in-an-image-the-job-builds.md)).

The action needs a **Linux runner with Docker** (`ubuntu-latest` has both); on another runner it
stops with exit code 2. Building the image takes a few minutes on a GitHub-hosted runner, most of it
downloading Gradle and the build's dependencies; the build's log is in the step's collapsed "Build
the ArchSim image" group. The check runs in the container without a network, as the runner's user,
with the workspace mounted read-only and a folder of the action's own to write its results in;
the image is removed once the check has run. A runner that is not Linux, an image that does not
build or a container Docker cannot run is a check that could not run (exit 2), and the comment
says which.

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
downloads that whole repository with the action, and the action builds ArchSim from it):

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
`comment-author`, `archsim-directory` (another ArchSim checkout to build and run, relative to the
workspace; empty builds the action's own repository). Outputs: `passed`, `exit-code`. Pull
requests from forks get a read-only token, so the comment step may fail there without failing
the job; the job summary always has the result.

## Checking infrastructure changes

With [import](import.md), a change to Kubernetes manifests, a compose file or a Terraform plan
can be checked by regenerating the design on both sides and comparing them:

```bash
git show origin/main:k8s/app.yaml | archsim import k8s - -o /tmp/base.json
archsim import k8s k8s/app.yaml -o /tmp/head.json
archsim check /tmp/head.json --baseline /tmp/base.json --fail-on regression
```

An imported design has default traffic and no objectives (infrastructure files state neither),
so regressions are the useful gate there: replicas, sizes and topology changes move p95, the
breaking point and cost. A team that keeps `archsim/design.json` with its own traffic and
objectives can check that file instead.

This repository's CI runs the action on `examples/cache-aside-starter.json`
(`.github/workflows/ci.yml`, job `archsim-check`): pull requests against their base branch
(`origin/<base>`), pushes against the previous commit (`HEAD~1`).

## Release images

`.github/workflows/release.yml` runs on a release tag (`v1.0.0`). It checks that the tag is on main
and, with archsim-tools' `CheckRelease` run from its source, that the tag names the version the
checkout says, `version` in `gradle.properties`
([ADR-0076](adr/0076-version-archsim-apart-from-its-engine.md)), and that `docker-compose.yml` and
the hosted kit's `deploy/compose.yaml`
([ADR-0080](adr/0080-host-a-public-server-from-a-compose-kit.md)) run both images at
it. Then it publishes `ghcr.io/dante0747/archsim-app` (Jib) and
`ghcr.io/dante0747/archsim-web` (`apps/web/Dockerfile`), for amd64 and arm64, tagged with the
version and, unless it is a pre-release, `latest`
([ADR-0077](adr/0077-publish-release-images-to-ghcr-and-run-them-from-compose.md)). Its jobs use
their own token, with `packages: write`, and no other secret. To release:

1. In one commit, set `version` in `gradle.properties`, run
   `npm version <version> --no-git-tag-version` in `apps/web`, set the two images' tags in
   `docker-compose.yml` and `deploy/compose.yaml`, and fold the CHANGELOG. The web app's and
   archsim-app's tests fail until the version is the same in all of them.
2. Once that commit is on main and its checks pass, tag it `v<version>` and push the tag.
3. After the first release, make both packages public, once: the package's page on GitHub >
   Package settings > Change visibility. GHCR makes a new package private.
4. Once the workflow has published the images, publish a GitHub release for the tag. With the
   repository variable `DEMO_ENABLED` set, `.github/workflows/deploy-demo.yml` then moves the public
   demo to it over SSH, unless it is a pre-release
   ([Hosting a public server](deployment.md#10-upgrade-to-a-new-release)).

## Which jobs a change runs

Public repositories run on GitHub's standard runners free of charge. Until 1.0.0 this one was
private, on the account's 2,000 included Actions minutes a month: in November 2026 a full CI run
on every push to main, about 44 minutes each, used them in five days, and GitHub stopped starting
jobs. CI still runs no more than a change needs, so that a run is short:

- **CI runs only the jobs a change can affect.** The `changes` job runs archsim-tools'
  `ChangedParts`, from its source, on the files a pull request or push changes; its log says which
  file needs each job. A change to `.claude/` or to a root file only people read (`AGENTS.md`,
  `CONTRIBUTING.md` and the like) runs no job but `Commit messages`; a change to docs, the README,
  the changelog or `NOTICE` runs the website's build, which fails on any broken link or anchor, and
  its browser checks (and Java too for the docs the Java tests read); a web change runs Web,
  End-to-end and the website's checks; a change that deletes a file, wherever it is, runs the
  website's checks as well, since the docs may link to it; a Java change runs Java and End-to-end,
  and a change to the CLI, the engine, the model or the Gradle build
  (Dependabot's Gradle updates included) also runs `ArchSim check`, whose image build (several
  minutes cold, [ADR-0060](adr/0060-run-the-check-action-in-an-image-the-job-builds.md)) costs more
  than the Python action's set-up did. A path no rule names runs every job, and so does a change to
  CI itself, a push that starts a branch or rewrites history, and a run started by hand (Actions >
  CI > Run workflow). When a test starts reading a file from another part of the repository, add it
  to archsim-tools' `Rules`.
- **Pushes to main never cancel one another**, since each push's run tests only what that push
  changed. A pull request's new commits still cancel its older run. A later push that skips a
  job does not retest it: when a job fails, its failure is in that push's run, and the job only
  runs again on a change it reads, or a run by hand.
- **The accuracy benchmarks run by hand only** until a run passes on a hosted runner; the weekly
  and release-tag runs are commented out in `.github/workflows/benchmark.yml`.
- **Dependabot's version updates are monthly.** Every update pull request, and every rebase of
  one when main moves, runs CI. Each ecosystem's group takes minor and patch releases only; a
  major release opens a pull request of its own, so it never turns the group red. Dependabot leaves
  out the libraries the version catalog pins at Spring Boot's versions (SnakeYAML, HttpClient 5,
  slf4j-nop): they move with a Spring Boot upgrade. It also offers the tests' embedded PostgreSQL
  only within the major the product runs, 16.

The run times are under Actions.
