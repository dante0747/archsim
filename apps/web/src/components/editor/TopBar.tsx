"use client";

import {
  BookOpen,
  Check,
  ChevronDown,
  CircleAlert,
  Download,
  Keyboard,
  LayoutGrid,
  LoaderCircle,
  MoreHorizontal,
  PanelRight,
  Play,
  Redo2,
  Save,
  Square,
  TriangleAlert,
  Undo2,
  Upload,
} from "lucide-react";
import Link from "next/link";
import { useRef } from "react";
import { useEditorActions } from "@/components/editor/actions";
import { LogoMark } from "@/components/brand";
import { Button } from "@/components/ui/button";
import { Kbd, Tip } from "@/components/ui/misc";
import {
  Dialog,
  DialogContent,
  Menu,
  MenuContent,
  MenuItem,
  MenuLabel,
  MenuSeparator,
  MenuTrigger,
} from "@/components/ui/overlays";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

export const SHORTCUTS: [string, string][] = [
  ["Ctrl/⌘ S", "Save"],
  ["Ctrl/⌘ Enter", "Run simulation"],
  ["Ctrl/⌘ Z", "Undo"],
  ["Ctrl/⌘ Shift Z  ·  Ctrl Y", "Redo"],
  ["Ctrl/⌘ D", "Duplicate selected components"],
  ["Shift L", "Tidy up (auto-layout)"],
  ["Delete / Backspace", "Delete selection"],
  ["Shift + drag", "Box-select"],
  ["Ctrl/⌘ + click", "Add to selection"],
  ["Esc", "Clear selection"],
  ["?", "Show this help"],
];

function RunControl() {
  const scenarios = useEditor((s) => s.scenarios);
  const selectedScenarioId = useRuns((s) => s.selectedScenarioId);
  const selectScenario = useRuns((s) => s.selectScenario);
  const starting = useRuns((s) => s.starting);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const cancel = useRuns((s) => s.cancel);
  const { run } = useEditorActions();
  const running = followRun && (followRun.status === "queued" || followRun.status === "running");
  const target = scenarios.find((s) => s.id === selectedScenarioId);

  if (running && followRun) {
    return (
      <button
        type="button"
        onClick={() => void cancel(followRun.id)}
        className="relative flex h-9 min-w-40 items-center gap-2 overflow-hidden rounded-xl border border-line bg-surface px-3 text-xs font-semibold text-ink shadow-panel"
        aria-label="Stop simulation"
      >
        <span className="progress-shimmer absolute inset-y-0 left-0 opacity-25" style={{ width: `${followRun.progress * 100}%` }} aria-hidden />
        <Square className="relative size-3.5 fill-current" />
        <span className="relative">{followRun.status === "queued" ? "Queued…" : "Simulating"}</span>
        <span className="tabular relative ml-auto font-mono">{Math.round(followRun.progress * 100)}%</span>
      </button>
    );
  }
  return (
    <div className="flex h-9 items-stretch rounded-xl shadow-[0_8px_24px_-10px_color-mix(in_srgb,var(--accent)_70%,transparent)]">
      <Tip content={<>Run simulation <Kbd>Ctrl Enter</Kbd></>}>
        <button
          type="button"
          onClick={() => void run()}
          disabled={starting}
          data-testid="run-button"
          className="brand-bg flex items-center gap-1.5 rounded-l-xl pl-3.5 pr-3 text-sm font-semibold text-accent-ink hover:brightness-110 disabled:opacity-60"
        >
          {starting ? <LoaderCircle className="size-4 animate-spin" /> : <Play className="size-4 fill-current" />}
          Run
        </button>
      </Tip>
      <Menu>
        <MenuTrigger asChild>
          <button
            type="button"
            aria-label="Choose what to simulate"
            className="flex max-w-48 items-center gap-1 rounded-r-xl border-l border-black/15 bg-[color-mix(in_srgb,var(--accent)_22%,var(--surface))] px-2.5 text-xs font-medium text-ink hover:brightness-110"
          >
            <span className="truncate">{target ? target.name : "Design as drawn"}</span>
            <ChevronDown className="size-3.5 shrink-0" />
          </button>
        </MenuTrigger>
        <MenuContent className="w-72">
          <MenuLabel>What to simulate</MenuLabel>
          <MenuItem onSelect={() => selectScenario(null)}>
            <Check className={cn(!target ? "opacity-100" : "opacity-0")} />
            <span>
              <span className="block">Design as drawn</span>
              <span className="block text-[11px] text-ink-3">Exactly what is on the canvas</span>
            </span>
          </MenuItem>
          {scenarios.length > 0 && <MenuSeparator />}
          {scenarios.map((s) => (
            <MenuItem key={s.id} onSelect={() => selectScenario(s.id)}>
              <Check className={cn(target?.id === s.id ? "opacity-100" : "opacity-0")} />
              <span className="min-w-0">
                <span className="block truncate">{s.name}</span>
                <span className="block truncate text-[11px] text-ink-3">
                  {s.overrides.length === 0 ? "No changes" : `${s.overrides.length} change${s.overrides.length > 1 ? "s" : ""} to the design`}
                </span>
              </span>
            </MenuItem>
          ))}
        </MenuContent>
      </Menu>
    </div>
  );
}

