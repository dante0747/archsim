/**
 * Words for the engine's confidence and instant estimate (report 1.13+, analysis). The engine
 * decides the level and writes the reasons; these helpers only phrase them, so the Results page
 * and the Markdown report say the same thing as the CLI and the MCP server.
 */
import { fmtPct } from "./format";
import type { Confidence, ConfidenceLevel, Estimate, ProvenanceKind, Report } from "./types";

export const CONFIDENCE_LABEL: Record<ConfidenceLevel, string> = {
  high: "High confidence",
  medium: "Medium confidence",
  low: "Low confidence",
};

/** "Low confidence: 31 of 35 inputs are illustrative defaults". */
export function confidenceHeadline(c: Confidence): string {
  return `${CONFIDENCE_LABEL[c.level]}: ${c.reason}`;
}

export const KIND_LABEL: Record<ProvenanceKind, string> = {
  measured: "measured",
  documented: "documented",
  assumed: "assumed",
  illustrative: "illustrative",
};

/** "Measured 0, documented 1, assumed 4, illustrative 31". */
export function countsText(counts: Partial<Record<ProvenanceKind, number>>): string {
  return [
    `${counts.measured ?? 0} measured from telemetry`,
    `${counts.documented ?? 0} documented by the vendor`,
    `${counts.assumed ?? 0} assumed (typed in)`,
    `${counts.illustrative ?? 0} illustrative (ArchSim defaults and presets)`,
  ].join(", ");
}

/** A latency for prose: "97 ms", "8.4 ms", "1.20 s". */
export function msText(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "–";
  if (v >= 1000) return `${(v / 1000).toFixed(2)} s`;
  return v >= 10 ? `${Math.round(v)} ms` : `${v.toFixed(1)} ms`;
}

/** "Estimate from queueing formulas (not simulated): p95 ≈ 97 ms; busiest API Servers 70%". */
export function estimateText(e: Estimate): string {
  return `${e.method}: ${e.summary}`;
}

export interface EstimateComparison {
  p95: { estimate: number | null; simulated: number | null };
  busiest: { id: string; label: string; estimate: number; simulated: number | null } | null;
  /** "Queueing formulas: p95 ≈ 97 ms (simulated 91 ms); API Servers 70% busy (simulated 70%)." */
  text: string;
}

/** The run's estimate (same input as the run) against what the run simulated. */
export function compareEstimate(report: Report): EstimateComparison | null {
  const e = report.estimate;
  if (!e) return null;
  const simP95 = report.summary.latencyMs.p95;
  const estP95 = e.latencyMs?.p95 ?? null;
  const node = e.busiest ? report.nodes.find((n) => n.id === e.busiest?.id) : undefined;
  const simUtil = e.busiest?.limitedBy === "cpu" ? (node?.summary.cpuUtilization ?? node?.summary.utilization ?? null) : (node?.summary.utilization ?? null);
  const busiest = e.busiest ? { id: e.busiest.id, label: e.busiest.label, estimate: e.busiest.utilization, simulated: simUtil } : null;
  const parts: string[] = [];
  if (e.overloaded) parts.push(`overloaded at ${Math.round(e.atRps ?? 0).toLocaleString("en-US")} req/s (simulated p95 ${msText(simP95)})`);
  else if (estP95 != null) parts.push(`p95 ≈ ${msText(estP95)} (simulated ${msText(simP95)})`);
  if (busiest) {
    const what = e.busiest?.limitedBy === "cpu" ? "CPU busy" : "busy";
    parts.push(`${busiest.label} ${fmtPct(busiest.estimate, 0)} ${what} (simulated ${busiest.simulated == null ? "–" : fmtPct(busiest.simulated, 0)})`);
  }
  const text = parts.length ? `Queueing formulas: ${parts.join("; ")}.` : "Queueing formulas give no estimate for this design.";
  return { p95: { estimate: estP95, simulated: simP95 }, busiest, text };
}
