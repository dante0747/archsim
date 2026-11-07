---
status: accepted
date: 2026-11-07
decision-makers: Majid Abarghooei (owner)
---

# 0045. Replay calibrations through an interim `/simulate` on the engine service

## Context and Problem Statement

Stage 3 of the [Java port](../proposals/java-25-port.md) moves calibration to `archsim-engine`:
reading the mapping and the telemetry, fitting the inputs, the provenance and the replay summary.
A calibration then checks its result by replaying the calibrated design: it simulates it and
compares the run with what was observed. The simulator itself is ported in stage 4. Until then,
the engine service ([ADR-0036](0036-python-engine-service-for-synchronous-calls.md)) ran the
whole calibration in Python behind `/calibrate`, calls to Prometheus included. How does the Java
calibration run its replay before the Java simulator exists?

## Decision Drivers

- The calibrations, and their calls to Prometheus, move to Java in stage 3, as planned.
- The replay's report must be the frozen engine's, so a calibration's result is unchanged.
- The service must expose nothing it did not expose already.
- It disappears when the Java simulator arrives.

## Considered Options

- An interim `POST /simulate` on the engine service, called for the replay only
- Keep `/calibrate` in Python until stage 4
- Replay through the run queue, as runs are simulated
- No replay until stage 4

## Decision Outcome

Chosen option: "an interim `POST /simulate`", because it moves everything but the simulation to
Java now and keeps the replay's report the frozen engine's, without opening anything new.

- `POST /simulate` takes `{"document", "maxRequests", "maxWallSeconds"}` and answers the report
  of `run_simulation(document, limits=...)`, as the engine's calibrations ran their replay, as
  the engine wrote it. Both limits are required (`400` without them), so the service never
  simulates without bounds; the Java API sends the workspace's replay limits, the same ones it
  sent with `/calibrate`. A run over its limits is refused with
  `422 {"message", "issues": [], "kind": "limit"}` in the engine's words, and a design the
  engine cannot read with `kind: "invalid"` and its issues.
- `/calibrate` is removed from the service: `/simulate` is the one call it keeps. The Java API
  calibrates with archsim-engine's `PrometheusCalibration` and `OtlpCalibration`, whose
  `ReplaySimulator` seam the engine service's client implements, and answers every refusal in
  the words the Python API answered.
- The service keeps its binding and exposure: it listens only inside compose, on the network it
  shares with the API alone, and checks no caller.
- This amends ADR-0036, whose last remaining call it replaces. Stage 4's exit removes
  `/simulate`, when the Java simulator replays calibrations; the service is deleted with it.

### Consequences

- Good, because the calibrations, their Prometheus client and their error words are Java, under
  the Java API's own outbound policy ([ADR-0046](0046-call-prometheus-with-apache-httpclient-5.md)).
- Good, because the service no longer calls out: what reaches it can no longer choose which
  Prometheus servers it calls, which ADR-0036 listed as its weakness.
- Good, because the replay's report, and so the calibration record, is the frozen engine's.
- Bad, because whatever reaches the service can still run simulations, within limits it chooses,
  as it could through `/calibrate`; the service must stay where only the Java API reaches it.
- Bad, because a replay's report crosses the network as JSON, a few hundred kilobytes per
  calibration, small next to the simulation itself.

### Confirmation

`services/engine-service/tests` compare `/simulate` with `run_simulation` and check its refusals.
The Java client's tests use a stand-in service; the black-box suite calibrates against the Java
API with the real service, replay and limit refusal included.

## Pros and Cons of the Options

### Keep `/calibrate` in Python until stage 4

- Good, because there is nothing to change.
- Bad, because the calibrations ported in stage 3 would serve only the golden tests, and the
  service would keep calling the Prometheus servers that requests name, under a policy its
  caller sends.

### Replay through the run queue

- Good, because runs already cross the language boundary there.
- Bad, because a calibration waits for its answer: it would poll the database for a run that
  takes seconds, and a replay would count against the workspace's runs.

### No replay until stage 4

- Good, because it needs no service at all.
- Bad, because a calibration's record and summary would lose their check against what was
  observed, and its answer would change.

## More Information

- [ADR-0036](0036-python-engine-service-for-synchronous-calls.md): the engine service, which this
  amends.
- `archsim-engine`'s `ReplaySimulator`: the seam the replay goes through until stage 4.
