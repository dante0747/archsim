import { describe, expect, it } from "vitest";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import { compareEstimate, confidenceHeadline, countsText, estimateText, msText } from "./confidence";
import type { Analysis, Report } from "./types";

const analysis = analysisJson as unknown as Analysis;

/** A run of the starter as far as the comparison reads it, with the engine's real estimate. */
function run(over: { p95?: number; util?: number; cores?: number; rps?: number } = {}): Report {
  const api = { utilization: over.util ?? 0.698, cpuUtilization: 0.51, cpuCoreUtilization: over.cores ?? 0.82, arrivalRps: over.rps };
  const db = { utilization: 0.06, cpuCoreUtilization: 0.16, busiestInstanceCpuUtilization: 0.27, arrivalRps: 112 };
  return {
    summary: { latencyMs: { p95: over.p95 ?? 90.8 } },
    nodes: [
      { id: "api", summary: api },
      { id: "db", summary: db },
    ],
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
    expect(cmp.text).toBe("Queueing formulas: p95 ≈ 97 ms (simulated 91 ms); API Servers 70% of its slots busy (simulated 70%).");
  });

  it("compares CPU with the cores busy when the CPU binds first, not with the slots or their own work", () => {
    const r = run({ cores: 0.66 });
    r.estimate!.busiest!.limitedBy = "cpu";
    expect(compareEstimate(r)!.text).toContain("API Servers 70% CPU busy (simulated 66%)");
  });

  it("compares a database of several instances with its busiest instance", () => {
    const r = run();
    r.estimate!.busiest = { id: "db", label: "PostgreSQL", utilization: 0.271, limitedBy: "cpu" };
    expect(compareEstimate(r)!.text).toContain("PostgreSQL's busiest instance 27% CPU busy (simulated 27%)");
  });

  it("compares CPU at the load the run sent the part, not the estimate's peak", () => {
    const r = run();
    r.estimate!.busiest = { id: "db", label: "PostgreSQL", utilization: 0.271, limitedBy: "cpu" };
    r.nodes[1].summary.arrivalRps = 84;
    const cmp = compareEstimate(r)!;
    expect(cmp.busiest).toMatchObject({ estimate: 0.271 * 0.75, simulated: 0.27 });
    expect(cmp.text).toBe(
      "Queueing formulas: p95 ≈ 97 ms at the peak (simulated 91 ms over the run); PostgreSQL's busiest instance 20% CPU busy at the 84 req/s the run sent it (simulated 27%; 27% at the 112 req/s peak).",
    );
  });

  it("gives slots at their own loads, as the hold time of a slot does not grow with load alone", () => {
    const cmp = compareEstimate(run({ rps: 300, util: 0.5 }))!;
    expect(cmp.busiest).toMatchObject({ estimate: 0.6967, simulated: 0.5 });
    expect(cmp.text).toContain("API Servers 70% of its slots busy at the 400 req/s peak (simulated 50% at the 300 req/s the run sent it)");
  });

  it("leaves the estimate as it is when the run sent the part about its peak", () => {
    const r = run();
    r.estimate!.busiest!.limitedBy = "cpu";
    r.nodes[0].summary.arrivalRps = 390;
    expect(compareEstimate(r)!.text).toContain("API Servers 70% CPU busy (simulated 82%)");
  });

  it("says when the formulas expect an overload", () => {
    const r = run({ p95: 2400 });
    r.estimate = { ...r.estimate!, overloaded: true, latencyMs: null, atRps: 500 };
    expect(compareEstimate(r)!.text).toBe("Queueing formulas: overloaded at 500 req/s (simulated p95 2.40 s); API Servers 70% of its slots busy (simulated 70%).");
  });

  it("has nothing to compare in reports before 1.13", () => {
    const r = run();
    delete r.estimate;
    expect(compareEstimate(r)).toBeNull();
  });
});
