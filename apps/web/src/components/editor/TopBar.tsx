"use client";

import {
  BookOpen,
  Check,
  ChevronDown,
  CircleAlert,
  CircleHelp,
  Download,
  FileCode2,
  FileInput,
  TestTubeDiagonal,
  Gauge,
  Keyboard,
  LayoutGrid,
  LoaderCircle,
  Moon,
  MoreHorizontal,
  PanelRight,
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
import { startTour } from "@/components/editor/GuidedTour";
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
import { ThemeMenu, toggleTheme } from "@/components/ui/theme-toggle";
import { UserMenu } from "@/components/auth/UserMenu";
import { ActivityButton } from "@/components/team/ActivityButton";
import { WorkspaceSwitch } from "@/components/editor/WorkspaceBar";
import { SimulateOptionItems } from "@/components/editor/SimulateOptions";
import { TransportButtons, TransportLcd } from "@/components/editor/Transport";
import { cn, modKey } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export const SHORTCUTS: [string, string][] = [
  ["Ctrl/⌘ K  ·  /", "Command palette"],
  ["1  ·  2  ·  3  ·  4", "Design · Test · Results · Dashboard"],
  ["Ctrl/⌘ S", "Save"],
  ["Ctrl/⌘ Enter", "Simulate"],
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
        className="relative flex h-9 min-w-40 items-center gap-2 overflow-hidden rounded-[9px] border border-[color:var(--record-line)] bg-[color:var(--record-fill)] px-3 text-[13px] font-semibold text-ink"
        aria-label="Stop simulation"
      >
        <span className="progress-stripes absolute bottom-0 left-0 h-[3px]" style={{ width: `${Math.max(4, followRun.progress * 100)}%` }} aria-hidden />
        <Square className="size-3 fill-current" />
        <span>{followRun.status === "queued" ? "queued…" : "simulating"}</span>
        <span className="tabular ml-auto">{Math.round(followRun.progress * 100)}%</span>
      </button>
    );
  }
  const hasScenarios = scenarios.length > 0;
  return (
    <div className="flex h-9 items-stretch" data-tour="simulate">
      <Tip content={<>Send traffic through {target ? `“${target.name}”` : "the design"} and record what happens <Kbd>Ctrl Enter</Kbd></>}>
        <button
          type="button"
          onClick={() => void run()}
          disabled={starting}
          data-testid="run-button"
          className={cn(
            "flex items-center gap-2 rounded-l-[9px] border border-r-0 border-[color:var(--record-line)] bg-[color:var(--record-fill)] pl-3 pr-3.5 text-[13px] font-semibold text-ink transition-[filter] hover:brightness-110 disabled:opacity-60",
          )}
        >
          {starting ? (
            <LoaderCircle className="size-3.5 animate-spin" style={{ color: "var(--record)" }} />
          ) : (
            <span className="size-3 rounded-full bg-[color:var(--record)] shadow-[inset_0_1px_0_rgba(255,255,255,0.35)]" aria-hidden />
          )}
          Simulate
        </button>
      </Tip>
      <Menu>
        <MenuTrigger asChild>
          <button
            type="button"
            aria-label="Choose what to simulate"
            className="flex max-w-32 items-center gap-1 rounded-r-[9px] 2xl:max-w-40 border border-line-strong bg-surface-2 px-2 text-[13px] text-ink-2 transition-colors hover:bg-surface-3 hover:text-ink"
          >
            {target ? <span className="truncate">{target.name}</span> : <span className="sr-only">Current design</span>}
            <ChevronDown className="size-3.5 shrink-0" />
          </button>
        </MenuTrigger>
        <MenuContent className="w-80">
          {hasScenarios && (
          <>
          <MenuLabel>What to simulate</MenuLabel>
          <MenuItem onSelect={() => selectScenario(null)}>
            <Check className={cn(!target ? "opacity-100" : "opacity-0")} />
            <span>
              <span className="block">Current design</span>
              <span className="block text-xs text-ink-3">Exactly what is on the canvas</span>
            </span>
          </MenuItem>
          <MenuSeparator />
          <MenuLabel>Or a what-if scenario</MenuLabel>
          {scenarios.map((s) => (
            <MenuItem key={s.id} onSelect={() => selectScenario(s.id)}>
              <Check className={cn(target?.id === s.id ? "opacity-100" : "opacity-0")} />
              <span className="min-w-0">
                <span className="block truncate">{s.name}</span>
                <span className="block truncate text-xs text-ink-3">
                  {s.overrides.length === 0 ? "No changes" : `${s.overrides.length} change${s.overrides.length > 1 ? "s" : ""} to the design`}
                </span>
              </span>
            </MenuItem>
          ))}
          <MenuSeparator />
          </>
          )}
          <SimulateOptionItems onRun={(opts) => void run(opts)} />
        </MenuContent>
      </Menu>
    </div>
  );
}

