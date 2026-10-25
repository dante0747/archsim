"use client";

/**
 * Canvas overlay while replaying a run: the simulated clock, the phase, the system KPIs at the
 * playhead and the faults active at that moment. Hidden in whole-run summary view.
 */
import { fmtMs } from "@/lib/format";
import { faultLabel, faultsAt, fmtClock, fmtRate, phaseAt, runDuration, runFaults, valueAt } from "@/lib/recorder";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export function PlaybackClock() {
  const playhead = useUi((s) => s.playhead);
  const playing = useUi((s) => s.playing);
  const speed = useUi((s) => s.speed);
  const overlay = useRuns((s) => s.overlay);
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const run = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId) ?? null);
  const slo = useEditor((s) => s.objectives.latencyP95Ms ?? null);
  if (playhead == null || !report || !overlay) return null;

  const ts = report.timeseries;
  const b = ts.bucketS || 1;
  const end = runDuration(report);
  const thr = valueAt(ts.throughputRps, playhead, b, "linear");
  const p95 = valueAt(ts.latencyP95Ms, playhead, b, "linear");
  const err = (valueAt(ts.errorRps, playhead, b, "linear") ?? 0) + (valueAt(ts.timeoutRps, playhead, b, "linear") ?? 0);
  const total = (thr ?? 0) + err;
  const errShare = total > 0 ? err / total : null;
  const phase = phaseAt(playhead, report.window);
  const faults = faultsAt(runFaults(report), playhead);
  const p95Bad = slo != null && p95 != null && p95 > slo;

  return (
    <div
      className="surface-glass pointer-events-none absolute left-3 top-3 z-10 w-[252px] overflow-hidden rounded-xl font-mono"
      role="status"
      aria-label={`Replay at ${fmtClock(playhead)}`}
    >
      <div className="flex items-center gap-2 border-b border-line bg-surface-2 px-2.5 py-1 text-[11px] text-ink-3">
        <span className={cn("size-1.5 rounded-full", playing ? "pulse-soft bg-accent" : "bg-ink-3")} aria-hidden />
        <span className="text-ink-2">{playing ? `replay ×${speed}` : "paused"}</span>
        <span className="ml-auto truncate normal-case tracking-normal">{run?.label ?? "last run"}</span>
      </div>
      <div className="px-2.5 pb-2 pt-1.5">
        <div className="flex items-baseline gap-1.5">
          <span className="text-[11px] text-ink-3">t =</span>
          <span className="tabular text-[24px] font-semibold leading-7 tracking-tight text-ink">{playhead.toFixed(1)}</span>
          <span className="text-xs text-ink-3">s / {fmtClock(end)}</span>
          <span
            className={cn(
              "ml-auto rounded-sm border px-1 text-[11px] font-semibold leading-4",
              phase === "measured" ? "border-accent/40 text-accent" : "border-line-strong text-ink-3",
            )}
          >
            {phase}
          </span>
        </div>
        <div className="mt-1 h-[3px] overflow-hidden rounded-[1px] bg-surface-3" aria-hidden>
          <div className="h-full bg-accent" style={{ width: `${Math.min(100, (playhead / Math.max(end, 1e-6)) * 100)}%` }} />
        </div>
        <dl className="mt-2 grid grid-cols-3 divide-x divide-line rounded-sm border border-line">
          <div className="px-1.5 py-1">
            <dt className="text-[11px] text-ink-3">Rate</dt>
            <dd className="tabular truncate text-xs font-semibold text-ink">{fmtRate(thr)}/s</dd>
          </div>
          <div className="px-1.5 py-1">
            <dt className="text-[11px] text-ink-3">p95</dt>
            <dd className={cn("tabular truncate text-xs font-semibold", p95Bad ? "text-danger-ink" : "text-ink")}>{fmtMs(p95)}</dd>
          </div>
          <div className="px-1.5 py-1">
            <dt className="text-[11px] text-ink-3">Failed</dt>
            <dd className={cn("tabular truncate text-xs font-semibold", errShare && errShare >= 0.01 ? "text-danger-ink" : "text-ink")}>
              {errShare == null ? "–" : `${(errShare * 100).toFixed(errShare >= 0.1 ? 0 : 1)}%`}
            </dd>
          </div>
        </dl>
        {faults.length > 0 && (
          <ul className="mt-2 space-y-1">
            {faults.map((f) => (
              <li
                key={f.id}
                className={cn(
                  "flex items-center gap-1.5 truncate rounded-sm border px-1.5 py-0.5 text-[11px]",
                  f.kind === "outage" ? "border-critical bg-critical-soft text-danger-ink" : "border-serious bg-serious-soft text-ink",
                )}
              >
                <span className="font-bold">{f.kind === "outage" ? "Outage" : f.kind === "slowdown" ? "Slow" : "Latency"}</span>
                <span className="truncate">{faultLabel(report, f)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
