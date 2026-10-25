"use client";

import { ArrowRight, Play } from "lucide-react";
import { openDecide, replayActiveRun, verdictOf } from "@/components/editor/workflow";
import { Tip } from "@/components/ui/misc";
import { compactRate, fmtMs } from "@/lib/format";
import { NODE_STATUS } from "@/lib/status";
import { cn } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

const SHORT = { ok: "Healthy", warn: "Running hot", bad: "Bottleneck" } as const;

/**
 * The last run in one line, in the top bar (Design workspace): verdict, throughput and p95, with
 * Replay and Results. Replaces the card that used to float over the diagram.
 */
export function RunStatusChip() {
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const overlay = useRuns((s) => s.overlay);
  const mode = useUi((s) => s.mode);
  const playhead = useUi((s) => s.playhead);
  if (!report || !overlay || mode !== "build") return null;
  const v = verdictOf(report);
  const s = report.summary;
  const color = v.tone === "ok" ? NODE_STATUS.ok.color : v.tone === "warn" ? NODE_STATUS.hot.color : NODE_STATUS.saturated.color;
  return (
    <div data-run-status className="pop-in ml-2 hidden h-8 shrink-0 items-center gap-0.5 rounded-lg bg-surface-2 pl-2.5 pr-0.5 xl:flex">
      <Tip content={`${v.title}. Throughput and p95 latency of the last run.`}>
        <span className="flex items-center gap-2 text-[13px]">
          <span className={cn("size-2 shrink-0 rounded-full", v.tone === "bad" && "pulse-soft")} style={{ background: color }} aria-hidden />
          <span className="font-medium text-ink">{SHORT[v.tone]}</span>
          <span className="tabular whitespace-nowrap text-ink-3">
            {compactRate(s.throughputRps)}/s · {fmtMs(s.latencyMs.p95)}
          </span>
        </span>
      </Tip>
      {playhead === null && (
        <Tip content="Replay the run">
          <button type="button" onClick={replayActiveRun} aria-label="Replay the run" className="ml-1 rounded-md p-1.5 text-ink-2 hover:bg-surface-3 hover:text-ink">
            <Play className="size-3.5 fill-current" />
          </button>
        </Tip>
      )}
      <button
        type="button"
        onClick={() => {
          useDecide.getState().setSection("answer");
          openDecide();
        }}
        className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-accent hover:bg-accent-soft"
      >
        Results <ArrowRight className="size-3.5" />
      </button>
    </div>
  );
}
