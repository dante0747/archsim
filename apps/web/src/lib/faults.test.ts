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
  presetActive,
  sameExperiment,
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

describe("sameExperiment", () => {
  const plan: Workload = {
    ...workload,
    faults: [{ id: "f1", kind: "outage", target: "cache", startS: 28, durationS: 15 }],
    pattern: { kind: "burst", burstRps: 900, startS: 30, durationS: 10 },
  };

  it("ignores key order and the defaults the engine fills into a stored run", () => {
    // As stored by the API: the engine adds a factor and extra latency to every fault, and
    // PostgreSQL (JSONB) returns keys shortest first.
    const stored = JSON.parse(
      '{"rps":400,"durationS":60,"warmupS":10,"pattern":{"kind":"burst","startS":30,"burstRps":900,"durationS":10},' +
        '"faults":[{"id":"f1","kind":"outage","label":null,"factor":4.0,"startS":28,"target":"cache","extraMs":200.0,"durationS":15}]}',
    ) as Workload;
    expect(sameExperiment(stored, plan)).toBe(true);
  });

  it("applies the engine's defaults to a slowdown without a factor", () => {
    const slow: Fault = { id: "s", kind: "slowdown", target: "db", startS: 20, durationS: 10 };
    expect(sameExperiment({ ...plan, faults: [{ ...slow, factor: 4 }] }, { ...plan, faults: [slow] })).toBe(true);
    expect(sameExperiment({ ...plan, faults: [{ ...slow, factor: 8 }] }, { ...plan, faults: [slow] })).toBe(false);
  });

  it("notices a changed failure, traffic shape or rate", () => {
    expect(sameExperiment(plan, { ...plan, faults: [] })).toBe(false);
    expect(sameExperiment(plan, { ...plan, faults: [{ ...plan.faults![0], durationS: 20 }] })).toBe(false);
    expect(sameExperiment(plan, { ...plan, pattern: { kind: "constant" } })).toBe(false);
    expect(sameExperiment(plan, { ...plan, rps: 500 })).toBe(false);
  });
});

describe("presetActive", () => {
  const presets = chaosPresets(nodes, edges, null, workload);
  const byId = (id: string) => presets.find((p) => p.id === id)!;
  /** What the API hands back after a save: the engine's defaults filled in, keys reordered. */
  const stored = (w: Workload): Workload => ({
    ...w,
    faults: (w.faults ?? []).map((f) => JSON.parse(JSON.stringify({ label: null, factor: 4, extraMs: 200, ...f }))),
  });

  it("stays on after a save and reload fills in the engine's defaults", () => {
    for (const id of ["lose-cache", "slow-db", "lag-api-db"]) {
      const planned = { ...workload, ...byId(id).apply(workload) };
      expect(presetActive(byId(id), planned)).toBe(true);
      expect(presetActive(byId(id), stored(planned))).toBe(true);
    }
    const spike = { ...workload, ...byId("spike").apply(workload) };
    expect(presetActive(byId("spike"), spike)).toBe(true);
  });

  it("ignores a fault's id and label, not what it does", () => {
    const planned = stored({ ...workload, ...byId("slow-db").apply(workload) });
    const f = planned.faults![0];
    expect(presetActive(byId("slow-db"), { ...planned, faults: [{ ...f, id: "f7", label: "DB brown-out" }] })).toBe(true);
    expect(presetActive(byId("slow-db"), { ...planned, faults: [{ ...f, factor: 8 }] })).toBe(false);
    expect(presetActive(byId("slow-db"), { ...planned, faults: [{ ...f, durationS: 30 }] })).toBe(false);
    expect(presetActive(byId("slow-db"), { ...planned, faults: [f, { ...f, id: "f2" }] })).toBe(false);
  });

  it("is off when nothing or something else is planned", () => {
    expect(presetActive(byId("lose-cache"), workload)).toBe(false);
    expect(presetActive(byId("lose-cache"), { ...workload, ...byId("slow-db").apply(workload) })).toBe(false);
    // the spike plans no failures: a planned outage turns it off
    const spike = { ...workload, ...byId("spike").apply(workload) };
    expect(presetActive(byId("spike"), { ...spike, faults: byId("lose-cache").apply(workload).faults })).toBe(false);
  });
});