function ProblemsPill() {
  const issues = useEditor((s) => s.issues);
  const validation = useEditor((s) => s.validation);
  const select = useEditor((s) => s.select);
  const errors = issues.filter((i) => i.severity === "error");
  const warnings = issues.filter((i) => i.severity === "warning");
  if (!issues.length) {
    return (
      <span className="hidden items-center gap-1 rounded-full px-2 py-1 font-mono text-[10px] uppercase tracking-wider text-ink-3 lg:flex" aria-live="polite">
        {validation === "pending" ? <LoaderCircle className="size-3 animate-spin" /> : <Check className="size-3" style={{ color: "var(--status-good)" }} />}
        {validation === "pending" ? "checking" : "valid"}
      </span>
    );
  }
  return (
    <Menu>
      <MenuTrigger asChild>
        <button
          type="button"
          className={cn(
            "flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium text-ink",
            errors.length ? "bg-critical-soft" : "bg-warning-soft",
          )}
        >
          {errors.length ? (
            <CircleAlert className="size-3.5" style={{ color: "var(--status-critical)" }} />
          ) : (
            <TriangleAlert className="size-3.5" style={{ color: "var(--status-warning)" }} />
          )}
          {errors.length ? `${errors.length} error${errors.length > 1 ? "s" : ""}` : `${warnings.length} warning${warnings.length > 1 ? "s" : ""}`}
        </button>
      </MenuTrigger>
      <MenuContent align="end" className="max-h-96 w-96 overflow-y-auto">
        <MenuLabel>Problems — click one to jump to it</MenuLabel>
        {issues.map((i, k) => (
          <MenuItem key={k} className="items-start" onSelect={() => (i.nodeId || i.edgeId ? select({ nodeId: i.nodeId, edgeId: i.edgeId }) : undefined)}>
            {i.severity === "error" ? (
              <CircleAlert className="mt-0.5 shrink-0" style={{ color: "var(--status-critical)" }} />
            ) : (
              <TriangleAlert className="mt-0.5 shrink-0" style={{ color: "var(--status-warning)" }} />
            )}
            <span className="text-xs">
              {i.message}
              {i.hint && <span className="block text-ink-3">{i.hint}</span>}
            </span>
          </MenuItem>
        ))}
      </MenuContent>
    </Menu>
  );
}

