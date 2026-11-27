---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0110. Store a run's design once per digest, and delete it with its last run

## Context and Problem Statement

Each run stored a whole copy of the design it simulates, in `simulation_runs.input_document`. Runs
of the same design each stored another copy: the runs of a seed group or a batch of scenarios, a
rerun, the same template run in many sandboxes. So the database grew with every run rather than
with the designs people make, which a review finding pointed out.

How should a run's design be stored so that it is kept once, while every run still reads and
simulates exactly the design it was queued with, and a design leaves the database with the last run
that refers to it, whatever deletes the runs?

## Decision Drivers

- One stored copy of each design, however many runs simulate it.
- What reads a run's design reads the same JSON as before: `GET /runs/{id}/input`, reruns, share
  links, seed groups' objectives and the worker.
- No design outlives the last run that refers to it, whether runs are deleted on request, with
  their project or workspace (the schema cascades), with an expired sandbox or by the hourly prune
  of the runs past a project's limit
  ([ADR-0111](0111-prune-the-runs-past-a-projects-limit-and-expired-rows-every-hour.md)), and none
  of those has to remember it.
- A run queued with a stored design while that design's last other run is deleted neither fails
  nor loses its design, and neither does the deletion.
- The designs runs stored before move in the same migration, one copy of each.

## Considered Options

- A table of designs keyed by digest, which runs refer to, and a trigger that deletes the designs
  whose last run is deleted.
- The same table, with a periodic job that deletes the designs no run refers to.
- The same table, with each way of deleting runs deleting the designs it leaves behind.
- The same table, designs shared within a workspace only.
- A copy per run, as now.

## Decision Outcome

Chosen option: "a table of designs keyed by digest, and a trigger", because it keeps each design
once, and no way of deleting runs, today's or a later one, can leave a design behind.

- **`run_designs (digest, document)`** holds each design once. The digest is the SHA-256 of the
  design's JSON as PostgreSQL writes `jsonb` out, computed in SQL
  (`encode(sha256(convert_to(document::text, 'UTF8')), 'hex')`): `jsonb` keeps its own key order
  and spacing, so a design has one digest whether the migration moved it or the API stored it, and
  however the request wrote it. `simulation_runs.design_digest` refers to it (`NOT NULL`, a
  foreign key, indexed). It is not `input_digest`, the engine's digest of the resolved document a
  successful run simulated, which is known only when the run ends.
- **A run and its design are stored in one statement** (`RunRepository.add`): `INSERT … ON
  CONFLICT (digest) DO UPDATE` stores a new design, or updates a stored one to itself, which locks
  its row until the run's transaction ends; the run then refers to it. Any statement that makes a
  run refer to a design stores the design this way: one that only copied a digest from another
  run would lock the design only in its foreign-key check, after the run's row is written, and
  would then fail on the foreign key where the upsert stores the design again.
- **A statement-level trigger, `AFTER DELETE ON simulation_runs`,** deletes the deleted runs'
  designs that no run refers to any more. It locks those designs first, in digest order, and then
  reads which runs remain in a statement of its own, which, under READ COMMITTED (the isolation the
  application uses), sees every run committed before it begins. So when a run is queued with a
  design while the design's last other run is deleted, whichever transaction takes the design's
  row first, the other waits for it: the deletion then sees the new run and keeps the design, or
  the new run stores the design again. Under REPEATABLE READ, such a deletion would fail with a
  serialization error instead, and delete nothing.
- **`V4` moves the copies runs carried:** one per digest into `run_designs`, then each run's
  digest. It drops `input_document` and writes every run again, which frees the copies' space for
  reuse once PostgreSQL vacuums the table, all under a lock on the runs.

### Consequences

- Good, because the database grows with the distinct designs: a seed group, a batch of scenarios
  or a rerun stores one design, and so do the sandboxes that run the same template.
- Good, because a run's, a project's or a workspace's deletion, sandbox pruning and the hourly prune
  delete the designs they leave behind, in their own transaction, with no change to their code.
- Good, because a run's design reads as the same JSON as before, so the API, reruns, share links
  and the worker are unchanged.
- Neutral, because one stored design can be shared by runs in several workspaces: it is read only
  through a run, which its workspace's membership guards, as before.
- Bad, because a delete on `simulation_runs` does more than its SQL says, where
  [ADR-0008](0008-jdbcclient-and-flyway.md) wants what runs to be what the code says. The trigger
  is written in the migration, and `RunRepository` says what it does.
- Bad, because transactions that queue or delete runs of one design take turns on its row until
  they end. They are short, but two transactions can each hold a row the other needs; PostgreSQL
  then fails one of them, which a request answers with an error. The hourly prune skips the runs
  another instance is deleting, but waits for the rows of its runs' designs that another
  transaction holds, like any other deletion; two prunes delete different runs and lock designs
  in one order, so they only take turns.
- Bad, because the migration rewrites the table of runs under a lock: on a database with many
  runs, the upgrade takes a while.

### Confirmation

`RunDesignsTests` queues runs through the API: a second run of a design, a seed group and runs in
two workspaces store one design; deleting a run keeps a design another run simulates, and deleting
a design's last run, its project or its workspace deletes it. `SandboxPruningTests` checks that an
expired sandbox's designs go with it. `RunDesignLocksTests` queues a run of a design while the
design's last other run is deleted, each way round, on two connections: neither fails and the new
run keeps its design; with a trigger that does not lock first, the deletion failed on the foreign
key. `RunDesignsMigrationTests` migrates runs whose designs repeat, one written with its keys in
another order: one copy of each is kept, each run reads its own design, `input_document` is gone,
and a run queued afterwards finds a moved design by the same digest.

## Pros and Cons of the Options

### A periodic job deletes the designs no run refers to

- Good, because a delete does only what its SQL says, and never waits for a design's row.
- Bad, because a design stays until the job's next run, and each run of the job reads every
  design's and every run's digest to find the few to delete.
- Bad, because the job still needs the same lock against a run queued with a design it deletes.

### Each way of deleting runs deletes the designs it leaves behind

- Good, because every delete is in the code.
- Bad, because a project's or a workspace's deletion deletes runs in a cascade the code does not
  see, so each would first collect its runs' digests, and every other way of deleting runs, such as
  the hourly prune, would have to know to do the same.

### Designs shared within a workspace only

- Good, because no stored design is shared between workspaces.
- Bad, because the most common repeat, a template run in many sandboxes, would still be stored once
  per sandbox, and deleted runs would still need the trigger or a job.

### A copy per run

- Good, because nothing changes.
- Bad, because PostgreSQL already compresses large `jsonb` values: storage keeps growing with every
  run.

## More Information

- [ADR-0008](0008-jdbcclient-and-flyway.md): explicit SQL with `JdbcClient`, from which the
  trigger departs.
- [ADR-0032](0032-flyway-takes-over-from-alembic.md): Flyway owns the schema.
- [ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md): sandboxes, deleted
  with everything in them.
