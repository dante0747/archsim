"use client";

import { Handle, type NodeProps, Position } from "@xyflow/react";
import { CircleAlert } from "lucide-react";
import { memo } from "react";
import { describeValue, isFieldVisible } from "@/components/editor/FieldControl";
import { ComponentIcon } from "@/components/editor/icons";
import type { ArchNode } from "@/lib/document";
import { fmtNum, fmtPct } from "@/lib/format";
import { componentOf } from "@/lib/graph-rules";
import { NODE_STATUS } from "@/lib/status";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";

function ArchNodeViewImpl({ id, data, selected }: NodeProps<ArchNode>) {
  const comp = useEditor((s) => componentOf(s.catalog, data.type));
  const errorCount = useEditor((s) => s.issues.filter((i) => i.nodeId === id && i.severity === "error").length);
  const warningCount = useEditor(
    (s) => s.issues.filter((i) => i.nodeId === id && i.severity === "warning").length,
  );
  const overlay = useRuns((s) => s.overlay);
  const nodeReport = useRuns((s) => {
    const r = s.activeRunId ? s.reports[s.activeRunId] : null;
    return r?.nodes.find((n) => n.id === id) ?? null;
  });
  const inPorts = comp?.ports.filter((p) => p.direction === "in") ?? [];
  const outPorts = comp?.ports.filter((p) => p.direction === "out") ?? [];
  const headline = (comp?.fields ?? []).filter((f) => f.headline && isFieldVisible(f, data.config)).slice(0, 3);
  const status = overlay && nodeReport ? nodeReport.status : null;
  const meta = status ? NODE_STATUS[status] : null;
  const s = nodeReport?.summary;
  const util = s?.utilization ?? null;

  return (
    <div
      className={cn(
        "w-[248px] rounded-xl border-2 bg-surface shadow-sm transition-shadow",
        selected ? "shadow-lg ring-4 ring-accent/20" : "hover:shadow-md",
      )}
      style={{ borderColor: selected ? "var(--accent)" : meta ? meta.color : "var(--line)" }}
      data-testid={`node-${id}`}
    >
      {inPorts.map((p, i) => (
        <Handle
          key={p.name}
          id={p.name}
          type="target"
          position={Position.Left}
          style={{ top: `${((i + 1) / (inPorts.length + 1)) * 100}%` }}
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
              style={{ top }}
              title={`${p.label}: ${p.description}`}
            />
            {p.name !== "out" && (
              <span
                className="pointer-events-none absolute right-2 -translate-y-1/2 rounded bg-surface-2 px-1 text-[10px] font-medium text-ink-2"
                style={{ top }}
              >
                {p.label.toLowerCase()} →
              </span>
            )}
          </div>
        );
      })}

      <div className="flex items-start gap-2.5 p-3 pb-2">
        <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-surface-2 text-ink-2">
          <ComponentIcon name={comp?.icon} className="size-4" />
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold leading-5 text-ink">{data.label}</p>
          <p className="truncate text-[11px] leading-4 text-ink-3">{comp?.displayName ?? data.type}</p>
        </div>
        {errorCount > 0 ? (
          <span
            className="flex items-center gap-0.5 rounded bg-critical-soft px-1 text-[10px] font-semibold text-ink"
            title={`${errorCount} error(s)`}
          >
            <CircleAlert className="size-3" style={{ color: "var(--status-critical)" }} /> {errorCount}
          </span>
        ) : warningCount > 0 ? (
          <span
            className="flex items-center gap-0.5 rounded bg-warning-soft px-1 text-[10px] font-semibold text-ink"
            title={`${warningCount} warning(s)`}
          >
            <CircleAlert className="size-3" style={{ color: "var(--status-serious)" }} /> {warningCount}
          </span>
        ) : null}
      </div>

      {headline.length > 0 && (
        <dl className="space-y-0.5 px-3 pb-2.5 text-[11px] leading-4">
          {headline.map((f) => (
            <div key={f.key} className="flex justify-between gap-2">
              <dt className="truncate text-ink-3">{f.label}</dt>
              <dd className="tabular shrink-0 text-ink-2">{describeValue(f, data.config[f.key] ?? f.default)}</dd>
            </div>
          ))}
        </dl>
      )}

      {meta && s && (
        <div className="border-t border-line px-3 py-2" aria-label="Simulation result for this component">
          <div className="mb-1 flex items-center justify-between text-[11px]">
            <span className="flex items-center gap-1 font-medium text-ink">
              <span className="size-2 rounded-full" style={{ background: meta.color }} aria-hidden />
              {meta.label}
            </span>
            <span className="tabular text-ink-2">
              {util != null ? `${fmtPct(util, 0)} busy` : `${fmtNum(s.arrivalRps, 0)} req/s`}
            </span>
          </div>
          {util != null && (
            <div className="h-1.5 overflow-hidden rounded-full bg-surface-3">
              <div
                className="h-full rounded-full"
                style={{ width: `${Math.min(util, 1) * 100}%`, background: meta.color }}
              />
            </div>
          )}
          <p className="tabular mt-1 truncate text-[10px] text-ink-3">
            {fmtNum(s.arrivalRps, 0)} req/s · queue peak {fmtNum(s.peakQueue)}
            {s.rejectedTotal ? ` · ${fmtNum(s.rejectedTotal)} rejected` : ""}
            {s.hitRatio != null ? ` · hit ${fmtPct(s.hitRatio, 0)}` : ""}
          </p>
        </div>
      )}
    </div>
  );
}

export const ArchNodeView = memo(ArchNodeViewImpl);
