/**
 * Dashboard model: turns a report into Grafana-style rows of panels. Pure and deterministic, so the
 * same report always yields the same panels, colours and layout (and tests can pin them down).
 *
 * Colours: categorical series use var(--series-1..8). Cross-component panels colour by component
 * (one map per dashboard, never cycled: components past the eighth fold into "Other"); a
 * component's own panels colour by metric (the registry's slot), so a metric keeps its colour.
 */
import { kindOf, type Kind } from "@/lib/theme";
import {
  DERIVED_METRICS,
  type MetricDef,
  type MetricScope,
  type MetricThresholds,
  type MetricUnit,
  NODE_METRICS,
  PANEL_GROUPS,
  type PanelGroupId,
  SATURATION_THRESHOLDS,
  type ThresholdStatus,
  metricDef,
  panelGroup,
} from "@/lib/metrics-catalog";
import { valueAt } from "@/lib/recorder";
import type { FailureRow, NodeReport, Objectives, Report, Series } from "@/lib/types";
import { type TimeRange, legendStats, maxSeries, sameRange, sumSeries, visibleBuckets } from "./range";

export const SERIES_COLORS = Array.from({ length: 8 }, (_, i) => `var(--series-${i + 1})`);
export const OTHER_COLOR = "var(--ink-3)";
export const MAX_COLORS = SERIES_COLORS.length;

export type PanelSpan = 4 | 6 | 8 | 12 | 24;

export interface MetricRef {
  key: string;
  scope: MetricScope;
}

export interface ChartSeries {
  id: string;
  label: string;
  data: Series;
  color: string;
  metric: MetricRef;
  nodeId?: string;
  dashed?: boolean;
}

export interface ThresholdLine {
  value: number;
  status: ThresholdStatus;
  label: string;
}

export interface ThresholdBand {
  from: number;
  to: number;
  status: Exclude<ThresholdStatus, "good">;
}

interface PanelBase {
  id: string;
  title: string;
  subtitle?: string;
  span: PanelSpan;
  /** What the panel shows, for the info popover (metric descriptions come from the registry). */
  description?: string;
  metrics: MetricRef[];
  /** The component the panel belongs to (Show on canvas, explain entries). */
  nodeId?: string;
}

export interface TimeSeriesSpec extends PanelBase {
  kind: "timeseries";
  unit: MetricUnit;
  series: ChartSeries[];
  style: "line" | "bars";
  thresholds: ThresholdLine[];
  bands: ThresholdBand[];
  /** Fixed top of the value axis (ratios: 1); otherwise it fits the visible data. */
  yMax?: number;
  /** Annotations to show: every event ("system") or only one component's. */
  scope: "system" | { nodeId: string };
  /** Shown instead of the plot when no series has data. */
  empty?: string;
}

export interface StatCandidate {
  nodeId: string;
  label: string;
  data: Series;
}

export interface StatSpec extends PanelBase {
  kind: "stat";
  unit: MetricUnit | "usd";
  /** Per-bucket values: background sparkline and range / replay readouts. */
  spark: Series | null;
  /** Exact value over the measurement window (from the report summary). */
  summary: number | null;
  summaryOwner?: string | null;
  /** "maxOfMeans": the busiest of several candidates (max saturation). "fixed": never changes. */
  reduce: "mean" | "fixed" | "maxOfMeans";
  candidates?: StatCandidate[];
  thresholds: MetricThresholds | null;
  /** Static second line, e.g. "objective 250 ms". */
  note?: string;
}

export interface TableSpec extends PanelBase {
  kind: "table";
  rows: FailureRow[];
}

export type PanelSpec = TimeSeriesSpec | StatSpec | TableSpec;

export interface RowSpec {
  id: string;
  title: string;
  panels: PanelSpec[];
  defaultCollapsed: boolean;
  /** Component rows only. */
  nodeId?: string;
  kind?: Kind;
  rank?: number;
}

export interface DashboardModel {
  rows: RowSpec[];
  colors: Map<string, string | null>;
}

const finite = (v: number | null | undefined): v is number => v != null && Number.isFinite(v);
const hasData = (s: Series | undefined): s is Series => !!s && s.some(finite);

