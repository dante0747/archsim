"use client";

import { ReactFlowProvider } from "@xyflow/react";
import { LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { DesignReviewPanel } from "@/components/build/DesignReview";
import { ExportDialog } from "@/components/build/ExportDialog";
import { DecideWorkspace } from "@/components/decide/DecideWorkspace";
import { useEditorActions } from "@/components/editor/actions";
import { Canvas } from "@/components/editor/Canvas";
import { CommandPalette } from "@/components/editor/CommandPalette";
import { GuidedTour, hasSeenTour } from "@/components/editor/GuidedTour";
import { Inspector } from "@/components/editor/Inspector";
import { PartsPanel } from "@/components/editor/Palette";
import { TopBar } from "@/components/editor/TopBar";
import { NextStepPill } from "@/components/editor/WorkspaceBar";
import { openDecide } from "@/components/editor/workflow";
import { ExperimentPanel } from "@/components/stress/ExperimentPanel";
import { PlaybackClock, TimelineDock } from "@/components/timeline/TimelineDock";
import { Button } from "@/components/ui/button";
import { TooltipProvider } from "@/components/ui/misc";
import { ApiError, api } from "@/lib/api";
import { isEditableTarget } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { contentKey, useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { type Workspace, useUi } from "@/store/ui";

export function EditorApp({ projectId }: { projectId: string }) {
  const [state, setState] = useState<{ kind: "loading" } | { kind: "ready" } | { kind: "error"; message: string }>({
    kind: "loading",
  });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [project, catalog] = await Promise.all([api.getProject(projectId), api.components()]);
        if (cancelled) return;
        useEditor.getState().load(project, catalog);
        useUi.getState().resetForProject();
        void useRuns.getState().reset(projectId);
        setState({ kind: "ready" });
      } catch (e) {
        if (cancelled) return;
        const message =
          e instanceof ApiError && e.status === 404 ? "This project does not exist (it may have been deleted)." : e instanceof Error ? e.message : String(e);
        setState({ kind: "error", message });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  if (state.kind === "loading") {
    return (
      <div className="flex h-full items-center justify-center gap-2 font-mono text-xs text-ink-2" role="status">
        <LoaderCircle className="size-4 animate-spin" /> loading project…
      </div>
    );
  }
  if (state.kind === "error") {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
        <p className="text-sm font-medium text-ink">Could not open the project</p>
        <p className="max-w-md text-sm text-ink-2">{state.message}</p>
        <Button asChild>
          <Link href="/">Back to projects</Link>
        </Button>
      </div>
    );
  }
  return (
    <ReactFlowProvider>
      <TooltipProvider>
        <EditorShell />
      </TooltipProvider>
    </ReactFlowProvider>
  );
}

function useLiveValidation() {
  const lastKey = useRef<string | null>(null);
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    let seq = 0;
    const schedule = () => {
      const s = useEditor.getState();
      const key = contentKey(s);
      if (key === lastKey.current) return;
      lastKey.current = key;
      if (timer) clearTimeout(timer);
      useEditor.getState().setIssues(s.issues, "pending");
      timer = setTimeout(async () => {
        const mySeq = ++seq;
        try {
          const res = await api.validate(useEditor.getState().toDocument());
          if (mySeq === seq) useEditor.getState().setIssues(res.issues, "done");
        } catch {
          if (mySeq === seq) useEditor.getState().setIssues(useEditor.getState().issues, "error");
        }
      }, 350);
    };
    schedule();
    const unsub = useEditor.subscribe((s, prev) => {
      if (s.nodes !== prev.nodes || s.edges !== prev.edges || s.workload !== prev.workload || s.scenarios !== prev.scenarios) {
        schedule();
      }
    });
    return () => {
      unsub();
      if (timer) clearTimeout(timer);
      lastKey.current = null; // re-validate on remount (React StrictMode mounts effects twice)
    };
  }, []);
}

/** Wiring between runs and workspaces: results open in Results (decide), finished runs replay in Test (stress). */
function useWorkspaceWiring() {
  const mode = useUi((s) => s.mode);
  const activeRunId = useRuns((s) => s.activeRunId);

  useEffect(
    () =>
      useRuns.subscribe((s, prev) => {
        // Anything that used to open the results drawer now opens the matching Decide section.
        if (s.drawerOpen && (!prev.drawerOpen || s.drawerTab !== prev.drawerTab)) {
          useDecide.getState().goToDrawerTab(s.drawerTab);
          openDecide();
          useRuns.getState().setDrawerOpen(false);
        }
        // A run started from the UI just finished: replay it if the user is watching Stress.
        if (s.lastFinishedRunId && s.lastFinishedRunId !== prev.lastFinishedRunId && useUi.getState().mode === "stress") {
          useUi.getState().setPlayhead(0);
          useUi.getState().setPlaying(true);
        }
        // Switching to another run resets the replay.
        if (s.activeRunId !== prev.activeRunId && s.lastFinishedRunId !== s.activeRunId) {
          useUi.getState().setPlaying(false);
          useUi.getState().setPlayhead(null);
        }
      }),
    [],
  );

  // Visiting Decide with a run counts as having seen its results.
  useEffect(() => {
    if (mode === "decide" && activeRunId) useUi.getState().markInsightsSeen(activeRunId);
    if (mode !== "stress") useUi.getState().setPlaying(false);
  }, [mode, activeRunId]);
}

