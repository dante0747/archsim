"use client";

import { BaseEdge, EdgeLabelRenderer, type EdgeProps, getBezierPath } from "@xyflow/react";
import { memo, useMemo } from "react";
import type { ArchEdge } from "@/lib/document";
import { describeDistribution } from "@/lib/distributions";
import { compact, compactRate } from "@/lib/format";
import { REQ_COLOR } from "@/lib/theme";
import type { RequestType } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { prefersReducedMotion, useUi } from "@/store/ui";

/** Map a measured call rate to a stroke width and a particle count (log scale). */
function flowScale(rps: number): { width: number; particles: number } {
  if (rps <= 0) return { width: 1.75, particles: 0 };
  const t = Math.min(1, Math.log10(rps + 1) / 3); // 1000 req/s ≈ full scale
  return { width: 1.75 + 3.25 * t, particles: Math.max(1, Math.round(1 + t * 4)) };
}

function TypeDots({ types }: { types: RequestType[] }) {
  return (
    <span className="flex items-center gap-0.5" aria-label={types.join(" and ")}>
      {types.map((t) => (
        <span key={t} className="flex items-center gap-0.5 font-mono text-[9.5px] font-semibold uppercase" style={{ color: REQ_COLOR[t] }}>
          <span className="size-1.5 rounded-full" style={{ background: REQ_COLOR[t] }} aria-hidden />
          {t === "read" ? "R" : "W"}
        </span>
      ))}
    </span>
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
  const [path, labelX, labelY] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition });
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
  const stroke = selected ? "var(--accent)" : hasError ? "var(--status-critical)" : showFlow && rps > 0 ? "color-mix(in srgb, var(--accent) 45%, var(--line-strong))" : "var(--line-strong)";

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        markerEnd={markerEnd}
        style={{
          stroke,
          strokeWidth: selected ? width + 0.75 : width,
          strokeDasharray: isMiss ? "7 5" : undefined,
          strokeLinecap: "round",
        }}
      />
      {dots.map(({ i, type }) => (
        <circle key={i} r={3.2} fill={REQ_COLOR[type]} opacity={0.95}>
          <animateMotion dur={`${dur}s`} repeatCount="indefinite" begin={`${-(i * dur) / dots.length}s`} path={path} />
        </circle>
      ))}
      <EdgeLabelRenderer>
        <div
          className={cn(
            "nodrag nopan pointer-events-auto absolute flex items-center gap-1.5 rounded-full border bg-glass px-2 py-0.5 text-[10px] leading-4 shadow-panel backdrop-blur",
            selected ? "border-accent" : hasError ? "border-critical" : "border-line",
          )}
          style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
          data-testid={`edge-label-${id}`}
          title={[
            data?.label,
            isMiss ? "Cache miss path" : `Carries ${(cfg?.requestTypes ?? []).join(" + ")}`,
            cfg?.latencyMs ? `latency ${describeDistribution(cfg.latencyMs)} each way` : null,
            cfg?.timeoutMs ? `timeout ${cfg.timeoutMs} ms` : null,
            cfg?.maxAttempts && cfg.maxAttempts > 1 ? `${cfg.maxAttempts} attempts` : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        >
          {isMiss && <span className="font-mono text-[9.5px] font-semibold uppercase tracking-wider text-pink">miss</span>}
          {!isMiss && <TypeDots types={cfg?.requestTypes ?? ["read", "write"]} />}
          {showFlow && (
            <span className="tabular rounded-full bg-surface-3 px-1.5 font-mono font-semibold text-ink">{compactRate(rps)}/s</span>
          )}
          {showFlow && edgeReport.timeouts > 0 && (
            <span className="rounded-full bg-critical-soft px-1.5 font-mono font-semibold text-ink">⏱ {compact(edgeReport.timeouts)}</span>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

export const ArchEdgeView = memo(ArchEdgeViewImpl);
