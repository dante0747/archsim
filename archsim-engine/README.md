# archsim-engine

The engine's deterministic parts in Java: what ArchSim works out from a design without simulating
it, and from a simulation's report. It is a plain library with no framework, over the design
model in [`archsim-model`](../archsim-model/README.md)
([ADR-0012](../docs/adr/0012-separate-model-and-engine-modules.md)). It is stage 3 of the
[Java port](../docs/proposals/java-25-port.md#stage-3--the-engines-deterministic-parts-12-weeks):
each part replaces a call of the Python engine service
([ADR-0036](../docs/adr/0036-python-engine-service-for-synchronous-calls.md)) once it matches the
frozen Python engine's [golden files](../parity/README.md). The simulation kernel is stage 4.

## Packages

| Package | What |
| --- | --- |
| `numeric` | arithmetic as Python does it: `ExactSum` (`math.fsum`), `FloatSum` (the built-in `sum()` of floats, compensated since Python 3.12), `Median` |
| `text` | how the engine writes numbers: `NumberText` (formulas, percentages, short figures, latencies) and `HalfUp` (rounding halves away from zero, as the Python engine does, and JavaScript's `Math.round` as the Python engine computes it) |
| `queueing` | the Erlang C formula, the wait in an M/M/c queue, Zipf key popularity |
| `load` | the load each part carries without a run: `LoadEstimator` (clients' shares, balancers by replicas, caches' misses, cache-aside write-backs; TTL caches' miss shares), `BaseRate` beside the workload's peak, `TtlCacheTraffic` (a TTL cache's hit ratio) |
| `settings` | reading a part's settings where the Python engine read the raw settings of a design that may not be valid yet: `LenientSettings` ([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md)), `LoadCurve` (service time by load) |
| `pricing` | instance types from price snapshots: `MachineKind` (which price list), `InstanceTypes` (the cheapest type that covers a size, the part's own type), `Busy` (burstable types' CPU credits), `Purchase`, `MachineSize`, `Machine` |
| `host` | the design's host machine (schema 1.7): `HostMachine` (threads that slow each other down), `PartWork`, `HostSpeeds` |
| `provenance` | where each input came from; `ValueText`, how its value reads |
| `confidence` | how far a result can be trusted; `ConfidenceLevel` |
| `seeds` | ranges across seeds: `ValueRange`, `Seeds` |
| `sweep` | load sweeps: `SweepRange`, its levels and the highest load a step can run |
| `simulation` | where the engine meets the simulator: `SimulationLimits` |
| `report` | reading a report: `ReportDigest` |

Where a Python function parses a node's config, the Java reads it as its type's record; where it
reads the raw settings of a design that may not be valid yet (the load estimate, which kind of
machine a part runs on, the CPU work on the design's host), the Java reads them through
`LenientSettings`, with the same lenient rules
([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md)).

## Tests

```bash
./gradlew :archsim-engine:test
```

- `GoldenParityTests` runs every recorded call of each Python function this module ports, in
  every golden file, and expects the same output: text exactly, numbers to a relative 1e-9.
  `EngineFunctions` maps each Python name to its Java call, through one table per area
  (`TextFunctions`, `QueueingFunctions`, `LoadFunctions`, `PricingFunctions`, `SeedFunctions`,
  `SweepFunctions`). Ported so far: the load estimate (`load.estimate_load`, `ttl_miss_shares`,
  `base_rps`, and `peak_rps`, whose counterpart is the model's `Workload.peakRate()`) for every
  case of every template; `pricing.Snapshot.cover` and `machine_kind`; and the number
  formats and small numeric helpers of `catalog/formatting` (`explain.num`, `pct`, `erlang_c`,
  `zipf_share`; `load.js_num`, `fmt_int`, `fixed`, `round_half_up`, `ttl_hit_ratio`, and
  `mean_ms`, whose counterpart is `LenientSettings.meanMs`;
  `sizing.fmt`; `provenance.describe_value`; the `seeds` text helpers; `estimate.mmc_wait`;
  `sweep.suggest_range`, `rps_cap`, `levels`; `report.canonical_digest`).
- The other tests specify each class.
- `ArchitectureTests` checks that the engine uses no framework or database class, and that its
  packages have no cycles.
