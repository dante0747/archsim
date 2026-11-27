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
  DEFAULT_OBJECTIVES,
  DEFAULT_WORKLOAD,
  defaultEdgeConfig,
  fromDocument,
  setPath,
  sideHandle,
  sideOf,
  toDocument,
  uniqueId,
  withoutProvenance,
} from "@/lib/document";
import { checkConnection, componentOf } from "@/lib/graph-rules";
import { layoutGraph } from "@/lib/layout";
import type {
  Analysis,
  ArchitectureDocument,
  Catalog,
  DocMetadata,
  EdgeConfig,
  Issue,
  Objectives,
  Preset,
  Pricing,
  Project,
  Scenario,
  Workload,
} from "@/lib/types";

const HISTORY_LIMIT = 100;
const COALESCE_MS = 1000;

export type RightTab = "inspect" | "workload" | "scenarios";

/** What a save sent: which opening of the project it belongs to, and the design as it was then. */
export interface SentDesign {
  loadId: number;
  content: string;
}

export interface EditorState extends GraphState {
  projectId: string | null;
  revision: number;
  /** Counts the projects opened here: the answer to a save made before the last one is not this design's. */
  loadId: number;
  catalog: Catalog | null;
  initialViewport: Viewport | null;
  dirty: boolean;
  saving: boolean;
  /** The project was saved elsewhere since this editor opened it: a save from here would replace that version. */
  conflict: boolean;
  dragging: boolean;
  past: GraphState[];
  future: GraphState[];
  lastHistoryKey: string | null;
  lastHistoryAt: number;
  issues: Issue[];
  validation: "idle" | "pending" | "done" | "error";
  /** The engine's analysis of the current design (sizes, cost, review); refreshed with validation. */
  analysis: Analysis | null;
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
  /** Add a node configured from a technology preset (preset config merged over the defaults). */
  addPresetNode: (type: string, position: { x: number; y: number }, preset: Preset) => string | null;
  updateNode: (id: string, patch: { label?: string; description?: string | null }) => void;
  setNodeConfig: (id: string, key: string, value: unknown) => void;
  setEdgeConfig: (id: string, key: keyof EdgeConfig | string, value: unknown) => void;
  setEdgeLabel: (id: string, label: string | null) => void;
  deleteElements: (nodeIds: string[], edgeIds: string[]) => void;
  duplicateSelection: () => void;
  setWorkload: (patch: Partial<Workload>, key?: string) => void;
  setScenarios: (scenarios: Scenario[], key?: string) => void;
  setObjectives: (patch: Partial<Objectives>, key?: string) => void;
  /** Where prices come from (null: generic illustrative rates). */
  setPricing: (pricing: Pricing | null) => void;
  /** The design's vCPU sibling speed, architecture.siblingSpeed (null: each part's instance family decides). */
  setSiblingSpeed: (siblingSpeed: number | null) => void;
  setMetadata: (patch: Partial<DocMetadata>, key?: string) => void;
  select: (sel: { nodeId?: string | null; edgeId?: string | null }) => void;
  autoLayout: () => Promise<void>;
  setIssues: (issues: Issue[], state: EditorState["validation"]) => void;
  setAnalysis: (analysis: Analysis | null) => void;
  setRightTab: (tab: RightTab) => void;
  /** A save starts: what it sends, to tell when its answer comes whether the design was edited meanwhile. */
  beginSave: () => SentDesign;
  /** A save was answered: the design is saved only if it is still what the save sent. */
  markSaved: (sent: SentDesign, saved: Pick<Project, "revision" | "name">) => void;
  /** A save was refused; `conflict`: because the project was saved elsewhere first. */
  saveFailed: (sent: SentDesign, conflict: boolean) => void;
  /** Replaces the design with the version saved elsewhere, as one undoable step: undo brings this one back. */
  takeTheirs: (project: Project) => void;
  toDocument: (viewport?: Viewport | null) => ArchitectureDocument;
}

/** A design as a save sends it, as text; the viewport is left out, as panning is not an edit. */
function savedContent(s: GraphState): string {
  return JSON.stringify(toDocument(s));
}

function snapshot(s: GraphState): GraphState {
  return {
    nodes: s.nodes.map((n) => (n.selected || n.dragging ? { ...n, selected: false, dragging: false } : n)),
    edges: s.edges.map((e) => (e.selected ? { ...e, selected: false } : e)),
    workload: s.workload,
    scenarios: s.scenarios,
    objectives: s.objectives,
    metadata: s.metadata,
    extras: s.extras,
  };
}

/** Links from a client start with that client's typical network (e.g. a mobile network's latency). */
function linkDefaultsFrom(s: Pick<EditorState, "catalog" | "nodes">, sourceId: string): Partial<EdgeConfig> {
  const type = s.nodes.find((n) => n.id === sourceId)?.data.type;
  return structuredClone(componentOf(s.catalog, type ?? "")?.linkDefaults ?? {});
}

