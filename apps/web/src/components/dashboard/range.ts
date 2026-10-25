/**
 * Time-axis helpers for the dashboard: ranges (whole run, measurement window, drag-zoom), which
 * buckets a view draws (range and replay playhead), legend statistics and axis scales.
 *
 * Bucket i covers [i·bucketS, (i+1)·bucketS) and is drawn at its centre, like the flight recorder.
 */
import { runDuration, valueAt } from "@/lib/recorder";
import type { Report, Series } from "@/lib/types";

export interface TimeRange {
  from: number;
  to: number;
}

const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);

export function fullRange(report: Pick<Report, "timeseries" | "window">): TimeRange {
  return { from: 0, to: runDuration(report) };
}

export function measuredRange(report: Pick<Report, "window">): TimeRange {
  return { from: report.window.warmupS, to: report.window.durationS };
}

export function sameRange(a: TimeRange | null | undefined, b: TimeRange | null | undefined, eps = 1e-6): boolean {
  if (!a || !b) return a == b;
  return Math.abs(a.from - b.from) < eps && Math.abs(a.to - b.to) < eps;
}

/** Keep a range inside the run and at least `minWidth` seconds wide. */
export function clampRange(r: TimeRange, full: TimeRange, minWidth = 0): TimeRange {
  let from = Math.max(full.from, Math.min(r.from, r.to));
  let to = Math.min(full.to, Math.max(r.from, r.to));
  const w = Math.min(Math.max(minWidth, 0), full.to - full.from);
  if (to - from < w) {
    const mid = (from + to) / 2;
    from = Math.max(full.from, mid - w / 2);
    to = Math.min(full.to, from + w);
    from = Math.max(full.from, to - w);
  }
  return { from, to };
}

/** Grafana's zoom out: twice as wide around the same centre. null once it covers the whole run. */
export function zoomOutRange(r: TimeRange, full: TimeRange): TimeRange | null {
  const w = (r.to - r.from) * 2;
  if (w >= full.to - full.from - 1e-9) return null;
  const mid = (r.from + r.to) / 2;
  let from = mid - w / 2;
  let to = mid + w / 2;
  if (from < full.from) {
    to += full.from - from;
    from = full.from;
  }
  if (to > full.to) {
    from -= to - full.to;
    to = full.to;
  }
  return { from: Math.max(full.from, from), to: Math.min(full.to, to) };
}

export const bucketCentre = (i: number, bucketS: number) => i * bucketS + bucketS / 2;

/**
 * Buckets a view shows: those whose centre lies inside the range and, while replaying, at or before
 * the playhead. null when none qualifies.
 */
export function visibleBuckets(n: number, bucketS: number, range: TimeRange, playhead: number | null = null): { i0: number; i1: number } | null {
  if (n <= 0) return null;
  const b = bucketS > 0 ? bucketS : 1;
  const i0 = Math.max(0, Math.ceil((range.from - b / 2) / b - 1e-9));
  let i1 = Math.min(n - 1, Math.floor((range.to - b / 2) / b + 1e-9));
  if (playhead != null) i1 = Math.min(i1, Math.floor((playhead - b / 2) / b + 1e-9));
  return i1 >= i0 ? { i0, i1 } : null;
}

/** Last bucket drawn while replaying (centre at or before the playhead), or the last bucket. */
export function lastDrawable(n: number, bucketS: number, playhead: number | null): number {
  if (playhead == null) return n - 1;
  const b = bucketS > 0 ? bucketS : 1;
  return Math.min(n - 1, Math.floor((playhead - b / 2) / b + 1e-9));
}

export interface LegendStats {
  mean: number | null;
  max: number | null;
  /** Last value in the window (the value at the playhead while replaying). */
  last: number | null;
}

/** Mean, max and last of the finite values in buckets [i0, i1]. */
export function legendStats(series: Series | undefined, i0: number, i1: number): LegendStats {
  if (!series || i1 < i0) return { mean: null, max: null, last: null };
  let sum = 0;
  let count = 0;
  let max = -Infinity;
  let last: number | null = null;
  for (let i = Math.max(0, i0); i <= Math.min(series.length - 1, i1); i++) {
    const v = series[i];
    if (!finite(v)) continue;
    sum += v;
    count++;
    if (v > max) max = v;
    last = v;
  }
  return count ? { mean: sum / count, max, last } : { mean: null, max: null, last: null };
}

/** Stats for a view: buckets in range up to the playhead; "last" is interpolated at the playhead. */
export function viewStats(series: Series | undefined, n: number, bucketS: number, range: TimeRange, playhead: number | null): LegendStats {
  const vis = visibleBuckets(n, bucketS, range, playhead);
  const s = vis ? legendStats(series, vis.i0, vis.i1) : { mean: null, max: null, last: null };
  if (playhead != null && playhead >= range.from && playhead <= range.to) {
    const at = valueAt(series, playhead, bucketS, "linear");
    if (at != null) s.last = at;
  }
  return s;
}

/** Per-bucket sum of several series (null only where every input is null). */
export function sumSeries(list: Series[], n: number): Series {
  return Array.from({ length: n }, (_, i) => {
    let any = false;
    let s = 0;
    for (const series of list) {
      const v = series[i];
      if (finite(v)) {
        s += v;
        any = true;
      }
    }
    return any ? s : null;
  });
}

/** Per-bucket maximum of several series (null only where every input is null). */
export function maxSeries(list: Series[], n: number): Series {
  return Array.from({ length: n }, (_, i) => {
    let m: number | null = null;
    for (const series of list) {
      const v = series[i];
      if (finite(v) && (m == null || v > m)) m = v;
    }
    return m;
  });
}

/** "Nice" axis maximum and ticks for a [0, max] range. */
export function niceScale(max: number, count = 4): { max: number; ticks: number[] } {
  const target = Math.max(1, count);
  const m = finite(max) && max > 0 ? max : 1;
  const raw = m / target;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const r = raw / mag;
  const step = (r <= 1 ? 1 : r <= 2 ? 2 : r <= 2.5 ? 2.5 : r <= 5 ? 5 : 10) * mag;
  const top = Math.ceil(m / step - 1e-9) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v * 1e9) / 1e9);
  return { max: top, ticks };
}

const TIME_STEPS = [0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600];

/** Time ticks inside [from, to], at least `minPx` apart. */
export function timeTicks(range: TimeRange, plotWidth: number, minPx = 64): number[] {
  const span = Math.max(range.to - range.from, 1e-6);
  const pxPerS = plotWidth / span;
  const step = TIME_STEPS.find((s) => s * pxPerS >= minPx) ?? TIME_STEPS[TIME_STEPS.length - 1];
  const ticks: number[] = [];
  // "+ 0" turns -0 (from ceil of a tiny negative) into 0.
  for (let v = Math.ceil(range.from / step - 1e-9) * step; v <= range.to + 1e-9; v += step) ticks.push(Math.round(v * 1000) / 1000 + 0);
  return ticks;
}

/** Axis time label: "45 s", "2:05". */
export function fmtAxisTime(t: number): string {
  if (t < 120) return `${Number.isInteger(t) ? t : t.toFixed(1)} s`;
  const m = Math.floor(t / 60);
  return `${m}:${String(Math.round(t - m * 60)).padStart(2, "0")}`;
}

/** Range label: "10–120 s", "1:30–2:45". */
export function fmtRange(r: TimeRange): string {
  const f = (t: number) => (t < 120 ? `${+t.toFixed(1)}` : fmtAxisTime(t));
  return `${f(r.from)}–${f(r.to)}${r.to < 120 ? " s" : ""}`;
}
