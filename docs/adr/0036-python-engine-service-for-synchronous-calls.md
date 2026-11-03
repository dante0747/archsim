---
status: proposed
date: 2026-11-03
decision-makers: Majid Abarghooei (owner)
---

# 0036. Run the engine's synchronous calls in a small internal Python service

## Context and Problem Statement

During stage 2 the Java API serves every endpoint, but the engine stays Python until stage 3
([proposal](../proposals/java-25-port.md#stage-2--the-api-on-spring-boot-engine-still-python-12-weeks)).
Runs already cross the language boundary through the PostgreSQL queue. Some answers, though,
are computed while the request waits: an analysis's sizes, cost and review (`POST /analyze`), a
run's prepared input and quick check, comparisons, seed summaries, exports, imports and
calibrations. Validating a design is mostly not among them: archsim-model already validates with
the engine's codes and words. Validating one of its scenarios is, because resolving a scenario
sizes the "auto" parts first, which Java does from stage 3. How does the Java API call the
Python engine synchronously, and where does that code live, given that the engine package is
frozen and the FastAPI code is deleted at the end of stage 2?

## Decision Drivers

- Answers identical to today's, numbers included, without re-encoding them in Java.
- Nothing under `packages/` changes (the engine is frozen at 0.16.0).
- It disappears piece by piece: each endpoint goes when stage 3 ports its call.
- No new kind of infrastructure; compose already runs Python containers.

## Considered Options

- A new internal HTTP service, `services/engine-service` (Starlette and Uvicorn, already in the
  lock file), one endpoint per engine call, stateless
- Keep the FastAPI API running behind the Java API for those endpoints
- A Python subprocess per call
- Engine calls through the PostgreSQL queue, like runs

## Decision Outcome

Chosen option: "a new internal HTTP service", because it is the smallest thing that keeps the
answers exactly as they are and can shrink one endpoint at a time.

- `services/engine-service` is a uv workspace member, `archsim-engine-service`, depending on
  `archsim-engine`, Starlette and Uvicorn only: no database, no settings beyond the engine's own
  (`ARCHSIM_PRICES_DIR`), no authentication. It listens inside compose only.
- One `POST` endpoint per engine call, JSON in and out: `{"document", "scenarioId"}` for
  `/analyze` and for `/validate`, which the Java API calls only with a scenario. A document the
  engine cannot read answers `422 {"issues": [...]}`; a request it cannot read,
  `400 {"message"}`. Answers are encoded as FastAPI encoded them, so the Java API passes the
  bytes on. Each call runs in a worker thread, as FastAPI ran sync routes, so a long analysis
  holds up no other request; the service has no body limit of its own, as only the Java API,
  which limits bodies, calls it.
- The Java API reaches it at `ARCHSIM_ENGINE_URL` (default `http://127.0.0.1:8001`) through one
  client in its `engine` package, over HTTP/1.1, and waits `ARCHSIM_ENGINE_TIMEOUT` seconds
  (default 120) for an answer. If the service is down, or does not answer in time, the endpoint
  answers `503` with the error body saying which; `GET /health` stays about the database, as
  today. The Python API had no such timeout.
- The black-box suite starts the service next to the Java API.

### Consequences

- Good, because `/analyze` and the others answer byte for byte what the Python API answered.
- Good, because the engine and the FastAPI code stay untouched until each is deleted.
- Good, because the service is stateless: it can be restarted or scaled on its own.
- Bad, because there is one more container and one more network hop per analysis (a few
  milliseconds on the compose network, small next to the engine's own time).
- Bad, because a request validated in Java is read again in Python; both use the same document
  rules, so they agree.

### Confirmation

`services/engine-service/tests` compare each endpoint with the engine call it wraps, and its
bytes with FastAPI's encoding. The Java client's tests use a stub server; CI runs the ported
black-box tests, `/analyze` among them, against the Java API with the real service.

## Pros and Cons of the Options

### Keep the FastAPI API behind the Java API

- Good, because there is nothing to write.
- Bad, because the stage's exit deletes the FastAPI code, and it would keep a second copy of
  authentication, settings and the database in the request path.

### A Python subprocess per call

- Good, because there is no extra service.
- Bad, because importing the engine takes about a second, on every analysis.

### Through the PostgreSQL queue

- Good, because the seam exists already.
- Bad, because a waiting request would poll the database; the queue is built for runs that take
  minutes, not answers in milliseconds.

## More Information

- [ADR-0014](0014-one-deployable-application-with-roles.md): the Java application's roles.
- Stage 3 of the port removes each endpoint as its call moves to `archsim-engine`; when the last
  one goes, the service is deleted.
