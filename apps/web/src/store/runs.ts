/**
 * Simulation runs: history, polling of active runs, loaded reports, and A/B comparison.
 * Runs execute on the server; this store only enqueues them and polls their real progress.
 */
import { toast } from "sonner";
import { create } from "zustand";
import { ApiError, api } from "@/lib/api";
import { SWEEP_SCENARIO_PREFIX } from "@/lib/sweep";
import type { ArchitectureDocument, Comparison, Report, Run } from "@/lib/types";

export type DrawerTab = "overview" | "charts" | "components" | "compare" | "runs" | "assumptions";

const POLL_MS = 400;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let pollBusy = false;

export interface RunsState {
  projectId: string | null;
  runs: Run[];
  reports: Record<string, Report>;
  activeRunId: string | null;
  pending: string[];
  followRunId: string | null;
  batchIds: string[] | null;
  compareA: string | null;
  compareB: string | null;
  comparison: Comparison | null;
  comparing: boolean;
  overlay: boolean;
  drawerOpen: boolean;
  drawerTab: DrawerTab;
  drawerHeight: number;
  focusNodeId: string | null;
  starting: boolean;
  /** Scenario chosen in the run control (null = the design as drawn). */
  selectedScenarioId: string | null;
  /** Last run started from the UI that finished successfully (drives replay autoplay). */
  lastFinishedRunId: string | null;

  reset: (projectId: string) => Promise<void>;
  startRun: (doc: ArchitectureDocument, scenarioId: string | null) => Promise<Run | null>;
  startBatch: (doc: ArchitectureDocument, scenarioIds: (string | null)[]) => Promise<void>;
  cancel: (runId: string) => Promise<void>;
  rerun: (runId: string) => Promise<void>;
  remove: (runId: string) => Promise<void>;
  showRun: (runId: string) => Promise<void>;
  setCompare: (a: string | null, b: string | null) => Promise<void>;
  setOverlay: (on: boolean) => void;
  openDrawer: (tab?: DrawerTab) => void;
  setDrawerOpen: (open: boolean) => void;
  setDrawerHeight: (h: number) => void;
  focusNode: (id: string | null) => void;
  selectScenario: (id: string | null) => void;
}

function upsert(runs: Run[], run: Run): Run[] {
  const i = runs.findIndex((r) => r.id === run.id);
  if (i === -1) return [run, ...runs];
  const copy = runs.slice();
  copy[i] = run;
  return copy;
}