// ---------------------------------------------------------------------------------------------
// Colours and layout
// ---------------------------------------------------------------------------------------------

export function isClientNode(n: Pick<NodeReport, "type" | "category">): boolean {
  return kindOf(n.type, n.category) === "traffic";
}

/**
 * Component → colour for one dashboard. Servers first (clients rarely share a panel with them),
 * report order within each, the first eight get var(--series-1..8) and the rest null ("Other").
 * Built from every component, never the filtered set, so filtering never repaints a survivor.
 */
export function componentColors(nodes: Pick<NodeReport, "id" | "type" | "category">[]): Map<string, string | null> {
  const ordered = [...nodes.filter((n) => !isClientNode(n)), ...nodes.filter((n) => isClientNode(n))];
  const map = new Map<string, string | null>();
  ordered.forEach((n, i) => map.set(n.id, i < MAX_COLORS ? SERIES_COLORS[i] : null));
  return map;
}

/** Spans (of 24 columns) for n panels: lines of three; a lone leftover joins the line before. */
export function layoutSpans(n: number): PanelSpan[] {
  if (n <= 0) return [];
  if (n === 1) return [24];
  if (n === 2) return [12, 12];
  const r = n % 3;
  const spans: PanelSpan[] = Array.from({ length: n }, () => 8 as PanelSpan);
  if (r === 1) for (let i = n - 4; i < n; i++) spans[i] = 6;
  if (r === 2) for (let i = n - 2; i < n; i++) spans[i] = 12;
  return spans;
}

/** Spans on a narrow screen: two stats or two charts per line. */
export function narrowSpan(span: PanelSpan): PanelSpan {
  return span === 4 ? 8 : span === 6 || span === 8 ? 12 : 24;
}

function withSpans<T extends PanelSpec>(panels: T[]): T[] {
  const spans = layoutSpans(panels.length);
  return panels.map((p, i) => ({ ...p, span: spans[i] }));
}

// ---------------------------------------------------------------------------------------------
// Ranking
// ---------------------------------------------------------------------------------------------

/** Components from busiest to idlest: mean utilization, then arrival rate. */
export function rankNodes(nodes: NodeReport[]): NodeReport[] {
  const util = (n: NodeReport) => n.summary?.utilization ?? -1;
  const rate = (n: NodeReport) => n.summary?.arrivalRps ?? 0;
  return [...nodes].sort((a, b) => util(b) - util(a) || rate(b) - rate(a));
}

// ---------------------------------------------------------------------------------------------
// Series helpers
// ---------------------------------------------------------------------------------------------

const DASHED = new Set(["offeredRps", "queueMax"]);

function slotColor(def: MetricDef): string {
  return SERIES_COLORS[Math.min(MAX_COLORS, Math.max(1, def.slot)) - 1];
}

/** Bands for thresholds: "above" colours from each step up; "below" from each step down. */
export function thresholdBands(t: MetricThresholds | undefined | null, top: number): ThresholdBand[] {
  if (!t) return [];
  const steps = t.steps;
  return steps.map((s, i) =>
    t.direction === "above"
      ? { from: s.value, to: i + 1 < steps.length ? steps[i + 1].value : Math.max(top, s.value), status: s.status }
      : { from: i + 1 < steps.length ? steps[i + 1].value : 0, to: s.value, status: s.status },
  );
}

/**
 * One series per component that reports `key`, coloured by the component map; components without
 * a colour fold into one "Other" series (summed for rates and counts, max for ratios and latencies).
 */
export function perComponentSeries(nodes: NodeReport[], key: string, colors: Map<string, string | null>): ChartSeries[] {
  const def = metricDef(key, "node");
  const own: ChartSeries[] = [];
  const rest: Series[] = [];
  let n = 0;
  for (const node of nodes) {
    const data = node.timeseries?.[key];
    if (!hasData(data)) continue;
    n = Math.max(n, data.length);
    const color = colors.get(node.id);
    if (color) own.push({ id: node.id, label: node.label || node.id, data, color, metric: { key, scope: "node" }, nodeId: node.id });
    else rest.push(data);
  }
  if (rest.length) {
    const useMax = def.kind === "ratio" || def.kind === "latency";
    own.push({
      id: "__other",
      label: `Other (${rest.length}${useMax ? ", max" : ""})`,
      data: useMax ? maxSeries(rest, n) : sumSeries(rest, n),
      color: OTHER_COLOR,
      metric: { key, scope: "node" },
    });
  }
  return own;
}

