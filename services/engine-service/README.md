# archsim-engine-service

An internal HTTP service that runs the simulation a calibration replays, for the Java API
(`archsim-app`), until stage 4 of the [Java port](../../docs/proposals/java-25-port.md) moves the
simulator to Java ([ADR-0036](../../docs/adr/0036-python-engine-service-for-synchronous-calls.md),
[ADR-0045](../../docs/adr/0045-replay-calibrations-through-an-interim-simulate.md)). It keeps no
state and no database, listens only inside compose, and goes away with stage 4.

```bash
uv run archsim-engine-service --port 8001
```

| Endpoint | Engine call | Used by |
| --- | --- | --- |
| `GET /health` | | the Java API's checks |
| `POST /simulate` (`{"document", "maxRequests", "maxWallSeconds"}`) | `run_simulation`, as the calibrations ran their replay | the replay of `POST /api/v1/calibrate` |

A run the engine refuses answers `422 {"message", "issues", "kind"}`: `invalid` with the
engine's words and issues for a document it cannot read, or `limit` with the engine's words for a
run over its limits. A request the service cannot read answers `400 {"message": ...}`, and so does
one without both limits: it never simulates without bounds. The report is the engine's, as it
wrote it. Each call runs in a worker thread, so a long call does not hold up other requests. The
service has no body limit of its own: it listens only to the Java API, which refuses bodies over
`ARCHSIM_MAX_BODY_BYTES`. The engine reads `ARCHSIM_PRICES_DIR` from the environment, as it does
in the Python API.

The service calls nothing: the Java API calls the Prometheus servers that calibrations name,
under its own outbound policy. It checks no caller, so it must listen where only the Java API
reaches it: whatever reaches it can run simulations, within the limits it sends.
