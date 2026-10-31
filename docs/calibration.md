# Calibrating a design from telemetry

A design's numbers start as assumptions: service times, hit ratios, link latency, traffic, the
CPU and memory each request needs. Calibration replaces them with what your running system
recorded, marks each one **measured** with where it came from and how well it fits, and then
checks the result: it replays the observed traffic through the calibrated model and reports how
close the model comes to the latency, throughput, CPU and memory that were actually seen. The
summary gives the end-to-end p95 and always the largest error of all the checks ("This model
reproduces the observed p95 (…) within 6% (under); the largest of 10 replay errors is -13% (End
to end p99)").

Two sources are supported: **Prometheus** metrics (with a mapping file) and **OpenTelemetry**
traces (an OTLP JSON export). Both run locally: the only network calls are the queries to the
Prometheus server you name, and only when you run a calibration.

## Where inputs come from (provenance)

Every performance input is one of:

- **measured**: fitted from telemetry, **and still the value that was fitted**. The document
  records the source, the query, the time range, the fit error, when it was fitted and the
  value it set (`provenance` in schema 1.1; `value` added in 1.2).
- **assumed**: typed in by a person (it differs from ArchSim's default and from the
  technology preset).
- **illustrative**: ArchSim's default or the number a technology preset fills in (see
  [assumptions.md](assumptions.md)).

A measured input stops being measured as soon as its value differs from the fitted one, however
that happens: a scenario that overrides it, a sweep, or an edit through the CLI, MCP, an API
`PUT` or the editor. It is then assumed (or illustrative, if it went back to a default) with
the note "changed since it was measured (source, range)". Provenance records written before
`value` existed are taken at their word.

The inspector shows a green **measured** badge next to measured settings (hover it for the
source, query, range and fit quality); editing a measured value makes it assumed again, and a
value changed outside the editor loses the badge too. Reports
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
used, so a measured input never cites a window its data did not come from. Times without an
offset are UTC.

The CLI and the MCP server read the mapping from a file you name. The API takes the mapping's
text (or an object) and never opens a path: a request whose `mapping` happens to name a file on
the server gets "the mapping must be a YAML or JSON object", not the file.

