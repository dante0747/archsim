"use client";

/**
 * Command palette (Ctrl/⌘ K): every editor action, searchable by name. Lists only what can run
 * right now (no Undo without history, no Stop without a run), so it also teaches what is possible.
 */
import { useReactFlow } from "@xyflow/react";
import {
  Activity,
  ArrowLeft,
  BookOpen,
  Boxes,
  CircleAlert,
  Copy,
  Download,
  Flame,
  FlaskConical,
  Gauge,
  GitCompare,
  History,
  Keyboard,
  LayoutDashboard,
  LayoutGrid,
  Library,
  ListChecks,
  Maximize,
  Moon,
  Palette,
  PanelBottomClose,
  Play,
  Redo2,
  Save,
  SlidersHorizontal,
  Square,
  Trash2,
  Undo2,
  Upload,
  ZoomIn,
  ZoomOut,
} from "lucide-react";
import { useRouter } from "next/navigation";
import { Dialog as RDialog } from "radix-ui";
import { useEffect, useMemo, useRef, useState } from "react";
import { useEditorActions } from "@/components/editor/actions";
import { openDecide, replayActiveRun } from "@/components/editor/workflow";
import { findParts, useAddComponent } from "@/components/editor/Palette";
import { SHAPE_OF, ShapeGlyph } from "@/components/shapes";
import { Kbd } from "@/components/ui/misc";
import { THEMES, applyTheme, toggleTheme } from "@/components/ui/theme-toggle";
import { rankCommands } from "@/lib/commands";
import { kindOf } from "@/lib/theme";
import { cn, modKey } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { type DecideSection, useDecide } from "@/store/decide";
import { type DrawerTab, useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

interface Command {
  id: string;
  group: string;
  label: string;
  hint?: string;
  keywords?: string;
  shortcut?: string;
  icon: React.ReactNode;
  run: () => void;
}

function useCommands(onShowShortcuts: () => void, onImport: () => void): Command[] {
  const rf = useReactFlow();
  const router = useRouter();
  const add = useAddComponent();
  const { save, run, exportJson } = useEditorActions();
  const catalog = useEditor((s) => s.catalog);
  const scenarios = useEditor((s) => s.scenarios);
  const canUndo = useEditor((s) => s.past.length > 0);
  const canRedo = useEditor((s) => s.future.length > 0);
  const hasSelection = useEditor((s) => s.nodes.some((n) => n.selected) || s.edges.some((e) => e.selected));
  const firstError = useEditor((s) => s.issues.find((i) => i.severity === "error") ?? null);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const hasReport = useRuns((s) => s.activeRunId !== null);
  const overlay = useRuns((s) => s.overlay);
  const drawerOpen = useRuns((s) => s.drawerOpen);
  const selectedScenarioId = useRuns((s) => s.selectedScenarioId);
  const flow = useUi((s) => s.flow);
  const mod = modKey();

  return useMemo(() => {
    const ed = () => useEditor.getState();
    const runs = () => useRuns.getState();
    const ui = () => useUi.getState();
    const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
    const target = scenarios.find((s) => s.id === selectedScenarioId);
    const drawer = (tab: DrawerTab) => () => {
      runs().openDrawer(tab);
      if (tab === "overview" && runs().activeRunId) ui().markInsightsSeen(runs().activeRunId!);
    };
    const i = "size-4";
    const decide = (section: DecideSection) => () => {
      useDecide.getState().setSection(section);
      openDecide();
    };
    const cmds: (Command | false)[] = [
      // workspaces
      { id: "ws-build", group: "Workspace", label: "Design: draw the system", shortcut: "1", keywords: "workspace edit build", icon: <LayoutGrid className={i} />, run: () => ui().setMode("build") },
      { id: "ws-stress", group: "Workspace", label: "Test: traffic, failures, replay", shortcut: "2", keywords: "workspace stress experiment chaos fault timeline", icon: <Activity className={i} />, run: () => ui().setMode("stress") },
      { id: "ws-decide", group: "Workspace", label: "Results: the answer", shortcut: "3", keywords: "workspace decide scorecard summary verdict slo objectives", icon: <Gauge className={i} />, run: decide("answer") },
      hasReport && { id: "replay", group: "Workspace", label: "Replay the last run", keywords: "flight recorder playback timeline", icon: <Play className={i} />, run: replayActiveRun },
      { id: "tradeoffs", group: "Workspace", label: "Compare options: cost vs. speed", keywords: "pareto scenarios compare cost tradeoff latency", icon: <GitCompare className={i} />, run: decide("compare") },
      { id: "breaking", group: "Workspace", label: "Find the breaking point", keywords: "capacity sweep load knee max throughput", icon: <Flame className={i} />, run: decide("breaking") },
      { id: "report", group: "Workspace", label: "Report (Markdown)", keywords: "export share document decision", icon: <BookOpen className={i} />, run: decide("report") },
      // simulate
      running && {
        id: "stop",
        group: "Simulate",
        label: "Stop simulation",
        hint: followRun?.label ?? undefined,
        icon: <Square className={i} />,
        run: () => void runs().cancel(followRun!.id),
      },
      {
        id: "run",
        group: "Simulate",
        label: "Simulate",
        hint: target ? target.name : "design as drawn",
        shortcut: `${mod} Enter`,
        keywords: "start simulate go",
        icon: <Play className={i} />,
        run: () => void run(),
      },
      ...scenarios.map((s) => ({
        id: `run-${s.id}`,
        group: "Simulate",
        label: `Run scenario: ${s.name}`,
        hint: s.overrides.length ? `${s.overrides.length} change${s.overrides.length > 1 ? "s" : ""}` : "no changes",
        keywords: "what if variant",
        icon: <FlaskConical className={i} />,
        run: () => {
          runs().selectScenario(s.id);
          void run();
        },
      })),
      scenarios.length > 0 && {
        id: "run-all",
        group: "Simulate",
        label: "Simulate all scenarios and compare",
        hint: `${Math.min(scenarios.length, 10)} runs`,
        keywords: "batch compare side by side",
        icon: <GitCompare className={i} />,
        run: () => void runs().startBatch(ed().toDocument(), ed().scenarios.slice(0, 10).map((s) => s.id)),
      },
      // add
      ...(catalog?.components ?? []).map((c) => ({
        id: `add-${c.type}`,
        group: "Add component",
        label: `Add ${c.displayName}`,
        hint: c.description,
        keywords: `${c.type} new insert ${c.category}`,
        icon: <ShapeGlyph shape={SHAPE_OF[kindOf(c.type, c.category)]} w={22} h={14} />,
        run: () => add(c.type),
      })),
      ...(catalog?.components ?? []).flatMap((c) =>
        (c.presets ?? []).map((p) => ({
          id: `add-${c.type}-${p.id}`,
          group: "Add technology",
          label: `Add ${p.name}`,
          hint: `${p.family} · ${c.displayName}`,
          keywords: `${p.technology} ${p.tags.join(" ")} ${c.type} ${p.description}`,
          icon: <ShapeGlyph shape={SHAPE_OF[kindOf(c.type, c.category)]} w={22} h={14} />,
          run: () => add(c.type, p.id),
        })),
      ),
      // go to
      !!firstError && {
        id: "problem",
        group: "Go to",
        label: "First problem",
        hint: firstError.message,
        keywords: "error issue fix validation",
        icon: <CircleAlert className={i} />,
        run: () => {
          ed().setRightTab("inspect");
          ed().select({ nodeId: firstError.nodeId ?? null, edgeId: firstError.edgeId ?? null });
        },
      },
      {
        id: "overview",
        group: "Go to",
        label: "Design overview",
        keywords: "summary checks legend",
        icon: <LayoutDashboard className={i} />,
        run: () => {
          ed().select({});
          ed().setRightTab("inspect");
        },
      },
      {
        id: "traffic",
        group: "Go to",
        label: "Traffic settings",
        keywords: "workload rps requests per second load mix",
        icon: <SlidersHorizontal className={i} />,
        run: () => {
          ed().setRightTab("workload");
          ui().markTrafficVisited();
        },
      },
      { id: "scenarios", group: "Go to", label: "Scenarios", keywords: "what if", icon: <FlaskConical className={i} />, run: () => ed().setRightTab("scenarios") },
      { id: "insights", group: "Go to", label: "Run details", keywords: "results insights findings kpi bottleneck", icon: <Gauge className={i} />, run: drawer("overview") },
      { id: "charts", group: "Go to", label: "Metrics over time", keywords: "time series graph charts", icon: <Activity className={i} />, run: drawer("charts") },
      { id: "components", group: "Go to", label: "Per component results", keywords: "per node table components", icon: <Boxes className={i} />, run: drawer("components") },
      { id: "compare", group: "Go to", label: "Compare two runs", keywords: "diff a b", icon: <GitCompare className={i} />, run: drawer("compare") },
      { id: "history", group: "Go to", label: "Run history", keywords: "runs past", icon: <History className={i} />, run: drawer("runs") },
      { id: "assumptions", group: "Go to", label: "Assumptions", keywords: "inputs measured", icon: <ListChecks className={i} />, run: drawer("assumptions") },
      drawerOpen && { id: "close-drawer", group: "Go to", label: "Close results panel", icon: <PanelBottomClose className={i} />, run: () => runs().setDrawerOpen(false) },
      // canvas
      canUndo && { id: "undo", group: "Canvas", label: "Undo", shortcut: `${mod} Z`, icon: <Undo2 className={i} />, run: () => ed().undo() },
      canRedo && { id: "redo", group: "Canvas", label: "Redo", shortcut: `${mod} Shift Z`, icon: <Redo2 className={i} />, run: () => ed().redo() },
      hasSelection && { id: "dup", group: "Canvas", label: "Duplicate selection", shortcut: `${mod} D`, icon: <Copy className={i} />, run: () => ed().duplicateSelection() },
      hasSelection && {
        id: "del",
        group: "Canvas",
        label: "Delete selection",
        shortcut: "Del",
        icon: <Trash2 className={i} />,
        run: () => {
          const s = ed();
          s.deleteElements(
            s.nodes.filter((n) => n.selected).map((n) => n.id),
            s.edges.filter((e) => e.selected).map((e) => e.id),
          );
        },
      },
      { id: "layout", group: "Canvas", label: "Tidy up layout", shortcut: "Shift L", keywords: "auto layout arrange elk", icon: <LayoutGrid className={i} />, run: () => void ed().autoLayout() },
      { id: "fit", group: "Canvas", label: "Fit to screen", keywords: "zoom view center", icon: <Maximize className={i} />, run: () => void rf.fitView({ padding: 0.15, maxZoom: 1, duration: 250 }) },
      { id: "zin", group: "Canvas", label: "Zoom in", icon: <ZoomIn className={i} />, run: () => void rf.zoomIn({ duration: 150 }) },
      { id: "zout", group: "Canvas", label: "Zoom out", icon: <ZoomOut className={i} />, run: () => void rf.zoomOut({ duration: 150 }) },
      { id: "library", group: "Canvas", label: "Search parts", keywords: "library browse palette technology add", icon: <Library className={i} />, run: findParts },
      hasReport && {
        id: "overlay",
        group: "Canvas",
        label: overlay ? "Hide results on canvas" : "Show results on canvas",
        keywords: "overlay heat status",
        icon: <Flame className={i} />,
        run: () => runs().setOverlay(!overlay),
      },
      hasReport && {
        id: "flow",
        group: "Canvas",
        label: flow ? "Stop live flow animation" : "Animate live flow",
        keywords: "particles dots traffic",
        icon: <Activity className={i} />,
        run: () => ui().setFlow(!flow),
      },
      // project
      { id: "save", group: "Project", label: "Save", shortcut: `${mod} S`, icon: <Save className={i} />, run: () => void save() },
      { id: "export", group: "Project", label: "Export JSON", keywords: "download file", icon: <Download className={i} />, run: exportJson },
      {
        id: "export-infra",
        group: "Project",
        label: "Export docker-compose · Kubernetes · Mermaid",
        keywords: "k8s yaml manifests deploy helm diagram",
        icon: <Download className={i} />,
        run: () => ui().setExportOpen(true),
      },
      { id: "import", group: "Project", label: "Import JSON into this project", keywords: "upload file open", icon: <Upload className={i} />, run: onImport },
      { id: "home", group: "Project", label: "Back to projects", keywords: "home list", icon: <ArrowLeft className={i} />, run: () => router.push("/") },
      { id: "theme", group: "Project", label: "Toggle light / dark theme", keywords: "dark mode light mode appearance", icon: <Moon className={i} />, run: toggleTheme },
      ...THEMES.map((t) => ({
        id: `theme-${t.id}`,
        group: "Project",
        label: `Theme: ${t.label}`,
        keywords: "appearance colours colors palette dark light",
        icon: <Palette className={i} />,
        run: () => applyTheme(t.id),
      })),
      { id: "keys", group: "Project", label: "Keyboard shortcuts", shortcut: "?", keywords: "help hotkeys", icon: <Keyboard className={i} />, run: onShowShortcuts },
      {
        id: "api",
        group: "Project",
        label: "API documentation",
        keywords: "openapi swagger rest",
        icon: <BookOpen className={i} />,
        run: () => window.open("/api/v1/docs", "_blank", "noopener"),
      },
    ];
    return cmds.filter((c): c is Command => !!c);
  }, [
    rf,
    router,
    add,
    save,
    run,
    exportJson,
    catalog,
    scenarios,
    canUndo,
    canRedo,
    hasSelection,
    firstError,
    followRun,
    hasReport,
    overlay,
    drawerOpen,
    selectedScenarioId,
    flow,
    mod,
    onShowShortcuts,
    onImport,
  ]);
}

function PaletteBody({ onShowShortcuts, onImport }: { onShowShortcuts: () => void; onImport: () => void }) {
  const setOpen = useUi((s) => s.setPaletteOpen);
  const commands = useCommands(onShowShortcuts, onImport);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  const filtered = useMemo(() => rankCommands(commands, q), [commands, q]);
  const current = Math.min(active, Math.max(0, filtered.length - 1));

  useEffect(() => {
    listRef.current?.querySelector(`[data-index="${current}"]`)?.scrollIntoView({ block: "nearest" });
  }, [current]);

  const exec = (c: Command | undefined) => {
    if (!c) return;
    setOpen(false);
    window.setTimeout(c.run, 0);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive(filtered.length ? (current + 1) % filtered.length : 0);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive(filtered.length ? (current - 1 + filtered.length) % filtered.length : 0);
    } else if (e.key === "Enter") {
      e.preventDefault();
      exec(filtered[current]);
    }
  };

  return (
    <>
      <div className="flex items-center gap-2 border-b border-line px-3">
        <span className="font-mono text-sm font-semibold text-accent" aria-hidden>
          &gt;
        </span>
        <input
          autoFocus
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setActive(0);
          }}
          onKeyDown={onKeyDown}
          placeholder="Type a command: run, add cache, compare, export…"
          role="combobox"
          aria-expanded
          aria-controls="command-list"
          aria-activedescendant={filtered[current] ? `cmd-${filtered[current].id}` : undefined}
          aria-label="Search commands"
          className="h-12 min-w-0 flex-1 bg-transparent font-mono text-[13px] text-ink placeholder:text-ink-3 focus:outline-none"
        />
        <Kbd>esc</Kbd>
      </div>
      <div ref={listRef} id="command-list" role="listbox" aria-label="Commands" className="max-h-[min(56vh,460px)] overflow-y-auto p-1.5">
        {filtered.length === 0 && <p className="px-3 py-6 text-center font-mono text-xs text-ink-3">no command matches “{q}”</p>}
        {filtered.map((c, idx) => {
          const header = idx === 0 || filtered[idx - 1].group !== c.group;
          return (
            <div key={c.id}>
              {header && (
                <p className="microlabel px-2.5 pb-1 pt-2.5">
                  <span className="mr-1 opacity-60" aria-hidden>
            {"//"}
          </span>
                  {c.group}
                </p>
              )}
              <div
                id={`cmd-${c.id}`}
                role="option"
                aria-selected={idx === current}
                data-index={idx}
                onMouseMove={() => idx !== current && setActive(idx)}
                onClick={() => exec(c)}
                className={cn(
                  "relative flex cursor-default items-center gap-2.5 rounded-sm px-2.5 py-1.5 text-[13px] text-ink",
                  idx === current && "bg-surface-2 before:absolute before:inset-y-1 before:left-0 before:w-0.5 before:rounded-full before:bg-accent",
                )}
              >
                <span className={cn("flex w-6 shrink-0 justify-center", idx === current ? "text-accent" : "text-ink-3")}>{c.icon}</span>
                <span className="shrink-0">{c.label}</span>
                {c.hint && <span className="min-w-0 truncate text-xs text-ink-3">{c.hint}</span>}
                {c.shortcut && <Kbd className="ml-auto shrink-0">{c.shortcut}</Kbd>}
              </div>
            </div>
          );
        })}
      </div>
      <div className="flex items-center gap-3 border-t border-line px-3 py-1.5 font-mono text-[10.5px] text-ink-3">
        <span>↑↓ navigate</span>
        <span>↵ run</span>
        <span>esc close</span>
        <span className="ml-auto">{filtered.length} commands</span>
      </div>
    </>
  );
}

export function CommandPalette({ onShowShortcuts }: { onShowShortcuts: () => void }) {
  const open = useUi((s) => s.paletteOpen);
  const setOpen = useUi((s) => s.setPaletteOpen);
  const { importJson } = useEditorActions();
  const fileRef = useRef<HTMLInputElement>(null);
  return (
    <>
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
      <RDialog.Root open={open} onOpenChange={setOpen}>
        <RDialog.Portal>
          <RDialog.Overlay className="fixed inset-0 z-50 bg-black/45" />
          <RDialog.Content
            className="surface-glass fixed left-1/2 top-[12vh] z-50 w-[min(640px,calc(100vw-32px))] -translate-x-1/2 overflow-hidden rounded-xl"
            aria-describedby={undefined}
          >
            <RDialog.Title className="sr-only">Command palette</RDialog.Title>
            {open && <PaletteBody onShowShortcuts={onShowShortcuts} onImport={() => fileRef.current?.click()} />}
          </RDialog.Content>
        </RDialog.Portal>
      </RDialog.Root>
    </>
  );
}
