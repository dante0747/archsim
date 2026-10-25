"use client";

import { Handle, type NodeProps, NodeToolbar, Position } from "@xyflow/react";
import { Copy, Cpu, HardDrive, MemoryStick, Trash2 } from "lucide-react";
import { memo, useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import { describeValue, isFieldVisible } from "@/components/editor/FieldControl";
import { SHAPE_HANDLE, SHAPE_OF, SHAPE_PADDING, ShapeSvg } from "@/components/shapes";
import { TechIcon } from "@/components/TechIcon";
import { useIsDark } from "@/components/ui/theme-toggle";
import { type ArchNode, SIDES, type Side, sideHandle } from "@/lib/document";
import { isDistribution, shortDistribution } from "@/lib/distributions";
import { compact, compactRate, fmtPct } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { clientFraction, clientShares } from "@/lib/clients";
import { faultsAt, fmtRate, heatColor, runFaults, statusAt } from "@/lib/recorder";
import { NODE_STATUS } from "@/lib/status";
import { KIND_COLOR, kindOf, technologyLabel } from "@/lib/theme";
import type { Fault, NodeReport, NodeStatus, Report } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useUi } from "@/store/ui";

export const NODE_W = 212;

const SIDE_POSITION: Record<Side, Position> = { top: Position.Top, right: Position.Right, bottom: Position.Bottom, left: Position.Left };

