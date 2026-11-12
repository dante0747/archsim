/**
 * Chaos experiments: fault helpers, one-click experiment presets, and the verdict of a run
 * against the steady-state hypothesis (the document's objectives).
 *
 * Faults live in `workload.faults` (see `Fault` in archsim-model):
 *   outage   - a node rejects every request arriving in the window
 *   slowdown - service times at a node are multiplied by `factor`
 *   latency  - each traversal of an edge gets `extraMs` added (one way)
 */
import { evaluateSlo, sloTimeline, type SloResult } from "./slo";
import { isClientType } from "./theme";
import type { Catalog, Fault, Objectives, Report, Workload } from "./types";
import { sameJson } from "./utils";

export type FaultKind = Fault["kind"];

export const FAULT_KINDS: FaultKind[] = ["outage", "slowdown", "latency"];

export const FAULT_META: Record<FaultKind, { label: string; target: "node" | "edge"; color: string; description: string }> = {
  outage: {
    label: "Outage",
    target: "node",
    color: "var(--status-critical)",
    description: "The component refuses every request that arrives during the window.",
  },
  slowdown: {
    label: "Slowdown",
    target: "node",
    color: "var(--status-warning)",
    description: "Service times at the component are multiplied by a factor.",
  },
  latency: {
    label: "Latency",
    target: "edge",
    color: "var(--accent)",
    description: "Extra one-way network latency on a link.",
  },
};

export const DEFAULT_FACTOR = 4;
export const DEFAULT_EXTRA_MS = 300;
export const MIN_FAULT_S = 0.5;

/** Minimal graph shapes so the helpers work with editor state and report input alike. */
export interface NodeLike {
  id: string;
  data: { type: string; label: string };
}
export interface EdgeLike {
  id: string;
  source: string;
  target: string;
}

export interface FaultTarget {
  id: string;
  label: string;
  /** component type for nodes; "edge" for links */
  type: string;
}

const round1 = (v: number) => Math.round(v * 10) / 10;
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** The part of the run that counts in the summary: [warmupS, durationS]. */
export function measuredWindow(w: Pick<Workload, "durationS" | "warmupS">): [number, number] {
  const start = clamp(w.warmupS, 0, w.durationS);
  return [start, w.durationS];
}

/** A window of `lengthS` centred in the measured part of the run, snapped to whole seconds. */
export function centredWindow(w: Pick<Workload, "durationS" | "warmupS">, lengthS: number): { startS: number; durationS: number } {
  const [a, b] = measuredWindow(w);
  const span = Math.max(b - a, MIN_FAULT_S);
  const len = clamp(lengthS, MIN_FAULT_S, span);
  const start = a + (span - len) / 2;
  const snapped = span - len >= 1 ? Math.round(start) : round1(start);
  return { startS: clamp(snapped, 0, Math.max(0, w.durationS - len)), durationS: round1(len) };
}

export function nextFaultId(existing: Pick<Fault, "id">[]): string {
  const taken = new Set(existing.map((f) => f.id));
  for (let i = 1; ; i++) {
    const id = `f${i}`;
    if (!taken.has(id)) return id;
  }
}

/** A new fault with a sensible default window: a quarter of the measured part, centred. */
export function newFault(kind: FaultKind, target: string, workload: Pick<Workload, "durationS" | "warmupS" | "faults">): Fault {
  const [a, b] = measuredWindow(workload);
  const len = Math.max(1, Math.round((b - a) / 4));
  const win = centredWindow(workload, len);
  const f: Fault = { id: nextFaultId(workload.faults ?? []), kind, target, startS: win.startS, durationS: win.durationS };
  if (kind === "slowdown") f.factor = DEFAULT_FACTOR;
  if (kind === "latency") f.extraMs = DEFAULT_EXTRA_MS;
  return f;
}

/** Keep a fault inside the run and its parameters inside the engine's bounds. */
export function clampFault(f: Fault, durationS: number): Fault {
  const maxStart = Math.max(0, durationS - MIN_FAULT_S);
  const startS = round1(clamp(f.startS, 0, maxStart));
  const dur = round1(clamp(f.durationS, Math.min(MIN_FAULT_S, durationS - startS), Math.max(MIN_FAULT_S, durationS - startS)));
  const out: Fault = { ...f, startS, durationS: dur };
  if (f.factor != null) out.factor = clamp(f.factor, 1, 100);
  if (f.extraMs != null) out.extraMs = clamp(f.extraMs, 0, 60_000);
  return out;
}

