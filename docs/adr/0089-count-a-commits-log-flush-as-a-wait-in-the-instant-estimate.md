---
status: proposed
date: 2026-11-19
decision-makers: Majid Abarghooei (owner)
---

# 0089. Count a commit's log flush as a wait in the instant estimate, not as CPU

## Context and Problem Statement

The instant estimate (`InstantEstimator`) is what the app, the command line and the MCP server
show of a design before a run, and what every report records beside one: each part's share of
its slots and of its CPU cores busy at the peak, and the load at which the design saturates. For
a database that flushes its commits it took a write's time as the query plus the log flush, and
applied the CPU share to the sum (`PartShapes.database`). Nothing else in the engine counts a
flush that way:

- A run does the CPU share of a write's query on the instance's cores (`DatabaseShards.serve`),
  then waits for the `LogFlusher` with its connection and its row lock held and no core at work
  (`Operation.commit`).
- The sizing and the design review's CPU check take the queries alone (`PartWork.database`).
- The setting itself is "the share of a query's time that needs a CPU core"
  (`DatabaseConfig.cpuShare`), and a calibration measures PostgreSQL's CPU per query against the
  query times alone (`PartConfigs`: "its log flush needs no CPU").

[ADR-0083](0083-name-each-utilisation-and-compare-like-with-like.md) noted the term on Black
Friday's Orders DB (56% busy at the peak, where the sizing's busy cores are 52%) and left it.
The baselines of [ADR-0087](0087-compare-the-simulator-with-simple-estimates.md) then showed
what it costs on the accuracy benchmarks: the term set the estimate's knee on three of the nine
experiments and was the overload it reported on a fourth, where the same inputs put PostgreSQL's
cores at four times that load.

How should the estimate count a commit's flush, does the engine's version move, and how do the
frozen engine's recorded estimates, which counted it the same way, stay checked?

## Decision Drivers

- The estimate and the run model one part one way: where they differ, the difference should be
  what the formulas leave out, not a term one of them counts twice.
- A wait holds what it holds, a connection and the request's time, and nothing else.
- A figure a report records changes only with the engine's version.
- The frozen Python engine's recorded results stay readable through structured departures.

## Considered Options

- (a) A commit's flush is a wait: it stays in the time a write holds its connection and in the
  latency, leaves the database's CPU, and is not stretched on slowed threads; engine 1.5.1
- (b) Leave the estimate, and explain the term where the estimate is shown
- (c) Take the flush out of the estimate altogether
- (d) Model the commit as the run does, with group commit

## Decision Outcome

Chosen option: (a).

- **The CPU.** A database's CPU in the estimate is its primary's cores busy with the CPU share of
  its queries: `((reads ÷ instances of a shard) × read time + writes × write time) × cpuShare ÷
  1000 ÷ shards ÷ cores`. `PartShape` keeps a write's work and the flush it then waits for apart
  (`write` and `flush`), and only the work counts there.
