---
status: proposed
date: 2026-11-09
decision-makers: Majid Abarghooei (owner)
---

# 0054. Replay calibrations on a pool of platform threads, never on the request's thread

## Context and Problem Statement

[ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md) moves the simulation a
calibration replays into the API's process, on the Java simulator. The API serves every request
on a virtual thread (`spring.threads.virtual.enabled`). A replay is CPU work that never blocks,
for up to its wall-clock limit (`ARCHSIM_CALIBRATE_MAX_WALL_SECONDS`, the lower of it and the
workspace's run limit: 60 s by default, 20 s hosted). On the request's virtual thread it would
hold that thread's carrier the whole time, and there are only as many carriers as cores, so as
many calibrations at once as there are cores would stop every other request, the health check
and sign-in included. Until now the replay waited on the engine service over HTTP, which frees
the carrier, and the Python API ran it on Starlette's thread pool, which the GIL switched every
5 ms: neither stalled the server. Where should a replay run?

## Decision Drivers

- No calibration may stall the API's other requests, on a machine of one or two cores too.
- A replay's wall-clock limit should measure the replay, not the other replays it shares the
  cores with.
- A replay's refusals keep their words: its limit, an invalid design.

## Considered Options

- On the request's virtual thread
- A platform thread of its own for each replay
- A pool of platform threads, the cores less one (at least one), with replays beyond it waiting
  in line

## Decision Outcome

Chosen option: "A pool of platform threads, the cores less one", because the operating system
schedules platform threads, so a replay never holds a carrier: the request's virtual thread
waits for the replay's result, which takes it off its carrier. Bounding the pool leaves a core
for the API's other requests, and keeps each replay at full speed, so its wall-clock limit
measures it alone.

- `ReplayPool` in the app's `calibration` package wraps the engine's `ReplaySimulator.of`: its
  threads are daemon platform threads named `archsim-replay-N`, as many as the processors the
  JVM sees less one, at least one, closed when the application stops.
- A replay's own exception is thrown again as it is, so a replay over its limits or of a design
  the engine cannot read is refused in the same words as before. A request interrupted while it
  waits (the server is stopping) keeps its interrupt, cancels its replay (one still in line
  never runs; one running runs on to its limit) and is answered `503`.

### Consequences

- Good, because a calibration no longer competes with the API's other requests for carriers:
  the health check and sign-in answer while replays run.
- Good, because a replay runs as fast as it would alone, as long as no more replays run than the
  pool has threads.
- Bad, because replays beyond the pool wait in line, with no limit on the wait; the hourly
  calibration limit (30 a user on a hosted server) bounds how many one user can queue.
- Bad, because a stopped replay runs on until its wall-clock limit: the simulator stops at its
  limits, not at an interrupt. Its result is thrown away.

### Confirmation

`ReplayPoolTests`: a replay asked from a virtual thread runs on a platform thread of the pool;
its exceptions are thrown again as they are; a request interrupted while it waits keeps its
interrupt and is answered `503`; the pool has the processors less one threads, at least one.

## Pros and Cons of the Options

### On the request's virtual thread

- Good, because it needs no code: the request simply calls the simulator.
- Bad, because a replay holds its carrier for up to its wall-clock limit, so as many
  calibrations at once as there are cores stall the whole API.

### A pool of platform threads, the cores less one

- Good, because no replay holds a carrier, and a core is left to the API's other requests.
- Good, because each replay runs at full speed while no more run than the pool has threads.
- Bad, because replays beyond the pool wait in line.

### A platform thread of its own for each replay

- Good, because no replay ever waits for another, and none holds a carrier.
- Bad, because many replays at once share the cores: each runs slower, and they reach their
  wall-clock limits together, refused for the load rather than for their designs.

## More Information

[ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md) keeps a worker's runs off
virtual threads for the same reason. [ADR-0045](0045-replay-calibrations-through-an-interim-simulate.md)
was the interim replay through the engine service, which this replaces.
