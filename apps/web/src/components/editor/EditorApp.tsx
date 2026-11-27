"use client";

import { ReactFlowProvider } from "@xyflow/react";
import { LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { EditorAgentTools } from "@/components/agent/AgentTools";
import { SandboxBanner } from "@/components/auth/SandboxBanner";
import { ChaosPads } from "@/components/editor/ChaosPads";
import { DesignReviewPanel } from "@/components/build/DesignReview";
import { CalibrateDialog } from "@/components/build/CalibrateDialog";
import { ImportDialog } from "@/components/build/ImportDialog";
import { ExportDialog } from "@/components/build/ExportDialog";
import { Dashboard } from "@/components/dashboard/Dashboard";
import { DecideWorkspace } from "@/components/decide/DecideWorkspace";
import { useEditorActions } from "@/components/editor/actions";
import { Canvas } from "@/components/editor/Canvas";
import { CommandPalette } from "@/components/editor/CommandPalette";
import { GuidedTour, hasSeenTour } from "@/components/editor/GuidedTour";
import { Inspector } from "@/components/editor/Inspector";
import { PartsPanel } from "@/components/editor/Palette";
import { SaveConflictDialog } from "@/components/editor/SaveConflictDialog";
import { TopBar } from "@/components/editor/TopBar";
import { NextStepPill } from "@/components/editor/WorkspaceBar";
import { openDecide } from "@/components/editor/workflow";
import { ExperimentPanel } from "@/components/stress/ExperimentPanel";
import { PlaybackClock, TimelineDock } from "@/components/timeline/TimelineDock";
import { DesktopOnly } from "@/components/editor/DesktopOnly";
import { Button } from "@/components/ui/button";
import { TooltipProvider } from "@/components/ui/misc";
import { ApiError, api } from "@/lib/api";
import { projectTitle, useDocumentTitle } from "@/lib/document-title";
import { signInPage } from "@/lib/session";
import { isEditableTarget } from "@/lib/utils";
import { useDecide } from "@/store/decide";
import { contentKey, useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { type Workspace, useUi } from "@/store/ui";

export function EditorApp({ projectId }: { projectId: string }) {
  const narrow = useNarrowScreen();
  const [openAnyway, setOpenAnyway] = useState(false);
  const [state, setState] = useState<{ kind: "loading" } | { kind: "ready" } | { kind: "error"; message: string }>({
    kind: "loading",
  });
  const name = useEditor((s) => s.metadata.name);
  useDocumentTitle(state.kind === "ready" ? projectTitle(name) : null);

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
        if (e instanceof ApiError && e.status === 401) {
          window.location.replace(signInPage(`/projects/${projectId}`));
          return;
        }
        const message =
          e instanceof ApiError && e.status === 404
            ? "This project does not exist, or it is in a workspace you are not a member of."
            : e instanceof Error
              ? e.message
              : String(e);
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
          <Link href="/projects">Back to projects</Link>
        </Button>
      </div>
    );
  }
  if (narrow && !openAnyway) return <DesktopOnly projectId={projectId} onOpenAnyway={() => setOpenAnyway(true)} />;
  return (
    <ReactFlowProvider>
      <TooltipProvider>
        <EditorShell />
      </TooltipProvider>
    </ReactFlowProvider>
  );
}

const NARROW = "(max-width: 767px)";