const WORKSPACE_KEYS: Record<string, Workspace> = { "1": "build", "2": "stress", "3": "decide" };

function EditorShell() {
  // Rendered only on the client (after the project loads), so window is available.
  const [showSide, setShowSide] = useState(() => window.innerWidth >= 768);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const mode = useUi((s) => s.mode);
  const exportOpen = useUi((s) => s.exportOpen);
  const setExportOpen = useUi((s) => s.setExportOpen);
  const { save, run } = useEditorActions();
  useLiveValidation();
  useWorkspaceWiring();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mod = e.ctrlKey || e.metaKey;
      const key = e.key.toLowerCase();
      if (mod && key === "k") {
        e.preventDefault();
        useUi.getState().setPaletteOpen(!useUi.getState().paletteOpen);
        return;
      }
      if (mod && key === "s") {
        e.preventDefault();
        void save();
        return;
      }
      if (mod && e.key === "Enter") {
        e.preventDefault();
        void run();
        return;
      }
      if (isEditableTarget(e.target) || useUi.getState().paletteOpen) return;
      const ed = useEditor.getState();
      if (!mod && !e.altKey && WORKSPACE_KEYS[e.key]) {
        e.preventDefault();
        const ws = WORKSPACE_KEYS[e.key];
        if (ws === "decide") openDecide();
        else useUi.getState().setMode(ws);
      } else if (mod && key === "z" && !e.shiftKey) {
        e.preventDefault();
        ed.undo();
      } else if ((mod && key === "z" && e.shiftKey) || (mod && key === "y")) {
        e.preventDefault();
        ed.redo();
      } else if (mod && key === "d") {
        e.preventDefault();
        ed.duplicateSelection();
      } else if (!mod && e.shiftKey && key === "l") {
        e.preventDefault();
        void ed.autoLayout();
      } else if (e.key === "?") {
        setShortcutsOpen(true);
      } else if (e.key === "/" && !mod) {
        e.preventDefault();
        useUi.getState().setPaletteOpen(true);
      } else if (e.key === "Escape") {
        ed.select({});
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [save, run]);

  // First visit on a roomy screen: show the 30-second tour once.
  useEffect(() => {
    if (hasSeenTour() || window.innerWidth < 1024) return;
    const t = window.setTimeout(() => useUi.getState().setTourOpen(true), 700);
    return () => window.clearTimeout(t);
  }, []);

  useEffect(() => {
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (useEditor.getState().dirty) {
        e.preventDefault();
      }
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, []);

  return (
    <div className="flex h-full flex-col overflow-hidden" data-workspace={mode}>
      <TopBar
        showInspector={showSide}
        onToggleInspector={() => setShowSide((v) => !v)}
        shortcutsOpen={shortcutsOpen}
        onShortcutsOpenChange={setShortcutsOpen}
      />
      <div className="relative flex min-h-0 flex-1">
        {mode === "build" && <PartsPanel />}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="relative min-h-0 flex-1 overflow-hidden">
            <Canvas />
            {mode !== "decide" && <NextStepPill />}
            {mode === "build" && <DesignReviewPanel />}
            {mode === "stress" && <PlaybackClock />}
            {mode === "decide" && (
              <div className="absolute inset-0 z-30 overflow-hidden bg-page">
                <DecideWorkspace />
              </div>
            )}
          </div>
          {mode === "stress" && <TimelineDock />}
        </div>
        {showSide && mode !== "decide" && (
          <div className="absolute inset-y-0 right-0 z-20 shadow-float lg:static lg:z-auto lg:shadow-none">
            {mode === "build" ? (
              <Inspector />
            ) : (
              <aside className="surface-sheen flex h-full w-[320px] shrink-0 flex-col overflow-y-auto border-l border-line bg-surface" aria-label="Experiment">
                <ExperimentPanel />
              </aside>
            )}
          </div>
        )}
      </div>
      <CommandPalette onShowShortcuts={() => setShortcutsOpen(true)} />
      <ExportDialog open={exportOpen} onOpenChange={setExportOpen} />
      <GuidedTour />
    </div>
  );
}
