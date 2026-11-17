import { describe, expect, it } from "vitest";
import analysisJson from "@/test/fixtures/analysis-starter.json";
import starter from "@/test/fixtures/starter.json";
import { decisionReportMarkdown } from "./report-md";
import { seedCappedConfidence } from "./report-md-seeds";
import {
  capConfidence,
  fmtRangeMs,
  isEstimated,
  nearObjective,
  quickCheckOf,
  rangeOf,
  rangeText,
  seedRange,
  seedsText,
  simulationModeOf,
} from "./seed-groups";
import type { Analysis, ArchitectureDocument, Confidence, Report, Run, RunSummary } from "./types";

const OBJ = { latencyP95Ms: 120, latencyP99Ms: null, availability: 0.999, minThroughputRps: null };

function summary(p95: number, successRate = 1): RunSummary {
  return { offeredRps: 400, throughputRps: 400, successRate, timeoutRate: 0, errorRate: 0, p50: 40, p95, p99: p95 * 1.5, bottleneck: null, bottleneckNodeId: null };
}

function seedRun(seed: number, p95: number | null, status: Run["status"] = "succeeded", successRate = 1): Pick<Run, "seed" | "status" | "summary"> {
  return { seed, status, summary: p95 == null ? null : summary(p95, successRate) };
}

const FIVE = [seedRun(42, 98.2), seedRun(43, 104.4), seedRun(44, 131), seedRun(45, 101), seedRun(46, 110.3)];

describe("seed ranges", () => {
  it("reads like the engine: verdict split, p95 range with its median, seeds", () => {
    const r = seedRange(FIVE, OBJ);
    expect(r.text).toBe("Meets the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds 42–46");
    expect([r.met, r.missed, r.finished, r.runs]).toEqual([4, 1, 5, 5]);
    expect(r.verdict).toBe("borderline");
    expect(r.borderline).toBe(true);
    expect(r.p95).toEqual({ min: 98.2, max: 131, median: 104.4 });
  });

  it("judges a design with a queue by its lost messages and its messages' age, as the engine does", () => {
    const queued = (seed: number, availability: number, messageAgeP95Ms: number) => ({
      ...seedRun(seed, 90),
      summary: { ...summary(90), lostMessages: 3, availability, messageAgeP95Ms },
    });
    const r = seedRange([queued(1, 0.99, 2000), queued(2, 1, 21000)], { ...OBJ, messageAgeP95Ms: 10000 });
    expect([r.met, r.missed, r.verdict]).toEqual([0, 2, "fails"]);
  });

  it("agrees, fails, waits for unfinished runs and works without objectives", () => {
    const holds = seedRange([seedRun(7, 90), seedRun(8, 90)], OBJ);
    expect(holds.verdict).toBe("holds");
    expect(holds.text).toBe("Meets the objectives in 2 of 2 runs · p95 90 ms · seeds 7–8");
    const fails = seedRange([seedRun(1, 300), seedRun(2, 100, "succeeded", 0.9)], OBJ);
    expect(fails.verdict).toBe("fails");
    expect(fails.text.startsWith("Misses the objectives in 2 of 2 runs")).toBe(true);
    const waiting = seedRange([seedRun(42, 5.25), seedRun(43, null, "running")], {});
    expect([waiting.finished, waiting.runs, waiting.verdict]).toEqual([1, 2, null]);
    expect(waiting.text).toBe("1 runs, no objectives set · p95 5.3 ms · seeds 42–43");
  });

  it("formats like the scorecard and finds medians", () => {
    expect(fmtRangeMs(9.94)).toBe("9.9");
    expect(fmtRangeMs(104.5)).toBe("105");
    expect(rangeOf([3, null, 1, 2, 10])).toEqual({ min: 1, max: 10, median: 2.5 });
    expect(rangeOf([null])).toBeNull();
    expect(rangeText("p95", { min: 98, max: 98.4, median: 98 })).toBe("p95 98 ms");
    expect(seedsText([5])).toBe("seed 5");
    expect(seedsText([1, 3])).toBe("seeds 1, 3");
  });

  it("offers more seeds only when p95 is within 15% of its objective", () => {
    const at = (p95: number) => ({ summary: { latencyMs: { p50: 1, p95, p99: 1 } } }) as unknown as Report;
    expect(nearObjective(at(100), { latencyP95Ms: 115 })).toBe(true);
    expect(nearObjective(at(130), { latencyP95Ms: 115 })).toBe(true);
    expect(nearObjective(at(90), { latencyP95Ms: 115 })).toBe(false);
    expect(nearObjective(at(100), { latencyP95Ms: null })).toBe(false);
  });

  it("caps confidence at medium and says why", () => {
    const high = { level: "high", reason: "measured", reasons: ["measured"], drivers: [], weightedBy: "latency", howToRaise: "" } as Confidence;
    const capped = capConfidence(high, "medium", "borderline");
    expect(capped.level).toBe("medium");
    expect(capped.reasons).toEqual(["borderline", "measured"]);
    const low = { ...high, level: "low" } as Confidence;
    expect(capConfidence(low, "medium", "borderline")).toBe(low);
    expect(seedCappedConfidence(high, FIVE, OBJ)?.level).toBe("medium");
    expect(seedCappedConfidence(high, FIVE.slice(0, 2), OBJ)?.level).toBe("high");
  });
});

