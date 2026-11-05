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
| `cost` | the cost estimate, before a run or after one: `CostEstimator` (each part's price per instance-hour, set on it, a price snapshot's instance type, a preset's, its size's at generic rates or the default; managed and per-request parts at the snapshot's rates; implied Kubernetes nodes; burstable types' surplus CPU credits: `CostEstimate`; after a run, `estimateAfterRun` prices per-request parts at each part's rate the run measured at the base load, `MeasuredUsage`, autoscaled services and clusters at their average active instances, and gives the cost per million successful requests), `SizePricing` (the price of a part's size) |
| `estimate` | the instant estimate: `InstantEstimator` (what textbook queueing formulas predict for a design at its peak, without a run: each part's utilization and wait, each request path's latency and a rough p95, where an average request's time goes, and where the design saturates: `InstantEstimate`) |
| `analysis` | the static analysis of a design: `DesignAnalyzer` (its sizes, cost, review, technology tiers, inputs, instant estimate and confidence, and whether a quick check can answer it, as a run would use them: `DesignAnalysis`, which `POST /analyze` answers) |
| `run` | preparing a run before it is queued: `RunPreparer` (the design resolved, its seed replaced, validated, and its requests counted against the run's limit: `PreparedRun`, or `SimulationLimitException`), `QuickCheck` (whether sampled mode can answer a run) |
| `review` | the design review: `DesignReview` (an architecture linter over a design as a run simulates it: single points of failure, missing timeouts, stacked retries, timeout inversion, pool exhaustion, unbounded queues, CPU at the peak and more, each a `Finding` with the design's numbers), `ReviewRule` (the rules and their titles), `SeverityCounts` |
| `scenario` | `ScenarioResolver`: the document a run simulates, a design or one of its scenarios with its defaults in full, its prices pinned and its empty specs auto-sized (`ResolvedDesign`) |
| `sizing` | auto-sizing: `AutoSizer` (each part's CPU, memory and storage for the design's own peak, snapped to an instance type or a standard shape, with what it needs and why: `PartSize`), one sizer per kind of part, `BaseBusy`, `SizingSummaries` (each part's specs and where they came from, its needs against its instance, the price of its size) |
| `numeric` | arithmetic as Python does it: `ExactSum` (`math.fsum`), `FloatSum` (the built-in `sum()` of floats, compensated since Python 3.12), `PythonRound` (`round(x, n)`), `FloorDivision` (`//` on floats), `TrueDivision` (`/` on floats, an error on zero), `Median`, `Erf` (the complementary error function, as fdlibm and Python compute it) |
| `text` | how the engine writes numbers: `NumberText` (formulas, percentages, short figures, latencies) and `HalfUp` (rounding halves away from zero, as the Python engine does, and JavaScript's `Math.round` as the Python engine computes it); `PythonStrings` (texts folded as Python 3.12 folds them, from `casefold.tsv`, which `scripts/export_casefold.py` writes, and ordered by code point) |
| `queueing` | the Erlang C formula, the wait in an M/M/c queue, `Moments` of a time (its distribution's, or calls made on average), the p95 of a mix of times (`LognormalMixture`), Zipf key popularity |
| `load` | the load each part carries without a run: `LoadEstimator` (clients' shares, balancers by replicas, caches' misses, cache-aside write-backs; TTL caches' miss shares), `BaseRate` beside the workload's peak, `TtlCacheTraffic` (a TTL cache's hit ratio) |
| `settings` | reading a part's settings where the Python engine read the raw settings of a design that may not be valid yet: `LenientSettings` ([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md)), `LoadCurve` (service time by load) |
| `pricing` | instance types from price snapshots: `MachineKind` (which price list), `InstanceTypes` (the cheapest type that covers a size, the part's own type), `Busy` (burstable types' CPU credits), `PricingChoice` (a design's snapshot and purchase), `Purchase`, `MachineSize`, `Machine` |
| `host` | the design's host machine (schema 1.7): `HostMachine` (threads that slow each other down), `PartWork`, `HostSpeeds` (on the host, or on a part's own hyperthreads) |
| `provenance` | where each input came from: `InputProvenances` (each performance input of a design, measured, documented, assumed or illustrative, with its value: `Input`, `Provenance`), read from the model's `ComponentFacts`; `ValueText` (how a value reads) |
| `technology` | each part's technology and its tier: `TechnologyTiers`; `TechnologyNames` (a technology's display name) |
| `confidence` | how far a result can be trusted, and why: `Evidence` (inputs, each part's weight, technology tiers and the bottleneck) assessed as a `Confidence` (the level, its reasons, the inputs to replace first, how to raise it), `AnalysisConfidence` (an analysis's, weighed by each part's estimated utilization), `RunConfidence` (a run's, weighed by each part's and link's share of a request's time, or by each part's utilization), `ConfidenceLevel` |
| `slo` | whether a run is good enough: `SloResult` (each objective the design sets checked against the run's `RunFigures`, an `SloCheck`, and the verdict in words), `Objective`, `StoredObjectives` (the objectives stored with a run, a target left out being off) |
| `seeds` | ranges across seeds: `SeedSummary` (how a seed group's verdicts split, their ranges, whether they are borderline, and the summary in words, which `GET /run-groups/{id}` answers), `ValueRange`, `Seeds` |
| `sweep` | load sweeps: `SweepRange` (its levels and the highest load a step can run), `SweepRow` (a step's figures), `SweepAnalysis` (the knee and the heaviest load within the objectives) |
| `simulation` | where the engine meets the simulator: `SimulationLimits`, `Stationarity` (whether a sampled run reached a `SteadyState`) |
| `explain` | the calculations behind a report's numbers: `QueueingFormulas` (any part's arrival rate, slot capacity, utilization, Little's law, M/M/c wait and CPU capacity), `Formula` |
| `findings` | what a run found: `RunAnalysis` (each part's status, a `Classification`, from its utilization, CPU, rejections and queue; the root causes and the symptoms of a part downstream; what clients saw; work wasted on clients that gave up; caches; data stores' hot shards, replicas, lag and locks; third parties' errors and latency; throttling; pending pods; each fault's impact; autoscaling, serverless cold starts and queue lag), each a `RunFinding` with the figures it rests on |
| `report` | reading a run's report: `RunReport` and its parts, records of what the engine reads, each read by its own `read` ([ADR-0039](../docs/adr/0039-engine-reads-reports-as-partial-records.md)); `ReportDigest`; the words a report is written with: `ModelNotes`, `FailureReason` |

Where a Python function parses a node's config, the Java reads it as its type's record; where it
reads the raw settings of a design that may not be valid yet (the load estimate, which kind of
machine a part runs on, the CPU work on the design's host, the design review), the Java reads them
through `LenientSettings`, with the same lenient rules
([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md)).

A run's report is read into records of the parts the engine reads (`RunReport`, `RunSummary`,
`ReportNode` and the rest), each by its own `read`, which skips what they do not name: a value
the Python indexes is required, one it reads with `get` is nullable, and a report of an older
version reads as long as it has the parts the records require
([ADR-0039](../docs/adr/0039-engine-reads-reports-as-partial-records.md)). Because what they do
not name is skipped, `ReportRecordKeysTests` checks that each record's components name keys the
golden reports write, so a misspelt name fails the build. A run's compact
summary (the run row's flat `p50`, `p95` and `p99`) reads into `RunFigures`, as a report's
summary does, and the objectives stored with a run into `Objectives` with the targets the design
leaves out off (`StoredObjectives`).

A list the Python reads with `get` (a report's links, the breakdown of a request's time) reads
as empty when the report leaves it out. What a report copies from its design is read from the
design the run simulated, as a part's settings are: a run's confidence takes its parts'
technologies and its links' names from the design, not from the report's
`assumptions.technologies` and `input`.

The Java departs from the Python engine in a few places (the Python engine stays frozen), and
its tests say which. Where the Python engine fails, the Java gives a result instead (the owner's
decision):

- A link to a part that does not exist: the Python estimate raises `KeyError`; the Java estimate
  leaves the calling part and the latency unestimated, so a half-drawn design still gets an
  estimate (validation reports the link).
- A rate-limited part first among parts that use none of their limits at the peak: the Python
  summary raises `TypeError` (it has no saturation point to write); the Java summary names the
  rate limit's use without a saturation point, and `saturationRps` is `null`.
- A part with a load and no CPU above zero (`cpuCores: 0` or less), a service whose CPU work per
  request is below zero, a part on its own hyperthreads that stop (`siblingSpeed: 0` on 2 vCPU or
  more, with any CPU work), a data store whose hot data is zero, or a TTL cache whose items have
  no size: the Python review divides by zero and raises `ZeroDivisionError`; the
  Java review skips the check that would (CPU capacity, buffer cache, cache capacity) and gives
  the other findings. `review/cases.json` holds, for each, the Python review of the same design
  without the setting whose removal skips only that check (a part's cores are also read for its
  speed on its hyperthreads, where cores not above zero and none read alike), which the Java
  review must match.

Where the Python engine gives a result for a report no run writes, the Java refuses it:

- A run's report that lists no parts: the Python cost prices the design as before a run, with
  the cost per million requests from the run's throughput; the Java `estimateAfterRun` throws
  `IllegalArgumentException`, since a run's report lists every part of its design and a design
  has a client (`CostEstimatorTests.estimateAfterRun_aReportWithoutParts_isRefused`).

## Tests

```bash
./gradlew :archsim-engine:test
```

- `GoldenParityTests` runs every recorded call of each Python function this module ports, in
  every golden file, and expects the same output: text exactly, numbers to a relative 1e-9.
  `EngineFunctions` maps each Python name to its Java call, through one table per area
  (`TextFunctions`, `QueueingFunctions`, `LoadFunctions`, `PricingFunctions`, `SeedFunctions`,
  `SweepFunctions`, `SizingFunctions`, `InputFunctions`, `EstimateFunctions`, `CostFunctions`,
  `ReviewFunctions`, `AnalysisFunctions`, `SloFunctions`, `ReportFunctions`). Ported so far:
  - on every recorded run, its findings (`analysis.analyze`), and each part's status and why,
    which the report carries as the analysis set them; the golden files reach 17 of the 22
    kinds of finding: `findings/cases.json` covers the rest and branches they do not reach;
  - on every recorded run, the verdict against the design's objectives (`slo.slo_checks`,
    `evaluate`, `verdict_text`), the run as a load sweep's step (`sweep.row_from`, `meets`),
    whether it reached a steady state (`engine.stationary`) and each part's queueing formulas
    (`explain.generic`); on every pair of runs, the seed summary (`seeds.summarize_seeds`) and
    the sweep's breaking point (`sweep.analyze`); on every recorded run, its confidence
    (`confidence.report_confidence`) and its cost (`cost.estimate_cost` with its report); the
    golden files have no run without objectives or figures, no compact run summary, no latency
    knee, no run weighed by utilization and none without base-load buckets, an offered rate, a
    throughput or measured replicas, or with a base load of zero: `report/cases.json` covers
    them; and the words reports are written with (`report.MODEL_NOTES`, `REASON_TEXT`);
  - the analysis of a design for every case of every template (`analyze.analyze_document`), its
    confidence (`confidence.analysis_confidence`) and its quick check (`engine.quick_check`); the
    golden files prepare no run and have no analysis of high or medium confidence:
    `analysis/cases.json` covers them;
  - the design review for every case of every template (`review.review_design`,
    `count_by_severity` and `REVIEW_RULES`; the golden files have none for the invalid
    documents: `review/cases.json` covers the rules the templates never fire and settings
    read leniently);
  - the cost estimate before a run (`cost.estimate_cost` without a run's report, and
    `NOT_MODELLED`) and `sizing.sizing_summary`, for every case of every template (the golden
    files have neither for the invalid documents: `cost/cases.json` covers settings read
    leniently); the cost with a run's report is checked with the report functions;
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
  approximation, from `scripts/export_erfc_points.py`. The cost estimate's and the sizing
  summary's cases are in `cost/cases.json`, from `scripts/export_cost_cases.py`, which
  `CostEstimatorTests` and `SizingSummariesTests` check. The analysis's, the prepared runs' and
  the technology names' are in `analysis/cases.json`, from `scripts/export_analysis_cases.py`,
  which `DesignAnalyzerTests`, `RunPreparerTests` and `TechnologyNamesTests` check. The design
  review's are in `review/cases.json`, from `scripts/export_review_cases.py`, which
  `DesignReviewTests` checks. The report functions' are in `report/cases.json`, each a call with
  the Python engine's output in the golden files' own format, from
  `scripts/export_report_cases.py`, which `ReportCasesTests` replays through `EngineFunctions`.
  The run analysis's are in `findings/cases.json`: golden reports with the figures (or settings)
  changed that reach each branch, cut to what the analysis reads, with the Python engine's
  findings and each part's status, from `scripts/export_findings_cases.py`, which
  `FindingsCasesTests` checks.
- The other tests specify each class.
- `ArchitectureTests` checks that the engine uses no framework or database class, and that its
  packages have no cycles.