/** What a fault does to a run: the fields the engine reads for its kind, with its defaults. */
function faultEffect(f: Fault) {
  return {
    kind: f.kind,
    target: f.target,
    startS: f.startS,
    durationS: f.durationS,
    factor: f.kind === "slowdown" ? (f.factor ?? 4) : null,
    extraMs: f.kind === "latency" ? (f.extraMs ?? 200) : null,
  };
}

/**
 * Whether a run used the test plan the editor holds now (traffic and failures). Only what
 * changes the run counts: a stored run comes back with the engine's defaults filled in (an
 * outage gets a slowdown factor and extra latency it never uses), and from PostgreSQL with its
 * keys reordered.
 */
export function sameExperiment(ran: Workload, now: Workload): boolean {
  return (
    sameJson((ran.faults ?? []).map(faultEffect), (now.faults ?? []).map(faultEffect)) &&
    sameJson(ran.pattern, now.pattern) &&
    ran.rps === now.rps &&
    ran.durationS === now.durationS
  );
}

export function faultsActiveAt(faults: Fault[] | undefined, t: number): Fault[] {
  return (faults ?? []).filter((f) => f.startS <= t && t < f.startS + f.durationS);
}

/** Name lookup for node and edge ids ("api → db" for edges). */
export function targetNames(nodes: NodeLike[], edges: EdgeLike[]): Record<string, string> {
  const names: Record<string, string> = {};
  for (const n of nodes) names[n.id] = n.data.label || n.id;
  for (const e of edges) names[e.id] = `${names[e.source] ?? e.source} → ${names[e.target] ?? e.target}`;
  return names;
}

export function faultParam(f: Fault): string {
  if (f.kind === "slowdown") return `×${f.factor ?? DEFAULT_FACTOR}`;
  if (f.kind === "latency") return `+${f.extraMs ?? DEFAULT_EXTRA_MS} ms`;
  return "down";
}

export function faultLabel(f: Fault, names: Record<string, string>): string {
  if (f.label) return f.label;
  const target = names[f.target] ?? f.target;
  if (f.kind === "outage") return `Outage · ${target}`;
  if (f.kind === "slowdown") return `Slowdown ${faultParam(f)} · ${target}`;
  return `${faultParam(f)} · ${target}`;
}

export function fmtWindow(f: Pick<Fault, "startS" | "durationS">): string {
  const s = (v: number) => (Number.isInteger(v) ? String(v) : v.toFixed(1));
  return `${s(f.startS)}–${s(round1(f.startS + f.durationS))} s`;
}

/** Valid fault targets per kind: any non-source node for outage/slowdown; any link for latency. */
export function faultTargets(nodes: NodeLike[], edges: EdgeLike[], catalog: Catalog | null): Record<FaultKind, FaultTarget[]> {
  const names = targetNames(nodes, edges);
  const nodeTargets = nodes
    .filter((n) => !isClientType(n.data.type, catalog))
    .map((n) => ({ id: n.id, label: names[n.id], type: n.data.type }));
  const edgeTargets = edges.map((e) => ({ id: e.id, label: names[e.id], type: "edge" }));
  return { outage: nodeTargets, slowdown: nodeTargets, latency: edgeTargets };
}

/** Is this fault's target valid for its kind in the given graph? */
export function faultTargetValid(f: Fault, nodes: NodeLike[], edges: EdgeLike[], catalog: Catalog | null): boolean {
  return faultTargets(nodes, edges, catalog)[f.kind].some((t) => t.id === f.target);
}

// ---------------------------------------------------------------------------------------------
// One-click experiments
// ---------------------------------------------------------------------------------------------

export interface ChaosPreset {
  id: string;
  title: string;
  description: string;
  kind: FaultKind | "spike";
  /** Patch for the workload. Single-variable: replaces the current faults. */
  apply: (w: Workload) => Partial<Workload>;
}

function faultPreset(
  id: string,
  title: string,
  description: string,
  kind: FaultKind,
  target: string,
  lengthS: number,
  extra: Partial<Fault> = {},
): ChaosPreset {
  return {
    id,
    title,
    description,
    kind,
    apply: (w) => {
      const win = centredWindow(w, lengthS);
      return { faults: [{ id: "f1", kind, target, ...win, ...extra }] };
    },
  };
}

/** Does `actual` hold every field `want` sets? Arrays must match element by element. */
function holds(actual: unknown, want: unknown): boolean {
  if (Array.isArray(want)) {
    const list = actual ?? [];
    return Array.isArray(list) && list.length === want.length && want.every((v, i) => holds(list[i], v));
  }
  if (want !== null && typeof want === "object") {
    if (actual === null || typeof actual !== "object") return false;
    return Object.entries(want).every(([k, v]) => holds((actual as Record<string, unknown>)[k], v));
  }
  return actual === want;
}

