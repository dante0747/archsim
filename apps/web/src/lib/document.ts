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
}

export type ArchNode = Node<ArchNodeData, "arch">;
export type ArchEdge = Edge<ArchEdgeData, "arch">;

export interface GraphState {
  nodes: ArchNode[];
  edges: ArchEdge[];
  workload: Workload;
  scenarios: Scenario[];
  metadata: DocMetadata;
}

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
  const edges: ArchEdge[] = doc.architecture.edges.map((e) => ({
    id: e.id,
    type: "arch",
    source: e.source,
    target: e.target,
    sourceHandle: e.sourcePort ?? "out",
    targetHandle: e.targetPort ?? "in",
    data: { config: e.config, label: e.label ?? null },
  }));
  return {
    nodes,
    edges,
    workload: { ...DEFAULT_WORKLOAD, ...(doc.workload ?? {}) },
    scenarios: doc.scenarios ?? [],
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
        sourcePort: e.sourceHandle ?? "out",
        target: e.target,
        targetPort: e.targetHandle ?? "in",
        label: e.data?.label ?? null,
        config: e.data?.config ?? defaultEdgeConfigFallback(),
      })),
    },
    workload: state.workload,
    scenarios: state.scenarios,
    layout: {
      nodes: Object.fromEntries(
        state.nodes.map((n) => [n.id, { x: round(n.position.x), y: round(n.position.y) }]),
      ),
      viewport: viewport ? { x: round(viewport.x), y: round(viewport.y), zoom: viewport.zoom } : null,
    },
  };
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
