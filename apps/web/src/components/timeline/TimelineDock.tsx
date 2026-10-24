"use client";

/**
 * Flight recorder dock (Stress workspace): one time axis for what you will run next (traffic and
 * fault tracks) and what the last run recorded (system strips, events, per-component heat lanes).
 * Transport plays the recording back onto the canvas; click or drag any plot to scrub.
 */
import {
  ChevronDown,
  ChevronFirst,
  ChevronLast,
  ChevronUp,
  Diamond,
  Pause,
  Play,
  StepBack,
  StepForward,
  Sigma,
} from "lucide-react";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ExperimentTracks } from "@/components/stress/ExperimentTracks";
import { LANE_H, LaneRow, RecorderLanes, Ruler } from "@/components/timeline/RecorderLanes";
import { TIMELINE_LABEL_WIDTH, TimelineProvider, useTimeline } from "@/components/timeline/TimelineContext";
import {
  SPEEDS,
  jumpTo,
  showSummary,
  stepBucket,
  togglePlay,
  usePlaybackLoop,
  useTransportKeys,
} from "@/components/timeline/playback";
import { Kbd, Tip } from "@/components/ui/misc";
import { useIsDark } from "@/components/ui/theme-toggle";
import { axisDuration, detectEvents, fmtClock, heatLegend, phaseAt, runDuration } from "@/lib/recorder";
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

function SectionRow({ title, right }: { title: React.ReactNode; right?: React.ReactNode }) {
  const { labelWidth, plotWidth } = useTimeline();
  return (
    <div className="flex border-b border-line bg-surface-2/70" style={{ height: LANE_H.section }}>
      <div className="flex shrink-0 items-center border-r border-line px-3 font-mono text-[9.5px] uppercase tracking-[0.1em] text-ink-3" style={{ width: labelWidth }}>
        <span className="mr-1 opacity-60" aria-hidden>
          {"//"}
        </span>
        {title}
      </div>
      <div className="flex shrink-0 items-center justify-end gap-3 overflow-hidden px-2 font-mono text-[9.5px] text-ink-3" style={{ width: plotWidth }}>
        {right}
      </div>
    </div>
  );
}

