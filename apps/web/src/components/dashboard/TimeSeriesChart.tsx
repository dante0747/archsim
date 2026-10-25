"use client";

/**
 * Time-series panel body: a light SVG renderer for a dense dashboard (no chart library, so 30+
 * panels stay smooth under a synced crosshair). Lines or stacked bars on one value axis, nulls as
 * gaps, warm-up and drain shading, fault windows and detected events as annotations, threshold
 * lines and bands, the replay playhead with data drawn up to it, drag to zoom the whole dashboard,
 * and a Grafana table legend: click a series to isolate it, Ctrl/⌘-click to toggle it.
 */
import { useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import { jumpTo } from "@/components/timeline/playback";
import { type MetricUnit, formatCompact, formatTick, formatValue } from "@/lib/metrics-catalog";
import { type RecorderEvent, bucketIndex, fmtClock, phaseAt, valueAt } from "@/lib/recorder";
import type { Series } from "@/lib/types";
import { cn } from "@/lib/utils";
import { type DashView, type FaultAnnotation, useDashView } from "./context";
import type { ChartSeries, TimeSeriesSpec } from "./panels";
import { bucketCentre, clampRange, fmtAxisTime, lastDrawable, niceScale, timeTicks, viewStats, visibleBuckets } from "./range";
import { useCursor } from "./store";

export const STATUS_COLOR = {
  good: "var(--status-good)",
  warning: "var(--status-warning)",
  serious: "var(--status-serious)",
  critical: "var(--status-critical)",
} as const;

export const SEVERITY_COLOR: Record<RecorderEvent["severity"], string> = {
  critical: "var(--status-critical)",
  warning: "var(--status-warning)",
  info: "var(--accent)",
  ok: "var(--status-good)",
};

const PAD_TOP = 8;
const PAD_RIGHT = 10;
const PAD_BOTTOM = 18;
const DRAG_MIN_PX = 6;

const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);