export function TopBar({
  showInspector,
  onToggleInspector,
  shortcutsOpen,
  onShortcutsOpenChange,
}: {
  showInspector: boolean;
  onToggleInspector: () => void;
  shortcutsOpen: boolean;
  onShortcutsOpenChange: (o: boolean) => void;
}) {
  const name = useEditor((s) => s.metadata.name);
  const setMetadata = useEditor((s) => s.setMetadata);
  const dirty = useEditor((s) => s.dirty);
  const saving = useEditor((s) => s.saving);
  const canUndo = useEditor((s) => s.past.length > 0);
  const canRedo = useEditor((s) => s.future.length > 0);
  const undo = useEditor((s) => s.undo);
  const redo = useEditor((s) => s.redo);
  const autoLayout = useEditor((s) => s.autoLayout);
  const { save, exportJson, importJson } = useEditorActions();
  const fileRef = useRef<HTMLInputElement>(null);

  return (
    <header className="relative z-30 flex h-14 shrink-0 items-center gap-3 border-b border-line bg-surface px-3">
      <div className="flex min-w-0 items-center gap-2">
        <Tip content="All projects">
          <Link href="/" aria-label="Back to projects" className="rounded-lg p-1 hover:bg-surface-2">
            <LogoMark className="size-7" />
          </Link>
        </Tip>
        <Link href="/" className="hidden text-xs text-ink-3 hover:text-ink md:inline">
          Projects
        </Link>
        <span className="hidden text-ink-3 md:inline" aria-hidden>
          /
        </span>
        <input
          value={name}
          onChange={(e) => setMetadata({ name: e.target.value }, "name")}
          aria-label="Project name"
          maxLength={120}
          className="h-8 w-36 min-w-0 rounded-lg border border-transparent bg-transparent px-1.5 text-sm font-semibold text-ink hover:border-line focus-visible:border-accent focus-visible:bg-surface-2 focus-visible:outline-none sm:w-56"
        />
        <span
          className={cn(
            "hidden items-center gap-1 whitespace-nowrap font-mono text-[10px] uppercase tracking-wider sm:flex",
            dirty ? "text-ink-2" : "text-ink-3",
          )}
          aria-live="polite"
        >
          <span className={cn("size-1.5 rounded-full", saving ? "bg-accent pulse-soft" : dirty ? "bg-warning" : "bg-good")} aria-hidden />
          {saving ? "saving" : dirty ? "unsaved" : "saved"}
        </span>
      </div>

      <div className="flex-1" />

      <div className="flex items-center gap-1.5">
        <ProblemsPill />
        <RunControl />
        <Tip content={<>Save <Kbd>Ctrl S</Kbd></>}>
          <Button onClick={() => void save()} disabled={saving} aria-label="Save" className={cn("h-9", !dirty && "text-ink-3")}>
            {saving ? <LoaderCircle className="animate-spin" /> : <Save />}
            <span className="hidden lg:inline">Save</span>
          </Button>
        </Tip>
        <input
          ref={fileRef}
          type="file"
          accept="application/json,.json"
          className="hidden"
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f && window.confirm("Replace the current design with the imported document? You can undo this.")) void importJson(f);
            e.target.value = "";
          }}
        />
        <Menu>
          <MenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="More actions" className="size-9">
              <MoreHorizontal />
            </Button>
          </MenuTrigger>
          <MenuContent>
            <MenuItem onSelect={undo} disabled={!canUndo}>
              <Undo2 /> Undo <Kbd className="ml-auto">Ctrl Z</Kbd>
            </MenuItem>
            <MenuItem onSelect={redo} disabled={!canRedo}>
              <Redo2 /> Redo <Kbd className="ml-auto">Ctrl Shift Z</Kbd>
            </MenuItem>
            <MenuItem onSelect={() => void autoLayout()}>
              <LayoutGrid /> Tidy up layout <Kbd className="ml-auto">Shift L</Kbd>
            </MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={exportJson}>
              <Download /> Export JSON
            </MenuItem>
            <MenuItem onSelect={() => fileRef.current?.click()}>
              <Upload /> Import JSON into this project
            </MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={() => onShortcutsOpenChange(true)}>
              <Keyboard /> Keyboard shortcuts <Kbd className="ml-auto">?</Kbd>
            </MenuItem>
            <MenuItem asChild>
              <a href="/api/v1/docs" target="_blank" rel="noreferrer">
                <BookOpen /> API documentation
              </a>
            </MenuItem>
          </MenuContent>
        </Menu>
        <ThemeToggle />
        <Tip content={showInspector ? "Hide side panel" : "Show side panel"}>
          <Button variant="ghost" size="icon" onClick={onToggleInspector} aria-pressed={showInspector} aria-label="Toggle side panel" className="size-9">
            <PanelRight />
          </Button>
        </Tip>
      </div>

      <Dialog open={shortcutsOpen} onOpenChange={onShortcutsOpenChange}>
        <DialogContent title="Keyboard shortcuts">
          <dl className="divide-y divide-line text-sm">
            {SHORTCUTS.map(([k, v]) => (
              <div key={k} className="flex justify-between gap-4 py-1.5">
                <dt className="text-ink-2">{v}</dt>
                <dd>
                  <Kbd>{k}</Kbd>
                </dd>
              </div>
            ))}
          </dl>
        </DialogContent>
      </Dialog>
    </header>
  );
}
