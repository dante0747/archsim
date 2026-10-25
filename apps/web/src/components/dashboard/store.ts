/**
 * Dashboard view state (never saved into the document): zoomed time range, component filter, row
 * collapse overrides, the panel open in fullscreen or inspect, and the shared crosshair. The
 * crosshair lives in its own store so moving the pointer only re-renders the cursor layers.
 */
import { create } from "zustand";
import type { TimeRange } from "./range";

export interface DashboardState {
  /** Zoomed range (null = whole run) and the run it was chosen for: another run starts unzoomed. */
  range: TimeRange | null;
  rangeRunId: string | null;
  /** Components hidden by the variable filter. */
  hidden: string[];
  groupByKind: boolean;
  /** Row collapse chosen by the user, by row id (otherwise each row's default applies). */
  collapsed: Record<string, boolean>;
  fullscreenId: string | null;
  inspectId: string | null;
  setRange: (runId: string | null, range: TimeRange | null) => void;
  setHidden: (ids: string[]) => void;
  toggleNode: (id: string) => void;
  setGroupByKind: (v: boolean) => void;
  setCollapsed: (rowId: string, v: boolean) => void;
  setManyCollapsed: (rowIds: string[], v: boolean) => void;
  setFullscreen: (id: string | null) => void;
  setInspect: (id: string | null) => void;
}

export const useDashboard = create<DashboardState>()((set) => ({
  range: null,
  rangeRunId: null,
  hidden: [],
  groupByKind: false,
  collapsed: {},
  fullscreenId: null,
  inspectId: null,
  setRange: (rangeRunId, range) => set({ rangeRunId, range }),
  setHidden: (hidden) => set({ hidden }),
  toggleNode: (id) => set((s) => ({ hidden: s.hidden.includes(id) ? s.hidden.filter((x) => x !== id) : [...s.hidden, id] })),
  setGroupByKind: (groupByKind) => set({ groupByKind }),
  setCollapsed: (rowId, v) => set((s) => ({ collapsed: { ...s.collapsed, [rowId]: v } })),
  setManyCollapsed: (rowIds, v) => set((s) => ({ collapsed: { ...s.collapsed, ...Object.fromEntries(rowIds.map((id) => [id, v])) } })),
  setFullscreen: (fullscreenId) => set({ fullscreenId }),
  setInspect: (inspectId) => set({ inspectId }),
}));

export interface CursorState {
  /** Simulated second under the pointer (null = no panel hovered). */
  t: number | null;
  /** Panel the pointer is over (it shows the tooltip; the others show the line and legend values). */
  source: string | null;
  /** Pointer y inside the source panel's plot, for placing its tooltip. */
  y: number;
  setCursor: (t: number | null, source: string | null, y?: number) => void;
}

export const useCursor = create<CursorState>()((set) => ({
  t: null,
  source: null,
  y: 0,
  setCursor: (t, source, y = 0) => set({ t, source, y }),
}));