function crossPanel(
  id: string,
  title: string,
  key: string,
  nodes: NodeReport[],
  colors: Map<string, string | null>,
  extra: Partial<TimeSeriesSpec> = {},
): TimeSeriesSpec | null {
  const series = perComponentSeries(nodes, key, colors);
  if (!series.length && !extra.empty) return null;
  const def = metricDef(key, "node");
  return {
    kind: "timeseries",
    id,
    title,
    span: 12,
    unit: def.unit,
    series,
    style: "line",
    thresholds: [],
    bands: def.thresholds ? thresholdBands(def.thresholds, def.max ?? 1) : [],
    yMax: def.max,
    metrics: [{ key, scope: "node" }],
    scope: "system",
    ...extra,
  };
}

function systemSeries(report: Report, keys: string[]): ChartSeries[] {
  const ts = report.timeseries as unknown as Record<string, Series | undefined>;
  return keys
    .filter((k) => Array.isArray(ts[k]))
    .map((k) => {
      const def = metricDef(k, "system");
      return { id: k, label: def.label, data: ts[k] as Series, color: slotColor(def), metric: { key: k, scope: "system" as const }, dashed: DASHED.has(k) };
    });
}

// ---------------------------------------------------------------------------------------------
// Overview stats
// ---------------------------------------------------------------------------------------------

export interface OverviewInput {
  report: Report;
  objectives: Objectives;
  /** Monthly cost estimate (null without prices). */
  cost: { monthly: number; perMillionRequests: number | null; hasCost: boolean } | null;
}

function shareSeries(report: Report, kind: "success" | "error"): Series {
  const ts = report.timeseries;
  return ts.t.map((_, i) => {
    const thr = ts.throughputRps[i] ?? 0;
    const bad = (ts.errorRps[i] ?? 0) + (ts.timeoutRps[i] ?? 0);
    const total = thr + bad;
    if (!total) return null;
    return kind === "success" ? thr / total : bad / total;
  });
}

const fmtObjMs = (v: number) => (v >= 1000 ? `${+(v / 1000).toFixed(2)} s` : `${+v.toFixed(1)} ms`);
const fmtObjPct = (v: number) => `${+(v * 100).toFixed(3)}%`;

