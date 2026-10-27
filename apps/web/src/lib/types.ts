/** Types mirroring the versioned document schema and the API (see docs/schema.md, docs/api.md). */

export type RequestType = "read" | "write";

export type Distribution =
  | { kind: "constant"; value: number }
  | { kind: "exponential"; mean: number }
  | { kind: "uniform"; min: number; max: number }
  | { kind: "lognormal"; median: number; p95: number }
  /** Measured: a quantile table [p, ms], p from 0 to 1, linear between points (schema 1.1). */
  | { kind: "empirical"; quantiles: [number, number][] };

export type ConfigValue = unknown;
export type NodeConfig = Record<string, ConfigValue>;

export interface NodeSpec {
  id: string;
  type: string;
  label: string;
  description?: string | null;
  config: NodeConfig;
}

export interface EdgeConfig {
  requestTypes: RequestType[];
  latencyMs: Distribution;
  timeoutMs?: number | null;
  maxAttempts?: number;
  retryBackoffMs?: number;
  order?: number;
  /** Cache-aside: reads go over this link only after the service's cache lookup missed. */
  readsOnMiss?: boolean;
  /** A failure on this link does not fail the request (a failed cache lookup counts as a miss). */
  continueOnFailure?: boolean;
}

export interface EdgeSpec {
  id: string;
  source: string;
  sourcePort: string;
  target: string;
  targetPort: string;
  label?: string | null;
  config: EdgeConfig;
}

export type TrafficPattern =
  | { kind: "constant" }
  | { kind: "ramp"; startRps: number; endRps: number }
  | { kind: "burst"; burstRps: number; startS: number; durationS: number }
  /** A replayed rate curve, e.g. observed traffic: [t seconds, req/s] points (schema 1.1). */
  | { kind: "trace"; points: [number, number][]; source?: string | null };

/** Where one numeric input came from (schema 1.1). */
export interface InputProvenance {
  kind: "measured" | "assumed" | "illustrative";
  source?: string | null;
  query?: string | null;
  timeRange?: string | null;
  /** Largest relative error of the fit at the checked quantiles. */
  fitError?: number | null;
  fittedAt?: string | null;
  note?: string | null;
  /** The value calibration set (schema 1.2): the input is measured only while it still has it. */
  value?: unknown;
}

/** One observed-against-simulated comparison of a calibration's replay. */
export interface ReplayCheck {
  scope: "end_to_end" | "component";
  id: string | null;
  label: string;
  metric: "p50" | "p95" | "p99" | "throughputRps";
  observed: number;
  simulated: number | null;
  error: number | null;
}

/** The last calibration from telemetry and how well the model replays it (schema 1.1). */
export interface CalibrationRecord {
  at: string;
  source: string;
  timeRange?: string | null;
  inputs: number;
  replay: ReplayCheck[];
  replayDigest?: string | null;
  notes: string[];
}

/** A performance input with its value and provenance (analysis and report 1.9+). */
export interface InputEntry {
  scope: "node" | "edge" | "workload";
  id: string | null;
  label: string;
  field: string;
  fieldLabel: string;
  value: string;
  provenance?: InputProvenance;
}

/** A fault injected during a run (see docs/product-spec-v2.md). */
export interface Fault {
  id: string;
  kind: "outage" | "slowdown" | "latency";
  /** node id (outage, slowdown) or edge id (latency) */
  target: string;
  startS: number;
  durationS: number;
  /** slowdown: service-time multiplier */
  factor?: number;
  /** latency: added one-way latency (ms) */
  extraMs?: number;
  label?: string | null;
}

export interface Workload {
  rps: number;
  durationS: number;
  warmupS: number;
  seed: number;
  readRatio: number;
  arrivalProcess: "poisson" | "deterministic";
  pattern: TrafficPattern;
  keyCount: number;
  keySkew: number;
  drainS?: number | null;
  faults?: Fault[];
}

/** Service-level objectives the design should meet (evaluated by the UI, not the engine). */
export interface Objectives {
  latencyP95Ms?: number | null;
  latencyP99Ms?: number | null;
  availability?: number | null;
  minThroughputRps?: number | null;
}

