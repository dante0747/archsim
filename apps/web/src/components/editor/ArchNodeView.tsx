"use client";

import { Handle, type NodeProps, NodeToolbar, Position } from "@xyflow/react";
import { Copy, Trash2 } from "lucide-react";
import { memo, useLayoutEffect, useRef, useState } from "react";
import { describeValue, isFieldVisible } from "@/components/editor/FieldControl";
import { SHAPE_HANDLE, SHAPE_OF, SHAPE_PADDING, ShapeSvg } from "@/components/shapes";
import type { ArchNode } from "@/lib/document";
import { isDistribution, shortDistribution } from "@/lib/distributions";
import { compact, compactRate, fmtPct } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { REQ_GLYPH, kindOf } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

export const NODE_W = 232;

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
    <div className="flex items-baseline justify-between gap-3 font-mono leading-[18px]">
      <dt className="truncate text-[10.5px] lowercase text-ink-3">{k}</dt>
      <dd className="tabular shrink-0 text-[11px] text-ink">{v}</dd>
    </div>
  );
}

/** Ten-segment meter, like a VU bar. Neutral when healthy, status-colored when it needs attention. */
function Meter({ value, color }: { value: number; color: string }) {
  const filled = Math.round(Math.min(1, Math.max(0, value)) * 10);
  return (
    <div className="flex gap-[2px]" aria-hidden>
      {Array.from({ length: 10 }, (_, i) => (
        <span key={i} className="h-[5px] flex-1 rounded-[1px]" style={{ background: i < filled ? color : "var(--surface-3)" }} />
      ))}
    </div>
  );
}

