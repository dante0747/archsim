"use client";

import { useReactFlow } from "@xyflow/react";
import { Activity, Flame, LayoutGrid, Maximize, Minus, Plus, Redo2, Undo2 } from "lucide-react";
import { Kbd, Tip } from "@/components/ui/misc";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

function ToolButton({
  label,
  shortcut,
  onClick,
  disabled,
  active,
  children,
}: {
  label: string;
  shortcut?: string;
  onClick: () => void;
  disabled?: boolean;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <Tip
      side="top"
      content={
        <span className="flex items-center gap-2">
          {label} {shortcut && <Kbd>{shortcut}</Kbd>}
        </span>
      }
    >
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        aria-label={label}
        aria-pressed={active}
        className={cn(
          "flex size-7 items-center justify-center rounded-sm text-ink-2 transition-colors hover:bg-surface-2 hover:text-ink disabled:pointer-events-none disabled:opacity-35 [&_svg]:size-4",
          active && "bg-accent-soft text-accent",
        )}
      >
        {children}
      </button>
    </Tip>
  );
}

/** Floating toolbar for canvas actions (undo, layout, zoom, result overlays). */
export function CanvasToolbar() {
  const rf = useReactFlow();
  const canUndo = useEditor((s) => s.past.length > 0);
  const canRedo = useEditor((s) => s.future.length > 0);
  const undo = useEditor((s) => s.undo);
  const redo = useEditor((s) => s.redo);
  const autoLayout = useEditor((s) => s.autoLayout);
  const hasReport = useRuns((s) => s.activeRunId !== null);
  const overlay = useRuns((s) => s.overlay);
  const setOverlay = useRuns((s) => s.setOverlay);
  const flow = useUi((s) => s.flow);
  const setFlow = useUi((s) => s.setFlow);
  const setLibraryOpen = useUi((s) => s.setLibraryOpen);
  const libraryOpen = useUi((s) => s.libraryOpen);
  const sep = <span className="mx-1 h-4 w-px bg-line-strong" aria-hidden />;
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-3 z-10 flex justify-center">
      <div
        role="toolbar"
        aria-label="Canvas tools"
        className="surface-glass pointer-events-auto flex items-center gap-0.5 rounded-xl p-1"
      >
        <button
          type="button"
          onClick={() => setLibraryOpen(!libraryOpen)}
          className="flex h-7 items-center gap-1.5 rounded-sm border border-accent/40 bg-accent-soft px-2.5 font-mono text-[11px] font-semibold text-accent hover:border-accent"
          aria-expanded={libraryOpen}
        >
          <Plus className="size-3.5" /> add component
        </button>
        {sep}
        <ToolButton label="Undo" shortcut="Ctrl Z" onClick={undo} disabled={!canUndo}>
          <Undo2 />
        </ToolButton>
        <ToolButton label="Redo" shortcut="Ctrl Shift Z" onClick={redo} disabled={!canRedo}>
          <Redo2 />
        </ToolButton>
        {sep}
        <ToolButton label="Tidy up (auto-layout)" shortcut="Shift L" onClick={() => void autoLayout()}>
          <LayoutGrid />
        </ToolButton>
        <ToolButton label="Zoom out" onClick={() => void rf.zoomOut({ duration: 150 })}>
          <Minus />
        </ToolButton>
        <ToolButton label="Fit to screen" onClick={() => void rf.fitView({ padding: 0.15, maxZoom: 1, duration: 250 })}>
          <Maximize />
        </ToolButton>
        <ToolButton label="Zoom in" onClick={() => void rf.zoomIn({ duration: 150 })}>
          <Plus />
        </ToolButton>
        {hasReport && (
          <>
            {sep}
            <ToolButton label={overlay ? "Hide results on canvas" : "Show results on canvas"} onClick={() => setOverlay(!overlay)} active={overlay}>
              <Flame />
            </ToolButton>
            <ToolButton label={flow ? "Stop flow animation" : "Animate measured traffic"} onClick={() => setFlow(!flow)} active={flow && overlay} disabled={!overlay}>
              <Activity />
            </ToolButton>
          </>
        )}
      </div>
    </div>
  );
}