describe("quick checks", () => {
  it("reads eligibility from the analysis and the mode from the report", () => {
    const analysis = { ...(analysisJson as unknown as Analysis), quickCheck: { eligible: false, reason: "too short", requestedS: 60, simulatedS: null, sampleS: null } };
    expect(quickCheckOf(analysis)?.reason).toBe("too short");
    expect(quickCheckOf(null)).toBeNull();
    const sampled = { simulationMode: { kind: "sampled", requestedDurationS: 300, simulatedDurationS: 135, note: "Answered from a steady 125 s sample" } } as unknown as Report;
    expect(isEstimated(sampled)).toBe(true);
    expect(simulationModeOf(sampled)?.simulatedDurationS).toBe(135);
    expect(isEstimated({} as Report)).toBe(false);
  });
});

describe("decision report", () => {
  const doc = starter as unknown as ArchitectureDocument;
  function report(extra: Record<string, unknown> = {}): Report {
    return {
      summary: {
        offeredRps: 400,
        throughputRps: 399,
        successRate: 1,
        timeoutRate: 0,
        retryAmplification: 1,
        latencyMs: { p50: 30, p95: 98.2, p99: 120 },
        accounting: { measured: { arrived: 20000, succeeded: 20000, failed: 0, timedOut: 0, outstanding: 0 } },
      },
      findings: [],
      nodes: [],
      input: { architecture: doc.architecture, workload: doc.workload },
      assumptions: { userInputs: [] },
      provenance: { seed: 42, engineVersion: "0.12.0", requestsGenerated: 23980, inputDigest: "abcdef0123456789", resultDigest: "0123456789abcdef" },
      confidence: { level: "high", reason: "Most inputs are measured", reasons: ["Most inputs are measured"], drivers: [], weightedBy: "latency", howToRaise: "Nothing to do" },
      ...extra,
    } as unknown as Report;
  }

  it("shows the range across seeds, borderline, and caps the confidence", () => {
    const md = decisionReportMarkdown({ doc, catalog: null, objectives: OBJ, report: report(), seedRuns: FIVE });
    expect(md).toContain("- **Across seeds:** Meets the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds 42–46.");
    expect(md).toContain("- **Borderline:** the runs disagree on the verdict");
    expect(md).toContain("**Medium confidence:** The runs disagree on the verdict, so it is borderline.");
    expect(md).toContain("Across 5 of 5 runs (seeds 42–46):");
    expect(md).toContain("| Latency p95 | 98.2 ms | 104 ms | 131 ms |");
  });

  it("labels a quick check's report as estimated", () => {
    const sampled = report({ simulationMode: { kind: "sampled", requestedDurationS: 300, simulatedDurationS: 135, note: "Answered from a steady 125 s sample after the 10 s warm-up." } });
    const md = decisionReportMarkdown({ doc, catalog: null, objectives: OBJ, report: sampled });
    expect(md).toContain("- **Estimated (quick check):** Answered from a steady 125 s sample after the 10 s warm-up.");
    expect(md).not.toContain("Across seeds");
    const plain = decisionReportMarkdown({ doc, catalog: null, objectives: OBJ, report: report() });
    expect(plain).not.toContain("Estimated");
  });
});