function HeatLegend({ dark }: { dark: boolean }) {
  const stops = heatLegend(dark);
  return (
    <span className="flex items-center gap-2" aria-label="Heat scale: utilization">
      <span>busy</span>
      {stops.map((s) => (
        <span key={s.label} className="flex items-center gap-1">
          <span className={cn("h-2 rounded-[1px]", s.label === "<70%" ? "w-8" : "w-2.5")} style={{ background: s.color }} aria-hidden />
          {s.label}
        </span>
      ))}
      <span className="flex items-center gap-1">
        <span className="h-px w-3 bg-ink" aria-hidden />
        queue
      </span>
      <span className="flex items-center gap-1">
        <span className="h-[2px] w-2.5 bg-critical" aria-hidden />
        rejected
      </span>
    </span>
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
        <span className="font-mono text-[10.5px] text-ink-3">
          last run
          <span className="mt-1 block text-[9.5px]">none yet</span>
        </span>
      }
    >
      <div className="flex h-full items-center gap-5 px-6">
        <div className="relative flex size-12 shrink-0 items-center justify-center rounded-md border border-dashed border-line-strong text-ink-3">
          <span className={cn("size-2.5 rounded-full", running ? "pulse-soft bg-accent" : "bg-ink-3")} aria-hidden />
        </div>
        <div className="min-w-0">
          <p className="text-[13.5px] font-semibold text-ink">
            {running ? "Recording the run…" : "Press Simulate to record a run"}
          </p>
          <p className="mt-1 text-[12px] text-ink-3">Replay it here, second by second.</p>
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
  const run = useRuns((s) => s.runs.find((r) => r.id === s.activeRunId) ?? null);
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

  const prevEvent = playhead == null ? null : [...events].reverse().find((ev) => ev.t < playhead - 0.05);
  const nextEvent = events.find((ev) => ev.t > (playhead ?? -1) + 0.05);
  const phase = report && playhead != null ? phaseAt(playhead, report.window) : null;
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

      <header className="flex shrink-0 items-center gap-3 border-b border-line px-2" style={{ height: HEADER_H }}>
        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          className="flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-sm px-1.5 py-1 font-mono text-[10px] uppercase tracking-[0.1em] text-ink-3 hover:bg-surface-2 hover:text-ink"
          aria-expanded={!collapsed}
          aria-label={collapsed ? "Expand the timeline" : "Collapse the timeline"}
        >
          {collapsed ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
          <span className="hidden sm:inline">
            <span className="opacity-60">{"//"}</span> timeline
          </span>
        </button>

        <div className="flex items-center gap-0.5" role="toolbar" aria-label="Playback">
          <TransportButton label="Jump to start" shortcut="Home" onClick={() => jumpTo(0, endS)} disabled={noRun}>
            <ChevronFirst />
          </TransportButton>
          <TransportButton label="Step back one bucket" shortcut="←" onClick={() => stepBucket(-1, endS, bucketS)} disabled={noRun}>
            <StepBack />
          </TransportButton>
          <TransportButton label={playing ? "Pause" : "Play the recording"} shortcut="Space" onClick={() => togglePlay(endS)} disabled={noRun} primary>
            {playing ? <Pause className="fill-current" /> : <Play className="fill-current" />}
          </TransportButton>
          <TransportButton label="Step forward one bucket" shortcut="→" onClick={() => stepBucket(1, endS, bucketS)} disabled={noRun}>
            <StepForward />
          </TransportButton>
          <TransportButton label="Jump to end" shortcut="End" onClick={() => jumpTo(endS, endS)} disabled={noRun}>
            <ChevronLast />
          </TransportButton>
          <span className="mx-1 h-4 w-px bg-line-strong" aria-hidden />
          <TransportButton label={prevEvent ? `Previous event: ${prevEvent.label}` : "Previous event"} onClick={() => prevEvent && jumpTo(prevEvent.t, endS)} disabled={!prevEvent}>
            <span className="flex items-center">
              <ChevronFirst className="!size-3" />
              <Diamond className="!size-2.5" />
            </span>
          </TransportButton>
          <TransportButton label={nextEvent ? `Next event: ${nextEvent.label}` : "Next event"} onClick={() => nextEvent && jumpTo(nextEvent.t, endS)} disabled={!nextEvent || noRun}>
            <span className="flex items-center">
              <Diamond className="!size-2.5" />
              <ChevronLast className="!size-3" />
            </span>
          </TransportButton>
        </div>

        <div className="hidden items-center rounded-sm border border-line p-0.5 md:flex" role="radiogroup" aria-label="Playback speed">
          {SPEEDS.map((v) => (
            <button
              key={v}
              type="button"
              role="radio"
              aria-checked={speed === v}
              onClick={() => setSpeed(v)}
              className={cn(
                "h-5 rounded-[2px] px-1.5 font-mono text-[10px] tabular-nums",
                speed === v ? "bg-surface-3 text-ink" : "text-ink-3 hover:text-ink",
              )}
            >
              {v}×
            </button>
          ))}
        </div>

        <div className="flex min-w-0 items-baseline gap-2 font-mono" aria-live="off">
          {report ? (
            playhead != null ? (
              <>
                <span className="text-[10px] text-ink-3">t</span>
                <span className="tabular text-[15px] font-semibold text-ink">{playhead.toFixed(1)}</span>
                <span className="text-[11px] text-ink-3">s / {fmtClock(endS)}</span>
                {phase && (
                  <span
                    className={cn(
                      "rounded-sm border px-1 text-[9px] font-semibold uppercase leading-4 tracking-wider",
                      phase === "measured" ? "border-accent/40 text-accent" : "border-line-strong text-ink-3",
                    )}
                  >
                    {phase}
                  </span>
                )}
              </>
            ) : (
              <span className="truncate text-[12px] text-ink-3">Press ▶ to replay</span>
            )
          ) : (
            <span className="truncate text-[12px] text-ink-3">Nothing recorded yet</span>
          )}
        </div>

        <div className="ml-auto flex items-center gap-2">
          {report && (
            <span className="hidden truncate font-mono text-[10.5px] text-ink-3 lg:inline" title="Recording shown">
              {run?.label ?? "last run"} · seed {report.provenance.seed} · {events.length} events
            </span>
          )}
          <TransportButton label="Show the whole run on the canvas" onClick={showSummary} disabled={noRun} pressed={!!report && playhead == null}>
            <Sigma />
          </TransportButton>
        </div>
      </header>

      {!collapsed && (
        <div ref={bodyRef} className="relative min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
          {bodyW > 0 && (
            <TimelineProvider durationS={axisS} plotWidth={plotWidth}>
              <div className="relative pb-2">
                <Ruler warmupS={report ? report.window.warmupS : workloadWarmupS} scrubbable={!!report} endS={endS} />
                <SectionRow title="next run" />
                <div data-no-transport-keys>
                  <ExperimentTracks />
                </div>
                {report ? (
                  <>
                    <SectionRow title="last run" right={<HeatLegend dark={dark} />} />
                    <RecorderLanes report={report} objectives={objectives} events={events} dark={dark} endS={endS} />
                  </>
                ) : (
                  <>
                    <SectionRow title="last run" />
                    <EmptyRecording height={104} />
                  </>
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
