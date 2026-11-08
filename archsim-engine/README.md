# archsim-engine

The engine's deterministic parts in Java: what ArchSim works out from a design without simulating
it, and from a simulation's report. It is a plain library with no framework, over the design
model in [`archsim-model`](../archsim-model/README.md)
([ADR-0012](../docs/adr/0012-separate-model-and-engine-modules.md)). It is stage 3 of the
[Java port](../docs/proposals/java-25-port.md#stage-3--the-engines-deterministic-parts-12-weeks):
each part replaced a call of the Python engine service
([ADR-0036](../docs/adr/0036-python-engine-service-for-synchronous-calls.md)) once it matched the
frozen Python engine's [golden files](../parity/README.md), and all of them have. Stage 4 ports
the simulator, part by part: the random streams, the kernel, the metrics, the runtime, client
groups, third-party APIs, services with fixed replicas, load balancers and CPU contention on a
shared host or a part's own hyperthreads so far, with the whole report, each checked by replaying
the frozen engine's runs
([ADR-0049](../docs/adr/0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)). A
report repeats itself on every platform: every transcendental function goes through `StrictMath`,
which an ArchUnit rule checks, and the digests keep the Python engine's definitions
([ADR-0050](../docs/adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)).
Until it is complete, runs are simulated by the Python workers and a calibration's replay goes
through `ReplaySimulator` to the engine service's one remaining call, `/simulate`
([ADR-0045](../docs/adr/0045-replay-calibrations-through-an-interim-simulate.md)).

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
| `text` | how the engine writes numbers: `NumberText` (formulas, percentages, short figures, latencies) and `HalfUp` (rounding halves away from zero, as the Python engine does, and JavaScript's `Math.round` as the Python engine computes it); `PythonStrings` (texts folded as Python 3.12 folds them, from `casefold.tsv`, which `scripts/export_casefold.py` writes, ordered by code point, and split into words as `str.split()` splits them) |
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
| `runtime` | what every part shares during a run: `Request` (what it asks, when it was sent, its call depth, whether its client gave it up, its trace), `Outcome` (served, refused or given up on, where and why), `NodeMetrics` (what every part measures), `EdgeRuntime` (a link's latency and counts), `SimContext` (the run's clock, streams, parts, links, planned faults and traces), `Calls` (an attempt across a link: latency out, an outage, the callee, latency back, the caller's timeout if it has one; a call with the link's retries and backoff; a service's calls in order), `WorkerPool` (slots with a bounded queue and its timeout), `Cores` (an instance's CPU: executors, contention, on its machine), `CpuMachine` (the machine CPU work runs on: a `SharedHost`, an instance's `OwnHyperthreads` or `FullCores`; the speed of a thread as the machine fills, and a part's CPU ceiling on it), `RuntimeComponent` (a part during a run: its instances, as a balancer picks one, its occupancy, and what it adds to its part of the report), `TokenBucket` |
| `components` | each part's behaviour during a run: `client.TrafficSourceRuntime` (a client group: attempts, timeouts, retries; `ClientMetrics`, what users experienced), `external.ExternalApiRuntime` (a third-party API: latency, errors, rate and concurrency limits), `service.ServiceRuntime` (a service with fixed replicas: `Serving` a request through a replica's handlers, its service time on the replica's cores stalled by its `GarbageCollector`, its memory and its load curve, its calls; `ServiceReport` and `ServiceExplain`, what it adds to the report), `loadbalancer.LoadBalancerRuntime` (connection and rate limits, overhead, `Routing` to an instance of its targets by policy, upstream timeout, circuit breaker, retries on another instance; `LoadBalancerExplain`) |
| `simulator` | `Simulator`: a design, or one of its scenarios, simulated as the Python engine's `run_simulation` did it (the run prepared, its parts built, the workload's `Arrivals` sent by client groups a `ClientPicker` picks by share, the simulation run in chunks) and its report written |
| `reporting` | `ReportWriter`: a finished run's report, as the Python engine's `build_report` wrote it, figures rounded as Python rounds them and integers kept integers: the window, the summary (`ClientSummary`), the time series, each part with its formulas and status (`PartsWriter`, `NodeEntry`, `NodeSeries`, `Capacity`) and each link, the traces (`Traces`), where a request's time goes (`LatencyBreakdown`, `SystemExplain`), the `Assumptions`, the analyses the engine's ports give (`RunAnalyses`: findings, sizing, cost, review, estimate, confidence, read from a `DraftReport` as Python read its report so far) and the `Provenance` with its digests |
| `kernel` | the simulation kernel: `Simulation` (simulated time in milliseconds and its events, processed in SimPy 4.1.2's order: time, then priority, then scheduling; `runUntil` runs a chunk as the Python engine did), `Signal`, `Promise`, `Timeout`, `Routine` (a process, written as steps that continue when the event they wait for is processed), `Condition` (any of, all of), `Resource` and `Store`, each as SimPy writes it, quirks included ([ADR-0048](../docs/adr/0048-keep-simpys-order-of-events.md)) |
| `metrics` | what a run measures, in memory bounded by its time buckets and histogram bins: `Timeline` (a run's buckets of a round width and its measurement window), `Counter`, `Gauge` (a level integrated exactly over time), `LatencyHistogram` (log-sized bins, about 2% wide), `LatencyWithFailures` (what users waited, failures at their timeout), `BucketedHistograms`, `MeanAccumulator`, `LatencySummary` (a distribution's count, mean, percentiles and largest, rounded as a report writes them) |
| `random` | the simulation's random numbers, as the Python engine drew them ([ADR-0047](../docs/adr/0047-draw-python-random-streams-in-java.md)): `PythonRandom` (Python's Mersenne Twister, `random()` and samplers, step for step, under Java names: `nextDouble`, `nextBits`, `below`, `uniform`, `exponential`, `normal`, `logNormal`), `RandomStreams` (one stream per name, derived from the run's seed), `Sampler` (a distribution's values, capped at ten minutes), `ZipfSampler` (key ranks) |
| `simulation` | where the engine meets the simulator: `SimulationLimits`, `Stationarity` (whether a sampled run reached a `SteadyState`) |
| `explain` | the calculations behind a report's numbers: `QueueingFormulas` (any part's arrival rate, slot capacity, utilization, Little's law, M/M/c wait and CPU capacity), `Formula` |
| `findings` | what a run found: `RunAnalysis` (each part's status, a `Classification`, from its utilization, CPU, rejections and queue; the root causes and the symptoms of a part downstream; what clients saw; work wasted on clients that gave up; caches; data stores' hot shards, replicas, lag and locks; third parties' errors and latency; throttling; pending pods; each fault's impact; autoscaling, serverless cold starts and queue lag), each a `RunFinding` with the figures it rests on |
| `check` | the SLO gate for CI, `archsim check`, as a library for stage 5's command: `Measurement` (what a check compares of a run: its figures, cost, objectives judged and confidence), `Regressions` (each figure against the baseline's, the breaking point to one step of its load grid), `CheckResult` (whether the check passes, as it fails on objectives or regressions), `CheckText` (its headline and pull-request comment, the design's text escaped); the sweep of the breaking point needs the simulator (stage 4) |
| `compare` | comparing two runs: `RunComparer` (each run figure with a verdict, each part side by side, what changed in words and why the runs may differ otherwise: `RunComparison`), `InputDiff` (the settings two simulated designs differ in, as the reports store them, compared as Python compares them; [ADR-0040](../docs/adr/0040-comparison-copies-what-it-does-not-read.md)) |
| `export` | exporting a design: `DesignExporter` (the design auto-sized as a run would simulate it, then written in an `ExportFormat`), `ComposeExport` (a docker-compose file), `KubernetesExport` (Kubernetes manifests, with a CPU request below the cores for an auto-sized part), `MermaidExport` (a Mermaid flowchart); each part's settings read leniently over its type's defaults, and the YAML written by a small writer of its own, `YamlText`, as the Python engine wrote it, so the texts match to the character ([ADR-0042](../docs/adr/0042-exports-read-settings-leniently-and-write-their-own-yaml.md)) |
| `roundtrip` | what the exports carry so an import gives the design back, written by the exports and read back by the importers: `RoundTrip` (each part's outgoing links as `target[/port][:types][!flag...]`, `ExportedLink`, and the parts nothing deploys) |
| `imports` | importing what teams already have: `ComposeImport` (a docker-compose file), `KubernetesImport` (Kubernetes manifests) and `TerraformImport` (`terraform show -json` output: resources by type, sized by their instance types (`InstanceSizes`), links the configuration states, guessed ones and suggestions), each a design as built, before validation, with an `ImportReport` of what it mapped, guessed and ignored; `DesignImporter` (the Python engine's `import_design`, behind `POST /import`: the design read and validated, the report with the issues found); parts by their image's full name (`Images`), replicas, specs, autoscalers, storage and links from what the file states, an entry point, cache misses and memory that fits added as guesses; `ImportYaml` (the files' YAML, read by `yaml`, what is wrong with it in the importers' words); `PythonValues` and `PythonMappings` (Python's ways with the values YAML gives, where the importers inherited them, failing in Python's words) |
| `yaml` | YAML from anyone (imported files, calibration mappings): `YamlDocuments` (read as PyYAML reads it through SnakeYAML, within the Python engine's bounds; [ADR-0043](../docs/adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md)), what is wrong with a text is one of four exceptions, as Python told them apart: `InvalidYamlException` (not valid YAML), `YamlValueException` (a value Python refuses, or beyond the bounds), `YamlKeyException` (a scalar Python looks up in vain) and `YamlScalarException` (another scalar Python fails to read) |
| `calibration` | calibration's deterministic parts, as the Python engine's `calibration` package: `mapping` (`MappingLoader`: the mapping file read into a `CalibrationMapping` of each part's, link's and the workload's sources, refused in the Python engine's words without the text or its values; `TimeRange.parse`), `prometheus` (`PrometheusSeries`: a range query's series as a `Histogram`, one figure, or summed per timestamp), `fit` (`LatencyFits`: a histogram's or samples' latencies fitted to a lognormal or an empirical table, with the fit's error; `NormalQuantile`, Python's `NormalDist().inv_cdf`), `resources` (`CpuFits`: `cpuShare` from CPU in use against the request rate; `LoadCurves`: CPU per request against load, fitted never rising; `CurveExtensions`: how that curve continues above its top rate; `MemoryFits`: the working set against the request rate and requests held, by non-negative least squares), `otlp` (`OtlpSpans`: the spans of an OTLP JSON export; `TraceSummaries`: each service's own time, each database's reads and writes, the call graph and CPU time; `OtlpMappings`: services and databases to parts) `ReplaySummary` (the line on how well a calibrated model reproduces what was observed), and the whole calibrations: `PrometheusCalibration` (a design calibrated from a `PrometheusClient`'s range queries: `RecordedPrometheus` reads recorded responses, `Matrices` checks a response as the Python engine did) and `OtlpCalibration` (from an OTLP export), each giving a `CalibrationOutcome` and replaying the calibrated design through a `ReplaySimulator`, with the time it stamps from a `Clock`; a range's times are read as Python 3.12's C `datetime.fromisoformat` reads them (`IsoFormat`, checked against Python on 120,000 generated texts) |
| `assumptions` | `AssumptionsPage`: `docs/assumptions.md`, every number ArchSim uses without the user typing it (technology tiers and defaults, preset numbers, the defaults of a new part, from the catalog the editor reads) and where each comes from, documented or illustrative; `EngineConstants`: the engine-wide constants behind sizing, prices and the component models, read from the code that uses them, each an `EngineConstant` named as the Python engine named it |
| `version` | `EngineVersions`: the engine's version and its reports', which every run and `GET /meta` record (the frozen Python engine's until stage 4) |
| `report` | reading a run's report: `RunReport` and its parts, records of what the engine reads, each read by its own `read` ([ADR-0039](../docs/adr/0039-engine-reads-reports-as-partial-records.md)); `ReportDigest`; the words a report is written with: `ModelNotes`, `FailureReason` |

Where a Python function parses a node's config, the Java reads it as its type's record; where it
reads the raw settings of a design that may not be valid yet (the load estimate, which kind of
machine a part runs on, the CPU work on the design's host, the design review, the exports), the
Java reads them through `LenientSettings`, with the same lenient rules
([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md),
[ADR-0042](../docs/adr/0042-exports-read-settings-leniently-and-write-their-own-yaml.md)).

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
  estimate (validation reports the link). Its export works too, without the link: the Python
  export failed in its auto-sizing's estimate.
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
- A histogram fitted to an empirical table whose shares round alike to six places (top buckets
  that hold almost no observations): the Python fit writes two quantiles with the same
  probability, a table the validator refuses (`empirical quantile probabilities must rise
  strictly`), so a calibration with `fit: empirical` writes a design that is not valid. The Java
  fit keeps the last quantile of each run of shares that round alike, so the probabilities rise
  strictly; every other table is the Python's
  (`LatencyFitsTests.fitHistogram_empiricalSharesThatRoundAlike_keepTheLastOfEachSoTheTableIsValid`).

A report differs from the Python engine's in three ways, none in a digest
([ADR-0050](../docs/adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)):

- it is report 1.15, where Python's were 1.14, so the golden files' `REPORT_VERSION` is not
  checked;
- its provenance names the Java runtime (`java`, the `java.version` property) where Python's
  named `python` and `simpy`;
- a cost line's `instances` is always a number with a fraction, where Python wrote a literal `0`
  or `1` on a line billed per use or by the hour.

A run comparison of a Python run and a Java run names the two engines, as it names two engine
versions.

The importers read YAML as PyYAML reads it
([ADR-0043](../docs/adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md)), and
refuse what they cannot read in the Python engine's words, with these differences:

- Text that is not YAML is refused as `not valid YAML: ...`, with SnakeYAML's description of the
  problem where the Python printed PyYAML's, or, for a value PyYAML's constructor refuses (an
  explicit tag on the wrong kind of node, a value written `=`), a summary without its line and
  column.
- A mapping key that is not text (`1:`, `yes:`) is read as its text, as Python's `str()` writes
  it, and so is a name or a Terraform address an importer keys its parts by: keys Python held
  apart or together (`"1"` and `1`; `1`, `1.0` and `True`) can read otherwise; a timestamp is
  read as the text Python's `str()` gives its date or time, `!!binary` as its base64 text,
  `!!set` as a list of its items, `!!omap` as a mapping and `!!pairs` as a list of two-item lists
  (Python had bytes, a set and lists of tuples, whose `str()` differs).
- A number Python's runtime could not turn into a whole number (`int(float("inf"))`), or into a
  float (`float(10**400)`, `10**400 / 1024`), is refused in Python's words, where the Python
  failed with an error the API answered `500` for.
- Terraform text that is not JSON is refused in `json`'s words where no value starts (`Expecting
  value: line 1 column 1 (char 0)`); past that, with Jackson's description of the problem in
  `json`'s form. The Java reads up to 1,000 levels of nesting, where Python's limit was its C
  recursion limit (3,000 on Windows, 10,000 on Linux), and numbers whose whole part, fraction and
  exponent have up to 4,300 digits each: Python's most for a whole number, but it read floats of
  any length. It also reads `-INF`, `+INF` and `+Infinity`, which `json` refuses
  ([ADR-0044](../docs/adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)).

Calibration reads its mapping's YAML as the importers do, and words what is wrong with it as the
Python engine's `describe_yaml_error` did, the problem and its line and column without the text,
with these differences:

- Where SnakeYAML's parser words a problem otherwise than PyYAML's, the Java keeps its words, at
  the same line and column (`but got }` for `but got '}'`, `found undefined alias nope` for
  `'nope'`); `!!binary` text that is not base64 is described without Python's codec detail, and
  a value written `=` in SnakeYAML's words for its tag.
- A mapping key that is a list or a mapping is refused before any of the mapping's keys is built,
  where PyYAML refused it on reaching it, so a mapping with another problem before it reports the
  key instead (the importers too).
- A Prometheus point is a two-item list; the Python also unpacked a two-character string as one.
  A whole number beyond a float's range is refused as malformed, where the Python let Python's
  `OverflowError` through and its API answered `500`.
- An `!!omap` whose key is a list or a mapping is refused, in the importers too; PyYAML kept it
  as a pair.
- An OTLP span whose id, name or kind is not text is read as the text Python's `str()` gives it,
  where the Python kept the value; a time beyond a long's range (before 1677 or after 2262) is
  refused; a CPU-time attribute holding a whole number beyond a float's range is left out, where
  Python's `OverflowError` failed the calibration. Data that is not an export fails as the
  Python's did: `OtlpExportException` carries the `AttributeError` or `TypeError` its
  `calibrate_otlp` named (`not an OTLP JSON export (TypeError)`).
- An OTLP mapping read from YAML keys its names as text, so a name that is not text (`1: api`) is
  accepted where the Python refused the mapping.

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
  `ReviewFunctions`, `AnalysisFunctions`, `SloFunctions`, `ReportFunctions`, `CheckFunctions`,
  `ExportFunctions`, `ImportDesignFunctions`, `AssumptionFunctions`, `CalibrationFunctions`,
  `ResourceFitFunctions`, `OtlpFunctions`, `CalibrateFunctions`). Every recorded function has
  its Java call. The few entries whose words are a library's are
  listed in `GoldenDepartures`, which checks the error's type, its words up to where the two
  libraries' words part, and that the rest still differs.
  Ported so far:
  - on every recorded run, its findings (`analysis.analyze`), and each part's status and why,
    which the report carries as the analysis set them; the golden files reach 17 of the 22
    kinds of finding: `findings/cases.json` covers the rest and branches they do not reach;
  - on every recorded run, the verdict against the design's objectives (`slo.slo_checks`,
    `evaluate`, `verdict_text`), the run as a load sweep's step (`sweep.row_from`, `meets`),
    whether it reached a steady state (`engine.stationary`) and each part's queueing formulas
    (`explain.generic`); on every pair of runs, the seed summary (`seeds.summarize_seeds`), the
    sweep's breaking point (`sweep.analyze`), the comparison (`compare.compare_reports`,
    `input_diff`) and the check of one against the other (`check.regressions`, `run_check`,
    `headline`, `to_markdown`); on every recorded run, its confidence
    (`confidence.report_confidence`), its cost (`cost.estimate_cost` with its report) and what a
    check measures of it (`check.measure`); the golden files have no run without objectives or
    figures, no compact run summary, no latency knee, no run weighed by utilization and none
    without base-load buckets, an offered rate, a throughput or measured replicas, or with a base
    load of zero, no comparison of identical inputs, other seeds or engines, a bottleneck that
    went or came, or inputs equal only as Python compares them, and no check of breaking points,
    without a baseline or objectives, with other thresholds or fail-on choices, or of design text
    with markup: `report/cases.json` covers them; and the words reports are written with
    (`report.MODEL_NOTES`, `REASON_TEXT`);
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
  - importing the engine's fixtures, every template's compose file and manifests as the exports
    write them, and the text the importers refuse (`importers.import_design`); one entry departs,
    `compose-bad-yaml`, whose description of the YAML is SnakeYAML's (ADR-0043);
  - the assumptions page (`assumptions.render`, which `scripts/gen_assumptions.py` writes to
    `docs/assumptions.md` until the Python engine goes), the constants it lists
    (`assumptions.constants`) and the engine's versions (`version.ENGINE_VERSION`,
    `REPORT_VERSION`);
  - calibration's mapping (`calibration.mapping.load_mapping`), ranges (`apply.parse_range`),
    the recorded Prometheus results read as histograms and figures (`prometheus.histogram`,
    `reduce`), the latency fits (`fit.fit_histogram`, `fit_samples`) and the replay summary of
    every recorded calibration (`apply.replay_summary`); the golden files have no YAML that is
    not valid past one parser error, no malformed Prometheus result and no empirical fit of a
    histogram: the unit tests cover them; the CPU and memory fits (`resources.fit_cpu_share`,
    `fit_load_curve`, `extend_load_curve`, `fit_memory`), the recorded OTLP export's spans and
    their summary (`otlp.parse_otlp`, `summarize`) and the OTLP mapping
    (`otlp.load_otlp_mapping`); the golden files have no capped CPU share, curve that stays flat
    for most of its reasons, memory that hardly changed, malformed span or parent-link loop: the
    unit tests cover them; and whole calibrations from the recorded Prometheus responses and OTLP
    export (`apply.calibrate_prometheus`, `calibrate_otlp`), with the recorded replays' reports
    given back in their order and the recorder's clock; the golden files have no calibration
    without a range or with one backwards, no recording whose range overrides the one given, no
    mapping naming what the design lacks, no gauge, no CPU fit without the workload's rate, and
    no traces without spans, with too few or naming a part the design lacks: the unit tests cover
    them;
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
- Whole simulations are in `simulator/cases.json.gz`, from `scripts/export_simulation_cases.py`:
  small designs, each reaching a feature of the simulator (clients' timeouts and retries, client
  groups, traffic patterns, faults, a set drain, a calibrated scenario of a priced API, services
  with queues, retrying links, event loops on contended cores, garbage collection, memory limits,
  load curves, a CPU ceiling just above the slots' and calls in order, load balancers by each
  policy with retries, circuit breakers, upstream timeouts and rate limits, or none of them;
  and services sharing a host or on their own hyperthreads), with the scenario simulated and the
  frozen engine's report, which `SimulationCasesTests` replays
  ([ADR-0049](../docs/adr/0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)):
  the whole report must match, digests, counts and events processed exactly, numbers to a
  relative 1e-9, and an integer wherever Python wrote one but for a cost line's `instances`,
  leaving out only the report's version and the provenance's wall-clock time and runtime. The
  note about experimental technologies, which no part simulated so far has, is checked by
  `AssumptionsTests`.
- The kernel's order of events is in `kernel/cases.json`, from `scripts/export_kernel_cases.py`:
  small SimPy programs (timeouts and process starts at equal times, processes waiting on
  processes, resource contention, a request granting the oldest waiter, queue timeouts, stores,
  conditions, a condition nested in another, a chunked run), each with what it saw, when, and
  how many events SimPy processed; `TimingCasesTests` and `QueueCasesTests` run each program's
  Java twin and expect the same.
- The metric primitives' cases are in `metrics/cases.json`, from `scripts/export_metrics_cases.py`:
  recorded increments, level changes (some at one moment), latencies and values on timelines of
  several lengths, with every series, total and summary the frozen engine's `metrics` module
  gives, which `SeriesCasesTests` and `HistogramCasesTests` check: bit for bit, except a
  histogram's percentiles before they are rounded, to a relative 1e-14 (they go through `pow`).
- The simulator's random draws are in `random/cases.json`, from `scripts/export_random_cases.py`
  (the frozen engine's `derive_rng`, `random.Random` on edge seeds, `getrandbits`, `randrange`,
  the samplers, each distribution and the Zipf sampler), which `RandomCasesTests` checks: draws
  that are arithmetic bit for bit, those through `log` or `exp` to a relative 1e-14 (the file
  marks them).
- The other tests specify each class.
- `ArchitectureTests` checks that the engine uses no framework or database class, and that its
  packages have no cycles.
