"use client";

/**
 * Decide workspace: the page for choosing between designs. Decision sections (scorecard,
 * trade-offs, breaking point, report) plus the detailed results (insights, timeline, components,
 * compare, history, assumptions). Fills its parent and scrolls internally.
 */
import {
  Activity,
  Boxes,
  CircleCheck,
  Crosshair,
  FileText,
  Gauge,
  GitCompareArrows,
  History,
  ListChecks,
  LoaderCircle,
  ScatterChart,
  Sparkles,
} from "lucide-react";
import { useEffect, useRef } from "react";
import { AssumptionsTab } from "@/components/results/AssumptionsTab";
import { ChartsTab } from "@/components/results/ChartsTab";
import { CompareTab } from "@/components/results/CompareTab";
import { ComponentsTab } from "@/components/results/ComponentsTab";
import { OverviewTab } from "@/components/results/OverviewTab";
import { RunsTab } from "@/components/results/RunsTab";
import { evaluateSlo } from "@/lib/slo";
import { cn } from "@/lib/utils";
import { type DecideSection, sectionForDrawerTab, useDecide } from "@/store/decide";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";
import { BreakingPoint } from "./BreakingPoint";
import { useDesignReview } from "@/components/build/DesignReview";
import { ReportSection } from "./ReportSection";
import { Scorecard } from "./Scorecard";
import { TradeoffMap } from "./TradeoffMap";
import { PageHeader, runLabel, useDecideRun } from "./shared";

interface NavItem {
  key: DecideSection;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}

const GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: "the answer",
    items: [
      { key: "scorecard", label: "Summary", icon: Gauge },
      { key: "insights", label: "What happened", icon: Sparkles },
    ],
  },
  {
    label: "explore",
    items: [
      { key: "tradeoffs", label: "Cost vs. speed", icon: ScatterChart },
      { key: "breaking", label: "Breaking point", icon: Crosshair },
      { key: "compare", label: "Compare two runs", icon: GitCompareArrows },
    ],
  },
  {
    label: "details",
    items: [
      { key: "timeline", label: "Metrics over time", icon: Activity },
      { key: "components", label: "Per component", icon: Boxes },
      { key: "history", label: "Run history", icon: History },
      { key: "assumptions", label: "Assumptions", icon: ListChecks },
    ],
  },
  { label: "share", items: [{ key: "report", label: "Report", icon: FileText }] },
];

const RESULT_PAGES: Partial<Record<DecideSection, { kicker: string; title: string; description: string; body: React.ComponentType }>> = {
  insights: { kicker: "findings", title: "What happened in the run", description: "Verdict, key numbers and findings for the run shown on the canvas.", body: OverviewTab },
  timeline: { kicker: "timeline", title: "Metrics over simulated time", description: "Throughput, latency, failures and utilization, second by second.", body: ChartsTab },
  components: { kicker: "components", title: "Every component, measured", description: "Utilization, queues and waits per component, with drill-down charts.", body: ComponentsTab },
  compare: { kicker: "compare", title: "Two runs side by side", description: "What changed between two runs and why it matters.", body: CompareTab },
  history: { kicker: "run history", title: "All runs", description: "Every run keeps its exact input, seed and result digest, so it can be revisited and reproduced.", body: RunsTab },
  assumptions: { kicker: "assumptions", title: "What the numbers rest on", description: "Values you assumed versus values the simulation derived.", body: AssumptionsTab },
};

function NavBadge({ section }: { section: DecideSection }) {
  const runCount = useRuns((s) => s.runs.length);
  const comparison = useRuns((s) => s.comparison !== null);
  const sweep = useDecide((s) => s.sweep);
  const projectId = useRuns((s) => s.projectId);
  if (section === "history" && runCount > 0) return <span className="tabular font-mono text-[10px] text-ink-3">{runCount}</span>;
  if (section === "compare" && comparison) return <span className="size-1.5 rounded-full bg-accent" aria-label="comparison ready" />;
  if (section === "breaking" && sweep.projectId === projectId) {
    if (sweep.status === "starting" || sweep.status === "running") return <LoaderCircle className="size-3 animate-spin text-accent" aria-label="running" />;
    if (sweep.status === "done") return <CircleCheck className="size-3 text-ink-3" aria-label="done" />;
  }
  return null;
}

