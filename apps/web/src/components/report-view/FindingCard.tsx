"use client";

import { CircleAlert, CircleCheck, Info, TriangleAlert } from "lucide-react";
import { SEVERITY } from "@/lib/status";
import type { Finding } from "@/lib/types";

const SEVERITY_ICON = {
  critical: CircleAlert,
  warning: TriangleAlert,
  info: Info,
  ok: CircleCheck,
};

/** One finding of a run. `onShowNode` adds "Show on canvas" for findings about a part (the editor). */
export function FindingCard({ f, onShowNode }: { f: Finding; onShowNode?: (nodeId: string) => void }) {
  const meta = SEVERITY[f.severity];
  const Icon = SEVERITY_ICON[f.severity];
  return (
    <li className="relative flex gap-2.5 overflow-hidden rounded-md border border-line bg-surface p-3 pl-3.5">
      <span className="absolute inset-y-0 left-0 w-0.5" style={{ background: meta.color }} aria-hidden />
      <Icon className="mt-0.5 size-3.5 shrink-0" style={{ color: meta.color }} aria-hidden />
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="sr-only">{meta.label}:</span>
          <p className="text-[13px] font-semibold text-ink">{f.title}</p>
          {f.nodeId && onShowNode && (
            <button type="button" className="text-xs font-medium text-accent hover:underline" onClick={() => onShowNode(f.nodeId!)}>
              Show on canvas
            </button>
          )}
        </div>
        <p className="text-xs leading-relaxed text-ink-2">{f.detail}</p>
        {f.suggestion && (
          <p className="rounded-sm border-l border-accent bg-surface-2 px-2.5 py-1.5 text-xs leading-relaxed text-ink">
            <span className="mr-1.5 font-mono text-[11px] text-accent">try →</span>
            {f.suggestion}
          </p>
        )}
      </div>
    </li>
  );
}
