/**
 * Conversion between the versioned document (what is saved, exported and simulated) and the
 * React Flow graph state the editor manipulates. Layout lives only in `layout`, never in the
 * topology, so moving a node never changes simulation input.
 */
import type { Edge, Node, Viewport } from "@xyflow/react";
import type {
  ArchitectureDocument,
  CalibrationRecord,
  Catalog,
  DocMetadata,
  EdgeConfig,
  InputProvenance,
  NodeConfig,
  Objectives,
  Pricing,
  Scenario,
  Workload,
} from "./types";

export interface ArchNodeData extends Record<string, unknown> {
  type: string;
  label: string;
  description?: string | null;
  config: NodeConfig;
}

export interface ArchEdgeData extends Record<string, unknown> {
  config: EdgeConfig;
  label?: string | null;
  /** Port names on the components (what the simulation uses); handles only say which side. */
  sourcePort?: string;
  targetPort?: string;
}

export type ArchNode = Node<ArchNodeData, "arch">;
export type ArchEdge = Edge<ArchEdgeData, "arch">;

/** Components can be linked from any of their four sides. */
export type Side = "top" | "right" | "bottom" | "left";
export const SIDES: Side[] = ["top", "right", "bottom", "left"];
export const sideHandle = (side: Side): string => `s-${side}`;

/** The side a handle id names ("s-top"), or null for a plain port name ("out", "miss"). */
export function sideOf(handle: string | null | undefined): Side | null {
  if (!handle || !handle.startsWith("s-")) return null;
  const side = handle.slice(2) as Side;
  return SIDES.includes(side) ? side : null;
}

/** The port a link leaves from / arrives at, whatever side it is drawn on. */
export function edgeSourcePort(e: Pick<ArchEdge, "sourceHandle" | "data">): string {
  return e.data?.sourcePort ?? (sideOf(e.sourceHandle) ? "out" : (e.sourceHandle ?? "out"));
}
export function edgeTargetPort(e: Pick<ArchEdge, "targetHandle" | "data">): string {
  return e.data?.targetPort ?? (sideOf(e.targetHandle) ? "in" : (e.targetHandle ?? "in"));
}

export interface GraphState {
  nodes: ArchNode[];
  edges: ArchEdge[];
  workload: Workload;
  scenarios: Scenario[];
  objectives: Objectives;
  metadata: DocMetadata;
  /** Document fields the editor does not edit directly but must keep (schema 1.1). */
  extras?: DocExtras;
}

/** Kept through edits and saves: auto-sizing off, where inputs came from, the last calibration,
 * where prices come from. */
export interface DocExtras {
  autoSize?: boolean;
  provenance: Record<string, InputProvenance>;
  calibration: CalibrationRecord | null;
  pricing?: Pricing | null;
}

export const SCHEMA_VERSION = "1.3";

/** The provenance of an input, or undefined (then it is assumed or illustrative). */
export function provenanceOf(extras: DocExtras | undefined, path: string): InputProvenance | undefined {
  return extras?.provenance[path];
}

/** JSON values equal up to float noise (the engine's ``same_value``). */
export function sameValue(a: unknown, b: unknown): boolean {
  if (typeof a === "number" && typeof b === "number") {
    return a === b || Math.abs(a - b) <= Math.max(1e-12, 1e-9 * Math.max(Math.abs(a), Math.abs(b)));
  }
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => sameValue(x, b[i]));
  if (a && b && typeof a === "object" && typeof b === "object" && !Array.isArray(a) && !Array.isArray(b)) {
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    return ka.length === kb.length && ka.every((k) => k in b && sameValue((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]));
  }
  return a === b;
}

/** Whether a measured input still has the value calibration set. Records without a value
 * (written before schema 1.2 kept it) are taken at their word. */
export function stillMeasured(p: InputProvenance | undefined, current: unknown): boolean {
  return p?.kind === "measured" && (p.value === undefined || p.value === null || sameValue(p.value, current));
}

/** One line on a calibration's replay: the end-to-end p95 and always the largest of all errors
 * (a close p95 can hide a poor p99 or part). Empty when nothing was compared. */
