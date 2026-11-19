---
status: accepted
date: 2026-11-19
decision-makers: Majid Abarghooei (owner)
---

# 0086. Keep the load open-loop past the objective, and close in on the measured knee

## Context and Problem Statement

A strict review of the accuracy benchmarks (18 Nov 2026) found two ways in which a run's measured
knee depended on the harness rather than on the stack:

- **k6 ran out of virtual users.** `load.js` started each step with virtual users for 50 ms of
  arrivals and fifty more, and let k6 add up to 3,000 as it went. k6 adds them one at a time and
  drops the requests it has no user for meanwhile, so past the knee the arrivals were no longer
  open-loop: the latencies it measured were held down by how fast it added users, and the
  success rate fell by the share k6 never sent. java-mixed's latest run measured a p95 of
  56.8 ms and 97% success at 5,925 req/s with no request failing: 2.8% were dropped.
- **The ladder stands where the prediction put it.** Its steps are shares of the predicted knee
  (0.25 to 1.5 times it), and the measured knee is interpolated between the last step that met
  the objective and the first that missed it. When the prediction is far off those two are far
  apart: java-mixed's were 3,950 and 5,925 req/s, and the interpolation ran towards a step
  whose latency was held down as above.

The review also noted that the predicted knee comes from one simulation of each load at one
seed, so nobody knew how much of it was what that seed drew. And k6's own CPU was not recorded,
so a run could not show whether the load generator had been short of CPU. How should a run load
the stack and find its knee, so that the measured knee is the stack's?

## Decision Drivers

- The measured knee does not depend on where the prediction put the ladder.
- A step's latencies are whole at least well past the objective, and a step that could not be
  loaded open-loop says so.
- Predictions stay sealed before each measurement
  ([ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md)).
- The stacks still fit the one laptop they run on (8 logical CPUs, an 8 GiB Docker VM), where
  k6 has one or two physical cores.
- Recorded results are not rewritten
  ([ADR-0068](0068-port-the-harness-to-a-module-that-calls-the-engine-in-process.md)), and the
  kept runs' statistics stay comparable with new ones.

## Considered Options

- (a) Every virtual user ready before the first request, enough for at least a quarter of a
  second of arrivals to wait at once, with k6's garbage collector relaxed so that they cost the
  measurement little up to about a thousand of them; k6's CPU recorded; each step's dropped share shown; and the run closes in
  on the measured knee by halving between the steps either side of it
- (b) Virtual users for the whole 2 s timeout at every rate, so that the load is open-loop
  until requests time out
- (c) Another load generator (wrk2, or k6 on a second machine)
- (d) Leave the generator and place the ladder more densely around the predicted knee

## Decision Outcome

Chosen option: (a), protocol v7.

