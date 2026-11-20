---
status: proposed
date: 2026-11-20
decision-makers: Majid Abarghooei (owner)
---

# 0095. Run the benchmark designs on the laptop's fitted host machine and let a freeze name it

## Context and Problem Statement

Every benchmark stack shares one laptop: 4 physical cores of 8 threads in Docker's VM, with
k6 and the stack's other parts. Since W27 (engine 0.16.0) a design can say so
(`architecture.host`, `siblingSpeed`), and the harness can put the benchmark designs on the
laptop's host machine as its contention checks fit it (`HostSwitch`, `MachineFit`): the
clock as the busy threads rise, a thread's speed beside a busy sibling, the threads busy at
rest and k6's CPU per request. It was opt-in (`BENCH_HOST=on`), off by default.

- **Off, the law promises capacity that is not there.** Since the service's CPU per request
  became a law of its rate (ADR-0093), the knees of 7 of the 9 experiments are predicted too
  high, java-mixed's by 72% and go-mixed's by 67%: the law carries the falling cost up to the
  knee, while the machine, filling, slows every thread down.
- **The host machine's fit was partly assumed.** ADR-0093's what-if on it (a median of 11.9%)
  assumed two of its numbers. On 20 Nov 2026 a quiet check measured them (0.95 threads busy at
  rest, k6 0.32 ms of CPU per request), and a full 15-minute contention check refitted the rest
  (`envcheck/2026-11-20T17-00-31Z-contention.json`: the clock at 0.805 of one busy thread's
  with 8 busy, a thread beside a busy sibling at 0.47 of its speed, 0.90 threads busy at rest,
  k6 0.33 ms).
- **The seal refused it.** ADR-0088 keeps a held-out experiment sealed while `BENCH_HOST` is
  on, because no freeze names the fit the designs would run on.

Should the benchmark designs run on the fitted host machine by default, and how does a frozen
engine say which?

## Decision Drivers

- A predicted knee above the measured one promises capacity the stack does not have: it is the
  costly error (ADR-0094).
- The model should describe what runs: the stacks do share the laptop's threads.
- Every input measured, none assumed.
- A held-out prediction must be reproducible from what its freeze names.

## Considered Options

- (a) On by default, a freeze naming the fit by its digest and the seal checking it
- (b) Keep it off: the law alone
- (c) On only for the stacks it helps (the three mixed runtimes and chain)
- (d) Keep it off until a quieter testbed (a second machine) can be measured

## Decision Outcome

Chosen option: (a), the owner's choice, because it takes the runs whose knee is predicted too
high from 25 of the 37 counted v6 runs to 4, and the largest over-prediction of an
experiment's median from +72% to +16%, for a median error 1.6 points higher and a largest
under-prediction of 29%.

Every kept run re-simulated in-sample with today's engine (`resimulate`), the median knee error
of each experiment's counted v6 runs (predicted over measured, less 1: above 0 promises more
than the stack held):

| Experiment | Runs | The law alone (`1.6.0-law`) | Host, two numbers assumed (`1.6.0-law-host`) | Host, at rest measured (`1.6.0-law-host-measured`) | **Host, refitted (`1.6.0-law-host-refit`)** |
| --- | --- | --- | --- | --- | --- |
| pg-mixed | 6 | +19% | +6% | -11% | **-7%** |
| pg-hot-row | 5 | -10% | -11% | -16% | **-14%** |
| cache-aside | 6 | -7% | -20% | -30% | **-29%** |
| kafka | 5 | +7% | -13% | -27% | **-19%** |
| java-mixed | 3 | +72% | -8% | -15% | **-15%** |
| go-mixed | 3 | +67% | -16% | -17% | **-17%** |
| python-mixed | 3 | +29% | +17% | +16% | **+16%** |
| chain | 3 | +37% | 0% | -2% | **-2%** |
| cache-ttl | 3 | +10% | 0% | -15% | **-14%** |
| Median of the 37 runs' absolute errors | | 12.9% | 11.9% | 16.2% | **14.5%** |
| Experiments predicted too high | | 7 | 3 | 1 | **1** |
| Runs predicted too high | | 25 | 13 | 4 | **4** |

- **On by default, on the machine's own checks.** `HostSwitch` puts the designs on the host
  machine unless `BENCH_HOST=off`: the probe of the latest contention check with one, and the
  machine at rest and k6 of the latest quiet one (`ContentionChecks.latestFit`), counting only
  the checks that name the machine they run on (`environment.machineId`: `BENCH_MACHINE`, or
  the CPU's model). The committed checks are the laptop's, so on another machine, a CI runner
  or a contributor's among them, the designs have none until that machine is checked, and the
  log says so. `BENCH_HOST` is `on` (or unset) or `off`, in any case; another value is refused
  rather than read as on. `BENCH_HOST_FIT` (a what-if fit) and `BENCH_HOST_CLOCK` still
  replace the fit. `range-fit` and a calibration derive their inputs the same way.
- **A freeze names the fit.** When the designs are on a host machine, the entry `engine-id`
  prints for `engines.json` records `hostFit`, the fit's digest (`MachineFit.digest`: 16 hex
  digits of the SHA-256 of its six numbers; how it came about, in words, is left out, so a
  harness that words it otherwise keeps the freeze). `engine-id` names no fit the repository
  cannot give again: with `BENCH_HOST_FIT` or `BENCH_HOST_CLOCK` set, or with contention
  checks not committed, it says why instead of printing the entry. This amends ADR-0088's
  seal: a held-out experiment may run when, besides the rest, its designs would run on the fit
  its freeze names, or on none when the freeze names none (`Seals`); otherwise it stays sealed,
  and the refusal names both and how to run on the frozen fit (the checks as they were at its
  tag, or a file of its numbers `BENCH_HOST_FIT` names). A contention check added between a
  freeze and its campaign changes the fit, so the checks are taken before the freeze, as the
  model's other inputs are.