export interface OverrideTarget {
  kind: "node" | "edge" | "workload";
  id?: string | null;
}

export interface Override {
  target: OverrideTarget;
  path: string;
  value: unknown;
}

export interface Scenario {
  id: string;
  name: string;
  description?: string | null;
  overrides: Override[];
}

export interface DocMetadata {
  name: string;
  description?: string | null;
  tags?: string[];
}

/** Where prices come from: "generic" illustrative rates, or a price snapshot (schema 1.2). */
export interface Pricing {
  provider: string;
  region?: string | null;
  purchaseOption?: "on_demand";
  /** Instance families to snap to; empty: any in the snapshot. */
  families?: string[];
  /** Pins the snapshot of this date (YYYY-MM-DD): exactly that one is used, never a newer one. */
  snapshotDate?: string | null;
  /** With snapshotDate: the snapshot's content hash (a run's input records it). */
  snapshotSha256?: string | null;
}

export interface ArchitectureDocument {
  schemaVersion: "1.0" | "1.1" | "1.2" | "1.3";
  kind: "archsim.architecture";
  metadata: DocMetadata;
  /** false: empty CPU/memory/storage specs are not modelled (default: auto-sized by the engine). */
  architecture: { nodes: NodeSpec[]; edges: EdgeSpec[]; autoSize?: boolean };
  workload: Workload;
  scenarios: Scenario[];
  objectives?: Objectives;
  layout: {
    nodes: Record<string, { x: number; y: number }>;
    /** Where each link attaches on the canvas (rendering only). */
    edges?: Record<string, { sourceSide?: "top" | "right" | "bottom" | "left" | null; targetSide?: "top" | "right" | "bottom" | "left" | null }>;
    viewport?: { x: number; y: number; zoom: number } | null;
  };
  /** Where inputs came from, keyed nodes/<id>/<key>, edges/<id>/<key>, workload/<field> (1.1). */
  provenance?: Record<string, InputProvenance>;
  calibration?: CalibrationRecord | null;
  pricing?: Pricing;
}

// ---------------------------------------------------------------------------------------------
// Component catalog (GET /components)
// ---------------------------------------------------------------------------------------------

export type FieldKind =
  | "integer"
  | "number"
  | "enum"
  | "boolean"
  | "distribution"
  | "requestTypes"
  | "pattern"
  | "string"
  | "object"
  /** Points at another part of the design (e.g. a service's Kubernetes cluster); see refType. */
  | "nodeRef";

export interface FieldDescriptor {
  key: string;
  label: string;
  kind: FieldKind;
  nullable: boolean;
  description: string;
  default: unknown;
  assumption: boolean;
  headline: boolean;
  options?: string[];
  min?: number;
  max?: number;
  exclusiveMin?: boolean;
  unit?: string;
  group?: string;
  visibleWhen?: Record<string, unknown>;
  step?: number;
  /** For nodeRef fields: the component type the value must name. */
  refType?: string;
}

export interface PortSpec {
  name: string;
  direction: "in" | "out";
  label: string;
  description: string;
  requestTypes: RequestType[];
  minEdges: number;
  maxEdges: number | null;
}

/** A technology profile of a component type, e.g. PostgreSQL for "database". */
export interface Preset {
  id: string;
  name: string;
  technology: string;
  family: string;
  description: string;
  config: NodeConfig;
  tags: string[];
  /** The technology's tier (parts with technology profiles only). */
  tier?: Tier;
  /** Documentation behind preset numbers: config key -> link; other numbers are illustrative. */
  sources?: Record<string, string>;
}

/** Core technologies are ArchSim's focus; experimental ones rest on documentation and illustrative numbers. */
export type Tier = "core" | "experimental";

/** A part of a design with its technology and that technology's tier (analysis and report 1.8+). */
export interface TechnologyTier {
  id: string;
  label: string;
  type: string;
  technology: string;
  tier: Tier;
}

