"use client";

import { ArrowRight, X } from "lucide-react";
import { useState } from "react";
import { verdictOf } from "@/components/editor/workflow";
import { compactRate, fmtMs, fmtPct } from "@/lib/format";
import { NODE_STATUS } from "@/lib/status";
import { REQ_COLOR } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

/** Compact verdict card for the run whose results are on the canvas. */
export function RunReportCard() {
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const run = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId) ?? null);
  const overlay = useRuns((s) => s.overlay);
  const drawerOpen = useRuns((s) => s.drawerOpen);
  const openDrawer = useRuns((s) => s.openDrawer);
  const markSeen = useUi((s) => s.markInsightsSeen);
  const flow = useUi((s) => s.flow);
  const [collapsed, setCollapsed] = useState(false);
  if (!report || !overlay || drawerOpen) return null;
  const v = verdictOf(report);
  const s = report.summary;
  const color = v.tone === "ok" ? NODE_STATUS.ok.color : v.tone === "warn" ? NODE_STATUS.hot.color : NODE_STATUS.saturated.color;
  if (collapsed) {
    return (
      <button
        type="button"
        onClick={() => setCollapsed(false)}
        className="absolute right-3 top-3 z-10 flex items-center gap-2 rounded-xl border border-line bg-glass px-3 py-1.5 text-xs text-ink shadow-float backdrop-blur"
      >
        <span className="size-2 rounded-full" style={{ background: color }} aria-hidden /> Last run
      </button>
    );
  }
  const kpis: [string, string][] = [
    ["Throughput", `${compactRate(s.throughputRps)}/s`],
    ["p95", fmtMs(s.latencyMs.p95)],
    ["Success", fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)],
  ];
  return (
    <div data-run-card className="absolute right-3 top-3 z-10 hidden w-[272px] rounded-2xl border border-line bg-glass p-3 shadow-float backdrop-blur xl:block">
      <div className="mb-2 flex items-center gap-2">
        <span className="microlabel">Last run</span>
        <span className="truncate text-[11px] text-ink-2">{run?.label ?? "Design"} · seed {report.provenance.seed}</span>
        <button type="button" onClick={() => setCollapsed(true)} className="ml-auto rounded p-0.5 text-ink-3 hover:text-ink" aria-label="Collapse run card">
          <X className="size-3.5" />
        </button>
      </div>
      <p className="mb-2.5 flex items-start gap-2 text-[13px] font-semibold leading-5 text-ink">
        <span className={cn("mt-1.5 size-2 shrink-0 rounded-full", v.tone !== "ok" && "pulse-soft")} style={{ background: color }} aria-hidden />
        {v.title}
      </p>
      <dl className="mb-2.5 grid grid-cols-3 gap-1.5">
        {kpis.map(([k, val]) => (
          <div key={k} className="rounded-lg bg-surface-2 px-2 py-1.5">
            <dt className="microlabel !text-[9px]">{k}</dt>
            <dd className="tabular truncate font-mono text-[12px] font-semibold text-ink">{val}</dd>
          </div>
        ))}
      </dl>
      <ul className="mb-2.5 flex flex-wrap gap-x-2.5 gap-y-1 text-[10.5px] text-ink-2" aria-label="Legend">
        {(["ok", "warm", "hot", "saturated"] as const).map((st) => (
          <li key={st} className="flex items-center gap-1" title={NODE_STATUS[st].description}>
            <span className="size-1.5 rounded-full" style={{ background: NODE_STATUS[st].color }} aria-hidden />
            {NODE_STATUS[st].label}
          </li>
        ))}
        <li className="flex items-center gap-1">
          <span className="size-1.5 rounded-full" style={{ background: REQ_COLOR.read }} aria-hidden />R
          <span className="size-1.5 rounded-full" style={{ background: REQ_COLOR.write }} aria-hidden />W
        </li>
        <li className="w-full text-ink-3">Link width{flow ? " and dots" : ""} follow measured calls/s.</li>
      </ul>
      <button
        type="button"
        onClick={() => {
          openDrawer("overview");
          if (run) markSeen(run.id);
        }}
        className="flex w-full items-center justify-center gap-1 rounded-lg bg-accent-soft py-1.5 text-xs font-medium text-ink hover:brightness-105"
      >
        Open insights <ArrowRight className="size-3.5" />
      </button>
    </div>
  );
}
