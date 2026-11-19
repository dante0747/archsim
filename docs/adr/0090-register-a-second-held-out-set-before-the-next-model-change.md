---
status: proposed
date: 2026-11-19
decision-makers: Majid Abarghooei (owner)
---

# 0090. Register a second held-out set before the next model change

## Context and Problem Statement

A held-out experiment shows the model's accuracy on a stack it was never tuned on
([ADR-0004](0004-prove-accuracy-on-sealed-held-out-experiments.md)). The five registered on
30 Oct 2026 are spent for the next model change: a review read their results, and the changes
it recommends are made with those results in view
([ADR-0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md)).
The first version that changes how capacity or latency is modelled lists all five in `tunedOn`,
and its out-of-sample accuracy can then be shown only on experiments registered before the
change.

Three things limit what a new experiment can be on the machine we have:

- **One laptop.** Four cores, eight logical processors, Docker Desktop's VM. Its contention
  check of 1 Nov 2026 found that the VM numbers its CPUs as hyperthread pairs while Windows
  schedules them itself: a probe and its numbered sibling did 1.57 to 1.70 times one CPU's
  work, where a real pair does about 1.2. A cpuset may therefore not decide who shares a
  core, and work slows with how much of the whole machine is busy.
- **The load generator.** Under protocol v7 k6 has been checked up to 4,400 req/s
  ([ADR-0086](0086-keep-the-load-open-loop-past-the-objective-and-close-in-on-the-measured-knee.md)).
  go-mixed's and java-mixed's knees lie at or above that.
- **The changes to be proved.** The next ones are about how CPUs that share a machine turn
  into capacity, and a cost per request that moves with load. The simulator's knees on the
  nine experiments are no nearer the measured ones than bottleneck arithmetic's
  ([ADR-0087](0087-compare-the-simulator-with-simple-estimates.md)); only pg-hot-row, where a
  row lock binds, tells estimates that know the design from those that do not.

Which experiments are registered now, so that the next model change has something to be
proved on?

## Decision Drivers

- Each experiment tests one claim, and what result would count against the model is written
  down before it is measured.
- Its knee lies where the load generator has been checked.
- It is no near-copy of a spent experiment, unless the one difference is what it tests.
- It runs on the services that exist: an experiment that needs a new part of the model would
  be a model change itself.

## Considered Options

- (a) Five experiments on the four existing services, each with one difference from what
  exists
- (b) Measure the spent five again under protocol v7 and count them as held out
- (c) A public benchmark (DeathStarBench, TeaStore)
- (d) Wait for a testbed with known hyperthread pairs and the load generator on its own
  machine

## Decision Outcome

Chosen option: (a), registered on 19 Nov 2026 in commit `61ceaa32` and sealed like the first
five: only `smoke` runs them until a frozen engine has predicted them.

| Experiment | Stack | What differs | What it tests |
| --- | --- | --- | --- |
| python-cores | new | python-mixed's service and workload on CPUs 0 and 2, numbered as two cores, with 1 and 3 idle (python-mixed: 2 and 3, numbered as one core's pair) | Whether the VM's pairs are real. If they are, this knee is about a quarter above python-mixed's measured in the same campaign; if Windows schedules the CPUs itself, the two are the same. |
| python-wide | new | the same on CPUs 0 to 3, still 10 workers | Capacity with twice the CPUs and more of the machine busy at the knee, where the contention check saw work slow most. |
| java-hot-row | java-mixed | every request updates the same row | A row lock behind Tomcat's threads and HikariCP's 10 connections: what the simulation adds to arithmetic, on a second runtime. |
| go-pool | new | a pool of 2 connections, half the requests writes | A knee set by the pool and the commit flush with the service's CPU far from full, so that neither the machine's CPUs nor the load generator is what binds. |
| cache-miss | cache-ttl | uniform over 1,000,000 keys with the 5 s TTL: about 1% of lookups hit at 2,500 req/s | The miss path: Redis and PostgreSQL in almost every request, which cache-aside rarely takes. |

- **Read with the campaign's own runs.** The nine existing experiments are measured again
  under protocol v7 in the campaign that measures these five. python-cores is read against
  python-mixed's knee of that campaign, not against the one of 3-4 Nov, which protocol v6
  measured.
- **Model changes come after the registering commit.** The engine that predicts the five is
  frozen once the changes are in
  ([ADR-0088](0088-freeze-the-java-engine-by-its-sources-and-fix-the-criteria-before-a-campaign.md)).
  From then on the first five are tuned-on for it and these five held out.
