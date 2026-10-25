"use client";

/**
 * Results workspace: four places in a tab bar (Answer, Compare, Breaking point, Details) plus the
 * report. The answer comes first; everything else is one click away. Fills its parent and scrolls.
 */
import { CircleCheck, Crosshair, FileText, Gauge, GitCompareArrows, ListTree, LoaderCircle } from "lucide-react";
import { useEffect, useRef } from "react";
import { useDesignReview } from "@/components/build/DesignReview";
import { AssumptionsTab } from "@/components/results/AssumptionsTab";
import { CalculationsTab } from "@/components/results/CalculationsTab";
import { ChartsTab } from "@/components/results/ChartsTab";
import { CompareTab } from "@/components/results/CompareTab";
import { ComponentsTab } from "@/components/results/ComponentsTab";
import { TracesTab } from "@/components/results/TracesTab";
import { OverviewTab } from "@/components/results/OverviewTab";
import { RunsTab } from "@/components/results/RunsTab";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { type CompareView, type DecideSection, type DetailsView, useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { BreakingPoint } from "./BreakingPoint";
import { ReportSection } from "./ReportSection";
import { AnswerPage, ErrorBudgetPanel } from "./Scorecard";
import { TradeoffMap } from "./TradeoffMap";
import { RunPicker, SubTabs, runAllScenariosNow, useDecideRun } from "./shared";

const TABS: { key: Exclude<DecideSection, "report">; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
  { key: "answer", label: "Answer", icon: Gauge },
  { key: "compare", label: "Compare", icon: GitCompareArrows },
  { key: "breaking", label: "Breaking point", icon: Crosshair },
  { key: "details", label: "Details", icon: ListTree },
];

function TabBadge({ section }: { section: DecideSection }) {
  const sweep = useDecide((s) => s.sweep);
  const projectId = useRuns((s) => s.projectId);
  if (section === "breaking" && sweep.projectId === projectId) {
    if (sweep.status === "starting" || sweep.status === "running") return <LoaderCircle className="size-3 animate-spin text-accent" aria-label="running" />;
    if (sweep.status === "done") return <CircleCheck className="size-3 text-ink-3" aria-label="done" />;
  }
  return null;
}

function ComparePage() {
  const view = useDecide((s) => s.compareView);
  const setView = useDecide((s) => s.setCompareView);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  const starting = useRuns((s) => s.starting);
  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3 px-6 pt-5">
        <SubTabs<CompareView>
          value={view}
          onChange={setView}
          items={[
            { key: "map", label: "Cost vs. speed" },
            { key: "pair", label: "Two runs" },
            { key: "runs", label: "All runs" },
          ]}
        />
        {scenarioCount > 0 && (
          <Button size="sm" onClick={runAllScenariosNow} disabled={starting}>
            <GitCompareArrows /> Simulate all scenarios
          </Button>
        )}
      </div>
      {view === "map" && <TradeoffMap />}
      {view === "pair" && <CompareTab />}
      {view === "runs" && <RunsTab />}
    </div>
  );
}

function DetailsSummary() {
  const objectives = useEditor((s) => s.objectives);
  const { report } = useDecideRun();
  return (
    <div>
      <OverviewTab />
      {report && (
        <div className="px-4 pb-4">
          <ErrorBudgetPanel report={report} objectives={objectives} />
        </div>
      )}
    </div>
  );
}

function DetailsPage() {
  const view = useDecide((s) => s.detailsView);
  const setView = useDecide((s) => s.setDetailsView);
  return (
    <div>
      <div className="px-6 pt-5">
        <SubTabs<DetailsView>
          value={view}
          onChange={setView}
          items={[
            { key: "summary", label: "Summary" },
            { key: "time", label: "Over time" },
            { key: "components", label: "Per component" },
            { key: "calculations", label: "Calculations" },
            { key: "traces", label: "Request traces" },
            { key: "assumptions", label: "Assumptions" },
          ]}
        />
      </div>
      <div className="px-2 pb-4">
        {view === "summary" && <DetailsSummary />}
        {view === "time" && <ChartsTab />}
        {view === "components" && <ComponentsTab />}
        {view === "calculations" && <CalculationsTab />}
        {view === "traces" && <TracesTab />}
        {view === "assumptions" && <AssumptionsTab />}
      </div>
    </div>
  );
}

export function DecideWorkspace() {
  const section = useDecide((s) => s.section);
  const setSection = useDecide((s) => s.setSection);
  const setScorecardRunId = useDecide((s) => s.setScorecardRunId);
  const showRun = useRuns((s) => s.showRun);
  const markInsightsSeen = useUi((s) => s.markInsightsSeen);
  const activeRunId = useRuns((s) => s.activeRunId);
  const { run } = useDecideRun();
  const mainRef = useRef<HTMLElement>(null);
  const review = useDesignReview();

  // Requests to "open results" (run history buttons, batch runs finishing) land on the matching place.
  useEffect(
    () =>
      useRuns.subscribe((s, prev) => {
        if (s.drawerTab !== prev.drawerTab || (s.drawerOpen && !prev.drawerOpen)) useDecide.getState().goToDrawerTab(s.drawerTab);
      }),
    [],
  );

  useEffect(() => {
    if (activeRunId) markInsightsSeen(activeRunId);
  }, [activeRunId, markInsightsSeen]);

  useEffect(() => {
    mainRef.current?.scrollTo({ top: 0 });
  }, [section]);

  // One run for every tab: picking it also puts it on the canvas.
  const pickRun = (id: string) => {
    setScorecardRunId(id);
    void showRun(id);
  };

  return (
    <div className="flex h-full min-h-0 flex-col bg-page">
      <div className="flex h-12 shrink-0 items-center gap-1 border-b border-line bg-surface px-4" role="tablist" aria-label="Results">
        {TABS.map((t) => {
          const active = section === t.key;
          return (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setSection(t.key)}
              className={cn(
                "relative flex h-full items-center gap-2 px-3 text-sm font-medium transition-colors after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:rounded-full",
                active ? "text-ink after:bg-accent" : "text-ink-3 hover:text-ink after:bg-transparent",
              )}
            >
              <t.icon className={cn("size-4", active ? "text-accent" : "text-ink-3")} />
              {t.label}
              <TabBadge section={t.key} />
            </button>
          );
        })}
        <div className="ml-auto flex items-center gap-2">
          <RunPicker value={run?.id ?? null} onChange={pickRun} label="" />
          <Button size="sm" variant={section === "report" ? "soft" : "ghost"} onClick={() => setSection("report")}>
            <FileText /> Report
          </Button>
        </div>
      </div>
      <main ref={mainRef} className="min-w-0 flex-1 overflow-y-auto" aria-label="Results">
        {section === "answer" && <AnswerPage />}
        {section === "compare" && <ComparePage />}
        {section === "breaking" && <BreakingPoint />}
        {section === "details" && <DetailsPage />}
        {section === "report" && <ReportSection review={review} />}
      </main>
    </div>
  );
}
