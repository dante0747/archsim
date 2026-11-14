"use client";

/**
 * The transport: replay buttons and an LCD in the top bar, like a pro audio app's control bar.
 * The LCD shows the simulated time and, at that moment of the active run (or for the whole run
 * when nothing is playing), throughput, p95 against the objective and success. Play opens the
 * Test workspace, which owns the replay loop.
 */
import { Pause, Play, SkipBack } from "lucide-react";
import { Tip } from "@/components/ui/misc";
import { runDuration, valueAt } from "@/lib/recorder";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useActiveReport } from "@/store/runs";
import { useUi } from "@/store/ui";

function clock(t: number | null): string {
  if (t == null || !Number.isFinite(t)) return "00:00.0";
  const v = Math.max(0, t);
  const m = Math.floor(v / 60);
  const s = v - m * 60;
  return `${String(m).padStart(2, "0")}:${s.toFixed(1).padStart(4, "0")}`;
}

function rate(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "–";
  return v >= 10_000 ? `${(v / 1000).toFixed(1)}k` : Math.round(v).toLocaleString("en-US");
}

function ms(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "–";
  return v >= 10_000 ? `${(v / 1000).toFixed(1)}s` : v >= 100 ? String(Math.round(v)) : v.toFixed(1);
}

function Cell({ label, children, last, className }: { label: string; children: React.ReactNode; last?: boolean; className?: string }) {
  return (
    <div className={cn("flex flex-col justify-center whitespace-nowrap px-3", !last && "border-r border-[color:var(--lcd-line)]", className)}>
      <span className="text-[10px] font-medium uppercase tracking-[0.08em] text-ink-3">{label}</span>
      <span className="font-readout text-[16px] leading-tight text-ink">{children}</span>
    </div>
  );
}

export function TransportLcd() {
  const report = useActiveReport();
  const playhead = useUi((s) => s.playhead);
  const playing = useUi((s) => s.playing);
  const mode = useUi((s) => s.mode);
  const objectives = useEditor((s) => s.objectives);
  const plannedS = useEditor((s) => s.workload.durationS);
  const ts = report?.timeseries;
  const b = ts?.bucketS || 1;
  const total = report ? runDuration(report) : plannedS;
  // Live values while replaying or scrubbing inside the run; the run's summary otherwise (and once a
  // replay has run to the end, where the last bucket only holds the drain).
  const live = playhead != null && !!ts && (playing || (mode === "stress" && playhead < total - b));
  const at = (series: (number | null)[] | undefined) => (live ? valueAt(series, playhead!, b, "linear") : null);

  const thr = live ? at(ts!.throughputRps) : (report?.summary.throughputRps ?? null);
  const p95 = live ? at(ts!.latencyP95Ms) : (report?.summary.latencyMs.p95 ?? null);
  let success = report?.summary.successRate ?? null;
  if (live) {
    const ok = at(ts!.throughputRps);
    const bad = (at(ts!.errorRps) ?? 0) + (at(ts!.timeoutRps) ?? 0);
    success = ok != null && ok + bad > 0 ? ok / (ok + bad) : null;
  }
  const p95Bad = p95 != null && objectives.latencyP95Ms != null && p95 > objectives.latencyP95Ms;
  const successBad = success != null && objectives.availability != null && success < objectives.availability;

  return (
    <div
      className="hidden h-11 shrink-0 items-stretch rounded-[10px] border border-line bg-[color:var(--lcd)] shadow-[inset_0_1px_0_var(--highlight)] lg:flex"
      aria-label={report ? (live ? `Replay at ${clock(playhead)}` : "Last run") : "No run yet"}
      data-testid="transport-lcd"
    >
      {/* The top bar has room for the run's length from 2xl (1536 px), for the throughput from 1440 px. */}
      <Cell label="Time">
        {clock(live ? playhead : report ? total : 0)}
        <span className="hidden text-[11px] text-ink-3 2xl:inline"> / {clock(total).slice(0, 5)}</span>
      </Cell>
      <Cell label="Throughput" className="hidden min-[1440px]:flex">
        <span style={{ color: report ? "var(--accent-2)" : undefined }}>{rate(thr)}</span>
        <span className="text-[11px] text-ink-3"> req/s</span>
      </Cell>
      <Cell label={objectives.latencyP95Ms != null ? `p95 · goal ${Math.round(objectives.latencyP95Ms)}` : "p95"}>
        <span style={{ color: p95Bad ? "var(--status-critical)" : undefined }}>{ms(p95)}</span>
        <span className="text-[11px] text-ink-3"> ms</span>
      </Cell>
      <Cell label="Success" last>
        <span style={{ color: success == null ? undefined : successBad ? "var(--status-critical)" : "var(--status-good)" }}>
          {success == null ? "–" : (success * 100).toFixed(success >= 0.999 ? 2 : 1)}
        </span>
        <span className="text-[11px] text-ink-3"> %</span>
      </Cell>
    </div>
  );
}

const btn =
  "flex size-9 items-center justify-center rounded-[9px] border border-line-strong bg-surface-2 text-ink-2 shadow-[inset_0_1px_0_var(--highlight)] transition-colors hover:text-ink disabled:opacity-40 [&_svg]:size-3.5";

export function TransportButtons() {
  const report = useActiveReport();
  const playing = useUi((s) => s.playing);
  const setMode = useUi((s) => s.setMode);
  const setPlayhead = useUi((s) => s.setPlayhead);
  const setPlaying = useUi((s) => s.setPlaying);
  const disabled = !report;
  return (
    <div className="flex items-center gap-1.5" role="group" aria-label="Replay">
      <Tip content="Back to the start">
        <button
          type="button"
          className={btn}
          disabled={disabled}
          aria-label="Back to the start"
          onClick={() => {
            setPlaying(false);
            setPlayhead(0);
          }}
        >
          <SkipBack className="fill-current" />
        </button>
      </Tip>
      <Tip content={disabled ? "Simulate first, then replay the run" : playing ? "Pause the replay" : "Replay the run"}>
        <button
          type="button"
          className={cn(btn, !disabled && "text-[color:var(--status-good)] hover:text-[color:var(--status-good)]")}
          disabled={disabled}
          aria-label={playing ? "Pause the replay" : "Replay the run"}
          data-testid="transport-play"
          onClick={() => {
            if (playing) {
              setPlaying(false);
              return;
            }
            setMode("stress");
            const ui = useUi.getState();
            if (ui.playhead == null) setPlayhead(0);
            setPlaying(true);
          }}
        >
          {playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}
        </button>
      </Tip>
    </div>
  );
}
