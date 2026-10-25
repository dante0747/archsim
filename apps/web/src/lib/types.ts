/** Types mirroring the versioned document schema and the API (see docs/schema.md, docs/api.md). */

export type RequestType = "read" | "write";

export type Distribution =
  | { kind: "constant"; value: number }
  | { kind: "exponential"; mean: number }
  | { kind: "uniform"; min: number; max: number }
  | { kind: "lognormal"; median: number; p95: number };

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
  | { kind: "burst"; burstRps: number; startS: number; durationS: number };

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

export interface ArchitectureDocument {
  schemaVersion: "1.0";
  kind: "archsim.architecture";
  metadata: DocMetadata;
  architecture: { nodes: NodeSpec[]; edges: EdgeSpec[] };
  workload: Workload;
  scenarios: Scenario[];
  objectives?: Objectives;
  layout: {
    nodes: Record<string, { x: number; y: number }>;
    /** Where each link attaches on the canvas (rendering only). */
    edges?: Record<string, { sourceSide?: "top" | "right" | "bottom" | "left" | null; targetSide?: "top" | "right" | "bottom" | "left" | null }>;
    viewport?: { x: number; y: number; zoom: number } | null;
  };
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
  | "object";

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
}

export interface Catalog {
  components: ComponentType[];
  edgeFields: FieldDescriptor[];
  workloadFields: FieldDescriptor[];
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
    userInputs: {
      scope: "node" | "edge" | "workload";
      id: string | null;
      label: string;
      field: string;
      fieldLabel: string;
      value: string;
    }[];
    components: Record<string, { modeled: string[]; notModeled: string[] }>;
    validationWarnings: Issue[];
  };
  input: Pick<ArchitectureDocument, "schemaVersion" | "architecture" | "workload"> & {
    metadata: { name: string };
  };
  provenance: Provenance;
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
