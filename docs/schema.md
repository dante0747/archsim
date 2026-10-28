# Architecture document format (schema 1.1)

An ArchSim project is one human-readable JSON document. It is what the editor saves, what
`GET /api/v1/projects/{id}/export` downloads and what `POST /api/v1/projects/import` accepts.
The machine-readable JSON Schema is served at `GET /api/v1/schema/architecture` (and printed by
`uv run archsim schema`). The Pydantic models are in
`packages/engine/src/archsim_engine/schema.py`.

Conventions: keys are camelCase; unknown keys are rejected; times in component and link
settings are **milliseconds** (`...Ms`), workload times are **seconds** (`...S`), rates are
requests per second.

## Annotated example

A trimmed version of `examples/cache-aside-starter.json` (comments are not valid JSON; they
are here for explanation only):

```jsonc
{
  "schemaVersion": "1.4",                 // required; drives migrations
  "kind": "archsim.architecture",
  "metadata": { "name": "Cache-aside web API", "description": "...", "tags": ["starter"] },

  "architecture": {                        // topology: what exists, how requests flow
    "nodes": [
      { "id": "clients", "type": "traffic_source", "label": "Clients",
        "config": { "timeoutMs": 2000, "maxAttempts": 1 } },
      { "id": "api", "type": "service", "label": "API Servers",
        "config": { "replicas": 2, "workersPerReplica": 4, "queueCapacity": 50,
                    "serviceTimeMs": { "kind": "lognormal", "median": 8, "p95": 25 } } },
      { "id": "cache", "type": "cache", "label": "Redis Cache",
        "config": { "hitModel": "fixed_ratio", "hitRatio": 0.8 } },
      { "id": "db", "type": "database", "label": "PostgreSQL",
        "config": { "maxConnections": 20 } }
    ],
    "edges": [
      { "id": "clients-api", "source": "clients", "target": "api",
        "config": { "requestTypes": ["read", "write"],
                    "latencyMs": { "kind": "lognormal", "median": 15, "p95": 40 } } },
      { "id": "api-cache", "source": "api", "target": "cache",   // lookups + invalidations
        "config": { "requestTypes": ["read", "write"], "timeoutMs": 250, "order": 1,
                    "continueOnFailure": true } },  // cache down: reads go to the DB instead
      { "id": "api-db", "source": "api", "target": "db",         // cache-aside: the API reads
        "config": { "requestTypes": ["read", "write"], "order": 0,  // the DB itself on a miss;
                    "readsOnMiss": true } }                         // writes go to the DB first
    ]
  },

  "workload": { "rps": 400, "durationS": 60, "warmupS": 10, "seed": 42, "readRatio": 0.9 },

  "scenarios": [                            // replayable variants of the base document
    { "id": "three-api-replicas", "name": "3 API replicas",
      "overrides": [ { "target": { "kind": "node", "id": "api" },
                       "path": "replicas", "value": 3 } ] }
  ],

  "layout": {                               // rendering only; never affects results
    "nodes": { "clients": { "x": 0, "y": 160 }, "api": { "x": 560, "y": 160 } }
  }
}
```

Omitted config fields take the component defaults. When a document is simulated, defaults are
materialised and the fully resolved document is stored in the report's `input`.

## Top level

| Field | Type | Notes |
| --- | --- | --- |
| `schemaVersion` | `"1.4"` | Required. Older versions are migrated; newer versions are rejected. |
| `kind` | `"archsim.architecture"` | |
| `metadata` | object | `name` (1-120 chars), `description`, `tags` (max 20). |
| `architecture` | object | `nodes` (max 200), `edges` (max 1,000). |
| `workload` | object | See below. Every field has a default. |
| `scenarios` | array | Max 50. |
| `objectives` | object | Service-level objectives (see below). Optional; defaults apply. |
| `layout` | object | `nodes: {id: {x, y}}`, optional `edges: {id: {sourceSide, targetSide}}` (each `top`, `right`, `bottom` or `left`; where a link attaches on the canvas) and optional `viewport: {x, y, zoom}`. Rendering only. |
| `provenance` | object | 1.1. Where inputs came from, keyed by path: `nodes/<id>/<config key>`, `edges/<id>/<config key>` or `workload/<field>`. Each: `kind` (`measured`, `assumed`, `illustrative`), `source`, `query`, `timeRange`, `fitError`, `fittedAt`, `note`. Inputs without an entry are assumed when they differ from the default (and the technology preset), illustrative otherwise. See [calibration.md](calibration.md). |
| `calibration` | object or null | 1.1. The last calibration from telemetry: `at`, `source`, `timeRange`, `inputs` (how many were set), `replay` (observed against simulated: `scope`, `id`, `label`, `metric`, `observed`, `simulated`, `error`), `replayDigest`, `notes`. |
| `pricing` | object | 1.2. Where prices come from: `provider` (`generic`, the default: illustrative rates; or a price snapshot's provider), `region`, `purchaseOption` (`on_demand`), `families` (instance families to snap to), `snapshotDate` (optional `YYYY-MM-DD`: use exactly that snapshot, never a newer one), `snapshotSha256` (optional, with `snapshotDate`: the snapshot's content hash). With a snapshot, auto sizes snap to the cheapest instance type that covers them and cost lines cite it; a run's `input.pricing` records the snapshot used (date and hash). Unset `snapshotDate`/`snapshotSha256` are left out of the JSON. See [pricing.md](pricing.md). |