- **The range is refitted on the host's knees.** `range-fit` on `1.6.0-law-host-refit`'s
  development checks, in the form ADR-0094 chose: the model's error is 0.131 that an
  experiment's runs share and 0.029 of each run's own (it was 0.32 and 0). Leaving out each
  run's experiment, the 80% range holds 37 of the 49 counted runs (76%, within the review's 75%
  to 85%; 21 at a nominal 50%, 40 at 90%). Two misses are below the range (pg-mixed's runs 9
  and 14) and ten above it (8 of cache-aside's 9 runs and 2 of Kafka's), where all 8 were
  below. At the runs' median calibration spread (0.094) the range is about x0.81 to x1.23 of
  the predicted knee, where it was about x0.64 to x1.56.
- **The engine does not change.** The host machine is the harness's model of the laptop, so the
  engine stays 1.6.0 and `engines.json`'s entry says so; the kept runs' comparisons keep the
  predictions they were made with.

### Consequences

- Good, because the model rarely promises capacity the stack does not have: 4 of 37 runs
  predicted too high where 25 were, and no experiment by more than 16%.
- Good, because the range is narrower, holds about its nominal coverage leaving out each
  experiment, and 10 of its 12 misses are on the safe side.
- Good, because every number of the host machine's fit is measured, and a freeze records the
  fit by its digest.
- Bad, because the median error rises from 12.9% to 14.5%, and cache-aside's knee is predicted
  29% low and Kafka's 19%: the host model charges the machine's slowdown in full, while those
  stacks' CPU per request kept falling as the machine filled.
- Bad, because the fit is the laptop's. A run on another machine needs that machine's own
  contention checks; without them its designs have no host machine, as the benchmark workflow's
  hosted runner has none, so its predictions are not on the laptop's footing.
- Bad, because the choice is in-sample: it was made with all nine experiments' results in view.
  The second held-out set (ADR-0090), predicted by the frozen engine on the fit its freeze
  names, is the test.
- Neutral, because `BENCH_HOST=off` puts the designs on no host machine, as before, for a
  what-if or a comparison.

### Confirmation

- `HostSwitchTests` and `ContentionChecksTests`: unset or `on`, the designs are on the host
  machine; `off`, in any case, on none; with no quiet check, or only another machine's, on
  none; the committed checks' fit has the digest `2385cd88d3042c1e`. `HarnessRulesTests`: a
  `BENCH_HOST` neither on nor off is refused. `MachineFitTests`: a fit worded otherwise has the
  same digest.
- `HarnessSetupTests`: by default the designs take the host machine the committed checks fit;
  switched off, none; a fit that cannot be had is logged.
- `SealsTests`: a freeze that names the run's fit opens the seal; one that names none or
  another fit, or a run on none where the freeze names one, keeps it sealed and names both; a
  `hostFit` that is no digest keeps it sealed.
- `EngineFreezeTests` and `EngineIdCommandTests`: the entry `engine-id` prints records the
  committed checks' `hostFit`, none without a host machine, and no entry for a what-if or for
  checks not committed.
- `CommittedRangeFitTests`: the committed fit regenerates from what it records and holds 37 of
  49 leaving out each experiment. `docs/validation/knee-range.md`, generated by `report --dev`.

## Pros and Cons of the Options

### (b) Keep it off

- Good, because its median error is the lowest, 12.9%.
- Bad, because 7 of 9 experiments' knees are predicted too high, three of them by more than a
  third: a reader would be told a stack holds load it does not.

### (c) On for some stacks

- Good, because it would take the better of the two columns for each experiment.
- Bad, because whether a stack shares its machine is a fact about the machine, not a choice
  made per stack after seeing its errors: for a new stack nothing would say which to pick.

### (d) Wait for a quieter testbed

- Good, because a dedicated machine would need less of a machine model.
- Bad, because there is none yet (a second machine is the owner's decision), and the laptop's
  predictions would keep promising too much until then.

## More Information

- [ADR-0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md):
  the seal this amends.
- [ADR-0093](0093-fit-a-services-falling-cpu-per-request-as-a-law-of-its-rate.md): the law the
  knees are predicted with.
- [ADR-0094](0094-fit-the-knees-range-on-every-experiment-the-engine-is-tuned-on-with-an-error-each-experiment-shares.md):
  the range's form, refitted here.
- [docs/validation.md](../validation.md), [docs/validation/dev-checks.md](../validation/dev-checks.md),
  [docs/validation/knee-range.md](../validation/knee-range.md).