/** Size of an element in layout pixels (unaffected by the canvas zoom transform). */
function useBoxSize(ref: React.RefObject<HTMLDivElement | null>) {
  const [size, setSize] = useState({ w: NODE_W, h: 96 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () =>
      setSize((s) => (s.w === el.offsetWidth && s.h === el.offsetHeight ? s : { w: el.offsetWidth, h: el.offsetHeight }));
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return size;
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 leading-[18px]">
      <dt className="truncate text-[11.5px] text-ink-3">{k}</dt>
      <dd className="tabular shrink-0 font-mono text-[11.5px] text-ink">{v}</dd>
    </div>
  );
}

/** Ten-segment meter, like a VU bar. Neutral when healthy, status-colored when it needs attention. */
function Meter({ value, color }: { value: number; color: string }) {
  const filled = Math.round(Math.min(1, Math.max(0, value)) * 10);
  return (
    <div className="flex gap-[2px]" aria-hidden>
      {Array.from({ length: 10 }, (_, i) => (
        <span
          key={i}
          className="h-[5px] flex-1 rounded-[1px] transition-colors duration-300"
          style={{ background: i < filled ? color : "var(--surface-3)" }}
        />
      ))}
    </div>
  );
}

/** State of one component in one bucket of the recording (flight-recorder replay). */
interface LiveState {
  t: number;
  util: number | null;
  arrival: number | null;
  rejected: number | null;
  queue: number | null;
  replicas: number | null;
  lag: number | null;
  hit: number | null;
  status: NodeStatus;
  faults: Fault[];
}

function liveStateAt(report: Report, node: NodeReport, bucket: number): LiveState {
  const ts = node.timeseries ?? {};
  const at = (k: string) => ts[k]?.[bucket] ?? null;
  const b = report.timeseries.bucketS || 1;
  const util = at("utilization");
  const arrival = at("arrivalRps");
  const rejected = at("rejectedRps");
  const t = bucket * b;
  return {
    t,
    util,
    arrival,
    rejected,
    queue: at("queueMean"),
    replicas: at("replicas"),
    lag: at("lag"),
    hit: at("hitRatio"),
    status: statusAt(util, arrival, rejected),
    faults: faultsAt(runFaults(report), t + b / 2).filter((f) => f.target === node.id),
  };
}

/** A row of small blocks above the node: requests waiting, on a log scale. */
function QueueBlocks({ queue, left }: { queue: number; left: number }) {
  const blocks = Math.min(16, Math.max(1, Math.ceil(Math.log2(queue + 1) * 2.5)));
  return (
    <div
      className="pointer-events-none absolute -top-4 flex items-end gap-[2px]"
      style={{ left }}
      aria-label={`${Math.round(queue)} requests waiting`}
    >
      {Array.from({ length: blocks }, (_, i) => (
        <span
          key={i}
          className="size-[6px] rounded-[1px] bg-ink-2"
          style={{ opacity: 0.35 + (0.65 * (i + 1)) / blocks }}
        />
      ))}
      <span className="ml-1 font-mono text-[9.5px] leading-none text-ink-3">{queue >= 10 ? Math.round(queue) : queue.toFixed(1)} queued</span>
    </div>
  );
}

function FaultTag({ fault }: { fault: Fault }) {
  if (fault.kind === "outage") {
    return (
      <span className="pulse-soft mt-0.5 shrink-0 rounded-sm border border-critical bg-critical-soft px-1 font-mono text-[9.5px] font-bold leading-4 tracking-wider text-danger-ink">
        OUTAGE
      </span>
    );
  }
  const f = fault.factor ?? 4;
  return (
    <span className="mt-0.5 shrink-0 rounded-sm border border-serious bg-serious-soft px-1 font-mono text-[9.5px] font-bold leading-4 tracking-wider text-ink">
      SLOW ×{Number.isInteger(f) ? f : f.toFixed(1)}
    </span>
  );
}

function ArchNodeViewImpl({ id, data, selected }: NodeProps<ArchNode>) {
  const comp = useEditor((s) => componentOf(s.catalog, data.type));
  const errorCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "error").length);
  const warningCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "warning").length);
  const rps = useEditor((s) => s.workload.rps);
  const patternKind = useEditor((s) => s.workload.pattern.kind);
  // With several client parts each sends its share of the workload.
  const clientCount = useEditor((s) => (comp?.isTrafficSource ? clientShares(s.nodes, s.catalog).length : 0));
  const fraction = useEditor((s) => (comp?.isTrafficSource ? clientFraction(s.nodes, s.catalog, id) : null));
  const duplicate = useEditor((s) => s.duplicateSelection);
  const remove = useEditor((s) => s.deleteElements);
  const overlay = useRuns((s) => s.overlay);
  const report = useRuns((s) => (s.activeRunId ? (s.reports[s.activeRunId] ?? null) : null));
  const nodeReport = useMemo(() => report?.nodes.find((n) => n.id === id) ?? null, [report, id]);
  const bucketS = report?.timeseries.bucketS || 1;
  const nBuckets = report?.timeseries.t.length ?? 0;
  // Quantized to the bucket so nodes re-render once per simulated bucket, not on every frame.
  const bucket = useUi((s) => (s.playhead == null ? -1 : Math.floor(s.playhead / bucketS + 1e-9)));
  const dark = useIsDark();
  const clipId = `nodeclip-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const boxRef = useRef<HTMLDivElement>(null);
  const { w, h } = useBoxSize(boxRef);

  const liveOn = overlay && !!report && !!nodeReport && bucket >= 0 && bucket < nBuckets;
  const live = useMemo(
    () => (liveOn && report && nodeReport ? liveStateAt(report, nodeReport, bucket) : null),
    [liveOn, report, nodeReport, bucket],
  );

  const shape = SHAPE_OF[kindOf(data.type, comp?.category)];
  const pad = SHAPE_PADDING[shape];
  const hp = SHAPE_HANDLE[shape];
  const configReplicas = typeof data.config.replicas === "number" ? data.config.replicas : 1;
  const replicas = live?.replicas != null ? Math.max(1, Math.round(live.replicas)) : configReplicas;
  const headline = (comp?.fields ?? []).filter((f) => f.headline && isFieldVisible(f, data.config)).slice(0, 2);
  const status: NodeStatus | null = live ? live.status : overlay && nodeReport ? nodeReport.status : null;
  const meta = status ? NODE_STATUS[status] : null;
  const s = nodeReport?.summary;
  const util = live ? live.util : (s?.utilization ?? null);
  const alarm = status === "saturated" || status === "hot";
  const attention = alarm || status === "warm";
  const outage = live?.faults.find((f) => f.kind === "outage") ?? null;
  const slowdown = live?.faults.find((f) => f.kind === "slowdown") ?? null;
  const rejecting = !!live && (live.rejected ?? 0) > 0;

  const stroke = selected
    ? "var(--accent)"
    : outage
      ? "var(--status-critical)"
      : errorCount > 0
        ? "var(--status-critical)"
        : alarm && meta
          ? meta.color
          : `color-mix(in srgb, ${KIND_COLOR[kindOf(data.type, comp?.category)]} 62%, var(--node-stroke))`;
  const glow = selected
    ? "drop-shadow(0 0 5px color-mix(in srgb, var(--accent) 45%, transparent))"
    : status === "saturated" && meta
      ? `drop-shadow(0 0 7px color-mix(in srgb, ${meta.color} 55%, transparent))`
      : undefined;
  const dashed = (outage || errorCount > 0) && !selected;
  const typeName = comp?.displayName.split(" / ")[0].replace(/ \(.*\)$/, "") ?? data.type;
  const technology = typeof data.config.technology === "string" ? data.config.technology : null;
  const tech = technologyLabel(comp, technology);
  const spec = (k: string) => (typeof data.config[k] === "number" ? (data.config[k] as number) : null);
  const [cpu, mem, disk] = [spec("cpuCores"), spec("memoryGib"), spec("storageGib")];
  const level = live?.util != null ? Math.min(1, Math.max(0, live.util)) : null;
  const heat = heatColor(level, dark);

  return (
    <div ref={boxRef} className="group relative" style={{ width: NODE_W }} data-testid={`node-${id}`}>
      <NodeToolbar isVisible={selected} position={Position.Top} offset={10}>
        <div className="flex items-center gap-0.5 rounded-md border border-line-strong bg-surface p-0.5 font-mono shadow-float">
          <button
            type="button"
            onClick={duplicate}
            className="flex items-center gap-1 rounded-sm px-2 py-1 text-[10.5px] text-ink-2 hover:bg-surface-2 hover:text-ink"
            title="Duplicate (Ctrl/⌘ D)"
          >
            <Copy className="size-3" /> duplicate
          </button>
          <button
            type="button"
            onClick={() => remove([id], [])}
            className="flex items-center gap-1 rounded-sm px-2 py-1 text-[10.5px] text-danger-ink hover:bg-critical-soft"
            title="Delete (Del)"
          >
            <Trash2 className="size-3" /> delete
          </button>
        </div>
      </NodeToolbar>

      {live && live.queue != null && live.queue >= 0.5 && !selected && <QueueBlocks queue={live.queue} left={pad.left} />}

      <svg className="pointer-events-none absolute inset-0 overflow-visible" width={w} height={h} style={{ filter: "var(--node-shadow)" }} aria-hidden>
        <ShapeSvg
          shape={shape}
          w={w}
          h={h}
          layers={replicas}
          fill="url(#archsim-node-fill)"
          stroke={stroke}
          strokeWidth={selected ? 1.75 : outage ? 1.5 : 1.25}
          outlineStyle={{ filter: glow, strokeDasharray: dashed ? "5 3" : undefined }}
        />
        {level != null && (
          <>
            <defs>
              <clipPath id={clipId}>
                <ShapeSvg shape={shape} w={w} h={h} layers={replicas} strokeWidth={0} />
              </clipPath>
            </defs>
            {/* Utilization "fill level": liquid rising inside the outline. */}
            <g clipPath={`url(#${clipId})`}>
              <g
                style={{
                  transform: `translateY(${(1 - level) * h}px)`,
                  transition: "transform 420ms cubic-bezier(.2,.8,.2,1)",
                }}
              >
                <rect x={0} y={0} width={w} height={h} fill={heat} fillOpacity={dark ? 0.2 : 0.16} />
                <line x1={0} x2={w} y1={0.75} y2={0.75} stroke={heat} strokeWidth={1.5} strokeOpacity={0.9} />
              </g>
            </g>
          </>
        )}
      </svg>

      {/* Attach points on all four sides: drag from any of them to another part (either way). */}
      {SIDES.map((side) => (
        <Handle
          key={side}
          id={sideHandle(side)}
          type="source"
          position={SIDE_POSITION[side]}
          className="arch-handle"
          style={
            side === "left"
              ? { top: `calc(50% + ${hp.dy}px)`, left: hp.left }
              : side === "right"
                ? { top: `calc(50% + ${hp.dy}px)`, right: hp.right }
                : side === "top"
                  ? { left: "50%", top: 0 }
                  : { left: "50%", bottom: 0 }
          }
          title="Drag to another part to connect"
        />
      ))}

      <div className="relative" style={{ padding: `${pad.top}px ${pad.right}px ${pad.bottom}px ${pad.left}px` }}>
        <div className="flex items-center gap-2.5">
          <TechIcon technology={technology} kind={kindOf(data.type, comp?.category)} size={26} tile className="rounded-xl" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-[13.5px] font-semibold leading-5 tracking-[-0.01em] text-ink">{data.label}</p>
            <p className="truncate text-[11.5px] leading-4 text-ink-3">{tech && tech !== "Generic" ? tech : typeName}</p>
          </div>
          {outage ? (
            <FaultTag fault={outage} />
          ) : slowdown ? (
            <FaultTag fault={slowdown} />
          ) : errorCount > 0 ? (
            <span className="mt-0.5 rounded-sm border border-critical px-1 font-mono text-[10px] font-semibold leading-4 text-danger-ink" title={`${errorCount} error(s)`}>
              ✕{errorCount}
            </span>
          ) : warningCount > 0 ? (
            <span className="mt-0.5 rounded-sm border border-warning px-1 font-mono text-[10px] font-semibold leading-4 text-ink" title={`${warningCount} warning(s)`}>
              !{warningCount}
            </span>
          ) : null}
        </div>

        {(cpu != null || mem != null || disk != null) && (
          <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 font-mono text-[10.5px] text-ink-2" aria-label="Resources">
            {cpu != null && (
              <span className="inline-flex items-center gap-1">
                <Cpu className="size-3 text-ink-3" aria-hidden />
                {cpu} vCPU
              </span>
            )}
            {mem != null && (
              <span className="inline-flex items-center gap-1">
                <MemoryStick className="size-3 text-ink-3" aria-hidden />
                {mem} GiB
              </span>
            )}
            {disk != null && (
              <span className="inline-flex items-center gap-1">
                <HardDrive className="size-3 text-ink-3" aria-hidden />
                {disk >= 1000 ? `${+(disk / 1000).toFixed(1)} TB` : `${disk} GiB`}
              </span>
            )}
          </p>
        )}
        <dl className="mt-2">
          {comp?.isTrafficSource && (
            <Row
              k="Load"
              v={
                clientCount > 1 && fraction != null
                  ? `${Math.round(fraction * 100)}%${patternKind === "constant" ? ` · ${compact(rps * fraction)} req/s` : ""}`
                  : patternKind === "constant"
                    ? `${compact(rps)} req/s`
                    : patternKind
              }
            />
          )}
          {headline.map((f) => (
            <Row
              key={f.key}
              k={f.label}
              v={(() => {
                const v = data.config[f.key] ?? f.default;
                return isDistribution(v) ? shortDistribution(v, f.unit ?? "ms") : describeValue(f, v);
              })()}
            />
          ))}
        </dl>

        {live && meta ? (
          <div className="mt-2 border-t border-line pt-2" aria-label={`State at ${live.t.toFixed(0)} s`}>
            <div className="mb-1.5 flex items-center gap-1.5 text-[11.5px]">
              {status !== "ok" && (
                <span className="flex items-center gap-1.5 font-semibold text-ink">
                  <span className={cn("size-1.5 rounded-full", status === "saturated" && "pulse-soft")} style={{ background: meta.color }} aria-hidden />
                  {meta.label}
                </span>
              )}
              {rejecting && (
                <span key={bucket} className="pulse-soft rounded-sm bg-critical-soft px-1 font-mono text-[10.5px] font-semibold text-danger-ink">
                  ✕{fmtRate(live.rejected)}/s
                </span>
              )}
              <span className="tabular ml-auto font-mono text-ink-2">
                {live.util != null ? `${fmtPct(live.util, 0)} busy` : `${fmtRate(live.arrival ?? 0)}/s`}
              </span>
            </div>
            {live.util != null && <Meter value={live.util} color={attention ? meta.color : "var(--ink-3)"} />}
          </div>
        ) : (
          meta &&
          s && (
            <div className="mt-2 border-t border-line pt-2" aria-label="Simulation result for this component">
              <div className="mb-1.5 flex items-center gap-1.5 text-[11.5px]">
                {status !== "ok" && (
                  <span className="flex items-center gap-1.5 font-semibold text-ink">
                    <span className={cn("size-1.5 rounded-full", status === "saturated" && "pulse-soft")} style={{ background: meta.color }} aria-hidden />
                    {meta.label}
                  </span>
                )}
                <span className="tabular ml-auto font-mono text-ink-2">{util != null ? `${fmtPct(util, 0)} busy` : `${compactRate(s.arrivalRps ?? 0)}/s`}</span>
              </div>
              {util != null && <Meter value={util} color={attention ? meta.color : "var(--ink-3)"} />}
            </div>
          )
        )}
      </div>
    </div>
  );
}

export const ArchNodeView = memo(ArchNodeViewImpl);