- **Virtual users.** The harness tells k6 how many to hold (`VUS`, from `Traffic.virtualUsers`
  and `Protocol`), and k6 starts them all before the first request (`preAllocatedVUs` =
  `maxVUs`): as many as a whole 2 s timeout's arrivals up to 1,000, as many as a quarter of a
  second's above that, fifty more, and 3,000 at most.
  - Up to 500 req/s the load is open-loop until requests time out, as the model's clients are.
  - From 500 to 4,000 req/s a step holds 1,050 users, and above that a quarter of a second of
    its arrivals, five times the objective's p95: k6 drops a request only once that many are
    waiting, so a step's verdict and the latencies either side of the knee are whole. One
    stall must last nearly half a second to cost a 20 s window 1% of its requests.
  - 3,000 users hold a quarter of a second of arrivals up to 11,800 req/s.
  - The warm-up hands its users, with their connections, to the measured window: it stops at
    once (`gracefulStop` 0) and the window starts in the same instant. k6 plans the users of
    scenarios that overlap side by side, so a warm-up left to finish its requests doubled them
    (6,100 for a step of 3,050 in a first check). The requests the warm-up still had in flight
    are cut off and counted to it.
  - Keys are drawn without a per-user table (uniform directly, Zipf by rejection-inversion,
    which `K6ZipfTests` checks against the exact distribution for cache-aside's keyspace too).
- **k6's garbage collector.** More virtual users are not free: k6's collector marks every
  user's memory in each cycle, and with a thousand of them its cycles came often enough to add
  a tail of their own. On the idle laptop, pg-mixed at 3,000 req/s (below its knee), the median
  0.5 to 0.8 ms throughout:

  | Virtual users | k6's collector | p95 | p99 |
  | --- | --- | --- | --- |
  | 200 (the kept runs' at this rate) | default | 5.9 to 10.9 ms | 15 to 42 ms |
  | 500 | default | 6.3 ms | 15 ms |
  | 650 | default | 23.5 ms | 68 ms |
  | 1,000 | default | 59 ms | 173 ms |
  | 3,050 | default | 142 to 241 ms | 250 to 624 ms |
  | 650 | relaxed | 2.7 ms | 10 ms |
  | 1,050 (v7's at this rate) | relaxed | 2.9 to 5.4 ms | 7 to 18 ms |
  | 2,050 | relaxed | 5.8 to 10.0 ms | 35 to 38 ms |
  | 3,050 | relaxed | 4.2 ms | 68 ms |

  So `compose.yaml` relaxes it for every k6 run: `GOGC=800` lets k6's heap grow eight times
  between cycles, and `GOMEMLIMIT=3GiB` keeps it inside Docker's VM (k6 held 1.5 GiB with 1,050
  users and 1.9 GiB with 3,050). That is why the users stop at 3,000 and a step covers a
  quarter of a second, not the half second or the whole timeout first intended: with the
  collector at its default, the first version of this decision would have read a p95 of
  150 ms where the stack's is 3 ms.
- **Every k6 run.** The calibration's passes, the settling, the smoke test and the machine's
  checks go through the same `K6.run`, so they hold these users too. What the calibration
  measures and derives does not change; its `protocol` reads `v7`, so that runs loaded this way
  are told apart. A contention check's fit of k6's CPU per request is not comparable with the
  kept ones.
- **What a step records.** Each measured step keeps `generator` (`virtualUsers`, and `coverS`,
  the seconds of its arrivals they hold) and `droppedRepeats`, the repeats in which k6 dropped
  requests; each repeat keeps `k6Cores`, k6's busy cores from 2 s into its measured window to
  2 s before its end (`K6Cpu`, which the contention check already used). The window is counted
  from the load's start, the service's first counted request (`LoadStart`, which the
  containers' sampler uses too), because k6 takes seconds to start its users before it sends
  anything (3 to 8 s for 3,050 to 8,000 of them in the checks).
- **Closing in on the knee.** After the ladder (and its climb when the top still meets the
  objective), the run measures halfway between the last step that met the objective and the
  first that missed it until the two are within 3% of the higher (`KneeBracket`, on
  `Knees.gridKnee`, which the page reads the knee from), and halves the lowest step when even
  that missed, down to the ladder's least step of 10 req/s. Each such step is predicted, saved
  and sealed before it is measured, as a step above the ladder already was (`extensionSeals`),
  and is marked `refinement` in the predictions. It stops after eight steps (and logs that it
  did), and does not start on a ladder with a step that met the objective above one that
  missed it (a disturbed run), or whose lowest step answered no request (a stack that is down).
- **The predicted knee over seeds.** A prediction searches its knee again at nine more seeds,
  from the first knee (`SeedKnees`; `kneeBySeed` in `predictions.json`, kept in the comparison
  and shown in the knee's sentence), so that a run shows how far the knee moves with what the
  simulation drew. The prediction stays the first seed's, because every kept prediction, every
  development check and the range's fit are that seed's. A scratch search of the nine kept
  calibrations at ten seeds, bisecting to 0.5% (19 Nov 2026, engine 1.5.0, not kept), put the
  knees within 0.4% to 1.8% of each other on eight experiments and within 4.6% on cache-ttl,
  whose prediction of 5,100 req/s sits near the top of 4,906 to 5,138: the seed is not where
  the knee errors come from. A run's own searches resolve each knee to 1%, so a recorded
  spread under about 1% is at their resolution.
- **The comparison.** The steps a run added to close in on its knee count towards both knees
  and are kept apart from the ladder's (`kneeSearchRows` in `comparison.json`, beside `rows`,
  both in load order). `measured.json` marks such a step too (and a step above the ladder as
  `extension`, which nothing reads yet), so that a development check, which simulates every
  measured load afresh, tells it as the run's own comparison does. They
  cluster just under the knee, where the p95 climbs steepest, so
  counting them would make the p95 criterion stricter for a v7 run than for a kept one and tie
  it to the knee's error. The p95 and resource statistics, the criteria and the chart stay the
  ladder's. A kept run's `comparison.json` is computed byte for byte as before.
- **The page.** Every run's ladder shows the share of each step's requests k6 dropped (the
  median of its repeats), the kept runs' too (their `measured.json` always recorded `dropped`),
  and says what such a step's numbers are worth; it lists the knee-search steps in load order,
  marked; and the knee's sentence names the two steps the measured knee lies between.

### Consequences

- Good, because a run's measured knee is known to 3% wherever the prediction put the ladder,
  unless the eight steps, the 10 req/s floor or a disturbed ladder end the search first, and a
  prediction that is far off no longer widens the measured knee's uncertainty.
- Good, because the page now shows how much of each kept step k6 never sent, and that the kept
  knees lie somewhere between two steps: java-mixed's between 3,950 and 5,925 req/s.
- Good, because a run shows what k6 itself used.
- Bad, because a run takes longer: up to eight more steps of about a minute and a half each,
  and a few seconds more in every k6 run while k6 starts its users.
- Bad, because above 500 req/s the load is still not open-loop up to the timeout: once the
  users' share of arrivals is waiting, k6 drops requests, so the overload a model step predicts
  (requests timing out) is not measured like for like there. Such steps show their dropped
  share, and nothing at or below the knee depends on them. The old script could reach 3,000
  users in an overloaded step where v7 holds 1,050 to 3,000; past about a thousand its own
  measurements carried k6's garbage collection, by an amount the kept runs cannot tell.
- Bad, because a step's connections to the service rise with its virtual users (k6 takes them
  in turn): from a few hundred to 1,050 for most steps and up to 3,000 at the highest.
- **What stays open: k6 at higher rates.** The check above could only run below pg-mixed's
  knee, about 4,400 req/s. k6 allocates faster at the 6,000 to 10,000 req/s the Go and Spring
  Boot stacks reach, with 1,550 to 2,550 users, and it has one or two physical cores on this
  laptop: it filled both of its CPUs at 6,000 req/s in every check, old script and new. Each
  repeat's `k6Cores` and each step's dropped share show a generator short of CPU or of users.
  They do not show a collector's tail, which left the median alone and raised the p95 fifty
  times: only the table above, repeated at those rates on a stack that holds them, would. The
  load generator on a machine of its own, the review's first recommendation, remains the
  remedy.
- Neutral, because the kept runs were measured the old way and stay as recorded: their knees
  are not comparable to the last 3% with v7's, and the next campaign measures all nine
  experiments again.

### Confirmation

- `TrafficTests` covers the virtual users a rate gets and the arrivals they hold; `K6Tests`
  that k6 is told them; `K6CpuTests` that k6's CPU is read inside the measured window, counted
  from the load's start; `MeasurementTests` what a step and a repeat record;
  `StepAggregationTests` the repeats that dropped requests.
- `KneeBracketTests` covers where a run measures next (between, below, within 3%, a disturbed
  ladder, a stack that is down, rounding); `ExperimentRunTests` that a run closes in on a knee
  between two steps, above the ladder after a climb and below it, each step sealed before it
  is measured, and that eight steps end the search.
- `SeedKneesTests` covers the knee at other seeds: each searched from the first knee, two
  seeds simulating one load differently, and the least and greatest of them; `DesignsTests` a
  design at another seed.
- `ComparisonTests` covers the knee-search rows (apart from the ladder's, counted in the knee,
  left out of the p95 and resources summaries) and rows in load order; `DevCheckTests` the
  same split in a development check; `CommittedComparisonsTests` that the 67
  kept comparisons are unchanged.
- `PageBlocksTests` covers the dropped shares and their note on the committed runs, a
  knee-search step in its place, and the knee's two steps; `ReportParityTests` regenerates the
  page.
- Checks on a running stack (19 Nov 2026, not kept):
  - On the idle machine, the table above; and with the settings as committed, pg-mixed at
    3,000 req/s read a p95 of 2.9 and 3.9 ms (p99 7.1 and 9.7 ms) with 1,050 users, and at
    6,000 req/s, past its knee, a p50 of 94 ms and a p95 of 376 ms with 1,550 users, 19,978
    requests dropped once a quarter of a second of arrivals were waiting.
  - While the machine was building: at 6,000 req/s the old script read a p50 of 78 ms and a
    p95 of 156 ms and dropped 40,141 requests. With the hand-over k6 held 3,050 users when told
    3,050 (6,100 without it).

## Pros and Cons of the Options

### (b) Virtual users for the whole timeout at every rate

- Good, because the load would be open-loop until requests time out at every rate, as the
  model's clients are.
- Bad, because a 10,000 req/s step would need 20,000 virtual users: several times the number
  at which k6's own garbage collection showed in what it measured, about 6 GiB of an 8 GiB
  Docker VM, and as many connections to a service whose connector may accept 8,192.

### (c) Another load generator

- Good, because a generator built for constant throughput (wrk2) or on its own machine removes
  the generator's CPU from the stack's machine.
- Bad, because it needs a second machine, which the owner has put off, or a rewrite of the
  traffic (mixes, Zipf keys, phases the services count by) that every kept run was measured
  with.

### (d) A denser ladder

- Bad, because the steps would still stand where the prediction is, so a prediction far off
  would leave the knee between two distant steps as before.