/** True on phone-width screens, where the editor's canvas and panels do not fit. */
function useNarrowScreen(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const mq = window.matchMedia(NARROW);
      mq.addEventListener("change", onChange);
      return () => mq.removeEventListener("change", onChange);
    },
    () => window.matchMedia(NARROW).matches,
    () => false,
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
        const doc = useEditor.getState().toDocument();
        // the engine validates and analyzes (auto sizes, cost, review): one source of truth
        const [checked, analyzed] = await Promise.allSettled([api.validate(doc), api.analyze(doc)]);
        if (mySeq !== seq) return;
        const st = useEditor.getState();
        if (checked.status === "fulfilled") st.setIssues(checked.value.issues, "done");
        else st.setIssues(st.issues, "error");
        // an unreadable document (mid-edit) keeps the last analysis
        if (analyzed.status === "fulfilled") st.setAnalysis(analyzed.value);
      }, 350);
    };
    schedule();
    const unsub = useEditor.subscribe((s, prev) => {
      if (
        s.nodes !== prev.nodes ||
        s.edges !== prev.edges ||
        s.workload !== prev.workload ||
        s.scenarios !== prev.scenarios ||
        s.extras?.pricing !== prev.extras?.pricing ||
        s.extras?.siblingSpeed !== prev.extras?.siblingSpeed
      ) {
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
    // Test and Dashboard share the replay; elsewhere it stops.
    if (mode !== "stress" && mode !== "dashboard") useUi.getState().setPlaying(false);
  }, [mode, activeRunId]);
}

const WORKSPACE_KEYS: Record<string, Workspace> = { "1": "build", "2": "stress", "3": "decide", "4": "dashboard" };

function EditorShell() {
  // Rendered only on the client (after the project loads), so window is available.
  const [showSide, setShowSide] = useState(() => window.innerWidth >= 768);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const mode = useUi((s) => s.mode);
  const exportOpen = useUi((s) => s.exportOpen);
  const setExportOpen = useUi((s) => s.setExportOpen);
  const calibrateOpen = useUi((s) => s.calibrateOpen);
  const setCalibrateOpen = useUi((s) => s.setCalibrateOpen);
  const importOpen = useUi((s) => s.importOpen);
  const setImportOpen = useUi((s) => s.setImportOpen);
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
    <div className="flex h-full flex-col overflow-hidden bg-page" data-workspace={mode}>
      <TopBar
        showInspector={showSide}
        onToggleInspector={() => setShowSide((v) => !v)}
        shortcutsOpen={shortcutsOpen}
        onShortcutsOpenChange={setShortcutsOpen}
      />
      <SandboxBanner />
      <div className="relative flex min-h-0 flex-1">
        {mode === "build" && <PartsPanel />}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="relative min-h-0 flex-1 overflow-hidden">
            <Canvas />
            {mode !== "decide" && mode !== "dashboard" && <NextStepPill />}
            {mode === "build" && <DesignReviewPanel />}
            {mode === "build" && <ChaosPads />}
            {/* the transport LCD shows the same numbers on wide screens */}
            {mode === "stress" && (
              <div className="xl:hidden">
                <PlaybackClock />
              </div>
            )}
            {mode === "decide" && (
              <div className="absolute inset-0 z-30 overflow-hidden bg-page">
                <DecideWorkspace />
              </div>
            )}
            {mode === "dashboard" && (
              <div className="absolute inset-0 z-30 overflow-hidden bg-page">
                <Dashboard />
              </div>
            )}
          </div>
          {mode === "stress" && <TimelineDock />}
        </div>
        {showSide && mode !== "decide" && mode !== "dashboard" && (
          <div className="absolute inset-y-0 right-0 z-20 shadow-float lg:static lg:z-auto lg:shadow-none">
            {mode === "build" ? (
              <Inspector />
            ) : (
              <aside className="island flex h-full w-[352px] shrink-0 flex-col overflow-y-auto border-l border-line" aria-label="Experiment">
                <ExperimentPanel />
              </aside>
            )}
          </div>
        )}
      </div>
      <CommandPalette onShowShortcuts={() => setShortcutsOpen(true)} />
      <ExportDialog open={exportOpen} onOpenChange={setExportOpen} />
      <CalibrateDialog open={calibrateOpen} onOpenChange={setCalibrateOpen} />
      <ImportDialog open={importOpen} onOpenChange={setImportOpen} />
      <SaveConflictDialog />
      <GuidedTour />
      <EditorAgentTools />
    </div>
  );
}
