---
status: proposed
date: 2026-11-20
decision-makers: Majid Abarghooei (owner)
---

# 0091. Count a vCPU as a hyperthread unless the design or its instance family says otherwise

## Context and Problem Statement

A service, database or cache counts each of its vCPUs as a whole physical core unless it sets
`siblingSpeed` (schema 1.7): with it, and 2 or more vCPU, the vCPUs are hyperthreads, two per
core, and a vCPU whose core's other vCPU is busy too runs at that speed.

- **What a vCPU is.** On most x86 cloud instances a vCPU is one hardware thread: AWS (on an
  instance with simultaneous multithreading each thread is a vCPU), Google Cloud (two vCPUs
  share a core by default) and Azure (most families are hyper-threaded) say so in their
  documentation. A vCPU is a
  whole core on Arm families (Graviton, Cobalt, Ampere, Tau T2A), on AWS's T2 and its AMD
  families from the 7th generation (M7a, C7a, R7a, M8a), and on a few others.
- **What the second thread adds.** Published measurements of request-serving work put a busy
  pair at 1.1 to 1.4 times one thread alone: Intel's own 16% to 30% (2002), VMware's
  scheduler guidance of 55% to 65% of a thread each, PostgreSQL's pgbench at +16%, an AWS
  compile sweep on c6i at 63% each. As a sibling speed that is 0.55 to 0.70, with outliers
  from 0.45 (SPECjbb2005) to 0.86. The sources are listed under More Information.
- **What it costs today.** A part bound by its CPU, with 2 or more vCPU on such an instance, is
  predicted up to 1.67 times the capacity it has. A review of the accuracy evidence
  (18 Nov 2026) ranked this first among the model's suspected errors; four of the five
  held-out predictions were on the unsafe side.
- **Auto-sizing ignores it.** `SizingLoads` slows a part's work on the design's host only. A
  part that sets `siblingSpeed` is sized as if on whole cores, and the simulation then
  overloads the size the engine chose.
- **It cannot be shown on the benchmark laptop.** Docker Desktop's VM numbers its CPUs as
  pairs, but Windows schedules them itself (the contention check of 1 Nov 2026), so no cpuset
  there is a real pair. The python-cores experiment
  ([ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md)) measures
  whether that holds.

Should a vCPU be a hyperthread by default, and if so where, at what speed, and what happens
to the designs people have saved?

## Decision Drivers

- The default describes the hardware most designs run on, and errs toward less capacity.
- A saved design gives the results it gave.
- One rule, used by the simulation, the instant estimate, the review, the report and
  auto-sizing alike.
- What is not measured is said to be not measured.

## Considered Options

- (a) Hyperthread pairs at 0.6 by default from schema 1.11, whole cores where the instance
  family, the platform or the design says so; older designs pinned to whole cores by one
  design-wide setting
- (b) Keep whole cores as the default and let the review advise setting `siblingSpeed`
- (c) A measured speed per instance family
- (d) As (a), with the pin written on every part

## Decision Outcome

Chosen option: (a).

- **The rule (schema 1.11).** A service, database or cache with 2 or more vCPU, in a design
  without `architecture.host`, runs on hyperthread pairs at this sibling speed, the first
  that applies:
  1. the part's `siblingSpeed` (1 means whole cores);
  2. the design's `architecture.siblingSpeed`, new in 1.11;
  3. 1 when the instance type the cost prices for the part is of a whole-core family, when
     the service's platform is serverless, or when the service is a pod on Kubernetes;
  4. 0.6.
- **Whole-core families** are data, by provider, each with the page that says so
  (`whole-core-families.json`): for AWS the Graviton families, T2, and the AMD families whose
  page states one core per vCPU. A test holds the list against the shipped price snapshot:
  every family that sells a 1-vCPU type is on it.
- **Older designs keep their results.** The upgrade to 1.11 writes
  `architecture.siblingSpeed: 1` once. Clearing it puts the design on the default. Nothing is
  written on the parts, so a saved design's parts, and the digest of its results, stay as
  they were. The benchmark harness's designs are written at schema 1.0 and 1.7 and stay on
  whole cores, which is also what is known of the laptop.
- **One resolver** (`SiblingSpeeds`), run once when a run's document is resolved: it writes
  each speed it inferred into the resolved part, as auto-sizing records the instance type it
  chose, so that a run's input says what ran and running it again gives the same.