/** Size of an element, kept current with a ResizeObserver. */
export function useSize(ref: React.RefObject<HTMLElement | null>): { w: number; h: number } {
  const [size, setSize] = useState({ w: 0, h: 0 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => setSize((s) => (s.w === el.clientWidth && s.h === el.clientHeight ? s : { w: el.clientWidth, h: el.clientHeight }));
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return size;
}

interface Geom {
  w: number;
  h: number;
  left: number;
  top: number;
  pw: number;
  ph: number;
  from: number;
  to: number;
  yMax: number;
  ticks: number[];
  xs: (t: number) => number;
  ys: (v: number) => number;
}

function makeGeom(w: number, h: number, from: number, to: number, yMax: number, ticks: number[], left: number): Geom {
  const top = PAD_TOP;
  const pw = Math.max(10, w - left - PAD_RIGHT);
  const ph = Math.max(10, h - top - PAD_BOTTOM);
  const span = Math.max(to - from, 1e-9);
  return {
    w,
    h,
    left,
    top,
    pw,
    ph,
    from,
    to,
    yMax,
    ticks,
    xs: (t) => left + ((t - from) / span) * pw,
    ys: (v) => top + ph - (Math.max(0, Math.min(v, yMax * 1.5)) / yMax) * ph,
  };
}

/** Line segments (split at gaps) of buckets j0..j1, plus an interpolated tip at the playhead. */
function segments(data: Series, j0: number, j1: number, b: number, g: Geom, tip: number | null): [number, number][][] {
  const out: [number, number][][] = [];
  let cur: [number, number][] = [];
  for (let i = j0; i <= j1; i++) {
    const v = data[i];
    if (!finite(v)) {
      if (cur.length) out.push(cur);
      cur = [];
      continue;
    }
    cur.push([g.xs(bucketCentre(i, b)), g.ys(v)]);
  }
  if (tip != null && cur.length) {
    const v = valueAt(data, tip, b, "linear");
    if (v != null) cur.push([g.xs(tip), g.ys(v)]);
  }
  if (cur.length) out.push(cur);
  return out;
}

const toLine = (segs: [number, number][][]) =>
  segs.map((s) => s.map(([x, y], k) => `${k ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join("")).join("");

const toArea = (segs: [number, number][][], base: number) =>
  segs
    .filter((s) => s.length > 1)
    .map((s) => `${toLine([s])}L${s[s.length - 1][0].toFixed(1)},${base}L${s[0][0].toFixed(1)},${base}Z`)
    .join("");

function scoped(spec: TimeSeriesSpec, view: DashView): { markers: RecorderEvent[]; tipEvents: RecorderEvent[]; faults: FaultAnnotation[] } {
  const isFault = (e: RecorderEvent) => e.kind === "fault_start" || e.kind === "fault_end";
  // System panels mark only what needs attention (scaling and fault ends stay in the tooltip).
  if (spec.scope === "system") return { markers: view.events.filter((e) => !isFault(e) && e.severity !== "info"), tipEvents: view.events, faults: view.faults };
  const id = spec.scope.nodeId;
  const mine = view.events.filter((e) => e.nodeId === id);
  return { markers: mine.filter((e) => !isFault(e)), tipEvents: mine, faults: view.faults.filter((f) => f.fault.target === id) };
}

// ---------------------------------------------------------------------------------------------
// Chart
// ---------------------------------------------------------------------------------------------

export function TimeSeriesChart({ spec, expanded = false }: { spec: TimeSeriesSpec; expanded?: boolean }) {
  const view = useDashView();
  const { range, bucketS: b, n, playhead, full } = view;
  const plotRef = useRef<HTMLDivElement>(null);
  const { w, h } = useSize(plotRef);
  const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set());
  const [hot, setHot] = useState<string | null>(null);
  const [sel, setSel] = useState<{ a: number; b: number } | null>(null);
  const dragRef = useRef<number | null>(null);
  const rafRef = useRef(0);
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, "");

  const shown = useMemo(() => spec.series.filter((s) => !hidden.has(s.id)), [spec.series, hidden]);
  const last = lastDrawable(n, b, playhead);
  const vis = visibleBuckets(n, b, range, playhead);
  const i0 = vis?.i0 ?? 0;
  const i1 = vis?.i1 ?? -1;

  // Value axis: fixed for ratios, otherwise fit to what is visible (and nearby thresholds).
  const dataMax = useMemo(() => {
    let m = 0;
    if (spec.style === "bars") {
      for (let i = i0; i <= i1; i++) {
        let s = 0;
        for (const ser of shown) {
          const v = ser.data[i];
          if (finite(v) && v > 0) s += v;
        }
        m = Math.max(m, s);
      }
    } else {
      for (const ser of shown) for (let i = i0; i <= i1; i++) {
        const v = ser.data[i];
        if (finite(v) && v > m) m = v;
      }
    }
    return m;
  }, [spec.style, shown, i0, i1]);

  const tickCount = Math.max(2, Math.min(6, Math.floor((h - PAD_TOP - PAD_BOTTOM) / 34)));
  const scale = useMemo(() => {
    if (spec.yMax != null) return niceScale(Math.max(spec.yMax, dataMax), tickCount);
    // Thresholds join the axis only when they are near the data; far ones get a note instead.
    const near = spec.thresholds.filter((t) => t.value <= Math.max(dataMax, 1e-9) * 2.5).map((t) => t.value);
    return niceScale(Math.max(dataMax, ...near) * 1.06 || 1, tickCount);
  }, [spec.yMax, spec.thresholds, dataMax, tickCount]);
  const yMax = scale.max;
  const left = Math.max(30, Math.max(...scale.ticks.map((v) => formatTick(v, spec.unit).length)) * 6 + 12);
  const g = useMemo(() => makeGeom(w, h, range.from, range.to, scale.max, scale.ticks, left), [w, h, range.from, range.to, scale, left]);

  // Paths change per bucket, not per frame: the playhead tip is drawn separately below.
  const j0 = Math.max(0, i0 - 1);
  const j1 = Math.min(last, i1 + 1);
  const paths = useMemo(() => {
    if (spec.style === "bars" || g.w === 0) return [];
    return shown.map((s) => {
      const segs = segments(s.data, j0, j1, b, g, null);
      return { id: s.id, line: toLine(segs), area: shown.length === 1 && !s.dashed ? toArea(segs, g.top + g.ph) : "" };
    });
  }, [spec.style, shown, j0, j1, b, g]);

  const tipT = playhead != null && last >= 0 && last < n - 1 && playhead > bucketCentre(last, b) ? playhead : null;
  const tips =
    tipT != null && spec.style === "line"
      ? shown.map((s) => {
          const lastV = s.data[last];
          const v = valueAt(s.data, tipT, b, "linear");
          return finite(lastV) && v != null ? { id: s.id, color: s.color, x0: g.xs(bucketCentre(last, b)), y0: g.ys(lastV), x1: g.xs(tipT), y1: g.ys(v), dashed: s.dashed } : null;
        })
      : [];

  const barEnd = Math.min(i1, last);
  const bars = useMemo(() => {
    if (spec.style !== "bars" || g.w === 0) return [];
    const colW = g.xs(b) - g.xs(0);
    const gap = colW > 6 ? 2 : colW > 3 ? 1 : 0;
    const out: { key: string; sid: string; x: number; y: number; w: number; h: number; color: string }[] = [];
    for (let i = i0; i <= barEnd; i++) {
      let acc = 0;
      const x = g.xs(i * b) + gap / 2;
      for (const s of shown) {
        const v = s.data[i];
        if (!finite(v) || v <= 0) continue;
        const y0 = g.ys(acc);
        const y1 = g.ys(acc + v);
        const hh = y0 - y1;
        out.push({ key: `${s.id}-${i}`, sid: s.id, x, y: y1, w: Math.max(1, colW - gap), h: Math.max(1, hh > 3 ? hh - 1 : hh), color: s.color });
        acc += v;
      }
    }
    return out;
  }, [spec.style, shown, i0, barEnd, b, g]);

  const { markers, tipEvents, faults } = scoped(spec, view);
  const anyData = spec.series.some((s) => s.data.some(finite));
  const xTicks = g.w ? timeTicks(range, g.pw, 72) : [];
  const warmupS = view.report.window.warmupS;
  const durationS = view.report.window.durationS;
  const hiddenThresholds = spec.thresholds.filter((t) => t.value > yMax);

  // Pointer: crosshair for every panel, drag to zoom, click to scrub while replaying.
  const toLocal = (e: React.PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    return { x: Math.max(0, Math.min(g.pw, e.clientX - r.left)), y: e.clientY - r.top };
  };
  const tOf = (x: number) => range.from + (x / g.pw) * (range.to - range.from);
  const onMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const { x, y } = toLocal(e);
    if (dragRef.current != null) setSel({ a: dragRef.current, b: x });
    cancelAnimationFrame(rafRef.current);
    const t = tOf(x);
    rafRef.current = requestAnimationFrame(() => useCursor.getState().setCursor(t, spec.id, y + g.top));
  };
  const onLeave = () => {
    cancelAnimationFrame(rafRef.current);
    if (useCursor.getState().source === spec.id) useCursor.getState().setCursor(null, null);
  };
  const onDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    dragRef.current = toLocal(e).x;
  };
  const onUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const x0 = dragRef.current;
    dragRef.current = null;
    setSel(null);
    if (x0 == null) return;
    const x1 = toLocal(e).x;
    if (Math.abs(x1 - x0) >= DRAG_MIN_PX) {
      view.setRange(clampRange({ from: tOf(Math.min(x0, x1)), to: tOf(Math.max(x0, x1)) }, full, b));
    } else if (playhead != null) {
      jumpTo(tOf(x1), full.to);
    }
  };
  const onCancel = () => {
    dragRef.current = null;
    setSel(null);
  };

  const onLegendClick = (id: string, e: React.MouseEvent) => {
    if (e.ctrlKey || e.metaKey) {
      setHidden((cur) => {
        const next = new Set(cur);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        return next.size >= spec.series.length ? new Set() : next;
      });
      return;
    }
    const isolated = hidden.size === spec.series.length - 1 && !hidden.has(id);
    setHidden(isolated ? new Set() : new Set(spec.series.filter((s) => s.id !== id).map((s) => s.id)));
  };

  if (!anyData && spec.empty) {
    return <div className="flex h-full min-h-[120px] items-center justify-center text-xs text-ink-3">{spec.empty}</div>;
  }

  const plotH = expanded ? "min-h-[46vh]" : "min-h-[150px]";
  const hatchOut = `hatch-o-${uid}`;
  const hatchSlow = `hatch-s-${uid}`;
  const clip = `clip-${uid}`;
  const grad = `grad-${uid}`;
  const xEnd = g.left + g.pw;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div ref={plotRef} className={cn("relative min-h-0 flex-1 select-none", plotH)}>
        {g.w > 0 && (
          <svg width={g.w} height={g.h} className="absolute inset-0 overflow-visible" aria-hidden>
            <defs>
              <clipPath id={clip}>
                <rect x={g.left} y={g.top - 2} width={g.pw} height={g.ph + 4} />
              </clipPath>
              <pattern id={hatchOut} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                <line x1={0} y1={0} x2={0} y2={6} stroke="var(--status-critical)" strokeOpacity={0.22} strokeWidth={2} />
              </pattern>
              <pattern id={hatchSlow} width={6} height={6} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                <line x1={0} y1={0} x2={0} y2={6} stroke="var(--status-serious)" strokeOpacity={0.22} strokeWidth={2} />
              </pattern>
              {shown.length === 1 && (
                <linearGradient id={grad} x1={0} x2={0} y1={0} y2={1}>
                  <stop offset="0%" stopColor={shown[0].color} stopOpacity={0.26} />
                  <stop offset="100%" stopColor={shown[0].color} stopOpacity={0.02} />
                </linearGradient>
              )}
            </defs>

            {/* Phases: warm-up and drain are shaded, like the flight recorder. */}
            <g clipPath={`url(#${clip})`}>
              {warmupS > range.from && (
                <rect x={g.xs(Math.max(range.from, 0))} y={g.top} width={Math.max(0, g.xs(Math.min(warmupS, range.to)) - g.xs(Math.max(range.from, 0)))} height={g.ph} fill="var(--warmup-wash)" />
              )}
              {durationS < range.to && (
                <rect x={g.xs(Math.max(durationS, range.from))} y={g.top} width={Math.max(0, g.xs(range.to) - g.xs(Math.max(durationS, range.from)))} height={g.ph} fill="var(--warmup-wash)" />
              )}
              {spec.bands.map((band) => {
                const y1 = g.ys(Math.min(band.to, yMax));
                const y0 = g.ys(Math.max(band.from, 0));
                return y0 - y1 > 0.5 ? <rect key={`${band.from}-${band.status}`} x={g.left} y={y1} width={g.pw} height={y0 - y1} fill={STATUS_COLOR[band.status]} fillOpacity={0.07} /> : null;
              })}
            </g>

            {/* Grid and axes. */}
            {g.ticks.map((v) => (
              <g key={`y${v}`}>
                <line x1={g.left} x2={xEnd} y1={Math.round(g.ys(v)) + 0.5} y2={Math.round(g.ys(v)) + 0.5} stroke={v === 0 ? "var(--axis)" : "var(--grid)"} />
                <text x={g.left - 6} y={g.ys(v) + 3.5} textAnchor="end" fontSize={10} fill="var(--ink-3)" className="tabular">
                  {formatTick(v, spec.unit)}
                </text>
              </g>
            ))}
            {xTicks.map((t) => (
              <g key={`x${t}`}>
                <line x1={Math.round(g.xs(t)) + 0.5} x2={Math.round(g.xs(t)) + 0.5} y1={g.top} y2={g.top + g.ph} stroke="var(--grid)" />
                <text x={g.xs(t)} y={g.h - 4} textAnchor={t <= range.from + 1e-6 ? "start" : "middle"} fontSize={10} fill="var(--ink-3)" className="tabular">
                  {fmtAxisTime(t)}
                </text>
              </g>
            ))}
            {warmupS > range.from && g.xs(Math.min(warmupS, range.to)) - g.xs(Math.max(range.from, 0)) > 54 && (
              <text x={g.xs(Math.max(range.from, 0)) + 5} y={g.top + 11} fontSize={10} fill="var(--ink-3)" stroke="var(--surface)" strokeWidth={3} paintOrder="stroke">
                Warm-up
              </text>
            )}

            {/* Fault windows. */}
            <g clipPath={`url(#${clip})`}>
              {faults.map(({ fault }) => {
                const x0 = g.xs(fault.startS);
                const x1 = g.xs(fault.startS + fault.durationS);
                const outage = fault.kind === "outage";
                return (
                  <g key={fault.id}>
                    <rect x={x0} y={g.top} width={Math.max(2, x1 - x0)} height={g.ph} fill={`url(#${outage ? hatchOut : hatchSlow})`} />
                    <rect x={x0} y={g.top} width={Math.max(2, x1 - x0)} height={2} fill={outage ? "var(--status-critical)" : "var(--status-serious)"} />
                  </g>
                );
              })}
            </g>

            {/* Threshold lines. */}
            {spec.thresholds
              .filter((t) => t.value <= yMax)
              .map((t) => (
                <g key={`t${t.value}`}>
                  <line x1={g.left} x2={xEnd} y1={g.ys(t.value)} y2={g.ys(t.value)} stroke={STATUS_COLOR[t.status]} strokeWidth={1.25} strokeDasharray="6 4" />
                  <text x={xEnd - 4} y={g.ys(t.value) - 4} textAnchor="end" fontSize={10} fill="var(--ink-2)" stroke="var(--surface)" strokeWidth={3} paintOrder="stroke">
                    {t.label} {formatValue(t.value, spec.unit)}
                  </text>
                </g>
              ))}

            {/* Data. */}
            <g clipPath={`url(#${clip})`}>
              {paths.map((p) => {
                const s = shown.find((x) => x.id === p.id)!;
                const dim = hot != null && hot !== p.id;
                return (
                  <g key={p.id} opacity={dim ? 0.2 : 1}>
                    {p.area && <path d={p.area} fill={`url(#${grad})`} />}
                    <path
                      d={p.line}
                      fill="none"
                      stroke={s.color}
                      strokeWidth={shown.length === 1 ? 2 : 1.5}
                      strokeLinejoin="round"
                      strokeLinecap="round"
                      strokeDasharray={s.dashed ? "4 3" : undefined}
                    />
                  </g>
                );
              })}
              {tips.map((tp) =>
                tp ? (
                  <g key={`tip-${tp.id}`} opacity={hot != null && hot !== tp.id ? 0.2 : 1}>
                    <path d={`M${tp.x0},${tp.y0}L${tp.x1},${tp.y1}`} stroke={tp.color} strokeWidth={shown.length === 1 ? 2 : 1.5} strokeLinecap="round" strokeDasharray={tp.dashed ? "4 3" : undefined} />
                    <circle cx={tp.x1} cy={tp.y1} r={2.5} fill={tp.color} />
                  </g>
                ) : null,
              )}
              {bars.map((r) => (
                <rect key={r.key} x={r.x} y={r.y} width={r.w} height={r.h} fill={r.color} opacity={hot != null && r.sid !== hot ? 0.2 : 1} />
              ))}
            </g>

            {/* Detected events: a dashed marker with a notch on the time axis. */}
            {markers
              .filter((e) => e.t >= range.from && e.t <= range.to && (playhead == null || e.t <= playhead))
              .map((e, k) => {
                const x = g.xs(e.t);
                const yb = g.top + g.ph;
                return (
                  <g key={`${e.kind}-${e.t}-${k}`}>
                    <line x1={x} x2={x} y1={g.top} y2={yb} stroke={SEVERITY_COLOR[e.severity]} strokeOpacity={0.4} strokeDasharray="2 3" />
                    <path d={`M${x - 4},${yb}L${x + 4},${yb}L${x},${yb - 6}Z`} fill={SEVERITY_COLOR[e.severity]} />
                  </g>
                );
              })}

            {/* Replay playhead. */}
            {playhead != null && playhead >= range.from && playhead <= range.to && (
              <line x1={g.xs(playhead)} x2={g.xs(playhead)} y1={g.top} y2={g.top + g.ph} stroke="var(--accent)" strokeWidth={1.5} />
            )}

            {hiddenThresholds.length > 0 && (
              <text x={xEnd - 4} y={g.top + 10} textAnchor="end" fontSize={10} fill="var(--ink-3)">
                ↑ {hiddenThresholds.map((t) => `${t.label} ${formatValue(t.value, spec.unit)}`).join(" · ")}
              </text>
            )}

            {sel && (
              <rect
                x={g.left + Math.min(sel.a, sel.b)}
                y={g.top}
                width={Math.abs(sel.b - sel.a)}
                height={g.ph}
                fill="var(--select)"
                fillOpacity={0.12}
                stroke="var(--select)"
                strokeOpacity={0.6}
              />
            )}
          </svg>
        )}

        {g.w > 0 && !anyData && (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-xs text-ink-3">No data</div>
        )}

        {g.w > 0 && (
          <CursorLayer spec={spec} shown={shown} g={g} last={last} tipEvents={tipEvents} faults={faults} />
        )}

        {g.w > 0 && (
          <div
            className="absolute cursor-crosshair touch-none"
            style={{ left: g.left, top: g.top, width: g.pw, height: g.ph }}
            onPointerMove={onMove}
            onPointerLeave={onLeave}
            onPointerDown={onDown}
            onPointerUp={onUp}
            onPointerCancel={onCancel}
            onDoubleClick={() => view.zoomOut()}
            aria-label={`${spec.title}: drag to zoom, double-click to zoom out`}
            role="img"
          />
        )}
      </div>
      <Legend spec={spec} hidden={hidden} onClick={onLegendClick} onHover={setHot} expanded={expanded} />
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Crosshair and tooltip
// ---------------------------------------------------------------------------------------------

function CursorLayer({
  spec,
  shown,
  g,
  last,
  tipEvents,
  faults,
}: {
  spec: TimeSeriesSpec;
  shown: ChartSeries[];
  g: Geom;
  last: number;
  tipEvents: RecorderEvent[];
  faults: FaultAnnotation[];
}) {
  const view = useDashView();
  const { bucketS: b, n, range } = view;
  const idx = useCursor((s) => (s.t == null ? null : bucketIndex(s.t, b, n)));
  const isSource = useCursor((s) => s.source === spec.id);
  const y = useCursor((s) => (s.source === spec.id ? s.y : 0));
  if (idx == null) return null;
  const tc = bucketCentre(idx, b);
  if (tc < range.from - b / 2 || tc > range.to + b / 2) return null;
  const x = g.xs(tc);
  const live = idx <= last;
  const values = shown.map((s) => ({ s, v: live ? (s.data[idx] ?? null) : null }));
  const rows = values.length >= 4 ? [...values].sort((a, c) => (c.v ?? -Infinity) - (a.v ?? -Infinity)) : values;
  const t0 = idx * b;
  const events = tipEvents.filter((e) => e.t >= t0 && e.t < t0 + b);
  const active = faults.filter(({ fault }) => tc >= fault.startS && tc < fault.startS + fault.durationS);
  const flip = x > g.left + g.pw * 0.6;
  const total = spec.style === "bars" ? values.reduce((a, r) => a + (finite(r.v) ? r.v : 0), 0) : null;

  return (
    <>
      <svg width={g.w} height={g.h} className="pointer-events-none absolute inset-0" aria-hidden>
        {spec.style === "bars" ? (
          <rect x={g.xs(t0)} y={g.top} width={Math.max(1, g.xs(t0 + b) - g.xs(t0))} height={g.ph} fill="var(--ink)" fillOpacity={0.07} />
        ) : (
          <line x1={x} x2={x} y1={g.top} y2={g.top + g.ph} stroke="var(--ink-3)" strokeDasharray="3 3" />
        )}
        {spec.style === "line" &&
          values.map(({ s, v }) => (finite(v) ? <circle key={s.id} cx={x} cy={g.ys(v)} r={3.5} fill={s.color} stroke="var(--surface)" strokeWidth={2} /> : null))}
      </svg>
      {isSource && (
        <div
          role="status"
          className="pointer-events-none absolute z-30 w-max min-w-44 max-w-72 rounded-md border border-line-strong bg-surface px-2.5 py-2 text-xs shadow-float"
          style={{
            left: flip ? x - 12 : x + 12,
            top: Math.max(0, Math.min(y - 16, g.h - 60)),
            transform: flip ? "translateX(-100%)" : undefined,
          }}
        >
          <div className="mb-1 flex items-center justify-between gap-4 text-[11px] text-ink-3">
            <span className="tabular">{fmtClock(t0)} – {fmtClock(t0 + b)}</span>
            <span>{phaseAt(tc, view.report.window)}</span>
          </div>
          {rows.map(({ s, v }) => (
            <div key={s.id} className="flex items-center gap-2 py-px">
              <span className="h-0.5 w-3 shrink-0 rounded-full" style={{ background: s.color }} aria-hidden />
              <span className="min-w-0 flex-1 truncate text-ink-2">{s.label}</span>
              <span className="tabular pl-3 font-semibold text-ink">{formatValue(v, spec.unit)}</span>
            </div>
          ))}
          {total != null && (
            <div className="mt-1 flex items-center justify-between border-t border-line pt-1 text-ink-2">
              <span>Total</span>
              <span className="tabular font-semibold text-ink">{formatValue(total, spec.unit)}</span>
            </div>
          )}
          {(events.length > 0 || active.length > 0) && (
            <div className="mt-1.5 space-y-0.5 border-t border-line pt-1.5">
              {active.map(({ fault, label }) => (
                <p key={fault.id} className="flex items-center gap-1.5 text-ink">
                  <span className="size-2 shrink-0 rounded-[2px]" style={{ background: fault.kind === "outage" ? "var(--status-critical)" : "var(--status-serious)" }} aria-hidden />
                  <span className="truncate">{label}</span>
                </p>
              ))}
              {events
                .filter((e) => e.kind !== "fault_start" || !active.some((a) => a.label === e.label))
                .map((e, k) => (
                  <p key={k} className="flex items-center gap-1.5 text-ink">
                    <span className="size-2 shrink-0 rotate-45 rounded-[1px]" style={{ background: SEVERITY_COLOR[e.severity] }} aria-hidden />
                    <span className="truncate">{e.label}</span>
                  </p>
                ))}
            </div>
          )}
        </div>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------------------------
// Legend
// ---------------------------------------------------------------------------------------------

function Legend({
  spec,
  hidden,
  onClick,
  onHover,
  expanded,
}: {
  spec: TimeSeriesSpec;
  hidden: ReadonlySet<string>;
  onClick: (id: string, e: React.MouseEvent) => void;
  onHover: (id: string | null) => void;
  expanded: boolean;
}) {
  const view = useDashView();
  const { bucketS: b, n, range, playhead } = view;
  const idx = useCursor((s) => (s.t == null ? null : bucketIndex(s.t, b, n)));
  const stats = useMemo(
    () => spec.series.map((s) => viewStats(s.data, n, b, { from: range.from, to: range.to }, playhead)),
    [spec.series, n, b, range.from, range.to, playhead],
  );
  const last = lastDrawable(n, b, playhead);
  const atCursor = idx != null && idx <= last && bucketCentre(idx, b) >= range.from - b / 2 && bucketCentre(idx, b) <= range.to + b / 2;
  const lastHeader = atCursor ? `at ${fmtAxisTime(bucketCentre(idx, b) - b / 2)}` : playhead != null ? "Now" : "Last";
  const cell = (v: number | null) => formatCompact(v, spec.unit as MetricUnit);

  if (spec.series.length === 1) {
    const s = spec.series[0];
    const st = stats[0];
    const lastV = atCursor ? (s.data[idx] ?? null) : st.last;
    return (
      <div className="flex shrink-0 items-center justify-end gap-3 px-2 pb-1 pt-0.5 text-[11px] text-ink-3">
        <span>
          Mean <span className="tabular text-ink-2">{cell(st.mean)}</span>
        </span>
        <span>
          Max <span className="tabular text-ink-2">{cell(st.max)}</span>
        </span>
        <span>
          {lastHeader} <span className="tabular font-medium text-ink">{cell(lastV)}</span>
        </span>
      </div>
    );
  }

  return (
    <div className={cn("shrink-0 overflow-y-auto px-1 pb-1", !expanded && "max-h-[112px]")}>
      <table className="w-full table-fixed text-[11px]">
        <thead className="sticky top-0 bg-surface">
          <tr className="text-ink-3">
            <th className="py-0.5 pl-1 text-left font-normal">
              <span className="sr-only">Series</span>
            </th>
            <th className="w-[19%] py-0.5 text-right font-normal">Mean</th>
            <th className="w-[19%] py-0.5 text-right font-normal">Max</th>
            <th className="w-[21%] py-0.5 pr-1 text-right font-normal">{lastHeader}</th>
          </tr>
        </thead>
        <tbody>
          {spec.series.map((s, k) => {
            const off = hidden.has(s.id);
            const st = stats[k];
            const lastV = atCursor ? (s.data[idx] ?? null) : st.last;
            return (
              <tr
                key={s.id}
                onClick={(e) => onClick(s.id, e)}
                onMouseEnter={() => !off && onHover(s.id)}
                onMouseLeave={() => onHover(null)}
                className={cn("cursor-pointer rounded-sm hover:bg-surface-2", off && "opacity-40")}
                title="Click to show only this series, Ctrl/⌘-click to hide or show it"
              >
                <td className="truncate py-[1px] pl-1">
                  <span className="mr-1.5 inline-block h-[3px] w-3 rounded-full align-middle" style={{ background: off ? "var(--ink-3)" : s.color }} aria-hidden />
                  <span className="text-ink-2">{s.label}</span>
                </td>
                <td className="tabular py-[1px] text-right text-ink-2">{cell(st.mean)}</td>
                <td className="tabular py-[1px] text-right text-ink-2">{cell(st.max)}</td>
                <td className="tabular py-[1px] pr-1 text-right font-medium text-ink">{cell(lastV)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
