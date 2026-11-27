---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0109. Give each workspace a project cap and a quota of stored bytes

## Context and Problem Statement

Nothing bounded what a workspace stores. A sandbox holds five projects
([ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)). Personal and team
workspaces hold any number. Each run stores its report, and its design unless a run of the same
design stored it
([ADR-0110](0110-store-a-runs-design-once-per-digest-and-delete-it-with-its-last-run.md)), a share
link a copy of a run's results, and a design may be as large as the body cap
(`ARCHSIM_MAX_BODY_BYTES`, 5 MB), so one person could fill the hosting kit's 20 GB disk, which also
holds seven nightly dumps ([deployment.md](../deployment.md)). The rate limits bound how fast, not
how much.

How much may a workspace store, and what happens when it is full?

## Decision Drivers

- No workspace can fill the server's disk on its own.
- The limit is checked where data is written, with an answer that says what to do.
- Ordinary use seldom meets it. Measured on PostgreSQL 16, a run of a bundled example stores about
  50 to 100 KB (its report is 147 to 299 KB of JSON, which PostgreSQL compresses to 47 to 95 KB),
  and a 15.5 KB design stores 6.2 KB. 100 MB holds about 1,000 to 2,000 runs and 250 MB about
  3,000, so someone who keeps every run of the hosted 60 an hour fills a personal workspace in under
  two days, and then deletes old runs.
- One query, no new table: the bytes are measured where they are stored.

## Considered Options

- A quota of stored bytes and a project cap per kind of workspace, checked before each write.
- A quota per person, across every workspace they own.
- A counter of bytes per workspace, kept up to date on each write.

## Decision Outcome

Chosen option: "a quota of stored bytes and a project cap per kind of workspace, checked before each
write", because it bounds each workspace with the limits the API already has per kind
(`WorkspaceLimits`) and needs no new state.

- **A workspace's stored bytes** are its projects' designs, the designs its runs simulate, its
  runs' reports and summaries, and its share links' copies, as PostgreSQL stores the values
  (`pg_column_size`: compressed, without TOAST's own overhead, indexes or old row versions), summed
  in one query by workspace (`WorkspaceStorage`). A design is stored once however many runs
  simulate it, and counts once, in full, in each workspace whose runs simulate it: what a
  workspace stores then depends on its own rows only, tells nothing of other workspaces, and falls
  when its own runs go, whether someone deletes them or the hourly prune does
  ([ADR-0111](0111-prune-the-runs-past-a-projects-limit-and-expired-rows-every-hour.md)). The
  quotas together may count a design that several workspaces simulate more than once, which bounds
  the disk more tightly, never less.
- **The quotas**: a sandbox 10 MB, always; with the hosted limits a personal workspace 100 MB and a
  team 250 MB. Without the hosted limits only sandboxes have one, as with their other limits.
- **The project caps**, with the hosted limits: a personal workspace 100 and a team 500, besides a
  sandbox's five.
- **Enforced before each write that stores a design or results**: making, importing, duplicating or
  copying a project, saving a design (whatever its size), starting runs (each stores its design
  unless a run of it did, and later its report) and making a share link. A workspace that stores
  its quota or more gets `409`: "This workspace stores as much as it may (100 MB): delete runs,
  projects or share links first." Renaming a project, and deleting anything, still work.
- **Writes take turns.** The check locks the workspace until the write's transaction ends
  (`Access.lockWorkspace`, as the project cap already did), so writes made at once see what the
  ones before them stored. Starting runs and making a share link are checked twice: first without
  the lock, so that an ordinary refusal costs none of the hour's runs or the day's links, then with
  it, in `WorkspaceStorage.writeInTurn`'s transaction, which stores them. The charge between the
  two counts before that transaction begins
  ([ADR-0105](0105-charge-every-rate-limit-before-its-transaction-begins.md)), so the lock is
  never held while a charge waits for a connection.
- **What it leaves out.** Comments (at most 4,000 characters, 240 an hour a person), sign-off notes
  (2,000 characters, 60 an hour), project descriptions (4,000 characters, one a project) and the
  history (a row per change) are neither counted nor checked. The first three are kilobytes where
  designs and results are megabytes, and their own limits bound them. The history is a few hundred
  bytes a change, and only deleting the workspace deletes it, so a quota that counted it could
  leave a workspace full whatever its members deleted.

### Consequences

- Good, because what a workspace stores is bounded: its quota of 10 MB, 100 MB or 250 MB, plus the
  one write that found it below its quota, about 5 MB at most, and the reports of runs it had
  queued.
- Good, because the answer names the limit and what to delete.
- Bad, because a workspace can still pass its quota by what the writes under way store: the one
  write that found it below its quota (a design or a share link's copy, up to about 5 MB, as a
  batch or a seed group stores its one design once) and the reports of runs it queued while it was
  below its quota, which the worker writes after the check (about 50 to 100 KB each). After that it
  takes nothing new.
- Bad, because a quota bounds each workspace, not what one person stores across workspaces: making
  team workspaces is limited to ten a day, which bounds how fast, not how much. A quota per person
  is a further step.
- Bad, because runs and share links that the second check refuses, when another write filled the
  workspace between the two, have been counted against the hour or the day.
- Bad, because a workspace's writes that store more take turns, each holding the workspace's lock
  until its transaction ends, and the sum reads each of the workspace's rows: a few milliseconds for
  a workspace of 3,000 runs.

### Confirmation

`ProjectStorageTests` (a full sandbox refuses a new project and a new design, and still renames),
`RunStorageTests` (a full sandbox starts no run, and a start that waited for another write checks
again), `RunChargesTests` (runs the quota refuses cost none of the hour's), `ShareStorageTests` (a
full sandbox makes no share link and costs its maker none of the day's, and a link that waited for
another write checks again), `ProjectRacesTests` (saving a design locks the workspace),
`HostedProjectCapTests` (a personal workspace holding 100 projects refuses one more),
`WorkspaceStorageTests` (what counts, what does not, and the lock), `RunDesignStorageTests` (a
design counts once in each workspace whose runs simulate it, until its runs there go, also by the
hourly prune) and `WorkspaceLimitsTests` (the numbers); the black-box `LimitsTests` check the
refusals over HTTP. The refusals fail against the code before.

## Pros and Cons of the Options

### A quota per person

- Good, because it bounds what one person stores across all their workspaces.
- Bad, because a team's data belongs to all its members, and deciding whose quota it counts against
  when owners change is a design of its own.

### A counter of bytes per workspace

- Good, because a check reads one row.
- Bad, because every write, every deletion and every cascade must keep it right, in a new column
  that a migration fills; a sum over the rows cannot drift.

## More Information

- [ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md): a sandbox's
  limits, which this extends with a quota.
- [docs/api.md](../api.md), "Limits", [SECURITY.md](../../SECURITY.md) and
  [deployment.md](../deployment.md) list the numbers.
