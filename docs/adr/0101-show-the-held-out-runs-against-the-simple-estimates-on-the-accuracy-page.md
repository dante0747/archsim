---
status: proposed
date: 2026-11-25
decision-makers: Majid Abarghooei (owner)
---

# 0101. Show the held-out runs against the simple estimates on the accuracy page, each experiment one vote

## Context and Problem Statement

[ADR-0087](0087-compare-the-simulator-with-simple-estimates.md) set the simulator's knees beside
three simple estimates on [`docs/validation.md`](../validation.md) and left `accuracy.json` and
the web app unchanged. The README and the validation page's headline now lead with the held-out
comparison, written by hand: over the second held-out set's 15 runs the simulator's median knee
error is 16.5% against bottleneck arithmetic's 16.2%, a tie, and its worst run -27% against +79%.
`/accuracy`, built from `accuracy.json`, said nothing of it, which a review called a credibility
gap. How should the data carry the comparison, and on what evidence may the page call the two
level, or one of them the nearer?

## Decision Drivers

- Every figure the page shows comes from the results, so a new campaign cannot leave it stating
  old figures.
- The page says what the README says, and claims no more than the runs show.
- An experiment's runs share most of their error
  ([ADR-0094](0094-fit-the-knees-range-on-every-experiment-the-engine-is-tuned-on-with-an-error-each-experiment-shares.md)):
  three runs of one experiment are not three pieces of evidence.
- The runs are the held-out repeatability's and coverage's: every counted run that repeats a
  held-out experiment's latest conditions.

## Considered Options

- (a) The figures in `accuracy.json`, and a sign test over experiments
- (b) The figures, and a sign test over runs
- (c) The figures, and the page's words typed by hand

## Decision Outcome

Chosen option: (a).

`accuracy.json`'s summary gains `againstEstimatesHeldOut` (`AgainstEstimatesHeldOut`): the runs
and experiments counted; for the simulator and each estimate, the runs with a figure, the median
size of their errors (to a tenth, where two close medians would round alike), and the worst error
with the run it came from; and for each estimate, in how many experiments it and the simulator
were the nearer to the measured knee, with the verdict of a two-sided sign test at 5% on those
experiments: `level`, `simulator nearer` or `estimate nearer`. An experiment's nearer knee is the
one whose errors have the smaller median over its runs that have both. `/accuracy` opens its short
version with the README's statement built from these figures, the verdict choosing its words, a
table of each estimate's median and worst run, and the split the verdict rests on.

On the second held-out set every verdict is `level`: the simulator is the nearer in 3 of the 5
experiments against the arithmetic, 4 against the engine's analytic estimate and all 5 against
the utilisation line, and no split of five experiments is uneven enough (5 to 0 comes 6.3% of the
time).

### Consequences

- Good, because the page, the README and the validation page's headline give the same figures,
  and tests fail when they part.
- Good, because "level" is the data's word, and claims nothing the experiments cannot show.
- Bad, because a sign test cannot call a winner below six experiments: the analytic estimate's
  median error is 27.5% and the utilisation line's 43.3%, against the simulator's 16.5%, and both
  stay `level`.
- Bad, because a close median decides an experiment: on python-wide the simulator and the
  analytic estimate are half a point apart, and on cache-miss the median gives the experiment to
  the simulator over the utilisation line by a tenth of a point, though two of its three runs went
  the other way.

### Confirmation

`AgainstEstimatesHeldOutTests` (the figures, the runs left out, an experiment counted once, the
sign test's bounds), `CommittedAccuracyTests` (the committed figures and verdicts),
`CommittedAccuracyPageTests` (the headline table's medians), `ReportParityTests` (both copies of
`accuracy.json`, byte for byte), and in the web app `accuracy.test.ts` (the README's figures are
the data's) and `AccuracyPage.test.tsx`.

## Pros and Cons of the Options

### (b) A sign test over runs

- Good, because fifteen runs give the test more power.
- Bad, because it reads an experiment's three runs as three independent pieces of evidence, which
  ADR-0094 rejected: in 14 of the 15 pairs of experiment and estimate all three runs favour the
  same knee, and the test called the simulator nearer than the analytic estimate (12 runs to 3) and
  the utilisation line (13 to 2) on five pieces of evidence.

### (c) The page's words typed by hand

- Good, because it is the simplest.
- Bad, because the words could outlive the figures they describe.

## More Information

Extends [ADR-0087](0087-compare-the-simulator-with-simple-estimates.md), whose "`accuracy.json`
and the web app are unchanged" this changes. The figures by hand: `docs/validation.md`'s
"Headline" and "Against simple estimates".
