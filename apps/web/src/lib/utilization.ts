/**
 * A part's CPU busy as the app words it (ADR-0083): its cores busy over all its instances, and, for
 * a database of several instances, its busiest instance's, which set its CPU ceiling. Its slots
 * busy are `utilization`, and the share of busy slot time on its own work `cpuUtilization`.
 */
import { fmtPct } from "./format";
import type { NodeReportSummary } from "./types";

/** "16% · busiest 33%": the cores busy, and a database's busiest instance's when it has several. */
export function cpuBusyText(s: NodeReportSummary): string {
  const cores = fmtPct(s.cpuCoreUtilization, 0);
  return s.busiestInstanceCpuUtilization != null ? `${cores} · busiest ${fmtPct(s.busiestInstanceCpuUtilization, 0)}` : cores;
}
