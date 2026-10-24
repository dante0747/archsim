"use client";

import { BaseEdge, EdgeLabelRenderer, type EdgeProps, getBezierPath } from "@xyflow/react";
import { memo } from "react";
import type { ArchEdge } from "@/lib/document";
import { describeDistribution } from "@/lib/distributions";
import { compact } from "@/lib/format";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

function typesLabel(types: string[] | undefined): string {
  if (!types || types.length === 2) return "reads + writes";
  return types[0] === "read" ? "reads" : "writes";
}

function ArchEdgeViewImpl({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
  sourceHandleId,
  markerEnd,
}: EdgeProps<ArchEdge>) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  });
  const hasError = useEditor((s) => s.issues.some((i) => i.edgeId === id && i.severity === "error"));
  const overlay = useRuns((s) => s.overlay);
  const edgeReport = useRuns((s) => {
    const r = s.activeRunId ? s.reports[s.activeRunId] : null;
    return r?.edges.find((e) => e.id === id) ?? null;
  });
  const cfg = data?.config;
  const isMiss = sourceHandleId === "miss";
  const stroke = selected ? "var(--accent)" : hasError ? "var(--status-critical)" : "var(--ink-3)";
  const showFlow = overlay && edgeReport;

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        style={{
          stroke,
          strokeWidth: selected ? 2.5 : 1.75,
          strokeDasharray: isMiss ? "6 4" : undefined,
        }}
      />
      <EdgeLabelRenderer>
        <div
          className={cn(
            "nodrag nopan pointer-events-auto absolute flex items-center gap-1 rounded-md border bg-surface px-1.5 py-0.5 text-[10px] leading-4 shadow-sm",
            selected ? "border-accent" : "border-line",
          )}
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          data-testid={`edge-label-${id}`}
        >
          <span className="font-medium text-ink">
            {data?.label || (isMiss ? "cache miss" : typesLabel(cfg?.requestTypes))}
          </span>
          {cfg?.latencyMs && <span className="text-ink-3">· {describeDistribution(cfg.latencyMs)}</span>}
          {cfg?.timeoutMs ? <span className="text-ink-3">· t/o {compact(cfg.timeoutMs)} ms</span> : null}
          {showFlow && (
            <span className="tabular rounded bg-surface-2 px-1 font-medium text-ink-2">
              {compact(edgeReport.callRps ?? 0)}/s
            </span>
          )}
          {showFlow && edgeReport.timeouts > 0 && (
            <span className="rounded bg-critical-soft px-1 font-medium text-ink">{compact(edgeReport.timeouts)} timeouts</span>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export const ArchEdgeView = memo(ArchEdgeViewImpl);
