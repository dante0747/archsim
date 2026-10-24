import { describe, expect, it } from "vitest";
import {
  axisDuration,
  bucketIndex,
  detectEvents,
  edgeRateAt,
  faultsAt,
  fmtClock,
  fmtRate,
  heatColor,
  phaseAt,
  runDuration,
  statusAt,
  tickStep,
  valueAt,
} from "./recorder";
import type { Fault, NodeReport, Report } from "./types";

const N = 10;
const zeros = () => Array.from({ length: N }, () => 0);

function node(id: string, ts: Record<string, (number | null)[]>, slots: number | null = 4): NodeReport {
  return {
    id,
    type: "service",
    label: id.toUpperCase(),
    category: "compute",
    status: "ok",
    statusReasons: [],
    summary: { slots } as NodeReport["summary"],
    accounting: {} as NodeReport["accounting"],
    timeseries: ts,
    config: {},
  };
}

function report(opts: { nodes?: NodeReport[]; faults?: Fault[]; p95?: (number | null)[]; errorRps?: number[]; timeoutRps?: number[]; edgeTs?: boolean } = {}): Report {
  return {
    window: { warmupS: 2, durationS: 8, measuredS: 6, drainS: 2, simulatedS: 10 },
    timeseries: {
      bucketS: 1,
      t: Array.from({ length: N }, (_, i) => i),
      offeredRps: Array.from({ length: N }, () => 100),
      throughputRps: Array.from({ length: N }, () => 100),
      errorRps: opts.errorRps ?? zeros(),
      timeoutRps: opts.timeoutRps ?? zeros(),
      attemptRps: Array.from({ length: N }, () => 100),
      latencyP50Ms: Array.from({ length: N }, () => 10),
      latencyP95Ms: opts.p95 ?? Array.from({ length: N }, () => 20),
      latencyP99Ms: Array.from({ length: N }, () => 30),
    },
    nodes: opts.nodes ?? [],
    edges: [
      {
        id: "a-b",
        source: "a",
        sourcePort: "out",
        target: "b",
        requestTypes: ["read"],
        flowTypes: ["read"],
        attempts: 0,
        attemptsTotal: 0,
        callRps: 0,
        retries: 0,
        timeouts: 0,
        failures: 0,
        ...(opts.edgeTs ? { timeseries: { callRps: Array.from({ length: N }, (_, i) => i * 10) } } : {}),
      },
    ],
    input: { workload: { faults: opts.faults ?? [] } },
  } as unknown as Report;
}

describe("time and sampling", () => {
  it("maps seconds to buckets and clamps", () => {
    expect(bucketIndex(0, 1)).toBe(0);
    expect(bucketIndex(2.999, 1)).toBe(2);
    expect(bucketIndex(3, 1)).toBe(3);
    expect(bucketIndex(-4, 1)).toBe(0);
    expect(bucketIndex(99, 1, 10)).toBe(9);
    expect(bucketIndex(5, 2.5)).toBe(2);
  });

  it("reads step and linear values", () => {
    const s = [0, 10, 20, null, 40];
    expect(valueAt(s, 1.2, 1)).toBe(10);
    expect(valueAt(s, 1.5, 1, "linear")).toBe(10); // bucket centre
    expect(valueAt(s, 2.0, 1, "linear")).toBe(15);
    expect(valueAt(s, 3.2, 1, "step")).toBeNull();
    expect(valueAt(s, 3.0, 1, "linear")).toBe(20); // falls back to the side with data
    expect(valueAt(s, 9, 1)).toBeNull();
    expect(valueAt(undefined, 1, 1)).toBeNull();
  });

  it("knows the recording length, axis and phase", () => {
    const r = report();
    expect(runDuration(r)).toBe(10);
    expect(axisDuration(60, r)).toBe(60);
    expect(axisDuration(5, r)).toBe(10);
    expect(axisDuration(30, null)).toBe(30);
    expect(phaseAt(1, r.window)).toBe("warm-up");
    expect(phaseAt(5, r.window)).toBe("measured");
    expect(phaseAt(9, r.window)).toBe("drain");
  });

  it("finds active faults", () => {
    const faults: Fault[] = [{ id: "f", kind: "outage", target: "a", startS: 3, durationS: 2 }];
    expect(faultsAt(faults, 2.9)).toHaveLength(0);
    expect(faultsAt(faults, 3)).toHaveLength(1);
    expect(faultsAt(faults, 5)).toHaveLength(0);
  });

  it("derives per-bucket status with the whole-run thresholds", () => {
    expect(statusAt(0.5, 100, 0)).toBe("ok");
    expect(statusAt(0.75, 100, 0)).toBe("warm");
    expect(statusAt(0.9, 100, 0)).toBe("hot");
    expect(statusAt(0.97, 100, 0)).toBe("saturated");
    expect(statusAt(0.2, 100, 5)).toBe("saturated");
    expect(statusAt(0, 0, 0)).toBe("idle");
  });

  it("uses the link series and falls back to the callee arrivals", () => {
    const b = node("b", { arrivalRps: Array.from({ length: N }, () => 7) });
    expect(edgeRateAt(report({ nodes: [b], edgeTs: true }), "a-b", 4.5)).toBe(40);
    expect(edgeRateAt(report({ nodes: [b] }), "a-b", 4.5)).toBe(7);
    expect(edgeRateAt(report({ nodes: [b] }), "nope", 1)).toBeNull();
  });
});