/**
 * Is this experiment already planned? Only what the pad sets counts: a saved fault comes back
 * from the API with the engine's defaults filled in (`factor`, `extraMs`, `label: null`), and the
 * fault's id or label does not change the run. Faults compare by what they do (`faultEffect`).
 */
export function presetActive(p: ChaosPreset, w: Workload): boolean {
  const patch = p.apply(w);
  return Object.entries(patch).every(([k, v]) =>
    k === "faults"
      ? sameJson(((v as Fault[] | undefined) ?? []).map(faultEffect), (w.faults ?? []).map(faultEffect))
      : holds((w as unknown as Record<string, unknown>)[k], v),
  );
}

/** Experiments that make sense for this graph, most telling first (at most 6). */
export function chaosPresets(nodes: NodeLike[], edges: EdgeLike[], catalog: Catalog | null, workload: Workload): ChaosPreset[] {
  const out: ChaosPreset[] = [];
  const names = targetNames(nodes, edges);
  const byType = (t: string) => nodes.filter((n) => n.data.type === t);
  const typeOf = (id: string) => nodes.find((n) => n.id === id)?.data.type;

  for (const n of byType("cache").slice(0, 1)) {
    out.push(faultPreset(`lose-${n.id}`, `Lose ${names[n.id]} for 15 s`, "Does the design fall back to the data store, and can the data store take the extra reads?", "outage", n.id, 15));
  }
  for (const n of byType("database").slice(0, 1)) {
    out.push(faultPreset(`slow-${n.id}`, `${names[n.id]} 4× slower for 20 s`, "A slow database: do callers queue up, time out, or retry it to death?", "slowdown", n.id, 20, { factor: 4 }));
  }
  const dbEdge = edges.find((e) => typeOf(e.source) === "service" && typeOf(e.target) === "database") ?? edges.find((e) => typeOf(e.source) === "service");
  if (dbEdge) {
    out.push(faultPreset(`lag-${dbEdge.id}`, `+300 ms on ${names[dbEdge.id]}`, "Network trouble on one link: are timeouts set so the slowness stays contained?", "latency", dbEdge.id, 15, { extraMs: 300 }));
  }
  for (const n of byType("external_api").slice(0, 1)) {
    out.push(faultPreset(`down-${n.id}`, `${names[n.id]} goes down for 15 s`, "A third-party dependency fails: does the failure spread to every request?", "outage", n.id, 15));
  }
  for (const n of byType("service").slice(0, 1)) {
    out.push(faultPreset(`kill-${n.id}`, `Kill ${names[n.id]} for 10 s`, "The whole service tier is down: how fast does the system recover afterwards?", "outage", n.id, 10));
  }
  for (const n of byType("message_queue").slice(0, 1)) {
    out.push(faultPreset(`broker-${n.id}`, `${names[n.id]} broker outage, 10 s`, "Producers cannot publish: what happens upstream?", "outage", n.id, 10));
  }
  if (workload.rps > 0 && out.length < 6) {
    out.push({
      id: "spike",
      title: "3× traffic spike for 10 s",
      description: "A sudden burst at three times the baseline load. Is there enough headroom or a buffer?",
      kind: "spike",
      apply: (w) => {
        const win = centredWindow(w, 10);
        return { faults: [], pattern: { kind: "burst", burstRps: Math.round(w.rps * 3), startS: win.startS, durationS: win.durationS } };
      },
    });
  }
  return out.slice(0, 6);
}

// ---------------------------------------------------------------------------------------------
// Verdict
// ---------------------------------------------------------------------------------------------

export interface WindowEvidence {
  fault: Fault;
  /** share of requests that succeeded inside the window (null = no traffic) */
  success: number | null;
  /** worst per-bucket p95 inside the window (ms) */
  worstP95: number | null;
  /** buckets that breached the objectives / buckets with traffic */
  breached: number;
  buckets: number;
  pass: boolean | null;
}

export interface Verdict {
  held: boolean | null;
  summary: string;
  overall: SloResult;
  windows: WindowEvidence[];
  /** The worst moment of the run (start of the worst bucket), for "jump to". */
  worst: { t: number; success: number | null; p95: number | null; why: string } | null;
}