export const useEditor = create<EditorState>()((set, get) => ({
  projectId: null,
  revision: 0,
  loadId: 0,
  catalog: null,
  nodes: [],
  edges: [],
  workload: DEFAULT_WORKLOAD,
  scenarios: [],
  objectives: DEFAULT_OBJECTIVES,
  metadata: { name: "" },
  initialViewport: null,
  dirty: false,
  saving: false,
  conflict: false,
  dragging: false,
  past: [],
  future: [],
  lastHistoryKey: null,
  lastHistoryAt: 0,
  issues: [],
  validation: "idle",
  analysis: null,
  rightTab: "inspect",
  layoutVersion: 0,

  load: (project, catalog) => {
    const g = fromDocument(project.document);
    set((s) => ({
      projectId: project.id,
      revision: project.revision,
      loadId: s.loadId + 1,
      catalog,
      nodes: g.nodes,
      edges: g.edges,
      workload: g.workload,
      scenarios: g.scenarios,
      objectives: g.objectives,
      metadata: { ...g.metadata, name: project.name },
      extras: g.extras,
      initialViewport: g.viewport,
      dirty: false,
      saving: false,
      conflict: false,
      past: [],
      future: [],
      issues: [],
      validation: "idle",
      analysis: null,
      rightTab: "inspect",
    }));
  },

  replaceDocument: (doc) => {
    get().pushHistory();
    const g = fromDocument(doc);
    set((s) => ({
      nodes: g.nodes,
      edges: g.edges,
      workload: g.workload,
      scenarios: g.scenarios,
      objectives: g.objectives,
      metadata: { ...g.metadata, name: s.metadata.name },
      extras: g.extras,
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
      sourceHandle: sideOf(conn.sourceHandle) ? conn.sourceHandle : sideHandle("right"),
      targetHandle: sideOf(conn.targetHandle) ? conn.targetHandle : sideHandle("left"),
      data: {
        config: { ...defaultEdgeConfig(s.catalog, check.requestTypes), ...linkDefaultsFrom(s, conn.source) },
        label: null,
        sourcePort: check.sourcePort.name,
        targetPort: check.targetPort.name,
      },
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

  addPresetNode: (type, position, preset) => {
    const id = get().addNode(type, position);
    if (!id) return null;
    const labels = new Set(get().nodes.filter((n) => n.id !== id).map((n) => n.data.label));
    let label = preset.name;
    for (let i = 2; labels.has(label); i++) label = `${preset.name} ${i}`;
    set((st) => ({
      nodes: st.nodes.map((n) =>
        n.id === id ? { ...n, data: { ...n.data, label, config: { ...n.data.config, ...structuredClone(preset.config) } } } : n,
      ),
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
      extras: withoutProvenance(st.extras, [`nodes/${id}/${key.split(".")[0]}`]),
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
      extras: withoutProvenance(st.extras, [`edges/${id}/${String(key).split(".")[0]}`]),
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
      extras: withoutProvenance(st.extras, [], [...nodeIds.map((id) => `nodes/${id}/`), ...edgeIds.map((id) => `edges/${id}/`)]),
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

  setObjectives: (patch, key) => {
    get().pushHistory(key ? `objectives:${key}` : undefined);
    set((st) => ({ objectives: { ...st.objectives, ...patch }, dirty: true }));
  },

  setPricing: (pricing) => {
    get().pushHistory();
    set((st) => ({
      extras: { provenance: {}, calibration: null, ...st.extras, pricing },
      dirty: true,
    }));
  },

  setSiblingSpeed: (siblingSpeed) => {
    get().pushHistory("architecture:siblingSpeed");
    set((st) => ({
      extras: { provenance: {}, calibration: null, ...st.extras, siblingSpeed },
      dirty: true,
    }));
  },

  setWorkload: (patch, key) => {
    get().pushHistory(key ? `workload:${key}` : undefined);
    set((st) => ({
      workload: { ...st.workload, ...patch },
      extras: withoutProvenance(st.extras, Object.keys(patch).map((k) => `workload/${k}`)),
      dirty: true,
    }));
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
  setAnalysis: (analysis) => set({ analysis }),
  setRightTab: (rightTab) => set({ rightTab }),

  beginSave: () => {
    set({ saving: true });
    return { loadId: get().loadId, content: savedContent(get()) };
  },

  markSaved: (sent, saved) =>
    set((st) => {
      if (st.loadId !== sent.loadId) return {};
      const unchanged = savedContent(st) === sent.content;
      return {
        revision: saved.revision,
        saving: false,
        conflict: false,
        dirty: !unchanged,
        // the API's name is the saved one (trimmed, never empty); a name retyped meanwhile is the newer one
        metadata: unchanged && saved.name !== st.metadata.name ? { ...st.metadata, name: saved.name } : st.metadata,
      };
    }),

  saveFailed: (sent, conflict) =>
    set((st) => (st.loadId === sent.loadId ? { saving: false, conflict: st.conflict || conflict } : {})),

  takeTheirs: (project) => {
    get().pushHistory();
    const g = fromDocument(project.document);
    set((s) => ({
      revision: project.revision,
      nodes: g.nodes,
      edges: g.edges,
      workload: g.workload,
      scenarios: g.scenarios,
      objectives: g.objectives,
      metadata: { ...g.metadata, name: project.name },
      extras: g.extras,
      dirty: false,
      conflict: false,
      layoutVersion: s.layoutVersion + 1,
    }));
  },

  toDocument: (viewport) => toDocument(get(), viewport),
}));

/** Stable key of the simulation-relevant document content (excludes layout and selection). */
export function contentKey(s: Pick<EditorState, "nodes" | "edges" | "workload" | "scenarios"> & { extras?: EditorState["extras"] }): string {
  return JSON.stringify({
    n: s.nodes.map((n) => [n.id, n.data.type, n.data.config]),
    e: s.edges.map((e) => [e.id, e.source, e.target, e.sourceHandle, e.targetHandle, e.data?.config]),
    w: s.workload,
    s: s.scenarios,
    p: s.extras?.pricing ?? null,
    ss: s.extras?.siblingSpeed ?? null,
  });
}
