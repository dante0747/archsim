import { describe, expect, it } from "vitest";
import { finding, sharedFixture } from "@/test/fixtures/share";
import { previewLine, shareFindings, shareObjectives, shareVerdict, viewReport } from "./share-view";

const report = sharedFixture().report; // p95 55 ms, 475 req/s handled, 95% success, 500 offered

describe("the verdict a share shows", () => {
  it("says yes when every objective is met", () => {
    const v = shareVerdict({ latencyP95Ms: 100, availability: 0.9 }, report);
    expect(v.tone).toBe("good");
    expect(v.headline).toBe("Yes, it holds up.");
    expect(v.reason).toBe("Meets all 2 objectives at 500 req/s.");
    expect(v.slo.met).toBe(2);
  });

  it("says no and names each missed objective", () => {
    const v = shareVerdict({ latencyP95Ms: 30, availability: 0.99 }, report);
    expect(v.tone).toBe("bad");
    expect(v.headline).toBe("No, it doesn’t hold up.");
    expect(v.reason).toBe("Latency p95 55 ms (target ≤ 30 ms) · Availability 95.0% (target ≥ 99.0%)");
  });

  it("gives no yes or no without objectives, worded for a reader", () => {
    const v = shareVerdict({ latencyP95Ms: null, latencyP99Ms: null, availability: null, minThroughputRps: null }, report);
    expect(v.tone).toBe("none");
    expect(v.headline).toBe("No objectives set");
    expect(v.reason).not.toMatch(/below/);
    expect(v.reason).toContain("475 req/s");
  });

  it("names the bottleneck when the run found one", () => {
    const withBottleneck = { ...report, findings: [finding({ kind: "bottleneck", severity: "critical", title: "Db is the bottleneck" })] };
    expect(shareVerdict({ latencyP95Ms: 100 }, withBottleneck).bottleneck).toBe("Db is the bottleneck");
    expect(shareVerdict({ latencyP95Ms: 100 }, report).bottleneck).toBeNull();
  });

  it("reads unset objectives as the editor does: with the defaults", () => {
    expect(shareObjectives(undefined)).toEqual({ latencyP95Ms: 300, latencyP99Ms: null, availability: 0.999, minThroughputRps: null });
    expect(shareObjectives({ latencyP95Ms: 50, availability: null })).toMatchObject({ latencyP95Ms: 50, availability: null });
  });
});

describe("what the page draws", () => {
  it("keeps the summary, charts and findings but not per-part detail", () => {
    const view = viewReport({ ...report, assumptions: { ...report.assumptions, technologies: [{ id: "q", label: "Queue", type: "message_queue", technology: "x", tier: "experimental" }] } });
    expect(Object.keys(view).sort()).toEqual(["confidence", "cost", "findings", "provenance", "reportVersion", "summary", "technologies", "timeseries", "window"]);
    expect(view.technologies).toHaveLength(1);
    expect(view).not.toHaveProperty("nodes");
    expect(view).not.toHaveProperty("input");
  });

  it("lists problems first and the healthy line only when it is alone", () => {
    const list = [finding({ severity: "info", title: "note" }), finding({ severity: "critical", title: "worst" }), finding({ severity: "ok", kind: "healthy", title: "Healthy" })];
    expect(shareFindings(list).map((f) => f.title)).toEqual(["worst", "note"]);
    expect(shareFindings([finding({ severity: "ok", kind: "healthy", title: "Healthy" })]).map((f) => f.title)).toEqual(["Healthy"]);
  });

  it("previews the verdict with p95, throughput and confidence in one line", () => {
    expect(previewLine({ latencyP95Ms: 100, availability: 0.9 }, report)).toBe("Yes, it holds up. p95 55.0 ms · 475 req/s · High confidence");
  });
});
