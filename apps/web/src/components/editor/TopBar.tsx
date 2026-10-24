"use client";

import {
  BookOpen,
  Check,
  ChevronDown,
  CircleAlert,
  Download,
  FileCode2,
  Keyboard,
  LayoutGrid,
  LoaderCircle,
  MoreHorizontal,
  PanelRight,
  Play,
  Redo2,
  Save,
  Search,
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
import { cn, modKey } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export const SHORTCUTS: [string, string][] = [
  ["Ctrl/⌘ K  ·  /", "Command palette"],
  ["1  ·  2  ·  3", "Build · Stress · Decide workspaces"],
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
        className="relative flex h-8 min-w-40 items-center gap-2 overflow-hidden rounded-md border border-line-strong bg-surface px-2.5 text-[12.5px] font-semibold text-ink shadow-panel"
        aria-label="Stop simulation"
      >
        <span className="progress-stripes absolute bottom-0 left-0 h-[3px]" style={{ width: `${Math.max(4, followRun.progress * 100)}%` }} aria-hidden />
        <Square className="size-3 fill-current" />
        <span>{followRun.status === "queued" ? "queued…" : "simulating"}</span>
        <span className="tabular ml-auto">{Math.round(followRun.progress * 100)}%</span>
      </button>
    );
  }
  return (
    <div className="flex h-8 items-stretch">
      <Tip content={<>Run simulation <Kbd>Ctrl Enter</Kbd></>}>
        <button
          type="button"
          onClick={() => void run()}
          disabled={starting}
          data-testid="run-button"
          className="accent-fill flex items-center gap-1.5 rounded-l-md pl-3 pr-3 text-[13px] font-semibold transition-[filter] hover:brightness-[1.08] disabled:opacity-60"
        >
          {starting ? <LoaderCircle className="size-3.5 animate-spin" /> : <Play className="size-3.5 fill-current" />}
          Run
        </button>
      </Tip>
      <Menu>
        <MenuTrigger asChild>
          <button
            type="button"
            aria-label="Choose what to simulate"
            className="flex max-w-48 items-center gap-1 rounded-r-md border border-l-0 border-line-strong bg-surface px-2.5 text-[12.5px] text-ink-2 shadow-panel transition-colors hover:bg-surface-2 hover:text-ink"
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
      <span className="hidden items-center gap-1 px-2 py-1 font-mono text-[10.5px] text-ink-3 lg:flex" aria-live="polite">
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
            "flex items-center gap-1.5 rounded-sm border px-2 py-1 font-mono text-[11px] text-ink",
            errors.length ? "border-critical/50 bg-critical-soft" : "border-warning/50 bg-warning-soft",
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
  const setPaletteOpen = useUi((s) => s.setPaletteOpen);
  const setExportOpen = useUi((s) => s.setExportOpen);

  return (
    <header className="relative z-30 flex h-12 shrink-0 items-center gap-3 border-b border-line bg-surface/85 px-3 backdrop-blur-md">
      <div className="flex min-w-0 items-center gap-2">
        <Tip content="All projects">
          <Link href="/" aria-label="Back to projects" className="rounded-md p-1 hover:bg-surface-2">
            <LogoMark className="size-6" />
          </Link>
        </Tip>
        <Link href="/" className="hidden text-[13px] text-ink-3 transition-colors hover:text-ink md:inline">
          projects
        </Link>
        <span className="hidden text-ink-3 md:inline" aria-hidden>
          /
        </span>
        <input
          value={name}
          onChange={(e) => setMetadata({ name: e.target.value }, "name")}
          aria-label="Project name"
          maxLength={120}
          style={{ width: `calc(${Math.min(Math.max(name.length, 12), 38)}ch + 14px)` }}
          className="h-7 min-w-0 max-w-[40vw] rounded-md border border-transparent bg-transparent px-1.5 text-[14px] font-semibold tracking-[-0.01em] text-ink transition-colors hover:border-line focus-visible:border-accent focus-visible:bg-surface-2 focus-visible:outline-none"
        />
        <span
          className={cn(
            "hidden items-center gap-1.5 whitespace-nowrap font-mono text-[10.5px] sm:flex",
            dirty ? "text-ink-2" : "text-ink-3",
          )}
          aria-live="polite"
        >
          <span className={cn("size-1.5 rounded-full", saving ? "bg-accent pulse-soft" : dirty ? "bg-warning" : "bg-ink-3")} aria-hidden />
          {saving ? "saving" : dirty ? "unsaved" : "saved"}
        </span>
      </div>

      <div className="flex-1" />

      <div className="flex items-center gap-1.5">
        <button
          type="button"
          onClick={() => setPaletteOpen(true)}
          aria-label="Open command palette"
          className="hidden h-8 w-64 items-center gap-2 rounded-md border border-line-strong bg-surface-2/70 px-2.5 text-left text-[12.5px] text-ink-3 transition-colors hover:bg-surface-2 hover:text-ink-2 xl:flex"
        >
          <Search className="size-3.5 shrink-0" />
          <span className="flex-1 truncate">run, add, compare…</span>
          <Kbd>{modKey()} K</Kbd>
        </button>
        <Tip content={<>Command palette <Kbd>{modKey()} K</Kbd></>}>
          <Button variant="ghost" size="icon" onClick={() => setPaletteOpen(true)} aria-label="Open command palette" className="xl:hidden">
            <Search />
          </Button>
        </Tip>
        <ProblemsPill />
        <RunControl />
        <Tip content={<>Save <Kbd>Ctrl S</Kbd></>}>
          <Button onClick={() => void save()} disabled={saving} aria-label="Save" className={cn(!dirty && "text-ink-3")}>
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
            <Button variant="ghost" size="icon" aria-label="More actions">
              <MoreHorizontal />
            </Button>
          </MenuTrigger>
          <MenuContent>
            <MenuItem onSelect={() => setPaletteOpen(true)}>
              <Search /> Command palette <Kbd className="ml-auto">{modKey()} K</Kbd>
            </MenuItem>
            <MenuSeparator />
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
            <MenuItem onSelect={() => setExportOpen(true)}>
              <FileCode2 /> Export docker-compose · Kubernetes · Mermaid
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
          <Button variant="ghost" size="icon" onClick={onToggleInspector} aria-pressed={showInspector} aria-label="Toggle side panel">
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