export function overviewStats({ report, objectives, cost }: OverviewInput): StatSpec[] {
  const s = report.summary;
  const ts = report.timeseries;
  const base = { kind: "stat" as const, span: 4 as PanelSpan };
  const avail = objectives.availability ?? null;
  const budget = avail != null ? Math.max(1e-6, 1 - avail) : null;
  const p95obj = objectives.latencyP95Ms ?? null;
  const minThr = objectives.minThroughputRps ?? null;

  const errorRate = s.successRate != null ? Math.max(0, 1 - s.successRate) : null;

  const candidates: StatCandidate[] = report.nodes
    .filter((n) => hasData(n.timeseries?.utilization))
    .map((n) => ({ nodeId: n.id, label: n.label || n.id, data: n.timeseries.utilization }));
  const busiest = report.nodes
    .filter((n) => n.summary?.utilization != null)
    .sort((a, b) => (b.summary.utilization ?? 0) - (a.summary.utilization ?? 0))[0];

  const stats: StatSpec[] = [
    {
      ...base,
      id: "stat:throughput",
      title: "Throughput",
      unit: "req/s",
      spark: ts.throughputRps,
      summary: s.throughputRps,
      reduce: "mean",
      thresholds: minThr != null ? { direction: "below", steps: [{ value: minThr, status: "critical" }] } : null,
      note: minThr != null ? `objective ≥ ${Math.round(minThr).toLocaleString("en-US")} req/s` : s.offeredRps != null ? `of ${Math.round(s.offeredRps).toLocaleString("en-US")} offered` : undefined,
      metrics: [{ key: "throughputRps", scope: "system" }],
    },
    {
      ...base,
      id: "stat:p95",
      title: "Latency p95",
      unit: "ms",
      spark: ts.latencyP95Ms,
      summary: s.latencyMs?.p95 ?? null,
      reduce: "mean",
      thresholds:
        p95obj != null
          ? { direction: "above", steps: [{ value: p95obj * 0.8, status: "warning" }, { value: p95obj, status: "critical" }] }
          : null,
      note: p95obj != null ? `objective ${fmtObjMs(p95obj)}` : "no objective set",
      metrics: [{ key: "latencyP95Ms", scope: "system" }],
    },
    {
      ...base,
      id: "stat:errors",
      title: "Error rate",
      unit: "ratio",
      spark: shareSeries(report, "error"),
      summary: errorRate,
      reduce: "mean",
      thresholds:
        budget != null
          ? { direction: "above", steps: [{ value: budget * 0.5, status: "warning" }, { value: budget, status: "critical" }] }
          : (DERIVED_METRICS.errorRate.thresholds ?? null),
      note: budget != null ? `budget ${fmtObjPct(budget)}` : "errors and timeouts",
      metrics: [{ key: "errorRate", scope: "derived" }],
    },
    {
      ...base,
      id: "stat:success",
      title: "Success rate",
      unit: "ratio",
      spark: shareSeries(report, "success"),
      summary: s.successRate,
      reduce: "mean",
      thresholds:
        avail != null && budget != null
          ? { direction: "below", steps: [{ value: 1 - budget * 0.5, status: "warning" }, { value: avail, status: "critical" }] }
          : { direction: "below", steps: [{ value: 0.999, status: "warning" }, { value: 0.99, status: "serious" }, { value: 0.95, status: "critical" }] },
      note: avail != null ? `objective ${fmtObjPct(avail)}` : undefined,
      metrics: [{ key: "successRate", scope: "derived" }],
    },
    {
      ...base,
      id: "stat:saturation",
      title: "Max saturation",
      unit: "ratio",
      spark: candidates.length ? maxSeries(candidates.map((c) => c.data), ts.t.length) : null,
      summary: busiest?.summary.utilization ?? null,
      summaryOwner: busiest ? busiest.label || busiest.id : null,
      reduce: "maxOfMeans",
      candidates,
      thresholds: SATURATION_THRESHOLDS,
      nodeId: busiest?.id,
      metrics: [{ key: "maxUtilization", scope: "derived" }],
    },
    {
      ...base,
      id: "stat:cost",
      title: "Cost / month",
      unit: "usd",
      spark: null,
      summary: cost?.hasCost ? cost.monthly : null,
      reduce: "fixed",
      thresholds: null,
      note: !cost?.hasCost
        ? "no prices set"
        : cost.perMillionRequests != null
          ? `$${cost.perMillionRequests >= 100 ? Math.round(cost.perMillionRequests).toLocaleString("en-US") : cost.perMillionRequests.toFixed(2)} per million requests`
          : undefined,
      metrics: [{ key: "costMonthly", scope: "derived" }],
    },
  ];
  return stats;
}

export interface StatView {
  range: TimeRange;
  /** The measurement window: readouts over it use the exact summary values. */
  measured: TimeRange;
  full: TimeRange;
  n: number;
  bucketS: number;
  playhead: number | null;
}

export interface StatReadout {
  value: number | null;
  owner: string | null;
  caption: string;
}

function meanOver(s: Series | null | undefined, i0: number, i1: number): number | null {
  return s ? legendStats(s, i0, i1).mean : null;
}