export function replayVerdict(record: CalibrationRecord | null | undefined): string {
  const checks = (record?.replay ?? []).filter((c) => c.error != null);
  if (!checks.length) return "";
  const pct = (e: number) => `${e > 0 ? "+" : ""}${Math.round(e * 100)}%`;
  const worst = checks.reduce((w, c) => (Math.abs(c.error ?? 0) > Math.abs(w.error ?? 0) ? c : w));
  const metric = worst.metric === "throughputRps" ? "throughput" : worst.metric;
  const largest = `the largest of ${checks.length} replay errors is ${pct(worst.error ?? 0)} (${worst.label} ${metric})`;
  const e2e = checks.find((c) => c.scope === "end_to_end" && c.metric === "p95");
  if (!e2e || e2e.error == null) return `${largest[0].toUpperCase()}${largest.slice(1)}.`;
  return `The model reproduces the observed p95 within ${Math.round(Math.abs(e2e.error) * 100)}% (${e2e.error > 0 ? "over" : "under"}); ${largest}.`;
}

/** ``extras`` without the provenance of edited inputs (a typed value is no longer measured). */
export function withoutProvenance(extras: DocExtras | undefined, paths: string[], prefixes: string[] = []): DocExtras | undefined {
  if (!extras) return extras;
  const keys = Object.keys(extras.provenance);
  const drop = keys.filter((k) => paths.includes(k) || prefixes.some((p) => k.startsWith(p)));
  if (!drop.length) return extras;
  const provenance = { ...extras.provenance };
  for (const k of drop) delete provenance[k];
  return { ...extras, provenance };
}

export const DEFAULT_OBJECTIVES: Objectives = {
  latencyP95Ms: 300,
  latencyP99Ms: null,
  availability: 0.999,
  minThroughputRps: null,
};

export const DEFAULT_WORKLOAD: Workload = {
  rps: 100,
  durationS: 60,
  warmupS: 10,
  seed: 42,
  readRatio: 0.9,
  arrivalProcess: "poisson",
  pattern: { kind: "constant" },
  keyCount: 100000,
  keySkew: 0.99,
  drainS: null,
  faults: [],
};

const ID_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;

export function slugify(text: string): string {
  const s = text
    .toLowerCase()
    .replace(/[^a-z0-9_.-]+/g, "-")
    .replace(/^[^a-z0-9]+/, "")
    .replace(/-+$/, "")
    .slice(0, 56);
  return s || "node";
}

export function uniqueId(base: string, existing: Iterable<string>): string {
  const taken = new Set(existing);
  const root = slugify(base);
  if (!taken.has(root)) return root;
  for (let i = 2; ; i++) {
    const candidate = `${root}-${i}`;
    if (!taken.has(candidate)) return candidate;
  }
}

export function isValidId(id: string): boolean {
  return ID_RE.test(id);
}

export function fromDocument(doc: ArchitectureDocument): GraphState & { viewport: Viewport | null } {
  const positions = doc.layout?.nodes ?? {};
  const nodes: ArchNode[] = doc.architecture.nodes.map((n, i) => ({
    id: n.id,
    type: "arch",
    position: positions[n.id] ?? { x: (i % 4) * 300, y: Math.floor(i / 4) * 200 },
    data: {
      type: n.type,
      label: n.label || n.id,
      description: n.description ?? null,
      config: n.config ?? {},
    },
  }));
  const sides = doc.layout?.edges ?? {};
  const edges: ArchEdge[] = doc.architecture.edges.map((e) => ({
    id: e.id,
    type: "arch",
    source: e.source,
    target: e.target,
    sourceHandle: sideHandle(sides[e.id]?.sourceSide ?? "right"),
    targetHandle: sideHandle(sides[e.id]?.targetSide ?? "left"),
    data: { config: e.config, label: e.label ?? null, sourcePort: e.sourcePort ?? "out", targetPort: e.targetPort ?? "in" },
  }));
  return {
    nodes,
    edges,
    workload: { ...DEFAULT_WORKLOAD, ...(doc.workload ?? {}) },
    scenarios: doc.scenarios ?? [],
    objectives: { ...DEFAULT_OBJECTIVES, ...(doc.objectives ?? {}) },
    metadata: doc.metadata ?? { name: "Untitled architecture" },
    extras: {
      autoSize: doc.architecture.autoSize,
      provenance: doc.provenance ?? {},
      calibration: doc.calibration ?? null,
      pricing: doc.pricing ?? null,
    },
    viewport: doc.layout?.viewport ?? null,
  };
}

