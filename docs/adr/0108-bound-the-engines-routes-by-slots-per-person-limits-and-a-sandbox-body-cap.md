---
status: proposed
date: 2026-11-27
decision-makers: Majid Abarghooei (owner)
---

# 0108. Bound the engine's routes with slots, a limit per person and a sandbox body cap

## Context and Problem Statement

The API reads a request's whole body, up to `ARCHSIM_MAX_BODY_BYTES` (5 MB), into bytes and then a
JSON tree before anything else happens. The routes that make the engine work on a design then load
it and analyze it: `/validate`, `/analyze`, `/export`, `/import`, creating, importing, saving,
duplicating or copying a project, and starting or rerunning runs. `/calibrate` also replays the
design on the replay threads
([ADR-0054](0054-replay-calibrations-on-a-pool-of-platform-threads.md)), whose queue had no end.
Nothing limited how many of these requests ran at once, or how many requests to other routes were
reading a body. The largest body, read and loaded, holds about 77 MB of heap (measured on this
code). The hosting kit gives the API a 512 MB heap ([deployment.md](../deployment.md)), and every
role exits when its heap runs out ([ADR-0053](0053-simulate-runs-in-the-java-apps-worker-role.md)),
so the requests in memory at once must be bounded.

How should the API bound the memory and time these requests take, for signed-in people and for
visitors who have not signed in?

## Decision Drivers

- The requests in memory at once fit in the API's heap, whatever arrives.
- A person editing never notices: the editor sends a validation and an analysis together after each
  pause of 350 ms.
- Visitors in a sandbox ([ADR-0079](0079-let-visitors-try-archsim-in-a-sandbox-without-signing-in.md))
  get a small share; designs are kilobytes (the largest example is 24 KB).
- A slow calibration never holds what quick requests need.
- The bound is set by default from what the process has, and a setting changes it.

## Considered Options

- Slots taken before the body is read, a limit per person, and smaller body caps where designs are
  not sent.
- A smaller body cap for everyone.
- A budget of body bytes in flight, each request weighted by its size.

## Decision Outcome

Chosen option: "slots taken before the body is read, a limit per person, and smaller body caps
where designs are not sent", because it bounds memory directly, leaves the editor untouched and
needs no change to what a design may hold.

- **A request with work holds a slot from before its body is read until it is answered.** A Spring
  MVC interceptor (`RequestSlots`) takes it before the body is read. A request that finds no slot
  free within a second gets `503` with `Retry-After`.
- **The engine's routes**, marked `@EngineRoute`, share the quick slots. There are
  `ARCHSIM_MAX_ENGINE_REQUESTS` of them, or by default as many as the heap holds:
  - each is given 25 bytes of heap per byte of the largest body, 125 MB at 5 MB, against the
    77 MB measured, for what parsing and the analysis allocate on the way;
  - the rest of the API keeps 256 MiB, and each replay slot as much as a request;
  - the count is rounded to the nearest, since a JVM reports a little less than its `-Xmx` (the JVM
    picks the serial collector in a container under 1,792 MB, and with it a 512 MB heap reports
    495 MiB), and kept between 1 and 16.

  That is one slot on the kit's 512 MB heap with two cores, and three on 1 GB with four.
  `Retry-After` is 1 s.
- **A calibration holds a replay slot instead.** There is one per replay thread, the processors
  less one, but no more than the heap holds after the rest of the API and one quick slot, so a
  replay, which can take a minute, never holds a quick slot. A replay's share assumes its
  simulation fits in it: `ARCHSIM_MAX_REQUESTS` bounds the simulation, not the body. The replay pool's
  queue holds as many replays as it has threads, for a replay that outlives its request; one more
  is refused. `Retry-After` is 10 s.
- **Every other route that reads a body** takes up to 64 KB (`413` beyond; comments, the largest,
  are 4,000 characters), and 32 such requests are answered at once.
- **Each person may make 600 requests a minute to the engine's routes**, with the hosted limits on
  (`ARCHSIM_RATE_LIMITS`). That is about 1.75 times the most the editor sends. The request is
  counted before its slot is taken, so a person over the limit never waits for one; past the limit
  the answer is `429` with `Retry-After`.
- **A sandbox's guest sends bodies of up to 256 KB**, on every route, ten times the largest example.
  A filter refuses a larger one with `413` before anything reads it. Signed-in people keep the
  5 MB cap on the engine's routes.

The engine's routes are synchronous. The interceptor takes a slot only on a request's first
dispatch, so a route made asynchronous later could not hold two.

### Consequences

- Good, because the heap the requests with a body can hold is bounded by the slots: on the kit, one
  design read and loaded besides a calibration's replay, and 32 small bodies.
- Good, because the editor's requests take milliseconds, so even one slot serves many people, and a
  request waits a second at most before it is told when to come back.
- Good, because calibrations cannot starve the quick routes, and their queue has an end.
- Bad, because a slot is held while its body is read, while its answer is written, while a
  calibration queries Prometheus (30 s at most a query), and while the request waits for the
  database (10 s at most for a connection). A client that sends or reads slowly holds its slot
  until the server's connection timeout. The web app's proxy reads a browser's whole body before
  it calls the API, so this bites a client that reaches the API directly or stops reading an
  answer.
- Bad, because several large imports at once are answered `503` for some of them, and the web app
  shows that as an error until they try again.
- Bad, because a sandbox's guest cannot import a design larger than 256 KB without signing in.
- Bad, because reading or exporting a project and opening a share link hold no slot; bounding what a
  stored design may hold is a follow-up.

### Confirmation

- `EngineRequestsTests`, on a server with one slot: a validation while an analysis holds the slot
  is answered `503` with `Retry-After: 1`, and is served once the slot is free, also after a
  request that failed. A person past 600 requests a minute gets `429`.
- `RequestSlotsTests`:
  - a calibration while the only replay slot is held gets `503` with `Retry-After: 10` while a
    quick request is still served;
  - a request stopped while it waits keeps its interrupt;
  - an asynchronous dispatch takes nothing;
  - a body over 64 KB on another route gets `413`;
  - the small slots refuse when full.
- `SmallBodiesTests`: a 70 KB comment is refused.
- `EngineRoutesTests`: every route that makes the engine work on a design is marked.
- `GuestBodiesTests`: a guest's 300 KB design gets `413` and a signed-in person's is served.
- `ReplayPoolTests`: the pool's bounded queue.
- `ArchsimPropertiesTests`: the default count, with the heaps JDK 25 reports.

The slot, limit, guest and replay-queue behaviours fail against the code before.

## Pros and Cons of the Options

### A smaller body cap for everyone

- Good, because one setting bounds every route.
- Bad, because imports of larger files (Terraform, Kubernetes manifests) are what the 5 MB cap is
  for, and the number of requests at once would still be unbounded.

### A budget of body bytes in flight

- Good, because many small requests run at once and large ones wait for each other.
- Bad, because a small request still allocates for its analysis, so a count is needed as well, and
  two bounds are harder to size and explain than slots of a size.

## More Information

- [ADR-0054](0054-replay-calibrations-on-a-pool-of-platform-threads.md): the replay threads.
- [docs/api.md](../api.md), "Errors" and "Limits", and [SECURITY.md](../../SECURITY.md) list the
  numbers.
