/** A share as GET /public/shares/{slug} returns it, built on the dashboard fixture report. */
import type { PublicShare } from "@/lib/share-types";
import type { Confidence, Finding } from "@/lib/types";
import { fixtureReport } from "./dashboard-report";

export const SLUG = "4kTq9ZbW2xYc7Lm0PdRs1A";

export const CONFIDENCE: Confidence = {
  level: "high",
  reason: "20 of 24 inputs are measured from telemetry",
  reasons: [],
  drivers: [],
  weightedBy: "latency",
  howToRaise: "",
};

export function finding(over: Partial<Finding> = {}): Finding {
  return { severity: "warning", kind: "hot", nodeId: "api", title: "Api is running hot", detail: "Utilization peaks at 99%.", evidence: {}, ...over };
}

export function sharedFixture(over: Partial<PublicShare> = {}): PublicShare {
  const report = fixtureReport();
  delete report.traces;
  return {
    slug: SLUG,
    title: "Checkout",
    projectName: "Checkout",
    runLabel: "Current design",
    scenarioName: null,
    createdAt: "2026-10-20T12:00:00Z",
    expiresAt: "2026-11-19T12:00:00Z",
    payloadVersion: 1,
    reportVersion: "1.13",
    resultDigest: "r".repeat(64),
    objectives: { latencyP95Ms: 100, latencyP99Ms: null, availability: 0.9, minThroughputRps: null },
    report: {
      ...report,
      summary: {
        ...report.summary,
        accounting: {
          measured: { arrived: 1000, succeeded: 950, failed: 40, timedOut: 10, outstanding: 0 },
          warmup: { arrived: 100, succeeded: 100, failed: 0, timedOut: 0, outstanding: 0 },
          invariantHolds: true,
        } as PublicShare["report"]["summary"]["accounting"],
      },
      findings: [finding(), finding({ severity: "ok", kind: "healthy", nodeId: null, title: "Healthy" })],
      confidence: CONFIDENCE,
      provenance: {
        engineVersion: "0.12.0",
        schemaVersion: "1.0",
        seed: 42,
        inputDigest: "i".repeat(64),
        resultDigest: "r".repeat(64),
        requestsGenerated: 18000,
        wallSeconds: 1.2,
      } as PublicShare["report"]["provenance"],
    },
    canCopy: true,
    ...over,
  };
}
