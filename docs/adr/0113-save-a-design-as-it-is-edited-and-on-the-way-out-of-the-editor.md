---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0113. Save a design as it is edited and on the way out of the editor

## Context and Problem Statement

A design was saved only when its author pressed Ctrl/⌘ S or the Save button. The only sign of
unsaved work was a 6 px dot beside the project's name, and the only guard was the browser's
`beforeunload` prompt, which fires when a page is unloaded. Moving inside the app unloads nothing:
the logo, "Back to projects" in the command palette, Settings, a workspace switch, signing out and
the browser's Back button all left the editor without a word, and the project opened again as the
API had it.

How does the editor keep unsaved work, and what happens when its author leaves with some?

## Decision Drivers

- No edit is lost without its author choosing to lose it.
- The author can always read whether the design on screen is the saved one.
- Leaving is not made slower or noisier for someone whose work can simply be saved.
- The API's limits hold. Saving is one of the engine's routes: they answer a bounded number of
  requests at once (one on the hosting kit's heap) and, with the hosted limits, 600 a minute for
  each person
  ([ADR-0108](0108-bound-the-engines-routes-by-slots-per-person-limits-and-a-sandbox-body-cap.md)).
- No API change.

## Considered Options

- (a) Save after a short pause in editing and on the way out, and ask only when that save fails
- (b) Keep saving by hand, and ask before every move inside the app with unsaved work
- (c) Save after a pause, and still ask before every move inside the app with unsaved work
- (d) Keep unsaved work in the browser's storage and offer it back when the project opens

## Decision Outcome

Chosen option: (a), because it is the only one that keeps the work on every way out, including
the ones the app cannot hold back, and it asks a question only when there is something to decide.

- **The editor saves by itself.** A design is saved once it has been left alone for 2 seconds,
  and no later than 15 seconds after its oldest unsaved edit while edits keep coming; a part being
  dragged is saved when the drag ends. The save is the one Save makes
  ([ADR-0112](0112-end-a-save-conflict-with-a-choice-their-version-this-one-or-a-copy.md)): it
  names the revision it was edited from, and it counts as saved only what it sent, so typing
  through a save loses nothing. At most 30 saves a minute, against the 600 the hosted limits allow
  each person.
- **A save that fails is tried again only when that can help.** The network being down, a busy
  or restarting server and a request to wait (no answer, 408, 429, 5xx) are tried again after 5,
  15, 30 and then every 60 seconds. A refusal of the request itself (no role, a full workspace, a
  design the API cannot read) waits for the next edit. A conflict waits for the person's choice.
- **The top bar says it in words.** Under the project's name: "Saved", "Unsaved changes",
  "Saving…", "Not saved" (a button that tries again, with the reason in its tooltip) or "Changed
  elsewhere" (a button that opens the conflict's choices). A save made by itself says nothing when
  it works. Its failure is said once, in a toast: at once when trying again cannot help, at the
  second failure in a row when it can.
- **Leaving saves first.** The editor's own ways out (the logo, Settings, a workspace switch,
  signing out, "Back to projects", an agent opening another project) go through one guard,
  `leave` in `apps/web/src/lib/leave.ts`. With nothing unsaved they go at once. Otherwise the
  editor saves, then goes. It asks only when that save fails or takes more than 8 seconds: "Your
  changes are not saved", with the reason, and "Stay" or "Leave without saving" (for a conflict,
  "Choose a version"). If the save goes through while the question is open, the editor goes.
- **The editor saves as it closes.** The browser's Back and Forward buttons cannot be held back,
  so the editor saves what is unsaved when it is taken off the screen, and a project opened right
  after waits for that save before it reads the project. This save cannot ask: if it fails, a
  toast names the project and the reason.
- **Unloading the page** (closing the tab, reloading, typing an address) still gets the browser's
  own prompt while something is unsaved, as before.
- **The name is the author's.** A name is saved trimmed, and as "Untitled architecture" when
  there is none. The editor no longer writes that back into the field after a save, which a save
  made mid-word would turn into a jumping cursor; an empty field shows "Untitled architecture" as
  its placeholder. The design the editor sends carries the saved name too: with an empty one the
  API refused it, so a cleared name field made every save fail.
- **The history folds saves.** Each save is an event in the project's history. Saves that follow
  one another, by one person and with nothing else between them, read as one line: "saved
  revisions 4 to 17".

### Consequences

- Good, because an edit survives every way out of the editor that the app controls, and the
  browser's Back button too.
- Good, because a design in the list, a run and a share link are no longer older than the canvas
  by however long its author forgot to save.
- Bad, because there is no longer a way to try something and walk away from it: what is on the
  canvas is saved within seconds, an imported or calibrated design included, and only undo, in
  the same session, takes it back. The API keeps one version of a project; an earlier design
  survives only in the runs that simulated it.
- Bad, because every save is a revision. Revision numbers grow by one for each pause in editing,
  a review goes stale at the first edit after it (moving a part included), and the history table
  gets a row for each save, folded only where it is shown.
- Bad, because the editor now writes without being asked. Someone with the viewer role who moves
  a part reads "Not saved" a moment later, and is asked before leaving; a read-only editor for
  viewers would end that. An agent's edits ([agents.md](../agents.md)) reach the stored project
  without its `save_design` tool, the one marked consequential. And a template's copy counts as
  edited at its first change, so opening the template again makes another project, and a
  sandbox's five are used up sooner
  ([ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)).
- Bad, because closing the tab within two seconds of an edit still depends on the browser's
  prompt, and a closing save that fails (the Back button with the network down) loses the edit
  with a toast as its only trace.

### Confirmation

`lib/autosave.test.ts` (the pause, the latest moment, a drag, an edit made during a save, trying
again and not trying again, a conflict), `lib/save-leaving.test.ts` (saving before leaving and as
the editor closes, which failures may pass, the name left as typed), `lib/leave.test.ts`,
`components/editor/keeping.test.tsx` (the guard, the closing save, what is said and when),
`SaveStatus.test.tsx`, `LeaveDialog.test.tsx`, `lib/team.test.ts` (the folded history), and in a
browser against the API `e2e/unsaved-work.spec.ts`: an edit saved without a click; an edit made
and left at once by the logo, by the command palette and by the Back button, each found again;
a save that fails, which asks before leaving; and a conflict a save made by itself ran into.

## Pros and Cons of the Options

### (b) Keep saving by hand, and ask before every move inside the app

- Good, because the author decides when a revision is made, and can walk away from an experiment.
- Bad, because it protects only the ways out the app can hold back: the Back button cannot be,
  so that loss stays.
- Bad, because the question comes up on most ways out, and people learn to click through it.

### (c) Save after a pause, and still ask before every move

- Good, because nothing leaves the editor unasked, whatever the save does.
- Bad, because with a save two seconds behind every edit the question would nearly always be
  about work the editor could save by itself in the time it takes to read it.

### (d) Keep unsaved work in the browser's storage

- Good, because it would survive a closed tab and a crash, with or without the network.
- Bad, because the work stays on one machine and the stored project still lags behind; it needs
  rules for a draft that is older than the stored project, and it keeps designs in a place the
  person does not expect them. It could be added to (a) later for the cases (a) leaves open.

## More Information

[ADR-0112](0112-end-a-save-conflict-with-a-choice-their-version-this-one-or-a-copy.md) decides
what a save counts as saved and what a conflict offers; this ADR builds on both.
[features.md](../features.md) describes saving for the people who use it.