function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const first = e.issues.find((i) => i.severity === "error");
    return first ? `${e.message.split(":")[0]}: ${first.message}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

export const useRuns = create<RunsState>()((set, get) => {
  const ensurePolling = () => {
    if (pollTimer) return;
    pollTimer = setInterval(async () => {
      const { pending } = get();
      if (!pending.length) {
        if (pollTimer) clearInterval(pollTimer);
        pollTimer = null;
        return;
      }
      if (pollBusy) return; // previous tick is still waiting on the server
      pollBusy = true;
      try {
        await pollOnce(pending);
      } finally {
        pollBusy = false;
      }
    }, POLL_MS);
  };

  const pollOnce = async (pending: string[]) => {
    for (const id of pending) {
        try {
          const run = await api.getRun(id);
          set((s) => ({ runs: upsert(s.runs, run) }));
          if (run.status === "queued" || run.status === "running") continue;
          set((s) => ({ pending: s.pending.filter((p) => p !== id) }));
          await onFinished(run);
        } catch (e) {
          set((s) => ({ pending: s.pending.filter((p) => p !== id) }));
          toast.error(`Lost track of run: ${errorMessage(e)}`);
        }
      }
  };

  const onFinished = async (run: Run) => {
    const label = run.label ?? "Run";
    if (run.status === "succeeded") {
      if (get().followRunId === run.id) {
        await get().showRun(run.id);
        set({ followRunId: null, lastFinishedRunId: run.id });
      }
      if (run.reproducesRunId) {
        const original = get().runs.find((r) => r.id === run.reproducesRunId);
        if (original?.resultDigest && original.resultDigest === run.resultDigest) {
          toast.success("Reproduced exactly", {
            description: `Same input and seed gave an identical result digest (${run.resultDigest?.slice(0, 10)}).`,
          });
        } else if (original) {
          toast.warning("Reproduction differs", {
            description: "Results differ from the original run (different engine build or platform?).",
          });
        }
      }
    } else if (run.status === "failed") {
      toast.error(`${label} failed`, { description: run.error?.message });
    } else if (run.status === "cancelled") {
      toast.message(`${label} cancelled`);
    }
    const batch = get().batchIds;
    if (batch && batch.includes(run.id)) {
      const runs = get().runs.filter((r) => batch.includes(r.id));
      const done = runs.every((r) => !["queued", "running"].includes(r.status));
      if (done) {
        set({ batchIds: null });
        const ok = batch.filter((id) => get().runs.find((r) => r.id === id)?.status === "succeeded");
        if (ok.length >= 2) {
          await get().setCompare(ok[0], ok[1]);
          set({ drawerOpen: true, drawerTab: "compare" });
          toast.success(`${ok.length} scenario runs finished`, { description: "Comparing the first two." });
        }
      }
    }
  };

  return {
    projectId: null,
    runs: [],
    reports: {},
    activeRunId: null,
    pending: [],
    followRunId: null,
    batchIds: null,
    compareA: null,
    compareB: null,
    comparison: null,
    comparing: false,
    overlay: true,
    drawerOpen: false,
    drawerTab: "overview",
    drawerHeight: 360,
    focusNodeId: null,
    starting: false,
    selectedScenarioId: null,
    lastFinishedRunId: null,

    reset: async (projectId) => {
      set({
        projectId,
        runs: [],
        reports: {},
        activeRunId: null,
        pending: [],
        followRunId: null,
        lastFinishedRunId: null,
        compareA: null,
        compareB: null,
        comparison: null,
      });
      try {
        const runs = await api.listRuns(projectId);
        const active = runs.filter((r) => r.status === "queued" || r.status === "running").map((r) => r.id);
        set({ runs, pending: active });
        if (active.length) ensurePolling();
        // Breaking-point sweep steps are measurements, not designs: never open one by default.
        const latest = runs.find((r) => r.status === "succeeded" && !(r.scenarioId ?? "").startsWith(SWEEP_SCENARIO_PREFIX));
        if (latest) await get().showRun(latest.id);
      } catch (e) {
        toast.error(`Could not load runs: ${errorMessage(e)}`);
      }
    },

    startRun: async (doc, scenarioId) => {
      const projectId = get().projectId;
      if (!projectId) return null;
      set({ starting: true });
      try {
        const run = await api.createRun(projectId, { document: doc, scenarioId });
        set((s) => ({
          runs: upsert(s.runs, run),
          pending: [...s.pending, run.id],
          followRunId: run.id,
          // keep the canvas visible: results appear on it, and "Open insights" opens the panel
          drawerTab: s.drawerTab === "compare" || s.drawerTab === "runs" ? "overview" : s.drawerTab,
        }));
        ensurePolling();
        return run;
      } catch (e) {
        toast.error("Cannot start simulation", { description: errorMessage(e) });
        return null;
      } finally {
        set({ starting: false });
      }
    },

    startBatch: async (doc, scenarioIds) => {
      const projectId = get().projectId;
      if (!projectId) return;
      set({ starting: true });
      try {
        const runs = await api.batchRuns(projectId, scenarioIds, doc);
        set((s) => ({
          runs: runs.reduce(upsert, s.runs),
          pending: [...s.pending, ...runs.map((r) => r.id)],
          batchIds: runs.map((r) => r.id),
          drawerOpen: true,
          drawerTab: "runs",
        }));
        ensurePolling();
      } catch (e) {
        toast.error("Cannot start scenario runs", { description: errorMessage(e) });
      } finally {
        set({ starting: false });
      }
    },

    cancel: async (runId) => {
      try {
        const run = await api.cancelRun(runId);
        set((s) => ({ runs: upsert(s.runs, run) }));
      } catch (e) {
        toast.error(`Cancel failed: ${errorMessage(e)}`);
      }
    },

    rerun: async (runId) => {
      try {
        const run = await api.rerun(runId);
        set((s) => ({ runs: upsert(s.runs, run), pending: [...s.pending, run.id], followRunId: run.id }));
        ensurePolling();
        toast.message("Re-running from the stored snapshot", {
          description: "Same document, scenario and seed. The result digest should match.",
        });
      } catch (e) {
        toast.error(`Could not reproduce: ${errorMessage(e)}`);
      }
    },

    remove: async (runId) => {
      try {
        await api.deleteRun(runId);
        set((s) => {
          const reports = { ...s.reports };
          delete reports[runId];
          return {
            runs: s.runs.filter((r) => r.id !== runId),
            reports,
            activeRunId: s.activeRunId === runId ? null : s.activeRunId,
            compareA: s.compareA === runId ? null : s.compareA,
            compareB: s.compareB === runId ? null : s.compareB,
            comparison: s.compareA === runId || s.compareB === runId ? null : s.comparison,
          };
        });
      } catch (e) {
        toast.error(`Delete failed: ${errorMessage(e)}`);
      }
    },

    showRun: async (runId) => {
      if (!get().reports[runId]) {
        try {
          const report = await api.getReport(runId);
          set((s) => ({ reports: { ...s.reports, [runId]: report } }));
        } catch (e) {
          toast.error(`Could not load report: ${errorMessage(e)}`);
          return;
        }
      }
      set({ activeRunId: runId });
    },

    setCompare: async (a, b) => {
      set({ compareA: a, compareB: b, comparison: null });
      if (!a || !b) return;
      set({ comparing: true });
      try {
        const comparison = await api.compare(a, b);
        if (get().compareA === a && get().compareB === b) set({ comparison });
      } catch (e) {
        toast.error(`Comparison failed: ${errorMessage(e)}`);
      } finally {
        set({ comparing: false });
      }
    },

    setOverlay: (overlay) => set({ overlay }),
    openDrawer: (tab) => set((s) => ({ drawerOpen: true, drawerTab: tab ?? s.drawerTab })),
    setDrawerOpen: (drawerOpen) => set({ drawerOpen }),
    setDrawerHeight: (drawerHeight) => set({ drawerHeight: Math.max(180, Math.min(drawerHeight, 900)) }),
    focusNode: (focusNodeId) => set({ focusNodeId }),
    selectScenario: (selectedScenarioId) => set({ selectedScenarioId }),
  };
});

/** The report currently displayed, if any. */
export function useActiveReport(): Report | null {
  return useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
}
