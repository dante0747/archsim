---
status: accepted
date: 2026-11-17
decision-makers: Majid Abarghooei (owner)
---

# 0084. Judge asynchronous designs by their messages too

## Context and Problem Statement

A design's objectives judged only its requests: their latency, the share that succeeded and the
throughput. An asynchronous design answers a request once it has published a message, so its
work happens after the answer, and the objectives could not see it. The site critic's review of
the live demo (16 Nov 2026) ran the Kafka order pipeline. The verdict said "Yes, it holds up"
while orders waited 21.0 s (p95) for a consumer, and 46 of 4,601 confirmation e-mails failed
unseen. A message a queue dead-letters or drops is lost even though its request succeeded. How
should a design with queues be judged?

## Decision Drivers

- The verdict judges what the design promises, the work behind the answer included.
- A lost message is a failure, whatever the request's answer was.
- One rule in the engine (the CLI's `check`, the MCP server) and in the web app.
- Designs without queues are judged exactly as before.

## Considered Options

- (a) A freshness objective, the p95 age of a message when processed at the slowest queue, and
  an availability that counts lost messages as failed requests
- (b) A freshness objective only
- (c) Fail the verdict on any backlog or lost message, with no objective

## Decision Outcome

Chosen option: (a).

- **The objective.** Schema 1.10 adds `objectives.messageAgeP95Ms`: the p95 age of a message,
  from its publish to a consumer having processed it, at the design's slowest queue. It is left
  out of a document while unset, and the upgrade from 1.9 sets nothing. The verdict judges it as
  it judges a latency ("Message age p95 21,040.0 ms (target <= 10,000.0 ms)").
- **The run's figures.** A run whose design has a queue adds three figures to its summary (report
  1.19):
  - `messageAgeP95Ms`, the slowest queue's p95 age. It is left undecided (null) when a queue
    held messages it never processed, as their age is unknown and no smaller than the others'.
  - `lostMessages`, the messages its measured requests published that a queue dead-lettered or
    dropped, by the end of the run, drain included.
  - `availability`, the share of its measured requests that succeeded and lost no message.

  Each request carries its fate, which its attempts and the messages it published share
  (`RequestFate`). A measured request that succeeded and lost a message counts once, whichever
  came first and however many messages it lost, so the figure counts the same requests as the
  success rate.
- **The verdict.** The availability objective judges `availability` where a run has it, and
  the success rate otherwise. The message age judges only a run whose design has a queue: a
  target left on a design without one is not judged, so it does not leave every verdict
  undecided. The run lists store all three figures, and the web app's seed groups pass them
  through, so a listing judges as the run page does.
- **The templates.** The asynchronous templates set a freshness target from their story:
  - the Kafka order pipeline: 10 s for an order's confirmation;
  - the CQRS read model: 10 s for a merchant's order to show in search;
  - live telemetry: 1 s;
  - notifications: 60 s;
  - the image pipeline: 30 s;
  - the flash sale's payments: 30 s;
  - catalog edits in search: 5 s;
  - document ingestion for the RAG assistant: 60 s.
- **Engine 1.4.0**, because the report changes and a run's verdict can, for the same design and
  seed.

### Consequences

- Good, because the Kafka order pipeline now reads "No, it doesn't hold up: Message age p95
  21.0 s (target ≤ 10.0 s)". Its scenario with 32 partitions and 32 consumers shows what fixes
  it.
- Good, because a design that loses messages can no longer meet a 99.9% availability while
  answering every request.
- Bad, because a failure inside the consumer's work that does not lose the message still goes
  unjudged: the pipeline's 46 failed e-mails, whose link carries on, are reported by a finding
  but count in no objective.
- Bad, because the breaking point (`check --knee`, the sweep and the web's Breaking point)
  still judges the load at which a design breaks by its requests alone. A queue's age grows
  with load by design, its consumers falling behind until a burst ends, so it is no knee. A
  queue that starts to drop is arguably one, but the breaking point is what the benchmarks
  measure and validate, by requests' latency and success, and judging it otherwise would move
  the validated knees; the run's verdict at a load judges both.
- Neutral: a request's attempts, and the copies of a message that reappear after their
  visibility timeout, share one fate, so a request counts as having lost a message when a
  duplicate was lost: the message of an attempt that failed and was retried, or a copy
  dead-lettered while the other was processed.
- Bad, because a run with queues departs from the frozen engine by three summary figures, which
  the parity tests read through a structured departure (`MessageDepartures`).
- Neutral: a run without a queue reports and is judged exactly as before.

### Confirmation

- `ObjectivesTests` and `UpgradesTests` cover the schema.
- `RequestFateTests` checks that a request counts once, whichever came first, and every message
  it lost.
- `MessageFiguresTests` (reporting) covers the slowest queue, an undecided age, availability
  and a run with no request.
- `LostMessagesTests` covers a queue that processes every message, one whose consumer fails them
  all, and a design without a queue.
- `SloResultTests` and `RunFiguresTests` cover the verdict, a freshness target without a queue,
  and the figures.
- `ExecutionTests` checks the stored summary; `slo.test.ts` and `seed-groups.test.ts` the web's
  verdicts.
- `MessageDepartures` reads a recorded run as the frozen engine's only after checking the age
  against its queues and the availability against its success rate and lost messages.

## Pros and Cons of the Options

### (b) A freshness objective only

- Good, because availability keeps one meaning.
- Bad, because a design that drops a fifth of its messages still meets 99.9%.

### (c) Fail on any backlog or loss

- Good, because nothing needs setting.
- Bad, because a backlog that drains in seconds is a design working as intended; the target is
  the design's to state.

## More Information

[ADR-0083](0083-name-each-utilisation-and-compare-like-with-like.md) fixed the review's third
finding; [docs/schema.md](../schema.md#objectives) describes the objectives.
