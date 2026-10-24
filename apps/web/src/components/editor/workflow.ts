"use client";

/**
 * The guided workflow: Design → Traffic → Simulate → Insights → Compare.
 * Derives step states and one "next step" suggestion from editor/run state, so the UI always
 * tells a newcomer what to do next.
 */
import { useReactFlow } from "@xyflow/react";
import { useCallback } from "react";
import { useEditorActions } from "@/components/editor/actions";
import { compact } from "@/lib/format";
import type { Report } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export type StepKey = "design" | "traffic" | "simulate" | "insights" | "compare";
export type StepState = "done" | "current" | "todo";

export interface Step {
  key: StepKey;
  label: string;
  hint: string;
  state: StepState;
  busy?: boolean;
}

export interface NextHint {
  key: string;
  tone: "info" | "critical" | "busy" | "success";
  text: string;
  actionLabel?: string;
  progress?: number;
}

export function verdictOf(report: Report): { title: string; tone: "ok" | "warn" | "bad" } {
  const bottleneck = report.findings.find((f) => f.kind === "bottleneck");
  if (bottleneck) return { title: bottleneck.title, tone: "bad" };
  const hot = report.findings.find((f) => f.kind === "hot" || f.kind === "throughput_gap");
  if (hot) return { title: hot.title, tone: "warn" };
  return { title: "Healthy: no component is saturated", tone: "ok" };
}

export function useWorkflow() {
  const rf = useReactFlow();
  const { run } = useEditorActions();
  const nodeCount = useEditor((s) => s.nodes.length);
  const errorCount = useEditor((s) => s.issues.filter((i) => i.severity === "error").length);
  const firstError = useEditor((s) => s.issues.find((i) => i.severity === "error") ?? null);
  const rps = useEditor((s) => s.workload.rps);
  const durationS = useEditor((s) => s.workload.durationS);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const succeededCount = useRuns((s) => s.runs.filter((r) => r.status === "succeeded").length);
  const activeRunId = useRuns((s) => s.activeRunId);
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const hasComparison = useRuns((s) => s.comparison !== null);
  const starting = useRuns((s) => s.starting);
  const trafficVisited = useUi((s) => s.trafficVisited);
  const insightsSeenFor = useUi((s) => s.insightsSeenFor);
  const dismissedHint = useUi((s) => s.dismissedHint);

  const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
  const hasRun = succeededCount > 0;

  const designDone = nodeCount > 0 && errorCount === 0;
  const trafficDone = designDone && (trafficVisited || hasRun);
  const simulateDone = hasRun;
  const insightsDone = !!activeRunId && insightsSeenFor === activeRunId;
  const compareDone = hasComparison;
  const flags = [designDone, trafficDone, simulateDone, insightsDone, compareDone];
  const firstOpen = flags.findIndex((f) => !f);
  const state = (i: number): StepState => (flags[i] ? "done" : i === firstOpen ? "current" : "todo");

  const steps: Step[] = [
    { key: "design", label: "Design", hint: "Draw components and link them", state: state(0) },
    { key: "traffic", label: "Traffic", hint: "How many requests, which mix", state: state(1) },
    { key: "simulate", label: "Simulate", hint: "Run the discrete-event engine", state: state(2), busy: running },
    { key: "insights", label: "Insights", hint: "Bottlenecks, latency, queues", state: state(3) },
    { key: "compare", label: "Compare", hint: "Scenarios side by side", state: state(4) },
  ];

  const goTo = useCallback(
    (key: StepKey) => {
      const ed = useEditor.getState();
      const runs = useRuns.getState();
      const ui = useUi.getState();
      switch (key) {
        case "design":
          ed.select({});
          ed.setRightTab("inspect");
          runs.setDrawerOpen(false);
          void rf.fitView({ padding: 0.15, maxZoom: 1, duration: 250 });
          break;
        case "traffic":
          ed.setRightTab("workload");
          ui.markTrafficVisited();
          break;
        case "simulate":
          void run();
          break;
        case "insights":
          runs.openDrawer("overview");
          if (runs.activeRunId) ui.markInsightsSeen(runs.activeRunId);
          break;
        case "compare":
          runs.openDrawer("compare");
          break;
      }
    },
    [rf, run],
  );

  let next: NextHint | null = null;
  if (nodeCount === 0) {
    next = { key: "empty", tone: "info", text: "Start with Clients: they generate the traffic. Then add what they call.", actionLabel: "Add Clients" };
  } else if (errorCount > 0 && firstError) {
    next = {
      key: `err:${firstError.code}:${firstError.nodeId ?? firstError.edgeId ?? ""}`,
      tone: "critical",
      text: `${errorCount === 1 ? "1 problem" : `${errorCount} problems`} before you can simulate: ${firstError.message}`,
      actionLabel: firstError.nodeId || firstError.edgeId ? "Show me" : undefined,
    };
  } else if (running && followRun) {
    next = {
      key: "running",
      tone: "busy",
      text: followRun.status === "queued" ? `Queued: ${followRun.label}` : `Simulating ${followRun.label}…`,
      progress: followRun.progress,
    };
  } else if (!hasRun) {
    next = {
      key: "ready",
      tone: "info",
      text: trafficVisited
        ? `Ready. Simulate ${compact(durationS)} s of traffic at ${compact(rps)} req/s and see where it breaks.`
        : `Traffic is ${compact(rps)} req/s for ${compact(durationS)} s. Tweak it in the Traffic tab, or run now.`,
      actionLabel: starting ? undefined : "Run simulation",
    };
  } else if (report && activeRunId && insightsSeenFor !== activeRunId && !hasComparison) {
    const v = verdictOf(report);
    next = { key: `insights:${activeRunId}`, tone: v.tone === "ok" ? "success" : "critical", text: `Results are in. ${v.title}.`, actionLabel: "Open insights" };
  } else if (!hasComparison && scenarioCount >= 2) {
    next = { key: "compare", tone: "info", text: "Try alternatives: run every scenario and compare them side by side.", actionLabel: "Run all & compare" };
  } else if (!hasComparison && succeededCount >= 2) {
    next = { key: "compare-runs", tone: "info", text: "You have several runs. Compare two of them to see what changed and why.", actionLabel: "Compare runs" };
  }
  if (next && next.key === dismissedHint && next.tone !== "busy") next = null;

  const doNext = useCallback(() => {
    const ed = useEditor.getState();
    const runs = useRuns.getState();
    if (!next) return;
    if (next.key === "empty") {
      ed.addNode("traffic_source", { x: 0, y: 0 });
      void setTimeout(() => void rf.fitView({ padding: 0.4, maxZoom: 1, duration: 250 }), 30);
    } else if (next.key.startsWith("err:")) {
      const err = ed.issues.find((i) => i.severity === "error");
      if (err) ed.select({ nodeId: err.nodeId ?? null, edgeId: err.edgeId ?? null });
    } else if (next.key === "ready") {
      void run();
    } else if (next.key.startsWith("insights:")) {
      goTo("insights");
    } else if (next.key === "compare") {
      void runs.startBatch(ed.toDocument(), ed.scenarios.slice(0, 10).map((s) => s.id));
    } else if (next.key === "compare-runs") {
      runs.openDrawer("compare");
    }
  }, [next, rf, run, goTo]);

  return { steps, goTo, next, doNext, running };
}