Each Prometheus query has one deadline covering the connection, the headers and the whole body
(30 s; `ARCHSIM_PROMETHEUS_TIMEOUT_S` in the API), so a slow or trickling server cannot hold a
calibration past it, and responses are capped at 50 MiB (`ARCHSIM_PROMETHEUS_MAX_BYTES`; use a
larger `step` or a shorter range for more). Redirects are not followed and proxy variables
(`HTTP_PROXY`) are not used: the URL is queried directly. A failed, cut-off or
non-Prometheus answer is a calibration error (422 in the API), and the message never repeats
what the server said; the CLI also prints Prometheus' own error text ("Prometheus said: …"),
since it runs for the person who named the server.

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
    cpuPerRequest:   # CPU in use over requests per second -> cpuShare
      cpu: sum(rate(container_cpu_usage_seconds_total{container="api"}[5m]))
      requests: sum(rate(http_server_request_duration_seconds_count{job="api"}[5m]))
    memoryWorkingSet: # -> memory at idle, per req/s and per request held
      workingSet: sum(container_memory_working_set_bytes{container="api"})
      requests: sum(rate(http_server_request_duration_seconds_count{job="api"}[5m]))
  cache:
    hitRatio:
      ratio: {hits: sum(rate(redis_keyspace_hits_total[5m])), misses: sum(rate(redis_keyspace_misses_total[5m]))}
  db:
    readServiceTimeMs: {histogram: sum by (le) (rate(pg_query_duration_seconds_bucket{op="read"}[5m])), unit: s}
    cpuPerRequest:
      cpu: sum(rate(container_cpu_usage_seconds_total{container="postgres"}[5m]))
      requests: sum(rate(pg_stat_statements_calls_total[5m]))   # queries per second
    workingSetGib: {gauge: max(pg_database_size_bytes{datname="app"}), unit: bytes, reduce: max}
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
| `histogram` | a distribution | the buckets averaged over the range give quantiles (linear inside a bucket, like `histogram_quantile`); `lognormal` matches the median and p95 exactly, `empirical` keeps a quantile table that ends at the first bucket bound holding every observation. See "Fit error" below. |
| `gauge` | a number (workers, connections, a size...) | the series summed per timestamp, then the mean, max, min or last value over the range; with `unit` (`bytes`, `KiB`, `MiB`, `GiB`) a memory or size is converted to the setting's unit (e.g. bytes to GiB for `workingSetGib`) |
| `cpuPerRequest` | `cpuShare` (services, databases) | `cpu` (CPU cores in use: the rate of CPU seconds) over `requests` (requests per second; queries for a database), see [CPU and memory](#cpu-and-memory) |
| `memoryWorkingSet` | a service's `memoryBaselineMb`, `memoryPerRpsMb` and `memoryPerRequestMb` | the working set per replica fitted against `requests` (the request rate), `inflight` (requests held) or both, see [CPU and memory](#cpu-and-memory) |
| `ratio` | a hit ratio | hits / (hits + misses), each averaged over the range; with no hits and no misses in the range nothing is measured, the hit ratio is left as it was (and stays assumed) and a note says so |
| link `client` / `server` | the link's latency | (client mean - server mean) / 2, one way, as a constant |
| `rate` | the workload | its mean becomes the baseline rate; with `trace: true` the curve becomes a `trace` pattern fitted into the run's existing duration: compressed when the range is longer than the run, stretched when it is shorter (the note says by how much). The run's duration and warm-up are never changed, so calibrating from a short range does not make later runs longer; both workload changes (rate and pattern) are listed. |

**Fit error.** For a lognormal fitted to a histogram it is the largest relative error at the
bucket bounds whose observed share lies between p50 and p99: where the fitted distribution puts
that share of observations, against the bound the histogram counted them under. Bucket bounds
are where a histogram is exact, so this does not depend on the linear interpolation the fit
itself started from (coarse buckets show up as a larger error). An `empirical` table built from
a histogram passes through every bucket bound by construction, so there is nothing independent
to check it against: its fit error is left empty and a note gives the widths of the buckets
holding p50, p95 and p99 instead. For trace samples the fit error compares fitted and sample
p50, p75, p90, p95 and p99. When observations fall above the highest finite bucket bound (the
`+Inf` bucket), the notes and the input's provenance say what share: a histogram cannot say how
far above, so quantiles there are taken as that bound (an `empirical` table spreads them up to
twice the bound). Add a higher bucket if that share matters.

Use handler time **without** downstream calls for `serviceTimeMs` (ArchSim adds the downstream
calls itself); total request time double-counts them. Parts or settings the mapping names but
the design lacks are reported, never guessed.

### CPU and memory

Sizes and costs follow the CPU and memory each request needs. Calibration fits them after every
other input (a CPU share depends on the fitted service time) and they count as measured inputs
in confidence like any other.

**CPU per request → `cpuShare`.** `cpu` is the CPU in use in cores (the rate of CPU seconds,
summed over replicas) and `requests` the requests per second the part serves (queries for a
database). CPU per request is their ratio over the whole range (mean cores ÷ mean rate), so the
busiest times, where a part is sized, weigh most. `cpuShare` is that over the mean service time
(a database: its read and write query times, weighted by the reads and writes it gets; the log
flush needs no CPU), at most 1: the rest of the service time waits on the worker without a core.
The fit error is the largest relative error of the fitted CPU at the points at and above the
median rate.

- **CPU per request falls as load rises** on real stacks: requests share event-loop wake-ups,
  system calls and driver work. On the pg-mixed benchmark (run 5: Node.js pinned to one CPU,
  PostgreSQL to two) the Node.js service used 0.48 ms per request at 560 req/s and 0.21 ms at
  2,231 req/s. Calibration fits that curve and, for a service, sets it as `serviceTimeByLoad`
  (schema 1.6): the points are sorted by the rate per replica (the part's rate over the design's
  replicas) into up to 6 bins of equal count, each bin's CPU per request is its CPU over its
  rate, and the closest curve that never rises with rate is kept (a rise is noise or contention,
  and a falling cost must not be extrapolated from it). Its factors are relative to the CPU per
  request over the whole range, the one `cpuShare` comes from. It is set only when the factors
  span more than 10%; the fit error is how far the never-rising curve is from a bin. On
  pg-mixed, the share alone predicts the CPU at the knee within 12% (before calibration ArchSim
  predicted a full core where 0.46 was used, +116%) and is 51% low at 560 req/s; with the curve
  every step below the knee is within 15%. The simulation keeps the last bin's factor above the
  range, so pick a range that reaches the load you size for. A service that autoscales, or runs
  serverless, gets a note instead (its rate per replica is not known), and so does a database
  (it has no such setting).
- **More CPU than service time.** CPU spent outside the handler time you measured (HTTP parsing,
  drivers, garbage collection) makes CPU per request larger than the service time. The share is
  then 1, a note says by how much the model under-counts, and the fit error shows it. Use a
  service time that covers the CPU per request; the benchmarks set a Node.js service time to its
  CPU per request for this reason.

**Working set → memory.** `workingSet` is the memory in use, summed over replicas (`unit`:
`bytes` by default). It is divided by the replicas (the design's count, or a `replicas` query for
a part that autoscales) and fitted by non-negative least squares as memory at idle
(`memoryBaselineMb`) plus memory per req/s (`memoryPerRpsMb`, against `requests`) and/or per
request held (`memoryPerRequestMb`, against `inflight`). No term may be negative. The fit
explains the whole working set, so a term it was not given is set to zero (memory per request
held to its 0.01 MiB floor): otherwise the model would count the memory of requests held twice.
The fit error is the largest relative error at and above the median rate.

- **Garbage-collected runtimes** (Node.js, the JVM, Go, .NET) keep what requests allocate until
  the next collection, so their working set follows the request rate, not the requests held. On
  pg-mixed the Node.js working set went from 43 to 188 MiB between 560 and 2,231 req/s, and did
  not grow with requests held even under overload. Fitted against the rate (0.077 MiB per req/s)
  it predicts every step below the knee within 25%; fitted against requests held it is off by
  up to 118%, and ArchSim's defaults (256 MiB at idle + 2 MiB per request held) predicted 550 MiB
  at the knee. Memory per req/s counts in the memory a replica uses and is sized for, not in the
  out-of-memory limit, since a collector reclaims it under pressure.
- **Requests held** (`inflight`) suit thread-per-request servers with large per-request buffers.
  Give both only when the range includes times when requests held rise without the rate (slow
  dependencies, overload); under normal load they rise together and the split is noise.
- **Databases.** PostgreSQL's process working set stayed at about 95 MiB at every rate on pg-mixed: it
  is shared buffers and connections, not something that follows load. `workingSetGib` is the
  hot data the reads touch, a gauge: the size of the hot tables and their indexes, or the
  database size as an upper bound.

Example PromQL (rename the labels to your own):

| What | Kubernetes (cAdvisor, kube-state-metrics) | A process (Prometheus client libraries) |
| --- | --- | --- |
| `cpu` | `sum(rate(container_cpu_usage_seconds_total{namespace="shop", container="api"}[5m]))` | `sum(rate(process_cpu_seconds_total{job="api"}[5m]))` |
| `workingSet` | `sum(container_memory_working_set_bytes{namespace="shop", container="api"})` (not `container_memory_usage_bytes`, which counts the page cache) | `sum(process_resident_memory_bytes{job="api"})` |
| `replicas` | `sum(kube_deployment_status_replicas_available{namespace="shop", deployment="api"})` | `count(up{job="api"} == 1)` |
| `requests` | `sum(rate(http_server_request_duration_seconds_count{service_name="api"}[5m]))` (OpenTelemetry HTTP metrics) | `sum(rate(http_requests_total{job="api"}[5m]))` |
| `inflight` | `sum(http_server_active_requests{service_name="api"})` | your server's in-flight gauge |
| database `requests` | `sum(rate(pg_stat_statements_calls_total[5m]))` (postgres_exporter's `stat_statements` collector) | |
| `workingSetGib` | `max(pg_database_size_bytes{datname="app"})` with `unit: bytes` (an upper bound), or a custom query for the hot tables | |

Use the same `rate()` window for `cpu` and `requests`, so both describe the same seconds.

### The replay check

After applying the mapping, ArchSim simulates the calibrated design and compares it with the
`replay` targets: end-to-end p50, p95 and p99 (and throughput), and each listed part's latency
(the time requests spend in it, including its downstream calls). Every part whose CPU or working
set was fitted is compared too, with no `replay` entry needed: its mean CPU in use over all
replicas (`cpuCores`) and its memory peak per replica (`memoryMiB`, against the highest working
set observed), over the part of the range the run's measurement window covers. These need the
workload calibrated from the same range (`workload.rps`); otherwise the run's own traffic would
not match what they used, and a note says so. The document keeps the result (`calibration`) so
reports can show it later. The one-line summary gives the end-to-end p95 and
the largest error of all the checks, so a close p95 cannot hide a poor p99 or part.

The replay is an ordinary run of the calibrated design, under the same limits as any run. The
API runs it inside the request, so it also gets a shorter wall clock
(`ARCHSIM_CALIBRATE_MAX_WALL_SECONDS`, 60 s). A replay that would exceed a limit is a clean
error (422 in the API; the CLI says so and suggests `--no-replay`), never a crash.

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
cpuTime: {attribute: app.cpu_time_ms, unit: ms}   # optional; unit s, ms, us or ns
```

Where spans allow it, traces also give a service's CPU share: when SERVER spans record the CPU
time they used in an attribute (OpenTelemetry has no standard one, so name the attribute your
instrumentation writes in `cpuTime`), the share is their CPU time over their own time. Spans
carry no memory: fit memory from metrics.

The replay simulates the calibrated design at the traced request rate (for the design's
duration, at least 60 s and at most 300 s) and compares each mapped service's latency with its
SERVER spans. Traces are usually sampled, so the rate and tails are rougher than metrics. Parent
links that loop (a span that is its own parent, or a cycle) are ignored and counted in the
notes.

## Example

`packages/engine/tests/fixtures/calibration/` holds a calibration of the cache-aside starter:
a mapping and Prometheus responses **generated** from known parameters
(`scripts/make_calibration_fixture.py`), plus an OTLP export generated the same way
(`scripts/make_otlp_fixture.py`). Calibrating the starter from them recovers the parameters
(median and p95 within 5%, hit ratio, memory and the database's working set exactly, CPU shares
within 2%; the OTLP spans' CPU share exactly).

The replay check on this fixture is **circular** and proves less than it seems: the "observed"
end-to-end and database latencies it compares against were produced by simulating the true
parameters with ArchSim itself. A close replay therefore shows that calibration recovers known
inputs and that the pipeline is wired correctly, not that ArchSim predicts a real system. On
it the end-to-end p95 comes within 6% (under), CPU and memory within 4%, but the largest error
is -13% (end-to-end p99); the observed curve (2 minutes) is compressed into the starter's 60 s
run. The CPU and memory fits against a real stack are checked on the pg-mixed benchmark's
committed results (`benchmarks/tests/test_resource_fit.py`). Only a calibration
against your own telemetry says how well the model fits your system.

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
  traces, a design from traces (the call graph is reported, not drawn), CPU and memory of caches
  and queues.
- One CPU cost per request: CPU per request that falls (or rises) with load is reported, not
  modelled (see [CPU and memory](#cpu-and-memory)).
- The API's `/calibrate` calls the Prometheus URL it is given only as `ARCHSIM_OUTBOUND`
  allows. Without sign-in (local use) any host is allowed, or only
  `ARCHSIM_PROMETHEUS_ALLOWED_HOSTS` when set. A hosted API (sign-in on) calls none unless
  hosts are listed, and then only those, each name resolving to public addresses only (no
  loopback, private or cloud-metadata addresses; the call goes to the address checked); sandbox
  workspaces never call out. Where the API may not call your Prometheus, record the responses
  with `archsim calibrate --record` on a machine that can reach it and send those instead
  (see [SECURITY.md](../SECURITY.md)).
