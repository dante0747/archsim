---
status: accepted
date: 2026-11-02
decision-makers: Majid Abarghooei (owner)
---

# 0001. Record architecture decisions

## Context and Problem Statement

Until now ArchSim's decisions have lived in commit messages, `IMPLEMENTATION_STATUS.md`, the
proposals in `docs/proposals/` and conversations. A reader can see what the code does, but not why
one option won over another, or whether a choice is still meant to hold. The Java port
([ADR-0005](0005-port-to-java-25-in-stages.md)) brings a series of structural decisions that later
contributors will need to understand. The project is also meant to be a reference for engineers,
so its reasoning should be as easy to find as its code.

How should ArchSim keep its significant decisions?

## Considered Options

- Architecture Decision Records in `docs/adr/`, in the MADR 4 format
- A "Decisions" section inside each proposal or design document
- Commit messages and `IMPLEMENTATION_STATUS.md` only, as before

## Decision Outcome

Chosen option: "Architecture Decision Records in `docs/adr/`, in the MADR 4 format", because one
short, numbered file per decision gives each decision a stable link, a status and its reasons, and
MADR is a widely used, documented format. Proposals stay useful for planning, but a decision
outlives the proposal that led to it.

### Consequences

- Good, because a new contributor can read, in order, why the project looks the way it does.
- Good, because a decision that changes gets a new record that supersedes the old one, so the
  history stays visible.
- Bad, because each significant decision now costs a short document, written in the same change
  that acts on it.
- Bad, because decisions made before 2 Nov 2026 are recorded after the fact, from what the
  repository shows, and may miss reasons nobody wrote down.

### Confirmation

Reviews check that a change which makes a significant decision adds an ADR or supersedes one. The
[README](README.md) says what counts as significant.

## More Information

- [How to add an ADR, and the index](README.md)
- MADR: <https://adr.github.io/madr/>
- Michael Nygard, "Documenting Architecture Decisions" (2011), which introduced the practice:
  <https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions>
