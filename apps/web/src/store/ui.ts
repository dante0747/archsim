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

/** The three workspaces of the editor, shown as Design, Test and Results. */
export type Workspace = "build" | "stress" | "decide";

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
  /** The command palette (Ctrl/Cmd K) is open. */
  paletteOpen: boolean;
  /** The export dialog (docker-compose, Kubernetes, Mermaid) is open. */
  exportOpen: boolean;
  /** The guided tour of the editor is showing. */
  tourOpen: boolean;
  /** Workflow progress the user has seen (drives the step indicator and next-step hint). */
  trafficVisited: boolean;
  insightsSeenFor: string | null;
  dismissedHint: string | null;
  setFlow: (v: boolean) => void;
  setMode: (m: Workspace) => void;
  setPlayhead: (t: number | null) => void;
  setPlaying: (v: boolean) => void;
  setSpeed: (v: number) => void;
  setPaletteOpen: (v: boolean) => void;
  setExportOpen: (v: boolean) => void;
  setTourOpen: (v: boolean) => void;
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
  paletteOpen: false,
  exportOpen: false,
  tourOpen: false,
  trafficVisited: false,
  insightsSeenFor: null,
  dismissedHint: null,
  setFlow: (flow) => {
    writeFlag("archsim-flow", flow);
    set({ flow });
  },
  setMode: (mode) => set({ mode }),
  setPlayhead: (playhead) => set({ playhead }),
  setPlaying: (playing) => set({ playing }),
  setSpeed: (speed) => set({ speed }),
  setPaletteOpen: (paletteOpen) => set({ paletteOpen }),
  setExportOpen: (exportOpen) => set({ exportOpen }),
  setTourOpen: (tourOpen) => set({ tourOpen }),
  markTrafficVisited: () => set({ trafficVisited: true }),
  markInsightsSeen: (insightsSeenFor) => set({ insightsSeenFor }),
  dismissHint: (dismissedHint) => set({ dismissedHint }),
  resetForProject: () => set({ trafficVisited: false, insightsSeenFor: null, dismissedHint: null, paletteOpen: false, mode: "build", playhead: null, playing: false }),
}));

export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
