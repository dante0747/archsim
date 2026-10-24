"use client";

/**
 * Output lanes of the flight recorder, drawn on the shared time axis (TimelineContext):
 * ruler, event markers, system strips (throughput vs offered, p95 vs SLO, failures) and one heat
 * lane per component (utilization per bucket, queue as a thin line, rejections as red ticks).
 * Click or drag in any plot to scrub; hover for exact values.
 */
import {
  ArrowDownToLine,
  ArrowUpFromLine,
  ChevronRight,
  Flame,
  Layers,
  ShieldAlert,
  ShieldCheck,
  Timer,
  Zap,
  ZapOff,
} from "lucide-react";
import { memo, useMemo, useState } from "react";
import { useTimeline } from "@/components/timeline/TimelineContext";
import { jumpTo } from "@/components/timeline/playback";
import { fmtMs, fmtPct } from "@/lib/format";
import {
  type RecorderEvent,
  type RecorderEventKind,
  bucketIndex,
  faultLabel,
  fmtClock,
  fmtRate,
  heatColor,
  heatLegend,
  runFaults,
  tickStep,
} from "@/lib/recorder";
import { sloTimeline } from "@/lib/slo";
import type { Fault, NodeReport, Objectives, Report, Series } from "@/lib/types";
import { cn } from "@/lib/utils";

export const LANE_H = { ruler: 22, section: 22, events: 26, strip: 44, errors: 32, heat: 22 } as const;

const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);
const seriesMax = (...series: (Series | undefined)[]) => {
  let m = 0;
  for (const s of series) for (const v of s ?? []) if (finite(v) && v > m) m = v;
  return m;
};

// ---------------------------------------------------------------------------------------------
// Layout primitives
// ---------------------------------------------------------------------------------------------

export function LaneRow({
  label,
  height,
  lane,
  children,
  className,
  labelClassName,
}: {
  label: React.ReactNode;
  height: number;
  lane?: string;
  children?: React.ReactNode;
  className?: string;
  labelClassName?: string;
}) {
  const { labelWidth, plotWidth } = useTimeline();
  return (
    <div data-lane={lane} className={cn("flex border-b border-line", className)} style={{ height }}>
      <div
        className={cn("flex shrink-0 items-center gap-2 overflow-hidden border-r border-line px-3", labelClassName)}
        style={{ width: labelWidth }}
      >
        {label}
      </div>
      <div className="relative shrink-0" style={{ width: plotWidth, height }}>
        {children}
      </div>
    </div>
  );
}

