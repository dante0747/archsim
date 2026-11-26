---
status: proposed
date: 2026-11-26
decision-makers: Majid Abarghooei (owner)
---

# 0102. Lead each screen with the answer, and make every input explain itself

## Context and Problem Statement

The app grew one honest detail at a time, and each screen now says everything at once. Results →
Answer stacked the verdict, a line repeating each missed objective's numbers, a seed note, the
bottleneck, a confidence line with two links, an experimental-technology line, six figures, a
sentence of queueing formulas, a review form, two findings with their full evidence, four
objective tiles repeating the verdict's numbers, and a cost panel of up to six lines a part. The
inspector cut its labels short ("Max connections …", "Wait queue li…"), kept every explanation
behind an ⓘ and marked estimates with a bare "≈"; the parts library clipped names in a
three-column grid, and the chaos pads clipped their titles. The owner finds the results verbose
and the inputs and their labels opaque. How should the app present a result and an input?

## Decision Drivers

- A reader gets the answer in seconds; nothing the app knows is lost, only folded one click away.
- Every input says what it is, where its value comes from, and how to put the default back.
- No label, name or title is cut short.
- A result stays a hypothesis to test
  ([ADR-0099](0099-present-results-as-hypotheses-to-test-not-verdicts-or-gates.md)): its caveats
  stay on the answer, not behind a tab.
- The same facts from the same sources: no new figure, no engine change.

## Considered Options

- (a) Progressive disclosure: one answer card with short caveats, the explanations one click
  away; every input with a help line and a source tag
- (b) Fewer words in the same layout
- (c) A guided mode beside the current screens

## Decision Outcome

Chosen option: (a), because it shortens what a reader must get through without taking away
anything they may need, and gives every screen the same shape: the answer, then why, then the
detail.

- **Results → Answer** is one card: what the verdict rests on ("Misses 2 of 2 objectives"), the
  headline, the bottleneck with "Show on canvas" (which now opens Design with the part
  selected), and the objectives that have a target as tiles, with one tile to add the others.
  Under it, "How sure is this" gives each caveat a line (a quick check's sample, the seeds, the
  confidence, experimental parts, what queueing formulas predict), its explanation in a popover.
  Then four key numbers (handled, latency p95, success, cost per month), what to fix first by
  title with what to try and its evidence under "Why", the month's cost as one bar split by part
  with the fine print folded, and the review in a compact panel.
- **Inputs**: a setting shows its label in full (it wraps; the side panel is 352 px), a one-line
  help (its description's first sentence, the unit left to the field, the rest in the ⓘ), a tag
  from the design's analysis that says where its value comes from (measured, documented with the
  vendor's site, your estimate, or default: an illustrative default), the same classification
  the confidence counts, and a reset to the part's default when the value differs from it. The
  "≈" mark goes. A part's resources show their inputs, sizes and price in a line; why the auto
  size, what the part needs and which price the cost uses fold under "Auto-sized for …". A part's
  facts from the run read one a line, three before "N more".
- **Design**: the parts library lists parts as rows, each with its full name and what it is
  beneath; the chaos pads have two columns and room for three lines; a card's fact wraps a long
  value under its label instead of cutting the label; the timeline's traffic scale ends where
  the drain band starts.

Field labels stay the engine's catalog's: the engine's analysis and report cases, recorded from
the frozen Python engine, carry them, so renaming one is a decision of its own.

### Consequences

- Good, because the answer fits on one screen at 1440 × 900 with its objectives and caveats, and
  the page is about half as tall.
- Good, because the inspector's tags and the confidence count the same inputs the same way: a
  design whose confidence says "28 of 42 inputs are illustrative defaults" shows each of those as
  "default", where it can be replaced.
- Bad, because an explanation is one click further away, and the tests that read one open it
  first.
- Bad, because a setting takes two lines where it took one, and the wider side panel leaves the
  canvas 32 px.

### Confirmation

`AnswerPage.test.tsx` (the card, the caveats on demand, the key numbers, the findings folded, the
cost bar), `Confidence.test.tsx` and `SeedRange.test.tsx` (the caveats' content),
`FieldControl.test.tsx` (the help line, the source tags, the reset), `Inspector.test.tsx` (a part's
inputs tagged from the analysis), `Palette.test.tsx`, `ResourceBlock.test.tsx`,
`ExperimentTracks.test.tsx` (the scale before the drain band), and the end-to-end main path.

## Pros and Cons of the Options

### (b) Fewer words in the same layout

- Good, because it is the smallest change.
- Bad, because the repetition is the layout's: the verdict's numbers, the objective tiles and the
  figures say the same thing three times, and truncated labels stay truncated.

### (c) A guided mode beside the current screens

- Good, because a newcomer could be walked through a first design.
- Bad, because it doubles what is to keep correct, and leaves the screens people use as they were.

## More Information

[ADR-0099](0099-present-results-as-hypotheses-to-test-not-verdicts-or-gates.md) keeps the
results' words ("Yes, it holds up."); this changes where they sit, not what they say.
[docs/features.md](../features.md) describes the screens.
