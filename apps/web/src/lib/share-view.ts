/**
 * What a share page and its link preview show. The verdict is the same yes / no as Results →
 * Answer, worded for someone reading, not editing (no "set a target below").
 */
import { DEFAULT_OBJECTIVES } from "./document";
import { CONFIDENCE_LABEL } from "./confidence";
import { fmtMs, fmtRps } from "./format";
import type { SharedReport } from "./share-types";
import { type SloCheck, type SloResult, evaluateSlo, formatSloValue } from "./slo";
import type { Finding, Objectives, TechnologyTier } from "./types";

export type VerdictTone = "good" | "bad" | "none";

export interface ShareVerdict {
  tone: VerdictTone;
  headline: string;
  reason: string;
  /** The bottleneck finding's title, when the run found one. */
  bottleneck: string | null;
  slo: SloResult;
}

/** The shared objectives as the editor reads them: unset keys take the defaults. */
export function shareObjectives(objectives: Objectives | null | undefined): Objectives {
  return { ...DEFAULT_OBJECTIVES, ...(objectives ?? {}) };
}

function missedText(c: SloCheck): string {
  return `${c.label} ${formatSloValue(c.key, c.actual)} (target ${c.better === "lower" ? "≤" : "≥"} ${formatSloValue(c.key, c.target)})`;
}

export function shareVerdict(objectives: Objectives, report: Pick<SharedReport, "summary" | "findings">): ShareVerdict {
  const slo = evaluateSlo(objectives, report);
  const tone: VerdictTone = slo.pass === true ? "good" : slo.pass === false ? "bad" : "none";
  const headline =
    slo.total === 0 ? "No objectives set" : slo.pass === true ? "Yes, it holds up." : slo.pass === false ? "No, it doesn’t hold up." : "Not enough data yet.";
  const reason =
    slo.total === 0
      ? `No latency or availability targets were set, so this run has no yes or no. It handled ${fmtRps(report.summary.throughputRps)}.`
      : slo.pass === false
        ? slo.checks
            .filter((c) => c.pass === false)
            .map(missedText)
            .join(" · ")
        : slo.pass === true
          ? `Meets all ${slo.total} objectives at ${fmtRps(report.summary.offeredRps)}.`
          : "The run was too short or too light to decide.";
  const bottleneck = report.findings.find((f) => f.kind === "bottleneck")?.title ?? null;
  return { tone, headline, reason, bottleneck, slo };
}

/** What the share page draws: the report without per-part detail (nodes, edges, sizing), so the
 * page stays small on a phone. */
export type ShareView = Pick<SharedReport, "reportVersion" | "summary" | "findings" | "timeseries" | "window" | "provenance" | "confidence" | "cost"> & {
  technologies: TechnologyTier[];
};

export function viewReport(report: SharedReport): ShareView {
  return {
    reportVersion: report.reportVersion,
    summary: report.summary,
    findings: report.findings,
    timeseries: report.timeseries,
    window: report.window,
    provenance: report.provenance,
    confidence: report.confidence,
    cost: report.cost,
    technologies: report.assumptions?.technologies ?? [],
  };
}

/** Findings worth reading first: problems before notes, the "healthy" line only when alone. */
export function shareFindings(findings: Finding[]): Finding[] {
  const notable = findings.filter((f) => f.kind !== "healthy");
  const list = notable.length ? notable : findings;
  return [...list].sort((a, b) => (SEVERITY_RANK[a.severity] ?? 9) - (SEVERITY_RANK[b.severity] ?? 9));
}

const SEVERITY_RANK: Record<string, number> = { critical: 0, warning: 1, info: 2, ok: 3 };

/** One line for link previews: "Yes, it holds up. p95 42 ms · 1,200 req/s · High confidence". */
export function previewLine(objectives: Objectives, report: Pick<SharedReport, "summary" | "findings" | "confidence">): string {
  const v = shareVerdict(objectives, report);
  const parts = [`p95 ${fmtMs(report.summary.latencyMs.p95)}`, fmtRps(report.summary.throughputRps)];
  if (report.confidence) parts.push(CONFIDENCE_LABEL[report.confidence.level]);
  return `${v.headline} ${parts.join(" · ")}`;
}