export function toDocument(state: GraphState, viewport?: Viewport | null): ArchitectureDocument {
  const round = (v: number) => Math.round(v * 10) / 10;
  return {
    schemaVersion: SCHEMA_VERSION,
    kind: "archsim.architecture",
    metadata: state.metadata,
    architecture: {
      nodes: state.nodes.map((n) => ({
        id: n.id,
        type: n.data.type,
        label: n.data.label,
        description: n.data.description ?? null,
        config: n.data.config,
      })),
      edges: state.edges.map((e) => ({
        id: e.id,
        source: e.source,
        sourcePort: edgeSourcePort(e),
        target: e.target,
        targetPort: edgeTargetPort(e),
        label: e.data?.label ?? null,
        config: e.data?.config ?? defaultEdgeConfigFallback(),
      })),
      ...(state.extras?.autoSize === false ? { autoSize: false } : {}),
    },
    workload: state.workload,
    scenarios: state.scenarios,
    objectives: state.objectives,
    layout: {
      nodes: Object.fromEntries(
        state.nodes.map((n) => [n.id, { x: round(n.position.x), y: round(n.position.y) }]),
      ),
      ...edgeSides(state.edges),
      viewport: viewport ? { x: round(viewport.x), y: round(viewport.y), zoom: viewport.zoom } : null,
    },
    ...(state.extras && Object.keys(state.extras.provenance).length ? { provenance: state.extras.provenance } : {}),
    ...(state.extras?.calibration ? { calibration: state.extras.calibration } : {}),
    ...(state.extras?.pricing && state.extras.pricing.provider !== "generic" ? { pricing: state.extras.pricing } : {}),
  };
}

/** Only links drawn on non-default sides are recorded, so plain documents stay unchanged. */
function edgeSides(edges: ArchEdge[]): { edges?: NonNullable<ArchitectureDocument["layout"]["edges"]> } {
  const out: NonNullable<ArchitectureDocument["layout"]["edges"]> = {};
  for (const e of edges) {
    const s = sideOf(e.sourceHandle) ?? "right";
    const t = sideOf(e.targetHandle) ?? "left";
    if (s !== "right" || t !== "left") out[e.id] = { sourceSide: s, targetSide: t };
  }
  return Object.keys(out).length ? { edges: out } : {};
}

function defaultEdgeConfigFallback(): EdgeConfig {
  return { requestTypes: ["read", "write"], latencyMs: { kind: "constant", value: 0.5 } };
}

/** Default config for a new link, taken from the catalog's edge field defaults. */
export function defaultEdgeConfig(catalog: Catalog | null, requestTypes: EdgeConfig["requestTypes"]): EdgeConfig {
  const cfg: Record<string, unknown> = {};
  for (const f of catalog?.edgeFields ?? []) cfg[f.key] = structuredClone(f.default);
  return {
    ...defaultEdgeConfigFallback(),
    ...(cfg as Partial<EdgeConfig>),
    requestTypes,
  };
}

/** Read a possibly nested config value by dotted path. */
export function getPath(obj: unknown, path: string): unknown {
  let cur: unknown = obj;
  for (const p of path.split(".")) {
    if (cur == null || typeof cur !== "object") return undefined;
    cur = (cur as Record<string, unknown>)[p];
  }
  return cur;
}

/** Immutably set a nested value by dotted path. */
export function setPath<T extends Record<string, unknown>>(obj: T, path: string, value: unknown): T {
  const [head, ...rest] = path.split(".");
  if (rest.length === 0) return { ...obj, [head]: value };
  const child = (obj[head] ?? {}) as Record<string, unknown>;
  return { ...obj, [head]: setPath(child, rest.join("."), value) };
}
