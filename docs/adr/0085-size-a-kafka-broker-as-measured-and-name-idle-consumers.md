---
status: accepted
date: 2026-11-17
decision-makers: Majid Abarghooei (owner)
---

# 0085. Size a Kafka broker as measured, and name idle consumers

## Context and Problem Statement

The site critic's review of the live demo (16 Nov 2026) found two more things in the Kafka order
pipeline that contradicted the run or the design:

- **Idle consumers.** Its scenario "32 consumers (still 16 partitions)" gave results identical
  to the baseline and never said why. The engine lets no more of a queue's consumers work than it
  has partitions, a partitioned log's or a shared queue's alike (`effectiveConsumers`), and the
  design review already warns of it before a run ("Idle consumers", "Add partitions or reduce
  consumers"), but the run's results said nothing.
- **Broker sizing.** The broker's sizing said "needs 0.01 vCPU · 11 MiB" at an illustrative
  10,000 messages a second per vCPU, while ArchSim's own Kafka experiment measured the broker at
  0.40 cores busy at 522 messages a second and a working set near 1 GiB. Three of them were
  priced as kafka.m7g.large.

How should the run explain idle consumers, and what should a Kafka broker's size come from?

## Decision Drivers

- A result explains what the design does, not only what the run measured.
- Sizes come from measurements where ArchSim has them, and say so.
- A broker's size agrees with what the benchmark measured of the same technology.

## Considered Options

- (a) Report a queue's idle consumers and explain them in its finding; size a Kafka broker from
  the Kafka experiment's measurements, labelled measured on the assumptions page
- (b) Leave idle consumers to the design review's warning, and raise the illustrative
  throughput per vCPU
- (c) Leave the sizing and add a note that it is illustrative

## Decision Outcome

Chosen option: (a).

- **Idle consumers.** A queue with more consumers than partitions reports the rest as
  `idleConsumers` (report 1.20). Its lag finding says "16 of the 32 consumers sat idle: only one
  consumer per partition is active, so 16 partitions keep at most 16 of them busy", in the design
  review's words, which the two share (`QueueText`), and its evidence names them. It never
  advises more consumers, which would sit idle too. A queue that kept up is advised as the review
  advises, "Add partitions or reduce consumers."; one that fell behind, "Add partitions so the
  idle consumers get work", then the rest of its catch-up advice as before ("make consumers
  faster, or fix the failing downstream").
- **A Kafka broker's size.** A Kafka broker's busy cores are a least-squares fit to what the
  experiment measured over the steps the broker kept up with, those that ended with no backlog
  (522, 1,044 and 1,566 messages a second; `benchmarks/results/kafka/measured.json`). The fit
  gives 0.39 fixed cores, its intercept (nothing was measured at rest), and 0.053 ms of CPU a
  message, its slope. Its memory is the largest working set over those steps, 973 MiB. Its CPU
  is sized to the 60% target as before, and the memory spec keeps its floor of 2 GiB per vCPU
  for page cache. Other brokers keep their illustrative throughput per vCPU, for ArchSim has not
  measured them. `KafkaMeasurementsTests` recomputes the fit from the results.
- **The assumptions page** lists the three figures with a new basis, "measured", linked to the
  experiment's results.
- **Engine 1.5.0, report 1.20**, because a Kafka broker's size and a queue's report change for
  the same design and seed.

### Consequences

- Good, because the Kafka order pipeline's broker needs 0.65 vCPU (0.39 cores busy at the peak)
  and 973 MiB, which agrees with the experiment, where it said 0.01 vCPU and 11 MiB. It stays on
  kafka.m7g.large, so its cost does not change.
- Good, because the scenario with 32 consumers says why it changes nothing, the run and the
  review give one reason, and neither advises consumers that would sit idle.
- Bad, because the measurement's scope is narrow, and the figures carry it:
  - one combined KRaft broker and controller, with replication factor 1;
  - messages of about 50 bytes, sent one a request without batching;
  - one physical core of one machine;
  - three steps, the faster ones having left the consumer behind, which fit the line loosely
    (R² 0.42: the broker was busier at 1,044 messages a second than at 1,566), so the cost of a
    message is a rough figure.
  A broker with replicas fetching, larger or batched messages or faster cores is sized off. The
  per-message cost also applies to each broker's share of the messages, while a log keeps three
  copies of each.
- Bad, because every recorded size of a Kafka broker, and every queue with idle consumers,
  departs from the frozen engine, which the parity tests read through structured departures
  (`KafkaSizingDepartures`, `IdleConsumerDepartures`).

### Confirmation

- `IdleConsumersTests` covers idle consumers reported, explained and advised on, for a queue that
  kept up and one behind, a Kafka topic and a shared queue, and none without them.
- `BrokerSizerTests` covers a Kafka broker's CPU, memory and words, on a VM of its own too, and
  another broker's unchanged sizing.
- `KafkaMeasurementsTests` recomputes the fit and the working set from the experiment's results.
- `PageTextTests` and `EngineConstantTests` cover the measured basis.
- `KafkaSizingDepartures` reads a recorded broker as the frozen engine's only after checking its
  busy cores at the peak and at the base load, its CPU need and its memory against the measured
  figures at the frozen engine's rates. It then puts back only those figures and their words.

## Pros and Cons of the Options

### (b) The review's warning, a higher illustrative throughput

- Good, because it changes less: the review already warns of idle consumers.
- Bad, because a run's result would still not explain itself, and a guessed number would still
  stand where a measured one exists.

### (c) A note only

- Good, because nothing changes.
- Bad, because the size would still contradict ArchSim's own measurement.

## More Information

[ADR-0084](0084-judge-asynchronous-designs-by-their-messages-too.md) judged the same template's
messages; [docs/validation.md](../validation.md) describes the Kafka experiment.