## Nodes

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Unique; letters, digits, `_`, `.`, `-`; max 64 chars; starts with a letter or digit. |
| `type` | string | A registered component type: client groups `traffic_source`, `mobile_app`, `web_client` and `api_consumer`; `load_balancer`, `service`, `cache`, `database`, `message_queue`, `external_api` (or a plugin). A design needs at least one client group; several split the workload by their `trafficShare`. |
| `label` | string | Display name, max 120. |
| `description` | string? | |
| `config` | object | Validated by the component type. The full list of fields, units, ranges and defaults for each type is returned by `GET /api/v1/components` (`uv run archsim catalog`). |

## Edges (links)

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `id` | string | | Same rules as node ids. |
| `source`, `target` | string | | Node ids. |
| `sourcePort` | string | `"out"` | Must be an output port of the source (the cache's miss port is `"miss"`, for read-through caching). |
| `targetPort` | string | `"in"` | Must be an input port of the target. |
| `label` | string? | | |
| `config.requestTypes` | `["read" \| "write"]` | both | Which requests use this link. Drives routing (e.g. reads to the cache, writes to the database). |
| `config.latencyMs` | distribution | constant 0.5 | One-way network latency, applied to the request and again to the response. |
| `config.timeoutMs` | number? | none | Per-attempt time the caller waits (max 600,000). Ignored on the link from the traffic source. |
| `config.maxAttempts` | integer 1-5 | 1 | Attempts including retries. Ignored on the link from the traffic source. |
| `config.retryBackoffMs` | number | 50 | Backoff before the first retry; doubles each retry. |
| `config.order` | integer | 0 | Call order when a service calls several links for the same request (lower first). |
| `config.readsOnMiss` | boolean | false | Cache-aside, on a service's link to a data store: reads use it only after the service's cache lookup missed (or failed on a link that carries on), and the value is then written back to the cache. Writes on the link are always sent. |
| `config.continueOnFailure` | boolean | false | On a service's link: if the call still fails after its attempts, the request carries on. A failed cache lookup counts as a miss (reads go to the `readsOnMiss` links; with none, the read fails); a failed invalidation is skipped. |

## Distributions

Used for service times and latencies, always in milliseconds:

| `kind` | Fields | Notes |
| --- | --- | --- |
| `constant` | `value` | |
| `exponential` | `mean` | Memoryless; standard deviation equals the mean. |
| `uniform` | `min`, `max` | `max >= min`. |
| `lognormal` | `median`, `p95` | `p95 >= median`. Right-skewed; a common shape for handler times. |
| `empirical` | `quantiles` | 1.1. A measured quantile table `[[p, ms], ...]`: `p` rises strictly from 0 to 1 (both included), values never fall; linear between points. Written by calibration. |

## Workload

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `rps` | number 0-100,000 | 100 | Baseline offered load. |
| `durationS` | number (0, 3600] | 60 | Arrival window. |
| `warmupS` | number | 10 | Excluded from summary statistics; must be shorter than `durationS`. |
| `seed` | integer 0-2^32-1 | 42 | Reproducibility. |
| `readRatio` | number 0-1 | 0.9 | Fraction of reads. |
| `arrivalProcess` | `"poisson"` \| `"deterministic"` | `"poisson"` | Random vs evenly spaced arrivals. |
| `pattern` | object | `{"kind": "constant"}` | `constant`; `ramp {startRps, endRps}` (replaces `rps`); `burst {burstRps, startS, durationS}` (burst must start inside `durationS`); `trace {points: [[t, rps], ...], source}` (1.1: a replayed rate curve from `t = 0`, linear between points, flat after the last; written by calibration). |
| `keyCount` | integer 1-1,000,000 | 100,000 | Distinct keys (used by TTL caches). |
| `keySkew` | number 0-3 | 0.99 | Zipf exponent of key popularity (0 = uniform). |
| `drainS` | number 0-600? | automatic | Time after arrivals stop for in-flight requests to finish. |
| `faults` | array (max 20) | `[]` | Faults injected during the run (chaos experiments), see below. |

### Faults

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `id` | string | | Unique within the workload. |
| `kind` | `"outage"` \| `"slowdown"` \| `"latency"` | | |
| `target` | string | | Node id for `outage`/`slowdown` (not the traffic source); edge id for `latency`. |
| `startS` | number 0-3600 | | Start, in simulated seconds. Must be inside the run. |
| `durationS` | number (0, 3600] | | Length of the window. A window that runs past the end of the run is clipped (warning). |
| `factor` | number 1-100 | 4 | `slowdown`: service/processing times sampled at the target are multiplied by this. |
| `extraMs` | number 0-60,000 | 200 | `latency`: added to every one-way traversal of the link. |
| `label` | string? | | |

An `outage` makes the target refuse every call that reaches it inside the window (rejection
reason `outage`, counted at the target); work already in progress finishes. Faults live in the
workload so a scenario can carry an experiment: override `faults` with a list.

## Objectives

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `latencyP95Ms` | number? | 300 | p95 end-to-end latency target. |
| `latencyP99Ms` | number? | none | p99 target. |
| `availability` | number 0-1? | 0.999 | Minimum share of requests that succeed in the measured window. |
| `minThroughputRps` | number? | none | Minimum successful requests per second. |

Objectives never change the simulation; the UI evaluates runs against them (scorecard, error
budget, breach markers on the replay timeline, experiment verdicts). `null` disables a target.

## Scenarios and overrides

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Same rules as node ids. |
| `name` | string | 1-120 chars. |
| `description` | string? | |
| `overrides` | array (max 100) | Applied in order to the base document. |
| `overrides[].target` | `{kind: "node" \| "edge", id}` or `{kind: "workload"}` | |
| `overrides[].path` | string | Dotted path inside the node/edge `config` or the workload, e.g. `replicas`, `serviceTimeMs.median`, `pattern`, `pattern.burstRps`. |
| `overrides[].value` | any JSON | Replaces the value at `path`. The result is validated like any document. |

Overrides referring to a missing node, edge or field are rejected with
`scenario.invalid_override`. The run's report records the scenario id, name and the exact
overrides in `provenance`.

## Versioning and migrations

- `schemaVersion` follows `MAJOR.MINOR`. The engine supports documents up to its
  `SCHEMA_VERSION` (currently `1.4`). 1.0 to 1.1 only adds optional fields (`provenance`,
  `calibration`, the `empirical` distribution and the `trace` pattern), 1.1 to 1.2 adds
  `pricing`, 1.2 to 1.3 the cache-aside link settings, and 1.3 to 1.4 a part's optional
  `instanceType`, so older documents load unchanged.
- `instanceType` (1.4; services on VMs or Docker, databases, caches, load balancers and queues):
  the instance type a part runs on in the design's price snapshot (e.g. `c6g.xlarge`). It is
  used while it covers the part's CPU and memory; empty, too small or not in the snapshot (a
  warning, `pricing.unknown_instance_type`), the part runs on the cheapest type that covers
  them, as before. Auto-sizing writes the type it chose into a run's input, so running that
  input again reproduces the same types and cost; a scenario that changes a part's CPU or memory
  gets the type that fits its new size. The Terraform importer fills it from `instance_type`,
  `instance_class` and `node_type`.
- On load, `migrate_document()` (`packages/engine/src/archsim_engine/migrations.py`) applies
  registered migrations one step at a time (`1.0 -> 1.1 -> ...`) and records notes. A document
  without `schemaVersion`, from a newer schema, or with no migration path is rejected with a
  clear message instead of being guessed at.
- Policy for schema changes: bump `SCHEMA_VERSION`, update `ArchitectureDocument`, register a
  `@migration("old", "new")` function that upgrades raw JSON, and add a test that an old
  document still loads and simulates. Never change the meaning of an existing field without a
  migration.
- Import always migrates; export always writes the current version.