function ArchNodeViewImpl({ id, data, selected }: NodeProps<ArchNode>) {
  const comp = useEditor((s) => componentOf(s.catalog, data.type));
  const errorCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "error").length);
  const warningCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "warning").length);
  const rps = useEditor((s) => s.workload.rps);
  const readRatio = useEditor((s) => s.workload.readRatio);
  const patternKind = useEditor((s) => s.workload.pattern.kind);
  const duplicate = useEditor((s) => s.duplicateSelection);
  const remove = useEditor((s) => s.deleteElements);
  const overlay = useRuns((s) => s.overlay);
  const nodeReport = useRuns((s) => {
    const r = s.activeRunId ? s.reports[s.activeRunId] : null;
    return r?.nodes.find((n) => n.id === id) ?? null;
  });
  const boxRef = useRef<HTMLDivElement>(null);
  const { w, h } = useBoxSize(boxRef);

  const shape = SHAPE_OF[kindOf(data.type, comp?.category)];
  const pad = SHAPE_PADDING[shape];
  const hp = SHAPE_HANDLE[shape];
  const replicas = typeof data.config.replicas === "number" ? data.config.replicas : 1;
  const inPorts = comp?.ports.filter((p) => p.direction === "in") ?? [];
  const outPorts = comp?.ports.filter((p) => p.direction === "out") ?? [];
  const headline = (comp?.fields ?? []).filter((f) => f.headline && isFieldVisible(f, data.config)).slice(0, 3);
  const status = overlay && nodeReport ? nodeReport.status : null;
  const meta = status ? NODE_STATUS[status] : null;
  const s = nodeReport?.summary;
  const util = s?.utilization ?? null;
  const alarm = status === "saturated" || status === "hot";
  const attention = alarm || status === "warm";

  const stroke = selected
    ? "var(--accent)"
    : errorCount > 0
      ? "var(--status-critical)"
      : alarm && meta
        ? meta.color
        : "var(--node-stroke)";
  const glow =
    selected
      ? "drop-shadow(0 0 5px color-mix(in srgb, var(--accent) 45%, transparent))"
      : status === "saturated" && meta
        ? `drop-shadow(0 0 7px color-mix(in srgb, ${meta.color} 55%, transparent))`
        : undefined;
  const portTop = (i: number, n: number) => `calc(${((i + 1) / (n + 1)) * 100}% + ${hp.dy}px)`;
  const typeName = comp?.displayName.split(" / ")[0].replace(/ \(.*\)$/, "") ?? data.type;

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

      <svg className="pointer-events-none absolute inset-0 overflow-visible" width={w} height={h} aria-hidden>
        <ShapeSvg
          shape={shape}
          w={w}
          h={h}
          layers={replicas}
          stroke={stroke}
          strokeWidth={selected ? 1.75 : 1.25}
          outlineStyle={{ filter: glow, strokeDasharray: errorCount > 0 && !selected ? "5 3" : undefined }}
        />
      </svg>

      {inPorts.map((p, i) => (
        <Handle
          key={p.name}
          id={p.name}
          type="target"
          position={Position.Left}
          style={{ top: portTop(i, inPorts.length), left: hp.left }}
          title={`${p.label}: ${p.description}`}
        />
      ))}
      {outPorts.map((p, i) => {
        const top = portTop(i, outPorts.length);
        return (
          <div key={p.name}>
            <Handle
              id={p.name}
              type="source"
              position={Position.Right}
              style={{ top, right: hp.right }}
              title={`${p.label}: ${p.description} — drag to link`}
            />
            {p.name !== "out" && (
              <span
                className="pointer-events-none absolute -right-1.5 translate-x-full -translate-y-[135%] font-mono text-[9.5px] lowercase text-ink-3"
                style={{ top }}
              >
                {p.label}
              </span>
            )}
          </div>
        );
      })}

      <div className="relative" style={{ padding: `${pad.top}px ${pad.right}px ${pad.bottom}px ${pad.left}px` }}>
        <div className="flex items-start gap-2">
          <div className="min-w-0 flex-1">
            <p className="truncate font-mono text-[12.5px] font-semibold leading-5 text-ink">{data.label}</p>
            <p className="microlabel truncate !text-[9.5px] leading-4">{typeName}</p>
          </div>
          {errorCount > 0 ? (
            <span className="mt-0.5 rounded-sm border border-critical px-1 font-mono text-[10px] font-semibold leading-4 text-danger-ink" title={`${errorCount} error(s)`}>
              ✕{errorCount}
            </span>
          ) : warningCount > 0 ? (
            <span className="mt-0.5 rounded-sm border border-warning px-1 font-mono text-[10px] font-semibold leading-4 text-ink" title={`${warningCount} warning(s)`}>
              !{warningCount}
            </span>
          ) : null}
        </div>

        <dl className="mt-1.5 border-t border-dashed border-line-strong pt-1.5">
          {comp?.isTrafficSource && (
            <>
              <Row k="load" v={patternKind === "constant" ? `${compact(rps)} req/s` : patternKind} />
              <Row k="mix" v={`${REQ_GLYPH.read} ${Math.round(readRatio * 100)}%  ${REQ_GLYPH.write} ${Math.round((1 - readRatio) * 100)}%`} />
            </>
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

        {meta && s && (
          <div className="mt-1.5 border-t border-dashed border-line-strong pt-1.5 font-mono" aria-label="Simulation result for this component">
            <div className="mb-1 flex items-center justify-between text-[10.5px]">
              <span className="flex items-center gap-1.5 font-semibold uppercase tracking-wider text-ink">
                <span className={cn("size-1.5 rounded-full", status === "saturated" && "pulse-soft")} style={{ background: meta.color }} aria-hidden />
                {meta.label}
              </span>
              <span className="tabular text-ink">{util != null ? `${fmtPct(util, 0)} busy` : `${compactRate(s.arrivalRps ?? 0)}/s`}</span>
            </div>
            {util != null && <Meter value={util} color={attention ? meta.color : "var(--ink-2)"} />}
            <p className="tabular mt-1 truncate text-[10px] text-ink-3">
              {compactRate(s.arrivalRps ?? 0)}/s · q {s.meanQueue != null ? s.meanQueue.toFixed(1) : "–"} · pk {compact(s.peakQueue)}
              {s.rejectedTotal ? ` · ✕${compact(s.rejectedTotal)}` : ""}
              {s.hitRatio != null ? ` · hit ${fmtPct(s.hitRatio, 0)}` : ""}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

export const ArchNodeView = memo(ArchNodeViewImpl);