- **What the harness knows of them.** An experiment may now name its service's CPUs
  (`serviceCpus`) and its pool (`pool`), where the nine take their runtime's CPUs and a pool
  of 10; the hot row is known by the traffic, not by pg-hot-row's name. Their designs are
  built by the code that builds the nine's. Their calibration has never run, since they are
  sealed: a fault in the runner found at the campaign is fixed then, which the freeze allows,
  and is no model change unless it changes the inputs or the designs.
- **The load generator is checked first.** python-wide and cache-miss are the two nearest
  k6's limit: k6 has CPUs 6 and 7 alone on their stacks, cache-miss's knee is expected above
  3,000 req/s, and python-wide's ladder, which reaches one and a half times the predicted
  knee, is expected to pass 4,000. Before the campaign, k6 is run at those rates against a
  spent stack that gives it the same CPUs (chain), and its CPU and dropped requests are read
  there. What that shows decides, before the campaign's first calibration, whether the two
  run as registered or with other CPUs for k6, and the page says which before they are
  measured.

### Consequences

- Good, because the next model change can be proved out of sample, on five results nobody has
  seen.
- Good, because three of the five (java-hot-row, go-pool, cache-miss) have a knee that the
  service's CPUs are not expected to set, so the laptop's CPU scheduling cannot explain a miss
  there.
- Good, because python-cores settles by measurement which of two explanations of the
  multi-CPU stacks' misses holds on this machine: hyperthread pairs, or the whole machine's
  busy share.
- Bad, because it is the same laptop. What the machine does to a measurement, it does to
  these five too.
- Bad, because the five add no new part of the model: one replica, steady load, PostgreSQL
  and Redis. Autoscaling, load balancers, retries and Kafka's consumers stay without measured
  evidence.
- Bad, because python-cores differs from python-mixed in more than the service's CPUs: k6 has
  CPUs 6 and 7 there, and 0, 1, 6 and 7 on python-mixed's stack. Each repeat records k6's own
  CPU, which says whether that mattered.
- Bad, because two of the five differ from python-mixed only in their CPUs. If the machine
  drowns that difference, they tell little.
- Bad, because go-pool's pool is not reported by the running service: `compose.yaml` sets it,
  a test holds it against the design, and one count of PostgreSQL's connections during a
  smoke run stands behind it.
- Bad, because five results are spent by one round: the model change after the next needs
  another set.
- Neutral, because nothing is calibrated or measured by this decision.

### Confirmation

- `ExperimentsTests` covers the registry: the four tuned-on experiments, then the two held-out
  sets, each with its registration; `ExperimentTests` the CPUs and the pool an experiment
  names, and the traffic of a hot row.
- `SealsTests` covers each of the five on an engine that is not frozen (sealed, with the
  `smoke` command to run instead), on a frozen one, and on the committed `engines.json`.
- `SecondSetDesignsTests` covers each one's design: its service's CPUs and workers,
  PostgreSQL's connections, its keys, skew and mix, its links, and cache-miss's TTL.
  `ComposePoolTests` holds each stack's `PG_POOL` in `compose.yaml` against its design,
  `CpusetsTests` and `StacksTests` its containers' and k6's CPUs, and `CpuTopologyTests` that,
  as the VM numbers them, no container shares a core with k6 or with another.
- `StaircaseTests` covers the calibration's bounds from an experiment's own pool and from a
  hot row known by its traffic.
- `RegisteredBlockTests`, `ValidationPageTests` and `ReportParityTests` cover the page's list
  of registered experiments without a kept run, and that an experiment with one needs a
  section of its own.
- On the registering commit `./gradlew :archsim-benchmarks:parity` reproduced the kept
  comparisons and development checks of the nine (65 of 65 and 52 of 52), and
  `smoke python-cores`, `python-wide`, `go-pool`, `java-hot-row` and `cache-miss` passed on
  19 Nov 2026: 2 connections held to PostgreSQL on go-pool, row 1 alone updated on
  java-hot-row, 201 misses of 201 lookups on cache-miss.

## Pros and Cons of the Options

### (b) Measure the spent five again under protocol v7

- Good, because nothing is built.
- Bad, because their results are known: a model changed with them in view and then measured
  on them shows in-sample accuracy, whatever the protocol.

### (c) A public benchmark

- Good, because others' published figures could be set beside ours.
- Bad, because DeathStarBench's services fan out in parallel and run many replicas, which the
  harness does not calibrate yet, and its load is above what k6 has been checked for here. It
  is a programme of its own and needs the testbed of (d).

### (d) Wait for a testbed

- Good, because known hyperthread pairs and a generator on its own machine remove what the
  laptop adds.
- Bad, because the machine is billed and the owner's to decide. Until then the model would
  either not change, or change with nothing registered to prove it on.
