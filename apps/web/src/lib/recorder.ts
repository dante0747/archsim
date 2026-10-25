/**
 * Flight recorder: pure helpers to replay a finished run second by second.
 *
 * Report time series are bucketed: bucket i covers [i·bucketS, (i+1)·bucketS). Everything here is
 * deterministic and side-effect free so the canvas, the timeline dock and tests agree.
 */
import { sloTimeline } from "./slo";
import type { Fault, NodeStatus, Objectives, Report, Series } from "./types";

// ---------------------------------------------------------------------------------------------
// Time & sampling
// ---------------------------------------------------------------------------------------------

/** Bucket containing simulated second `t` (clamped to [0, n-1] when `n` is given). */
export function bucketIndex(t: number, bucketS: number, n?: number): number {
  const b = bucketS > 0 ? bucketS : 1;
  let i = Math.floor(t / b + 1e-9);
  if (i < 0) i = 0;
  if (n != null && n > 0 && i > n - 1) i = n - 1;
  return i;
}

/**
 * Value of a bucketed series at second `t`.
 * - "step": the value of the bucket containing `t`.
 * - "linear": interpolated between bucket centres (smooth playback).
 * Missing data (out of range, null) returns null.
 */
export function valueAt(
  series: Series | undefined | null,
  t: number,
  bucketS: number,
  mode: "step" | "linear" = "step",
): number | null {
  if (!series || series.length === 0 || !Number.isFinite(t) || t < 0) return null;
  const b = bucketS > 0 ? bucketS : 1;
  if (t >= series.length * b) return null;
  if (mode === "step") return series[bucketIndex(t, b, series.length)] ?? null;
  const pos = t / b - 0.5;
  const i0 = Math.max(0, Math.min(series.length - 1, Math.floor(pos)));
  const i1 = Math.min(series.length - 1, i0 + 1);
  const a = series[i0];
  const c = series[i1];
  if (a == null) return c ?? null;
  if (c == null) return a;
  const f = Math.max(0, Math.min(1, pos - i0));
  return a + (c - a) * f;
}

/** Simulated length of the recording (arrival window plus drain), in seconds. */
export function runDuration(report: Pick<Report, "timeseries" | "window">): number {
  const ts = report.timeseries;
  const fromSeries = ts.t.length * (ts.bucketS || 1);
  return Math.max(fromSeries, report.window?.simulatedS ?? 0, report.window?.durationS ?? 0);
}

/** Length of the timeline axis: the configured workload or the recording, whichever is longer. */
export function axisDuration(workloadDurationS: number, report: Pick<Report, "timeseries" | "window"> | null): number {
  const d = Math.max(1, workloadDurationS || 0);
  return report ? Math.max(d, runDuration(report)) : d;
}

export type Phase = "warm-up" | "measured" | "drain";

export function phaseAt(t: number, window: { warmupS: number; durationS: number }): Phase {
  if (t < window.warmupS) return "warm-up";
  if (t < window.durationS) return "measured";
  return "drain";
}

/** Faults active at second `t`. */
export function faultsAt(faults: Fault[] | undefined | null, t: number): Fault[] {
  if (!faults?.length) return [];
  return faults.filter((f) => t >= f.startS && t < f.startS + f.durationS);
}

/** Faults recorded with the run (the run's own input, not the current draft). */
export function runFaults(report: Pick<Report, "input"> | null | undefined): Fault[] {
  return report?.input?.workload?.faults ?? [];
}

/** Status of a component in one bucket, using the same thresholds as the whole-run status. */
export function statusAt(
  util: number | null,
  arrivalRps: number | null,
  rejectedRps: number | null,
): NodeStatus {
  const arr = arrivalRps ?? 0;
  if ((rejectedRps ?? 0) > 0 && arr > 0 && (rejectedRps ?? 0) / arr >= 0.01) return "saturated";
  if (util != null) {
    if (util >= 0.95) return "saturated";
    if (util >= 0.85) return "hot";
    if (util >= 0.7) return "warm";
  }
  if (arr <= 0 && (util ?? 0) <= 0) return "idle";
  return "ok";
}

