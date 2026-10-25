/**
 * Conversion between the versioned document (what is saved, exported and simulated) and the
 * React Flow graph state the editor manipulates. Layout lives only in `layout`, never in the
 * topology, so moving a node never changes simulation input.
 */
import type { Edge, Node, Viewport } from "@xyflow/react";
import type {
  ArchitectureDocument,
  Catalog,
  DocMetadata,
  EdgeConfig,
  NodeConfig,
  Objectives,
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
    viewport: doc.layout?.viewport ?? null,
  };
}

export function toDocument(state: GraphState, viewport?: Viewport | null): ArchitectureDocument {
  const round = (v: number) => Math.round(v * 10) / 10;
  return {
    schemaVersion: "1.0",
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
