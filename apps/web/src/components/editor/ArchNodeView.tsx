"use client";

import { Handle, type NodeProps, NodeToolbar, Position } from "@xyflow/react";
import { CircleAlert, Copy, Trash2, TriangleAlert } from "lucide-react";
import { memo } from "react";
import { describeValue, isFieldVisible } from "@/components/editor/FieldControl";
import { KindTile } from "@/components/editor/icons";
import type { ArchNode } from "@/lib/document";
import { shortDistribution, isDistribution } from "@/lib/distributions";
import { compact, compactRate, fmtPct } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { kindColor, kindOf } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="microlabel truncate !tracking-[0.08em]">{k}</dt>
      <dd className="tabular shrink-0 font-mono text-[11.5px] text-ink">{v}</dd>
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

  const kind = kindOf(data.type, comp?.category);
  const accent = kindColor(kind);
  const inPorts = comp?.ports.filter((p) => p.direction === "in") ?? [];
  const outPorts = comp?.ports.filter((p) => p.direction === "out") ?? [];
  const headline = (comp?.fields ?? []).filter((f) => f.headline && isFieldVisible(f, data.config)).slice(0, 3);
  const status = overlay && nodeReport ? nodeReport.status : null;
  const meta = status ? NODE_STATUS[status] : null;
  const s = nodeReport?.summary;
  const util = s?.utilization ?? null;
  const alarm = status === "saturated" || status === "hot";

  return (
    <div
      className={cn(
        "group relative w-[236px] rounded-2xl border bg-surface transition-[box-shadow,border-color]",
        selected ? "border-accent" : "border-line hover:border-line-strong",
      )}
      style={{
        boxShadow: selected
          ? "0 0 0 3px color-mix(in srgb, var(--accent) 25%, transparent), var(--shadow-lg)"
          : alarm && meta
            ? `0 0 0 1px ${meta.color}, 0 0 28px -8px ${meta.color}, var(--shadow)`
            : "var(--shadow)",
      }}
      data-testid={`node-${id}`}
    >
      <NodeToolbar isVisible={selected} position={Position.Top} offset={8}>
        <div className="flex items-center gap-0.5 rounded-lg border border-line bg-glass p-0.5 shadow-float backdrop-blur">
          <button
            type="button"
            onClick={duplicate}
            className="flex items-center gap-1 rounded-md px-2 py-1 text-[11px] text-ink-2 hover:bg-surface-2 hover:text-ink"
            title="Duplicate (Ctrl/⌘ D)"
          >
            <Copy className="size-3" /> Duplicate
          </button>
          <button
            type="button"
            onClick={() => remove([id], [])}
            className="flex items-center gap-1 rounded-md px-2 py-1 text-[11px] text-danger-ink hover:bg-critical-soft"
            title="Delete (Del)"
          >
            <Trash2 className="size-3" /> Delete
          </button>
        </div>
      </NodeToolbar>

      {/* kind stripe */}
      <span className="absolute inset-y-4 left-0 w-[3px] rounded-r-full" style={{ background: accent }} aria-hidden />

      {inPorts.map((p, i) => (
        <Handle
          key={p.name}
          id={p.name}
          type="target"
          position={Position.Left}
          style={{ top: `${((i + 1) / (inPorts.length + 1)) * 100}%`, borderColor: accent }}
          title={`${p.label}: ${p.description}`}
        />
      ))}
      {outPorts.map((p, i) => {
        const top = `${((i + 1) / (outPorts.length + 1)) * 100}%`;
        return (
          <div key={p.name}>
            <Handle
              id={p.name}
              type="source"
              position={Position.Right}
              style={{ top, borderColor: accent, background: "var(--surface)" }}
              title={`${p.label}: ${p.description} — drag to link`}
            />
            {p.name !== "out" && (
              <span
                className="pointer-events-none absolute -right-1 translate-x-full -translate-y-[130%] rounded bg-surface-2 px-1 font-mono text-[9px] uppercase tracking-wider text-ink-2"
                style={{ top }}
              >
                {p.label}
              </span>
            )}
          </div>
        );
      })}

      <div className="flex items-center gap-2.5 px-3.5 pb-2 pt-3">
        <KindTile kind={kind} icon={comp?.icon} />
        <div className="min-w-0 flex-1">
          <p className="truncate text-[13.5px] font-semibold leading-5 text-ink">{data.label}</p>
          <p className="microlabel truncate" style={{ color: accent }}>
            {comp?.displayName.split(" / ")[0].replace(/ \(.*\)$/, "") ?? data.type}
          </p>
        </div>
        {errorCount > 0 ? (
          <span className="flex items-center gap-0.5 rounded-md bg-critical-soft px-1.5 py-0.5 font-mono text-[10px] font-semibold text-ink" title={`${errorCount} error(s)`}>
            <CircleAlert className="size-3" style={{ color: "var(--status-critical)" }} /> {errorCount}
          </span>
        ) : warningCount > 0 ? (
          <span className="flex items-center gap-0.5 rounded-md bg-warning-soft px-1.5 py-0.5 font-mono text-[10px] font-semibold text-ink" title={`${warningCount} warning(s)`}>
            <TriangleAlert className="size-3" style={{ color: "var(--status-warning)" }} /> {warningCount}
          </span>
        ) : null}
      </div>

      <dl className="space-y-1 border-t border-line/70 px-3.5 py-2.5">
        {comp?.isTrafficSource && (
          <>
            <Row k="Load" v={patternKind === "constant" ? `${compact(rps)} req/s` : `${patternKind}`} />
            <Row k="Mix" v={`${Math.round(readRatio * 100)}% R · ${Math.round((1 - readRatio) * 100)}% W`} />
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
        <div className="rounded-b-2xl border-t border-line/70 px-3.5 pb-3 pt-2" style={{ background: `color-mix(in srgb, ${meta.color} 7%, transparent)` }} aria-label="Simulation result for this component">
          <div className="mb-1.5 flex items-center justify-between">
            <span className="flex items-center gap-1.5 text-[11px] font-semibold text-ink">
              <span className={cn("size-2 rounded-full", alarm && "pulse-soft")} style={{ background: meta.color }} aria-hidden />
              {meta.label}
            </span>
            <span className="tabular font-mono text-[11px] text-ink">
              {util != null ? `${fmtPct(util, 0)} busy` : `${compactRate(s.arrivalRps ?? 0)} req/s`}
            </span>
          </div>
          {util != null && (
            <div className="h-1.5 overflow-hidden rounded-full bg-surface-3">
              <div className="h-full rounded-full" style={{ width: `${Math.min(util, 1) * 100}%`, background: meta.color }} />
            </div>
          )}
          <p className="tabular mt-1.5 truncate font-mono text-[10px] text-ink-3">
            {compactRate(s.arrivalRps ?? 0)}/s · q̄ {s.meanQueue != null ? s.meanQueue.toFixed(1) : "–"} · peak {compact(s.peakQueue)}
            {s.rejectedTotal ? ` · ✕${compact(s.rejectedTotal)}` : ""}
            {s.hitRatio != null ? ` · hit ${fmtPct(s.hitRatio, 0)}` : ""}
          </p>
        </div>
      )}
    </div>
  );
}

export const ArchNodeView = memo(ArchNodeViewImpl);
