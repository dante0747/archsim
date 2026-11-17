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
  /** The busiest part's share busy, the estimate's at the load the run sent it, beside the run's. */
  busiest: { id: string; label: string; estimate: number; simulated: number | null } | null;
  /** "Queueing formulas: p95 ≈ 97 ms (simulated 91 ms); API Servers 70% of its slots busy (simulated 70%)." */
  text: string;
}

/** Off by more than this share, the run sent a part another load than the estimate's peak. */
const SAME_LOAD = 0.05;

const rpsText = (v: number | null) => Math.round(v ?? 0).toLocaleString("en-US");

/**
 * The run's estimate (same input as the run) against what the run simulated, like with like
 * (ADR-0083): on the same resource (a CPU estimate against the cores busy, a database of several
 * instances' busiest instance's; slots against slots busy) and at the same load. The estimate is at
 * the peak: a CPU share, which grows with load alone, is scaled to the load the run sent the part;
 * a slot's hold time also grows with waits downstream, so slots are given each at its own load.
 */
export function compareEstimate(report: Report): EstimateComparison | null {
  const e = report.estimate;
  if (!e) return null;
  const simP95 = report.summary.latencyMs.p95;
  const estP95 = e.latencyMs?.p95 ?? null;
  const top = e.busiest;
  const node = top ? report.nodes.find((n) => n.id === top.id) : undefined;
  const part = top ? e.parts?.find((p) => p.id === top.id) : undefined;
  const cpu = top?.limitedBy === "cpu";
  const byBusiestInstance = cpu && node?.summary.busiestInstanceCpuUtilization != null;
  const simulated = cpu ? (node?.summary.busiestInstanceCpuUtilization ?? node?.summary.cpuCoreUtilization ?? null) : (node?.summary.utilization ?? null);
  const estRps = part?.arrivalRps ?? null;
  const simRps = node?.summary.arrivalRps ?? null;
  const ratio = estRps != null && simRps != null && estRps > 0 ? simRps / estRps : null;
  const loadsDiffer = ratio != null && Math.abs(ratio - 1) > SAME_LOAD;
  const scaled = cpu && loadsDiffer;
  const estimate = top && ratio != null && scaled ? top.utilization * ratio : (top?.utilization ?? 0);
  const busiest = top ? { id: top.id, label: top.label, estimate, simulated } : null;
  const parts: string[] = [];
  const over = loadsDiffer ? " over the run" : "";
  if (e.overloaded) parts.push(`overloaded at ${rpsText(e.atRps)} req/s (simulated p95 ${msText(simP95)}${over})`);
  else if (estP95 != null) parts.push(`p95 ≈ ${msText(estP95)}${loadsDiffer ? " at the peak" : ""} (simulated ${msText(simP95)}${over})`);
  if (top && busiest) {
    const who = byBusiestInstance ? `${busiest.label}'s busiest instance` : busiest.label;
    const what = cpu ? "CPU busy" : "of its slots busy";
    const sim = busiest.simulated == null ? "–" : fmtPct(busiest.simulated, 0);
    const sent = `the ${rpsText(simRps)} req/s the run sent it`;
    const peak = `the ${rpsText(estRps)} req/s peak`;
    if (scaled) parts.push(`${who} ${fmtPct(busiest.estimate, 0)} ${what} at ${sent} (simulated ${sim}; ${fmtPct(top.utilization, 0)} at ${peak})`);
    else if (loadsDiffer) parts.push(`${who} ${fmtPct(busiest.estimate, 0)} ${what} at ${peak} (simulated ${sim} at ${sent})`);
    else parts.push(`${who} ${fmtPct(busiest.estimate, 0)} ${what} (simulated ${sim})`);
  }
  const text = parts.length ? `Queueing formulas: ${parts.join("; ")}.` : "Queueing formulas give no estimate for this design.";
  return { p95: { estimate: estP95, simulated: simP95 }, busiest, text };
}
