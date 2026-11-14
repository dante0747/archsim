---
status: accepted
date: 2026-11-14
decision-makers: Majid Abarghooei (owner)
---

# 0074. Bill a workload's peak for the hours a month it lasts, and autoscaled parts at the count they settle at

## Context and Problem Statement

Since `b04b5b00` (28 Oct 2026, "price managed and per-request services at the steady base
load"), the monthly cost prices all 730 hours of a month at the workload's steady base load
(`BaseRate`: a constant pattern's rate, a burst's rate outside the burst, a ramp's low end, a
trace's median). Per-request lines use the rates a run measured at the base. An autoscaled
service or cluster is billed its average active instances in the run times base ÷ offered, at
least a floor (`Instances.averaged`, in archsim-engine's `cost` package).
[docs/pricing.md](../pricing.md) says "a burst's or a ramp's peak is not billed all month". In
fact the peak is not billed for a single hour.

On a ramp, this makes an autoscaled design cost what a fixed one does.
`examples/black-friday-kubernetes.json` ramps from 300 to 1,500 req/s. In the baseline, the HPA
runs the checkout on 3 to 10 pods, 5.29 on average. 5.29 × 300 ÷ 949.8 = 1.67 pods, which the
floor raises to 3. The `fixed-pods` scenario keeps 3 pods and fails 36% of its requests. Both
cost $824.80 a month. Results › Compare › "Cost vs. speed" plots them at one cost, and nothing
in the report says what the peak costs.

The same method has two more faults:

- The floor is `replicas`, the count a service starts with, not `minReplicas`. A service that
  starts above its minimum is billed its starting count at the base load, where its HPA would
  scale down. Python 0.16.0's `cost.py` did the same, and clusters already use `minNodes`.
- The run's average is the wrong number to scale. It includes the warm-up and the HPA's lag, and
  a replica count goes up in steps between a minimum and a maximum, so it does not grow in
  proportion to the load.

How should the monthly cost count autoscaling, and the peak it scales for?

## Decision Drivers

- Honest and stated: every hour of the month is billed at a load the report names
  ([CONTRIBUTING.md](../../CONTRIBUTING.md): clarity about assumptions comes first).
- One figure everywhere: the Cost vs. speed map, the scorecard, `archsim check`'s cost gate, the
  CLI and the MCP tools all read `cost.monthly`, so what autoscaling costs belongs in it.
- Independent of the run's length: a 60-s run and a 120-s run of the same design and month
  cost the same.
- The contracts: 0.16.0's golden entries are never recorded again, so every departure from them
  is listed with its reason (`GoldenDepartures`, archsim-engine's README). Digests keep their
  definitions ([ADR-0050](0050-one-report-per-seed-with-pythons-digest-definitions.md)).

## Considered Options

- (a) Keep base-load pricing, and report the peak's cost as a separate figure
- (b) Add a workload input for the hours a month at the peak, and bill base and peak hours
  separately
- (c) Bill the run's average instances, as Python's `cost.py` did before 28 Oct

## Decision Outcome

Chosen option: (b), built on (a)'s figures. It is the only option that bills a month the user
has stated: they say how long their peak lasts, and every hour is billed at either the base or
the peak. The default of 0 peak hours gives today's month exactly, so no design's cost changes
unless it sets the hours.

1. **The input.** `workload.peakHoursPerMonth`, from 0 to 730, default 0. A document leaves it
   out while it is 0 (`@OmitWhenDefault`, like `pricing.burstable`). This is schema 1.8, with an
   upgrade step that changes only the version. The field counts the hours a month at the
   pattern's peak rate (`Workload.peakRate()`: a burst's rate, a ramp's high end, a trace's
   highest point). A constant pattern has a single rate, so the field changes nothing there.
   It belongs to the workload because it describes the traffic, and scenarios already override
   the workload ("the sale lasts a week").
2. **Two hours, one month.** The engine prices one hour at the base rate and one at the peak
   rate, with each line's instances and per-request quantities at that rate. After a run, it
   uses the rates measured in the run's buckets at that level, scaled to it, as `MeasuredUsage`
   does for the base. Before a run, it uses the static load estimate at that rate. With H peak
   hours, monthly = (730 − H) × the base hour + H × the peak hour. Surplus CPU credits and
   per-request items count at each level. `perMillionRequests` divides by the month's requests
   at both levels. Report 1.16 adds `cost.peakHours`, `cost.hourly` (`base` and `peak`) and each
   line's `peakInstances`. These are option (a)'s figures.
3. **An autoscaled part's count.** At a given rate, an autoscaled service runs the count its
   autoscaler settles at. That is `Autoscaler`'s own rule: ceil(busy replicas ÷
   `targetUtilization`), kept between `minReplicas` and `maxReplicas`. The busy replicas are
   those the run measured, scaled to the rate; before a run, they are the instant estimate's. A
   cluster runs the nodes its pods need at that rate, kept between `minNodes` and `maxNodes`.
   This replaces the scaled average, and it answers the floor question: the floor is
   `minReplicas`, because at the base load the HPA scales down to it, not to the starting count.
   Before a run, an autoscaled service is billed by the same rule instead of by `replicas`.

Take black-friday-kubernetes with 24 peak hours (one sale day in a November bill). The HPA
design runs its checkout on 3 pods at the base. At a sustained 1,500 req/s it runs 10 to 14 pods
(10 when the run ended, 14 its maximum), which adds $13 to $20 a month. `fixed-pods` stays at
$824.80 and still fails 36% of the run's requests. That is the honest size of autoscaling's
cost here: a pod is cheap ($0.0756 an hour), and most of the bill is the database.

### Consequences

- Good, because the monthly cost covers a month the user stated, and the Cost vs. speed map,
  the cost gate, the CLI and MCP show the peak's cost without any code of their own.
- Good, because per-request lines get the peak too. A serverless function's burst, DynamoDB on
  demand and an API gateway are all billed at the base load all month today.
- Good, because the wrong floor and the scaled average go, both before a run and after one.
- Bad, because there is one more input to explain. At 0 hours the peak is still free, and the
  cost notes must say so as plainly as they do today.
- Bad, because two levels simplify the month: a ramp's middle and a trace's shape are billed at
  the base or at the peak, not in between.
- Bad, because the schema version changes, with the churn in the golden entries described below.

### What each option changes

The 50 recorded runs in `parity/golden/templates` (two per template) are compressed to about
1,500 requests. Six of them have an autoscaled line: black-friday-kubernetes's checkout, and the
clusters of kubernetes-pods-pending and kubernetes-shared-pool, two runs each. None of those parts
leaves its minimum, because each run lasts 2 s and ends before the 15-s scale interval. In every
template that autoscales, `replicas` equals `minReplicas`, so the 149 cost entries recorded
before a run keep their figures wherever the count rule settles at the minimum at the base rate.
Any entry where it does not is a departure to explain. `catalog/pricing.json.gz` covers
snapshots and lookups, not instance counts. So no option is expected to change a golden cost
figure, only its words and the fields around it. No option changes a `resultDigest`, because
the digest does not cover `cost` (`Provenance.RESULT`: summary, time series, nodes, edges,
window).

| | (a) peak figure | (b) peak hours (chosen) | (c) run's average |
| --- | --- | --- | --- |
| `cost.monthly` | unchanged | unchanged at 0 hours | up wherever an HPA scales: black-friday's baseline is $950.94 |
| Golden entries | 6 lines' basis wording; `cost`'s new fields, listed as a report difference | as (a), plus the schema below | 6 lines' basis wording; the base-load note in all 50 runs |
| Engine, digests | 1.1.0 (the count rule); digests unchanged | 1.1.0; `inputDigest` changes on every new run (the input carries `schemaVersion`) | 1.1.0; digests unchanged |
| Report | 1.16: `hourly`, `peakInstances` | 1.16: also `peakHours` | unchanged |
| Document schema | unchanged | 1.8: each golden entry that writes a document or an input departs by the stamp alone, and the JSON Schema departs by one property and the version it names | unchanged |
| Web | the scorecard shows an hour at the peak; the map still plots the base month | as (a), plus the field in the workload panel; the map follows `monthly` | no change |
| Sizing audit | unchanged | unchanged at 0 hours; a template that sets hours gets its reference recomputed by hand | unchanged in its 15-s runs, which never add a pod, but black-friday's full run ($950.94) would break its bound (1.15 × $825 = $948.75) |
| `archsim check` | unchanged | unchanged at 0 hours | the cost gate can trip for autoscaled designs on their first run with the new engine: black-friday's baseline rises 15%, and the default threshold is 10% |

### Confirmation

- Tests written first (archsim-engine's `cost` tests):
  - black-friday's baseline at 0 hours still costs $824.80;
  - at 24 hours, the checkout line adds 24 hours at its settled count, and `fixed-pods` does not
    change;
  - a service with `replicas` 5 and `minReplicas` 2 is billed 2 where the base load needs 2;
  - a cluster's count stays between its minimum and maximum.
- The model's upgrade test: a 1.7 document loads as 1.8 and nothing else changes.
- `GoldenDepartures` and archsim-engine's README list each departure with its reason. A
  structured departure puts 1.7 back into each document and input, and checks the rest exactly.
- `./gradlew build` passes, and so do the web checks.
- [docs/pricing.md](../pricing.md) and [docs/simulation-model.md](../simulation-model.md) say
  what a month is.

## Pros and Cons of the Options

### (a) Keep base-load pricing, and report the peak's cost as a separate figure

- Good, because there is no new input and no schema change: `monthly`, every golden figure and
  the cost gate stay the same.
- Good, because it is (b)'s first slice: the hourly figures and the count rule are the same code.
- Bad, because the map, the cost gate, the CLI and MCP still read a month with no peak in it.
  Putting autoscaling on the map would need a second axis (an hour at the peak) or a "peak
  hours" control in the viewer: a number the engine never computed and the CLI does not have.
- Bad, because the peak figure fits neither unit. As a monthly figure ("if the peak lasted all
  month") it overstates the bill. As an hourly figure it cannot share the monthly axis.

### (c) Bill the run's average instances, as before 28 Oct

- Good, because there is no new input and no schema change, and the map shows a gap at once.
- Bad, because it mixes two months in one figure: instances at the average of a two-minute
  window, and per-request lines at the base load.
- Bad, because it depends on the run's length and on where the peak falls. The same burst
  costs differently in a 60-s run and a 120-s run, and Black Friday's ramp is billed as if every
  hour of the month were its average.
- Bad, because it reverses `b04b5b00` for one kind of line, with no reason why that line should
  differ from the others.

## More Information

- Build (b) in two slices. Slice 1 adds the count rule, the floor and the two hourly figures
  (report 1.16, engine 1.1.0), which is option (a) on its own and leaves `monthly` as it is.
  Slice 2 adds the input, schema 1.8 and the month's sum. If (b) is not accepted, slice 1 still
  stands as (a).
- Which templates set the hours, and to what, is a template change to review on its own. For
  example, black-friday-kubernetes could use 24, for one sale day.
- With every option, black-friday's map stays nearly flat because no scenario sizes the
  checkout for the peak. A "fixed at the peak (14 pods)" scenario would cost about $772 a month
  for the checkout, against $165 for the HPA's base, which is the saving autoscaling buys.
- A related fault that this ADR does not decide: when no bucket of the run's window is at the
  base load, the base's per-request rates fall back to the whole window's mean, scaled down. In
  black-friday, the ramp passes the base during the warm-up, so this always happens. Failures at
  the peak then lower the base's rates: `fixed-pods` bills the payments API for 38.9 calls/s at
  the base, against 60.7 for the baseline, although at 300 req/s both designs make the same
  calls.
- Related: [docs/pricing.md](../pricing.md), [ADR-0020](0020-golden-files-of-the-frozen-engine.md),
  [ADR-0026](0026-component-specs-and-price-snapshots-in-the-model.md),
  [ADR-0050](0050-one-report-per-seed-with-pythons-digest-definitions.md).