- **The wait.** A write's own time is still its query and one flush. That is how long it holds a
  connection (`meanHoldMs`, the slots' share) and what it adds to a request's latency, as before.
- **Slowed threads.** On a host machine or on hyperthreads whose threads slow each other, the
  CPU share of a query takes longer and the flush does not, as in a run, where only the work on
  the cores runs at the machine's speed. The estimate had stretched the whole write.
- **Engine 1.5.1, report 1.20 unchanged.** Every report records the estimate, so a report
  changes for the same design and seed where a database with a CPU size flushes its commits and
  takes writes. Nothing a run simulates changes, and its result digest, which covers the run's
  figures and not the estimate, is the same.
- **Other parts were checked and do not make the mistake.** A service's CPU is the CPU share of
  its service time, as `Serving` runs it. A cache's whole command runs on its shard's cores
  (`CacheCommands`), and its shape adds no wait to it. A queue has no CPU in the estimate; its
  linger and replication count only in what a producer waits for.
- **The held-out experiments stay held out for the simulator.** The change was made with every
  experiment's results in view, the five held-out ones included: the baselines that exposed the
  term set the estimate beside measured knees. It changes nothing the simulator predicts
  (`:archsim-benchmarks:parity` still re-simulates all 65 kept calibrations with the frozen
  engine's knees and steps), so `benchmarks/engines.json` keeps the Java engine's `tunedOn`.
  The estimate's own figures on the validation page were never predictions (ADR-0087). The
  second set, registered the same day in commit `61ceaa32`
  ([ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md)), has no
  results yet; this change comes after its registering commit and touches nothing that will
  predict it.

### Consequences

- Good, because the estimate, the sizing, the review and the run now count a database's CPU
  alike. Black Friday's Orders DB reads 52% busy at its 480 req/s peak, the sizing's 2.06 busy
  cores of 4, where the estimate read 56%; the starter's PostgreSQL 27%, where it read 28%.
- Good, because on the accuracy benchmarks the estimate no longer saturates PostgreSQL where the
  calibrated inputs do not (each experiment's latest run, `baselines.json`):
  - go-mixed: its knee moves from 8,650 req/s, PostgreSQL's two cores filling at 8,676, to 9,900,
    the service's core filling at 9,931, beside the simulator's 9,916 and the arithmetic's 9,930;
  - java-mixed: from 7,850 to 7,900. PostgreSQL's 7,897 had sat 6 req/s under the service's
    7,902 by coincidence, and the service's core now sets it;
  - Kafka: the knee stays 2,050 req/s, and the overload the estimate reported there, PostgreSQL
    at 1,612 req/s where the same inputs put its cores at 6,438, is gone. What it reports now is
    the consumers falling behind at 1,959 req/s, the arithmetic's own figure for them, work no
    request waits for;
  - python-mixed (1,775), chain (4,400), cache-ttl (5,150), pg-mixed (5,100) and cache-aside
    (5,600) do not move: the service's or the gateway's core fills first either way.
- Bad, because the estimate still leaves out what it left out, and pg-hot-row now shows it
  plainly. The hot row's lock binds there at 858 req/s, and the estimate has no lock waits. Its
  knee was 1,712 req/s (+81% of the measured 947): PostgreSQL's cores filling by the flush term,
  at exactly twice the lock's rate. It is now 3,000 (+217%), the service's core filling at 3,069.
  The earlier figure was nearer the measurement for a reason that was not true of the stack, as
  go-mixed's +45% was, now +66% beside the simulator's +67%. The estimate's median absolute knee
  error is unchanged: 30% held out, 16% tuned on.
- Bad, because the estimate's commit is still one flush, where a run's group commit makes a
  write wait for the flush in progress and its own, about 1.5 under load (option d).
- Bad, because every recorded estimate of a database with a CPU size that flushes its commits
  and takes writes departs from the frozen engine. In 15 of the 25 templates that is 91 recorded
  estimates, the 91 analyses that hold them and 30 recorded runs; beside them, 17 analysis cases,
  4 of the estimate's own cases, 3 recorded simulations, 3 calibration replays and the command
  line's 30 recorded `analyze` answers. They are read through a structured departure
  (`CommitFlushDepartures`).
- Bad, because an analysis's confidence weighs each part by its estimated utilisation, so a
  database weighs a little less among its drivers, and the share of the load that rests on
  grounded inputs can move by a point.
- Neutral: the baselines of the 67 kept runs were computed by engine 1.5.0. `baselines` writes
  again the 17 files whose figures moved (3 of go-mixed's, 2 of java-mixed's, 6 of pg-hot-row's
  and 6 of Kafka's), under engine 1.5.1, and leaves the other 50 under the stamp of what first
  found them, as [ADR-0087](0087-compare-the-simulator-with-simple-estimates.md) decided.

### Confirmation

- `PartShapesTests` covers a database that flushes its commits: its CPU is its queries' alone, a
  write still waits for the flush, a read and a database that does not flush are as they were,
  and slower threads stretch a write's query and not its flush.
- `InstantEstimatorTests` covers the same through the estimate: such a database saturates where
  its queries fill its cores (2 cores × 1000 ÷ 2 ms of CPU a write), reports that share of its
  cores busy, and holds a connection through the flush.
- `CommitFlushCpuTests` checks every database of every design recorded with the frozen engine's
  estimate of it: the golden files' estimates and runs, the reports their calibrations replayed,
  the recorded simulations, and the estimate's and the analysis's own cases. The frozen engine's
  CPU share over the Java's must be the database's queries and flushes over its queries, to the
  rounding of the two shares, which holds at any speed of its threads.
- `CommitFlushDepartures` reads a recorded estimate as the frozen engine's only after checking
  that the database's utilization and load follow from its own slot, CPU and rate-limit shares,
  and that the estimate's busiest part and saturation point follow from its parts, each as the
  frozen engine's do from its own. Its rule on the database's CPU is one-sided, a lower share,
  since an estimate does not say how many of a database's requests are writes; the test above
  holds the share to the amount. It reads the summary as the frozen engine's only when it is the
  frozen engine's with the busiest part, the overloaded part and the saturation point replaced
  by the Java's own, and an analysis's confidence only when it is what the Java's
  `AnalysisConfidence` makes of the Java's estimate, and the frozen engine's is what it makes of
  the frozen engine's. Whatever else differs is left to show.
- `slowed-flush.json` lists the 21 figures that are the Java's own in the two recorded cases on
  a host machine, where the flush is no longer stretched (the database's own time, what waits on
  it, the latency, and the p95 one summary gives); `InstantEstimatorTests` holds the Java to
  them and every other figure of the two cases to the frozen engine's.
- `CommitFlushAgreementTests` simulates a PostgreSQL that takes 50 writes a second, each a 4 ms
  query on a core and a 6 ms flush: the run keeps its cores as busy as the estimate says, 10%
  within a point (the flush as CPU would say 25%), and holds a connection about as long.
- `EstimateAgreementTests` still keeps the estimate within 15% of the simulated mean and p95 on
  the starter and the queueing lab, and its busiest part within 5 points.
- `CommittedBaselinesTests` writes every kept run's `baselines.json` again, byte for byte.

## Pros and Cons of the Options

### (b) Leave the estimate, and explain the term

- Good, because no recorded figure departs.
- Bad, because the estimate would go on contradicting the run, the sizing and the calibration on
  the same design, and its saturation point would stay wrong wherever a database writes.

### (c) Take the flush out of the estimate altogether

- Good, because it is the smallest change to the code.
- Bad, because a write does wait for the flush with its connection held, and its client waits
  with it: the slots' share and the latency would be understated, and a run's would not.

### (d) Model the commit as the run does, with group commit

- Good, because a write's wait under load would be nearer the run's.
- Bad, because the 1.5 flushes hold only once commits queue behind a flush; at a low write rate a
  commit waits for its own alone. It would move the latency of every design with a database
  that writes, on evidence this decision does not have. It can follow as its own decision.

## More Information

[ADR-0083](0083-name-each-utilisation-and-compare-like-with-like.md) first recorded the term.
[ADR-0087](0087-compare-the-simulator-with-simple-estimates.md) measured it, and carries a dated
note of the estimate after the change; [docs/validation.md](../validation.md), "Against simple
estimates", describes the estimate after it. [docs/simulation-model.md](../simulation-model.md),
"Instant estimate", states the rule. Two related findings are left as they are: a sharded cache's
CPU in the estimate is its whole load over one shard's cores, where a run gives each shard its
own (ADR-0083 lists the run's side of it), and a read-through cache's write-back after a miss is
a command on its cores that the estimate's CPU does not count.