/** Calls per second on a link at `t`: the link's own series, else the callee's arrivals. */
export function edgeRateAt(report: Report, edgeId: string, t: number): number | null {
  const e = report.edges.find((x) => x.id === edgeId);
  if (!e) return null;
  const b = report.timeseries.bucketS;
  const own = e.timeseries?.callRps;
  if (own) return valueAt(own, t, b);
  const target = report.nodes.find((n) => n.id === e.target);
  return valueAt(target?.timeseries.arrivalRps, t, b);
}

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

export type RecorderEventKind =
  | "fault_start"
  | "fault_end"
  | "saturation"
  | "slo_breach"
  | "slo_recovered"
  | "scale_up"
  | "scale_down"
  | "queue_growth"
  | "timeouts"
  | "node_added"
  | "node_removed"
  | "pods_pending";

export type EventSeverity = "critical" | "warning" | "info" | "ok";

export interface RecorderEvent {
  t: number;
  kind: RecorderEventKind;
  label: string;
  nodeId?: string | null;
  severity: EventSeverity;
}

const SEVERITY_RANK: Record<EventSeverity, number> = { critical: 0, warning: 1, info: 2, ok: 3 };

function faultTargetLabel(report: Report, f: Fault): string {
  const node = report.nodes.find((n) => n.id === f.target);
  if (node) return node.label || node.id;
  const edge = report.edges.find((e) => e.id === f.target);
  if (edge) {
    const a = report.nodes.find((n) => n.id === edge.source);
    const b = report.nodes.find((n) => n.id === edge.target);
    return `${a?.label ?? edge.source} → ${b?.label ?? edge.target}`;
  }
  return f.target;
}

export function faultLabel(report: Report, f: Fault): string {
  if (f.label) return f.label;
  const target = faultTargetLabel(report, f);
  if (f.kind === "outage") return `Outage: ${target}`;
  if (f.kind === "slowdown") return `${target} ${fmtFactor(f.factor ?? 4)} slower`;
  return `+${Math.round(f.extraMs ?? 200)} ms on ${target}`;
}

function fmtFactor(v: number): string {
  return `×${Number.isInteger(v) ? v : v.toFixed(1)}`;
}

/**
 * Notable moments of a run, sorted by time then severity: fault windows, components entering
 * saturation, SLO breaches and recoveries, autoscaling changes, queues starting to build up and
 * timeouts appearing. Transitions use hysteresis so a value hovering at a threshold is reported once.
 */
