# Calibrating a design from telemetry

A design's numbers start as assumptions: service times, hit ratios, link latency, traffic.
Calibration replaces them with what your running system recorded, marks each one **measured**
with where it came from and how well it fits, and then checks the result: it replays the
observed traffic through the calibrated model and reports how close the model comes to the
latency and throughput that were actually seen ("this model reproduces the observed p95 within
5%").

Two sources are supported: **Prometheus** metrics (with a mapping file) and **OpenTelemetry**
traces (an OTLP JSON export). Both run locally: the only network calls are the queries to the
Prometheus server you name, and only when you run a calibration.

## Where inputs come from (provenance)

Every performance input is one of:

- **measured**: fitted from telemetry. The document records the source, the query, the time
  range, the fit error and when it was fitted (`provenance` in schema 1.1).
- **assumed**: typed in by a person (it differs from ArchSim's default and from the
  technology preset).
- **illustrative**: ArchSim's default or the number a technology preset fills in (see
  [assumptions.md](assumptions.md)).

The inspector shows a green **measured** badge next to measured settings (hover it for the
source, query, range and fit quality); editing a measured value makes it assumed again. Reports
list every input with its basis (Results → Details → Assumptions, and the Markdown report), with
counts such as "7 measured, 2 assumed, 31 illustrative".

## Prometheus

```bash
uv run archsim calibrate prometheus design.json --map mapping.yaml \
  --url http://localhost:9090 --range 2026-10-01T00:00:00Z/2026-10-08T00:00:00Z -o calibrated.json
```

In the web app: Ctrl/⌘ K → **Calibrate from telemetry** (or ⋯ → Calibrate from telemetry…),
which shows every change, its fit error and the replay check before you apply it (undoable).
The API is `POST /api/v1/calibrate` ([api.md](api.md)).

`--record responses.json` also saves every Prometheus response and the time range they cover;
`--recorded responses.json` replays a recording instead of querying a server (offline, for
tests and demos). A recording answers for its own range: a different `--range` is noted and not
used, so a measured input never cites a window its data did not come from.

### The mapping file

The mapping ties parts and links of the design (by id) to PromQL:

```yaml
range: {start: 2026-10-01T00:00:00Z, end: 2026-10-08T00:00:00Z, step: 300}
nodes:
  api:
    serviceTimeMs:   # handler time WITHOUT downstream calls, as a histogram
      histogram: sum by (le) (rate(http_server_handler_seconds_bucket{job="api"}[5m]))
      unit: s        # bucket bounds in s or ms
      fit: lognormal # or empirical (a quantile table)
    workersPerReplica: {gauge: max(tomcat_threads_config_max{job="api"}), reduce: max}
  cache:
    hitRatio:
      ratio: {hits: sum(rate(redis_keyspace_hits_total[5m])), misses: sum(rate(redis_keyspace_misses_total[5m]))}
  db:
    readServiceTimeMs: {histogram: sum by (le) (rate(pg_query_duration_seconds_bucket{op="read"}[5m])), unit: s}
edges:
  api-db:
    latencyMs: {client: <client-side histogram>, server: <server-side histogram>, unit: s}
workload:
  rps: {rate: sum(rate(http_requests_total{job="lb"}[1m])), trace: true}
replay:
  endToEnd: {histogram: <latency as clients see it>, unit: s, throughput: <successful req/s>}
  components:
    db: {histogram: <the database's own latency histogram>, unit: s}
```

What each source does:

| Source | Becomes | How |
| --- | --- | --- |
| `histogram` | a distribution | the buckets averaged over the range give quantiles (linear inside a bucket, like `histogram_quantile`); `lognormal` matches the median and p95 exactly, `empirical` keeps a quantile table. The fit error is the largest relative error between the fitted and observed p50, p75, p90, p95 and p99. |
| `gauge` | a number (workers, connections...) | the series summed per timestamp, then the mean, max, min or last value over the range |
| `ratio` | a hit ratio | hits / (hits + misses), each averaged over the range |
| link `client` / `server` | the link's latency | (client mean - server mean) / 2, one way, as a constant |
| `rate` | the workload | its mean becomes the baseline rate; with `trace: true` the curve becomes a `trace` pattern: in real time when the range is an hour or less (the run then lasts as long as the range), otherwise compressed into the run's duration (the note says by how much) |

Use handler time **without** downstream calls for `serviceTimeMs` (ArchSim adds the downstream
calls itself); total request time double-counts them. Parts or settings the mapping names but
the design lacks are reported, never guessed.

### The replay check

After applying the mapping, ArchSim simulates the calibrated design and compares it with the
`replay` targets: end-to-end p50, p95 and p99 (and throughput), and each listed part's latency
(the time requests spend in it, including its downstream calls). The document keeps the result
(`calibration`) so reports can show it later.

## OpenTelemetry traces

```bash
uv run archsim calibrate otlp design.json --traces traces.json --map otlp-mapping.yaml -o calibrated.json
```

The input is an OTLP JSON export (`resourceSpans`, as the collector's file exporter writes it).
ArchSim derives:

- each service's own time: its SERVER spans' durations minus the time covered by their outgoing
  CLIENT and PRODUCER spans, so `serviceTimeMs` excludes downstream calls;
- each database's read and write times: CLIENT spans with `db.system`, split by `db.operation`
  (or the statement's first word);
- the call graph: which service calls which service or database. Calls the design has no link
  for are reported.

The optional mapping names parts for OpenTelemetry names (otherwise a part whose id or label
matches is used):

```yaml
services: {checkout-api: api}
databases: {orders: db}
```

The replay simulates the calibrated design at the traced request rate and compares each mapped
service's latency with its SERVER spans. Traces are usually sampled, so the rate and tails are
rougher than metrics.

## Example

`packages/engine/tests/fixtures/calibration/` holds a calibration of the cache-aside starter:
a mapping and Prometheus responses **generated** from known parameters
(`scripts/make_calibration_fixture.py`), plus an OTLP export generated the same way
(`scripts/make_otlp_fixture.py`). Calibrating the starter from them recovers the parameters and
replays the observed p95 within about 5%:

```bash
uv run archsim calibrate prometheus examples/cache-aside-starter.json \
  --map packages/engine/tests/fixtures/calibration/starter-mapping.yaml \
  --recorded packages/engine/tests/fixtures/calibration/starter-prometheus.json -o calibrated.json
```

## Limits

- One time range per calibration; inputs are averaged over it (daily peaks and quiet nights are
  blended unless you pick the range).
- Histograms are only as precise as their buckets: coarse buckets make the fitted p95 and the
  replay's observed p95 approximate.
- Not derived yet: capacity limits from pool gauges without a mapping, retries and timeouts from
  traces, a design from traces (the call graph is reported, not drawn).
- The API's `/calibrate` calls the Prometheus URL it is given: keep the API on localhost (as the
  README says) so it cannot be used to reach other hosts.