function bucketSuccess(ts: Report["timeseries"], i: number): { ok: number; total: number } {
  const ok = ts.throughputRps[i] ?? 0;
  const bad = (ts.errorRps[i] ?? 0) + (ts.timeoutRps[i] ?? 0);
  return { ok, total: ok + bad };
}

export function windowEvidence(report: Pick<Report, "timeseries">, objectives: Objectives, f: Fault): WindowEvidence {
  const ts = report.timeseries;
  const ok = sloTimeline(objectives, ts);
  const end = f.startS + f.durationS;
  let okSum = 0;
  let total = 0;
  let worstP95: number | null = null;
  let breached = 0;
  let buckets = 0;
  for (let i = 0; i < ts.t.length; i++) {
    const t = ts.t[i];
    if (t + ts.bucketS <= f.startS || t >= end) continue;
    const s = bucketSuccess(ts, i);
    okSum += s.ok;
    total += s.total;
    const p = ts.latencyP95Ms[i];
    if (p != null && s.total > 0) worstP95 = worstP95 == null ? p : Math.max(worstP95, p);
    if (ok[i] !== null) {
      buckets++;
      if (ok[i] === false) breached++;
    }
  }
  const success = total > 0 ? okSum / total : null;
  return { fault: f, success, worstP95, breached, buckets, pass: buckets === 0 ? null : breached === 0 };
}

function worstMoment(report: Pick<Report, "timeseries">, objectives: Objectives): Verdict["worst"] {
  const ts = report.timeseries;
  const ok = sloTimeline(objectives, ts);
  let best: { i: number; score: number; why: string } | null = null;
  for (let i = 0; i < ts.t.length; i++) {
    if (ok[i] !== false) continue;
    const s = bucketSuccess(ts, i);
    const success = s.total ? s.ok / s.total : 1;
    const p95 = ts.latencyP95Ms[i] ?? 0;
    const availGap = objectives.availability != null ? Math.max(0, objectives.availability - success) : 0;
    const latGap = objectives.latencyP95Ms ? Math.max(0, p95 / objectives.latencyP95Ms - 1) : 0;
    // An availability shortfall outweighs a latency overshoot of the same relative size.
    const score = availGap * 10 + latGap;
    if (!best || score > best.score) best = { i, score, why: availGap > 0 ? "lowest success" : "highest p95" };
  }
  if (!best) return null;
  const s = bucketSuccess(ts, best.i);
  return { t: ts.t[best.i], success: s.total ? s.ok / s.total : null, p95: ts.latencyP95Ms[best.i] ?? null, why: best.why };
}

const pct = (v: number) => `${(v * 100).toFixed(v >= 0.995 ? 1 : 0)}%`;

/**
 * Did the steady-state hypothesis hold? It holds when the run meets every objective overall and
 * no one-second bucket inside a fault window breaches them.
 */
export function experimentVerdict(report: Pick<Report, "timeseries" | "summary" | "input">, objectives: Objectives, names: Record<string, string> = {}): Verdict {
  const overall = evaluateSlo(objectives, report);
  const faults = report.input?.workload?.faults ?? [];
  const windows = faults.map((f) => windowEvidence(report, objectives, f));
  const worst = worstMoment(report, objectives);
  if (overall.total === 0) {
    return { held: null, summary: "No objectives set, so there is nothing to check. Add a latency or availability target.", overall, windows, worst };
  }
  const failedWindow = windows.find((w) => w.pass === false);
  const held = overall.pass === false || failedWindow ? false : overall.pass === null && windows.every((w) => w.pass === null) ? null : true;
  let summary: string;
  if (held === null) {
    summary = "Not enough traffic to judge.";
  } else if (held) {
    summary = faults.length ? `Objectives met overall and during ${faults.length === 1 ? "the failure" : `all ${faults.length} failures`}.` : "Objectives met for the whole run.";
  } else if (failedWindow) {
    const what = faultLabel(failedWindow.fault, names);
    const detail =
      objectives.availability != null && failedWindow.success != null && failedWindow.success < objectives.availability
        ? `success fell to ${pct(failedWindow.success)}`
        : failedWindow.worstP95 != null
          ? `p95 reached ${Math.round(failedWindow.worstP95)} ms`
          : `${failedWindow.breached} s breached the objectives`;
    summary = `Missed: ${detail} during ${what} (${fmtWindow(failedWindow.fault)}).`;
  } else {
    const miss = overall.checks.filter((c) => c.pass === false).map((c) => c.label.toLowerCase());
    summary = `Missed: ${miss.join(" and ")} fell short of the objective over the run.`;
  }
  return { held, summary, overall, windows, worst };
}
