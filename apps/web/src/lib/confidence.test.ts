import { describe, expect, it } from "vitest";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import { compareEstimate, confidenceHeadline, countsText, estimateText, msText } from "./confidence";
import type { Analysis, Report } from "./types";

const analysis = analysisJson as unknown as Analysis;

/** A run of the starter as far as the comparison reads it, with the engine's real estimate. */
function run(over: { p95?: number; util?: number; cpu?: number } = {}): Report {
  return {
    summary: { latencyMs: { p95: over.p95 ?? 90.8 } },
    nodes: [{ id: "api", summary: { utilization: over.util ?? 0.698, cpuUtilization: over.cpu ?? 0.51 } }],
    estimate: structuredClone(analysis.estimate),
  } as unknown as Report;
}

describe("confidence words", () => {
  it("says the engine's level and its deciding reason", () => {
    expect(analysis.confidence?.level).toBe("low");
    expect(confidenceHeadline(analysis.confidence!)).toBe("Low confidence: 31 of 35 inputs are illustrative defaults");
  });

  it("counts documented inputs with the others", () => {
    expect(countsText({ measured: 1, documented: 2, assumed: 3, illustrative: 4 })).toBe(
      "1 measured from telemetry, 2 documented by the vendor, 3 assumed (typed in), 4 illustrative (ArchSim defaults and presets)",
    );
    // reports before 1.13 have no "documented" count
    expect(countsText({ measured: 0, assumed: 4, illustrative: 31 })).toContain("0 documented by the vendor");
  });

  it("formats latencies for prose", () => {
    expect(msText(96.78)).toBe("97 ms");
    expect(msText(8.44)).toBe("8.4 ms");
    expect(msText(1234)).toBe("1.23 s");
    expect(msText(null)).toBe("–");
  });
});

describe("instant estimate", () => {
  it("reads as the estimate the engine made, labelled as not simulated", () => {
    expect(estimateText(analysis.estimate!)).toMatch(/^Estimate from queueing formulas \(not simulated\): p95 ≈ \d+ ms; busiest API Servers 70%$/);
  });

  it("is set against the simulation after the run", () => {
    const cmp = compareEstimate(run())!;
    expect(cmp.p95).toEqual({ estimate: analysis.estimate!.latencyMs!.p95, simulated: 90.8 });
    expect(cmp.busiest).toMatchObject({ id: "api", label: "API Servers", simulated: 0.698 });
    expect(cmp.text).toBe("Queueing formulas: p95 ≈ 97 ms (simulated 91 ms); API Servers 70% busy (simulated 70%).");
  });

  it("compares CPU with CPU when the CPU binds first", () => {
    const r = run({ cpu: 0.82 });
    r.estimate!.busiest!.limitedBy = "cpu";
    expect(compareEstimate(r)!.text).toContain("API Servers 70% CPU busy (simulated 82%)");
  });

  it("says when the formulas expect an overload", () => {
    const r = run({ p95: 2400 });
    r.estimate = { ...r.estimate!, overloaded: true, latencyMs: null, atRps: 500 };
    expect(compareEstimate(r)!.text).toBe("Queueing formulas: overloaded at 500 req/s (simulated p95 2.40 s); API Servers 70% busy (simulated 70%).");
  });

  it("has nothing to compare in reports before 1.13", () => {
    const r = run();
    delete r.estimate;
    expect(compareEstimate(r)).toBeNull();
  });
});