export function detectEvents(report: Report, objectives: Objectives): RecorderEvent[] {
  const events: RecorderEvent[] = [];
  const b = report.timeseries.bucketS || 1;
  const n = report.timeseries.t.length;

  for (const f of runFaults(report)) {
    const label = faultLabel(report, f);
    const nodeId = report.nodes.some((x) => x.id === f.target) ? f.target : null;
    events.push({ t: f.startS, kind: "fault_start", label, nodeId, severity: "critical" });
    const end = f.startS + f.durationS;
    if (end < n * b) events.push({ t: end, kind: "fault_end", label: `${label} ends`, nodeId, severity: "info" });
  }

  for (const node of report.nodes) {
    const ts = node.timeseries ?? {};
    const name = node.label || node.id;

    const util = ts.utilization;
    if (util) {
      let armed = true;
      let count = 0;
      for (let i = 0; i < util.length && count < 3; i++) {
        const u = util[i];
        if (u == null) continue;
        if (armed && u >= 0.95) {
          events.push({ t: i * b, kind: "saturation", label: `${name} saturated (${Math.round(u * 100)}% busy)`, nodeId: node.id, severity: "critical" });
          armed = false;
          count++;
        } else if (!armed && u < 0.85) {
          armed = true;
        }
      }
    }

    const queue = ts.queueMean;
    if (queue) {
      const slots = node.summary.slots ?? 0;
      const threshold = Math.max(5, slots * 0.5);
      let armed = true;
      let count = 0;
      for (let i = 0; i < queue.length && count < 2; i++) {
        const q = queue[i];
        if (q == null) continue;
        if (armed && q >= threshold) {
          events.push({ t: i * b, kind: "queue_growth", label: `Queue builds at ${name} (${Math.round(q)} waiting)`, nodeId: node.id, severity: "warning" });
          armed = false;
          count++;
        } else if (!armed && q < threshold / 2) {
          armed = true;
        }
      }
    }

    const replicas = ts.replicas;
    if (replicas) {
      let prev: number | null = null;
      for (let i = 0; i < replicas.length; i++) {
        const r = replicas[i];
        if (r == null) continue;
        const cur = Math.round(r);
        if (prev != null && cur !== prev) {
          const up = cur > prev;
          events.push({
            t: i * b,
            kind: up ? "scale_up" : "scale_down",
            label: `${name} ${up ? "scaled up" : "scaled down"} ${prev} → ${cur}`,
            nodeId: node.id,
            severity: "info",
          });
        }
        prev = cur;
      }
    }

    // Kubernetes clusters: nodes added or removed, and pods starting to wait for room.
    const nodes = ts.nodes;
    if (nodes && ts.podsRunning) {
      let prev: number | null = null;
      for (let i = 0; i < nodes.length; i++) {
        const v = nodes[i];
        if (v == null) continue;
        const cur = Math.round(v);
        if (prev != null && cur !== prev) {
          const up = cur > prev;
          events.push({
            t: i * b,
            kind: up ? "node_added" : "node_removed",
            label: `${name}: ${up ? "node added" : "node removed"} ${prev} → ${cur}`,
            nodeId: node.id,
            severity: "info",
          });
        }
        prev = cur;
      }
      const pending = ts.podsPending;
      if (pending) {
        let armed = true;
        let count = 0;
        for (let i = 0; i < pending.length && count < 3; i++) {
          const p = pending[i];
          if (p == null) continue;
          if (armed && p >= 0.5) {
            events.push({ t: i * b, kind: "pods_pending", label: `Pods Pending on ${name} (${Math.max(1, Math.round(p))} waiting for a node)`, nodeId: node.id, severity: "warning" });
            armed = false;
            count++;
          } else if (!armed && p < 0.5) {
            armed = true;
          }
        }
      }
    }
  }

  // SLO breaches and recoveries (system level).
  const compliance = sloTimeline(objectives, report.timeseries);
  let state: boolean | null = null;
  for (let i = 0; i < compliance.length; i++) {
    const ok = compliance[i];
    if (ok == null) continue;
    if (state !== false && ok === false) {
      events.push({ t: i * b, kind: "slo_breach", label: "SLO breached", severity: "critical" });
    } else if (state === false && ok === true) {
      events.push({ t: i * b, kind: "slo_recovered", label: "SLO met again", severity: "ok" });
    }
    state = ok;
  }

  // Timeouts appearing (more than 1% of completed traffic in a bucket).
  const to = report.timeseries.timeoutRps;
  const thr = report.timeseries.throughputRps;
  let armed = true;
  for (let i = 0; i < to.length; i++) {
    const v = to[i] ?? 0;
    const base = Math.max(1, (thr[i] ?? 0) + v);
    const on = v > 0 && v / base >= 0.01;
    if (armed && on) {
      events.push({ t: i * b, kind: "timeouts", label: `Timeouts begin (${fmtRate(v)}/s)`, severity: "warning" });
      armed = false;
    } else if (!armed && v === 0) {
      armed = true;
    }
  }

  return events.sort((a, c) => a.t - c.t || SEVERITY_RANK[a.severity] - SEVERITY_RANK[c.severity]);
}

// ---------------------------------------------------------------------------------------------
// Heat colors
// ---------------------------------------------------------------------------------------------

