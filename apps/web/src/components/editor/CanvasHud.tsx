"use client";

import { X } from "lucide-react";
import { useState } from "react";
import { openDecide, replayActiveRun, verdictOf } from "@/components/editor/workflow";
import { compactRate, fmtMs, fmtPct } from "@/lib/format";
import { NODE_STATUS } from "@/lib/status";
import { REQ_GLYPH } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

/** Terminal-style verdict card for the run whose results are on the canvas. */
export function RunReportCard() {
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const run = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId) ?? null);
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
    ["thru", `${compactRate(s.throughputRps)}/s`],
    ["p95", fmtMs(s.latencyMs.p95)],
    ["ok", fmtPct(s.successRate, s.successRate === 1 ? 0 : 1)],
  ];
  return (
    <div
      data-run-card
      className="surface-glass absolute right-3 top-3 z-10 hidden w-[284px] overflow-hidden rounded-xl font-mono xl:block"
    >
      <div className="flex items-center gap-2 border-b border-line px-3 py-1.5 text-[10.5px] text-ink-3">
        <span className="size-1.5 shrink-0 rounded-full" style={{ background: color }} aria-hidden />
        <span className="truncate">
          result · {run?.label ?? "current design"}
        </span>
        <button type="button" onClick={() => setCollapsed(true)} className="ml-auto rounded-sm p-0.5 hover:text-ink" aria-label="Collapse the result card">
          <X className="size-3.5" />
        </button>
      </div>
      <div className="p-3">
        <p className="flex items-start gap-2 font-sans text-[14px] font-semibold leading-[19px] tracking-[-0.01em] text-ink">
          <span className={cn("shrink-0", v.tone === "bad" && "pulse-soft")} style={{ color }} aria-hidden>
            {mark}
          </span>
          {v.title}
        </p>
        <dl className="mt-2.5 grid grid-cols-3 divide-x divide-line rounded-md border border-line bg-surface-2/50">
          {kpis.map(([k, val]) => (
            <div key={k} className="px-2 py-1.5">
              <dt className="text-[9.5px] uppercase tracking-wider text-ink-3">{k}</dt>
              <dd className="tabular truncate text-[13px] font-semibold text-ink">{val}</dd>
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
        <div className="mt-2.5 flex gap-1.5">
          {playhead === null && (
            <button
              type="button"
              onClick={replayActiveRun}
              className="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-line-strong bg-surface/60 py-1.5 font-sans text-[12px] font-semibold text-ink hover:bg-surface-2"
            >
              ▶ Replay
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              useDecide.getState().setSection("scorecard");
              openDecide();
            }}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-md border border-accent/40 bg-accent-soft/40 py-1.5 font-sans text-[12px] font-semibold text-accent hover:bg-accent-soft"
          >
            See results →
          </button>
        </div>
      </div>
    </div>
  );
}
