# archsim-engine

The engine's deterministic parts in Java: what ArchSim works out from a design without simulating
it, and from a simulation's report. It is a plain library with no framework, over the design
model in [`archsim-model`](../archsim-model/README.md)
([ADR-0012](../docs/adr/0012-separate-model-and-engine-modules.md)). Only one part calls a server
over HTTP: the Prometheus client a calibration from a live server queries
([ADR-0057](../docs/adr/0057-move-the-prometheus-client-into-the-engine.md)), and only one touches
the file system: `files`, which reads and writes the files the CLI and the MCP server name, as
Python did ([ADR-0059](../docs/adr/0059-read-files-as-python-did-in-the-engine.md)); an ArchUnit
rule keeps each alone. It is stage 3 of the
[Java port](../docs/proposals/java-25-port.md#stage-3--the-engines-deterministic-parts-12-weeks):
each part replaced a call of the Python engine service
([ADR-0036](../docs/adr/0036-python-engine-service-for-synchronous-calls.md)) once it matched the
frozen Python engine's [golden files](../parity/README.md), and all of them have. Stage 4 ports
the simulator, part by part: the random streams, kernel, metrics, runtime, client groups,
third-party APIs, services (fixed, autoscaling and serverless), load balancers, CPU contention
on a shared host or a part's own hyperthreads, Kubernetes clusters, caches, databases and
message queues: every part of the frozen engine, with the whole report. Each is checked by
replaying the frozen engine's runs
([ADR-0049](../docs/adr/0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)): the
designs the frozen engine's simulation cases recorded and the golden files' 50 template runs. A
report repeats itself on every platform: every transcendental function goes through `StrictMath`,
which an ArchUnit rule checks, and the digests keep the Python engine's definitions
([ADR-0050](../docs/adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)).
The app simulates on it: a calibration's replay, through `ReplaySimulator.of`, and the runs its
worker role claims ([ADR-0053](../docs/adr/0053-simulate-runs-in-the-java-apps-worker-role.md)).
It is engine 1.6.1, and stage 4 is done: its exit is checked off in the port plan.

## Packages

| Package | What |
| --- | --- |
| `cost` | the cost estimate, before a run or after one: `CostEstimator` (each part's price per instance-hour, set on it, a price snapshot's instance type, a preset's, its size's at generic rates or the default; managed and per-request parts at the snapshot's rates; implied Kubernetes nodes; burstable types' surplus CPU credits: `CostEstimate`; after a run, `estimateAfterRun` prices per-request parts at each part's rate the run measured at the base load, `MeasuredUsage`, and gives the cost per million successful requests; each load priced by `LevelPricing`, and the month, `MonthlyCost`, the workload's peak hours billed at the peak and the rest at the base load, with an hour at each beside it, `HourlyCost`; an autoscaled service or a cluster is billed at each load the count it settles at there, `SettledCounts`, ADR-0074), `SizePricing` (the price of a part's size) |
| `estimate` | the instant estimate: `InstantEstimator` (what textbook queueing formulas predict for a design at its peak, without a run: each part's utilization and wait, each request path's latency and a rough p95, where an average request's time goes, and where the design saturates: `InstantEstimate`) |
| `analysis` | the static analysis of a design: `DesignAnalyzer` (its sizes, cost, review, technology tiers, inputs, instant estimate and confidence, and whether a quick check can answer it, as a run would use them: `DesignAnalysis`, which `POST /analyze` answers) |
| `run` | preparing a run before it is queued: `RunPreparer` (the design resolved, its seed replaced, validated, and its requests counted against the run's limit: `PreparedRun`, or `SimulationLimitException`), `QuickCheck` (whether sampled mode can answer a run) |
| `review` | the design review: `DesignReview` (an architecture linter over a design as a run simulates it: single points of failure, missing timeouts, stacked retries, timeout inversion, pool exhaustion, unbounded queues, CPU at the peak and more, each a `Finding` with the design's numbers), `ReviewRule` (the rules and their titles), `SeverityCounts` |
| `scenario` | `ScenarioResolver`: the document a run simulates, a design or one of its scenarios with its defaults in full, its prices pinned and its empty specs auto-sized (`ResolvedDesign`) |
| `sizing` | auto-sizing: `AutoSizer` (each part's CPU, memory and storage for the design's own peak, snapped to an instance type or a standard shape, with what it needs and why: `PartSize`), one sizer per kind of part, `Vcpus` (whole cores; hyperthreads, which need more vCPU for the same work; or, on the default of schema 1.11, both, the kind its instance type gives it deciding: `CpuKinds`), `CoresBusy` (the CPU one instance of each part keeps busy at the base load and at the peak, for its credits), `SizingSummaries` (each part's specs and where they came from, its needs against its instance, the price of its size) |
| `numeric` | arithmetic as Python does it: `ExactSum` (`math.fsum`), `FloatSum` (the built-in `sum()` of floats, compensated since Python 3.12), `PythonRound` (`round(x, n)`), `FloorDivision` (`//` on floats), `TrueDivision` (`/` on floats, an error on zero), `Median`, `Erf` (the complementary error function, as fdlibm and Python compute it) |
| `text` | how the engine writes numbers, and reads text as Python did: `NumberText` (formulas, percentages, short figures, latencies) and `HalfUp` (rounding halves away from zero, as the Python engine does, and JavaScript's `Math.round` as the Python engine computes it); `PythonStrings` (texts folded as Python 3.12 folds them, from `casefold.tsv`, which Python recorded once ([parity/README.md](../parity/README.md#pythons-own-answers)), ordered by code point, and split into words as `str.split()` splits them); `PythonJsonReader` (JSON read as Python's `json.loads` reads it, refused in its words where no value starts or something follows the value (`JsonDecodeException`) and in Jackson's past the first character, with Python's line, column and character; deeper than 1,000 levels, a `JsonTooDeepException`: [ADR-0044](../docs/adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)); and `Utf8` (where bytes stop being UTF-8: the `UndecodableBytes`, described in CPython's words) |
| `files` | `PythonFiles`: the files the CLI's `calibrate` and `check` read and write, as Python's `read_text`, `json.loads` and `write_text` did, named as `pathlib` named them and refused in the words of Python's `OSError`, codec and `json` (a `PythonFileException`); the engine's only access to the file system ([ADR-0059](../docs/adr/0059-read-files-as-python-did-in-the-engine.md)) |
| `queueing` | the Erlang C formula, the wait in an M/M/c queue, `Moments` of a time (its distribution's, or calls made on average), the p95 of a mix of times (`LognormalMixture`), Zipf key popularity |
| `load` | the load each part carries without a run: `LoadEstimator` (clients' shares, balancers by replicas, caches' misses, cache-aside write-backs; TTL caches' miss shares), `BaseRate` beside the workload's peak, `TtlCacheTraffic` (a TTL cache's hit ratio) |
| `settings` | reading a part's settings where the Python engine read the raw settings of a design that may not be valid yet: `LenientSettings` ([ADR-0038](../docs/adr/0038-engine-computes-on-typed-records.md)), `LoadCurve` (service time by load) |
| `pricing` | instance types from price snapshots: `MachineKind` (which price list), `InstanceTypes` (the cheapest type that covers a size, the part's own type), `Busy` (burstable types' CPU credits), `PricingChoice` (a design's snapshot and purchase), `Purchase`, `MachineSize`, `Machine` |
| `host` | the design's host machine (schema 1.7): `HostMachine` (threads that slow each other down), `PartWork`, `HostSpeeds` (on the host, or on a part's own hyperthreads), `SiblingSpeeds` (how each service's, database's and cache's vCPUs count from schema 1.11: its own sibling speed, the design's, whole cores on a whole-core family, serverless or a pod, else hyperthread pairs at 0.6; ADR-0091) |
| `provenance` | where each input came from: `InputProvenances` (each performance input of a design, measured, documented, assumed or illustrative, with its value: `Input`, `Provenance`), read from the model's `ComponentFacts`; `ValueText` (how a value reads) |
| `technology` | each part's technology and its tier: `TechnologyTiers`; `TechnologyNames` (a technology's display name) |
| `confidence` | how far a result can be trusted, and why: `Evidence` (inputs, each part's weight, technology tiers and the bottleneck) assessed as a `Confidence` (the level, its reasons, the inputs to replace first, how to raise it), `AnalysisConfidence` (an analysis's, weighed by each part's estimated utilization), `RunConfidence` (a run's, weighed by each part's and link's share of a request's time, or by each part's utilization), `ConfidenceLevel` |
| `slo` | whether a run is good enough: `SloResult` (each objective the design sets checked against the run's `RunFigures`, an `SloCheck`, and the verdict in words), `Objective`, `StoredObjectives` (the objectives stored with a run, a target left out being off) |
| `seeds` | ranges across seeds: `SeedSummary` (how a seed group's verdicts split, their ranges, whether they are borderline, and the summary in words, which `GET /run-groups/{id}` answers), `ValueRange`, `Seeds` |
| `sweep` | load sweeps: `SweepRange` (its levels and the highest load a step can run), `SweepRow` (a step's figures), `SweepAnalysis` (the knee and the heaviest load within the objectives), `BreakingPoint` (a `SweepRequest` run: the design or a scenario, resolved and sized at its own load, simulated at each constant load of its range without its faults, several steps at once, and its `Sweep` read) |
| `runtime` | what every part shares during a run: `Request` (what it asks, when it was sent, its call depth, whether its client gave it up, its trace; the request a queue's consumer handles for a message it published), `Outcome` (served, a cache's miss left to its caller, refused or given up on, where and why), `NodeMetrics` (what every part measures: its load, queue, times, counts and per-bucket means), `EdgeRuntime` (a link's latency and counts), `SimContext` (the run's clock, streams, workload, parts, links, Kubernetes clusters, planned faults and traces), `KeyShards` (the shard a key hashes to), `Timing` (a part's time draws, for the classes working on its behalf), `Calls` (an attempt across a link: latency out, an outage, the callee, latency back, the caller's timeout if it has one; a call with the link's retries and backoff; a service's calls in order, along its `DependencyLinks`, a read sent on to the links set to read on a cache miss after a lookup missed: `Dependencies`), `WorkerPool` (slots with a bounded queue and its timeout), `Cores` (an instance's CPU: executors, contention, on its machine; a part's busy cores and core utilization for its report), `CpuMachine` (the machine CPU work runs on: a `SharedHost`, an instance's `OwnHyperthreads` or `FullCores`; the speed of a thread as the machine fills, and a part's CPU ceiling on it), `RuntimeComponent` (a part during a run: its instances, as a balancer picks one, and their version when they change, its occupancy and the slots it has over time, and what it adds to its part of the report), `TokenBucket` |
| `components` | each part's behaviour during a run: `client.TrafficSourceRuntime` (a client group: attempts, timeouts, retries; `ClientMetrics`, what users experienced), `external.ExternalApiRuntime` (a third-party API: latency, errors, rate and concurrency limits), `service.ServiceRuntime` (a service with fixed or autoscaling replicas: `Serving` a request through a replica's handlers, its service time on the replica's cores stalled by its `GarbageCollector`, a new replica's warm-up, its memory and its load curve, its calls; a `ReplicaSet` whose replicas an `Autoscaler` starts and retires as an HPA does; `ServiceReport` and `ServiceExplain`, what it adds to the report), `service.ServerlessRuntime` (a serverless function: a request on an instance of its own from its `FunctionInstances`, the one that finished last reused, a new one within the concurrency limit and the scale-out rate after its cold start, idle ones reclaimed after the keep-warm time; `ServerlessReport`), a service on a Kubernetes cluster (its replicas' `Pods`, Pending until a node has room, then pulling the image and starting; an autoscaling one's new pods the same, its Pending pods deleted first when it scales down, a retired replica's pod freeing its node), `kubernetes.ClusterRuntime` (a cluster: a `NodePool` of `ClusterNode`s that binds each pod to the least allocated node with room or keeps it Pending, a `ClusterAutoscaler` that adds nodes for Pending pods and removes empty ones; `ClusterReport`), `loadbalancer.LoadBalancerRuntime` (connection and rate limits, overhead, `Routing` to an instance of its targets by policy, upstream timeout, circuit breaker, retries on another instance; `LoadBalancerExplain`), `cache.CacheRuntime` (a cache: `CacheShards` with their executors, CPU and keys, `CacheCommands` on them, hits by a fixed ratio or by `ShardKeys` that expire after a TTL in a memory of their size, evicting the least recently used or refusing new keys, read-through and cache-aside misses, write-backs, snapshot forks and the memory's sampling in `CacheUpkeep`; `CacheReport` and `CacheExplain`), `database.DatabaseRuntime` (a database: `DatabaseShards` of a primary and its read replicas, `DatabaseRequests` past the query router to a key's shard or every shard, each `Operation` on a connection with tickets, `LockTable` row locks or reads that wait for writers, its time on the CPU and disk reads after a buffer-cache miss, and a `LogFlusher`'s group commit, then the shard's replicas replaying the write in a `ReplicationPipeline` (each replica's `ReplicaLog`, an `ApplyJob` acknowledged by as many as the write waits for, the lag sampled); `ShardControls` with a table's `CapacityUnits`, and `DatabaseSettings`; `DatabaseReport`, `DatabaseExplain` and `DatabaseReplicaExplain`), `queue.MessageQueueRuntime` (a message queue: `Publishing` refused by a FIFO limit or a full backlog, dropped, or blocked by the memory alarm, then acknowledged; a `Backlog` held in `Partitions`, a `SharedQueue` or a `SharedStore`, consumers taking one `Message` at a time or a `PollingConsumer` in cycles; `Deliveries` with their calls, retries, dead letters and visibility timeouts; `QueueReport` and `QueueExplain`) |
| `simulator` | `Simulator`: a design, or one of its scenarios, simulated as the Python engine's `run_simulation` did it (the run prepared, its parts built, the workload's `Arrivals` sent by client groups a `ClientPicker` picks by share, the simulation run in chunks, its `WallClock` checked against the request's limit, one clock for a quick check's sample and the whole run after it, where the Python engine started one for each) and its report written; a `RunObserver` is told the run's progress after each chunk and 1.0 at the end, and asked whether to stop before each look at the wall clock, a cancel ending the run with a `SimulationCancelledException`, as the Python engine's `progress` and `should_cancel` hooks; a `RunRequest` asks for a scenario, a seed that replaces the workload's, limits, and a `SimulationMode`: a quick check answers a steady load from its warm-up and a steady sample, saying so in the report's `simulationMode` or why it simulated the whole run (`Sampling`) |
| `reporting` | `ReportWriter`: a finished run's report, as the Python engine's `build_report` wrote it, figures rounded as Python rounds them and integers kept integers: the window, the summary (`ClientSummary`), the time series, each part with its formulas and status (`PartsWriter`, `NodeEntry`, `NodeSeries`, `Capacity`) and each link, the traces (`Traces`), where a request's time goes (`LatencyBreakdown`, `SystemExplain`), the `Assumptions`, the analyses the engine's ports give (`RunAnalyses`: findings, sizing, cost, review, estimate, confidence, read from a `DraftReport` as Python read its report so far) and the `Provenance` with its digests |
| `kernel` | the simulation kernel: `Simulation` (simulated time in milliseconds and its events, processed in SimPy 4.1.2's order: time, then priority, then scheduling; `runUntil` runs a chunk as the Python engine did), `Signal`, `Promise`, `Timeout`, `Routine` (a process, written as steps that continue when the event they wait for is processed), `Condition` (any of, all of), `Resource` and `Store`, each as SimPy writes it, quirks included ([ADR-0048](../docs/adr/0048-keep-simpys-order-of-events.md)); `Steps`, a run of steps taken in a loop while each finishes at once, so steps that take no time need no deeper stack ([ADR-0114](../docs/adr/0114-take-a-run-of-steps-that-finish-at-once-in-a-loop-not-on-the-stack.md)) |
| `metrics` | what a run measures, in memory bounded by its time buckets and histogram bins: `Timeline` (a run's buckets of a round width and its measurement window), `Counter`, `Gauge` (a level integrated exactly over time; `AmountGauge`, one that need not be whole), `LatencyHistogram` (log-sized bins, about 2% wide), `LatencyWithFailures` (what users waited, failures at their timeout), `BucketedHistograms`, `MeanAccumulator`, `LatencySummary` (a distribution's count, mean, percentiles and largest, rounded as a report writes them) |
| `random` | the simulation's random numbers, as the Python engine drew them ([ADR-0047](../docs/adr/0047-draw-python-random-streams-in-java.md)): `PythonRandom` (Python's Mersenne Twister, `random()` and samplers, step for step, under Java names: `nextDouble`, `nextBits`, `below`, `uniform`, `exponential`, `normal`, `logNormal`), `RandomStreams` (one stream per name, derived from the run's seed), `Sampler` (a distribution's values, capped at ten minutes), `ZipfSampler` (key ranks) |
| `simulation` | where the engine meets the simulator: `SimulationLimits`, `Stationarity` (whether a sampled run reached a `SteadyState`) |
| `explain` | the calculations behind a report's numbers: `QueueingFormulas` (any part's arrival rate, slot capacity, utilization, Little's law, M/M/c wait and CPU capacity), `Formula` |
| `findings` | what a run found: `RunAnalysis` (each part's status, a `Classification`, from its utilization, CPU, rejections and queue; the root causes and the symptoms of a part downstream; what clients saw; work wasted on clients that gave up; caches; data stores' hot shards, replicas, lag and locks; third parties' errors and latency; throttling; pending pods; each fault's impact; autoscaling, serverless cold starts and queue lag), each a `RunFinding` with the figures it rests on |
| `check` | the SLO gate for CI, behind the CLI's `archsim check`: `Measurement` (what a check compares of a run: its figures, cost, objectives judged and confidence), `Regressions` (each figure against the baseline's, the breaking point to one step of its load grid), `CheckResult` (whether the check passes, as it fails on objectives or regressions), `CheckText` (its headline and pull-request comment, the design's text escaped), `KneeSweeps` (the breaking points of a design and its baseline on one load grid, narrowed by a finer second sweep: a `KneeFinding` each), `CheckRunner` (a design measured on the simulator, with its breaking point when asked, and a whole check against its baseline from a `CheckRequest`, a baseline it cannot simulate noted) |
| `compare` | comparing two runs: `RunComparer` (each run figure with a verdict, each part side by side, what changed in words and why the runs may differ otherwise: `RunComparison`), `InputDiff` (the settings two simulated designs differ in, as the reports store them, compared as Python compares them; [ADR-0040](../docs/adr/0040-comparison-copies-what-it-does-not-read.md)) |
| `export` | exporting a design: `DesignExporter` (the design auto-sized as a run would simulate it, then written in an `ExportFormat`), `ComposeExport` (a docker-compose file), `KubernetesExport` (Kubernetes manifests, with a CPU request below the cores for an auto-sized part), `MermaidExport` (a Mermaid flowchart); each part's settings read leniently over its type's defaults, and the YAML written by a small writer of its own, `YamlText`, as the Python engine wrote it, so the texts match to the character ([ADR-0042](../docs/adr/0042-exports-read-settings-leniently-and-write-their-own-yaml.md)) |
| `roundtrip` | what the exports carry so an import gives the design back, written by the exports and read back by the importers: `RoundTrip` (each part's outgoing links as `target[/port][:types][!flag...]`, `ExportedLink`, and the parts nothing deploys) |
| `imports` | importing what teams already have: `ComposeImport` (a docker-compose file), `KubernetesImport` (Kubernetes manifests) and `TerraformImport` (`terraform show -json` output: resources by type, sized by their instance types (`InstanceSizes`), links the configuration states, guessed ones and suggestions), each a design as built, before validation, with an `ImportReport` of what it mapped, guessed and ignored; `DesignImporter` (the Python engine's `import_design`, behind `POST /import`: the design read and validated, the report with the issues found); parts by their image's full name (`Images`), replicas, specs, autoscalers, storage and links from what the file states, an entry point, cache misses and memory that fits added as guesses; `ImportYaml` (the files' YAML, read by `yaml`, what is wrong with it in the importers' words); `PythonValues` and `PythonMappings` (Python's ways with the values YAML gives, where the importers inherited them, failing in Python's words) |
| `yaml` | YAML from anyone (imported files, calibration mappings): `YamlDocuments` (read as PyYAML reads it through SnakeYAML, within the Python engine's bounds; [ADR-0043](../docs/adr/0043-read-imported-yaml-with-snakeyaml-as-pyyaml-resolves-it.md)), what is wrong with a text is one of four exceptions, as Python told them apart: `InvalidYamlException` (not valid YAML), `YamlValueException` (a value Python refuses, or beyond the bounds), `YamlKeyException` (a scalar Python looks up in vain) and `YamlScalarException` (another scalar Python fails to read) |
| `calibration` | calibration, as the Python engine's `calibration` package, deterministic but for its HTTP client (`prometheus.http`): `mapping` (`MappingLoader`: the mapping file read into a `CalibrationMapping` of each part's, link's and the workload's sources, refused in the Python engine's words without the text or its values; `TimeRange.parse`), `prometheus` (`PrometheusSeries`: a range query's series as a `Histogram`, one figure, or summed per timestamp; `RecordingPrometheus`: a client's responses kept, written as the Python engine's `RecordingPrometheus` saved them for `--recorded` to replay), `prometheus.http` (`HttpPrometheus`: range queries over HTTP, each pinned to the address it checks, under one deadline and a size cap, failing in the Python engine's words; `PrometheusServer`, the server and how it is called, and `PrometheusUrls`, what a policy asks of a URL: [ADR-0046](../docs/adr/0046-call-prometheus-with-apache-httpclient-5.md), [ADR-0057](../docs/adr/0057-move-the-prometheus-client-into-the-engine.md)), `fit` (`LatencyFits`: a histogram's or samples' latencies fitted to a lognormal or an empirical table, with the fit's error; `NormalQuantile`, Python's `NormalDist().inv_cdf`), `resources` (`CpuFits`: `cpuShare` from CPU in use against the request rate; `LoadCurves`: CPU per request against load, fitted never rising; `CurveExtensions`: how that curve continues above its top rate; `MemoryFits`: the working set against the request rate and requests held, by non-negative least squares), `otlp` (`OtlpSpans`: the spans of an OTLP JSON export; `TraceSummaries`: each service's own time, each database's reads and writes, the call graph and CPU time; `OtlpMappings`: services and databases to parts) `ReplaySummary` (the line on how well a calibrated model reproduces what was observed), and the whole calibrations: `PrometheusCalibration` (a design calibrated from a `PrometheusClient`'s range queries: `RecordedPrometheus` reads recorded responses, `Matrices` checks a response as the Python engine did) and `OtlpCalibration` (from an OTLP export, named in the result by its `OtlpRequest`'s source: `UNNAMED_SOURCE`, `OpenTelemetry traces`, for the app, and `sourceNamed(file)`, `OpenTelemetry traces file.json`, for the CLI), each giving a `CalibrationOutcome` and replaying the calibrated design through a `ReplaySimulator`, with the time it stamps from a `Clock`; a range's times are read as Python 3.12's C `datetime.fromisoformat` reads them (`IsoFormat`, checked against Python on 120,000 generated texts) |
| `assumptions` | `AssumptionsPage`: `docs/assumptions.md`, every number ArchSim uses without the user typing it (technology tiers and defaults, preset numbers, the defaults of a new part, from the catalog the editor reads) and where each comes from, documented or illustrative; `EngineConstants`: the engine-wide constants behind sizing, prices and the component models, read from the code that uses them, each an `EngineConstant` named as the Python engine named it |
| `version` | `ArchsimVersion`: ArchSim's own version, the release's, which the build writes from `gradle.properties` (ADR-0076). `EngineVersions`: the engine's version (1.6.0) and its reports' (1.21), which every run and `GET /meta` record; runs the Python worker stored name the frozen Python engine (0.16.0, report 1.14), runs the Java worker stored before 1.0.0 name 0.16.0 with report 1.15, runs before ADR-0074 name 1.0.0 with report 1.15, runs from then until 1.1.1 name 1.1.0, and runs from then until ADR-0081 name 1.1.1 with report 1.16, runs from then until 1.2.1 name 1.2.0, runs from then until ADR-0083 name 1.2.1 with report 1.17, runs from then until ADR-0084 name 1.3.0 with report 1.18, runs from then until ADR-0085 name 1.4.0 with report 1.19, runs from then until ADR-0089 name 1.5.0 with report 1.20, runs from then until ADR-0092 name 1.5.1 with report 1.20, and runs from then until ADR-0091 name 1.5.2 with report 1.20 |
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

The Java departs from the Python engine in a few places (the Python engine stayed frozen at
0.16.0 until stage 5 deleted it), and its tests say which. Where the Python engine fails, the
Java gives a result instead (the owner's decision):

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

A report differs from the Python engine's in nineteen ways, which leave its result digest as Python
defined it ([ADR-0050](../docs/adr/0050-one-report-per-seed-with-pythons-digest-definitions.md)) and
change its value only where the last eight say:

- it names its own engine, 1.6.1, where Python's named 0.16.0, so the golden files' `ENGINE_VERSION` is
  not checked (the assumptions page names the engine too, and the golden page is checked
  rendered for 0.16.0, the version its file records; the page also names the command that writes
  it, `./gradlew :archsim-engine:generate` where Python's named `scripts/gen_assumptions.py`, and
  the golden page is checked with those two lines put back to Python's);
- it is report 1.20, where Python's were 1.14, so the golden files' `REPORT_VERSION` is not
  checked;
- its provenance names the Java runtime (`java`, the `java.version` property) where Python's
  named `python` and `simpy`;
- a cost line's `instances` is always a number with a fraction, where Python wrote a literal `0`
  or `1` on a line billed per use or by the hour;
- a cost item's `quantity` is always a number with a fraction, where Python wrote the literal
  `730` on a load balancer's and a control plane's hours;
- its cost bills a part that scales at the count it settles at under each load, and prices an hour
  at the peak beside the month
  ([ADR-0074](../docs/adr/0074-bill-the-peak-for-the-hours-a-month-it-lasts.md)): the cost has
  `peakHours`, `hourly` and each line `peakInstances`, which Python's did not; an autoscaled
  service's or a cluster's line says how it was counted in its own words (`3 replicas at the base
  load (autoscaling 3–14)`, where Python wrote `avg 3 active instances at the base load` after a
  run and `3 replicas` before one); and where the count is not Python's, the cost departs by that
  part's price. No template's count moves (their recorded runs are too short to scale, and each
  starts at its minimum), but sixteen hand-made cases' do, each listed with why in
  `GoldenDepartures`: in ten report cases a cluster no pod runs on keeps its minimum of 1 node,
  where Python billed the nodes the run measured, scaled to the base load; in five recorded
  simulations an autoscaled service or cluster is billed what its work at the base load needs,
  where Python billed the run's average, which lags it while replicas and nodes start or go; and a
  cluster without its autoscaler is billed its nodes. Every comparison of a cost with Python's
  (the golden files' `estimate_cost` and `analyze_document`, the report, analysis and cost cases,
  the recorded runs and calibration replays) goes through `CostDepartures`, which leaves the new
  figures out, puts Python's words back for those lines' counts (the rest of each basis is
  compared), and prices a listed count back at Python's, so the rest still matches exactly;
- where no time bucket of the run's window is at the base load (a ramp that passes it during the
  warm-up), its cost measures the base's per-request rates in the buckets nearest the base, where
  Python scaled the whole window's mean down to it, which the peak's failures, timeouts and cache
  hits moved (engine 1.1.1; the peak's rates likewise). Four recorded runs (black-friday's base
  and payments-slow, auth-token's base and idp-down) and one report case (`aBaseLoadOfZero`) depart,
  each on a line that costs nothing, so no money moves: a free third-party API's calls a second,
  and a function's time a request at a base load of 0. Each is listed with why in
  `GoldenDepartures`; `CostDepartures` checks that the line says what is listed and costs what
  Python's does, item by item, then puts Python's basis and item quantities back;
- its findings count one request, and one refused call, in the singular (`1 request failed at
  API`, `1 client request failed because of them`, `refused 1 call`), where Python wrote
  `1 requests`, `1 client requests` and `1 calls`. Only words change, so the engine stayed at 1.1.1.
  Some recorded simulations, two of the CLI's recorded runs and one of the MCP server's answers
  hold a count of one request (none holds one refused call): `ReportComparison` and the CLI's
  replays read Python's words through `WordingDepartures`, which puts one request in the
  singular, so the rest is still compared exactly, and `RunAnalysisTests` checks each finding with
  its count cut to one;
- its instant estimate counts the log flush a database's write waits for as a wait, where Python
  counted it as work on the database's cores (engine 1.5.1,
  [ADR-0089](../docs/adr/0089-count-a-commits-log-flush-as-a-wait-in-the-instant-estimate.md)):
  a write still holds its connection through the flush, but only the CPU share of its queries
  fills its cores, and only that share takes longer on slowed threads. Every recorded estimate of
  a database with a CPU size that flushes its commits and takes writes departs, in a report's
  `estimate` and, in an analysis, in the confidence that weighs each part by its estimated
  utilization too; the result digest covers neither. `EstimateDepartures` reads such a
  database's lower CPU share, with its utilization and load, as Python's once each follows from
  its own shares as Python's does (`CommitFlushDepartures`), the estimate's busiest part and
  saturation point once they follow from its parts likewise, its summary once it is Python's with
  the busiest part, the overloaded part and the saturation point it names replaced by the Java's
  own, and an analysis's confidence once it is what the Java's confidence makes of the Java's
  estimate and Python's what it makes of Python's. How much lower the share is, an estimate
  cannot say: `CommitFlushCpuTests` holds every database of every recorded estimate, analysis
  and report to it (Python's share over the Java's is its queries and flushes over its queries).
  In the two recorded cases on a host machine the flush is no longer stretched with the query, so
  the database's own time, what waits on it and the p95 a summary gives are the Java's own,
  listed in `slowed-flush.json`;
- its instant estimate spreads a cache's commands over every shard's cores, and counts a
  read-through cache's fill of a key it missed as a command in its slots, its CPU and the queue
  its commands wait in, where Python put a sharded cache's whole load on one shard's cores and
  counted a fill only in a read's time (engine 1.5.2,
  [ADR-0092](../docs/adr/0092-spread-a-caches-commands-over-its-shards-and-count-its-fills.md)).
  The recorded estimates of the hot-key feed's sharded cache and of the CDN's short-TTL caches
  depart, with their analyses, two template runs and two of the CLI's `analyze` answers.
  `CacheCommandDepartures` reads such a cache's shares and the limit they set as Python's once
  each follows from its own shares as Python's does, with its slots no less busy than Python's
  and its CPU moved exactly as its slots and shards say (Python's CPU share times the Java's slot
  share is the shards times the Java's CPU share times Python's slot share), and what follows
  from the parts as for a database; `CacheCommandsTests` holds every cache of the designs
  `RecordedEstimates` reads to the amount (Python's slot share over the Java's is its requests
  over its commands, its CPU share the shards times that). Where a fill makes a busy cache's
  commands wait longer, in two of the estimate's cases and three recorded simulations, the
  cache's wait, what its caller holds, the latency and where its time goes are the Java's own,
  listed in the two `cache-fills.json` and pinned by `OwnFigures`;
- its instant estimate saturates a part on its own hyperthread pairs where its vCPUs fill at the
  sibling speed: the CPU's share of its load is at least the share its work would take with every
  vCPU at that speed, while its utilization stays the share busy at the peak's speed; and past its
  vCPUs its work runs at the sibling speed and waits for one, where Python took the saturation
  point from the speed at the peak and counted the vCPUs taking turns at the part's own work, so
  an overloaded part ran at about a hundredth of its speed (engine 1.6.0, ADR-0091). The design
  review's CPU check reads the same speed and judges such a part by the same ceiling, the share of
  it its work takes, where Python judged it at the speed at the peak. The estimate's case of such
  a part and two recorded simulations depart: their figures that move, one simulation's CPU
  finding and the finding of little CPU headroom its gateway gains are the Java's own, listed in
  the two `own-pairs.json` and pinned by `OwnFigures`, which leaves out an item the Java adds to a
  list where it is the one listed; the read-through cache of one of them is listed with its
  shares, since its load no longer follows from them as `CacheCommandDepartures` needs;
- a third-party API whose price the design leaves unset costs its technology preset's price, or is
  not priced, listed in the cost's `unpriced` and a note (engine 1.2.0,
  [ADR-0081](../docs/adr/0081-price-an-unset-api-at-its-preset-or-leave-it-out-of-the-month.md)),
  where Python billed it at its default, 0. The recorded designs that leave it unset (black-friday's
  payments API and the Kafka pipeline's e-mail API, and the hand-made cases with an API and no
  price) depart. `CostDepartures`, through `ApiCostDepartures`, checks that such a line says
  what Python's says (its rate at the base load, or the basis listed for the case), then puts
  Python's line back and prices the month and the cost per million back by what it changed; the
  cost's `unpriced` is left out, its `hasCost` is Python's, and its notes are Python's once the
  Java's are shown to differ only in the notes on prices;
- a cache counts the reads it refused (`refusedLookups`, down or turned away by its queue, once
  however often its caller retries) and its hit ratio counts them, where Python counted neither
  (ADR-0082); and the advice after a cache's outage, and the review's on a cache without a
  fallback, ask only for what the callers lack, where Python always asked for both settings
  (engine 1.2.1). The recorded runs with refused lookups (the LLM assistant's cache-down, three
  hand-made simulations) and the cases whose callers already fall back depart. `CacheDepartures`
  reads such a cache as Python's once its hit ratio is shown to be hits over hits, misses and
  refused lookups, its series no higher than Python's and its formula its own counters', and its
  finding as Python's once it is Python's but for the refused reads; and the advice as Python's
  when the Java's is one of its forms. The result digest is then taken over that content, as the
  input's is;
- a database of several instances with a CPU size reports its busiest instance's share of its
  cores busy (`busiestInstanceCpuUtilization`) and sets its CPU ceiling where that instance fills,
  where Python spread its requests' CPU time evenly over the instances that serve them (engine
  1.3.0, ADR-0083). Every recorded run with read replicas or partitions departs.
  `BusiestInstanceDepartures` reads such a part as Python's once its busiest share lies between
  the mean over its instances (or below it, where the database refused operations: the busiest
  instance is the one sent the most work, refused operations included) and
  `min(1, instances × mean)`, its CPU ceiling is no higher than
  1.02 × Python's, and Python's capacity, limit and ceiling were it served alone follow from the
  Java's slots and Python's CPU ceiling; and a bottleneck finding that names the busiest instance
  as Python's once it is Python's but for that sentence.
  The result digest is then taken over that content;
- a run whose design has a queue adds to its summary the p95 age of a message at the slowest
  queue (`messageAgeP95Ms`), the messages its measured requests lost (`lostMessages`,
  dead-lettered or dropped) and the share of them that succeeded and lost none (`availability`),
  which Python did not report (engine 1.4.0, ADR-0084). Every recorded run with a queue departs.
  `MessageDepartures` leaves the three out once the age is shown to be the slowest queue's and
  the availability to lie between the success rate and the success rate less a request for each
  lost message; the result digest is then taken over that content;
- a queue with more consumers than partitions reports its idle consumers (`idleConsumers`), and
  its lag finding says why they sat idle and advises adding partitions, not consumers (engine
  1.5.0, ADR-0085), which Python did not. `IdleConsumerDepartures` leaves the figure, the sentence and
  the evidence out, and puts Python's advice back, once the idle consumers are shown to be the
  queue's consumers beyond its effective ones and the sentence the one they make;
- a Kafka broker's size comes from what ArchSim's Kafka experiment measured (0.39 fixed cores,
  0.053 ms of CPU per message, a 973 MiB working set) where Python's came from an illustrative
  throughput per vCPU (engine 1.5.0, ADR-0085), so every recorded size of a Kafka broker departs
  in its need and its words, not in its storage, type or price. `KafkaSizingDepartures` reads a
  broker's busy cores at the peak and at the base load, CPU need and memory as Python's once each
  is shown to be the measured broker's at the rates Python's imply, then their words; the
  assumptions page and constants leave the measured rows and their legend out;
- evenly spaced arrivals fall where the offered rate, added up since the last, reaches one more
  request, where Python took the gap to the next from the rate at the last arrival (engine
  1.6.1), so a run with evenly spaced arrivals on a rate that changes (a ramp, a burst or a trace)
  departs. Two recorded runs do, `aTracedPattern` and `aSetDrainAndAQuietStart`:
  `departed-runs.json` lists, for each, the parts of its report its requests make, which are the
  Java's own and pinned by their digest, and the rest of the report is compared with Python's;
- its design is schema 1.11, which adds `workload.peakHoursPerMonth` (ADR-0074) and leaves it
  out while it is 0, lets an API's price be left unset (ADR-0081), adds
  `objectives.messageAgeP95Ms` (ADR-0084), left out while it is unset, and counts a vCPU as a
  hyperthread by default (ADR-0091), so the report's `input` and `provenance` name 1.11 where
  Python's named 1.7, an unset API price is written empty where Python wrote 0, the input's
  architecture pins its vCPUs to whole cores (`siblingSpeed: 1`), each sized part's summary says
  how its vCPUs counted (`vcpus`), and its `inputDigest` and `resultDigest` differ. The model's
  `SchemaDepartures` (see [archsim-model's README](../archsim-model/README.md#tests)) gives the
  recorded documents to the Java at 1.11 as they were written, pinned to whole cores as the
  upgrade to 1.11 pins a saved design (`SiblingSpeedDepartures`), and reads its outputs back at
  1.7, an API's price read as Python's output records it where that is 0 (`ApiPriceDepartures`). `ReportComparison` also
  checks that a report's `inputDigest` is its input's digest and its `resultDigest` its results',
  then compares the digests of that input and those results at 1.7 with Python's. The cases that
  read recorded documents themselves, and the calibration replays, do the same.

A run comparison of a Python run and a Java run names the two engines, and their two versions
(0.16.0 and 1.6.1), as it names any two engine versions.

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
- A figure Python's `float()` reads as infinity (a CPU limit of 10,000 digits) leaves its setting
  unset, as in Python, and the import's issue says the setting `input should be a finite number`,
  where Python's named the setting's bound
  ([ADR-0103](../docs/adr/0103-refuse-a-number-that-is-not-finite-when-a-design-is-read.md)).
- Terraform text that is not JSON is refused in `json`'s words where no value starts (`Expecting
  value: line 1 column 1 (char 0)`); past that, with Jackson's description of the problem in
  `json`'s form. The Java reads up to 1,000 levels of nesting, where Python's limit was its C
  recursion limit (3,000 on Windows, 10,000 on Linux), and numbers whose whole part, fraction and
  exponent have up to 4,300 digits each: Python's most for a whole number, but it read floats of
  any length. It also reads `-INF`, `+INF` and `+Infinity`, which `json` refuses
  ([ADR-0044](../docs/adr/0044-read-terraform-json-with-jackson-as-python-json-reads-it.md)).

An import report says what the autoscaler reads: a HorizontalPodAutoscaler's other metrics are
ignored with `(ArchSim scales on worker utilization or, with a CPU size, CPU utilization if
higher)`, and one without a CPU utilization target `scales at ArchSim's default target`, where
Python wrote `(ArchSim scales on CPU utilization)` and `scales on CPU at ArchSim's default target`.
Only words change, so the engine stayed at 1.6.0. `ImportCasesTests` reads Python's words as the
Java's through `ImportWordingDepartures`, so every word of each report is still compared exactly.

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

Where the Python check noted any failure of its baseline and went on, the Java check notes only
a baseline that is invalid, lacks the scenario or is over a run's limits, in the same words, and
lets any other failure through, so an error in the engine is not taken for an unreadable
baseline: the conventions never catch `Exception` or `RuntimeException`
([docs/java-conventions.md](../docs/java-conventions.md); `SweepCasesTests`
`aCheckWhoseBaselineLacksTheScenario` and `aCheckWhoseBaselineCannotBeSimulated`).

## Tests

```bash
./gradlew :archsim-engine:test
```

The golden files and the `cases.json` files below hold what the frozen Python engine 0.16.0
gave, recorded by scripts that went with it at stage 5's exit (git history keeps them): they are
data now, not recorded again.

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
  - the assumptions page (`assumptions.render`), rendered for the version its golden file
    records, its two lines that name the command that writes it put back to the Python script's
    (`docs/assumptions.md` is the page for the Java engine's own version, which
    `GeneratedFileTests` checks, below), and the constants it lists (`assumptions.constants`);
    the engine's versions (`version.ENGINE_VERSION`, `REPORT_VERSION`) are the Java engine's own;
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
  and more), the unit tests expect the values and words the Python engine gave. The instant
  estimate's such cases are in `estimate/cases.json`, each a resolved design with the Python
  engine's estimate, recorded by `scripts/export_estimate_cases.py`; `numeric/erfc.csv` holds
  Python's `math.erfc` at points over every interval of the approximation, recorded once by
  `scripts/export_erfc_points.py` ([parity/README.md](../parity/README.md#pythons-own-answers)).
  The cost estimate's and the sizing summary's cases are in
  `cost/cases.json`, recorded by `scripts/export_cost_cases.py`, which `CostEstimatorTests` and
  `SizingSummariesTests` check. The analysis's, the prepared runs' and the technology names' are
  in `analysis/cases.json`, recorded by `scripts/export_analysis_cases.py`, which
  `DesignAnalyzerTests`, `RunPreparerTests` and `TechnologyNamesTests` check. The design review's
  are in `review/cases.json`, recorded by `scripts/export_review_cases.py`, which
  `DesignReviewTests` checks. The report functions' are in `report/cases.json`, each a call with
  the Python engine's output in the golden files' own format, recorded by
  `scripts/export_report_cases.py`, which `ReportCasesTests` replays through `EngineFunctions`.
  The run analysis's are in `findings/cases.json`: golden reports with the figures (or settings)
  changed that reach each branch, cut to what the analysis reads, with the Python engine's
  findings and each part's status, recorded by `scripts/export_findings_cases.py`, which
  `FindingsCasesTests` checks.
- Whole simulations are in `simulator/cases.json.gz`, recorded by
  `scripts/export_simulation_cases.py`: small designs, each reaching a feature of the simulator
  (clients' timeouts and retries, client groups, traffic patterns, faults, a set drain, a
  calibrated scenario of a priced API, services with queues, retrying links, event loops on
  contended cores, garbage collection, memory limits, load curves, a CPU ceiling just above the
  slots' and calls in order, autoscaling up and down on handlers or CPU, load balancers by each
  policy with retries, circuit breakers, upstream timeouts and rate limits, or none of them;
  services sharing a host or on their own hyperthreads; caches with a fixed hit ratio or TTL
  keys over the workload's keyspace, sharded, evicting or refusing keys when full,
  snapshotting, read through or cache-aside, writing back or not; and databases locking hot
  rows, flushing with group commit, waiting for tickets or writers, throttled, reading
  SSTables from disk, scattering searches, reading from replicas, replicas lagging or
  acknowledging writes, and tables metered in capacity units; and message queues with hot
  partitions, dead letters, blocked publishers, redeliveries, FIFO limits, dropped overflow,
  consumers polling in cycles, and a backlog a set drain cuts off; a scenario whose seed the
  run's replaces; and quick checks answered from a steady sample, falling back to the whole run,
  or of a run too short to sample), with the scenario, seed and mode simulated and the frozen
  engine's report, which `SimulationCasesTests` replays
  ([ADR-0049](../docs/adr/0049-measure-the-simulator-by-replaying-the-frozen-engines-runs.md)):
  the whole report must match (but for the parts of the two runs `departed-runs.json` lists,
  which are pinned by their digest instead), digests, counts and events processed exactly, numbers to a
  relative 1e-9, and an integer wherever Python wrote one but for a cost line's `instances` and
  an item's `quantity`, leaving out only the engine's and the report's versions and the
  provenance's wall-clock time and runtime (`ReportComparison`, in the test fixtures
  `archsim-cli`'s tests share). The note about experimental technologies, which the recorded
  balancers and caches reach, is also checked by `AssumptionsTests` for counts of parts they do
  not.
- `GoldenRunsTests` replays the golden files' 50 template runs the same way: each template's
  base design and one scenario, its time axis compressed (`runs/<key>`: `document`,
  `scenarioId`, `report`), simulated in Java and compared whole with the frozen engine's report,
  digests included.
- `CalibrationReplaysTests` runs the golden files' three calibrations that replay their result
  with each replay simulated in Java (`ReplaySimulator.of`, as the app replays it) in place of
  the report the frozen engine recorded: each replay gives that report, and the calibration its
  recorded outcome.
- `SizingAuditTests` checks auto-sizing against what the simulation then does, on every
  template's run compressed to 15 seconds: the port of `scripts/sizing_audit.py`, which went
  with the Python engine at stage 5's exit (in `sizing.audit`: `SizingAudit` runs `CpuCheck`,
  `ResourceChecks`, `ScaleChecks` and `PerRequestCheck`, which read the Java engine's report as
  the script read Python's). Each auto-sized part's p95 CPU is 25% to 75% of its vCPU or has a
  stated reason, no auto-sized part is the bottleneck by its CPU or memory, memory peak ≤ need ≤
  instance, a re-run gives the same types and cost, 1.5 and 2 times the rate never give a
  cheaper type, per-request lines are within 10% of a hand calculation, and the monthly cost is
  at most 1.15 times a hand-picked reference. On every template its checks are the script's on
  the frozen engine, part for part (301 checks).
- `QueueingTheoryTests`, `StarterReferenceTests` and `EstimateAgreementTests` (in `simulator`)
  hold the simulator to queueing theory, which does not move with the engine, as the frozen
  engine's `test_reference_benchmark.py` and `test_estimate.py` did, with their tolerances: one
  service with Poisson arrivals and exponential service is an M/M/c queue whose mean response is
  Erlang C's within 8% (M/M/1 at 50% and 80% load, M/M/4 at 80%), its utilization the offered
  load within 5% and an M/M/1's median `ln 2 / (mu - lambda)` within 8%; Little's law holds
  within 10% at the starter's API and database; the starter's scenarios stay in their documented
  ranges and directions; and the instant estimate's mean and p95 are within 15% of the
  simulated ones on the starter and the queueing lab, its busiest part's utilization within 5
  points.
- `GeneratedFileTests` holds the repository's files the engine generates to its output: the
  web app's engine fixtures (`apps/web/src/test/fixtures/catalog.json`, the component catalog
  as `GET /api/v1/components` answers it, and `analysis-starter.json`, the starter's analysis
  as `POST /api/v1/analyze` answers it), the run the web app's landing page states figures from
  (`apps/web/src/data/landing-run.json`, `LandingRun`: a full run of black-friday-kubernetes'
  `fixed-at-peak`, priced with the shipped snapshot, with the template's objectives and the
  engine's verdict on them) and `docs/assumptions.md` (`AssumptionsPage`, for this engine's
  version), one `GeneratedFile` each. `./gradlew :archsim-engine:generate` writes them
  (`GeneratedFile.main`); the test task declares them, and the landing page's template, as
  inputs, so an edit to one runs the check again.
- `./gradlew :archsim-engine:parity` runs `SimulationParityTests` (tagged `parity`, left out of
  `test`): every template case of the frozen engine's simulation baselines (149 cases, 20 seeds
  each; `parity/simulation/` once recorded), each run on the document the frozen engine ran
  (`Baselines.design`, the template's golden file's `/document`, not today's `examples/` file),
  simulated in Java and judged by the port plan's bar
  ([ADR-0052](../docs/adr/0052-record-the-frozen-engines-simulation-baselines-once.md)): the
  Java median p50, p95, throughput and each part's utilization inside Python's range and within
  2% of its median (`ParityBar`), and no seed failing otherwise than Python's (`CaseResult`),
  each case and in total, with the runs that replay Python's exactly, the metric furthest from
  Python's and the cases that miss, written to `build/parity/simulation-parity.json`. A failed
  run is recorded with its error, as the baselines record one (`RunError`). Each run is read as
  the frozen engine's where the engine has changed on purpose since
  ([ADR-0096](../docs/adr/0096-read-the-simulation-paritys-runs-as-the-frozen-engines-where-the-engine-changed-on-purpose.md)):
  `RunDepartures` reads its report with the golden reports' departures, guided by the baselines'
  figures of the run and by the frozen engine's report of the template's run in its golden file
  (`Baselines.frozenReport`), and `RunFigures` keeps its figures and digests from the report so
  read. A run that departs only where the baselines keep too little to read it (a cache's refused
  lookups) is counted apart: each case's `departing`, and in total `departingRuns` and
  `departures`, by departure.
  `-Parchsim.parity.baselines=DIR` judges another recording, `.out=DIR` writes elsewhere,
  `.threads=N` and `.only=TEMPLATE,...` choose how; hours of simulation, under a six-hour
  timeout.
- `./gradlew :archsim-engine:speed` runs `SimulationSpeedTests` (tagged `speed`, left out of
  `test`): every template as shipped, timed as `scripts/perf.py` timed the frozen engine's
  before both went (five runs, or `-Parchsim.speed.repeats=N`, with its limits, after one that
  warms the JVM) and written in its shape (`SpeedRow`) to `build/parity/speed.json`, or
  `-Parchsim.speed.out=FILE`. Minutes, on a quiet machine. Both measured tasks run without the
  coverage agent.
- Load sweeps and checks are in `sweep/cases.json.gz`, recorded by
  `scripts/export_sweep_cases.py`: breaking points over a range found from the load or asked for
  (one capped by the request limit, one capped below the lowest load a step runs, steps too
  short for their warm-up), a scenario's, a design's swept without its outage, a design that
  misses its objectives at the lightest load; a design's and its baseline's breaking points on
  one grid, with and without the finer second sweep; a scenario measured with its knee; and
  whole checks, against a baseline with knees, against one over the request limit, and against
  one without the check's scenario. `SweepCasesTests` runs each in Java (several steps at once,
  which it checks gives the sweep run one step at a time): text and every step's digest exactly,
  numbers to a relative 1e-9.
- The kernel's order of events is in `kernel/cases.json`, recorded by
  `scripts/export_kernel_cases.py`: small SimPy programs (timeouts and process starts at equal
  times, processes waiting on processes, resource contention, a request granting the oldest
  waiter, queue timeouts, stores, conditions, a condition nested in another, a chunked run), each
  with what it saw, when, and how many events SimPy processed; `TimingCasesTests` and
  `QueueCasesTests` run each program's Java twin and expect the same.
- The metric primitives' cases are in `metrics/cases.json`, recorded by
  `scripts/export_metrics_cases.py`: recorded increments, level changes (some at one moment),
  latencies and values on timelines of several lengths, with every series, total and summary the
  frozen engine's `metrics` module gave, which `SeriesCasesTests` and `HistogramCasesTests`
  check: bit for bit, except a histogram's percentiles before they are rounded, to a relative
  1e-14 (they go through `pow`).
- The simulator's random draws are in `random/cases.json`, recorded by
  `scripts/export_random_cases.py` (the frozen engine's `derive_rng`, `random.Random` on edge
  seeds, `getrandbits`, `randrange`, the samplers, each distribution and the Zipf sampler), which
  `RandomCasesTests` checks: draws that are arithmetic bit for bit, those through `log` or `exp`
  to a relative 1e-14 (the file marks them).
- The other tests specify each class.
- `ArchitectureTests` checks that the engine uses no framework or database class, and that its
  packages have no cycles.
