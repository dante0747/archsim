---
status: accepted
date: 2026-11-20
decision-makers: Majid Abarghooei (owner)
---

# 0094. Fit the knee's range on every experiment the engine is tuned on, with an error each experiment shares

## Context and Problem Statement

Since W28 (1 Nov 2026) each prediction carries an 80% range for the knee: a lognormal around
the predicted knee whose spread adds how far a fresh calibration moves the knee (each input's
variation between calibrations times the knee's sensitivity to it) and the model's own error
(`benchmarks/knee-range.json`, `range-fit`). The model's error was one log standard deviation,
0.15, fitted with a mean of 0 on the 12 counted v5 runs of the four tuned-on experiments.

- **It was too narrow out of sample.** On the held-out campaign of 3-4 Nov the range held 9 of
  15 runs, every miss below it: java-mixed and go-mixed held far less than predicted.
- **The misses are shared by an experiment's runs.** java-mixed's three runs are 42% to 48%
  below the prediction and go-mixed's 38% to 44%. One error per run, independent of the
  others, reads an experiment's three runs as three pieces of evidence, and fitted on four
  experiments it has seen four such shared errors.
- **The accuracy review of 18 Nov 2026** (its recommendation 8) asked for the model error to be
  refitted over all nine experiments, with a hierarchical term, checked by leaving out each
  experiment, and judged by that check's coverage: 75% to 85% at nominal 80%.
- **The model changed.** The service's cost per request is a law of its rate (ADR-0093), and
  engine 1.6.0 lists all nine measured experiments as tuned on (`engines.json`): the first
  held-out set is spent, and a fit that leaves it out ignores the most telling misses.

Which runs is the range fitted on, and what form does the model's error take?

## Decision Drivers

- The range is for predictions of experiments the fit has not seen: the check that matters is
  leaving out each run's whole experiment.
- A miss below the range, where the stack holds less than promised, is the costly one.
- The sealed second held-out set stays out of the fit, so that it can still test the range.

## Considered Options

- (a) Keep the W28 fit (one error, mean 0, the four tuned-on experiments)
- (b) The same form, fitted on all nine experiments the engine is tuned on
- (c) As (b), with a bias: the errors' mean fitted, the range centred on it
- (d) An error each experiment's runs share plus one each run has, mean 0, fitted on all nine
  (a hierarchical, random-effect model)
- (e) As (d), with a bias

## Decision Outcome

Chosen option: (d), because of the two forms within the review's 75% to 85% when each run's
experiment is left out of the fit ((b) at 80%, (d) at 84%), it misses fewer runs below the
range and does not weigh an experiment by its number of runs.

Leaving out each run's experiment, the 80% range on the 49 counted runs from protocol v5 on
of the nine experiments (today's harness and engine, `1.6.0-law`'s development checks):

| Option | Inside | Below the range | Above it |
| --- | --- | --- | --- |
| (a) one error, fitted on the four (0.15) | 32 | 17 | 0 |
| (b) one error, all nine (0.26) | 39 | 10 | 0 |
| (c) one error and a bias, all nine | 33 | 8 | 8 |
| **(d) shared and own errors, all nine (0.32 and 0)** | **41 (84%)** | **8** | **0** |
| (e) shared and own errors and a bias | 31 | 6 | 12 |

All five on one basis: each run's calibration spread as the full fit gives it, and the model's
error, and the bias where there is one, fitted by maximum likelihood without the run's
experiment. For (b) and (d) that is what `range-fit`'s own check
gives, which also refits the inputs' variation without the experiment.

- **Which runs.** Every experiment the engine's entry in `benchmarks/engines.json` lists as
  tuned on, through `Engines.tunedOn`: an experiment registered as held out joins once it is
  listed. For 1.6.0 that is all nine measured experiments: 64 calibrations (kept runs and
  calibration-only trials) and 49 counted runs. The second held-out set has no run, and no
  engine is tuned on it.
- **The model's error.** Each run's ln(measured / predicted knee) is an error its experiment
  shares, normal with mean 0 and standard deviation `between`, plus one of its own, normal
  with mean 0 and variance (its calibration spread)^2 + `within`^2. The two are fitted by
  maximum likelihood on a grid of 0.005 up to 2, then of 0.0005 around the best point
  (`ModelError`). A prediction for an experiment the fit has not seen takes both:
  `model.sigma` = sqrt(between^2 + within^2), which `KneeRanges` reads as before, so the
  range's form and its readers do not change. `knee-range.json` also records `between` and
  `within`.
- **The fit.** `between` is 0.32 and `within` 0: the calibration spread accounts for how an
  experiment's runs differ, and all the model's own error is shared by an experiment's runs.
  At the median calibration spread of the runs it is fitted on (0.125) the 80% range is about
  x0.64 to x1.56 of the predicted knee, where W28's was about x0.75 to x1.33 at its runs'
  (0.167).
- **No bias.** Seven of the nine experiments' knees are predicted too high, so a fitted bias
  would centre the range below the prediction. Fitted with a bias, the range held fewer of the
  left-out experiments' runs, since the bias moves with the experiment left out; the range
  stays centred on the predicted knee, and its misses are reported by their side.
- **The earlier ranges by coverage.** The fit's `check.before` now counts the ranges the runs
  were predicted with for each nominal coverage on its own: protocol v5's replicate sets' knees
  held 1 of 12 runs at 50%, W28's range 29 of 37 at 80%.
- **A model change.** The range is part of every prediction, so `engines.json`'s 1.6.0 entry
  says so; the engine's results do not change.

### Consequences

- Good, because the range's coverage on experiments it was not fitted to is about its nominal
  80% (84% leaving out each experiment), where the fit on four held 65%.
- Good, because an experiment with many runs no longer weighs as much evidence as many
  experiments: each experiment's runs count as one draw of its shared error.
- Bad, because the range is wider: about -36% to +56% at 80% where it was about -25% to +33%.
  That is what the evidence supports while the model misses whole stacks by 40% to 50%.
- Bad, because every miss is still below the range (java-mixed, go-mixed and two of pg-mixed's
  runs): the range is symmetric in log, the misses are not. The host machine (W27), which takes
  java-mixed's and go-mixed's errors to -8% and -16% in-sample (ADR-0093), is what would remove
  most of them, once its fit is measured.
- Bad, because it is in-sample for the model: the knees are today's engine on runs the model
  was tuned on. Leaving out each experiment is the nearest check, and the second held-out set
  is the test.
- Bad, because the calibration part is still linear in each input (one knee search per input,
  22% up), not an ensemble of simulations drawn from the inputs' variation, which the review
  also asked for.
- Neutral, because the kept runs' comparisons keep the ranges they were predicted with.

### Confirmation

- `ModelErrorTests`: an error the runs of each experiment share, one that varies within them,
  one run per experiment (the two together), errors the spread explains, errors beyond it, no
  runs, the two in quadrature.
- `RangeSourcesTests` and `EnginesTests`: a held-out experiment the engine is tuned on is
  fitted on, one it is not is left out, and the committed results for engine 1.6.0 are the nine
  measured experiments; `tunedOn` reads an engine's list.
- `FitDocumentTests`: the earlier ranges counted per nominal coverage.
- `CommittedRangeFitTests`: the committed fit regenerates from what it records, with 49 runs of
  nine experiments, and leaving out each experiment it holds 41 of 49.
- `docs/validation/knee-range.md`, generated by `report --dev`.

## Pros and Cons of the Options

### (a) Keep the W28 fit

- Good, because it is narrower.
- Bad, because on the nine experiments it holds 32 of 49 runs leaving out each experiment, with
  17 misses, all below the range.

### (b) One error on all nine

- Good, because it is the smallest change and holds 39 of 49.
- Bad, because it counts an experiment's runs as independent evidence of the model's error,
  so an experiment with nine runs weighs three times one with three.

### (c) and (e) A bias

- Good, because the model's knees are too high more often than too low.
- Bad, because left out of the fit, an experiment moves the bias the most when it is the one
  that sets it: 33 and 31 of 49, with eight and twelve misses above the range.

## More Information

- [ADR-0093](0093-fit-a-services-falling-cpu-per-request-as-a-law-of-its-rate.md): the law the
  knees are predicted with.
- [ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md): the set that
  tests the range out of sample.
- [docs/validation/knee-range.md](../validation/knee-range.md), [docs/validation.md](../validation.md),
  Protocol.
