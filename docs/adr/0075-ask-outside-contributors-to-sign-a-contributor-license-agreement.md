---
status: proposed
date: 2026-11-15
decision-makers: Majid Abarghooei (owner)
---

# 0075. Ask outside contributors to sign a contributor license agreement on their pull request, AI-assisted work included

## Context and Problem Statement

[ADR-0067](0067-license-under-agpl-3.0-with-a-commercial-license.md) licenses ArchSim under the
AGPL-3.0 and has its copyright holder offer a commercial license too. That needs the right to
license every outside contribution both ways, so ADR-0067 merges one "only under a Contributor
License Agreement", which was not written. Which agreement, how does a contributor sign it, and
what does it say about code that an AI assistant wrote, now that most contributors use one and
the repository ships instructions for them (`AGENTS.md`, `CLAUDE.md`, `.claude/`)?

## Decision Drivers

- The copyright holder may license each contribution under the AGPL-3.0 and commercially.
- Contributing stays easy: one signature, on GitHub, without a third-party service.
- Contributors can trust that their work stays open source.
- AI-assisted contributions are welcome, and a person answers for each of them.
- Nothing runs with write access on a pull request's code.

## Considered Options

- A license agreement in the Apache ICLA's shape, signed by a comment on the pull request and
  recorded by CLA Assistant Lite in the repository
- The same agreement, recorded by the hosted CLA Assistant (cla-assistant.io)
- A copyright assignment
- The Developer Certificate of Origin (`Signed-off-by`)

## Decision Outcome

Chosen option: "a license agreement in the Apache ICLA's shape, recorded by CLA Assistant Lite",
because it gives the copyright holder the rights the dual license needs while contributors keep
their copyright, and its signatures stay in the repository with no outside service.

- **The agreement**, `CLA.md`, written for ArchSim, follows the Apache Individual Contributor
  License Agreement's shape. The contributor grants a copyright license that may be sublicensed
  under any terms, the AGPL-3.0 and the commercial license included, and a patent license that ends
  for anyone who sues over the contribution. In return the copyright holder promises that every
  version including the contribution stays available under the AGPL-3.0-only or a later license
  the Open Source Initiative approves.
- **AI-assisted work** (section 6): the contributor confirms having read, understood and tested
  what an assistant produced, answers for it as their own, knows of no third-party work in it
  that they could not submit, used the assistant on terms that leave them the rights they grant,
  and names the assistant in the pull request. Commit messages keep refusing AI co-author
  trailers (`CheckCommits`): the agreement is signed by a person, who is the author. An agent may
  not open or update issues and pull requests unattended. `CONTRIBUTING.md` and the pull request
  template say this.
- **Signing**: `.github/workflows/cla.yml` runs CLA Assistant Lite (v2.6.1, pinned to its commit).
  A contributor signs once by commenting a fixed sentence; the action records the GitHub user,
  the date and the pull request in the `cla-signatures` branch and sets a check on the pull
  request. The owner and the bots are exempt.
- **Write access, but no pull request code**: the workflow runs on `pull_request_target`, so it can
  comment on a pull request from a fork; it never checks out or runs that pull request's code.
- **Off until the lawyer approves**: the job runs only when the repository variable `CLA_ENABLED`
  is `true`. Until then `CLA.md` says it is a draft, and outside pull requests wait.

This ADR is accepted when the owner puts the agreement in force: a lawyer has reviewed `CLA.md`
and settled its bracketed points (the date, section 4, an employer's agreement, the governing
law), the `cla-signatures` branch exists, and `CLA_ENABLED` is set.

### Consequences

- Good, because each outside contribution can be offered under both licenses, as ADR-0067 needs.
- Good, because contributors keep their copyright and section 4 keeps their work open source.
- Good, because the rules for AI-assisted work are written down and signed, not left to review.
- Good, because the signatures are in the repository: no outside service holds contributors'
  data, and nothing is lost if one closes.
- Bad, because a contributor signs before their first pull request can be merged, which some will
  not do.
- Bad, because the workflow runs with write access on outside pull requests; it is safe only while
  it never checks out their code, which review of any change to it must keep.
- Bad, because a contributor whose employer owns their work needs the employer's permission, which
  the individual agreement only asserts.

### Confirmation

`CLA.md`, `CONTRIBUTING.md` ("Working with AI assistants", "Licensing of contributions") and the
pull request template say the same. `cla.yml` checks out no code. Once it is on, a pull request
from an account that has not signed shows a failing "Contributor License Agreement" check.

## Pros and Cons of the Options

### The hosted CLA Assistant

- Good, because it needs no workflow and stores signatures for many repositories.
- Bad, because a third-party service holds the signatures and needs access to the repository.

### A copyright assignment

- Good, because the copyright holder owns everything and can relicense without limits.
- Bad, because many contributors, and their employers, refuse to give their copyright away.

### The Developer Certificate of Origin

- Good, because signing off each commit is light and familiar.
- Bad, because it certifies the right to submit under the project's license only: it grants no
  right to license the work commercially, which the dual license needs.

## More Information

- [ADR-0067](0067-license-under-agpl-3.0-with-a-commercial-license.md): the AGPL-3.0 and the
  commercial license.
- [CLA Assistant Lite](https://github.com/contributor-assistant/github-action), the GitHub Action.
- The [Apache Individual Contributor License Agreement](https://www.apache.org/licenses/contributor-agreements.html),
  whose shape `CLA.md` follows in its own words.
