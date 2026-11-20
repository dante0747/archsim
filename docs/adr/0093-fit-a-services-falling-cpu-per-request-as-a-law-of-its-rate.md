---
status: accepted
date: 2026-11-20
decision-makers: Majid Abarghooei (owner)
---

# 0093. Fit a service's falling CPU per request as a law of its rate

## Context and Problem Statement

The benchmark designs charge a service the CPU per request its calibration measured. Since
engine 0.15.0 (W21) they let that cost change with the rate one replica gets
(`serviceTimeByLoad`): each rate of the calibration staircase gets its CPU per request net of
idle, fitted so that it never rises, as a factor of the top rate's. Above the top rate the curve
is flat.

- **The cost keeps falling above the staircase.** Protocol v6 climbs the staircase to about 65%
  of the service's CPU. In every Node.js experiment the CPU per request measured above it kept
  falling: cache-aside's service went from 0.175 ms at the top of its staircase (3,470 req/s) to
  0.138 ms at 6,210 req/s, with its core 86% busy. Held flat above the top, the model charges a
  service near its knee 16% too much CPU per request (the median over the 131 measured steps of
  kept v6 runs at 75% busy or more; 21% in cache-aside), and so predicts too little capacity:
  cache-aside's median knee error is -22%.
- **W26's extension did not fix it.** It carries the upper staircase's fall on in ln(rate) by
  at most one measured stretch, and only when the top kept the CPUs 45% busy
  (`CurveExtensions`, the harness's `CurveForm.LOG`). Re-simulated in-sample it moved
  cache-aside from -25% to -23% and nothing else, so it stayed off.
- **The accuracy review of 18 Nov 2026** (its recommendation 5) asked for the flat curve to be
  replaced by a fitted law: a cost per request that falls as a fixed cost is shared, plus
  contention terms for a cost that rises near full load. Its test: fitted on the staircase at
  65% load or below, the law predicts the CPU per request at 90% within 5%.

Which law, carried how far above the staircase, and where in the model?

## Decision Drivers

- A curve is judged where it is extrapolated: on the CPU per request the kept runs measured
  above their staircases, not on how well it fits the staircase.
- A curve that charges too little promises capacity that is not there, so the extrapolation
  stops where the evidence stops.
- No change to how the engine simulates a curve, or to what a saved design means.
- Customers' calibration from their own telemetry changes only on evidence from that kind of
  data.

## Considered Options

- (a) Keep the curve flat above the top (today)
- (b) W26's log extension
- (c) A wake-up law: a CPU that was idle pays to wake, `c = a + w × (1 - utilisation)`
- (d) The review's form, a fixed cost per second shared by the requests: `c = a + b / rate`
- (e) A floor plus a power law, `c = floor + shared × rate^-k` with `0 < k ≤ 1`, carried above
  the staircase to where the service's CPUs fill, but no further than twice the top rate
- (f) (e) carried as far as the CPUs fill
- (g) A pure power law fitted above 50 req/s, `c = B × rate^-k`
- (h) (e) with contention and coherency terms (the universal scalability law) for a cost that
  rises near full load

## Decision Outcome

Chosen option: (e), because it is the only form that comes within a few percent of the
measured cost above the staircase on v6 runs without promising capacity on the shorter v5
staircases.

Each option was fitted on every kept run's staircase (each rate's CPU per request net of idle,
`cpuByLoad`) and asked for the service's CPU per request at every measured step above the
staircase that met the objective. The median error, with the signed median in brackets
(positive charges too much):

| Option | v6 runs, 75% busy or more (131 steps) | v6 runs, 85% to 95% busy (34) | v5 runs, 75% busy or more (40) |
| --- | --- | --- | --- |
| (a) flat | 16.2% (+16.2%) | 20.2% (+20.2%) | 31.5% (+31.5%) |
| (c) wake-up law | 27.9% (-27.9%) | 33.6% (-33.6%) | 62.5% (-62.5%) |
| (d) `a + b / rate` | 32.4% (+32.4%) | 37.1% (+37.1%) | 44.9% (+44.9%) |
| (g) pure power law | 3.7% (-2.9%) | 3.2% (+1.3%) | 30.1% (-30.1%) |
| (f) the law, carried to the fill | 5.4% (+3.8%) | 6.1% (+6.1%) | 28.0% (-28.0%) |
| **(e) the law, at most twice the top** | **5.4% (+3.8%)** | **6.1% (+6.1%)** | **10.2% (-5.9%)** |

Every kept run re-simulated in-sample with today's engine (`resimulate`), the median knee error
of each experiment's counted v6 runs:

| Experiment | Runs | Flat (`0.16.0-java`) | The law (`1.6.0-law`) | The law on the laptop's host machine, a what-if (`1.6.0-law-host`) |
| --- | --- | --- | --- | --- |
| pg-mixed | 6 | +16% | +19% | +6% |
| pg-hot-row | 5 | -10% | -10% | -11% |
| cache-aside | 6 | -22% | -7% | -20% |
| kafka | 5 | -3% | +7% | -13% |
| java-mixed | 3 | +70% | +72% | -8% |
| go-mixed | 3 | +67% | +67% | -16% |
| python-mixed | 3 | +29% | +29% | +17% |
| chain | 3 | +30% | +37% | 0% |
| cache-ttl | 3 | -6% | +10% | 0% |
| Median of the 37 runs' absolute errors | | 16.3% | 12.9% | 11.9% |

- **The law.** `floor + shared × rate^-exponent`: the floor is the work every request needs;
  the shared part is what requests share as they come closer together (waking an idle CPU,
  timers, cold caches). The exponent is fitted in hundredths from 0.01 to 1, so the CPU the
  shared part takes each second never falls as the rate rises, and a replica's busy time grows
  with its rate. For each exponent the floor and the shared part are fitted by least squares on
  the relative error, the floor held at 0 where it would fall below; the exponent whose fit
  errs least wins. Where no fit falls with the rate (python-mixed's staircase rises at its top),
  the curve is the flat one, as before. It is `CostLaw` and `CostLaws` in the harness's
  `inputs` package, with `LawCurveInput`: only the harness uses it, so the engine, whose
  sources a freeze pins, does not change.
- **How far.** From the staircase's lowest rate to where the service's CPUs fill, but no
  further than twice the top rate, flat above that. On v6 staircases, which stop at 60% to 65%
  of the core, twice the top passes the fill, so the bound changes nothing there; on v5
  staircases, which stopped at a third of it, it keeps the law from charging the busiest steps
  28% too little.
- **How the engine gets it.** As the `serviceTimeByLoad` table it already reads: points a
  quarter apart in rate from the lowest rate to the end, the top rate among them at a factor of
  1. The engine interpolates the table in log(rate), as it always has; between points a quarter
  apart the interpolation and the law differ by less than 1%. No schema change: a saved design
  and its results stay as they were.
- **On a host machine.** With `BENCH_HOST=on` the law is fitted on the work per request: each
  staircase pass's CPU per request net of idle times the machine's thread speed during that
  pass. The host machine slows the design's threads as they fill, so a law fitted on the
  measured cost would count that slowdown twice.
- **Where it is used.** The benchmark harness's derivation (`CurveForm.LAW`): calibration,
  `resimulate` and `range-fit`. The service's CPUs are its runtime's in the experiments'
  registry, which `compose.yaml`'s cpusets are held to; on a host machine they fill later than
  where the law, fitted at full speed, says, since the host slows them first. The tests that derive the recorded inputs and checks again keep
  the flat curve they were recorded with (`ModelInputs.asRecorded`).
- **The host machine stays off.** On its own the law moves cache-aside from -22% to -7% and
  lowers the median error, but it takes cache-ttl, chain, Kafka and pg-mixed further toward
  promising capacity that is not there: what still holds them back is the laptop itself, whose
  threads slow each other as more of them are busy (its contention check of 1 Nov 2026). With
  W27's host machine as well, every experiment is within 20% and most errors fall on the safe
  side, the first held-out set's java-mixed and go-mixed among them (+70% and +67% become -8%
  and -16%). But that host machine's fit assumes two numbers no quiet check has measured, k6's
  CPU per request and the machine's background at rest, and no freeze names a fit, so a sealed
  experiment cannot run with it ([ADR-0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md)).
  Turning it on is a decision of its own, after a quiet contention check measures the two.
- **Not the review's contention terms.** Above the staircase the service's own cost did not rise
  in any experiment: the flat curve errs high there, not low. What rises with load on this laptop
  is the machine, which the host machine describes; a contention term fitted to the service would
  mix the two.
- **Not the database or the cache.** PostgreSQL's CPU per query and Redis's per command fall
  with load too, but neither sets a knee in the nine experiments: in pg-mixed, the only one where
  PostgreSQL gets busy, the flat curve is as near as any law (-5%). They keep a single cost.