/** The value a stat panel shows for the current view: at the playhead, over the zoom, or the summary. */
export function statReadout(spec: StatSpec, view: StatView): StatReadout {
  if (spec.reduce === "fixed") return { value: spec.summary, owner: null, caption: "" };
  const b = view.bucketS || 1;
  if (view.playhead != null) {
    const t = view.playhead;
    if (spec.reduce === "maxOfMeans") {
      let best: StatCandidate | null = null;
      let bestV: number | null = null;
      for (const c of spec.candidates ?? []) {
        const v = valueAt(c.data, t, b, "linear");
        if (v != null && (bestV == null || v > bestV)) {
          best = c;
          bestV = v;
        }
      }
      return { value: bestV, owner: best?.label ?? null, caption: `at ${t.toFixed(1)} s` };
    }
    return { value: valueAt(spec.spark, t, b, "linear"), owner: null, caption: `at ${t.toFixed(1)} s` };
  }
  const whole = sameRange(view.range, view.full) || sameRange(view.range, view.measured);
  if (whole && spec.summary != null) return { value: spec.summary, owner: spec.summaryOwner ?? null, caption: "measured window" };
  const vis = visibleBuckets(view.n, b, whole ? view.measured : view.range);
  if (!vis) return { value: null, owner: null, caption: "no data in range" };
  if (spec.reduce === "maxOfMeans") {
    let best: StatCandidate | null = null;
    let bestV: number | null = null;
    for (const c of spec.candidates ?? []) {
      const v = meanOver(c.data, vis.i0, vis.i1);
      if (v != null && (bestV == null || v > bestV)) {
        best = c;
        bestV = v;
      }
    }
    return { value: bestV, owner: best?.label ?? null, caption: "mean over range" };
  }
  return { value: meanOver(spec.spark, vis.i0, vis.i1), owner: null, caption: "mean over range" };
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

export interface DashboardInput extends OverviewInput {
  colors: Map<string, string | null>;
  /** Components the variable filter hides. */
  hidden?: ReadonlySet<string>;
  /** Component rows expanded by default. */
  expandBusiest?: number;
}

function systemRows(input: DashboardInput, nodes: NodeReport[]): RowSpec[] {
  const { report, objectives, colors } = input;
  const s = report.summary;
  const rows: RowSpec[] = [];

  rows.push({ id: "overview", title: "Overview", defaultCollapsed: false, panels: overviewStats(input) });

  const traffic: TimeSeriesSpec = {
    kind: "timeseries",
    id: "sys:traffic",
    title: "Requests",
    span: 12,
    unit: "req/s",
    series: systemSeries(report, ["offeredRps", "throughputRps", "errorRps", "timeoutRps"]),
    style: "line",
    thresholds: objectives.minThroughputRps != null ? [{ value: objectives.minThroughputRps, status: "critical", label: "objective" }] : [],
    bands: [],
    description: "Offered load against what the system handled, and what failed or timed out.",
    metrics: ["offeredRps", "throughputRps", "errorRps", "timeoutRps"].map((key) => ({ key, scope: "system" as const })),
    scope: "system",
  };
  const amp = s.retryAmplification;
  const attempts: TimeSeriesSpec = {
    kind: "timeseries",
    id: "sys:attempts",
    title: "Attempts and retries",
    subtitle: amp != null ? `amplification ×${amp.toFixed(2)}` : undefined,
    span: 12,
    unit: "req/s",
    series: systemSeries(report, ["attemptRps", "offeredRps"]),
    style: "line",
    thresholds: [],
    bands: [],
    description: "Every attempt including retries against first attempts. A gap between them is retry amplification.",
    metrics: ["attemptRps", "offeredRps"].map((key) => ({ key, scope: "system" as const })),
    scope: "system",
  };
  rows.push({ id: "traffic", title: "Traffic", defaultCollapsed: false, panels: withSpans([traffic, attempts]) });

  const latThresholds: ThresholdLine[] = [];
  if (objectives.latencyP95Ms != null) latThresholds.push({ value: objectives.latencyP95Ms, status: "critical", label: "p95 objective" });
  if (objectives.latencyP99Ms != null) latThresholds.push({ value: objectives.latencyP99Ms, status: "serious", label: "p99 objective" });
  const latency: (TimeSeriesSpec | null)[] = [
    {
      kind: "timeseries",
      id: "sys:latency",
      title: "End-to-end latency",
      span: 12,
      unit: "ms",
      series: systemSeries(report, ["latencyP50Ms", "latencyP95Ms", "latencyP99Ms"]),
      style: "line",
      thresholds: latThresholds,
      bands: [],
      description: "Latency percentiles of successful requests, from the client's first attempt to the final answer.",
      metrics: ["latencyP50Ms", "latencyP95Ms", "latencyP99Ms"].map((key) => ({ key, scope: "system" as const })),
      scope: "system",
    },
    crossPanel("sys:node-p95", "Time in component p95", "latencyP95Ms", nodes, colors, {
      description: "95th percentile of the time requests spent in each component, including its own downstream calls.",
    }),
    crossPanel("sys:queue-wait", "Queue wait per component", "queueWaitMs", nodes, colors, {
      description: "How long requests waited for a free slot at each component.",
    }),
  ];
  rows.push({ id: "latency", title: "Latency", defaultCollapsed: false, panels: withSpans(latency.filter((p): p is TimeSeriesSpec => !!p)) });

  const saturation = [
    crossPanel("sys:utilization", "Utilization per component", "utilization", nodes, colors, {
      description: "Share of each component's workers, connections or consumers that were busy. Bands mark 70, 85 and 95%.",
      empty: "No component reports utilization",
    }),
    crossPanel("sys:queue", "Queue depth per component", "queueMean", nodes, colors, {
      description: "Requests waiting for a free slot at each component (time-weighted mean per bucket).",
    }),
    crossPanel("sys:cpu", "CPU utilization per component", "cpuUtilization", nodes, colors, {
      description: "Share of capacity spent computing rather than waiting on downstream calls.",
    }),
    crossPanel("sys:inflight", "In flight per component", "inflightMean", nodes, colors, {
      description: "Requests inside each component at once.",
    }),
    crossPanel("sys:replicas", "Instances per component", "replicas", nodes, colors, {
      description: "Running instances of each autoscaled or replicated component.",
    }),
  ].filter((p): p is TimeSeriesSpec => !!p);
  rows.push({ id: "saturation", title: "Saturation", defaultCollapsed: false, panels: withSpans(saturation) });

  const rejected = crossPanel("sys:rejected", "Rejected per component", "rejectedRps", nodes.filter((n) => n.timeseries?.rejectedRps?.some((v) => finite(v) && v > 0)), colors, {
    style: "bars",
    bands: [],
    description: "Requests each component turned away, stacked: full queue, queue timeout, limits, outages.",
    empty: "No component rejected requests",
  });
  const breakdown: TableSpec = {
    kind: "table",
    id: "sys:failures",
    title: "Failure breakdown",
    span: 12,
    rows: [...(s.failureBreakdown ?? [])].sort((a, b) => b.count - a.count),
    description: "Every failed request of the measurement window by reason and component.",
    metrics: [],
  };
  rows.push({ id: "errors", title: "Errors", defaultCollapsed: false, panels: withSpans([...(rejected ? [rejected] : []), breakdown]) });
  return rows;
}

/** A component panel's title fits what it actually shows (a lone metric names itself). */
function groupTitle(gid: string, defs: MetricDef[]): string {
  const has = (k: string) => defs.some((d) => d.key === k);
  if (gid === "componentLatency" && !has("latencyP50Ms") && !has("latencyP95Ms")) return "Queue wait";
  if (gid === "componentLatency" && !has("queueWaitMs")) return "Time in component";
  if (gid === "queue" && !has("inflightMean")) return "Queue depth";
  if (gid === "queue" && !has("queueMean")) return "In flight";
  if (defs.length === 1 || gid.startsWith("custom:")) return defs[0].label;
  return panelGroup(gid as PanelGroupId).title;
}

const OFF_PATH = new Set(["kubernetes_cluster"]);

/** Panels for one component, chosen from the series it reports. */
export function componentPanels(node: NodeReport): TimeSeriesSpec[] {
  const ts = node.timeseries ?? {};
  let keys = Object.keys(ts).filter((k) => hasData(ts[k]));
  // Parts off the request path (a Kubernetes cluster) only get their own signals, not empty
  // request, queue and latency panels.
  if (OFF_PATH.has(node.type)) keys = keys.filter((k) => metricDef(k, "node").emittedBy?.includes(node.type));
  const groups = new Map<string, MetricDef[]>();
  for (const k of keys) {
    const def = metricDef(k, "node");
    const gid = def.fallback ? `custom:${k}` : def.panel;
    const list = groups.get(gid) ?? [];
    list.push(def);
    groups.set(gid, list);
  }
  const order = [...PANEL_GROUPS.map((g) => g.id as string), ...[...groups.keys()].filter((g) => g.startsWith("custom:")).sort()];
  // Declaration order of the registry is the legend order inside a component panel.
  const keyOrder = Object.keys(NODE_METRICS);
  const panels: TimeSeriesSpec[] = [];
  for (const gid of order) {
    const defs = groups.get(gid);
    if (!defs?.length) continue;
    defs.sort((a, b) => keyIndex(keyOrder, a.key) - keyIndex(keyOrder, b.key));
    const shared = defs.every((d) => d.thresholds && d.thresholds === defs[0].thresholds) ? defs[0].thresholds : undefined;
    const top = defs.every((d) => d.max != null) ? Math.max(...defs.map((d) => d.max as number)) : undefined;
    panels.push({
      kind: "timeseries",
      id: `node:${node.id}:${gid}`,
      title: groupTitle(gid, defs),
      span: 8,
      unit: defs[0].unit,
      series: defs.map((d) => ({
        id: d.key,
        label: d.short,
        data: ts[d.key],
        color: slotColor(d),
        metric: { key: d.key, scope: "node" as const },
        nodeId: node.id,
        dashed: DASHED.has(d.key),
      })),
      style: "line",
      thresholds: [],
      bands: thresholdBands(shared, top ?? 1),
      yMax: top,
      metrics: defs.map((d) => ({ key: d.key, scope: "node" as const })),
      nodeId: node.id,
      scope: { nodeId: node.id },
    });
  }
  return withSpans(panels);
}

function keyIndex(order: string[], key: string): number {
  const i = order.indexOf(key);
  return i === -1 ? order.length : i;
}

const KIND_ORDER: Kind[] = ["network", "compute", "cache", "database", "messaging", "external", "traffic", "other"];

/** The whole dashboard: system rows, then one row per (visible) component. */
export function buildDashboard(input: DashboardInput): DashboardModel {
  const { report, colors } = input;
  const hidden = input.hidden ?? new Set<string>();
  const nodes = report.nodes.filter((n) => !hidden.has(n.id));
  // Clients only send traffic: their queues and waits would add flat lines to every shared panel.
  const rows = systemRows(input, nodes.filter((n) => !isClientNode(n)));
  const ranked = rankNodes(nodes);
  const expand = new Set(ranked.slice(0, input.expandBusiest ?? 3).map((n) => n.id));
  for (const node of nodes) {
    rows.push({
      id: `node:${node.id}`,
      title: node.label || node.id,
      nodeId: node.id,
      kind: kindOf(node.type, node.category),
      rank: ranked.indexOf(node),
      defaultCollapsed: !expand.has(node.id),
      panels: componentPanels(node),
    });
  }
  return { rows, colors };
}

/** Component rows sorted by kind (stable within a kind), for "Group by kind". */
export function groupRowsByKind(rows: RowSpec[]): RowSpec[] {
  const sys = rows.filter((r) => !r.nodeId);
  const comp = rows.filter((r) => r.nodeId);
  const k = (r: RowSpec) => KIND_ORDER.indexOf(r.kind ?? "other");
  return [...sys, ...comp.map((r, i) => ({ r, i })).sort((a, b) => k(a.r) - k(b.r) || a.i - b.i).map((x) => x.r)];
}

/** All series values of a panel in a range, for "Inspect data" and CSV export. */
export function panelTable(spec: TimeSeriesSpec, bucketS: number, range: TimeRange): { header: string[]; rows: (number | null)[][] } {
  const n = Math.max(0, ...spec.series.map((s) => s.data.length));
  const vis = visibleBuckets(n, bucketS, range);
  const header = ["t (s)", ...spec.series.map((s) => s.label)];
  if (!vis) return { header, rows: [] };
  const rows: (number | null)[][] = [];
  for (let i = vis.i0; i <= vis.i1; i++) rows.push([i * bucketS, ...spec.series.map((s) => s.data[i] ?? null)]);
  return { header, rows };
}

export function toCsv(table: { header: string[]; rows: (number | null)[][] }): string {
  const esc = (v: string) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  return [table.header.map(esc).join(","), ...table.rows.map((r) => r.map((v) => (v == null ? "" : String(v))).join(","))].join("\n");
}
