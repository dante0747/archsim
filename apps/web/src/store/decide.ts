/**
 * Decide workspace state: which section is open (shared so the rest of the app can deep-link),
 * which run the scorecard shows, and the breaking-point sweep runner.
 */
import { toast } from "sonner";
import { create } from "zustand";
import { ApiError, api } from "@/lib/api";
import { type SweepConfig, type SweepRow, buildSweepDocument, sweepRow } from "@/lib/sweep";
import type { ArchitectureDocument, Report, Run } from "@/lib/types";
import { type DrawerTab, useRuns } from "@/store/runs";

/** Results workspace: four places plus the report. */
export type DecideSection = "answer" | "compare" | "breaking" | "details" | "report";
export type CompareView = "map" | "pair" | "runs";
export type DetailsView = "summary" | "time" | "components" | "calculations" | "traces" | "assumptions";

/** Where a request to "open results" (the old drawer tabs) lands in the Results workspace. */
export function placeForDrawerTab(tab: DrawerTab): { section: DecideSection; compare?: CompareView; details?: DetailsView } {
  switch (tab) {
    case "overview":
      return { section: "details", details: "summary" };
    case "charts":
      return { section: "details", details: "time" };
    case "components":
      return { section: "details", details: "components" };
    case "assumptions":
      return { section: "details", details: "assumptions" };
    case "compare":
      return { section: "compare", compare: "pair" };
    case "runs":
      return { section: "compare", compare: "runs" };
  }
}

export type SweepStatus = "idle" | "starting" | "running" | "done" | "failed" | "cancelled";

export interface SweepState {
  status: SweepStatus;
  projectId: string | null;
  config: SweepConfig | null;
  levels: number[];
  runIds: string[];
  /** One row per level; filled in as steps finish. */
  rows: SweepRow[];
  /** Per step: finished with any outcome. */
  doneSteps: boolean[];
  /** Steps finished (any outcome). */
  finished: number;
  error: string | null;
}

const IDLE_SWEEP: SweepState = {
  status: "idle",
  projectId: null,
  config: null,
  levels: [],
  runIds: [],
  rows: [],
  doneSteps: [],
  finished: 0,
  error: null,
};

export interface DecideState {
  section: DecideSection;
  compareView: CompareView;
  detailsView: DetailsView;
  /** Run shown by the scorecard (null = the run currently on the canvas). */
  scorecardRunId: string | null;
  sweep: SweepState;
  setSection: (s: DecideSection) => void;
  setCompareView: (v: CompareView) => void;
  setDetailsView: (v: DetailsView) => void;
  /** Open the place an old "results drawer" tab maps to. */
  goToDrawerTab: (tab: DrawerTab) => void;
  setScorecardRunId: (id: string | null) => void;
  startSweep: (doc: ArchitectureDocument, cfg: SweepConfig) => Promise<void>;
  cancelSweep: () => Promise<void>;
  clearSweep: () => void;
}

const POLL_MS = 600;
let sweepTimer: ReturnType<typeof setTimeout> | null = null;