- **Not customers' calibration yet.** `archsim calibrate` keeps fitting telemetry with
  `LoadCurves`, flat above the busiest bin, which errs toward less capacity. A customer's
  service, runtime and machine need not share the laptop's services' fall; a backtest on
  customer telemetry (the review's recommendation 7) would show whether the law holds there.
- **A model change, with the engine at 1.6.0.** A design and a seed give the results they gave,
  so the engine's version stays. How the harness derives the inputs is part of the model
  ([ADR-0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md)):
  `engines.json`'s 1.6.0 entry says so. 1.6.0 already lists all nine measured experiments as
  tuned on, and the second held-out set
  ([ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md)) stays held
  out: it is where the law is first tested on data nobody has seen.

### Consequences

- Good, because the service's predicted CPU per request near its knee is within about 5% where
  the flat curve was 16% to 20% high, and the experiment the falling cost held back most,
  cache-aside, moves from -22% to -7%.
- Good, because the curve stays the table the engine, the instant estimate, auto-sizing and the
  review already read, and a saved design does not change.
- Bad, because on its own it makes four of the nine experiments' knees more optimistic, by 3 to
  16 points: the service's cost was too high, and that had hidden the machine's slowdown, which
  the designs do not model while the host machine is off.
- Bad, because it does not meet the review's test of 5% at 90% busy (6.1% on the median, still
  erring high): near cache-aside's knee the cost falls faster than the law.
- Bad, because a staircase that stops low is still extrapolated up to twice its top rate on a
  law fitted where the machine was idle most of the time. On v5 runs that cost 10% median error,
  erring low.
- Bad, because it is fitted and checked on one laptop's Node.js, Java, Go and Python services.
  How much of the fall is the service and how much is Windows and its virtual machine waking
  from idle is not separated; on a server that stays busy the fall may be smaller.
- Bad, because the comparison is in-sample: the laws and the knees are checked on runs already
  measured. The second held-out set is the first test out of sample.
- Neutral, because the database's and the cache's costs and customers' calibration are
  unchanged, for the reasons above.

### Confirmation

- `CostLawTests` and `CostLawsTests`: the law's cost, where the CPUs fill (below one request a
  second too, and never), where a curve ends and why, its factors (none within half a step of
  an end) and its words; the fit of points on a law, of a rising cost (none), of too few or too
  close rates, of a fall steeper than any exponent (the floor held at 0), and of cache-aside's
  staircase, whose cost at 6,210 req/s it gives within 7%.
- `LawCurveInputTests`: the curve from the lowest rate to the fill, to twice the top rate or to
  the top rate, the top rate at a factor of 1, its words, the flat curve of a two-rate staircase,
  and on a host machine the law of the work per request; `LoadCurveFormTests`: each form, the
  log form carrying a fall on; `CurveDerivationTests`: today's derivation fits the law,
  `ModelInputs.asRecorded` the flat curve.
- `RecordedInputsTests`, `CalibratedInputsTests`, `DevCheckTests` and the parity task's
  `KeptRunsChecksTests` and `HostParityTests` derive the recorded inputs again with the flat
  curve, and still match them byte for byte.
- `resimulate --label 1.6.0-law`, and with `BENCH_HOST=on` and W27's what-if fit
  `--label 1.6.0-law-host`, re-simulated every kept run (`benchmarks/results/dev/`,
  `docs/validation/dev-checks.md`).

## Pros and Cons of the Options

### (b) W26's log extension

- Good, because it is built and tested.
- Bad, because it extends only when the top kept the CPUs 45% busy and the fall stands out by
  two standard errors, and then by one measured stretch: on the kept runs it moved one knee by
  two points.

### (c) A wake-up law

- Good, because it has a physical reading: a request that finds the CPU idle pays to wake it.
- Bad, because the measured cost falls by three to five times while the service is still under
  20% busy, which a cost proportional to the idle share cannot do: fitted, it charges too little
  everywhere above the staircase.

### (d) A fixed cost per second shared by the requests

- Good, because it is the simplest amortisation.
- Bad, because the cost at 50 req/s, where the CPU sleeps between requests, dominates the fit of
  its two numbers: above the staircase it charges a third too much.

### (f) The law carried as far as the CPUs fill

- Good, because on v6 runs it is (e).
- Bad, because on v5 runs, whose staircases stopped at a third of the core, it charged the
  busiest steps 28% too little: the unsafe side.

### (g) A pure power law

- Good, because it is the nearest on v6 runs.
- Bad, because without a floor its cost never stops falling: on v5 runs it charged 30% too
  little, and fitted on Kafka's or pg-hot-row's short staircases it puts the CPUs' fill at four
  to twenty times the top rate.

### (h) Contention and coherency terms

- Good, because a cost that rises near full load is real on many machines.
- Bad, because nothing in the kept runs shows the service's own cost rising: the rise on this
  laptop is the machine's, which the host machine is for. Two more terms fitted on four
  staircase rates would be fitted to noise.

## More Information

- The accuracy review of 18 Nov 2026, recommendation 5; `IMPLEMENTATION_STATUS.md`, W21, W26
  and W27.
- [ADR-0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md):
  a change to how the harness derives the inputs is a model change.
- [ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md): the held-out
  set that tests it.
- [docs/validation.md](../validation.md), Protocol; [docs/validation/dev-checks.md](../validation/dev-checks.md).