/** Compact summary of the run the scorecard shows, at the foot of the nav. */
function RunFooter() {
  const objectives = useEditor((s) => s.objectives);
  const setSection = useDecide((s) => s.setSection);
  const { run, report } = useDecideRun();
  if (!run) return <p className="px-3 py-3 text-[11.5px] leading-relaxed text-ink-3">No runs yet. Press Simulate to score the design.</p>;
  const slo = report ? evaluateSlo(objectives, report) : null;
  const color = slo?.pass === true ? "var(--status-good)" : slo?.pass === false ? "var(--status-critical)" : "var(--ink-3)";
  return (
    <button type="button" onClick={() => setSection("scorecard")} className="block w-full px-3 py-3 text-left hover:bg-surface-2">
      <p className="microlabel">showing</p>
      <p className="mt-0.5 flex items-center gap-1.5 truncate text-[12px] font-medium text-ink">
        <span className="size-1.5 shrink-0 rounded-full" style={{ background: color }} aria-hidden />
        <span className="truncate">{runLabel(run)}</span>
      </p>
      {slo && (
        <p className="font-mono text-[10.5px] text-ink-3">
          {slo.total ? `${slo.met}/${slo.total} objectives met` : "no objectives set"} · seed {run.seed}
        </p>
      )}
    </button>
  );
}

export function DecideWorkspace() {
  const section = useDecide((s) => s.section);
  const setSection = useDecide((s) => s.setSection);
  const markInsightsSeen = useUi((s) => s.markInsightsSeen);
  const activeRunId = useRuns((s) => s.activeRunId);
  const mainRef = useRef<HTMLElement>(null);
  const review = useDesignReview();

  // Requests to "open results" (run history buttons, batch runs finishing) land on the matching section.
  useEffect(
    () =>
      useRuns.subscribe((s, prev) => {
        if (s.drawerTab !== prev.drawerTab || (s.drawerOpen && !prev.drawerOpen)) {
          useDecide.getState().setSection(sectionForDrawerTab(s.drawerTab));
        }
      }),
    [],
  );

  useEffect(() => {
    if (section === "insights" && activeRunId) markInsightsSeen(activeRunId);
  }, [section, activeRunId, markInsightsSeen]);

  useEffect(() => {
    mainRef.current?.scrollTo({ top: 0 });
  }, [section]);

  const page = RESULT_PAGES[section];

  return (
    <div className="flex h-full min-h-0 bg-page">
      <nav className="flex w-52 shrink-0 flex-col border-r border-line bg-surface" aria-label="Results sections">
        <div className="flex-1 overflow-y-auto py-3">
          {GROUPS.map((g) => (
            <div key={g.label} className="mb-3">
              <p className="microlabel px-4 pb-1">
                <span className="mr-1 opacity-60" aria-hidden>
                  {"//"}
                </span>
                {g.label}
              </p>
              <ul>
                {g.items.map((it) => {
                  const active = section === it.key;
                  return (
                    <li key={it.key}>
                      <button
                        type="button"
                        onClick={() => setSection(it.key)}
                        aria-current={active ? "page" : undefined}
                        className={cn(
                          "relative flex w-full items-center gap-2.5 px-4 py-1.5 text-left text-[13px] transition-colors",
                          active ? "bg-surface-2 font-medium text-ink" : "text-ink-2 hover:bg-surface-2 hover:text-ink",
                        )}
                      >
                        {active && <span className="absolute inset-y-1 left-0 w-0.5 rounded-full bg-accent" aria-hidden />}
                        <it.icon className={cn("size-4 shrink-0", active ? "text-accent" : "text-ink-3")} />
                        <span className="flex-1 truncate">{it.label}</span>
                        <NavBadge section={it.key} />
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          ))}
        </div>
        <div className="border-t border-line">
          <RunFooter />
        </div>
      </nav>
      <main ref={mainRef} className="min-w-0 flex-1 overflow-y-auto" aria-label="Results">
        {section === "scorecard" && <Scorecard />}
        {section === "tradeoffs" && <TradeoffMap />}
        {section === "breaking" && <BreakingPoint />}
        {section === "report" && <ReportSection review={review} />}
        {page && (
          <div>
            <PageHeader kicker={page.kicker} title={page.title} description={page.description} />
            <page.body />
          </div>
        )}
      </main>
    </div>
  );
}
