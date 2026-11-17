---
status: proposed
date: 2026-11-17
decision-makers: Majid Abarghooei (owner)
---

# 0083. Name each utilisation, and compare like with like

## Context and Problem Statement

A part has more than one share busy, and the reports showed them under one name. The site
critic's review of the live demo (16 Nov 2026) found three for one database. The Black Friday
template's Results page said "Orders DB 56% CPU busy (simulated 2%)". Its components table said
"Util 2%", and its capacity was 2,404 req/s at 303 req/s, about 13%. The starter's PostgreSQL said
"Util 6%" beside a CPU ceiling of 423 req/s at 112 (26%) and "needs 0.9 vCPU" of 2.

Each figure was right about something else:

- The 56% was the estimate's: the primary's CPU, at the template's 480 req/s peak.
- The 2% and 6% were connection slots, the run's average.
- The comparison set the two side by side because it read `cpuUtilization`, the share of busy
  slot time spent on a part's own work, which a database does not have. It then fell back to
  the slots.
- The dashboard called that same share "CPU utilization".
- The 2,404 req/s ceiling spread the requests' CPU time evenly over the primary and two replicas,
  though every write lands on the primary, which fills its cores at about 930 req/s.

How should a report name a part's shares busy, set a database's CPU ceiling, and compare the
instant estimate with the run?

## Decision Drivers

- A figure says what it measures: slots, CPU cores, or a part's own work.
- An estimate and a run are compared on the same resource, the same instances and the same
  load.
- A ceiling agrees with the share busy it is read beside: the load over the ceiling is the busiest
  instance's share busy.
- The frozen Python engine's recorded results stay readable through structured departures.

## Considered Options

- (a) Measure each database instance's CPU; report the busiest's share and set the CPU ceiling
  where it fills; compare the estimate with that share at the load the run sent the part; name
  every share in the app
- (b) Rename the figures in the app only
- (c) Have the estimate report the mean over a database's instances, as the run does

## Decision Outcome

Chosen option: (a).

