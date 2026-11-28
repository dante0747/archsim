---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0115. In a sandbox, reopen the visitor's own copy of a template

## Context and Problem Statement

Opening a template makes a project, a copy of it. To keep the projects list from filling with
copies, opening the same template again hands back the copy made the first time, but only while it
was never saved and still equals the template (`reuseUnedited`). Since the editor saves a design as
it is edited
([ADR-0113](0113-save-a-design-as-it-is-edited-and-on-the-way-out-of-the-editor.md)), a copy is
saved at its first change, so opening that template again makes another project.

A sandbox holds five projects
([ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md)). A visitor who opens
a template, changes something, goes back and opens it again, a few times over, meets "Could not
create project" within minutes, in the first session they ever have with ArchSim. A visitor who
opens six different templates meets it too, as before the autosave.

What should opening a template again do in a sandbox?

## Decision Drivers

- A visitor never meets the sandbox's project cap for opening a template they opened before.
- A visitor who comes back to a template finds what they did to it.
- Signed-in workspaces keep the rule they have: a fresh copy once the first was saved.

## Considered Options

- Reuse the visitor's own latest copy of the template in a sandbox, edits and all
- Raise the sandbox's cap from 5 projects to 20
- Leave it as it is

## Decision Outcome

Chosen option: "reuse the visitor's own latest copy of the template in a sandbox, edits and all",
the owner's choice, because it is the only option that meets the first two drivers, and it leaves
the third alone: a cap of 20 puts the cap off rather than removing what fills it, and leaves a
visitor's changes in one of several copies with the same name; leaving it meets neither.

In a sandbox, `reuseUnedited` hands back the copy of the template the visitor updated last, saved
since or not, among those made from it without a name of their own; anywhere else it hands back only
a copy never saved that still equals the template, as before. Someone else's copy is never handed
back.

### Consequences

- Good, because reopening templates a visitor opened before makes no more projects. The cap is met
  only by five different templates or designs, so a visitor who tries six different templates still
  meets it, as before the autosave.
- Good, because a visitor who goes back to a template finds their changes.
- Bad, because a visitor who wants a fresh copy of a template they changed must delete their copy
  and open the template again, or undo their changes; a sandbox lasts 24 hours, and signing in gives
  fresh copies.
- Bad, because a sandbox's copy no longer follows its template: a copy made before a release changed
  the template is handed back as it was, where elsewhere a copy that no longer equals its template
  is never reused. A sandbox lasts 24 hours, so few copies outlive a release.
- Neutral, because the request keeps its name, `reuseUnedited`, though in a sandbox the copy it
  hands back may be edited: a `200` there can carry a `revision` above 1, and the agent's
  `open_template` tool reads it to say that the copy holds the visitor's changes. Renaming the field
  would break clients for a difference only a sandbox sees.
- Neutral, because a signed-in workspace still gets a new copy each time a template is reopened
  after its copy was changed, which since the autosave means after any change. It holds 100 or 500
  projects, not five, and a person there may keep copies apart on purpose and delete the ones they
  do not want.
- Neutral, because a renamed copy is still the template's copy: a project keeps the template it was
  made from (`source_example`) whatever it is renamed to.

### Confirmation

`ProjectReuseTests`: in a sandbox, reopening a template after its copy was saved, and after its
design no longer equals the template, hands back the visitor's copy (red against the rule before);
a copy made with a name of its own and someone else's copy are not handed back; the other cases keep
the old rule. `ProjectRepositoryTests`: the copy handed back is the one updated last, and the
highest id among copies updated at the same instant. The black-box `SandboxTests` reopens a
template whose copy a guest changed, through the API.

## Pros and Cons of the Options

### Raise the sandbox's cap from 5 projects to 20

- Good, because a visitor could also try more different templates before meeting the cap.
- Bad, because a visitor who reopens templates fills 20 as surely as 5, only later, and every
  sandbox could hold four times the projects (its 10 MB storage cap still bounds their bytes).
- Bad, because reopening a template shows a fresh copy, not the visitor's changes, which sit in
  another copy with the same name.

### Leave it as it is

- Good, because nothing changes.
- Bad, because a visitor meets the cap within minutes by reopening templates, in their first
  session.

## More Information

- [ADR-0113](0113-save-a-design-as-it-is-edited-and-on-the-way-out-of-the-editor.md), whose
  autosave made a copy count as saved at its first change.
- [ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md), which gives a
  sandbox its five projects and 24 hours.
