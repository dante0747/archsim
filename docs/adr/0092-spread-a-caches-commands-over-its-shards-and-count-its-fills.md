---
status: accepted
date: 2026-11-19
decision-makers: Majid Abarghooei (owner)
---

# 0092. Spread a cache's commands over its shards, and count its fills, in the instant estimate

## Context and Problem Statement

The instant estimate (`InstantEstimator`) gives each part's share of its slots and of its CPU
cores busy at the peak, and the load at which the design saturates; the app shows it before a
run and every report records it. [ADR-0089](0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md)
found two places where its cache does not count what a run does, and left them:

- **A sharded cache's CPU** was its whole load over one shard's cores (`PartShapes.cache`:
  requests × command time ÷ 1000 ÷ `cpuCores`). A run gives every shard its own executors and
  cores (`CacheShards`; `cpuCores` is "vCPU per shard"), reports the cache's CPU over all its
  shards (`CacheReport`, as [ADR-0083](0083-name-each-utilisation-and-compare-like-with-like.md)
  records) and sets its CPU ceiling at every shard's usable cores; the sizing and the design
  review divide its busy threads by its shards (`PartWork.cache`). The estimate's own slots
  already spread over the shards (`Capacity`), as do a database's queries.
- **A read-through cache's fill.** After a miss read through its miss link, a cache that
  populates on a miss runs one more command to fill the key (`CacheRuntime.readThrough`): it
  waits for an executor on the shard, holds it, and runs on the shard's cores
  (`CacheCommands`). The estimate counted that command in a read's time (`PartEstimates`: one
  more wait and command per miss), but not in the cache's slots, its CPU or the queue its
  commands wait in. A cache-aside caller's write-back is already a request to the cache in the
  load estimate.

Should the estimate count a cache's commands as a run does, what should it say when a hot key
loads one shard, and how do the frozen engine's recorded estimates, which counted them the same
way, stay checked?

## Decision Drivers

- The estimate and the run model one part one way: where they differ, the difference should be
  what the formulas leave out, not a term one of them counts differently.
- Within the estimate, a part's slots and CPU follow one rule for where its keys go, and a
  cache's follows a database's.
- A figure a report records changes only with the engine's version.
- The frozen Python engine's recorded results stay readable through structured departures.

## Considered Options

- (a) A cache's commands are its requests and its fills, spread evenly over its shards'
  executors and cores, and queued together; engine 1.5.2
- (b) Count the busiest shard, from the workload's key skew
- (c) Leave the CPU as it is, as a guard against a hot key
- (d) Count the fills in the CPU alone

## Decision Outcome

Chosen option: (a).

- **Commands.** A read-through cache that populates on a miss runs a fill a second for each
  read a second it sends on its miss link (`PartShape.fills`). A fill takes a slot for a
  read's command time and the shard's cores. `Capacity` counts the cache's commands, requests
  and fills, in its slots' share and in the M/M/c queue of each shard, so the reads wait behind
  the fills; a read still adds its fill's wait and command per miss to its time, at that longer
  wait. `meanHoldMs` is a command's, the command time, as before.
- **CPU.** `commands × command time ÷ 1000 ÷ cpuCores ÷ shards`: the share of every shard's
  cores busy for keys spread evenly, what a run's `cpuCoreUtilization` measures. A design whose
  cache has one shard and no fills reads as before, to the bit.
- **A hot key.** The estimate keeps the even spread, for the slots as for the CPU and as for a
  database's shards, and leaves a hot shard to a run (option b).
- **Engine 1.5.2, report 1.20 unchanged.** Every report records the estimate, so a report
  changes for the same design and seed where a cache has shards and a CPU size, or fills what
  it misses. Nothing a run simulates changes, and its result digest, which does not cover the
  estimate, is the same.