/** Pointer handler that scrubs the playhead on a row whose plot starts after the label column. */
export function useScrub(enabled: boolean, endS: number) {
  const { labelWidth, t } = useTimeline();
  return (e: React.PointerEvent<HTMLElement>) => {
    if (!enabled || e.button !== 0) return;
    const el = e.currentTarget;
    const rect = el.getBoundingClientRect();
    const toT = (clientX: number) => t(clientX - rect.left - labelWidth);
    if (e.clientX - rect.left < labelWidth) return;
    e.preventDefault();
    el.setPointerCapture(e.pointerId);
    jumpTo(toT(e.clientX), endS);
    const move = (ev: PointerEvent) => jumpTo(toT(ev.clientX), endS);
    const up = () => {
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
  };
}

// ---------------------------------------------------------------------------------------------
// Ruler
// ---------------------------------------------------------------------------------------------

export function Ruler({ warmupS, scrubbable, endS }: { warmupS: number | null; scrubbable: boolean; endS: number }) {
  const { x, durationS, plotWidth, labelWidth } = useTimeline();
  const step = tickStep(durationS, plotWidth);
  const ticks: number[] = [];
  for (let v = 0; v <= durationS + 1e-6; v += step) ticks.push(Math.round(v * 1000) / 1000);
  const onPointerDown = useScrub(scrubbable, endS);
  return (
    <div
      className={cn("sticky top-0 z-10 flex border-b border-line bg-surface", scrubbable && "cursor-ew-resize")}
      style={{ height: LANE_H.ruler }}
      onPointerDown={onPointerDown}
    >
      <div className="flex shrink-0 items-center border-r border-line px-3 font-mono text-[9.5px] uppercase tracking-wider text-ink-3" style={{ width: labelWidth }}>
      </div>
      <div className="relative shrink-0" style={{ width: plotWidth }}>
        {warmupS != null && warmupS > 0 && (
          <div
            className="absolute inset-y-0 left-0 flex items-center justify-center overflow-hidden font-mono text-[9px] uppercase tracking-wider text-ink-3"
            style={{ width: x(warmupS), background: "var(--warmup-wash)" }}
          >
            {x(warmupS) > 56 ? "warm-up" : ""}
          </div>
        )}
        {ticks.map((v) => (
          <div key={v} className="absolute bottom-0 top-0" style={{ left: x(v) }}>
            <div className="absolute bottom-0 h-1.5 w-px bg-line-strong" />
            <span
              className="absolute top-1 -translate-x-1/2 font-mono text-[9.5px] tabular-nums text-ink-3"
              style={{ transform: v === 0 ? "translateX(2px)" : v >= durationS - 1e-6 ? "translateX(calc(-100% - 2px))" : undefined }}
            >
              {v < 120 ? `${v}` : `${Math.floor(v / 60)}:${String(Math.round(v % 60)).padStart(2, "0")}`}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

const EVENT_ICON: Record<RecorderEventKind, React.ComponentType<{ className?: string; style?: React.CSSProperties }>> = {
  fault_start: Zap,
  fault_end: ZapOff,
  saturation: Flame,
  slo_breach: ShieldAlert,
  slo_recovered: ShieldCheck,
  scale_up: ArrowUpFromLine,
  scale_down: ArrowDownToLine,
  queue_growth: Layers,
  timeouts: Timer,
};

export const SEVERITY_COLOR: Record<RecorderEvent["severity"], string> = {
  critical: "var(--status-critical)",
  warning: "var(--status-warning)",
  info: "var(--accent)",
  ok: "var(--status-good)",
};

interface Cluster {
  px: number;
  events: RecorderEvent[];
}

function clusterEvents(events: RecorderEvent[], x: (t: number) => number, minPx = 12): Cluster[] {
  const out: Cluster[] = [];
  for (const e of events) {
    const px = x(e.t);
    const last = out[out.length - 1];
    if (last && px - last.px < minPx) last.events.push(e);
    else out.push({ px, events: [e] });
  }
  return out;
}

const RANK = { critical: 0, warning: 1, info: 2, ok: 3 } as const;

export function EventRow({ events, endS, onHover }: { events: RecorderEvent[]; endS: number; onHover: (c: Cluster | null, px: number) => void }) {
  const { x } = useTimeline();
  const clusters = useMemo(() => clusterEvents(events, x), [events, x]);
  return (
    <LaneRow
      lane="events"
      height={LANE_H.events}
      label={
        <span className="text-[12px] text-ink-2">Events</span>
      }
    >
      <div className="absolute inset-x-0 top-1/2 h-px bg-line" aria-hidden />
      {clusters.map((c) => {
        const lead = [...c.events].sort((a, b) => RANK[a.severity] - RANK[b.severity])[0];
        const Icon = EVENT_ICON[lead.kind];
        return (
          <button
            key={`${c.px}-${lead.kind}`}
            type="button"
            className="absolute top-1/2 flex size-5 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-sm border border-line-strong bg-surface transition-transform hover:z-10 hover:scale-110 focus-visible:z-10"
            style={{ left: c.px }}
            aria-label={c.events.map((e) => `${fmtClock(e.t)} ${e.label}`).join("; ")}
            onPointerDown={(e) => e.stopPropagation()}
            onClick={() => jumpTo(c.events[0].t, endS)}
            onMouseEnter={() => onHover(c, c.px)}
            onMouseLeave={() => onHover(null, c.px)}
            onFocus={() => onHover(c, c.px)}
            onBlur={() => onHover(null, c.px)}
          >
            <Icon className="size-3" style={{ color: SEVERITY_COLOR[lead.severity] }} />
            {c.events.length > 1 && (
              <span className="absolute -right-1.5 -top-1.5 rounded-sm bg-ink px-[3px] font-mono text-[8.5px] font-bold leading-[11px] text-surface">
                {c.events.length}
              </span>
            )}
          </button>
        );
      })}
    </LaneRow>
  );
}

// ---------------------------------------------------------------------------------------------
// System strips
// ---------------------------------------------------------------------------------------------

function linePath(series: Series, x: (t: number) => number, y: (v: number) => number, b: number): string {
  let d = "";
  let pen = false;
  series.forEach((v, i) => {
    if (!finite(v)) {
      pen = false;
      return;
    }
    const px = x(i * b + b / 2).toFixed(1);
    const py = y(v).toFixed(1);
    d += `${pen ? "L" : "M"}${px},${py}`;
    pen = true;
  });
  return d;
}

function StripLabel({ title, max, hint }: { title: string; max?: string; hint?: string }) {
  return (
    <span className="flex w-full min-w-0 items-baseline justify-between gap-2" title={hint}>
      <span className="truncate text-[12px] text-ink-2">{title}</span>
      {max && <span className="shrink-0 font-mono text-[10px] tabular-nums text-ink-3">{max}</span>}
    </span>
  );
}

function HeatLegend({ dark }: { dark: boolean }) {
  const stops = heatLegend(dark);
  return (
    <span className="flex items-center gap-2 font-mono text-[10px] text-ink-3" aria-label="Heat scale: how busy each part was">
      {stops.map((st) => (
        <span key={st.label} className="flex items-center gap-1">
          <span className={cn("h-2 rounded-[1px]", st.label === "<70%" ? "w-6" : "w-2.5")} style={{ background: st.color }} aria-hidden />
          {st.label}
        </span>
      ))}
    </span>
  );
}

function readDetail(): boolean {
  try {
    return localStorage.getItem("archsim-timeline-detail") === "1";
  } catch {
    return false;
  }
}

const ThroughputStrip = memo(function ThroughputStrip({ report, plotWidth, durationS }: { report: Report; plotWidth: number; durationS: number }) {
  const ts = report.timeseries;
  const b = ts.bucketS || 1;
  const H = LANE_H.strip;
  const max = Math.max(1, seriesMax(ts.offeredRps, ts.throughputRps)) * 1.12;
  const x = (t: number) => (t / durationS) * plotWidth;
  const y = (v: number) => H - 3 - (v / max) * (H - 8);
  const thr = linePath(ts.throughputRps, x, y, b);
  const off = linePath(ts.offeredRps, x, y, b);
  const area = thr ? `${thr}L${x((ts.t.length - 0.5) * b).toFixed(1)},${H - 3}L${x(b / 2).toFixed(1)},${H - 3}Z` : "";
  return (
    <svg width={plotWidth} height={H} className="absolute inset-0" aria-hidden>
      {area && <path d={area} fill="var(--accent)" fillOpacity={0.1} />}
      <path d={off} fill="none" stroke="var(--ink-3)" strokeWidth={1.25} strokeDasharray="3 3" />
      <path d={thr} fill="none" stroke="var(--accent)" strokeWidth={1.75} strokeLinejoin="round" />
    </svg>
  );
});

const LatencyStrip = memo(function LatencyStrip({
  report,
  objectives,
  plotWidth,
  durationS,
}: {
  report: Report;
  objectives: Objectives;
  plotWidth: number;
  durationS: number;
}) {
  const ts = report.timeseries;
  const b = ts.bucketS || 1;
  const H = LANE_H.strip;
  const slo = objectives.latencyP95Ms ?? null;
  const max = Math.max(1, seriesMax(ts.latencyP95Ms), slo != null ? slo * 1.25 : 0) * 1.08;
  const x = (t: number) => (t / durationS) * plotWidth;
  const y = (v: number) => H - 3 - (Math.min(v, max) / max) * (H - 8);
  const compliance = useMemo(() => sloTimeline(objectives, ts), [objectives, ts]);
  const cw = x(b);
  return (
    <svg width={plotWidth} height={H} className="absolute inset-0" aria-hidden>
      {compliance.map((ok, i) =>
        ok === false ? <rect key={i} x={x(i * b)} y={0} width={Math.max(1, cw)} height={H} fill="var(--status-critical)" fillOpacity={0.12} /> : null,
      )}
      {slo != null && (
        <>
          <line x1={0} x2={plotWidth} y1={y(slo)} y2={y(slo)} stroke="var(--status-critical)" strokeOpacity={0.7} strokeWidth={1} strokeDasharray="5 4" />
          <text x={plotWidth - 4} y={y(slo) - 3} textAnchor="end" fontSize={9} fontFamily="var(--font-mono)" fill="var(--ink-3)">
            SLO {fmtMs(slo)}
          </text>
        </>
      )}
      <path d={linePath(ts.latencyP95Ms, x, y, b)} fill="none" stroke="var(--ink)" strokeWidth={1.5} strokeLinejoin="round" />
    </svg>
  );
});

const FailureStrip = memo(function FailureStrip({ report, plotWidth, durationS }: { report: Report; plotWidth: number; durationS: number }) {
  const ts = report.timeseries;
  const b = ts.bucketS || 1;
  const H = LANE_H.errors;
  const n = ts.t.length;
  let max = 0;
  for (let i = 0; i < n; i++) max = Math.max(max, (ts.errorRps[i] ?? 0) + (ts.timeoutRps[i] ?? 0));
  const x = (t: number) => (t / durationS) * plotWidth;
  const cw = x(b);
  const gap = cw > 4 ? 1 : 0;
  if (max <= 0) {
    return (
      <div className="absolute inset-0 flex items-center pl-2 font-mono text-[9.5px] text-ink-3">
        <span className="mr-2 h-px flex-1 bg-line" aria-hidden />
        no failed requests
        <span className="ml-2 h-px w-6 bg-line" aria-hidden />
      </div>
    );
  }
  const scale = (H - 5) / max;
  return (
    <svg width={plotWidth} height={H} className="absolute inset-0" aria-hidden>
      {Array.from({ length: n }, (_, i) => {
        const e = ts.errorRps[i] ?? 0;
        const t = ts.timeoutRps[i] ?? 0;
        if (e + t <= 0) return null;
        const he = e * scale;
        const ht = t * scale;
        const w = Math.max(1, cw - gap);
        return (
          <g key={i}>
            {e > 0 && <rect x={x(i * b)} y={H - 2 - he} width={w} height={Math.max(1, he)} fill="var(--status-critical)" />}
            {t > 0 && <rect x={x(i * b)} y={H - 2 - he - ht - (e > 0 ? 1 : 0)} width={w} height={Math.max(1, ht)} fill="var(--status-serious)" />}
          </g>
        );
      })}
    </svg>
  );
});

// ---------------------------------------------------------------------------------------------
// Heat lanes
// ---------------------------------------------------------------------------------------------

const HeatLane = memo(function HeatLane({
  node,
  report,
  faults,
  dark,
  plotWidth,
  durationS,
}: {
  node: NodeReport;
  report: Report;
  faults: Fault[];
  dark: boolean;
  plotWidth: number;
  durationS: number;
}) {
  const ts = node.timeseries ?? {};
  const b = report.timeseries.bucketS || 1;
  const H = LANE_H.heat;
  const x = (t: number) => (t / durationS) * plotWidth;
  const cw = x(b);
  const gap = cw > 4 ? 1 : 0;
  const util = ts.utilization;
  const queue = ts.lag ?? ts.queueMean;
  const qMax = seriesMax(queue);
  const arrivals = ts.arrivalRps;
  const aMax = seriesMax(arrivals);
  const rejected = ts.rejectedRps;
  const mine = faults.filter((f) => f.target === node.id);
  return (
    <svg width={plotWidth} height={H} className="absolute inset-0" aria-hidden>
      {util
        ? util.map((u, i) =>
            finite(u) ? (
              <rect key={i} x={x(i * b)} y={2} width={Math.max(1, cw - gap)} height={H - 4} rx={cw > 6 ? 1 : 0} fill={heatColor(u, dark)} />
            ) : null,
          )
        : arrivals && aMax > 0 && (
            <path
              d={`${linePath(arrivals, x, (v) => H - 3 - (v / aMax) * (H - 7), b)}L${x((arrivals.length - 0.5) * b)},${H - 3}L${x(b / 2)},${H - 3}Z`}
              fill="var(--ink-3)"
              fillOpacity={0.22}
              stroke="var(--ink-3)"
              strokeOpacity={0.6}
              strokeWidth={1}
            />
          )}
      {queue && qMax >= 1 && (
        <path
          d={linePath(queue, x, (v) => H - 3 - (v / qMax) * (H - 7), b)}
          fill="none"
          stroke="var(--ink)"
          strokeOpacity={0.65}
          strokeWidth={1}
          strokeLinejoin="round"
        />
      )}
      {rejected?.map((r, i) =>
        finite(r) && r > 0 ? <rect key={`r${i}`} x={x(i * b)} y={0} width={Math.max(1, cw - gap)} height={2} fill="var(--status-critical)" /> : null,
      )}
      {mine.map((f) => (
        <rect
          key={f.id}
          x={x(f.startS) + 0.5}
          y={0.5}
          width={Math.max(2, x(f.durationS) - 1)}
          height={H - 1}
          fill="none"
          stroke={f.kind === "outage" ? "var(--status-critical)" : "var(--status-serious)"}
          strokeWidth={1.25}
          strokeDasharray="3 2"
        />
      ))}
    </svg>
  );
});

// ---------------------------------------------------------------------------------------------
// Hover readout
// ---------------------------------------------------------------------------------------------

interface Hover {
  lane: string;
  px: number;
  y: number;
  t: number;
  cluster?: Cluster;
}

function readoutLines(h: Hover, report: Report, objectives: Objectives): string[] {
  const ts = report.timeseries;
  const b = ts.bucketS || 1;
  const i = bucketIndex(h.t, b, ts.t.length);
  if (h.cluster) return h.cluster.events.map((e) => `${fmtClock(e.t)}  ${e.label}`);
  const at = (s: Series | undefined) => (s ? (s[i] ?? null) : null);
  if (h.lane === "throughput") return [`throughput ${fmtRate(at(ts.throughputRps))}/s`, `offered ${fmtRate(at(ts.offeredRps))}/s`];
  if (h.lane === "latency") {
    const lines = [`p95 ${fmtMs(at(ts.latencyP95Ms))}`, `p50 ${fmtMs(at(ts.latencyP50Ms))} · p99 ${fmtMs(at(ts.latencyP99Ms))}`];
    if (objectives.latencyP95Ms != null) lines.push(`SLO p95 ${fmtMs(objectives.latencyP95Ms)}`);
    return lines;
  }
  if (h.lane === "failures") return [`errors ${fmtRate(at(ts.errorRps))}/s`, `timeouts ${fmtRate(at(ts.timeoutRps))}/s`];
  if (h.lane.startsWith("node:")) {
    const node = report.nodes.find((n) => `node:${n.id}` === h.lane);
    if (!node) return [];
    const s = node.timeseries ?? {};
    const lines = [node.label || node.id];
    const u = at(s.utilization);
    lines.push(`${u != null ? `${fmtPct(u, 0)} busy · ` : ""}${fmtRate(at(s.arrivalRps))}/s in`);
    const q = at(s.queueMean);
    const lag = at(s.lag);
    if (lag != null) lines.push(`lag ${fmtRate(lag)}`);
    else if (q != null) lines.push(`queue ${q >= 10 ? Math.round(q) : q.toFixed(1)} waiting`);
    const r = at(s.rejectedRps);
    if (r) lines.push(`rejected ${fmtRate(r)}/s`);
    const rep = at(s.replicas);
    if (rep != null) lines.push(`${Math.round(rep)} instances`);
    const faults = runFaults(report).filter((f) => f.target === node.id && h.t >= f.startS && h.t < f.startS + f.durationS);
    for (const f of faults) lines.push(faultLabel(report, f));
    return lines;
  }
  return [];
}

// ---------------------------------------------------------------------------------------------
// The lanes
// ---------------------------------------------------------------------------------------------

export function RecorderLanes({
  report,
  objectives,
  events,
  dark,
  endS,
}: {
  report: Report;
  objectives: Objectives;
  events: RecorderEvent[];
  dark: boolean;
  endS: number;
}) {
  const { x, t, plotWidth, labelWidth, durationS } = useTimeline();
  const [hover, setHover] = useState<Hover | null>(null);
  const [detail, setDetailState] = useState(readDetail);
  const setDetail = (v: boolean) => {
    setDetailState(v);
    try {
      localStorage.setItem("archsim-timeline-detail", v ? "1" : "0");
    } catch {
      /* storage unavailable */
    }
  };
  const onPointerDown = useScrub(true, endS);
  const faults = runFaults(report);
  const ts = report.timeseries;
  const win = report.window;

  const onMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.buttons) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - rect.left - labelWidth;
    const laneEl = (e.target as Element).closest?.("[data-lane]") as HTMLElement | null;
    const lane = laneEl?.dataset.lane;
    if (px < 0 || !lane || lane === "events") {
      if (!hover?.cluster) setHover(null);
      return;
    }
    setHover({ lane, px, y: e.clientY - rect.top, t: t(px) });
  };

  const lines = hover ? readoutLines(hover, report, objectives) : [];

  return (
    <div
      className="relative cursor-crosshair select-none"
      onPointerDown={onPointerDown}
      onPointerMove={onMove}
      onPointerLeave={() => setHover(null)}
    >
      {/* Background windows: warm-up and injected faults. */}
      <div className="pointer-events-none absolute inset-y-0" style={{ left: labelWidth, width: plotWidth }} aria-hidden>
        {win.warmupS > 0 && <div className="absolute inset-y-0 left-0" style={{ width: x(win.warmupS), background: "var(--warmup-wash)" }} />}
        {win.durationS < durationS && (
          <div
            className="absolute inset-y-0"
            style={{ left: x(win.durationS), width: Math.max(0, x(durationS) - x(win.durationS)), background: "var(--warmup-wash)" }}
          />
        )}
        {faults.map((f) => (
          <div
            key={f.id}
            className="absolute inset-y-0 border-x border-dashed"
            style={{
              left: x(f.startS),
              width: Math.max(2, x(f.durationS)),
              borderColor: "color-mix(in srgb, var(--status-critical) 45%, transparent)",
              background:
                "repeating-linear-gradient(135deg, color-mix(in srgb, var(--status-critical) 9%, transparent) 0 3px, transparent 3px 8px)",
            }}
          />
        ))}
      </div>

      <EventRow
        events={events}
        endS={endS}
        onHover={(c, px) => setHover(c ? { lane: "events", px, y: LANE_H.events, t: c.events[0].t, cluster: c } : null)}
      />
      <LaneRow
        lane="latency"
        height={LANE_H.strip}
        label={
          <StripLabel
            title="Latency"
            max={fmtMs(seriesMax(ts.latencyP95Ms))}
            hint={objectives.latencyP95Ms != null ? `p95 per second; dashed line = objective ${fmtMs(objectives.latencyP95Ms)}` : "p95 per second"}
          />
        }
      >
        <LatencyStrip report={report} objectives={objectives} plotWidth={plotWidth} durationS={durationS} />
      </LaneRow>
      <LaneRow
        lane="failures"
        height={LANE_H.errors}
        label={<StripLabel title="Failures" hint="Errors (red) and client timeouts (orange) per second" />}
      >
        <FailureStrip report={report} plotWidth={plotWidth} durationS={durationS} />
      </LaneRow>

      <div className="flex h-8 items-center border-b border-line" onPointerDown={(e) => e.stopPropagation()}>
        <button
          type="button"
          onClick={() => setDetail(!detail)}
          aria-expanded={detail}
          className="flex h-full shrink-0 items-center gap-1.5 border-r border-line px-3 text-[12px] text-ink-3 hover:text-ink"
          style={{ width: labelWidth }}
        >
          <ChevronRight className={cn("size-3.5 transition-transform", detail && "rotate-90")} aria-hidden />
          {detail ? "Less detail" : "More detail"}
        </button>
        <div className="flex flex-1 items-center justify-end px-3">{detail ? <HeatLegend dark={dark} /> : <span className="text-[11.5px] text-ink-3">throughput and each part</span>}</div>
      </div>

      {detail && (
        <>
          <LaneRow
            lane="throughput"
            height={LANE_H.strip}
            label={<StripLabel title="Throughput" max={`${fmtRate(seriesMax(ts.offeredRps, ts.throughputRps))}/s`} hint="Handled (solid) against offered (dashed)" />}
          >
            <ThroughputStrip report={report} plotWidth={plotWidth} durationS={durationS} />
          </LaneRow>
          {report.nodes.map((node) => {
            const peak = node.summary.peakUtilization ?? seriesMax(node.timeseries?.utilization);
            const hasUtil = !!node.timeseries?.utilization;
            return (
              <LaneRow
                key={node.id}
                lane={`node:${node.id}`}
                height={LANE_H.heat}
                label={
                  <span className="flex w-full min-w-0 items-center gap-1.5 text-[12px] text-ink-2">
                    <span className="min-w-0 flex-1 truncate" title={node.label || node.id}>
                      {node.label || node.id}
                    </span>
                    {hasUtil && (
                      <span className="shrink-0 font-mono text-[10px] tabular-nums" style={{ color: peak >= 0.95 ? "var(--danger-ink)" : "var(--ink-3)" }}>
                        {fmtPct(peak, 0)}
                      </span>
                    )}
                  </span>
                }
              >
                <HeatLane node={node} report={report} faults={faults} dark={dark} plotWidth={plotWidth} durationS={durationS} />
              </LaneRow>
            );
          })}
        </>
      )}

      {/* Hover crosshair and readout. */}
      {hover && (
        <>
          {!hover.cluster && (
            <div
              className="pointer-events-none absolute inset-y-0 w-px border-l border-dashed border-ink-3"
              style={{ left: labelWidth + hover.px }}
              aria-hidden
            />
          )}
          {lines.length > 0 && (
            <div
              role="status"
              className="pointer-events-none absolute z-30 max-w-72 rounded-sm border border-line-strong bg-surface px-2 py-1.5 font-mono text-[10.5px] leading-[15px] text-ink shadow-float"
              style={{
                left: Math.min(labelWidth + hover.px + 12, labelWidth + plotWidth - 200),
                top: Math.max(4, hover.y + (hover.cluster ? 4 : -8)),
              }}
            >
              <div className="mb-0.5 text-[9.5px] text-ink-3">t {fmtClock(hover.t)}</div>
              {lines.map((l, k) => (
                <div key={k} className={cn("whitespace-nowrap", k === 0 && hover.lane.startsWith("node:") && "font-semibold")}>
                  {l}
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}
