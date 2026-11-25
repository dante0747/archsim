---
status: proposed
date: 2026-11-25
decision-makers: Majid Abarghooei (owner)
---

# 0099. Present results as hypotheses to test, not verdicts or gates

## Context and Problem Statement

The README calls ArchSim "a flight simulator for distributed systems" that gives "a straight yes
or no against your objectives", offers to "gate pull requests" with `archsim check` and lists
"sign-offs" among its team features; the app's home page makes the same promise. archsim.io's
home page ([ADR-0097](0097-give-the-websites-home-page-its-own-narrative-and-take-its-facts-from-the-repository.md))
offers a "CI gate" that fails a pull request, ends its three steps with "Decide" under "Ship the
one that holds" and closes on "Make better architecture decisions before production"; its link
preview says "get a straight yes or no". The accuracy report ([validation.md](../validation.md))
supports less: its figures are for models calibrated on each stack, small synthetic services on
one laptop; over the 15 held-out runs the simulator's median knee error (16.5%) ties bottleneck
arithmetic on the same calibration (16.2%), its edge being the worst run (-27% against +79%); no
experiment meets the proposed bar for validated; and no design on the illustrative defaults has
been checked against a measurement. The owner now presents ArchSim as a tool to explore design
alternatives, challenge assumptions and generate testable hypotheses before investing in
implementation or real-world testing, not as production sign-off, audit evidence or an automated
deployment gate. How should the public copy describe a result, and the check?

## Decision Drivers

- Claim no more than validation.md shows.
- Say the same wherever a reader lands: the README, archsim.io, app.archsim.io, the docs.
- Change no behaviour: the check, the reviews of runs and the results' words stay as they are.

## Considered Options

- Keep the pitch, with the caveats already beside it.
- Reword the copy: an experimentation tool whose results are hypotheses to test, and a check run
  as a warning.
- Reword the copy and change the product's words with it: the results' "Yes, it holds up.", the
  review panel's Approve, `archsim check --help`'s "an SLO gate for CI".

## Decision Outcome

Chosen option: reword the copy, because it brings every page in line with the evidence without
changing what the product does. ArchSim is "an architecture experimentation tool for distributed
systems"; a result says how a design fares against its objectives under the assumptions stated, a
hypothesis to test with a prototype, a load test or production telemetry; the caveat beside the
results names what they are not (sign-off, audit evidence, a deployment gate), and the README adds
that its accuracy figures are for calibrated models. archsim.io's accuracy section is that README
statement word for word (ADR-0097), so it follows; `/accuracy` says the same in its lead. On
archsim.io the story ends in what to test ("Learn what to test", its third step "Learn", and
"Challenge your assumptions before you build."), the CI way in is a check, "a warning to read, not
a gate", and the hero's eyebrow and the search and preview descriptions name an experimentation
tool; ADR-0097's structure and its facts from the repository are unchanged. [ci.md](../ci.md)
recommends `continue-on-error: true` on the check's step and no required status check; the check
itself still exits 1 on a miss, and this repository's CI still fails on it, because there it tests
ArchSim. A review of a run (the API's sign-off) is the team's reading of a simulated run, not
evidence about the running system. The tagline, "Crash-test your architecture before your users
do.", stays: it names an experiment.

### Consequences

- Good, because no page promises more than the accuracy report shows, and ci.md sets the check up
  as a warning.
- Bad, because the product stays blunter than its copy until the owner decides on its words, the
  third option, one at a time.
- Bad, because `/accuracy`'s sentence on bottleneck arithmetic is written by hand, like the
  README's: `accuracy.json` does not carry the comparison, so the next campaign must revisit it.

### Confirmation

The web app's tests hold the copy to it: `site.test.ts` (the search and preview descriptions),
`SiteHome.test.tsx` and `ProjectsHome.test.tsx` (the two home pages), `readme.test.ts` (the README)
and `AccuracyPage.test.tsx` (calibrated models, and bottleneck arithmetic).

## More Information

[ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md) and
[ADR-0087](0087-compare-the-simulator-with-simple-estimates.md) set how accuracy is shown and
compared; [ADR-0097](0097-give-the-websites-home-page-its-own-narrative-and-take-its-facts-from-the-repository.md)
gives the home page its narrative, whose words this changes;
[ADR-0060](0060-run-the-check-action-in-an-image-the-job-builds.md) is the check's action,
unchanged.