- **Auto-sizing sizes for what the vCPUs can do.** The target's purpose is headroom: the
  peak's work is 60% of what the part can do. With all of them busy, N vCPUs in pairs do
  N times `siblingSpeed` cores' work, the capacity the run reports, so a part on pairs needs
  its whole-core size divided by its sibling speed: at 0.6, two thirds more vCPU, which then
  read about 40% to 60% busy at the peak. A part with one handler, or one that 1 vCPU serves,
  is sized as on whole cores. Where the price snapshot offers both kinds, the cheapest type
  that covers its own family's need is chosen. That also mends sizing for a part that sets
  `siblingSpeed` itself. Two other targets were tried and measured first. "60% of the vCPUs
  busy" ran a part at the edge of a size at 68% (32 vCPU) to 86% (2 vCPU) of its vCPUs busy,
  because work that starts while more threads are busy runs slower, and overloaded it below a
  sibling speed of 0.5. Counting a pair as one core below 0.5 (its most, with one thread busy)
  still overloaded it at 0.3, since under load both threads are busy and do less.
- **The estimate's headroom is the pairs' own.** The instant estimate counts, in a part's
  load, the larger of its CPU share at the peak and the share its work would take with every
  vCPU at the sibling speed, so it saturates where its vCPUs fill (a 4 vCPU service at 0.6
  doing 10 ms a request: at 240 req/s, where it said 400 at a load of 200). Its utilisation
  stays the work's share of the vCPUs at full speed; a run, where siblings already slow each
  other below the ceiling, measures more (45% against 37% for that template's API of 4 vCPU).
  Past the ceiling a part's own
  threads run at the sibling speed and its work waits: the estimate no longer counts them
  taking turns at the part's own work, which with no fixed point ran the same service at
  300 req/s at a hundredth of its speed ("needs 8094% of its capacity; saturates at about
  4 req/s"). The design review's CPU check judges such a part by the same ceiling. A design on
  a host machine is left as it was: there the turns are other parts' threads.
- **The report names the assumption** on every part the default applied to, with the speed
  and why.
- **Shipped examples move to 1.11** without the pin, since they are what a new design starts
  from. 24 of the 25 keep every figure. The SQL Server SaaS template's database was tuned on
  8 whole cores, and on 4 cores with their hyperthreads its month-end stories broke (with RCSI,
  93.9% of requests succeeded where all did), so it gets the same 8 cores as 16 vCPU, a
  c6i.4xlarge at $248 a month more, and its stories keep their point.
- **Not in this decision:** pods share their node's cores with other pods, so a pod's own
  pairs are the wrong shape for them. They count whole cores until the node is the machine,
  which is a decision of its own; the model notes say this is optimistic on x86 node pools.

### Consequences

- Good, because a new design on x86 instances no longer counts one core as two.
- Good, because the default errs on the safe side: 0.6 is the middle of the measured range
  and below most of the modern figures.
- Good, because auto-sizing and the simulation agree on a part's capacity again.
- Bad, because no measurement of ours stands behind 0.6. It rests on vendors' documentation
  and others' benchmarks, most of them on hardware of 2002 to 2019, and the spread by workload
  (0.45 to 0.86) is wider than any default can cover. The setting stays there to be typed.
- Bad, because the benchmark cannot show it. The published accuracy figures do not move by
  this decision, and its proof needs a host with real hyperthread pairs.
- Bad, because pods, the most common way a service runs, stay on whole cores for now.
- Bad, because a saved design stays optimistic until someone clears its setting.
- Bad, because an auto-sized part on x86 at generic prices takes two thirds more vCPU than it
  did, and costs as much more. That is what the same headroom takes on hyperthreads; a
  Graviton type, where a vCPU is a core, is cheaper still beside it.
- Neutral, because on the shipped AWS snapshot the cheapest type that covers a part is a
  Graviton one, so priced, auto-sized designs change little. The default bites on generic
  pricing, on families limited to x86, and on an x86 type named by hand.

### Confirmation

- `VcpuDefaultTests` runs a 4 vCPU service on the default and in a design saved at 1.10. The
  first has the CPU ceiling, estimate and review of a service that sets 0.6, records that speed
  in its run's input, says how its vCPUs counted and names the default in the report's model
  notes. The second keeps whole cores, writes nothing into its parts and raises no CPU finding.
- `ScenarioResolverSiblingSpeedTests` holds the rule's order (the part, the design, a whole-core
  type, the default), and that a scenario that resizes a part counts the vCPUs of the type it
  then runs on.
- `UpgradesTests` holds the pin: a design before 1.11 gets `architecture.siblingSpeed: 1` once
  and nothing on its parts, and one with a speed of its own keeps it.
- `WholeCoreFamiliesTests` holds the list against the shipped price snapshots: every listed
  family is priced, each group names its reason and an https page, and every family that sells
  a 1-vCPU type is on it.
- `HyperthreadSizingTests` sizes services on pairs at 0.6 and at 0.3, at the edge of each size,
  and simulates the size chosen: none refuses a request, none is short of CPU in its review, and
  no more of its vCPUs are busy than its work is of what they can do. `AutoSizerDefaultTests`
  holds the cheapest type by family.
- `OwnPairsReviewTests` and the estimate's own-pairs case hold the ceiling: a 4 vCPU service at
  0.6 doing 10 ms of CPU a request saturates at 240 req/s, is flagged at 83% of it at 200 req/s
  and needs 125% at 300.
- With the 25 templates at 1.11, the sizing audit, the starter references and the estimate's
  agreement with the simulation pass. The benchmark harness's designs, pinned, give every knee
  they gave (`:archsim-benchmarks:parity`).
- Not confirmed: the speed itself. The python-cores experiment
  ([ADR-0090](0090-register-a-second-held-out-set-before-the-next-model-change.md)) shows
  whether the laptop's CPU pairs behave as pairs at all; a host with real hyperthread pairs is
  what would confirm 0.6 or move it.

## Pros and Cons of the Options

### (b) Keep whole cores and advise

- Good, because nothing changes for anyone.
- Bad, because the default stays wrong for most hardware, on the unsafe side, and a finding
  nobody acts on changes no prediction.

### (c) A measured speed per instance family

- Good, because it is what the hardware does.
- Bad, because nobody publishes such a table and we have no machine to measure one on. It is
  the refinement of (a) once a testbed exists; the data file has room for a speed per family.

### (d) The pin on every part

- Good, because no new setting.
- Bad, because every older design's parts change, and with them the digest of every result,
  the recorded replays and each part's form in the editor.

## More Information

The sources, each read on 19 Nov 2026. The sibling speed is a pair's throughput over two
threads alone: (1 + the second thread's gain) / 2.

- What a vCPU is: AWS,
  [Optimize CPUs](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instance-optimize-cpu.html),
  [the general-purpose specifications](https://docs.aws.amazon.com/ec2/latest/instancetypes/gp.html),
  [M7a](https://aws.amazon.com/ec2/instance-types/m7a/),
  [M8a](https://aws.amazon.com/ec2/instance-types/m8a/) and
  [the Graviton performance runbook](https://aws.github.io/graviton/perfrunbook/system-load-and-compute-headroom.html);
  Google Cloud,
  [threads per core](https://docs.cloud.google.com/compute/docs/instances/set-threads-per-core),
  [CPU platforms](https://docs.cloud.google.com/compute/docs/cpu-platforms) and
  [SMT on GKE](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/configure-smt);
  Azure, [VM customization](https://learn.microsoft.com/en-us/azure/virtual-machines/vm-customization)
  and [Cobalt](https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/cobalt-overview);
  Kubernetes,
  [the meaning of CPU](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/#meaning-of-cpu).
- What the second thread adds, as a sibling speed:
  [Intel's Hyper-Threading paper](https://www.cl.cam.ac.uk/teaching/0607/CompArch/documents/all/beyond-risc/intel-smt.pdf)
  (2002, 0.58 to 0.65);
  [VMware's scheduler guidance](https://knowledge.broadcom.com/external/article/326271/allowing-vcpus-of-an-smp-virtual-machine.html)
  (0.55 to 0.65);
  [Dell's SPEC CPU2006 tuning](https://lafibre.info/images/materiel/201212_dell_bios_tuning_for_performance.pdf)
  (2012, 0.45 to 0.67); the Graviton runbook above (an OpenSSL compile on c6i, 0.63; a Netty
  server, at most 0.86);
  [the Linux core-scheduling measurements](https://lkml.iu.edu/hypermail/linux/kernel/1904.3/02754.html)
  (2019: memcached 0.57 and 0.68, tbench 0.65 and 0.70, SPECjbb2005 0.45 to 0.48);
  [pgbench on PostgreSQL 9.2](https://www.postgresql.org/message-id/509B241C.1080108@boreham.org)
  (0.58); [SPECweb99](https://www.cs.princeton.edu/techreports/2007/793.pdf) (2007, 0.525 to
  0.65 on Xeon); [Bulpin and Pratt](https://static.usenix.org/event/usenix05/tech/general/full_papers/short_papers/bulpin/bulpin.pdf)
  (2005, 0.60);
  [ServeTheHome](https://www.servethehome.com/amd-epyc-9006-results-are-here-and-amd-delivers-a-knockout-part-deux/9/)
  (EPYC and Xeon 6980P, 0.66 to 0.69);
  [HammerDB's sizing rule](https://www.hammerdb.com/blog/?p=11) (0.675).
- Why utilisation misleads on hyperthreads:
  [Cockcroft, CMG 2006](https://www.cmg.org/wp-content/uploads/2015/10/2-Utilization-is-virtually-useless-as-a-metric-61331.pdf),
  [Gunther 2014](https://perfdynamics.blogspot.com/2014/01/monitoring-cpu-utilization-under-hyper.html)
  and [Percona 2015](https://www.percona.com/blog/2015/01/15/hyper-threading-double-cpu-throughput/).
- Not found: on-and-off measurements for Redis, Node.js, Go, Python or a modern MySQL, and
  whether a Fargate or Lambda vCPU is a hyperthread.
