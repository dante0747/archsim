"use client";

/**
 * Flight recorder dock (Stress workspace): one time axis for what you will run next (traffic and
 * fault tracks) and what the last run recorded (system strips, events, per-component heat lanes).
 * Transport plays the recording back onto the canvas; click or drag any plot to scrub.
 */
import { ChevronDown, ChevronUp, Pause, Play } from "lucide-react";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ExperimentTracks } from "@/components/stress/ExperimentTracks";
import { LaneRow, RecorderLanes, Ruler } from "@/components/timeline/RecorderLanes";
import { TIMELINE_LABEL_WIDTH, TimelineProvider, useTimeline } from "@/components/timeline/TimelineContext";
import { SPEEDS, showSummary, togglePlay, usePlaybackLoop, useTransportKeys } from "@/components/timeline/playback";
import { Kbd, Tip } from "@/components/ui/misc";
import { useIsDark } from "@/components/ui/theme-toggle";
import { axisDuration, detectEvents, fmtClock, runDuration } from "@/lib/recorder";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export { PlaybackClock } from "@/components/timeline/PlaybackClock";

const HEIGHT_KEY = "archsim-timeline-h";
const MIN_H = 150;
/** Leave most of the screen to the canvas: about a third of the window, at most 280 px. */
const defaultHeight = () => Math.round(Math.min(280, Math.max(MIN_H, window.innerHeight * 0.3)));
const HEADER_H = 40;

function readHeight(): number {
  try {
    const v = Number(localStorage.getItem(HEIGHT_KEY));
    return Number.isFinite(v) && v >= MIN_H ? v : defaultHeight();
  } catch {
    return defaultHeight();
  }
}