export interface ComponentType {
  type: string;
  displayName: string;
  category: string;
  description: string;
  icon: string;
  isTrafficSource: boolean;
  ports: PortSpec[];
  fields: FieldDescriptor[];
  defaultConfig: NodeConfig;
  modeled: string[];
  notModeled: string[];
  presets?: Preset[];
  /** Link settings suggested for links drawn from this component (e.g. a mobile network's latency). */
  linkDefaults?: Partial<EdgeConfig>;
  /** Behaviour each technology brings (engine 0.5+), keyed by technology id. */
  technologyTraits?: Record<string, TechTrait[]>;
  /** "Auto" settings resolved per technology: field key -> technology -> value (engine 0.5+). */
  technologyDefaults?: Record<string, Record<string, unknown>>;
  /** Documented "auto" defaults: field key -> technology -> link; the rest are illustrative. */
  technologySources?: Record<string, Record<string, string>>;
  /** Each technology's tier. */
  technologyTiers?: Record<string, Tier>;
}

/** One behaviour a technology brings, with the vendor documentation it comes from. */
export interface TechTrait {
  id: string;
  title: string;
  detail: string;
  source: string | null;
}

/** A calculation behind a number: the formula, this run's numbers in it, and the result. */
export interface ExplainEntry {
  id: string;
  title: string;
  formula: string;
  substituted: string;
  result: number | null;
  unit: string;
  note: string | null;
  source: string | null;
  /** Time series key the calculation explains, when there is one. */
  metric: string | null;
}

export interface TraceSpan {
  nodeId: string | null;
  label: string | null;
  edgeId: string | null;
  /** network, handle, queue, service, execute, lock, commit, replication, gc, cold_start, ... */
  kind: string;
  startMs: number;
  endMs: number;
  depth: number;
  detail: string | null;
}

/** One sampled request, step by step (report 1.5+). */
export interface TraceRecord {
  id: number;
  op: RequestType;
  client: string;
  startS: number;
  durationMs: number;
  status: "ok" | "error" | "timeout";
  reason: string | null;
  failedAt: string | null;
  attempts: number;
  /** Why it was kept: slowest, median, failed, sample. */
  tags: string[];
  spans: TraceSpan[];
}

export interface LatencyShare {
  nodeId: string | null;
  label: string;
  meanMs: number;
  share: number;
}

export interface Catalog {
  components: ComponentType[];
  edgeFields: FieldDescriptor[];
  workloadFields: FieldDescriptor[];
  /** What the "experimental" badge says. */
  tierNote?: string;
}

export interface Issue {
  severity: "error" | "warning";
  code: string;
  message: string;
  nodeId?: string | null;
  edgeId?: string | null;
  path?: string | null;
  hint?: string | null;
}

// ---------------------------------------------------------------------------------------------
// Projects & runs
// ---------------------------------------------------------------------------------------------

export interface ProjectSummary {
  id: string;
  name: string;
  description: string | null;
  revision: number;
  createdAt: string;
  updatedAt: string;
  nodeCount: number;
  scenarioCount: number;
}

export interface Project extends ProjectSummary {
  document: ArchitectureDocument;
}

export interface ExampleSummary {
  id: string;
  name: string;
  description: string | null;
  tags: string[];
  scenarioCount: number;
}

export type RunStatus = "queued" | "running" | "succeeded" | "failed" | "cancelled";

export interface RunSummary {
  offeredRps: number | null;
  throughputRps: number | null;
  successRate: number | null;
  timeoutRate: number | null;
  errorRate: number | null;
  p50: number | null;
  p95: number | null;
  p99: number | null;
  bottleneck: string | null;
  bottleneckNodeId: string | null;
}

export interface Run {
  id: string;
  projectId: string;
  label: string | null;
  scenarioId: string | null;
  scenarioName: string | null;
  status: RunStatus;
  progress: number;
  error: { message: string; issues?: Issue[] } | null;
  seed: number;
  engineVersion: string;
  inputDigest: string | null;
  resultDigest: string | null;
  summary: RunSummary | null;
  wallSeconds: number | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  reproducesRunId?: string | null;
}

// ---------------------------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------------------------

