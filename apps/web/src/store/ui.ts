/** Presentation-only editor state (never saved into the document). */
import { create } from "zustand";

function readFlag(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : v === "1";
  } catch {
    return fallback;
  }
}

function writeFlag(key: string, v: boolean) {
  try {
    localStorage.setItem(key, v ? "1" : "0");
  } catch {
    /* storage unavailable */
  }
}

/** The workspaces of the editor, shown as Design, Test, Results and Dashboard. */
export type Workspace = "build" | "stress" | "decide" | "dashboard";

/** A move away from the editor that waits for the person: stay, or leave without the unsaved work. */
export interface LeaveHeld {
  /** Why the design is not saved. */
  reason: string;
  /** The project was saved elsewhere, and the person has not chosen which version to keep. */
  conflict: boolean;
  /** Makes the move. */
  go: () => void;
}

export interface UiState {
  /** Current workspace. */
  mode: Workspace;
  /** Flight recorder: simulated second shown on the canvas (null = whole-run summary). */
  playhead: number | null;
  playing: boolean;
  /** Playback speed in simulated seconds per real second. */
  speed: number;
  /** Animate measured traffic along links after a run. */
  flow: boolean;
  /** Offer experimental ("Labs") technologies when adding parts. */
  labs: boolean;
  /** The command palette (Ctrl/Cmd K) is open. */
  paletteOpen: boolean;
  /** The export dialog (docker-compose, Kubernetes, Mermaid) is open. */
  exportOpen: boolean;
  /** The calibration dialog (Prometheus, OpenTelemetry) is open. */
  calibrateOpen: boolean;
  /** The import dialog (docker-compose, Kubernetes, Terraform) is open. */
  importOpen: boolean;
  /** The guided tour of the editor is showing. */
  tourOpen: boolean;
  /** The choices that end a save conflict (reload theirs, overwrite, save a copy) are showing. */
  saveConflictOpen: boolean;
  /** A move away from the editor held back because the design could not be saved: why, and the move. */
  leaveHeld: LeaveHeld | null;
  /** Workflow progress the user has seen (drives the step indicator and next-step hint). */
  trafficVisited: boolean;
  insightsSeenFor: string | null;
  dismissedHint: string | null;
  setFlow: (v: boolean) => void;
  setLabs: (v: boolean) => void;
  setMode: (m: Workspace) => void;
  setPlayhead: (t: number | null) => void;
  setPlaying: (v: boolean) => void;
  setSpeed: (v: number) => void;
  setPaletteOpen: (v: boolean) => void;
  setExportOpen: (v: boolean) => void;
  setCalibrateOpen: (v: boolean) => void;
  setImportOpen: (v: boolean) => void;
  setTourOpen: (v: boolean) => void;
  setSaveConflictOpen: (v: boolean) => void;
  setLeaveHeld: (v: LeaveHeld | null) => void;
  markTrafficVisited: () => void;
  markInsightsSeen: (runId: string) => void;
  dismissHint: (key: string) => void;
  resetForProject: () => void;
}

export const useUi = create<UiState>()((set) => ({
  mode: "build",
  playhead: null,
  playing: false,
  speed: 4,
  flow: typeof window === "undefined" ? true : readFlag("archsim-flow", true),
  labs: typeof window === "undefined" ? false : readFlag("archsim-labs", false),
  paletteOpen: false,
  exportOpen: false,
  calibrateOpen: false,
  importOpen: false,
  tourOpen: false,
  saveConflictOpen: false,
  leaveHeld: null,
  trafficVisited: false,
  insightsSeenFor: null,
  dismissedHint: null,
  setFlow: (flow) => {
    writeFlag("archsim-flow", flow);
    set({ flow });
  },
  setLabs: (labs) => {
    writeFlag("archsim-labs", labs);
    set({ labs });
  },
  setMode: (mode) => set({ mode }),
  setPlayhead: (playhead) => set({ playhead }),
  setPlaying: (playing) => set({ playing }),
  setSpeed: (speed) => set({ speed }),
  setPaletteOpen: (paletteOpen) => set({ paletteOpen }),
  setExportOpen: (exportOpen) => set({ exportOpen }),
  setCalibrateOpen: (calibrateOpen) => set({ calibrateOpen }),
  setImportOpen: (importOpen) => set({ importOpen }),
  setTourOpen: (tourOpen) => set({ tourOpen }),
  setSaveConflictOpen: (saveConflictOpen) => set({ saveConflictOpen }),
  setLeaveHeld: (leaveHeld) => set({ leaveHeld }),
  markTrafficVisited: () => set({ trafficVisited: true }),
  markInsightsSeen: (insightsSeenFor) => set({ insightsSeenFor }),
  dismissHint: (dismissedHint) => set({ dismissedHint }),
  resetForProject: () =>
    set({
      trafficVisited: false,
      insightsSeenFor: null,
      dismissedHint: null,
      paletteOpen: false,
      saveConflictOpen: false,
      leaveHeld: null,
      mode: "build",
      playhead: null,
      playing: false,
    }),
}));

export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
