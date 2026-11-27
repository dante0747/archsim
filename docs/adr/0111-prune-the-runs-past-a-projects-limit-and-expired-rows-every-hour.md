---
status: accepted
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0111. Prune the runs past a project's limit, and the rows that expired, every hour

## Context and Problem Statement

What the server stores has no ceiling. A run, with its design and report, goes only when someone
deletes it: `ARCHSIM_RUN_HISTORY_LIMIT` (200) shortens a project's list, and the runs past it stay,
unlisted. Expired sessions and invitations stay for ever, so do the frozen copies of expired share
links (only revoking deletes one), and so does every event of the history. Only the rate-limit
counters and expired sandboxes are pruned, every minute (`RateLimitPruning`, `SandboxPruning`). The
hosting kit is sized for 20 GB of disk, with seven nightly dumps on the same disk
([deployment.md](../deployment.md)), and its privacy section says an expired session's address
stays until its person signs out.

Which rows should the server delete, when, and how, given that several API instances may prune at
once?

## Decision Drivers

- What a project stores has a ceiling, without another setting to learn.
- Nothing goes that a worker is simulating or a review refers to.
- Personal data (a session's address and browser, an invitee's email, the addresses in the
  history) has an end that a privacy notice can state.
- Several API instances prune at once: deletes are idempotent and short, and no instance waits
  for the rows another is pruning.
- No migration: the queries use the indexes there are.

## Considered Options

- One hourly job in the API role that runs each feature's retention, in batches.
- A scheduled job per feature, as for the rate-limit counters and the sandboxes.
- Leave pruning to the operator, with documented SQL and a cron job.

## Decision Outcome

Chosen option: "one hourly job in the API role that runs each feature's retention, in batches",
because each rule stays in the feature that owns the rows, one schedule runs them all, and every
delete is a short transaction.

- **Runs.** A project keeps its newest `ARCHSIM_RUN_HISTORY_LIMIT` runs, the ones its list shows
  (200); the setting now means that. Older runs are deleted, oldest first, except:
  - runs a worker has yet to finish (queued or running);
  - runs with a sign-off or a comment: `review_signoffs.run_id` and `comments.run_id` are
    `ON DELETE SET NULL`, so deleting the run would quietly detach the review from the results it
    judged;
  - every run of a seed group any of whose runs is kept, as the group's summary reads them all. A
    group goes with its last run (`simulation_runs.group_id` is `ON DELETE SET NULL` too, so an
    empty group would linger).

  A share link is no reason to keep a run: it holds a frozen copy, and outlives its run by design.
  Runs are counted, not aged, so an idle project keeps its runs. One number, the list's, rather
  than a second setting for the runs kept: a run past the list is reachable only from a link, and
  the runs kept for a review stay reachable from the review. The statement that deletes reads the
  review's tables itself: the reviews build on runs, so the runs cannot ask them, and one
  statement sees a sign-off or comment already written.
- **Sessions** go at the first prune after they expire: an expired session answers as one that
  never was, and nothing reads its row.
- **Invitations** go at the first prune after they expire, accepted or not: a link used, expired
  or never made answers alike, and the row's one detail of value is the email it was sent to.
- **Share links** lose their frozen copy at the first prune after they expire, as revoking deletes
  it at once: an expiry cannot be moved, so the copy can never be shown again. The link stays in
  its workspace's lists, expired, with its title and views, as the API documents.
- **The history** keeps each event `ARCHSIM_HISTORY_DAYS` (365) days, then deletes it. A year
  covers a review cycle and the questions an address answers (who changed this, from where),
  and bounds both the table and how long the server keeps addresses; the sign-offs and comments
  themselves stay. Events go oldest first by id, which grows with time, so a prune reads the
  events it deletes and one batch more, never the whole table, which has no index on its time.
