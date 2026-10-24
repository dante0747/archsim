"use client";

/**
 * The studio workflow: Build → Stress → Decide. Derives each workspace's progress and one "next
 * step" suggestion from editor/run state, so the UI always tells a newcomer what to do next.
 */
import { useReactFlow } from "@xyflow/react";
import { useCallback } from "react";
import { useEditorActions } from "@/components/editor/actions";
import { compact } from "@/lib/format";
import type { Report } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { type Workspace, useUi } from "@/store/ui";

export type StepState = "done" | "todo";

export interface Step {
  key: Workspace;
  label: string;
  caption: string;
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

/** Open the decision workspace (optionally a results section) and remember the run was seen. */
export function openDecide() {
  const ui = useUi.getState();
  ui.setMode("decide");
  const id = useRuns.getState().activeRunId;
  if (id) ui.markInsightsSeen(id);
}

/** Switch to Stress and replay the active run from the start. */
export function replayActiveRun() {
  const ui = useUi.getState();
  ui.setMode("stress");
  if (useRuns.getState().activeRunId) {
    ui.setPlayhead(0);
    ui.setPlaying(true);
  }
}

export function useWorkflow() {
  const rf = useReactFlow();
  const { run } = useEditorActions();
  const mode = useUi((s) => s.mode);
  const nodeCount = useEditor((s) => s.nodes.length);
  const errorCount = useEditor((s) => s.issues.filter((i) => i.severity === "error").length);
  const firstError = useEditor((s) => s.issues.find((i) => i.severity === "error") ?? null);
  const rps = useEditor((s) => s.workload.rps);
  const durationS = useEditor((s) => s.workload.durationS);
  const faultCount = useEditor((s) => s.workload.faults?.length ?? 0);
  const scenarioCount = useEditor((s) => s.scenarios.length);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const succeededCount = useRuns((s) => s.runs.filter((r) => r.status === "succeeded").length);
  const activeRunId = useRuns((s) => s.activeRunId);
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const hasComparison = useRuns((s) => s.comparison !== null);
  const starting = useRuns((s) => s.starting);
  const insightsSeenFor = useUi((s) => s.insightsSeenFor);
  const dismissedHint = useUi((s) => s.dismissedHint);

  const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
  const hasRun = succeededCount > 0;
  const seen = !!activeRunId && insightsSeenFor === activeRunId;

  const steps: Step[] = [
    {
      key: "build",
      label: "Build",
      caption: "design with real tech",
      hint: "Pick technologies, wire them, set capacity. A design review flags risks as you go.",
      state: nodeCount > 0 && errorCount === 0 ? "done" : "todo",
    },
    {
      key: "stress",
      label: "Stress",
      caption: "traffic · chaos · replay",
      hint: "Shape traffic, inject faults, run, and replay what happened second by second.",
      state: hasRun ? "done" : "todo",
      busy: running,
    },
    {
      key: "decide",
      label: "Decide",
      caption: "SLOs · cost · capacity",
      hint: "Scorecard against your SLOs, monthly cost, trade-offs between options, breaking point.",
      state: seen || hasComparison ? "done" : "todo",
    },
  ];

  const goTo = useCallback((key: Workspace) => {
    const ui = useUi.getState();
    if (key === "decide") openDecide();
    else ui.setMode(key);
  }, []);

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
      text: followRun.status === "queued" ? `Queued: ${followRun.label}` : `Recording ${followRun.label}…`,
      progress: followRun.progress,
    };
  } else if (!hasRun) {
    next =
      mode === "stress"
        ? {
            key: "ready-stress",
            tone: "info",
            text: faultCount
              ? `${faultCount} fault${faultCount > 1 ? "s" : ""} armed. Run the experiment and watch the system react.`
              : `Shape traffic or add a fault on the timeline, or just run ${compact(rps)} req/s for ${compact(durationS)} s.`,
            actionLabel: starting ? undefined : "Run",
          }
        : {
            key: "ready",
            tone: "info",
            text: `Design is valid. Stress it: ${compact(rps)} req/s for ${compact(durationS)} s, then replay what happens.`,
            actionLabel: starting ? undefined : "Run in Stress",
          };
  } else if (report && activeRunId && !seen && !hasComparison) {
    const v = verdictOf(report);
    next =
      mode === "build"
        ? { key: `replay:${activeRunId}`, tone: v.tone === "ok" ? "success" : "critical", text: `Results are in. ${v.title}.`, actionLabel: "Replay" }
        : { key: `insights:${activeRunId}`, tone: v.tone === "ok" ? "success" : "critical", text: `Recorded. ${v.title}.`, actionLabel: "Open scorecard" };
  } else if (!hasComparison && scenarioCount >= 2) {
    next = { key: "compare", tone: "info", text: "Try alternatives: run every scenario and compare cost against latency.", actionLabel: "Run all & compare" };
  }
  if (next && next.key === dismissedHint && next.tone !== "busy") next = null;

  const doNext = useCallback(() => {
    const ed = useEditor.getState();
    const runs = useRuns.getState();
    if (!next) return;
    if (next.key === "empty") {
      ed.addNode("traffic_source", { x: 0, y: 0 });
      window.setTimeout(() => void rf.fitView({ padding: 0.4, maxZoom: 1, duration: 250 }), 30);
    } else if (next.key.startsWith("err:")) {
      const err = ed.issues.find((i) => i.severity === "error");
      if (err) ed.select({ nodeId: err.nodeId ?? null, edgeId: err.edgeId ?? null });
    } else if (next.key === "ready" || next.key === "ready-stress") {
      void run();
    } else if (next.key.startsWith("replay:")) {
      replayActiveRun();
    } else if (next.key.startsWith("insights:")) {
      openDecide();
    } else if (next.key === "compare") {
      void runs.startBatch(ed.toDocument(), ed.scenarios.slice(0, 10).map((s) => s.id));
      useUi.getState().setMode("decide");
    }
  }, [next, rf, run]);

  return { steps, goTo, next, doNext, running, mode };
}