export interface LatencySummary {
  count: number;
  mean: number | null;
  p50: number | null;
  p90: number | null;
  p95: number | null;
  p99: number | null;
  max: number | null;
}

export interface CohortCounts {
  arrived: number;
  succeeded: number;
  failed: number;
  timedOut: number;
  outstanding: number;
}

export interface FailureRow {
  status: string;
  reason: string;
  reasonText: string;
  nodeId: string | null;
  nodeLabel: string | null;
  edgeId: string | null;
  count: number;
  share: number | null;
}

export interface ClientGroupReport {
  nodeId: string;
  label: string;
  type: string | null;
  /** Fraction of the workload this group sends (0-1). */
  share: number | null;
  arrived: number;
  succeeded: number;
  failed: number;
  timedOut: number;
  offeredRps: number | null;
  successRate: number | null;
  retryAmplification: number | null;
  latencyMs: LatencySummary;
}

export interface ReportSummary {
  arrived: number;
  succeeded: number;
  failed: number;
  timedOut: number;
  outstanding: number;
  offeredRps: number | null;
  throughputRps: number | null;
  errorRps: number | null;
  timeoutRps: number | null;
  successRate: number | null;
  errorRate: number | null;
  timeoutRate: number | null;
  attempts: number;
  retryAmplification: number | null;
  latencyMs: LatencySummary;
  /**
   * Latency counting failures: successes at their latency, each failed request as a full client
   * timeout (or longer, when retries ran past it). Missing in reports before 1.12.
   */
  latencyWithFailuresMs?: LatencySummary;
  latencyByOp: Record<RequestType, LatencySummary>;
  failureBreakdown: FailureRow[];
  /** What each client group experienced (one entry per client part). Missing in reports before 1.2. */
  clients?: ClientGroupReport[];
  accounting: {
    warmup: CohortCounts;
    measured: CohortCounts;
    total: CohortCounts;
    invariantHolds: boolean;
    drained: boolean;
  };
}

export type Series = (number | null)[];

export interface ReportTimeseries {
  bucketS: number;
  t: number[];
  offeredRps: Series;
  throughputRps: Series;
  errorRps: Series;
  timeoutRps: Series;
  attemptRps: Series;
  latencyP50Ms: Series;
  latencyP95Ms: Series;
  latencyP99Ms: Series;
}

export type NodeStatus = "saturated" | "hot" | "warm" | "ok" | "idle";

export interface NodeReportSummary {
  arrivals: number;
  arrivalRps: number | null;
  completed: number;
  failedDownstream: number;
  rejected: Record<string, number>;
  rejectedTotal: number;
  rejectionRate: number | null;
  slots: number | null;
  utilization: number | null;
  peakUtilization: number | null;
  cpuUtilization: number | null;
  meanInSystem: number | null;
  meanQueue: number | null;
  peakQueue: number;
  meanQueueWaitMs: number | null;
  p95QueueWaitMs: number | null;
  p99QueueWaitMs: number | null;
  meanHoldMs: number | null;
  meanServiceMs: number | null;
  capacityRps: number | null;
  serviceOnlyCapacityRps: number | null;
  hitRatio: number | null;
  counters: Record<string, number>;
  /** Which ceiling sets capacityRps (report 1.4+): worker/connection slots, CPU cores or memory. */
  capacityLimit?: "slots" | "cpu" | "memory" | null;
  /** CPU ceiling from the CPU spec (report 1.4+, only when set), req/s. */
  cpuCapacityRps?: number | null;
  /** Resource specs (report 1.3+, only when set): share of CPU cores busy in the window. */
  cpuCoreUtilization?: number | null;
  /** Services with memory set: highest memory one replica used. */
  memoryPeakGiB?: number;
  /** Databases with memory and a working set: share of reads served from memory. */
  bufferHitRatio?: number;
  /** Caches with memory (TTL model): keys that fit, and keys held at the end. */
  capacityKeys?: number;
  keysCached?: number;
  /** Time a call spends in this component, incl. its downstream calls (report 1.5+). */
  latencyMs?: LatencySummary;
  /** Technology behaviour figures (report 1.5+, when relevant). */
  meanLockWaitMs?: number;
  meanCommitWaitMs?: number;
  meanTicketWaitMs?: number;
  meanReplicaAckMs?: number;
  commitsPerFlush?: number | null;
  maxReplicationLagMs?: number | null;
  partitions?: number;
  gcPauses?: number;
  executorsPerShard?: number;
  [extra: string]: unknown;
}

