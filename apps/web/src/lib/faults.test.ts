import { describe, expect, it } from "vitest";
import {
  centredWindow,
  chaosPresets,
  clampFault,
  experimentVerdict,
  faultLabel,
  faultTargets,
  faultsActiveAt,
  newFault,
  nextFaultId,
  targetNames,
  windowEvidence,
} from "./faults";
import type { Fault, Report, Workload } from "./types";

const workload: Workload = {
  rps: 400,
  durationS: 60,
  warmupS: 10,
  seed: 42,
  readRatio: 0.9,
  arrivalProcess: "poisson",
  pattern: { kind: "constant" },
  keyCount: 1000,
  keySkew: 0.99,
  faults: [],
};

const nodes = [
  { id: "clients", data: { type: "traffic_source", label: "Clients" } },
  { id: "api", data: { type: "service", label: "API" } },
  { id: "cache", data: { type: "cache", label: "Redis" } },
  { id: "db", data: { type: "database", label: "Postgres" } },
];
const edges = [
  { id: "c-api", source: "clients", target: "api" },
  { id: "api-cache", source: "api", target: "cache" },
  { id: "api-db", source: "api", target: "db" },
];

describe("fault helpers", () => {
  it("creates a fault centred in the measured window", () => {
    const f = newFault("outage", "cache", workload);
    expect(f).toMatchObject({ id: "f1", kind: "outage", target: "cache" });
    expect(f.durationS).toBe(13); // a quarter of the 50 s measured window, rounded
    expect(f.startS).toBeGreaterThanOrEqual(10);
    expect(f.startS + f.durationS).toBeLessThanOrEqual(60);
    expect(newFault("slowdown", "db", workload).factor).toBe(4);
    expect(newFault("latency", "api-db", workload).extraMs).toBe(300);
  });

  it("picks the next free id", () => {
    expect(nextFaultId([{ id: "f1" }, { id: "f3" }])).toBe("f2");
  });

  it("clamps windows and parameters to the run", () => {
    const f: Fault = { id: "f1", kind: "slowdown", target: "db", startS: 58, durationS: 20, factor: 500 };
    const c = clampFault(f, 60);
    expect(c.startS).toBe(58);
    expect(c.durationS).toBe(2);
    expect(c.factor).toBe(100);
    expect(clampFault({ ...f, startS: -5 }, 60).startS).toBe(0);
  });

  it("centres windows and lists active faults", () => {
    expect(centredWindow(workload, 10)).toEqual({ startS: 30, durationS: 10 });
    const faults: Fault[] = [{ id: "f1", kind: "outage", target: "cache", startS: 20, durationS: 10 }];
    expect(faultsActiveAt(faults, 25)).toHaveLength(1);
    expect(faultsActiveAt(faults, 30)).toHaveLength(0);
  });

  it("offers node targets except the traffic source, and links for latency", () => {
    const t = faultTargets(nodes, edges, null);
    expect(t.outage.map((x) => x.id)).toEqual(["api", "cache", "db"]);
    expect(t.latency.map((x) => x.label)).toContain("API → Postgres");
  });

  it("labels faults with names", () => {
    const names = targetNames(nodes, edges);
    expect(faultLabel({ id: "f", kind: "slowdown", target: "db", startS: 0, durationS: 1, factor: 4 }, names)).toBe("Slowdown ×4 · Postgres");
    expect(faultLabel({ id: "f", kind: "latency", target: "api-db", startS: 0, durationS: 1, extraMs: 300 }, names)).toBe("+300 ms · API → Postgres");
  });

  it("builds graph-aware chaos presets that replace current faults", () => {
    const presets = chaosPresets(nodes, edges, null, workload);
    const titles = presets.map((p) => p.title);
    expect(titles[0]).toBe("Lose Redis for 15 s");
    expect(titles).toContain("Postgres 4× slower for 20 s");
    expect(titles).toContain("+300 ms on API → Postgres");
    const patch = presets[0].apply({ ...workload, faults: [{ id: "old", kind: "outage", target: "db", startS: 0, durationS: 1 }] });
    expect(patch.faults).toHaveLength(1);
    expect(patch.faults?.[0]).toMatchObject({ kind: "outage", target: "cache", durationS: 15 });
    const spike = presets.find((p) => p.kind === "spike")!;
    expect(spike.apply(workload).pattern).toMatchObject({ kind: "burst", burstRps: 1200, durationS: 10 });
  });
});

function report(opts: { errorsFrom?: number; errorsTo?: number; faults?: Fault[]; successRate?: number; p95?: number }): Pick<Report, "timeseries" | "summary" | "input"> {
  const n = 60;
  const t = Array.from({ length: n }, (_, i) => i);
  const inWin = (i: number) => opts.errorsFrom != null && i >= opts.errorsFrom && i < (opts.errorsTo ?? n);
  const throughput = t.map((i) => (inWin(i) ? 200 : 400));
  const errors = t.map((i) => (inWin(i) ? 200 : 0));
  const zero = t.map(() => 0);
  const lat = t.map(() => 80);
  return {
    timeseries: {
      bucketS: 1,
      t,
      offeredRps: t.map(() => 400),
      throughputRps: throughput,
      errorRps: errors,
      timeoutRps: zero,
      attemptRps: t.map(() => 400),
      latencyP50Ms: lat,
      latencyP95Ms: lat,
      latencyP99Ms: lat,
    },
    summary: {
      latencyMs: { count: 1, mean: 50, p50: 50, p90: 70, p95: opts.p95 ?? 80, p99: 90, max: 100 },
      successRate: opts.successRate ?? 1,
      throughputRps: 400,
      accounting: { measured: { arrived: 20000, succeeded: 20000, failed: 0, timedOut: 0, outstanding: 0 } },
    } as unknown as Report["summary"],
    input: { workload: { ...workload, faults: opts.faults ?? [] } } as unknown as Report["input"],
  };
}

const objectives = { latencyP95Ms: 300, availability: 0.999 };

describe("experiment verdict", () => {
  it("holds when objectives are met overall and inside the fault window", () => {
    const f: Fault = { id: "f1", kind: "latency", target: "api-db", startS: 20, durationS: 10, extraMs: 50 };
    const v = experimentVerdict(report({ faults: [f] }), objectives);
    expect(v.held).toBe(true);
    expect(v.windows[0]).toMatchObject({ pass: true, breached: 0, buckets: 10, success: 1 });
    expect(v.worst).toBeNull();
  });

  it("is violated when success drops inside the window, and finds the worst moment", () => {
    const f: Fault = { id: "f1", kind: "outage", target: "cache", startS: 20, durationS: 10 };
    const r = report({ errorsFrom: 20, errorsTo: 30, faults: [f], successRate: 0.92 });
    const v = experimentVerdict(r, objectives, { cache: "Redis" });
    expect(v.held).toBe(false);
    expect(v.summary).toContain("success fell to 50%");
    expect(v.summary).toContain("Outage · Redis (20–30 s)");
    expect(v.worst?.t).toBe(20);
    expect(windowEvidence(r, objectives, f).breached).toBe(10);
  });

  it("has no hypothesis without objectives", () => {
    expect(experimentVerdict(report({}), {}).held).toBeNull();
  });
});
