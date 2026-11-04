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
| `estimate` | the instant estimate: `InstantEstimator` (what textbook queueing formulas predict for a design at its peak, without a run: each part's utilization and wait, each request path's latency and a rough p95, where an average request's time goes, and where the design saturates: `InstantEstimate`) |
| `scenario` | `ScenarioResolver`: the document a run simulates, a design or one of its scenarios with its defaults in full, its prices pinned and its empty specs auto-sized (`ResolvedDesign`) |
| `sizing` | auto-sizing: `AutoSizer` (each part's CPU, memory and storage for the design's own peak, snapped to an instance type or a standard shape, with what it needs and why: `PartSize`), one sizer per kind of part, `BaseBusy` |
| `numeric` | arithmetic as Python does it: `ExactSum` (`math.fsum`), `FloatSum` (the built-in `sum()` of floats, compensated since Python 3.12), `PythonRound` (`round(x, n)`), `Median`, `Erf` (the complementary error function, as fdlibm and Python compute it) |
| `text` | how the engine writes numbers: `NumberText` (formulas, percentages, short figures, latencies) and `HalfUp` (rounding halves away from zero, as the Python engine does, and JavaScript's `Math.round` as the Python engine computes it) |
| `queueing` | the Erlang C formula, the wait in an M/M/c queue, `Moments` of a time (its distribution's, or calls made on average), the p95 of a mix of times (`LognormalMixture`), Zipf key popularity |
| `load` | the load each part carries without a run: `LoadEstimator` (clients' shares, balancers by replicas, caches' misses, cache-aside write-backs; TTL caches' miss shares), `BaseRate` beside the workload's peak, `TtlCacheTraffic` (a TTL cache's hit ratio) |
| `settings` | reading a part's settings where the Python engine read the raw settings of a design that may not be valid yet: `LenientSettings` ([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md)), `LoadCurve` (service time by load) |
| `pricing` | instance types from price snapshots: `MachineKind` (which price list), `InstanceTypes` (the cheapest type that covers a size, the part's own type), `Busy` (burstable types' CPU credits), `PricingChoice` (a design's snapshot and purchase), `Purchase`, `MachineSize`, `Machine` |
| `host` | the design's host machine (schema 1.7): `HostMachine` (threads that slow each other down), `PartWork`, `HostSpeeds` (on the host, or on a part's own hyperthreads) |
| `provenance` | where each input came from: `InputProvenances` (each performance input of a design, measured, documented, assumed or illustrative, with its value: `Input`, `Provenance`), read from the model's `ComponentFacts`; `ValueText` (how a value reads) |
| `technology` | each part's technology and its tier: `TechnologyTiers` |
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

Where the Python engine fails, the Java decides for itself, and its tests say how:

- A link to a part that does not exist: the Python estimate raises `KeyError`; the Java estimate
  leaves the calling part and the latency unestimated, so a half-drawn design still gets an
  estimate (validation reports the link).
- A rate-limited part first among parts that use none of their limits at the peak: the Python
  summary raises `TypeError` (it has no saturation point to write); the Java estimate throws
  `IllegalStateException`.

## Tests

```bash
./gradlew :archsim-engine:test
```

- `GoldenParityTests` runs every recorded call of each Python function this module ports, in
  every golden file, and expects the same output: text exactly, numbers to a relative 1e-9.
  `EngineFunctions` maps each Python name to its Java call, through one table per area
  (`TextFunctions`, `QueueingFunctions`, `LoadFunctions`, `PricingFunctions`, `SeedFunctions`,
  `SweepFunctions`, `SizingFunctions`, `InputFunctions`, `EstimateFunctions`). Ported so far:
  - the instant estimate for every case of every template and of the invalid documents
    (`estimate.estimate_design`), and its `METHOD` and `NOT_INCLUDED`;
  - for every case of every template and of the invalid documents, where each input came from
    (`provenance.input_provenance`, `provenance_counts`), each part's technology tier
    (`ComponentRegistry.technology_tiers`) and the model a run simulates
    (`scenarios.simulation_input`, whose counterpart is the model's
    `DesignDocuments.simulationInput`);
  - resolving every case of every template and of the invalid documents
    (`scenarios.resolve_scenario_sized`), auto-sizing (`sizing.auto_sizes`, `apply_auto_sizes`,
    `base_busy`) and `sweep.sweep_range`;
  - the load estimate for every case of every template (`load.estimate_load`,
    `ttl_miss_shares`, `base_rps`, and `peak_rps`, whose counterpart is the model's
    `Workload.peakRate()`); `pricing.Snapshot.cover` and `machine_kind`;
  - the number formats and small numeric helpers of `catalog/formatting` (`explain.num`, `pct`,
    `erlang_c`, `zipf_share`; `load.js_num`, `fmt_int`, `fixed`, `round_half_up`,
    `ttl_hit_ratio`, and `mean_ms`, whose counterpart is `LenientSettings.meanMs`; `sizing.fmt`;
    `provenance.describe_value`; the `seeds` text helpers; `estimate.mmc_wait`;
    `sweep.suggest_range`, `rps_cap`, `levels`; `report.canonical_digest`).
- Where the golden files do not reach a branch (a design on a host machine, a pod bigger than
  its node, an instance type set on the part, a size no type covers, an input measured before,
  and more), the unit tests expect the values and words the Python engine gives. The instant
  estimate's such cases are in `estimate/cases.json`, each a resolved design with the Python
  engine's estimate, which `scripts/export_estimate_cases.py` writes (`--check` compares a fresh
  export); `numeric/erfc.csv` holds Python's `math.erfc` at points over every interval of the
  approximation, from `scripts/export_erfc_points.py`.
- The other tests specify each class.
- `ArchitectureTests` checks that the engine uses no framework or database class, and that its
  packages have no cycles.
