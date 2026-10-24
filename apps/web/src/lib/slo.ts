/**
 * Service-level objectives: evaluate a run against the document's objectives, per run and per
 * time bucket (for budget burn and for marking breaches on the flight-recorder timeline).
 */
import type { Objectives, Report, ReportTimeseries } from "./types";

export type SloKey = "p95" | "p99" | "availability" | "throughput";

export interface SloCheck {
  key: SloKey;
  label: string;
  /** Target value: ms for latency, share (0..1) for availability, req/s for throughput. */
  target: number;
  actual: number | null;
  /** null when the run has no data for this metric */
  pass: boolean | null;
  better: "lower" | "higher";
}

export interface SloResult {
  checks: SloCheck[];
  /** null when no objective is set */
  pass: boolean | null;
  met: number;
  total: number;
}

export function sloChecks(obj: Objectives, report: Pick<Report, "summary">): SloCheck[] {
  const s = report.summary;
  const checks: SloCheck[] = [];
  const add = (key: SloKey, label: string, target: number | null | undefined, actual: number | null, better: "lower" | "higher") => {
    if (target == null) return;
    const pass = actual == null ? null : better === "lower" ? actual <= target : actual >= target;
    checks.push({ key, label, target, actual, pass, better });
  };
  add("p95", "Latency p95", obj.latencyP95Ms, s.latencyMs.p95, "lower");
  add("p99", "Latency p99", obj.latencyP99Ms, s.latencyMs.p99, "lower");
  add("availability", "Availability", obj.availability, s.successRate, "higher");
  add("throughput", "Throughput", obj.minThroughputRps, s.throughputRps, "higher");
  return checks;
}

export function evaluateSlo(obj: Objectives, report: Pick<Report, "summary">): SloResult {
  const checks = sloChecks(obj, report);
  const decided = checks.filter((c) => c.pass !== null);
  const met = decided.filter((c) => c.pass).length;
  return {
    checks,
    pass: checks.length === 0 ? null : decided.length < checks.length ? (met < decided.length ? false : null) : met === decided.length,
    met,
    total: checks.length,
  };
}

/**
 * Error budget over the measured window: the failures the availability objective allows versus
 * the failures that happened. `consumed` > 1 means the budget is exhausted.
 */
export function errorBudget(obj: Objectives, report: Pick<Report, "summary">): {
  allowed: number;
  failed: number;
  consumed: number | null;
} | null {
  if (obj.availability == null) return null;
  const m = report.summary.accounting.measured;
  const failed = m.failed + m.timedOut;
  const allowed = (1 - obj.availability) * m.arrived;
  return { allowed, failed, consumed: allowed > 0 ? failed / allowed : failed > 0 ? Infinity : 0 };
}

/**
 * Per-bucket compliance: a bucket breaches when its p95 exceeds the latency target or its
 * success share drops below the availability target. null = no traffic in that bucket.
 */
export function sloTimeline(obj: Objectives, ts: ReportTimeseries): (boolean | null)[] {
  return ts.t.map((_, i) => {
    const thr = ts.throughputRps[i];
    const err = (ts.errorRps[i] ?? 0) + (ts.timeoutRps[i] ?? 0);
    const total = (thr ?? 0) + err;
    if (!total) return null;
    if (obj.latencyP95Ms != null && (ts.latencyP95Ms[i] ?? 0) > obj.latencyP95Ms) return false;
    if (obj.latencyP99Ms != null && (ts.latencyP99Ms[i] ?? 0) > obj.latencyP99Ms) return false;
    if (obj.availability != null && (thr ?? 0) / total < obj.availability) return false;
    return true;
  });
}

/** Human formatting of a check's target/actual. */
export function formatSloValue(key: SloKey, v: number | null): string {
  if (v == null) return "–";
  if (key === "availability") return `${(v * 100).toFixed(v >= 0.999 ? 2 : 1)}%`;
  if (key === "throughput") return `${Math.round(v).toLocaleString("en-US")} req/s`;
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ms`;
}
