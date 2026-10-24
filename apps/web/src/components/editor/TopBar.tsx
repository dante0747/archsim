"use client";

import {
  ArrowLeft,
  BookOpen,
  CircleAlert,
  CircleCheck,
  Download,
  Keyboard,
  LayoutGrid,
  LoaderCircle,
  MoreHorizontal,
  PanelLeft,
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
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/input";
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
  ["Shift L", "Auto-layout"],
  ["Delete / Backspace", "Delete selection"],
  ["Shift + drag", "Box-select"],
  ["Ctrl/⌘ + click", "Add to selection"],
  ["Esc", "Clear selection"],
  ["?", "Show this help"],
];

export function TopBar({
  showPalette,
  showInspector,
  onTogglePalette,
  onToggleInspector,
  shortcutsOpen,
  onShortcutsOpenChange,
}: {
  showPalette: boolean;
  showInspector: boolean;
  onTogglePalette: () => void;
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
  const issues = useEditor((s) => s.issues);
  const validation = useEditor((s) => s.validation);
  const select = useEditor((s) => s.select);
  const scenarios = useEditor((s) => s.scenarios);
  const selectedScenarioId = useRuns((s) => s.selectedScenarioId);
  const selectScenario = useRuns((s) => s.selectScenario);
  const starting = useRuns((s) => s.starting);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const cancel = useRuns((s) => s.cancel);
  const { save, run, exportJson, importJson } = useEditorActions();
  const fileRef = useRef<HTMLInputElement>(null);

  const errors = issues.filter((i) => i.severity === "error");
  const warnings = issues.filter((i) => i.severity === "warning");
  const running = followRun && (followRun.status === "queued" || followRun.status === "running");
  const scenarioValue = scenarios.some((s) => s.id === selectedScenarioId) ? (selectedScenarioId ?? "") : "";

  return (
    <header className="flex h-12 shrink-0 items-center gap-1 border-b border-line bg-surface px-2">
      <Tip content="All projects">
        <Button variant="ghost" size="icon" asChild>
          <Link href="/" aria-label="Back to projects">
            <ArrowLeft />
          </Link>
        </Button>
      </Tip>
      <Button variant="ghost" size="icon" onClick={onTogglePalette} aria-pressed={showPalette} aria-label="Toggle component palette" title="Toggle palette">
        <PanelLeft />
      </Button>
      <input
        value={name}
        onChange={(e) => setMetadata({ name: e.target.value }, "name")}
        aria-label="Project name"
        maxLength={120}
        className="h-8 w-40 min-w-0 rounded-md border border-transparent bg-transparent px-2 text-sm font-semibold text-ink hover:border-line focus-visible:border-accent focus-visible:outline-none md:w-64"
      />
      <span className="hidden whitespace-nowrap text-[11px] text-ink-3 sm:inline" aria-live="polite">
        {saving ? "Saving…" : dirty ? "Unsaved changes" : "Saved"}
      </span>

      <div className="mx-1 hidden h-5 w-px bg-line md:block" />
      <div className="hidden items-center md:flex">
        <Tip content={<>Undo <Kbd>Ctrl Z</Kbd></>}>
          <Button variant="ghost" size="icon" onClick={undo} disabled={!canUndo} aria-label="Undo">
            <Undo2 />
          </Button>
        </Tip>
        <Tip content={<>Redo <Kbd>Ctrl Shift Z</Kbd></>}>
          <Button variant="ghost" size="icon" onClick={redo} disabled={!canRedo} aria-label="Redo">
            <Redo2 />
          </Button>
        </Tip>
        <Tip content={<>Auto-layout <Kbd>Shift L</Kbd></>}>
          <Button variant="ghost" size="icon" onClick={() => void autoLayout()} aria-label="Auto-layout">
            <LayoutGrid />
          </Button>
        </Tip>
      </div>

      <Menu>
        <MenuTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className="gap-1"
            aria-label={`${errors.length} errors, ${warnings.length} warnings`}
          >
            {validation === "pending" ? (
              <LoaderCircle className="animate-spin text-ink-3" />
            ) : errors.length ? (
              <CircleAlert style={{ color: "var(--status-critical)" }} />
            ) : warnings.length ? (
              <TriangleAlert style={{ color: "var(--status-serious)" }} />
            ) : (
              <CircleCheck style={{ color: "var(--status-good)" }} />
            )}
            <span className="hidden text-xs lg:inline">
              {errors.length ? `${errors.length} error${errors.length > 1 ? "s" : ""}` : warnings.length ? `${warnings.length} warning${warnings.length > 1 ? "s" : ""}` : "Valid"}
            </span>
          </Button>
        </MenuTrigger>
        <MenuContent align="start" className="max-h-96 w-96 overflow-y-auto">
          <MenuLabel>{issues.length ? "Problems" : "No problems: ready to simulate"}</MenuLabel>
          {issues.map((i, k) => (
            <MenuItem
              key={k}
              className="items-start"
              onSelect={() => (i.nodeId || i.edgeId ? select({ nodeId: i.nodeId, edgeId: i.edgeId }) : undefined)}
            >
              {i.severity === "error" ? (
                <CircleAlert className="mt-0.5 shrink-0" style={{ color: "var(--status-critical)" }} />
              ) : (
                <TriangleAlert className="mt-0.5 shrink-0" style={{ color: "var(--status-serious)" }} />
              )}
              <span className="text-xs">
                {i.message}
                {i.hint && <span className="block text-ink-3">{i.hint}</span>}
              </span>
            </MenuItem>
          ))}
        </MenuContent>
      </Menu>

      <div className="ml-auto flex items-center gap-1.5">
        <NativeSelect
          aria-label="What to simulate"
          value={scenarioValue}
          onChange={(e) => selectScenario(e.target.value || null)}
          className="hidden h-8 w-44 sm:block"
        >
          <option value="">Design as drawn</option>
          {scenarios.map((s) => (
            <option key={s.id} value={s.id}>
              Scenario: {s.name}
            </option>
          ))}
        </NativeSelect>
        {running && followRun ? (
          <div className="flex items-center gap-2">
            <div
              className="hidden h-1.5 w-28 overflow-hidden rounded-full bg-surface-3 sm:block"
              role="progressbar"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(followRun.progress * 100)}
              aria-label="Simulation progress"
            >
              <div className="h-full rounded-full bg-accent transition-[width]" style={{ width: `${followRun.progress * 100}%` }} />
            </div>
            <span className="tabular w-9 text-right text-xs text-ink-2">
              {followRun.status === "queued" ? "queued" : `${Math.round(followRun.progress * 100)}%`}
            </span>
            <Button size="sm" onClick={() => void cancel(followRun.id)}>
              <Square /> Cancel
            </Button>
          </div>
        ) : (
          <Tip content={<>Run simulation <Kbd>Ctrl Enter</Kbd></>}>
            <Button variant="primary" onClick={() => void run()} disabled={starting} data-testid="run-button">
              {starting ? <LoaderCircle className="animate-spin" /> : <Play />} Run
            </Button>
          </Tip>
        )}
        <Tip content={<>Save <Kbd>Ctrl S</Kbd></>}>
          <Button onClick={() => void save()} disabled={saving} className={cn(!dirty && "text-ink-3")} aria-label="Save">
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
            <MenuItem onSelect={exportJson}>
              <Download /> Export JSON
            </MenuItem>
            <MenuItem onSelect={() => fileRef.current?.click()}>
              <Upload /> Import JSON into this project
            </MenuItem>
            <MenuSeparator />
            <MenuItem className="md:hidden" onSelect={undo} disabled={!canUndo}>
              <Undo2 /> Undo
            </MenuItem>
            <MenuItem className="md:hidden" onSelect={redo} disabled={!canRedo}>
              <Redo2 /> Redo
            </MenuItem>
            <MenuItem className="md:hidden" onSelect={() => void autoLayout()}>
              <LayoutGrid /> Auto-layout
            </MenuItem>
            <MenuItem onSelect={() => onShortcutsOpenChange(true)}>
              <Keyboard /> Keyboard shortcuts
            </MenuItem>
            <MenuItem asChild>
              <a href="/api/v1/docs" target="_blank" rel="noreferrer">
                <BookOpen /> API documentation
              </a>
            </MenuItem>
          </MenuContent>
        </Menu>
        <ThemeToggle />
        <Button variant="ghost" size="icon" onClick={onToggleInspector} aria-pressed={showInspector} aria-label="Toggle inspector" title="Toggle inspector">
          <PanelRight />
        </Button>
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
