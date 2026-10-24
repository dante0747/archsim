"use client";

import { ArrowRight, Play, X } from "lucide-react";
import { useState } from "react";
import { openDecide, replayActiveRun, verdictOf } from "@/components/editor/workflow";
import { compactRate, fmtMs, fmtPct } from "@/lib/format";
import { NODE_STATUS } from "@/lib/status";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

/** Small verdict card for the run whose results are on the canvas (Design workspace). */
export function RunReportCard() {
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const overlay = useRuns((s) => s.overlay);
  const drawerOpen = useRuns((s) => s.drawerOpen);
  const mode = useUi((s) => s.mode);
  const playhead = useUi((s) => s.playhead);
  const [collapsed, setCollapsed] = useState(false);
  if (!report || !overlay || drawerOpen || mode !== "build") return null;
  const v = verdictOf(report);
  const s = report.summary;
  const color = v.tone === "ok" ? NODE_STATUS.ok.color : v.tone === "warn" ? NODE_STATUS.hot.color : NODE_STATUS.saturated.color;
  const mark = v.tone === "ok" ? "✓" : v.tone === "warn" ? "!" : "✕";
  if (collapsed) {
    return (
      <button
        type="button"
        onClick={() => setCollapsed(false)}
        className="surface-glass absolute right-3 top-3 z-10 flex items-center gap-2 rounded-lg px-2.5 py-1 font-mono text-[11px] text-ink"
      >
        <span className="size-1.5 rounded-full" style={{ background: color }} aria-hidden /> result
      </button>
    );
  }
  const kpis: [string, string][] = [
    ["throughput", `${compactRate(s.throughputRps)}/s`],
    ["p95", fmtMs(s.latencyMs.p95)],
    ["success", fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)],
  ];
  return (
    <div data-run-card className="surface-glass absolute right-3 top-3 z-10 hidden w-[264px] overflow-hidden rounded-xl xl:block">
      <div className="flex items-start gap-2 px-3 pt-3">
        <span className={cn("mt-0.5 shrink-0 font-mono text-[13px] font-bold", v.tone === "bad" && "pulse-soft")} style={{ color }} aria-hidden>
          {mark}
        </span>
        <p className="min-w-0 flex-1 text-[13.5px] font-semibold leading-[18px] tracking-[-0.01em] text-ink">{v.title}</p>
        <button type="button" onClick={() => setCollapsed(true)} className="-mr-1 -mt-0.5 shrink-0 rounded-md p-1 text-ink-3 hover:bg-surface-2 hover:text-ink" aria-label="Collapse the result card">
          <X className="size-3.5" />
        </button>
      </div>
      <dl className="mt-2.5 grid grid-cols-3 px-3">
        {kpis.map(([k, val]) => (
          <div key={k} className="min-w-0">
            <dd className="tabular truncate font-mono text-[14px] font-semibold text-ink">{val}</dd>
            <dt className="text-[11px] text-ink-3">{k}</dt>
          </div>
        ))}
      </dl>
      <div className="mt-3 flex gap-1.5 border-t border-line p-2">
        {playhead === null && (
          <button
            type="button"
            onClick={replayActiveRun}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-md py-1.5 text-[12.5px] font-medium text-ink-2 hover:bg-surface-2 hover:text-ink"
          >
            <Play className="size-3.5 fill-current" /> Replay
          </button>
        )}
        <button
          type="button"
          onClick={() => {
            useDecide.getState().setSection("answer");
            openDecide();
          }}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-md bg-accent-soft py-1.5 text-[12.5px] font-semibold text-accent hover:brightness-110"
        >
          Results <ArrowRight className="size-3.5" />
        </button>
      </div>
    </div>
  );
}
