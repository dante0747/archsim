"use client";

/**
 * Design review panel: the architecture linter (lib/review.ts) as a floating panel at the canvas
 * bottom-left. Collapsed it is a chip with counts; clicking a finding selects and frames the
 * components and links it is about.
 */
import { useReactFlow } from "@xyflow/react";
import { ChevronDown, ScanSearch } from "lucide-react";
import { useMemo, useState } from "react";
import { REVIEW_RULES, type ReviewFinding, type ReviewSeverity, countBySeverity, reviewDesign } from "@/lib/review";
import type { EdgeSpec, NodeSpec } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

export const SEVERITY_COLOR: Record<ReviewSeverity, string> = {
  high: "var(--status-critical)",
  medium: "var(--status-serious)",
  low: "var(--ink-3)",
};

/** Findings of the design review for the current document (recomputed when the model changes). */
export function useDesignReview(): ReviewFinding[] {
  const nodes = useEditor((s) => s.nodes);
  const edges = useEditor((s) => s.edges);
  const workload = useEditor((s) => s.workload);
  const catalog = useEditor((s) => s.catalog);
  return useMemo(() => {
    const ns: NodeSpec[] = nodes.map((n) => ({ id: n.id, type: n.data.type, label: n.data.label, config: n.data.config }));
    const es: EdgeSpec[] = edges.map((e) => ({
      id: e.id,
      source: e.source,
      sourcePort: e.sourceHandle ?? "out",
      target: e.target,
      targetPort: e.targetHandle ?? "in",
      config: e.data?.config ?? { requestTypes: ["read", "write"], latencyMs: { kind: "constant", value: 0.5 } },
    }));
    return reviewDesign(ns, es, catalog, workload);
  }, [nodes, edges, workload, catalog]);
}

function Counts({ findings }: { findings: ReviewFinding[] }) {
  const c = countBySeverity(findings);
  return (
    <span className="flex items-center gap-2">
      {(["high", "medium", "low"] as const).map((s) =>
        c[s] ? (
          <span key={s} className="flex items-center gap-1" title={`${c[s]} ${s}`}>
            <span className="size-1.5 rounded-full" style={{ background: SEVERITY_COLOR[s] }} aria-hidden />
            {c[s]} {s === "medium" ? "med" : s}
          </span>
        ) : null,
      )}
    </span>
  );
}

export function DesignReviewPanel() {
  const findings = useDesignReview();
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState<string | null>(null);
  const rf = useReactFlow();

  const focus = (f: ReviewFinding) => {
    setFocused(f.id);
    const nodeIds = new Set(f.nodeIds);
    const edgeIds = new Set(f.edgeIds);
    const st = useEditor.getState();
    if (nodeIds.size === 1 && edgeIds.size === 0) st.select({ nodeId: f.nodeIds[0] });
    else if (nodeIds.size === 0 && edgeIds.size === 1) st.select({ edgeId: f.edgeIds[0] });
    else {
      useEditor.setState((s) => ({
        nodes: s.nodes.map((n) => (!!n.selected !== nodeIds.has(n.id) ? { ...n, selected: nodeIds.has(n.id) } : n)),
        edges: s.edges.map((e) => (!!e.selected !== edgeIds.has(e.id) ? { ...e, selected: edgeIds.has(e.id) } : e)),
      }));
    }
    st.setRightTab("inspect");
    if (f.nodeIds.length) void rf.fitView({ nodes: f.nodeIds.map((id) => ({ id })), padding: 0.5, maxZoom: 1, duration: 300 });
  };

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="surface-glass absolute bottom-3 left-3 z-10 flex h-8 max-w-[240px] items-center gap-2 rounded-lg px-2.5 font-mono text-[11px] text-ink-2 hover:text-ink"
        aria-label={`Design review: ${findings.length} finding${findings.length === 1 ? "" : "s"}`}
      >
        <ScanSearch className="size-3.5 shrink-0 text-ink-3" />
        <span className="text-ink-3">review</span>
        {findings.length === 0 ? (
          <span className="flex items-center gap-1">
            <span className="size-1.5 rounded-full" style={{ background: "var(--status-good)" }} aria-hidden />
            clean
          </span>
        ) : (
          <Counts findings={findings} />
        )}
      </button>
    );
  }

  return (
    <section
      className="surface-glass absolute bottom-[60px] left-3 z-10 flex max-h-[min(440px,calc(100%-84px))] w-[min(360px,calc(100%-24px))] flex-col overflow-hidden rounded-xl"
      aria-label="Design review"
    >
      <header className="flex items-center gap-2 border-b border-line bg-surface-2 px-3 py-2 font-mono text-[11px]">
        <ScanSearch className="size-3.5 text-ink-3" />
        <span className="font-semibold uppercase tracking-wider text-ink">design review</span>
        <span className="text-ink-3">
          <Counts findings={findings} />
        </span>
        <button type="button" onClick={() => setOpen(false)} className="ml-auto rounded-sm p-0.5 text-ink-3 hover:text-ink" aria-label="Collapse design review">
          <ChevronDown className="size-4" />
        </button>
      </header>
      {findings.length === 0 ? (
        <p className="p-4 text-xs leading-relaxed text-ink-2">
          No static risks found: replicas, timeouts, retries, pools and queues look sane. Stress it with faults to be sure.
        </p>
      ) : (
        <ul className="min-h-0 flex-1 overflow-y-auto">
          {findings.map((f) => (
            <li key={f.id} className="border-b border-line last:border-b-0">
              <button
                type="button"
                onClick={() => focus(f)}
                className={cn(
                  "relative block w-full py-2.5 pl-4 pr-3 text-left transition-colors hover:bg-surface-2",
                  focused === f.id && "bg-surface-2",
                )}
              >
                <span className="absolute inset-y-2 left-1.5 w-[2px] rounded-full" style={{ background: SEVERITY_COLOR[f.severity] }} aria-hidden />
                <span className="flex items-center gap-2 font-mono text-[9.5px] uppercase tracking-wider text-ink-3">
                  <span style={{ color: SEVERITY_COLOR[f.severity] }}>{f.severity}</span>
                  <span>{REVIEW_RULES[f.rule] ?? f.rule}</span>
                </span>
                <span className="mt-0.5 block text-[12.5px] font-semibold leading-snug text-ink">{f.title}</span>
                <span className="mt-0.5 block text-[11.5px] leading-relaxed text-ink-2">{f.detail}</span>
                <span className="mt-1 block text-[11px] leading-relaxed text-ink-3">
                  <span className="font-mono text-accent">try →</span> {f.suggestion}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <footer className="border-t border-line px-3 py-1.5 font-mono text-[10px] text-ink-3">static checks on the model · click a finding to select it</footer>
    </section>
  );
}
