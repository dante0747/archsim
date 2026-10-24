"use client";

import { Boxes, ChevronDown, ChevronUp, FileText, GitCompareArrows, History, LayoutDashboard, LineChart } from "lucide-react";
import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/misc";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/overlays";
import { cn } from "@/lib/utils";
import { type DrawerTab, useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { AssumptionsTab } from "./AssumptionsTab";
import { ChartsTab } from "./ChartsTab";
import { CompareTab } from "./CompareTab";
import { ComponentsTab } from "./ComponentsTab";
import { OverviewTab } from "./OverviewTab";
import { RunsTab } from "./RunsTab";

export function ResultsDrawer() {
  const open = useRuns((s) => s.drawerOpen);
  const tab = useRuns((s) => s.drawerTab);
  const height = useRuns((s) => s.drawerHeight);
  const setHeight = useRuns((s) => s.setDrawerHeight);
  const setOpen = useRuns((s) => s.setDrawerOpen);
  const openDrawer = useRuns((s) => s.openDrawer);
  const overlay = useRuns((s) => s.overlay);
  const setOverlay = useRuns((s) => s.setOverlay);
  const hasReport = useRuns((s) => s.activeRunId !== null);
  const runCount = useRuns((s) => s.runs.length);
  const dragStart = useRef<{ y: number; h: number } | null>(null);
  const activeRunId = useRuns((s) => s.activeRunId);
  const markInsightsSeen = useUi((s) => s.markInsightsSeen);
  useEffect(() => {
    if (open && tab === "overview" && activeRunId) markInsightsSeen(activeRunId);
  }, [open, tab, activeRunId, markInsightsSeen]);

  const onPointerDown = (e: React.PointerEvent) => {
    dragStart.current = { y: e.clientY, h: height };
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: React.PointerEvent) => {
    if (!dragStart.current) return;
    setHeight(dragStart.current.h + (dragStart.current.y - e.clientY));
  };
  const onPointerUp = () => {
    dragStart.current = null;
  };

  return (
    <section
      className="relative z-10 flex shrink-0 flex-col border-t border-line bg-page"
      style={{ height: open ? height : undefined, maxHeight: open ? "60vh" : undefined }}
      aria-label="Simulation results"
    >
      {open && (
        <div
          role="separator"
          aria-orientation="horizontal"
          aria-label="Resize results panel"
          tabIndex={0}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onKeyDown={(e) => {
            if (e.key === "ArrowUp") setHeight(height + 40);
            if (e.key === "ArrowDown") setHeight(height - 40);
          }}
          className="absolute inset-x-0 -top-1 z-10 h-2 cursor-row-resize hover:bg-accent/30"
        />
      )}
      <Tabs
        value={tab}
        onValueChange={(v) => openDrawer(v as DrawerTab)}
        className="flex min-h-0 flex-1 flex-col"
      >
        <div className="flex items-center gap-2 border-b border-line bg-surface pl-3 pr-2">
          <span className="microlabel hidden sm:inline">Results</span>
          <TabsList className="min-w-0 flex-1 overflow-x-auto border-b-0" onClick={() => !open && setOpen(true)}>
            <TabsTrigger value="overview">
              <LayoutDashboard /> Insights
            </TabsTrigger>
            <TabsTrigger value="charts">
              <LineChart /> Timeline
            </TabsTrigger>
            <TabsTrigger value="components">
              <Boxes /> Components
            </TabsTrigger>
            <TabsTrigger value="compare">
              <GitCompareArrows /> Compare
            </TabsTrigger>
            <TabsTrigger value="runs">
              <History /> History{runCount ? ` · ${runCount}` : ""}
            </TabsTrigger>
            <TabsTrigger value="assumptions">
              <FileText /> Assumptions
            </TabsTrigger>
          </TabsList>
          {hasReport && (
            <label className="hidden shrink-0 items-center gap-2 text-[11px] text-ink-2 md:flex">
              <Switch checked={overlay} onCheckedChange={setOverlay} label="Show results on canvas" />
              Show on canvas
            </label>
          )}
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={() => setOpen(!open)}
            aria-label={open ? "Collapse results" : "Expand results"}
            aria-expanded={open}
          >
            {open ? <ChevronDown /> : <ChevronUp />}
          </Button>
        </div>
        {open && (
          <div className={cn("min-h-0 flex-1 overflow-y-auto")}>
            <TabsContent value="overview">
              <OverviewTab />
            </TabsContent>
            <TabsContent value="charts">
              <ChartsTab />
            </TabsContent>
            <TabsContent value="components">
              <ComponentsTab />
            </TabsContent>
            <TabsContent value="compare">
              <CompareTab />
            </TabsContent>
            <TabsContent value="runs">
              <RunsTab />
            </TabsContent>
            <TabsContent value="assumptions">
              <AssumptionsTab />
            </TabsContent>
          </div>
        )}
      </Tabs>
    </section>
  );
}