- **The run measures each instance.** In a database of more than one instance with a CPU size
  (a shard's primary and its replicas, over its partitions), each instance keeps its own load
  (`InstanceLoad`):
  - its busy executors (`Cores.Accounts.instanceBusy`);
  - the operations sent to it, refused ones included;
  - the operations it served and their CPU work at full speed;
  - a replica's apply work.

  The database reports its busiest instance's share of its cores busy
  (`busiestInstanceCpuUtilization`, report 1.18) beside the mean over them all
  (`cpuCoreUtilization`). That instance is a primary taking every write, or a hot shard's.
- **Its CPU ceiling is where that instance fills.** Such a database's CPU ceiling is
  `cores × 1000 ÷` the CPU work per request of the instance sent the most. That work is
  counted at full speed, with the operations it refused costing what the ones it served did.
  The result passes through the machine's capacity, as a single instance's does, so it holds
  past saturation and on hyperthreads or a shared host. The ceiling had spread the requests'
  CPU time evenly over the instances that serve them. The database's own formulas explain it,
  and the bottleneck finding names the instance ("Its busiest instance's CPU cores, 100% busy,
  can do about 2,415 req/s; it received 2,794 req/s"). A database of one instance keeps its
  ceiling exactly as it was.
- **The estimate is compared like with like.** The Results page and the Markdown report compare
  the estimate's busiest part on the resource it limits by:
  - **CPU** against the run's CPU cores busy (the busiest instance's, for a database of several),
    no longer against the slots or the share of own work;
  - **slots** against the slots busy;
  - **load:** at the load the run sent that part, whenever it differs from the estimate's peak by
    more than 5%. A CPU share grows with load alone, so it is scaled from the peak to the run's
    arrival rate, with the peak's figure beside it. A slot's hold time grows with the waits
    downstream too, so slots are given each at its own load. The p95 is said to be the peak's
    against the run's.

  Black Friday now reads "Orders DB's busiest instance 35% CPU busy at the 303 req/s the run sent
  it (simulated 33%; 56% at the 480 req/s peak)".
- **The app names each share.** The components table's columns are "Slots busy avg / peak", "CPU
  busy" (with "busiest" for a database of several instances), "Own work" and "Ceiling". The
  dashboard's "CPU utilization" panel, which plots the share of own work, is "Own processing".
- **Engine 1.3.0, report 1.18**, because a database's CPU ceiling changes for the same design
  and seed. Nothing a run simulates changes; the instances' gauges only watch.

### Consequences

- Good, because the three figures for Orders DB agree: its busiest instance is 33% busy at
  303 req/s, its ceiling is 927 req/s (303 ÷ 32.6%), and the estimate gives 35% at that load.
- Good, because a hot shard's or a write-heavy primary's ceiling is no longer overstated: MongoDB's
  hot-shard template reads 2,415 req/s, where its hot shard's cores fill, not every shard's share.
- Bad, because the engine's estimate summary still reads "busiest Orders DB 56%" without naming
  the resource or the peak, as the frozen engine wrote it; the app's comparison line names both.
  The estimate also counts a database's commit flush as CPU time, which the run and the sizing do
  not, so it reads 56% at the peak where the sizing's 2.06 busy cores of 4 are 52%. Both are left
  for a later change.
- Bad, because a database of several instances departs from the frozen engine in its ceilings, so
  the parity tests read it through a structured departure (`BusiestInstanceDepartures`).
- Bad, because other places still read the mean over the instances or an even spread:
  - `Classification.ofServer` and `RootCause.title` judge a part hot by the mean
    `cpuCoreUtilization`, so a primary at 90% beside idle replicas is not called hot;
  - `StoreFindings`' hot-shard load estimates the hot shard from an even split;
  - a sharded cache counts its CPU over all its shards (`Accounts.ofPart`);
  - the estimate's primary (`PartShapes.database`) ignores `readFrom` and key skew.

  Each is a candidate for the same rule.
- Neutral: with uniform keys, chance makes one instance a little busier than the rest, the more
  so at a low rate. Eight shards that should fill at 4,000 req/s read about 3,500 at 20 req/s,
  as the measured busiest instance is.

### Confirmation

`BusiestInstanceTests` covers:
- a replicated database whose primary takes 50 writes/s: 13.3% of its cores busy, a ceiling of
  750 req/s, explained as 2 × 1000 ÷ about 2.67 ms of its busiest instance's work per request;
- a single instance's ceiling as before;
- a primary past its ceiling, whose ceiling stays at 400 req/s and whose finding names it;
- an idle replica on hyperthreads, which leaves the ceiling as it was;
- a hot shard, below an even spread.

`CoresTests` checks the instance's own gauge. `confidence.test.ts` checks that CPU is compared
with the cores busy, a database with its busiest instance, CPU at the load the run sent the part
and slots each at its own load. `utilization.test.ts` covers the "CPU busy" cell.
`BusiestInstanceDepartures` reads each recorded database as the frozen engine's only after
checking that its share lies between the mean and all its instances on one, that its ceiling is
no higher than the even spread but for chance, and that the frozen engine's capacity and limit
follow from the Java's slots and that CPU ceiling.

## Pros and Cons of the Options

### (b) Rename the figures in the app only

- Good, because nothing in the engine changes.
- Bad, because the ceiling still contradicts the share busy, and nothing the run measures can be
  set against the estimate's primary.

### (c) Have the estimate report the mean over a database's instances

- Good, because the run already reports that mean.
- Bad, because the busiest instance is the one that limits the design: a mean over a busy primary
  and idle replicas hides the bottleneck the estimate is there to find.

## More Information

[ADR-0082](0082-count-a-caches-refused-lookups-in-its-hit-ratio.md) fixed the review's second
finding; [docs/simulation-model.md](../simulation-model.md) describes the capacity figures.
