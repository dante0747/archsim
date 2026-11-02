---
name: conventional-commits
description: Write and check ArchSim commit messages as Conventional Commits (type(scope) - description). Use whenever creating a commit, amending or rewording one, or reviewing a branch's commit messages in this repository.
---

# Conventional Commits

Every commit message in ArchSim follows [Conventional Commits](https://www.conventionalcommits.org).
`scripts/check_commits.py` checks it: the `commit-msg` hook on each commit (`make hooks` or
`git config core.hooksPath .githooks`), and CI on every commit of a pull request.

## The header

```
type(scope): description
type(scope)!: description of a breaking change
```

- **type**, one of:

  | Type | For |
  | --- | --- |
  | `feat` | a new capability for users or callers |
  | `fix` | a bug fix |
  | `refactor` | a code change that changes no behaviour |
  | `test` | tests only |
  | `docs` | documentation only (including ADRs) |
  | `style` | formatting only, no code change |
  | `perf` | faster or leaner, same behaviour |
  | `build` | the build, dependencies, Gradle, uv, npm |
  | `ci` | CI workflows |
  | `chore` | anything else that ships nothing (tooling, housekeeping) |
  | `revert` | reverting an earlier commit |

- **scope**, optional, lower case: the area changed. In use here: `engine`, `api`, `app`,
  `model`, `web`, `java`, `parity`, `benchmarks`, `pricing`, `sizing`, `auth`, `calibration`,
  `import`, `templates`, `mcp`, `adr`, `deps`, `e2e`, `docker`, `security`.
- **`!`** marks a breaking change; explain it in a `BREAKING CHANGE:` footer.
- **description:** imperative mood ("add", not "added" or "adds"), starts in lower case (a
  name such as `CQRS` or `TypeScript` excepted), no period at the end. The whole header is at
  most 100 characters.

## The body

Optional, after a blank line: **why** the change was made, and anything a reviewer needs that
the diff doesn't show. Wrap at about 100 characters.

## Rules

- One logical change per commit, and each commit builds and passes its tests.
- **No AI co-author trailer** and no "Generated with ..." line: commits are the author's own,
  under the author's configured git identity. The checker rejects such trailers.
- Don't amend or rewrite commits that others already have.

## Examples (from this repository)

```
feat(model): validate designs as the Python engine does, word for word
fix(benchmarks): judge p95 within a factor of two either way
refactor(model): build the model's packages in layers and check them with ArchUnit
test(api): port the sign-in and token tests to the black-box suite
docs(adr): propose ADRs 0019 to 0021 for the port's stage-0 decisions
build(java): format in the Spring style and enforce Checkstyle
ci(java): build the Java code and run the black-box API tests in CI
```

## Check before pushing

```bash
uv run python scripts/check_commits.py --range main..HEAD
```