describe("detectEvents", () => {
  it("reports fault windows, saturation with hysteresis, queues and scaling", () => {
    const util = [0.2, 0.5, 0.96, 0.97, 0.9, 0.96, 0.5, 0.97, 0.3, 0.2];
    const queue = [0, 0, 1, 8, 9, 2, 0, 0, 0, 0];
    const replicas = [2, 2, 2, 4, 4, 4, 3, 3, 3, 3];
    const api = node("api", { utilization: util, queueMean: queue, replicas });
    const faults: Fault[] = [{ id: "f", kind: "outage", target: "api", startS: 4, durationS: 3 }];
    const ev = detectEvents(report({ nodes: [api], faults }), { latencyP95Ms: null, availability: null });
    const kinds = ev.map((e) => `${e.kind}@${e.t}`);
    // saturated at 2, stays armed-off through 0.9, re-arms after 0.5 and fires again at 7
    expect(kinds).toContain("saturation@2");
    expect(kinds).not.toContain("saturation@5");
    expect(kinds).toContain("saturation@7");
    expect(kinds).toContain("queue_growth@3");
    expect(kinds).toContain("scale_up@3");
    expect(kinds).toContain("scale_down@6");
    expect(kinds).toContain("fault_start@4");
    expect(kinds).toContain("fault_end@7");
    expect(ev.find((e) => e.kind === "fault_start")?.label).toBe("Outage: API");
    // sorted by time
    expect(ev.map((e) => e.t)).toEqual([...ev.map((e) => e.t)].sort((a, b) => a - b));
  });

  it("reports SLO breach and recovery, and timeouts appearing", () => {
    const p95 = [20, 20, 400, 450, 20, 20, 20, 20, 20, 20];
    const timeoutRps = [0, 0, 0, 5, 5, 0, 0, 0, 0, 0];
    const ev = detectEvents(report({ p95, timeoutRps }), { latencyP95Ms: 300, availability: null });
    const kinds = ev.map((e) => `${e.kind}@${e.t}`);
    expect(kinds).toContain("slo_breach@2");
    expect(kinds).toContain("slo_recovered@4");
    expect(kinds).toContain("timeouts@3");
    expect(kinds.filter((k) => k.startsWith("slo_breach"))).toHaveLength(1);
  });

  it("describes slowdown and latency faults", () => {
    const faults: Fault[] = [
      { id: "s", kind: "slowdown", target: "api", startS: 1, durationS: 2, factor: 4 },
      { id: "l", kind: "latency", target: "a-b", startS: 2, durationS: 2, extraMs: 300 },
    ];
    const ev = detectEvents(report({ nodes: [node("api", {}), node("a", {}), node("b", {})], faults }), {});
    expect(ev.find((e) => e.t === 1 && e.kind === "fault_start")?.label).toBe("API ×4 slower");
    expect(ev.find((e) => e.t === 2 && e.kind === "fault_start")?.label).toBe("+300 ms on A → B");
  });
});

describe("heat colors and formatting", () => {
  it("is neutral at zero, a blue ramp below 70%, status colors above", () => {
    expect(heatColor(null, false)).toBe("transparent");
    expect(heatColor(0, false)).toBe("var(--surface-3)");
    expect(heatColor(0.01, false)).toMatch(/^#[0-9a-f]{6}$/);
    expect(heatColor(0.35, true)).toMatch(/^#[0-9a-f]{6}$/);
    expect(heatColor(0.35, true)).not.toBe(heatColor(0.35, false));
    expect(heatColor(0.75, false)).toBe("var(--status-warning)");
    expect(heatColor(0.9, true)).toBe("var(--status-serious)");
    expect(heatColor(1.2, true)).toBe("var(--status-critical)");
  });

  it("formats clock, rates and ticks", () => {
    expect(fmtClock(23.44)).toBe("23.4 s");
    expect(fmtClock(125)).toBe("2:05.0");
    expect(fmtClock(null)).toBe("–");
    expect(fmtRate(1234)).toBe("1.2k");
    expect(fmtRate(412.4)).toBe("412");
    expect(fmtRate(3.21)).toBe("3.2");
    expect(fmtRate(0.04)).toBe("0.04");
    expect(tickStep(60, 600)).toBe(10);
    expect(tickStep(60, 3000)).toBe(2);
  });
});
