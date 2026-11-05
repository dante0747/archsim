# archsim-engine-service

An internal HTTP service that runs the Python engine's synchronous calls for the Java API
(`archsim-app`) while the engine is ported ([ADR-0036](../../docs/adr/0036-python-engine-service-for-synchronous-calls.md)).
It keeps no state and no database, listens only inside compose, and each endpoint goes away when
stage 3 of the [Java port](../../docs/proposals/java-25-port.md) moves its call to Java.

```bash
uv run archsim-engine-service --port 8001
```

| Endpoint | Engine call | Used by |
| --- | --- | --- |
| `GET /health` | | the Java API's checks |
| `POST /compare` `{"a", "b", "labelA", "labelB"}` | `compare_reports` | `GET /api/v1/compare` |
| `POST /export` `{"document", "format"}` | `export_document` | `POST /api/v1/export` |
| `POST /import` `{"format", "text", "name"}` | `import_design` | `POST /api/v1/import` |
| `POST /calibrate` (the request of `POST /api/v1/calibrate`, with the server's outbound policy) | `calibrate_prometheus`, `calibrate_otlp` | `POST /api/v1/calibrate` |

A call the engine refuses answers `422 {"message", "issues", "kind"}`: `invalid` with the
engine's words and issues for a document it cannot read, or `unreadable` for text, a mapping or
telemetry that cannot be used, in the words the Python API answered. A request the service cannot
read answers `400 {"message": ...}`. Answers are written as FastAPI wrote them, so the Java API
passes them on unchanged. Each call runs in a worker
thread, so a long call does not hold up other requests. The service has no body limit of its
own: it listens only to the Java API, which refuses bodies over `ARCHSIM_MAX_BODY_BYTES`. The
engine reads `ARCHSIM_PRICES_DIR` from the environment, as it does in the Python API.

`/calibrate` is the one call that reaches out: it queries the Prometheus server a request names,
under the policy the Java API sends with it, worked out from the server's settings and the
workspace: `outbound` (`open`, `allowlist` or `off`, always `off` for a sandbox),
`allowedHosts` (`ARCHSIM_PROMETHEUS_ALLOWED_HOSTS`), `timeoutS` and `maxBytes`
(`ARCHSIM_PROMETHEUS_TIMEOUT_S`, `ARCHSIM_PROMETHEUS_MAX_BYTES`), and the replay's `maxRequests`
and `maxWallSeconds`. A policy it cannot read is refused (`400`), never guessed. Under
`allowlist` a listed name must resolve to public addresses, as in the Python API. The service
checks no caller, so it must listen where only the Java API reaches it.