- **The job.** `Pruning`, in the API role only, runs each feature's `Retention` when the API
  starts and every hour after (a fixed delay). A retention deletes in batches (100 runs or copies,
  1,000 of the smaller rows), each one statement, so a transaction of its own, which selects its
  rows `FOR UPDATE SKIP LOCKED`: instances pruning at once share the rows rather than wait for
  them, and find nothing the other deleted. A batch of runs still waits for a transaction that
  holds one of their designs, until that transaction ends, as a design goes with its last run
  ([ADR-0110](0110-store-a-runs-design-once-per-digest-and-delete-it-with-its-last-run.md)). A
  retention the database fails is logged and tried again at the next prune; the others still run.
  Hourly, because nothing here is urgent and each query is cheap: a project passes its limit by an
  hour's runs at most, and an expired row stays an hour at most. The rate-limit counters and the
  sandboxes keep their minute: a guest's sandbox must go on time, and the counters churn.
- **No migration.** Sessions go through `ix_sessions_expires_at`; runs through
  `ix_runs_project_created`, `ix_simulation_runs_group_id`, `ix_review_signoffs_run_id` and
  `ix_comments_run_id`; the history through its primary key. Invitations and share links are small
  tables, read whole. The one query that grows with a whole table is the count of each project's
  runs, which finds the projects past their limit by reading `ix_simulation_runs_project_id`
  whole. That is acceptable once an hour: the index holds a project id per run, a small fraction
  of the runs' rows with their designs and reports, which the limit now bounds.

### Consequences

- Good, because a project's runs, the largest rows, have a ceiling: its limit, and the runs a
  review or a seed group keeps.
- Good, because a privacy notice can say how long each kind of personal data stays, and
  deployment.md's Privacy section now does.
- Good, because each rule lives with the rows it deletes, and another kind of row needs only
  another `Retention` bean.
- Bad, because the run limit now deletes: a server that lowered `ARCHSIM_RUN_HISTORY_LIMIT` to
  shorten the list loses its older runs at the first prune after the upgrade, which the
  changelog says.
- Bad, because the runs a review refers to still have no ceiling: a project whose every run has a
  comment keeps them all. The workspace's quota of stored bytes
  ([ADR-0109](0109-give-each-workspace-a-project-cap-and-a-quota-of-stored-bytes.md)) bounds them.
- Bad, because the first prune after the upgrade may delete many rows. The batches keep each
  transaction short; the nightly dumps keep what it deleted for seven days. Spring runs every
  fixed-delay task on one scheduler thread, so the minute prunes of the rate-limit counters and
  the sandboxes wait while a long prune like that one runs.
- Neutral, because the history goes in the order it was recorded: events a server with a fast
  clock stamped go when their own time comes, and a batch's worth of them in a row holds back the
  events after them until then.

### Confirmation

`StartupPruningTests` start the API on a database holding each kind of row past its retention and
see it go; each failed before this change. The retentions' tests (`RunRetentionTests`,
`RunRetentionGroupsTests`, `SessionRetentionTests`, `InvitationRetentionTests`,
`ShareRetentionTests`, `HistoryRetentionTests`) check what each deletes and what it keeps, a prune
of more than one batch, and that a run another transaction holds is left to it without waiting.
`PruningTests` check that a retention the database fails leaves the others to run, and that only
the API role prunes.

## Pros and Cons of the Options

### A scheduled job per feature

- Good, because it is how the two prunes there are work.
- Bad, because it takes five more copies of the same schedule, error handling and log line, and
  five schedules to keep in step.

### Leave pruning to the operator

- Good, because no code deletes anyone's data on its own.
- Bad, because each operator would have to work out what is safe to delete (a run a review refers
  to is not), and a server without it fills its disk.

## More Information

- [ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md): sandboxes, pruned
  every minute.
- [ADR-0080](0080-host-a-public-server-from-a-compose-kit.md): the hosting kit and its disk.
- [ADR-0011](0011-organise-application-code-by-feature.md): why each retention is in its feature,
  behind an interface every feature may use.
