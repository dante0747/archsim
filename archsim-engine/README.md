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
| `text` | how the engine writes numbers: `NumberText` (formulas, percentages, short figures, latencies) and `HalfUp` (rounding halves away from zero, as the web app does) |
| `queueing` | the Erlang C formula, the wait in an M/M/c queue, Zipf key popularity |
| `load` | the load each part carries; `TtlCacheTraffic`, a TTL cache's hit ratio |
| `provenance` | where each input came from; `ValueText`, how its value reads |
| `confidence` | how far a result can be trusted; `ConfidenceLevel` |
| `seeds` | ranges across seeds: `ValueRange`, `Seeds` |
| `sweep` | load sweeps: `SweepRange`, its levels and the highest load a step can run |
| `simulation` | where the engine meets the simulator: `SimulationLimits` |
| `report` | reading a report: `ReportDigest` |

## Tests

```bash
./gradlew :archsim-engine:test
```

- `GoldenParityTests` runs every recorded call of each Python function this module ports, in
  every golden file, and expects the same output: text exactly, numbers to a relative 1e-9.
  `EngineFunctions` maps each Python name to its Java call, through one table per area
  (`TextFunctions`, `LoadFunctions`, `SeedFunctions`, `SweepFunctions`). Ported so far: the number
  formats and small numeric helpers of `catalog/formatting` (`explain.num`, `pct`, `erlang_c`,
  `zipf_share`; `load.js_num`, `fmt_int`, `fixed`, `round_half_up`, `ttl_hit_ratio`, and
  `mean_ms`, whose counterpart is the model's `Distribution.expected()`;
  `sizing.fmt`; `provenance.describe_value`; the `seeds` text helpers; `estimate.mmc_wait`;
  `sweep.suggest_range`, `rps_cap`, `levels`; `report.canonical_digest`).
- The other tests specify each class.
- `ArchitectureTests` checks that the engine uses no framework or database class, and that its
  packages have no cycles.