function writeHeight(h: number) {
  try {
    localStorage.setItem(HEIGHT_KEY, String(Math.round(h)));
  } catch {
    /* storage unavailable */
  }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

function TransportButton({
  label,
  shortcut,
  onClick,
  disabled,
  children,
  primary,
  pressed,
}: {
  label: string;
  shortcut?: string;
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
  primary?: boolean;
  pressed?: boolean;
}) {
  return (
    <Tip side="top" content={<span className="flex items-center gap-2">{label}{shortcut && <Kbd>{shortcut}</Kbd>}</span>}>
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        aria-label={label}
        aria-pressed={pressed}
        className={cn(
          "flex items-center justify-center rounded-sm transition-colors disabled:pointer-events-none disabled:opacity-35 [&_svg]:size-3.5",
          primary
            ? "size-7 bg-accent text-accent-ink hover:brightness-110"
            : "size-7 text-ink-2 hover:bg-surface-2 hover:text-ink",
          pressed && !primary && "bg-accent-soft text-accent",
        )}
      >
        {children}
      </button>
    </Tip>
  );
}

/** Playhead line across every row, with a time flag on the ruler. */
function PlayheadLine({ endS }: { endS: number }) {
  const playhead = useUi((s) => s.playhead);
  const { x, labelWidth, durationS } = useTimeline();
  if (playhead == null || playhead > durationS || endS <= 0) return null;
  const left = labelWidth + x(playhead);
  return (
    <div className="pointer-events-none absolute inset-y-0 z-20" style={{ left }} aria-hidden>
      <div className="absolute inset-y-0 -left-px w-[2px] bg-accent shadow-[0_0_8px_var(--accent)]" />
      <div className="absolute left-0 top-0 -translate-x-1/2 rounded-sm bg-accent px-1 font-mono text-[9.5px] font-semibold leading-[14px] tabular-nums text-accent-ink">
        {playhead.toFixed(1)}
      </div>
    </div>
  );
}

function EmptyRecording({ height }: { height: number }) {
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
  return (
    <LaneRow
      height={height}
      label={
        <span className="text-[12px] text-ink-3">Last run</span>
      }
    >
      <div className="flex h-full items-center gap-5 px-6">
        <div className="relative flex size-12 shrink-0 items-center justify-center rounded-md border border-dashed border-line-strong text-ink-3">
          <span className={cn("size-2.5 rounded-full", running ? "pulse-soft bg-accent" : "bg-ink-3")} aria-hidden />
        </div>
        <div className="min-w-0">
          <p className="text-[13px] text-ink-2">{running ? "Recording the run…" : "Press Simulate to record a run"}</p>
          {running && followRun ? (
            <div className="mt-2 flex items-center gap-2 font-mono text-[11px] text-ink-2" role="status">
              <span className="block h-1.5 w-40 overflow-hidden rounded-[1px] bg-surface-3" aria-hidden>
                <span className="progress-stripes block h-full" style={{ width: `${Math.max(4, followRun.progress * 100)}%` }} />
              </span>
              {followRun.status === "queued" ? "queued" : `${Math.round(followRun.progress * 100)}%`}
            </div>
          ) : null}
        </div>
      </div>
    </LaneRow>
  );
}

// ---------------------------------------------------------------------------------------------
// Dock
// ---------------------------------------------------------------------------------------------

export function TimelineDock() {
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const workloadDurationS = useEditor((s) => s.workload.durationS);
  const workloadWarmupS = useEditor((s) => s.workload.warmupS);
  const objectives = useEditor((s) => s.objectives);
  const playhead = useUi((s) => s.playhead);
  const playing = useUi((s) => s.playing);
  const speed = useUi((s) => s.speed);
  const setSpeed = useUi((s) => s.setSpeed);
  const dark = useIsDark();

  const endS = report ? runDuration(report) : 0;
  const bucketS = report?.timeseries.bucketS || 1;
  const axisS = axisDuration(workloadDurationS, report);
  const events = useMemo(() => (report ? detectEvents(report, objectives) : []), [report, objectives]);

  usePlaybackLoop(endS, bucketS);
  useTransportKeys(!!report, endS, bucketS);

  // A new recording replaces the old one: stop playback and drop a playhead past its end.
  useEffect(() => {
    const ui = useUi.getState();
    ui.setPlaying(false);
    if (ui.playhead != null && (!report || ui.playhead > runDuration(report))) ui.setPlayhead(null);
  }, [report]);

  // The editor renders client-side only (after the project loads), so storage is readable here.
  const [height, setHeight] = useState(readHeight);
  const [collapsed, setCollapsed] = useState(false);

  const bodyRef = useRef<HTMLDivElement>(null);
  const [bodyW, setBodyW] = useState(0);
  useLayoutEffect(() => {
    const el = bodyRef.current;
    if (!el) return;
    const update = () => setBodyW(el.clientWidth);
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [collapsed]);
  const plotWidth = Math.max(160, bodyW - TIMELINE_LABEL_WIDTH - 14);

  const onResizeStart = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const el = e.currentTarget;
    el.setPointerCapture(e.pointerId);
    const y0 = e.clientY;
    const h0 = collapsed ? HEADER_H : height;
    let last = h0;
    const move = (ev: PointerEvent) => {
      const max = Math.max(MIN_H, window.innerHeight * 0.72);
      last = Math.min(max, Math.max(MIN_H, h0 + (y0 - ev.clientY)));
      setCollapsed(false);
      setHeight(last);
    };
    const up = () => {
      writeHeight(last);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
  };

  const noRun = !report;

  return (
    <section
      aria-label="Replay timeline"
      className="relative flex shrink-0 flex-col border-t border-line-strong bg-surface"
      style={{ height: collapsed ? HEADER_H : height }}
    >
      <div
        role="separator"
        aria-orientation="horizontal"
        aria-label="Resize the timeline"
        title="Drag to resize · double-click to collapse"
        className="group absolute inset-x-0 -top-1 z-30 h-2 cursor-ns-resize"
        onPointerDown={onResizeStart}
        onDoubleClick={() => setCollapsed((c) => !c)}
      >
        <span className="mx-auto mt-[3px] block h-[2px] w-10 rounded-full bg-line-strong opacity-0 transition-opacity group-hover:opacity-100" />
      </div>

      <header className="flex shrink-0 items-center gap-2 border-b border-line px-2" style={{ height: HEADER_H }}>
        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          className="flex shrink-0 items-center gap-1.5 rounded-md px-1.5 py-1 text-[12.5px] font-medium text-ink-2 hover:bg-surface-2 hover:text-ink"
          aria-expanded={!collapsed}
          aria-label={collapsed ? "Expand the timeline" : "Collapse the timeline"}
        >
          {collapsed ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
          Timeline
        </button>

        <TransportButton label={playing ? "Pause" : "Play"} shortcut="Space" onClick={() => togglePlay(endS)} disabled={noRun} primary>
          {playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}
        </TransportButton>

        <div className="flex min-w-0 items-baseline gap-1.5" aria-live="off">
          {report && playhead != null ? (
            <>
              <span className="tabular font-mono text-[14px] font-semibold text-ink">{playhead.toFixed(1)} s</span>
              <span className="font-mono text-[11px] text-ink-3">/ {fmtClock(endS)}</span>
            </>
          ) : (
            <span className="truncate text-[12.5px] text-ink-3">{report ? "Press play to replay the run" : "Nothing recorded yet"}</span>
          )}
        </div>

        <div className="ml-auto flex items-center gap-1">
          {report && playhead != null && (
            <button type="button" onClick={showSummary} className="rounded-md px-2 py-1 text-[12.5px] text-ink-3 hover:bg-surface-2 hover:text-ink">
              Whole run
            </button>
          )}
          <Tip content="Playback speed">
            <button
              type="button"
              onClick={() => setSpeed(SPEEDS[(SPEEDS.indexOf(speed as (typeof SPEEDS)[number]) + 1) % SPEEDS.length])}
              disabled={noRun}
              className="h-7 min-w-9 rounded-md border border-line px-2 font-mono text-[11.5px] text-ink-2 hover:border-line-strong hover:text-ink disabled:opacity-40"
              aria-label={`Playback speed ${speed}×, click to change`}
            >
              {speed}×
            </button>
          </Tip>
        </div>
      </header>

      {!collapsed && (
        <div ref={bodyRef} className="relative min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
          {bodyW > 0 && (
            <TimelineProvider durationS={axisS} plotWidth={plotWidth}>
              <div className="relative pb-2">
                <Ruler warmupS={report ? report.window.warmupS : workloadWarmupS} scrubbable={!!report} endS={endS} />
                <div data-no-transport-keys>
                  <ExperimentTracks />
                </div>
                <div className="h-2 border-b border-line bg-surface-2/50" aria-hidden />
                {report ? (
                  <RecorderLanes report={report} objectives={objectives} events={events} dark={dark} endS={endS} />
                ) : (
                  <EmptyRecording height={88} />
                )}
                <PlayheadLine endS={endS} />
              </div>
            </TimelineProvider>
          )}
        </div>
      )}
    </section>
  );
}
