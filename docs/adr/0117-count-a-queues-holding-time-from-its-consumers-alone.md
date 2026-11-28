---
status: proposed
date: 2026-11-28
decision-makers: Majid Abarghooei (owner)
---

# 0117. Count a queue's holding time from its consumers alone

## Context and Problem Statement

A part's capacity is its slots over its mean holding time, the time a request holds a slot
(`capacityRps = slots × 1000 ÷ meanHoldMs`), and its utilization formula, its M/M/c wait and the
sentence of a finding on what it can sustain all use that holding time. A queue's slots are its
consumers', and a delivery holds one from its start to its end, its consumer's own time and the
calls the message makes. A publish holds none: its producer waits for the acknowledgement, and no
consumer is busy meanwhile. The queue's utilization already counts only busy consumer slots.

Its holding time did not: the engine, as the frozen Python engine did, also recorded each publish
from its start to its acknowledgement. On the "Async order pipeline with Kafka" template the
orders topic's holding time counted 5,048 publishes of about 10 ms with 4,546 deliveries of about
238 ms, so it was 118 ms and its finding said it "can sustain about 135 req/s", while it was 85%
busy at 57 messages a second, the instant estimate saturated it at about 67, and its formulas gave
it a 0.5% chance of waiting with a backlog of 565 messages. Where the memory alarm blocked
publishers, their wait counted too, and one recorded run's queue held 771 ms where its deliveries
held 19.

What should a queue's holding time count?

## Decision Drivers

- A queue's capacity, its formulas, its finding and the instant estimate agree with each other
  and with how busy its consumers were.
- A report keeps its fields, and the frozen engine's recorded runs still check every other figure.

## Considered Options

- Count a queue's deliveries alone in its holding time
- Keep the mix, and give the queue a second holding time, its deliveries', for its capacity
- Keep the mix, and say in the consumer throughput's note that it counts publishes

## Decision Outcome

Chosen option: "count a queue's deliveries alone in its holding time", because the holding time
is what a slot is held for, and a publish holds no consumer slot: every figure that follows from
it then describes the consumers, with no new field and no figure left that mixes the two. On the
Kafka template the topic holds a message for 238 ms, and its finding, its consumer throughput and
the estimate all give it about 67 messages a second.

The simulation itself does not change: every event, latency, rate and cost, and how busy the
queue's consumers were, are as they were. What a queue reports of its holding time changes, so
the engine moves to 1.6.2; the report keeps its fields, so its version stays 1.21, as for 1.5.1
and 1.5.2, whose changes also gave existing figures another meaning.

### Consequences

- Good, because a queue's "can sustain about" sentence, its capacity, its slot, utilization, M/M/c
  and consumer throughput formulas and the instant estimate agree.
- Good, because a backlog no longer comes with a formula that says messages hardly wait.
- Neutral, because a queue that published in the window but finished no delivery in it has no
  holding time, capacity or limit, and no slot, utilization, M/M/c or consumer throughput formula
  and no sentence on what it can sustain, where it showed its publishes' time: its consumers held
  nothing to the end that the window saw.
- Neutral, because the recorded runs of the frozen engine with a queue depart in these figures.
  They read as Python's once Python's holding time is, to the report's rounding, the mean of the
  deliveries', at the Java's holding time, and the publishes', each at the mean publish time and
  the mean time the memory alarm blocked the publishes acknowledged in each of the window's
  buckets (`QueueHoldDepartures`). The series give each block to the bucket its publish was
  released in, not always the one it was acknowledged in, so where the alarm blocked publishers
  the deliveries' holding time Python's implies may lie within 1% of the Java's (0.62% at most
  over the simulation baselines' blocked runs). The rest of each run is still compared as
  Python's, and its result digest is taken again from the reading.
- Bad, because a queue's `serviceOnlyCapacityRps` still divides its consumer slots by its mean
  publish time, which says nothing about its consumers. No finding, formula or page reads it; it
  is left as it is, to be decided apart.

### Confirmation

`QueueCapacityTests` simulates the Kafka template: the topic's holding time is what Little's law
makes of its busy slots and its messages processed a second (within 5%), its capacity is the
estimate's (within 15%), and its consumer throughput formula and its finding give the capacity it
reports. Both failed before (118 ms against 238, 135 msg/s against 67). It also pins a queue that
finished no delivery in its window. `QueueHoldDeparturesTests` and `RunDeparturesTests` hold the
reading. The golden reports and the recorded designs read as the frozen engine's in the build,
and the simulation baselines of the templates with a queue in the parity run
(`./gradlew :archsim-engine:parity`).

## Pros and Cons of the Options

### Keep the mix, and give the queue a second holding time, its deliveries', for its capacity

- Good, because the frozen engine's figure stays as it was.
- Bad, because it adds a field whose name says what the first should have said, and leaves a
  `meanHoldMs` that no formula or reader can use for a queue.
- Bad, because the utilization and M/M/c formulas, which take the holding time, would need the
  second one too, or go on saying a backlogged topic hardly waits.

### Keep the mix, and say in the consumer throughput's note that it counts publishes

- Good, because nothing departs.
- Bad, because the capacity, the finding and the formulas stay wrong by as much as before: a note
  explains a figure, and cannot make it the consumers'.

## More Information

- [ADR-0049](0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md), whose replay of
  the frozen engine's runs the departure is read through.
- [ADR-0089](0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md) and
  [ADR-0092](0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md), engines 1.5.1
  and 1.5.2, which also gave existing figures another meaning and kept the report's version.