/**
 * The project's name, edited in place. It is as wide as its text, up to 20rem, and is what gives
 * way when the top bar is short of room: cut short, it ends in an ellipsis and shows in full on
 * hover.
 */
function ProjectName({ name, onChange }: { name: string; onChange: (name: string) => void }) {
  const text = "border border-transparent px-1.5 text-[14px] font-semibold tracking-[-0.01em]";
  return (
    // An invisible copy of the name sizes the grid's one column, which minmax(0, auto) lets shrink
    // below the text, down to min-w-16; the input fills the column (size={1}: its default width of
    // 20 characters would widen the column).
    <span className="grid min-w-16 max-w-[20rem] grid-cols-[minmax(0,auto)]">
      <span aria-hidden className={cn(text, "invisible col-start-1 row-start-1 overflow-hidden whitespace-pre")}>
        {name || " "}
      </span>
      <input
        value={name}
        onChange={(e) => onChange(e.target.value)}
        aria-label="Project name"
        title={name}
        maxLength={120}
        size={1}
        className={cn(
          text,
          "col-start-1 row-start-1 h-7 w-full min-w-0 truncate rounded-md bg-transparent text-ink transition-colors hover:border-line focus-visible:border-accent focus-visible:bg-surface-2 focus-visible:outline-none",
        )}
      />
    </span>
  );
}