- **Sizing is left as it is.** `PartWork.cache` (the sizing, the design review's CPU check, a
  host machine's speeds) counts a read-through cache's requests and not its fills. Counting them
  would change the cores an auto-sized cache gets, and so what a run simulates: a decision of
  its own.
- **The held-out experiments stay held out for the simulator.** The change touches nothing the
  simulator predicts, so `benchmarks/engines.json` keeps the Java engine's `tunedOn`, and it
  comes after the commit that registered the second held-out set (`61ceaa32`,
  [ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md)).

### Consequences

- Good, because the estimate agrees with the run. `CacheCommandsAgreementTests` runs a Redis of
  2 shards, each with one executor and 2 cores, taking 400 reads a second of 0.5 ms, half of
  them missed and filled: the run keeps 7.51% of its cores and 15.03% of its executors busy, the
  estimate now says 7.5% and 15%, where it said 10% and 10%. The recorded runs move the same
  way. A TTL cache of 3 shards that fills 60% of its reads measured 10.8% of its executors and
  of its cores busy; the estimate said 7.1% and 21.4%, and says 10.55% and 10.55%. The hot-key
  feed's six-shard Redis measured 5.3% of its cores busy, where the estimate said 26% and now
  says 4.3%.
- Good, because the estimate's busiest part and saturation point are no longer set by a load
  no shard carries. On the hot-key feed the cache was the busiest part at 26% of "its" CPU with
  a saturation point of 7,692 req/s; its slots set its limit now (17.3%), the Feed API is the
  busiest part (18%), and the design saturates at about 11,217 req/s.
- Good, because the benchmark designs' Redis, which fills what it misses, now counts its fills
  at the estimate's knee: cache-aside's from 19.3% to 22.8% of its core (1,002 fills a second
  at 5,600 req/s), cache-ttl's from 17.9% to 23.1% (1,503 at 5,150). Neither knee moves, since
  the service's slots fill first; `baselines` wrote again the 7 files where an estimate's p95
  at a load it tried moved by 0.01 ms (4 of cache-aside's runs and 3 of cache-ttl's).
- Bad, because a hot key's shard still fills before the estimate says, as it did before. In the
  feed's celebrity scenario (Zipf 1.5, one author about 38% of requests) the estimate read the
  cache 74% busy: its whole load on one shard's 4 cores, which a shard's single command thread
  can never keep more than a quarter busy. It now reads its slots 49% busy and the Feed API 62%,
  while the hot shard needs about 1.4 times what its one thread can do and the run's lookups
  queue behind it. The earlier figure looked nearer the trouble for a reason that was not true
  of the design.
- Bad, because the recorded estimates of such caches depart from the frozen engine: the 7
  recorded estimates of the hot-key feed and the CDN's short-TTL scenario, the 7 analyses that
  hold them, 2 recorded runs of the feed, 3 recorded simulations, 2 of the estimate's own cases
  and 2 of the command line's recorded `analyze` answers. They are read through a structured
  departure, and in the 5 recordings where a fill makes a busy cache's commands wait longer, 46
  figures (the cache's wait, what its caller holds, the latency and where its time goes) are
  listed as the Java's own.
- Neutral: an analysis's confidence weighs each part by its estimated utilisation, so a
  sharded cache weighs less among its drivers, and a read-through cache a little more.
- Neutral: the cost reads two figures from the estimate (`EstimatedUsage`): the replicas an
  autoscaled service keeps busy and how long a function's request stays. A service or a function
  that waits on a busy read-through cache waits a little longer now, so it can settle at more
  replicas or bill a longer duration. No recorded cost moved.

### Confirmation

- `PartShapesTests` covers a sharded cache, whose CPU is every shard's, a read-through cache,
  which fills each read it missed and counts the fills on its cores, one that does not fill,
  and a slowed read-through cache.
- `InstantEstimatorTests` covers a read-through cache of 2 shards through the estimate: its
  cores' share, its executors' share and its wait count the fills, and an executor is held for
  one command's time.
- `CacheCommandsTests` checks every cache of the designs `RecordedEstimates` reads with the
  frozen engine's estimate of them (the golden files' estimates and runs, the reports their
  calibrations replayed, the recorded simulations and the estimate's and the analysis's cases):
  the frozen engine's slot share over the Java's must be the cache's requests over its commands,
  and its CPU share over the Java's the shards times that, to the rounding of the shares. The
  command line's two departing `analyze` answers are of the hot-key template, at its base and its
  baseline scenario, whose golden estimates it checks.
- `EstimateDepartures` reads the Java's estimate as the frozen engine's in one pass, a database
  by ADR-0089's rule (`CommitFlushDepartures`) and a cache by this one
  (`CacheCommandDepartures`). A cache departs with its slots no less busy than the frozen
  engine's, and with its CPU moved exactly as its slots and shards say: the frozen engine's CPU
  share times the Java's slot share is the shards times the Java's CPU share times the frozen
  engine's slot share, to the rounding of the four shares, since a cache holds a slot for its
  command's time. Its utilisation and load must follow from its shares and its limit be one of
  its shares equal to its load, each as the frozen engine's follow from its own. Its busiest
  part, saturation point, summary and an analysis's confidence are read as for a database;
  whatever else differs is left to show. How far the slots move, the test above holds.
- `OwnFigures` reads a figure listed in `cache-fills.json` (the estimate's two cases, and the
  three recorded simulations) as the frozen engine's only where the Java's is the listed one, so
  the list pins them; every other figure of those recordings must be the frozen engine's.
- `CacheCommandsAgreementTests` holds the estimate's cores' and executors' shares to a run, within
  a point.
- `CommittedBaselinesTests` writes every kept run's `baselines.json` again, byte for byte.

## Pros and Cons of the Options

### (b) Count the busiest shard, from the workload's key skew

- Good, because the hot key's shard is where a sharded cache fills first, and the workload's
  Zipf skew says how much of the load its hottest key takes, as the database's hot-row formula
  uses it.
- Bad, because the even spread is the rule for every sharded part in the estimate, the slots
  included, and for what a run reports of a cache (its CPU over all its shards, which ADR-0083
  lists): changing the cache's CPU alone would compare unlike with like. The busiest shard
  belongs to one decision for caches and databases, slots and CPU, with the run reporting a
  cache's busiest shard as it does a database's busiest instance.

### (c) Leave the CPU as it is, as a guard against a hot key

- Good, because no recorded figure departs.
- Bad, because it is no model of a hot key: it is right only when every key hashes to one shard,
  overstates every evenly loaded sharded cache by its number of shards, and leaves out the slots
  that bind a single-threaded Redis first.

### (d) Count the fills in the CPU alone

- Good, because it moves fewer figures: no wait changes.
- Bad, because a fill holds the shard's executor as long as its cores, and the executors are
  what bind a single-threaded cache.

## More Information

[docs/simulation-model.md](../simulation-model.md), "Instant estimate", states the rule. The
recorded runs above are `aTtlCacheWithShardsEvictingKeys` (`simulator/cases.json.gz`) and the
`social-feed-hot-key` template's runs (`parity/golden/templates`). ADR-0089 carries a dated note
pointing here.
