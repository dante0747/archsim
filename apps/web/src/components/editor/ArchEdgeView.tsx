"use client";

import { BaseEdge, EdgeLabelRenderer, type EdgeProps, getSmoothStepPath } from "@xyflow/react";
import { memo, useMemo } from "react";
import type { ArchEdge } from "@/lib/document";
import { describeDistribution } from "@/lib/distributions";
import { compact, compactRate } from "@/lib/format";
import { REQ_GLYPH } from "@/lib/theme";
import type { RequestType } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { prefersReducedMotion, useUi } from "@/store/ui";

/** Map a measured call rate to a stroke width and a particle count (log scale). */
function flowScale(rps: number): { width: number; particles: number } {
  if (rps <= 0) return { width: 1.25, particles: 0 };
  const t = Math.min(1, Math.log10(rps + 1) / 3); // 1000 req/s ≈ full scale
  return { width: 1.25 + 2.25 * t, particles: Math.max(1, Math.round(1 + t * 4)) };
}

/** A flow particle: a dot for reads, a small square for writes. */
function Particle({ type, path, dur, begin }: { type: RequestType; path: string; dur: number; begin: number }) {
  const motion = <animateMotion dur={`${dur}s`} repeatCount="indefinite" begin={`${begin}s`} path={path} />;
  return type === "write" ? (
    <rect x={-2.6} y={-2.6} width={5.2} height={5.2} fill="var(--accent)">
      {motion}
    </rect>
  ) : (
    <circle r={2.8} fill="var(--accent)">
      {motion}
    </circle>
  );
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
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 6,
    offset: 18,
  });
  const hasError = useEditor((s) => s.issues.some((i) => i.edgeId === id && i.severity === "error"));
  const overlay = useRuns((s) => s.overlay);
  const flowOn = useUi((s) => s.flow);
  const edgeReport = useRuns((s) => {
    const r = s.activeRunId ? s.reports[s.activeRunId] : null;
    return r?.edges.find((e) => e.id === id) ?? null;
  });
  const cfg = data?.config;
  const isMiss = sourceHandleId === "miss";
  const showFlow = overlay && edgeReport;
  const rps = showFlow ? (edgeReport.callRps ?? 0) : 0;
  const { width, particles } = flowScale(rps);
  const flowKey = ((showFlow && edgeReport.flowTypes.length ? edgeReport.flowTypes : cfg?.requestTypes) ?? ["read"]).join(",");
  const animate = showFlow && flowOn && particles > 0 && !prefersReducedMotion();
  const dur = 2.4;
  const dots = useMemo(() => {
    const types = flowKey.split(",") as RequestType[];
    return Array.from({ length: animate ? particles : 0 }, (_, i) => ({ i, type: types[i % types.length] }));
  }, [animate, particles, flowKey]);
  const stroke = selected ? "var(--accent)" : hasError ? "var(--status-critical)" : "var(--wire)";
  const types = cfg?.requestTypes ?? ["read", "write"];

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        style={{
          stroke,
          strokeWidth: selected ? Math.max(width, 1.75) : width,
          strokeDasharray: isMiss || hasError ? "6 4" : undefined,
        }}
      />
      {dots.map(({ i, type }) => (
        <Particle key={i} type={type} path={path} dur={dur} begin={-(i * dur) / dots.length} />
      ))}
      <EdgeLabelRenderer>
        <div
          className={cn(
            "nodrag nopan pointer-events-auto absolute flex items-center gap-1.5 rounded-sm border bg-surface px-1.5 font-mono text-[10px] leading-[16px] text-ink-2",
            selected ? "border-accent" : hasError ? "border-critical" : "border-line-strong",
          )}
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          data-testid={`edge-label-${id}`}
          title={[
            data?.label,
            isMiss ? "Cache miss path" : `Carries ${types.join(" + ")}`,
            cfg?.latencyMs ? `latency ${describeDistribution(cfg.latencyMs)} each way` : null,
            cfg?.timeoutMs ? `timeout ${cfg.timeoutMs} ms` : null,
            cfg?.maxAttempts && cfg.maxAttempts > 1 ? `${cfg.maxAttempts} attempts` : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        >
          {isMiss ? (
            <span className="lowercase">miss</span>
          ) : (
            <span aria-label={types.join(" and ")} className="tracking-tight">
              {types.map((t) => REQ_GLYPH[t]).join("")}
            </span>
          )}
          {showFlow && <span className="tabular font-semibold text-ink">{compactRate(rps)}/s</span>}
          {showFlow && edgeReport.timeouts > 0 && (
            <span className="font-semibold text-danger-ink" title="Timeouts on this link">
              ⏱{compact(edgeReport.timeouts)}
            </span>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export const ArchEdgeView = memo(ArchEdgeViewImpl);