function ProblemsPill() {
  const issues = useEditor((s) => s.issues);
  const validation = useEditor((s) => s.validation);
  const select = useEditor((s) => s.select);
  const empty = useEditor((s) => s.nodes.length === 0);
  const errors = issues.filter((i) => i.severity === "error");
  const warnings = issues.filter((i) => i.severity === "warning");
  if (empty) return null; // the empty canvas explains what to do; no red flags yet
  if (!issues.length) {
    return validation === "pending" ? <LoaderCircle className="size-3.5 animate-spin text-ink-3" aria-label="Checking the design" /> : null;
  }
  return (
    <Menu>
      <MenuTrigger asChild>
        <button
          type="button"
          className={cn(
            "flex items-center gap-1.5 rounded-sm border px-2 py-1 font-mono text-xs text-ink",
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
  const setCalibrateOpen = useUi((s) => s.setCalibrateOpen);
  const setImportOpen = useUi((s) => s.setImportOpen);
  const labs = useUi((s) => s.labs);
  const setLabs = useUi((s) => s.setLabs);

  // Only the project name gives way when the bar is short of room: the groups beside it keep their
  // width, so the workspace tabs, the transport and the run controls never run into one another.
  // Below 2xl (1536 px) they make room for it: the theme moves into the menu, the scenario's name
  // and the transport's display get shorter.
  return (
    <header className="island relative z-30 flex h-[60px] shrink-0 items-center gap-3 !overflow-visible border-b border-line px-3">
      <div className="flex shrink items-center gap-1.5">
        <Tip content="All projects">
          <Link href="/projects" aria-label="Back to projects" className="shrink-0 rounded-md p-1 hover:bg-surface-2">
            <LogoMark className="size-6" />
          </Link>
        </Tip>
        <ProjectName name={name} onChange={(value) => setMetadata({ name: value }, "name")} />
        <Tip content={saving ? "Saving…" : dirty ? "Unsaved changes" : "All changes saved"}>
          <span className={cn("size-1.5 shrink-0 rounded-full", saving ? "bg-accent pulse-soft" : dirty ? "bg-warning" : "bg-ink-3/50")} aria-label={saving ? "saving" : dirty ? "unsaved" : "saved"} />
        </Tip>
        <span className="ml-2 hidden shrink-0 xl:block">
          <WorkspaceSwitch />
        </span>
      </div>

      <div className="flex flex-1 items-center justify-center gap-2">
        <span className="xl:hidden">
          <WorkspaceSwitch />
        </span>
        <span className="hidden xl:flex xl:items-center xl:gap-2">
          <TransportButtons />
          <TransportLcd />
        </span>
      </div>

      <div className="flex shrink-0 items-center justify-end gap-1.5">
        <ActivityButton />
        <ProblemsPill />
        <RunControl />
        <Tip content={<>Save <Kbd>Ctrl S</Kbd></>}>
          <Button variant="ghost" size="icon" onClick={() => void save()} disabled={saving} aria-label="Save" className={cn("hidden xl:inline-flex", dirty && "text-ink")}>
            {saving ? <LoaderCircle className="animate-spin" /> : <Save />}
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
        <span className="hidden 2xl:inline-flex">
          <ThemeMenu />
        </span>
        <Menu>
          <MenuTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="More actions">
              <MoreHorizontal />
            </Button>
          </MenuTrigger>
          <MenuContent align="end" className="w-64">
            <MenuItem onSelect={() => void save()} disabled={saving} className="xl:hidden">
              <Save /> Save <Kbd className="ml-auto">Ctrl S</Kbd>
            </MenuItem>
            <MenuItem onSelect={toggleTheme} className="2xl:hidden">
              <Moon /> Switch light / dark
            </MenuItem>
            <MenuItem onSelect={() => setPaletteOpen(true)}>
              <Search /> Search commands <Kbd className="ml-auto">{modKey()} K</Kbd>
            </MenuItem>
            <MenuItem onSelect={undo} disabled={!canUndo}>
              <Undo2 /> Undo <Kbd className="ml-auto">Ctrl Z</Kbd>
            </MenuItem>
            <MenuItem onSelect={redo} disabled={!canRedo}>
              <Redo2 /> Redo <Kbd className="ml-auto">Ctrl Shift Z</Kbd>
            </MenuItem>
            <MenuItem onSelect={() => void autoLayout()}>
              <LayoutGrid /> Tidy up <Kbd className="ml-auto">Shift L</Kbd>
            </MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={onToggleInspector}>
              <PanelRight /> {showInspector ? "Hide side panel" : "Show side panel"}
            </MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={() => setExportOpen(true)}>
              <FileCode2 /> Export…
            </MenuItem>
            <MenuItem onSelect={() => setCalibrateOpen(true)}>
              <Gauge /> Calibrate from telemetry…
            </MenuItem>
            <MenuItem onSelect={exportJson}>
              <Download /> Download JSON
            </MenuItem>
            <MenuItem onSelect={() => fileRef.current?.click()}>
              <Upload /> Import JSON
            </MenuItem>
            <MenuItem onSelect={() => setImportOpen(true)}>
              <FileInput /> Import from compose, Kubernetes, Terraform…
            </MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={() => setLabs(!labs)}>
              <TestTubeDiagonal /> Labs parts (experimental)
              {labs && <Check className="ml-auto" aria-label="on" />}
            </MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={startTour}>
              <CircleHelp /> Take the tour
            </MenuItem>
            <MenuItem onSelect={() => onShortcutsOpenChange(true)}>
              <Keyboard /> Shortcuts <Kbd className="ml-auto">?</Kbd>
            </MenuItem>
            <MenuItem asChild>
              <a href="/api/v1/docs" target="_blank" rel="noreferrer">
                <BookOpen /> API docs
              </a>
            </MenuItem>
          </MenuContent>
        </Menu>
        <UserMenu showWorkspace={false} />
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