function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const first = e.issues.find((i) => i.severity === "error");
    return first ? `${e.message.split(":")[0]}: ${first.message}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

/** Share runs and reports with the runs store so history, compare and the canvas can use them. */
function shareRun(run: Run) {
  useRuns.setState((s) => {
    const i = s.runs.findIndex((r) => r.id === run.id);
    if (i === -1) return { runs: [run, ...s.runs] };
    const runs = s.runs.slice();
    runs[i] = run;
    return { runs };
  });
}

function shareReport(runId: string, report: Report) {
  useRuns.setState((s) => ({ reports: { ...s.reports, [runId]: report } }));
}

/** Load (and cache in the runs store) the reports of the given runs. */
export async function ensureReports(runIds: string[]): Promise<void> {
  const missing = runIds.filter((id) => !useRuns.getState().reports[id]);
  for (let i = 0; i < missing.length; i += 4) {
    await Promise.all(
      missing.slice(i, i + 4).map(async (id) => {
        try {
          shareReport(id, await api.getReport(id));
        } catch {
          /* a missing report only removes that point */
        }
      }),
    );
  }
}

export const useDecide = create<DecideState>()((set, get) => {
  const poll = async () => {
    sweepTimer = null;
    const sw = get().sweep;
    if (sw.status !== "running") return;
    const rows = sw.rows.slice();
    const doneSteps = sw.doneSteps.slice();
    for (let i = 0; i < sw.runIds.length; i++) {
      const id = sw.runIds[i];
      if (doneSteps[i]) continue;
      try {
        const run = await api.getRun(id);
        shareRun(run);
        if (run.status === "queued" || run.status === "running") continue;
        doneSteps[i] = true;
        if (run.status === "succeeded") {
          const report = useRuns.getState().reports[id] ?? (await api.getReport(id));
          shareReport(id, report);
          rows[i] = sweepRow(sw.levels[i], report, id);
        } else {
          rows[i] = { ...rows[i], runId: id };
        }
      } catch (e) {
        doneSteps[i] = true;
        rows[i] = { ...rows[i], runId: id };
        set((s) => ({ sweep: { ...s.sweep, error: errorMessage(e) } }));
      }
    }
    if (get().sweep.status !== "running" || get().sweep.runIds !== sw.runIds) return;
    const finished = doneSteps.filter(Boolean).length;
    const done = finished >= sw.runIds.length;
    set((s) => ({ sweep: { ...s.sweep, rows, doneSteps, finished, status: done ? "done" : "running" } }));
    if (done) {
      const ok = rows.filter((r) => r.throughput != null).length;
      if (ok) toast.success("Breaking-point sweep finished", { description: `${ok} of ${rows.length} steps completed.` });
      else toast.error("Breaking-point sweep failed", { description: "No step completed." });
      return;
    }
    sweepTimer = setTimeout(() => void poll(), POLL_MS);
  };

  return {
    section: "answer",
    compareView: "map",
    detailsView: "summary",
    scorecardRunId: null,
    sweep: IDLE_SWEEP,
    setSection: (section) => set({ section }),
    setCompareView: (compareView) => set({ compareView, section: "compare" }),
    setDetailsView: (detailsView) => set({ detailsView, section: "details" }),
    goToDrawerTab: (tab) => {
      const p = placeForDrawerTab(tab);
      set({ section: p.section, ...(p.compare ? { compareView: p.compare } : {}), ...(p.details ? { detailsView: p.details } : {}) });
    },
    setScorecardRunId: (scorecardRunId) => set({ scorecardRunId }),

    startSweep: async (doc, cfg) => {
      const projectId = useRuns.getState().projectId;
      if (!projectId) return;
      if (sweepTimer) clearTimeout(sweepTimer);
      const { document, scenarioIds, levels } = buildSweepDocument(doc, cfg);
      set({
        sweep: {
          ...IDLE_SWEEP,
          status: "starting",
          projectId,
          config: cfg,
          levels,
          rows: levels.map((rps) => sweepRow(rps, null)),
          doneSteps: levels.map(() => false),
        },
      });
      try {
        const runs = await api.batchRuns(projectId, scenarioIds, document);
        runs.forEach(shareRun);
        set((s) => ({ sweep: { ...s.sweep, status: "running", runIds: runs.map((r) => r.id) } }));
        sweepTimer = setTimeout(() => void poll(), POLL_MS);
      } catch (e) {
        const message = errorMessage(e);
        set((s) => ({ sweep: { ...s.sweep, status: "failed", error: message } }));
        toast.error("Cannot start the sweep", { description: message });
      }
    },

    cancelSweep: async () => {
      const sw = get().sweep;
      if (sweepTimer) clearTimeout(sweepTimer);
      sweepTimer = null;
      set((s) => ({ sweep: { ...s.sweep, status: "cancelled" } }));
      await Promise.all(
        sw.runIds.map(async (id, i) => {
          if (sw.doneSteps[i]) return;
          try {
            shareRun(await api.cancelRun(id));
          } catch {
            /* already finished */
          }
        }),
      );
    },

    clearSweep: () => {
      if (sweepTimer) clearTimeout(sweepTimer);
      sweepTimer = null;
      set({ sweep: IDLE_SWEEP });
    },
  };
});
