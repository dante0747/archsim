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
| `POST /analyze` `{"document", "scenarioId"}` | `analyze_document` | `POST /api/v1/analyze` |
| `POST /validate` `{"document", "scenarioId"}` | `resolve_scenario`, `validate_document` | `POST /api/v1/validate` with a scenario (resolving one sizes "auto" parts, which Java does from stage 3) |
| `POST /prepare` `{"document", "scenarioId", "maxRequests", "maxWallSeconds", "seed"}` | `prepare_run`, `quick_check` | starting and rerunning runs: the document to store, its scenario and seed |
| `POST /compare` `{"a", "b", "labelA", "labelB"}` | `compare_reports` | `GET /api/v1/compare` |
| `POST /summarize-seeds` `{"runs", "objectives"}` | `summarize_seeds` | `GET /api/v1/run-groups/{id}` |

A call the engine refuses answers `422 {"message", "issues", "limit"}`: the engine's words and
issues for a document it cannot read, or for a run over its limits (`limit` true). A request the
service cannot read answers `400 {"message": ...}`. Answers are written as FastAPI
wrote them, so the Java API passes them on unchanged. Each call runs in a worker thread, so a long
analysis does not hold up other requests. The service has no body limit of its own: it listens
only to the Java API, which refuses bodies over `ARCHSIM_MAX_BODY_BYTES`. The engine reads
`ARCHSIM_PRICES_DIR` from the environment, as it does in the Python API.
