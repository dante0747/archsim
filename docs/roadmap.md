# Roadmap and extension seams

These are directions the design leaves room for, with the seam in the current code that each
would build on. Where part of an item has shipped, its section says so; everything else on
this page is not implemented. Priorities are open for discussion in issues.

## 1. Calibration from telemetry (Prometheus)

**Why it matters most.** Today every service time, hit ratio and latency is a number the user
typed. A model built on guesses can be internally consistent and still far from production:
real handlers have multi-modal latency, caches have eviction, databases slow down under lock
contention, and traffic is burstier than Poisson. Calibration is what turns "what would happen
in this model" into "what is likely to happen in our system". Until then, ArchSim's numbers
are educational estimates.

Planned approach:

- A calibration job queries Prometheus (read-only, user-supplied URL and PromQL) over a chosen
  time range and fits model parameters:
  - **service-time distributions** from latency histograms (`*_duration_seconds_bucket`),
    preferably server-side handler time excluding downstream calls, fitted to lognormal
    (median/p95) or an empirical distribution;
  - **capacity** from worker or connection-pool gauges (e.g. busy threads,
    `pg_stat_activity` counts, pool max size) and queue-length gauges;
  - **arrival patterns** from request-rate counters (`rate(http_requests_total[1m])`), as a
    replayable rate curve instead of a constant/ramp/burst;
  - **cache hit ratio** from hit/miss counters; **network latency** from client-side minus
    server-side timings.
- Fitted values are written into the document **with provenance** (source query, time range,
  fit quality) and labelled *measured* rather than *assumed* in the UI and reports.
- A validation step replays the observed traffic through the calibrated model and reports the
  error against observed latency and throughput, so users see how far to trust it.

Seams this was planned on, which calibration now uses: the `@Ui(assumption = true)` hint
distinguishes asserted inputs; distributions are a tagged union in archsim-model's
`distribution` package (it gained an `empirical` kind); the workload `pattern` union gained a
`trace` kind.

## 2. Grafana export

Export a run's time series as a Grafana-compatible JSON data source or dashboard, and/or link
results next to real dashboards. Reports already contain bucketed time series with fixed
`bucketS`, which map directly onto Grafana panels.

## 3. API and MCP tools for AI agents

Agents could draft architectures, validate them, run experiments and explain bottlenecks.
Constraints that any such surface must keep:

- **Never invent results.** Every number an agent reports must come from a stored run whose id
  and `resultDigest` it can cite. Explanations come from the existing rule-based findings and
  comparisons.
- **Same guard rails as humans.** Agents use the same validated endpoints
  (`/validate`, `/projects/{id}/runs`, `/compare`) with the same request-count, wall-clock and
  concurrency limits. No code execution, no arbitrary plugins.
- **Actionable errors.** Validation issues already carry stable codes, node/edge ids and hints,
  which is what a tool-using model needs to fix a document.

Done (2026-10-26): WebMCP tools in the web app, for agents in the browser, and an MCP server
over stdio for desktop and coding agents (first in Python, `packages/mcp`; in Java,
`archsim-mcp`, since stage 5 of the Java port), using the engine directly, both in
[agents.md](agents.md); results carry the run id and result digest. Still open: an HTTP
transport for the MCP server, which needs authentication before it is exposed beyond localhost.

## 4. More component types

Each is a new component type (see [`extending-components.md`](extending-components.md)).

Shipped: message queues with asynchronous consumers (Kafka, RabbitMQ, SQS-style and others), a
CDN edge cache, an API gateway rate limit (token bucket on the load balancer), object storage as
an external API (latency and limits only), and scheduled failures (outage, slowdown, link
latency). Still open:

- **Object storage** with throughput limits and size-dependent latency.
- **API gateway** authentication cost.
- **Service discovery / DNS** latency and caching.
- **Circuit breaker** as link or component behaviour (today only Envoy-style request limits).
- **Regions and zones** with cross-region latency.
- **Failure injection** beyond the three scheduled kinds: instance crashes, partitions, random
  failures.
- Parallel fan-out calls from a service.

## 5. Nested architectures (subsystems)

Allow a node to contain its own graph ("payments subsystem") with ports mapped to inner
nodes, and switch between levels in the editor. Seams: node ids and the separate `layout`
section leave room for a `children` graph per node and per-level layouts; validation and the
engine would flatten nested graphs before simulation.

## 6. Collaboration and access control

Done: accounts through GitHub or Google sign-in, personal and team workspaces with viewer, editor
and owner roles, invitations and API tokens, comments on parts, links and runs, sign-offs on runs,
a history of every change to a project and its workspace, and read-only share links
([api.md](api.md#sign-in-workspaces-and-tokens), [deployment.md](deployment.md)). Projects carry a
`revision` with optimistic concurrency (`expectedRevision`), and every run stores its exact input.

## 7. Scalable job execution

Done: runs are queued in the database with leases, so they survive API restarts, cancel works
from any instance, and `archsim-app worker` processes on any number of machines share the queue
through PostgreSQL, each run on a thread of its worker
([ADR-0053](adr/0053-simulate-runs-in-the-java-apps-worker-role.md)); a claim keeps each
workspace under its cap of runs at once. Next, if a single database becomes the limit: a broker
(Redis, NATS) for wake-ups instead of polling once a second.

## 8. Engine performance

The Java engine simulates 27,000 to 316,000 requests per second on one core, 64,000 at the
templates' median ([performance.md](performance.md)), about 14 times the Python SimPy engine it
replaced (the custom event core this section once proposed). Options if larger models are
needed: running independent scenarios or replications on several threads, or variance-reduction
techniques (common random numbers) to need fewer simulated requests per comparison.