export interface NodeReport {
  id: string;
  type: string;
  label: string;
  category: string;
  status: NodeStatus;
  statusReasons: string[];
  summary: NodeReportSummary;
  accounting: {
    arrivals: number;
    completed: number;
    failedDownstream: number;
    rejected: number;
    inProgress: number;
    invariantHolds: boolean;
  };
  timeseries: Record<string, Series>;
  config: NodeConfig;
  /** The calculations behind this node's numbers (report 1.5+). */
  explain?: ExplainEntry[];
  /** Technology traits in effect for this node (report 1.5+). */
  behaviour?: TechTrait[];
}

export interface EdgeReport {
  id: string;
  source: string;
  sourcePort: string;
  target: string;
  requestTypes: RequestType[];
  flowTypes: RequestType[];
  attempts: number;
  attemptsTotal: number;
  callRps: number | null;
  retries: number;
  timeouts: number;
  failures: number;
  /** per-bucket series, e.g. callRps, timeoutRps, failureRps (report 1.1+) */
  timeseries?: Record<string, Series>;
}

export interface Finding {
  severity: "critical" | "warning" | "info" | "ok";
  kind: string;
  nodeId: string | null;
  title: string;
  detail: string;
  suggestion?: string;
  evidence: Record<string, unknown>;
}

export interface Provenance {
  engineVersion: string;
  schemaVersion: string;
  reportVersion: string;
  seed: number;
  scenarioId: string | null;
  scenarioName: string | null;
  overrides: Override[];
  inputDigest: string;
  resultDigest: string;
  requestsGenerated: number;
  /** Simulation events processed (report 1.7+). */
  eventsProcessed?: number;
  wallSeconds: number;
  python: string;
  simpy: string;
}

export interface Report {
  reportVersion: string;
  window: { warmupS: number; durationS: number; measuredS: number; drainS: number; simulatedS: number };
  summary: ReportSummary;
  timeseries: ReportTimeseries;
  nodes: NodeReport[];
  edges: EdgeReport[];
  findings: Finding[];
  assumptions: {
    modelNotes: string[];
    userInputs: InputEntry[];
    /** How many inputs are measured, assumed and illustrative (report 1.9+). */
    provenanceCounts?: Record<InputProvenance["kind"], number>;
    calibration?: CalibrationRecord | null;
    components: Record<string, { modeled: string[]; notModeled: string[] }>;
    validationWarnings: Issue[];
    /** Each part's technology tier (report 1.8+). */
    technologies?: TechnologyTier[];
  };
  input: Pick<ArchitectureDocument, "schemaVersion" | "architecture" | "workload"> & {
    metadata: { name: string };
  };
  provenance: Provenance;
  /** A sample of individual requests, step by step (report 1.5+). */
  traces?: TraceRecord[];
  /** System-level calculations and where a request's time goes (report 1.5+). */
  explain?: { system: ExplainEntry[]; latencyBreakdown: LatencyShare[] };
  /** The CPU, memory and storage simulated, and where each came from (report 1.7+). */
  sizing?: Sizing;
  /** Cost estimate of what was simulated, at the run's measured rates (report 1.7+). */
  cost?: CostEstimate;
  /** The static design review of what was simulated (report 1.7+). */
  review?: ReviewFinding[];
}

// ---------------------------------------------------------------------------------------------
// Analysis (POST /analyze; also in reports 1.7+): computed by the engine, never in the browser
// ---------------------------------------------------------------------------------------------

export type SpecKey = "cpuCores" | "memoryGib" | "storageGib";

