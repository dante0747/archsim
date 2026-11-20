---
status: accepted
date: 2026-11-20
decision-makers: Majid Abarghooei (owner)
---

# 0096. Read the simulation parity's runs as the frozen engine's where the engine changed on purpose

## Context and Problem Statement

The simulation parity ([ADR-0052](0052-record-the-frozen-engines-simulation-baselines-once.md))
replays the frozen engine 0.16.0's 2,980 baseline runs in Java and counts the runs that give
exactly the figures and both digests the baselines keep. Its page
([java-parity.md](../validation/java-parity.md)) said every run did, as engine 1.0.0 did. Engines
1.2.0 to 1.6.0 changed what a run reports on purpose: an API with no price stays unset
([ADR-0081](0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)), a cache counts
the lookups it refused in its hit ratio
([ADR-0082](0082-count-a-caches-refused-lookups-in-its-hit-ratio.md)), a database of several
instances gives its busiest instance's CPU ceiling
([ADR-0083](0083-name-each-utilisation-and-compare-like-with-like.md)), the summary gives message
figures ([ADR-0084](0084-judge-asynchronous-designs-by-their-messages-too.md)), a queue its idle
consumers ([ADR-0085](0085-size-a-kafka-broker-as-measured-and-name-idle-consumers.md)), and a
saved design is pinned to whole cores
([ADR-0091](0091-count-a-vcpu-as-a-hyperthread-unless-the-design-or-its-instance-family-says-otherwise.md)).
The golden reports read each change as the frozen engine's through a structured departure,
guided by the frozen engine's whole report of the same call. The simulation parity read only the
input digest's schema, so on engine 1.6.0 just 940 of its 2,980 runs replayed exactly, and the
page still said all of them did. Its baselines keep a run's figures, not its report. How should
the parity count a run that departs only by such a change?

## Decision Drivers

- The page's counts are true, and a run that departs for a reason no deliberate change explains
  still shows.
- A departure is read the same way in the golden reports and in the simulation parity.
- A result digest is all or nothing: a run read as the frozen engine's gives its digest only where
  every series, formula and figure of its result is the frozen engine's.

## Considered Options

- Read each departure as the golden reports do, from what the baselines and the golden files keep;
  work out what they do not keep where a rule gives it exactly; count apart the runs a departure
  leaves unreadable.
- Compare the kept figures alone, not the result digest.
- Leave the counts as they are and explain them on the page.

## Decision Outcome

Chosen option: read each departure as the golden reports do, because it keeps the counts true and
as strict as before: every run is still held to the frozen engine's result digest.

- A run's report is read with the golden reports' departure classes (`MessageDepartures`,
  `IdleConsumerDepartures`, `BusiestInstanceDepartures`, `SchemaDepartures`), guided by the
  baselines' figures of the run, and, for its parts' settings (an API's price of 0, which the
  upgrade to schema 1.9 unsets), by the frozen engine's report of the template's run in its golden
  file (`RunDepartures`).
- The baselines do not keep the formula the frozen engine wrote for a database of several
  instances' CPU ceiling. It is worked out from the frozen engine's kept work per request and
  ceiling: its cores over its instances at that work, in the frozen engine's words, which the
  parity's test code keeps as it keeps the frozen engine's other texts. The result digest checks it:
  a wrong rule gives a departing digest, never a match.
- A database that refused operations may report its busiest instance a little less busy than the
  mean over its instances, for that instance is the one sent the most work, the refused operations
  counted, which its cores did not run. `BusiestInstanceDepartures` reads it all the same; the rest
  of its checks still hold.
- The result digest is taken over the result so read, once it is shown to be the run's own, as the
  golden reports take theirs (`RunFigures`).
- A cache that refused lookups counts them in its hit ratio by second and in its hit ratio's
  formula. The baselines keep neither of the frozen engine's, and no rule gives a bucket's hits and
  misses from its hit ratio, so such a run cannot replay exactly. Its kept hit ratio is read as the
  frozen engine's, and the run is counted apart when every figure it keeps is the frozen engine's
  but the result digest (`CaseResult.Departing`). The page counts and names these runs, and any run
  that departs otherwise.

### Consequences

- Good, because the page's counts are what a full run gives, and every departure they read is
  named on it with its ADR.
- Good, because a run counted as an exact replay is still the frozen engine's in every series,
  formula and figure of its result: the digest covers what the baselines do not keep.
- Bad, because the runs where a cache refused lookups cannot be shown to replay exactly: the most
  the baselines show of them is every kept figure but the result digest.
- Bad, because one formula is worked out rather than read: the rule is checked only by the digests
  of the runs it reads.

### Confirmation

`RunFiguresTests` reads golden runs of the templates, simulated in Java, against what the baselines
would keep of the frozen engine's reports of them; `CaseResultTests` and `ParityReportTests` count
the runs; the full parity (`./gradlew :archsim-engine:parity`) writes `simulation-parity.json`, and
`CommittedParityPageTests` holds the committed page to it.

## Pros and Cons of the Options

### Compare the kept figures alone, not the result digest

- Good, because no formula has to be worked out.
- Bad, because the result digest is the only check on the series, formulas and links of a run's
  result, which the baselines do not keep: an exact replay would claim less than it did.

### Leave the counts as they are and explain them on the page

- Good, because nothing changes in the parity run.
- Bad, because the counts would mix deliberate changes with real departures: a run that departed
  for another reason would hide among the 2,040 the changes explain.

## More Information

[ADR-0052](0052-record-the-frozen-engines-simulation-baselines-once.md) records the baselines and
the count of exact replays this reads; [ADR-0049](0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)
why the simulator is measured by replaying the frozen engine's runs.
