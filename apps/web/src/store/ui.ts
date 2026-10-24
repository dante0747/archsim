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

export interface UiState {
  /** Animate measured traffic along links after a run. */
  flow: boolean;
  /** The full component library flyout is open. */
  libraryOpen: boolean;
  /** The command palette (Ctrl/Cmd K) is open. */
  paletteOpen: boolean;
  /** Workflow progress the user has seen (drives the step indicator and next-step hint). */
  trafficVisited: boolean;
  insightsSeenFor: string | null;
  dismissedHint: string | null;
  setFlow: (v: boolean) => void;
  setLibraryOpen: (v: boolean) => void;
  setPaletteOpen: (v: boolean) => void;
  markTrafficVisited: () => void;
  markInsightsSeen: (runId: string) => void;
  dismissHint: (key: string) => void;
  resetForProject: () => void;
}

export const useUi = create<UiState>()((set) => ({
  flow: typeof window === "undefined" ? true : readFlag("archsim-flow", true),
  libraryOpen: false,
  paletteOpen: false,
  trafficVisited: false,
  insightsSeenFor: null,
  dismissedHint: null,
  setFlow: (flow) => {
    writeFlag("archsim-flow", flow);
    set({ flow });
  },
  setLibraryOpen: (libraryOpen) => set({ libraryOpen }),
  setPaletteOpen: (paletteOpen) => set({ paletteOpen }),
  markTrafficVisited: () => set({ trafficVisited: true }),
  markInsightsSeen: (insightsSeenFor) => set({ insightsSeenFor }),
  dismissHint: (dismissedHint) => set({ dismissedHint }),
  resetForProject: () => set({ trafficVisited: false, insightsSeenFor: null, dismissedHint: null, libraryOpen: false, paletteOpen: false }),
}));

export function prefersReducedMotion(): boolean {
  return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