export interface SizingEntry {
  /** What the part is simulated with, and where each value came from. */
  specs: Partial<Record<SpecKey, { value: number; source: "set" | "auto" | "scenario" }>>;
  /** The auto size (used where the part leaves a spec empty). */
  auto: Record<SpecKey, number | null>;
  /** Peak requests per second reaching the part (static estimate). */
  peakRps: number;
  /** How each auto size was worked out. */
  why: Partial<Record<SpecKey, string>>;
  /** Price per hour of the size used (its instance type with a price snapshot). */
  pricePerHour: number | null;
  /** The instance type the size snapped to (with a price snapshot). */
  sku?: string | null;
  /** Why the part is not sized (managed service, unknown load). */
  skipped?: string;
}

export interface Sizing {
  auto: boolean;
  targetCpuUtilization: number;
  nodes: Record<string, SizingEntry>;
}

export interface NodeCost {
  id: string;
  label: string;
  type: string;
  technology: string | null;
  /** Instances billed per hour (0 for per-call pricing). */
  instances: number;
  /** $/instance-hour, or $/million calls when `unit` is "million". null = no price set. */
  unitPrice: number | null;
  /** Where a price came from: set on the part by the user, a snapshot's instance type or rate, a
   * technology preset's illustrative price, its size at generic rates, or the catalog default. */
  priceSource: "set" | "snapshot" | "preset" | "size" | "default" | null;
  /** The instance type priced (with a price snapshot), and the snapshot's date. */
  sku?: string;
  snapshotDate?: string | null;
  unit: "hour" | "million";
  monthly: number;
  /** Plain-language basis, e.g. "2 shards × 3 nodes". */
  basis: string;
}

export interface CostEstimate {
  monthly: number;
  /** $ per one million successful requests at the run's throughput; null without a run. */
  perMillionRequests: number | null;
  byNode: NodeCost[];
  notes: string[];
  /** True when at least one component carries a price. */
  hasCost: boolean;
  /** The price source used (engine 0.9+). */
  pricing?: {
    provider: string;
    region: string | null;
    snapshotDate: string | null;
    illustrative: boolean;
    sources: { name: string; url: string }[];
    /** Hash of the snapshot's content (null for generic rates). */
    sha256?: string | null;
    purchaseOption: string;
    families: string[];
  };
}

export type ReviewSeverity = "high" | "medium" | "low";

export interface ReviewFinding {
  id: string;
  severity: ReviewSeverity;
  rule: string;
  title: string;
  detail: string;
  suggestion: string;
  nodeIds: string[];
  edgeIds: string[];
}

export interface Analysis {
  scenarioId: string | null;
  peakRps: number;
  sizing: Sizing;
  cost: CostEstimate;
  review: ReviewFinding[];
  reviewCounts: Record<ReviewSeverity, number>;
  /** Display names of the review rules. */
  reviewRules: Record<string, string>;
  /** Each part's technology tier. */
  technologies?: TechnologyTier[];
  /** Every performance input with its provenance (engine 0.8+). */
  inputs?: InputEntry[];
  provenanceCounts?: Record<InputProvenance["kind"], number>;
  calibration?: CalibrationRecord | null;
}

export interface ComparisonKpi {
  key: string;
  label: string;
  unit: string;
  better: "higher" | "lower" | "neutral";
  a: number | null;
  b: number | null;
  delta: number | null;
  relativeDelta: number | null;
  verdict: "better" | "worse" | "same" | "changed" | "n/a";
}

export interface ComparisonNodeRow {
  id: string;
  label: string;
  type: string;
  statusA: NodeStatus | null;
  statusB: NodeStatus | null;
  a: Partial<NodeReportSummary> | null;
  b: Partial<NodeReportSummary> | null;
}

export interface Comparison {
  labels: { a: string; b: string };
  provenance: { a: Provenance; b: Provenance };
  inputDiff: { path: string; label: string; a: unknown; b: unknown }[];
  kpis: ComparisonKpi[];
  nodes: ComparisonNodeRow[];
  bottleneck: { a: Finding | null; b: Finding | null };
  explanations: string[];
  warnings: string[];
  timeseries: { a: ReportTimeseries; b: ReportTimeseries };
  runs: { a: string; b: string };
}
