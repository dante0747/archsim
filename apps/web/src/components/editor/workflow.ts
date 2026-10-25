"use client";

/**
 * The studio workflow: Design → Test → Results, plus the Dashboard (internal keys: build, stress,
 * decide, dashboard). Derives each workspace's progress and one "next
 * step" suggestion from editor/run state, so the UI always tells a newcomer what to do next.
 */
import { useReactFlow } from "@xyflow/react";
import { useCallback } from "react";
import { compact } from "@/lib/format";
import { isClientType } from "@/lib/theme";
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
  const mode = useUi((s) => s.mode);
  const nodeCount = useEditor((s) => s.nodes.length);
  // Clients that call nothing yet: the most common place a new design stalls.
  const loneClientsId = useEditor((s) => {
    const src = s.nodes.find((n) => isClientType(n.data.type, s.catalog) && !s.edges.some((e) => e.source === n.id));
    return src ? src.id : null;
  });
  const errorCount = useEditor((s) => s.issues.filter((i) => i.severity === "error").length);
  const firstError = useEditor((s) => s.issues.find((i) => i.severity === "error") ?? null);
  const rps = useEditor((s) => s.workload.rps);
  const durationS = useEditor((s) => s.workload.durationS);
  const faultCount = useEditor((s) => s.workload.faults?.length ?? 0);
  const followRun = useRuns((s) => s.runs.find((r) => r.id === s.followRunId) ?? null);
  const succeededCount = useRuns((s) => s.runs.filter((r) => r.status === "succeeded").length);
  const activeRunId = useRuns((s) => s.activeRunId);
  const hasComparison = useRuns((s) => s.comparison !== null);
  const insightsSeenFor = useUi((s) => s.insightsSeenFor);
  const dismissedHint = useUi((s) => s.dismissedHint);

  const running = !!followRun && (followRun.status === "queued" || followRun.status === "running");
  const hasRun = succeededCount > 0;
  const seen = !!activeRunId && insightsSeenFor === activeRunId;

  const steps: Step[] = [
    {
      key: "build",
      label: "Design",
      caption: "draw your system",
      hint: "Draw the system",
      state: nodeCount > 0 && errorCount === 0 ? "done" : "todo",
    },
    {
      key: "stress",
      label: "Test",
      caption: "traffic & failures",
      hint: "Traffic, failures, replay",
      state: hasRun ? "done" : "todo",
      busy: running,
    },
    {
      key: "decide",
      label: "Results",
      caption: "goals · cost · limits",
      hint: "Did it hold up? What does it cost?",
      state: seen || hasComparison ? "done" : "todo",
    },
    {
      key: "dashboard",
      label: "Dashboard",
      caption: "every metric over time",
      hint: "Watch every metric over time",
      state: "todo",
    },
  ];

  const goTo = useCallback((key: Workspace) => {
    const ui = useUi.getState();
    if (key === "decide") openDecide();
    else ui.setMode(key);
  }, []);

  let next: NextHint | null = null;
  if (nodeCount === 0) {
    next = { key: "empty", tone: "info", text: "Start by adding Clients", actionLabel: "Add Clients" };
  } else if (loneClientsId) {
    next = {
      key: `connect-clients:${loneClientsId}`,
      tone: "info",
      text: "Connect Clients to a server",
      actionLabel: "Add API servers",
    };
  } else if (errorCount > 0 && firstError) {
    next = {
      key: `err:${firstError.code}:${firstError.nodeId ?? firstError.edgeId ?? ""}`,
      tone: "critical",
      text: firstError.message,
      actionLabel: firstError.nodeId || firstError.edgeId ? "Show me" : undefined,
    };
  } else if (running && followRun) {
    next = {
      key: "running",
      tone: "busy",
      text: followRun.status === "queued" ? "Waiting to start…" : "Simulating…",
      progress: followRun.progress,
    };
  } else if (!hasRun) {
    // The run itself lives on the one Simulate button in the top bar; hints only point at it.
    next =
      mode === "stress"
        ? {
            key: "ready-stress",
            tone: "info",
            text: faultCount ? `${faultCount} failure${faultCount > 1 ? "s" : ""} planned · press Simulate` : `Press Simulate · ${compact(rps)} req/s for ${compact(durationS)} s`,
          }
        : {
            key: "ready",
            tone: "info",
            text: `Ready · press Simulate`,
          };
  }
  if (next && next.key === dismissedHint && next.tone !== "busy") next = null;

  const doNext = useCallback(() => {
    const ed = useEditor.getState();
    if (!next) return;
    if (next.key === "empty") {
      ed.addNode("traffic_source", { x: 0, y: 0 });
      window.setTimeout(() => void rf.fitView({ padding: 0.4, maxZoom: 1, duration: 250 }), 30);
    } else if (next.key.startsWith("connect-clients:")) {
      const src = ed.nodes.find((n) => n.id === next.key.slice("connect-clients:".length));
      if (!src) return;
      const id = ed.addNode("service", { x: src.position.x + 320, y: src.position.y });
      if (id) useEditor.getState().connect({ source: src.id, target: id, sourceHandle: null, targetHandle: null });
      window.setTimeout(() => void rf.fitView({ padding: 0.4, maxZoom: 1, duration: 250 }), 30);
    } else if (next.key.startsWith("err:")) {
      const err = ed.issues.find((i) => i.severity === "error");
      if (err) ed.select({ nodeId: err.nodeId ?? null, edgeId: err.edgeId ?? null });
    } else if (next.key.startsWith("insights:")) {
      openDecide();
    }
  }, [next, rf]);

  return { steps, goTo, next, doNext, running, mode };
}
