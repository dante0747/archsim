---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0112. End a save conflict with a choice: their version, this one or a copy

## Context and Problem Statement

A save names the revision it was edited from (`expectedRevision`), and the API refuses it with 409
when the project was saved elsewhere first, in another tab or by a teammate
([architecture.md](../architecture.md#persistence-model)). The editor answered that refusal with a
toast, "Changed elsewhere", and nothing else: it kept the stale revision, so every later save
failed the same way, and the only ways out were to reload the page and lose the edits or to
download the JSON. The editor also counted a design as saved the moment a save was answered,
whatever had been edited while the request was on its way: those edits were flagged saved, and a
project name retyped meanwhile went back to the one sent.

What does the editor do when a save meets a version saved elsewhere, and what does it count as
saved?

## Decision Drivers

- Neither version is lost without the person choosing: not this editor's edits, not the version
  saved elsewhere.
- A choice that can be taken back is taken back with what the editor already has (undo).
- No API change: the API already refuses a stale revision and stores a save that names none.
- No merge machinery. A design is a small graph with a layout, edited by a few people; merging two
  versions of one is a feature of its own.

## Considered Options

- (a) Three choices on a conflict: reload their version, overwrite it, or save this one as a copy
- (b) Reload only, as the API's message says ("Reload before saving")
- (c) Merge the two versions, part by part
- (d) Last write wins: stop sending `expectedRevision`
- (e) Keep conflicts from happening: lock a project while someone edits it

## Decision Outcome

Chosen option: (a), because it is the smallest change that leaves no dead end and loses nothing
unasked.

- **A conflict is a state of the editor, not a message.** A 409 for a stale revision sets
  `conflict` in the editor's store. While it is set, Save sends nothing (the API would refuse it
  again) and opens the choices. Closing them chooses nothing: the design stays unsaved.
- **Reload theirs** reads the stored version and puts it on the canvas in place of the design
  there, as one undoable step: undo brings the person's version back, now on top of their
  revision, where saving it is an ordinary save.
- **Overwrite** saves without `expectedRevision`, so the API stores it over whatever is stored.
  It is unconditional on purpose: the person chose their version over one they cannot see, and a
  third version saved in the meantime would be no more visible to them.
- **Save a copy** makes a new project from the design on the canvas, named "Name (copy)", in the
  workspace new projects go to, and opens it. The project keeps the stored version and its runs.
- **Saved is what was sent.** A save remembers the design it sent, as the text of the saved
  document without the viewport (panning is not an edit). When the answer comes, the design is
  marked saved only if it is still that text; the API's name (trimmed, never empty) is taken only
  then. An answer for a project that is no longer the open one, or was opened again, changes
  nothing.
- The editor's Save and the agents' `save_design` tool ([agents.md](../agents.md)) are one
  function, `saveDesign` in `apps/web/src/lib/save.ts`. An agent's save that meets a conflict
  reports it and opens the choices for the person: the choice is theirs.

### Consequences

- Good, because a conflict has three ways out, none of which needs a page reload, and the editor
  saves again after each.
- Good, because the destructive choice people reach for first, reloading, can be undone.
- Good, because nothing changes in the API or the database.
- Bad, because there is no merge: two people who changed different parts of one design still pick
  one version, or keep both as two projects.
- Bad, because Overwrite cannot be undone: the API keeps one version of a project, and an earlier
  design survives only in the runs that simulated it.
- Bad, because the choices cannot say who saved the other version, when, or what differs. The 409
  carries a message and no reason code, so the editor recognises it by its words
  (`isRevisionConflict`); a reason code and the other version's author would be an API change.
- Bad, because a copy lands in the workspace the person has selected, which is the project's own
  unless they opened the project by its link from another workspace.

### Confirmation

`lib/save.test.ts` (what a save sends, a conflict that is not sent again, each of the three
choices, undo after a reload, an answer that arrives for another project),
`components/editor/actions.test.tsx` (an edit or a retyped name made while a save is on its way
stays unsaved), `components/editor/SaveConflictDialog.test.tsx` (the three choices, what each says
it loses, a choice that fails, closing without choosing), `lib/agent/agent.test.ts` (the agent's
save) and, in a browser against the API, `e2e/save-conflict.spec.ts`: a save held back while the
name is retyped, and each choice after a save elsewhere, followed by a save that goes through.

## Pros and Cons of the Options

### (b) Reload only

- Good, because it is what the API's message asks for and needs no dialog.
- Bad, because it throws away the edits the person just tried to save, which is the loss the
  refusal exists to prevent on the other side.

### (c) Merge the two versions

- Good, because two people could work on one design at once.
- Bad, because a design's parts, links, scenarios, objectives and layout refer to one another: a
  merge needs the common ancestor (the API keeps none), rules for each kind of clash and a screen
  to settle them. It is a feature to decide on its own, not a fix.

### (d) Last write wins

- Good, because a save never fails.
- Bad, because it loses the other person's work silently, which optimistic concurrency was added
  to stop.

### (e) Lock a project while someone edits it

- Good, because a conflict cannot happen.
- Bad, because it needs presence (who has the project open, and for how long after a tab is left
  behind), which the API does not have, and it turns a rare clash into a daily "locked by" wait.

## More Information

The API's side is unchanged: `PUT /projects/{id}` in [api.md](../api.md). Workspaces, roles and
the history a save is recorded in are described in [features.md](../features.md#teams-review-and-sharing).