/*
 * Utilization heat: neutral at 0, a one-hue blue ramp for magnitude below 70%, then the reserved
 * status colors for warm (>=70%), hot (>=85%) and saturated (>=95%), the same thresholds as the
 * component status, so a lane and a node always agree. Ramp stops come from the validated
 * sequential palette (dataviz skill): light 250/350/450/550 on #ffffff, dark 600/500/350/250 on
 * #111317 (the graphite node fill is within a step of it); both pass the ordinal checks
 * (monotone lightness, visible gaps, low end clears 2:1).
 */
const RAMP_LIGHT = ["#86b6ef", "#5598e7", "#2a78d6", "#1c5cab"];
const RAMP_DARK = ["#184f95", "#256abf", "#5598e7", "#86b6ef"];

export const HEAT_THRESHOLDS = { warm: 0.7, hot: 0.85, saturated: 0.95 } as const;

function hexToRgb(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16);
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255];
}

function mix(a: string, c: string, f: number): string {
  const [r1, g1, b1] = hexToRgb(a);
  const [r2, g2, b2] = hexToRgb(c);
  const r = Math.round(r1 + (r2 - r1) * f);
  const g = Math.round(g1 + (g2 - g1) * f);
  const bl = Math.round(b1 + (b2 - b1) * f);
  return `#${((1 << 24) | (r << 16) | (g << 8) | bl).toString(16).slice(1)}`;
}

/** Color of a utilization value (0..1). null = no data (transparent). */
export function heatColor(u: number | null | undefined, dark: boolean): string {
  if (u == null || !Number.isFinite(u)) return "transparent";
  if (u <= 0.005) return "var(--surface-3)";
  if (u >= HEAT_THRESHOLDS.saturated) return "var(--status-critical)";
  if (u >= HEAT_THRESHOLDS.hot) return "var(--status-serious)";
  if (u >= HEAT_THRESHOLDS.warm) return "var(--status-warning)";
  const ramp = dark ? RAMP_DARK : RAMP_LIGHT;
  const pos = (u / HEAT_THRESHOLDS.warm) * (ramp.length - 1);
  const i = Math.min(ramp.length - 2, Math.floor(pos));
  return mix(ramp[i], ramp[i + 1], pos - i);
}

/** Legend stops for the heat scale. */
export function heatLegend(dark: boolean): { label: string; color: string }[] {
  const ramp = dark ? RAMP_DARK : RAMP_LIGHT;
  return [
    { label: "0", color: "var(--surface-3)" },
    { label: "<70%", color: `linear-gradient(90deg, ${ramp.join(", ")})` },
    { label: "warm", color: "var(--status-warning)" },
    { label: "hot", color: "var(--status-serious)" },
    { label: "saturated", color: "var(--status-critical)" },
  ];
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

/** Clock readout: "23.4 s", "2:05.0" above two minutes. */
export function fmtClock(t: number | null | undefined): string {
  if (t == null || !Number.isFinite(t)) return "–";
  const v = Math.max(0, t);
  if (v < 120) return `${v.toFixed(1)} s`;
  const m = Math.floor(v / 60);
  const s = v - m * 60;
  return `${m}:${s.toFixed(1).padStart(4, "0")}`;
}

/** Compact per-second rate: "412", "3.2", "0.04". */
export function fmtRate(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "–";
  if (v >= 1000) return `${(v / 1000).toFixed(v >= 10_000 ? 0 : 1)}k`;
  if (v >= 100) return String(Math.round(v));
  if (v >= 1) return v.toFixed(1);
  return v.toFixed(2);
}

/** "Nice" tick step (s) so labels are at least `minPx` apart. */
export function tickStep(durationS: number, plotWidth: number, minPx = 56): number {
  const steps = [0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600];
  const pxPerS = plotWidth / Math.max(durationS, 1e-6);
  return steps.find((s) => s * pxPerS >= minPx) ?? steps[steps.length - 1];
}
