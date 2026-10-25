/**
 * Hand-made report for dashboard tests: one component of each kind, reporting every series the
 * engine emits today plus the technology-specific ones it is about to (and one unknown key).
 */
import type { NodeReport, Report, Series } from "@/lib/types";

export const N = 20;
export const BUCKET_S = 2;

const ramp = (from: number, to: number): Series => Array.from({ length: N }, (_, i) => from + ((to - from) * i) / (N - 1));
const flat = (v: number | null): Series => Array.from({ length: N }, () => v);

export function fixtureNode(
  id: string,
  type: string,
  category: string,
  timeseries: Record<string, Series>,
  summary: Partial<NodeReport["summary"]> = {},
): NodeReport {
  return {
    id,
    type,
    label: id.charAt(0).toUpperCase() + id.slice(1),
    category,
    status: "ok",
    statusReasons: [],
    summary: { utilization: null, arrivalRps: null, slots: null, ...summary } as NodeReport["summary"],
    accounting: {} as NodeReport["accounting"],
    timeseries,
    config: {},
  };
}

const traffic = (rps: number) => ({
  arrivalRps: flat(rps),
  completedRps: flat(rps * 0.98),
  rejectedRps: flat(rps * 0.02),
  failedRps: flat(0),
});

export function fixtureNodes(): NodeReport[] {
  return [
    fixtureNode("clients", "traffic_source", "traffic", traffic(500), { arrivalRps: 500 }),
    fixtureNode(
      "lb",
      "load_balancer",
      "network",
      { ...traffic(500), utilization: flat(0.2), rateLimitedRps: flat(3), upstreamTimeoutRps: flat(0.5) },
      { utilization: 0.2, arrivalRps: 500 },
    ),
    fixtureNode(
      "api",
      "service",
      "compute",
      {
        ...traffic(490),
        utilization: ramp(0.4, 0.99),
        cpuUtilization: ramp(0.2, 0.6),
        queueMean: ramp(0, 40),
        queueMax: ramp(0, 80),
        queueWaitMs: ramp(1, 120),
        inflightMean: ramp(10, 90),
        latencyP50Ms: flat(12),
        latencyP95Ms: flat(48),
        replicas: [3, 3, 3, 3, 4, 4, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6],
        warmingReplicas: [0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        gcPauseMs: flat(30),
        eventLoopLagMs: flat(4),
        coldStartRps: flat(0),
        fooBarMs: flat(7),
      },
      { utilization: 0.8, arrivalRps: 490 },
    ),
    fixtureNode(
      "cache",
      "cache",
      "storage",
      { ...traffic(400), utilization: flat(0.1), hitRatio: flat(0.9), evictionRps: flat(12), memoryUsedRatio: ramp(0.5, 1), forkPauseMs: flat(null) },
      { utilization: 0.1, arrivalRps: 400 },
    ),
    fixtureNode(
      "db",
      "database",
      "storage",
      {
        ...traffic(60),
        utilization: flat(0.5),
        queueMean: flat(1),
        lockWaitMs: flat(3),
        commitWaitMs: flat(2),
        replicationLagMs: flat(80),
        diskReadRps: flat(15),
        staleReadRps: flat(0.2),
        consumedReadUnits: flat(120),
        consumedWriteUnits: flat(40),
        throttledRps: flat(0),
      },
      { utilization: 0.5, arrivalRps: 60 },
    ),
    fixtureNode(
      "events",
      "message_queue",
      "messaging",
      { ...traffic(50), utilization: flat(0.3), lag: ramp(0, 300), partitionLagMax: ramp(0, 120), redeliveryRps: flat(0.4), droppedRps: flat(0), publishBlockedMs: flat(5) },
      { utilization: 0.3, arrivalRps: 50 },
    ),
  ];
}

export function fixtureReport(nodes: NodeReport[] = fixtureNodes()): Report {
  const t = Array.from({ length: N }, (_, i) => i * BUCKET_S);
  return {
    reportVersion: "1.5",
    window: { warmupS: 4, durationS: 36, measuredS: 32, drainS: 4, simulatedS: 40 },
    summary: {
      arrived: 1000,
      succeeded: 950,
      failed: 40,
      timedOut: 10,
      outstanding: 0,
      offeredRps: 500,
      throughputRps: 475,
      errorRps: 20,
      timeoutRps: 5,
      successRate: 0.95,
      errorRate: 0.04,
      timeoutRate: 0.01,
      attempts: 1100,
      retryAmplification: 1.1,
      latencyMs: { count: 950, mean: 30, p50: 12, p90: 40, p95: 55, p99: 90, max: 200 },
      latencyByOp: {} as Report["summary"]["latencyByOp"],
      failureBreakdown: [
        { status: "rejected", reason: "queue_full", reasonText: "queue was full", nodeId: "api", nodeLabel: "Api", edgeId: null, count: 30, share: 0.6 },
        { status: "timeout", reason: "client_timeout", reasonText: "client timeout", nodeId: "clients", nodeLabel: "Clients", edgeId: null, count: 20, share: 0.4 },
      ],
      accounting: {} as Report["summary"]["accounting"],
    },
    timeseries: {
      bucketS: BUCKET_S,
      t,
      offeredRps: flat(500),
      throughputRps: ramp(500, 450),
      errorRps: ramp(0, 40),
      timeoutRps: ramp(0, 10),
      attemptRps: flat(550),
      latencyP50Ms: flat(12),
      latencyP95Ms: ramp(20, 90),
      latencyP99Ms: ramp(40, 160),
    },
    nodes,
    edges: [],
    findings: [],
    assumptions: { modelNotes: [], userInputs: [], components: {}, validationWarnings: [] },
    input: {
      schemaVersion: "1.0",
      metadata: { name: "fixture" },
      architecture: { nodes: [], edges: [] },
      workload: {
        rps: 500,
        durationS: 36,
        warmupS: 4,
        seed: 1,
        readRatio: 0.8,
        arrivalProcess: "poisson",
        pattern: { kind: "constant" },
        keyCount: 1000,
        keySkew: 0,
        faults: [{ id: "f1", kind: "outage", target: "db", startS: 10, durationS: 6 }],
      },
    },
    provenance: {} as Report["provenance"],
  };
}
