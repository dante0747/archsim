"use client";

import { ReactFlowProvider } from "@xyflow/react";
import { LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useEditorActions } from "@/components/editor/actions";
import { Canvas } from "@/components/editor/Canvas";
import { Inspector } from "@/components/editor/Inspector";
import { Palette } from "@/components/editor/Palette";
import { TopBar } from "@/components/editor/TopBar";
import { ResultsDrawer } from "@/components/results/ResultsDrawer";
import { Button } from "@/components/ui/button";
import { TooltipProvider } from "@/components/ui/misc";
import { ApiError, api } from "@/lib/api";
import { isEditableTarget } from "@/lib/utils";
import { contentKey, useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

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
      <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-2" role="status">
        <LoaderCircle className="size-4 animate-spin" /> Loading project…
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
    };
  }, []);
}

function EditorShell() {
  // Rendered only on the client (after the project loads), so window is available.
  const [showPalette, setShowPalette] = useState(() => window.innerWidth >= 1024);
  const [showInspector, setShowInspector] = useState(() => window.innerWidth >= 768);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const { save, run } = useEditorActions();
  useLiveValidation();

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mod = e.ctrlKey || e.metaKey;
      const key = e.key.toLowerCase();
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
      if (isEditableTarget(e.target)) return;
      const ed = useEditor.getState();
      if (mod && key === "z" && !e.shiftKey) {
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
      } else if (e.key === "Escape") {
        ed.select({});
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [save, run]);

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
    <div className="flex h-full flex-col overflow-hidden">
      <TopBar
        showPalette={showPalette}
        showInspector={showInspector}
        onTogglePalette={() => setShowPalette((v) => !v)}
        onToggleInspector={() => setShowInspector((v) => !v)}
        shortcutsOpen={shortcutsOpen}
        onShortcutsOpenChange={setShortcutsOpen}
      />
      <div className="relative flex min-h-0 flex-1">
        {showPalette && (
          <div className="absolute inset-y-0 left-0 z-20 shadow-xl lg:static lg:z-auto lg:shadow-none">
            <Palette />
          </div>
        )}
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="relative min-h-0 flex-1 overflow-hidden">
            <Canvas />
          </div>
          <ResultsDrawer />
        </div>
        {showInspector && (
          <div className="absolute inset-y-0 right-0 z-20 shadow-xl lg:static lg:z-auto lg:shadow-none">
            <Inspector />
          </div>
        )}
      </div>
    </div>
  );
}
