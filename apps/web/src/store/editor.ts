/**
 * Editor state: the graph being edited, selection, undo/redo history and validation issues.
 * All updates are immutable, so history snapshots can share structure safely.
 */
import {
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type Viewport,
} from "@xyflow/react";
import { create } from "zustand";
import {
  type ArchEdge,
  type ArchNode,
  type GraphState,
  DEFAULT_WORKLOAD,
  defaultEdgeConfig,
  fromDocument,
  setPath,
  toDocument,
  uniqueId,
} from "@/lib/document";
import { checkConnection, componentOf } from "@/lib/graph-rules";
import { layoutGraph } from "@/lib/layout";
import type {
  ArchitectureDocument,
  Catalog,
  DocMetadata,
  EdgeConfig,
  Issue,
  Project,
  Scenario,
  Workload,
} from "@/lib/types";

const HISTORY_LIMIT = 100;
const COALESCE_MS = 1000;

export type RightTab = "inspect" | "workload" | "scenarios";

export interface EditorState extends GraphState {
  projectId: string | null;
  revision: number;
  catalog: Catalog | null;
  initialViewport: Viewport | null;
  dirty: boolean;
  saving: boolean;
  dragging: boolean;
  past: GraphState[];
  future: GraphState[];
  lastHistoryKey: string | null;
  lastHistoryAt: number;
  issues: Issue[];
  validation: "idle" | "pending" | "done" | "error";
  rightTab: RightTab;
  layoutVersion: number;

  load: (project: Project, catalog: Catalog) => void;
  replaceDocument: (doc: ArchitectureDocument) => void;
  pushHistory: (key?: string) => void;
  undo: () => void;
  redo: () => void;
  onNodesChange: (changes: NodeChange<ArchNode>[]) => void;
  onEdgesChange: (changes: EdgeChange<ArchEdge>[]) => void;
  connect: (conn: Connection) => { ok: boolean; reason?: string };
  addNode: (type: string, position: { x: number; y: number }) => string | null;
  updateNode: (id: string, patch: { label?: string; description?: string | null }) => void;
  setNodeConfig: (id: string, key: string, value: unknown) => void;
  setEdgeConfig: (id: string, key: keyof EdgeConfig | string, value: unknown) => void;
  setEdgeLabel: (id: string, label: string | null) => void;
  deleteElements: (nodeIds: string[], edgeIds: string[]) => void;
  duplicateSelection: () => void;
  setWorkload: (patch: Partial<Workload>, key?: string) => void;
  setScenarios: (scenarios: Scenario[], key?: string) => void;
  setMetadata: (patch: Partial<DocMetadata>, key?: string) => void;
  select: (sel: { nodeId?: string | null; edgeId?: string | null }) => void;
  autoLayout: () => Promise<void>;
  setIssues: (issues: Issue[], state: EditorState["validation"]) => void;
  setRightTab: (tab: RightTab) => void;
  setSaving: (saving: boolean) => void;
  markSaved: (revision: number, name?: string) => void;
  toDocument: (viewport?: Viewport | null) => ArchitectureDocument;
}

function snapshot(s: GraphState): GraphState {
  return {
    nodes: s.nodes.map((n) => (n.selected || n.dragging ? { ...n, selected: false, dragging: false } : n)),
    edges: s.edges.map((e) => (e.selected ? { ...e, selected: false } : e)),
    workload: s.workload,
    scenarios: s.scenarios,
    metadata: s.metadata,
  };
}

