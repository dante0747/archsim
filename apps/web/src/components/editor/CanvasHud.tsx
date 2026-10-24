"use client";

import { X } from "lucide-react";
import { useState } from "react";
import { verdictOf } from "@/components/editor/workflow";
import { compactRate, fmtMs, fmtPct } from "@/lib/format";
import { NODE_STATUS } from "@/lib/status";
import { REQ_GLYPH } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

/** Terminal-style verdict card for the run whose results are on the canvas. */
export function RunReportCard() {
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const run = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId) ?? null);
  const overlay = useRuns((s) => s.overlay);
  const drawerOpen = useRuns((s) => s.drawerOpen);
  const openDrawer = useRuns((s) => s.openDrawer);
  const markSeen = useUi((s) => s.markInsightsSeen);
  const [collapsed, setCollapsed] = useState(false);
  if (!report || !overlay || drawerOpen) return null;
  const v = verdictOf(report);
  const s = report.summary;
  const color = v.tone === "ok" ? NODE_STATUS.ok.color : v.tone === "warn" ? NODE_STATUS.hot.color : NODE_STATUS.saturated.color;
  const mark = v.tone === "ok" ? "✓" : v.tone === "warn" ? "!" : "✕";
  if (collapsed) {
    return (
      <button
        type="button"
        onClick={() => setCollapsed(false)}
        className="absolute right-3 top-3 z-10 flex items-center gap-2 rounded-md border border-line-strong bg-surface px-2.5 py-1 font-mono text-[11px] text-ink shadow-float"
      >
        <span className="size-1.5 rounded-full" style={{ background: color }} aria-hidden /> last run
      </button>
    );
  }
  const kpis: [string, string][] = [
    ["thru", `${compactRate(s.throughputRps)}/s`],
    ["p95", fmtMs(s.latencyMs.p95)],
    ["ok", fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)],
  ];
  return (
    <div
      data-run-card
      className="absolute right-3 top-3 z-10 hidden w-[280px] overflow-hidden rounded-md border border-line-strong bg-surface font-mono shadow-float xl:block"
    >
      <div className="flex items-center gap-2 border-b border-line bg-surface-2 px-2.5 py-1.5 text-[10.5px] text-ink-3">
        <span className="size-1.5 shrink-0 rounded-full" style={{ background: color }} aria-hidden />
        <span className="truncate">
          last run · {run?.label ?? "design"} · seed {report.provenance.seed}
        </span>
        <button type="button" onClick={() => setCollapsed(true)} className="ml-auto rounded-sm p-0.5 hover:text-ink" aria-label="Collapse run card">
          <X className="size-3.5" />
        </button>
      </div>
      <div className="p-2.5">
        <p className="flex items-start gap-2 text-[12px] font-semibold leading-[18px] text-ink">
          <span className={cn("shrink-0", v.tone === "bad" && "pulse-soft")} style={{ color }} aria-hidden>
            {mark}
          </span>
          {v.title}
        </p>
        <dl className="mt-2 grid grid-cols-3 divide-x divide-line rounded-sm border border-line">
          {kpis.map(([k, val]) => (
            <div key={k} className="px-2 py-1.5">
              <dt className="text-[9.5px] uppercase tracking-wider text-ink-3">{k}</dt>
              <dd className="tabular truncate text-[12px] font-semibold text-ink">{val}</dd>
            </div>
          ))}
        </dl>
        <ul className="mt-2 flex flex-wrap gap-x-2.5 gap-y-0.5 text-[10px] text-ink-3" aria-label="Legend">
          {(["ok", "warm", "hot", "saturated"] as const).map((st) => (
            <li key={st} className="flex items-center gap-1" title={NODE_STATUS[st].description}>
              <span className="size-1.5 rounded-full" style={{ background: NODE_STATUS[st].color }} aria-hidden />
              {NODE_STATUS[st].label.toLowerCase()}
            </li>
          ))}
          <li>
            <span className="text-accent">{REQ_GLYPH.read}</span> read <span className="text-accent">{REQ_GLYPH.write}</span> write
          </li>
        </ul>
        <button
          type="button"
          onClick={() => {
            openDrawer("overview");
            if (run) markSeen(run.id);
          }}
          className="mt-2.5 flex w-full items-center justify-center gap-1.5 rounded-sm border border-accent/40 py-1.5 text-[11px] font-semibold text-accent hover:bg-accent-soft"
        >
          open insights →
        </button>
      </div>
    </div>
  );
}
