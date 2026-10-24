import { describe, expect, it } from "vitest";
import starter from "@/test/fixtures/starter.json";
import {
  MAX_SWEEP_STEPS,
  SWEEP_SCENARIO_PREFIX,
  type SweepRow,
  analyzeSweep,
  buildSweepDocument,
  rowMeetsSlo,
  suggestRange,
  sweepLevels,
  sweepRow,
} from "./sweep";
import type { ArchitectureDocument, Report } from "./types";

const doc = starter as unknown as ArchitectureDocument;

const row = (rps: number, throughput: number, p95: number, successRate = 1, bottleneck: string | null = null): SweepRow => ({
  rps,
  runId: null,
  offered: rps,
  throughput,
  p50: p95 / 3,
  p95,
  p99: p95 * 1.5,
  successRate,
  errorRate: 1 - successRate,
  bottleneck,
});

describe("sweep levels", () => {
  it("spaces levels evenly, rounds them and caps the step count at the batch limit", () => {
    expect(sweepLevels({ fromRps: 100, toRps: 1000, steps: 4 })).toEqual([100, 400, 700, 1000]);
    expect(sweepLevels({ fromRps: 50, toRps: 2000, steps: 40 })).toHaveLength(MAX_SWEEP_STEPS);
    expect(sweepLevels({ fromRps: 1000, toRps: 100, steps: 2 })).toEqual([100, 1000]);
  });

  it("suggests a range around the current load", () => {
    const r = suggestRange(400);
    expect(r.fromRps).toBeLessThan(400);
    expect(r.toRps).toBeGreaterThanOrEqual(1200);
  });
});

describe("buildSweepDocument", () => {
  it("adds one constant-load, fault-free scenario per level without touching the original", () => {
    const before = JSON.stringify(doc);
    const { document, scenarioIds, levels } = buildSweepDocument(doc, { fromRps: 100, toRps: 900, steps: 5, durationS: 20, warmupS: 5 });
    expect(JSON.stringify(doc)).toBe(before);
    expect(levels).toEqual([100, 300, 500, 700, 900]);
    expect(scenarioIds).toHaveLength(5);
    expect(document.scenarios).toHaveLength(doc.scenarios.length + 5);
    const s = document.scenarios.find((x) => x.id === scenarioIds[2])!;
    expect(s.id.startsWith(SWEEP_SCENARIO_PREFIX)).toBe(true);
    expect(s.id).toMatch(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/);
    const byPath = Object.fromEntries(s.overrides.map((o) => [o.path, o.value]));
    expect(byPath).toEqual({ pattern: { kind: "constant" }, rps: 500, durationS: 20, warmupS: 5, faults: [] });
    expect(s.overrides.every((o) => o.target.kind === "workload")).toBe(true);
  });

  it("keeps warm-up shorter than the run", () => {
    const { document, scenarioIds } = buildSweepDocument(doc, { fromRps: 10, toRps: 20, steps: 2, durationS: 4, warmupS: 10 });
    const s = document.scenarios.find((x) => x.id === scenarioIds[0])!;
    const w = Object.fromEntries(s.overrides.map((o) => [o.path, o.value]));
    expect(w.warmupS as number).toBeLessThan(w.durationS as number);
  });
});

describe("analyzeSweep", () => {
  const obj = { latencyP95Ms: 300, availability: 0.99 };

  it("finds the knee where throughput stops tracking load and the max load within the SLO", () => {
    const rows = [
      row(200, 200, 40),
      row(400, 399, 60),
      row(600, 598, 150),
      row(800, 640, 900, 0.8, "API Servers"),
      row(1000, 650, 1500, 0.65, "API Servers"),
    ];
    const a = analyzeSweep(rows, obj);
    expect(a.knee?.rps).toBe(800);
    expect(a.kneeReason).toBe("throughput");
    expect(a.maxWithinSlo?.rps).toBe(600);
    expect(a.saturatedAt).toBe("API Servers");
    expect(a.verdict).toBe("Holds 600 req/s within the objectives; saturates around 800 req/s at API Servers.");
  });

  it("detects a latency knee even when throughput still keeps up", () => {
    const a = analyzeSweep([row(100, 100, 20), row(200, 200, 25), row(300, 299, 90)], { latencyP95Ms: 500 });
    expect(a.knee?.rps).toBe(300);
    expect(a.kneeReason).toBe("latency");
  });

  it("says when no saturation was found, or when even the lightest step fails", () => {
    expect(analyzeSweep([row(100, 100, 20), row(200, 200, 22)], obj).verdict).toMatch(/no sign of saturation/);
    expect(analyzeSweep([row(100, 100, 400), row(200, 200, 450)], obj).verdict).toMatch(/already at 100 req\/s/);
    expect(analyzeSweep([], obj).verdict).toBe("No completed steps yet.");
  });

  it("ignores steps that did not complete", () => {
    const a = analyzeSweep([row(100, 100, 20), sweepRow(200, null)], obj);
    expect(a.rows).toHaveLength(1);
  });

  it("checks each step against latency and availability objectives", () => {
    expect(rowMeetsSlo(row(100, 100, 200), obj)).toBe(true);
    expect(rowMeetsSlo(row(100, 100, 400), obj)).toBe(false);
    expect(rowMeetsSlo(row(100, 100, 200, 0.95), obj)).toBe(false);
    expect(rowMeetsSlo(sweepRow(100, null), obj)).toBeNull();
  });
});

describe("sweepRow", () => {
  it("reads KPIs and the bottleneck label from a report", () => {
    const report = {
      summary: { offeredRps: 500, throughputRps: 480, successRate: 0.96, latencyMs: { p50: 10, p95: 50, p99: 90 } },
      findings: [{ kind: "bottleneck", nodeId: "api" }],
      nodes: [{ id: "api", label: "API Servers" }],
    } as unknown as Report;
    const r = sweepRow(500, report, "run-1");
    expect(r).toMatchObject({ rps: 500, runId: "run-1", throughput: 480, p95: 50, bottleneck: "API Servers" });
    expect(r.errorRate).toBeCloseTo(0.04, 9);
  });
});