export const useEditor = create<EditorState>()((set, get) => ({
  projectId: null,
  revision: 0,
  catalog: null,
  nodes: [],
  edges: [],
  workload: DEFAULT_WORKLOAD,
  scenarios: [],
  metadata: { name: "" },
  initialViewport: null,
  dirty: false,
  saving: false,
  dragging: false,
  past: [],
  future: [],
  lastHistoryKey: null,
  lastHistoryAt: 0,
  issues: [],
  validation: "idle",
  rightTab: "inspect",
  layoutVersion: 0,

  load: (project, catalog) => {
    const g = fromDocument(project.document);
    set({
      projectId: project.id,
      revision: project.revision,
      catalog,
      nodes: g.nodes,
      edges: g.edges,
      workload: g.workload,
      scenarios: g.scenarios,
      metadata: { ...g.metadata, name: project.name },
      initialViewport: g.viewport,
      dirty: false,
      past: [],
      future: [],
      issues: [],
      validation: "idle",
      rightTab: "inspect",
    });
  },

  replaceDocument: (doc) => {
    get().pushHistory();
    const g = fromDocument(doc);
    set((s) => ({
      nodes: g.nodes,
      edges: g.edges,
      workload: g.workload,
      scenarios: g.scenarios,
      metadata: { ...g.metadata, name: s.metadata.name },
      dirty: true,
      layoutVersion: s.layoutVersion + 1,
    }));
  },

  pushHistory: (key) => {
    const s = get();
    const now = Date.now();
    if (key && key === s.lastHistoryKey && now - s.lastHistoryAt < COALESCE_MS) {
      set({ lastHistoryAt: now });
      return;
    }
    set({
      past: [...s.past.slice(-(HISTORY_LIMIT - 1)), snapshot(s)],
      future: [],
      lastHistoryKey: key ?? null,
      lastHistoryAt: now,
    });
  },

  undo: () => {
    const s = get();
    const prev = s.past[s.past.length - 1];
    if (!prev) return;
    set({
      ...prev,
      past: s.past.slice(0, -1),
      future: [snapshot(s), ...s.future].slice(0, HISTORY_LIMIT),
      dirty: true,
      lastHistoryKey: null,
    });
  },

  redo: () => {
    const s = get();
    const next = s.future[0];
    if (!next) return;
    set({
      ...next,
      past: [...s.past, snapshot(s)].slice(-HISTORY_LIMIT),
      future: s.future.slice(1),
      dirty: true,
      lastHistoryKey: null,
    });
  },

  onNodesChange: (changes) => {
    const s = get();
    const dragStart = changes.some((c) => c.type === "position" && c.dragging) && !s.dragging;
    const dragEnd = changes.some((c) => c.type === "position" && c.dragging === false);
    const removes = changes.some((c) => c.type === "remove");
    if (dragStart || removes) get().pushHistory();
    const structural = changes.some((c) => c.type === "position" || c.type === "remove" || c.type === "add");
    set((st) => ({
      nodes: applyNodeChanges(changes, st.nodes),
      dragging: dragStart ? true : dragEnd ? false : st.dragging,
      dirty: st.dirty || structural,
    }));
  },

  onEdgesChange: (changes) => {
    const removes = changes.some((c) => c.type === "remove");
    if (removes) get().pushHistory("remove-edges");
    set((st) => ({
      edges: applyEdgeChanges(changes, st.edges),
      dirty: st.dirty || removes,
    }));
  },

  connect: (conn) => {
    const s = get();
    const check = checkConnection(conn, s.nodes, s.edges, s.catalog);
    if (!check.ok) return { ok: false, reason: check.reason };
    get().pushHistory();
    const id = uniqueId(`${conn.source}-${conn.target}`, s.edges.map((e) => e.id));
    const edge: ArchEdge = {
      id,
      type: "arch",
      source: conn.source,
      target: conn.target,
      sourceHandle: check.sourcePort.name,
      targetHandle: check.targetPort.name,
      data: { config: defaultEdgeConfig(s.catalog, check.requestTypes), label: null },
    };
    set((st) => ({ edges: [...st.edges, edge], dirty: true }));
    return { ok: true };
  },

  addNode: (type, position) => {
    const s = get();
    const comp = componentOf(s.catalog, type);
    if (!comp) return null;
    get().pushHistory();
    const id = uniqueId(comp.type.replace(/_/g, "-"), s.nodes.map((n) => n.id));
    const sameType = s.nodes.filter((n) => n.data.type === type).length;
    const baseLabel = comp.displayName.split(" / ")[0].replace(/ \(.*\)$/, "");
    const node: ArchNode = {
      id,
      type: "arch",
      position,
      selected: true,
      data: {
        type,
        label: sameType ? `${baseLabel} ${sameType + 1}` : baseLabel,
        description: null,
        config: structuredClone(comp.defaultConfig),
      },
    };
    set((st) => ({
      nodes: [...st.nodes.map((n) => (n.selected ? { ...n, selected: false } : n)), node],
      edges: st.edges.map((e) => (e.selected ? { ...e, selected: false } : e)),
      dirty: true,
      rightTab: "inspect",
    }));
    return id;
  },

  updateNode: (id, patch) => {
    get().pushHistory(`node:${id}:meta`);
    set((st) => ({
      nodes: st.nodes.map((n) => (n.id === id ? { ...n, data: { ...n.data, ...patch } } : n)),
      dirty: true,
    }));
  },

  setNodeConfig: (id, key, value) => {
    get().pushHistory(`node:${id}:${key}`);
    set((st) => ({
      nodes: st.nodes.map((n) =>
        n.id === id ? { ...n, data: { ...n.data, config: setPath(n.data.config, key, value) } } : n,
      ),
      dirty: true,
    }));
  },

  setEdgeConfig: (id, key, value) => {
    get().pushHistory(`edge:${id}:${key}`);
    set((st) => ({
      edges: st.edges.map((e) =>
        e.id === id && e.data
          ? {
              ...e,
              data: {
                ...e.data,
                config: setPath(e.data.config as unknown as Record<string, unknown>, key, value) as unknown as EdgeConfig,
              },
            }
          : e,
      ),
      dirty: true,
    }));
  },

  setEdgeLabel: (id, label) => {
    get().pushHistory(`edge:${id}:label`);
    set((st) => ({
      edges: st.edges.map((e) => (e.id === id && e.data ? { ...e, data: { ...e.data, label } } : e)),
      dirty: true,
    }));
  },

  deleteElements: (nodeIds, edgeIds) => {
    if (!nodeIds.length && !edgeIds.length) return;
    get().pushHistory();
    const ns = new Set(nodeIds);
    const es = new Set(edgeIds);
    set((st) => ({
      nodes: st.nodes.filter((n) => !ns.has(n.id)),
      edges: st.edges.filter((e) => !es.has(e.id) && !ns.has(e.source) && !ns.has(e.target)),
      dirty: true,
    }));
  },

  duplicateSelection: () => {
    const s = get();
    const selected = s.nodes.filter((n) => n.selected);
    if (!selected.length) return;
    get().pushHistory();
    const taken = s.nodes.map((n) => n.id);
    const mapping = new Map<string, string>();
    const copies: ArchNode[] = selected.map((n) => {
      const id = uniqueId(n.id, [...taken, ...mapping.values()]);
      mapping.set(n.id, id);
      return {
        ...n,
        id,
        selected: true,
        position: { x: n.position.x + 40, y: n.position.y + 40 },
        data: { ...n.data, label: `${n.data.label} copy`, config: structuredClone(n.data.config) },
      };
    });
    const edgeIds = s.edges.map((e) => e.id);
    const copiedEdges: ArchEdge[] = s.edges
      .filter((e) => mapping.has(e.source) && mapping.has(e.target))
      .map((e) => {
        const id = uniqueId(`${mapping.get(e.source)}-${mapping.get(e.target)}`, edgeIds);
        edgeIds.push(id);
        return {
          ...e,
          id,
          source: mapping.get(e.source)!,
          target: mapping.get(e.target)!,
          selected: false,
          data: e.data ? { ...e.data, config: structuredClone(e.data.config) } : e.data,
        };
      });
    set((st) => ({
      nodes: [...st.nodes.map((n) => ({ ...n, selected: false })), ...copies],
      edges: [...st.edges, ...copiedEdges],
      dirty: true,
    }));
  },

  setWorkload: (patch, key) => {
    get().pushHistory(key ? `workload:${key}` : undefined);
    set((st) => ({ workload: { ...st.workload, ...patch }, dirty: true }));
  },

  setScenarios: (scenarios, key) => {
    get().pushHistory(key ? `scenarios:${key}` : undefined);
    set({ scenarios, dirty: true });
  },

  setMetadata: (patch, key) => {
    get().pushHistory(key ? `meta:${key}` : undefined);
    set((st) => ({ metadata: { ...st.metadata, ...patch }, dirty: true }));
  },

  select: ({ nodeId = null, edgeId = null }) => {
    set((st) => ({
      nodes: st.nodes.map((n) => {
        const sel = n.id === nodeId;
        return n.selected === sel ? n : { ...n, selected: sel };
      }),
      edges: st.edges.map((e) => {
        const sel = e.id === edgeId;
        return e.selected === sel ? e : { ...e, selected: sel };
      }),
      rightTab: nodeId || edgeId ? "inspect" : st.rightTab,
    }));
  },

  autoLayout: async () => {
    const s = get();
    if (!s.nodes.length) return;
    const positions = await layoutGraph(s.nodes, s.edges);
    get().pushHistory();
    set((st) => ({
      nodes: st.nodes.map((n) => (positions[n.id] ? { ...n, position: positions[n.id] } : n)),
      dirty: true,
      layoutVersion: st.layoutVersion + 1,
    }));
  },

  setIssues: (issues, validation) => set({ issues, validation }),
  setRightTab: (rightTab) => set({ rightTab }),
  setSaving: (saving) => set({ saving }),
  markSaved: (revision, name) =>
    set((st) => ({
      revision,
      dirty: false,
      saving: false,
      metadata: name ? { ...st.metadata, name } : st.metadata,
    })),
  toDocument: (viewport) => toDocument(get(), viewport),
}));

/** Stable key of the simulation-relevant document content (excludes layout and selection). */
export function contentKey(s: Pick<EditorState, "nodes" | "edges" | "workload" | "scenarios">): string {
  return JSON.stringify({
    n: s.nodes.map((n) => [n.id, n.data.type, n.data.config]),
    e: s.edges.map((e) => [e.id, e.source, e.target, e.sourceHandle, e.targetHandle, e.data?.config]),
    w: s.workload,
    s: s.scenarios,
  });
}
