"use client";

/**
 * What to fix first: the run's most severe findings by title, each with what to try, and the
 * evidence behind it one click away ("Why"). A short finding with nothing to try shows its
 * evidence at once.
 */
import { ArrowRight, ChevronDown, CircleAlert, CircleCheck, Info, TriangleAlert } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { SEVERITY } from "@/lib/status";
import type { Finding } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";

const SEVERITY_ICON = { critical: CircleAlert, warning: TriangleAlert, info: Info, ok: CircleCheck };

/** Evidence this short reads at a glance, so it is shown at once when there is nothing to try. */
const SHORT_DETAIL = 120;

function FindingRow({ f, onShowNode }: { f: Finding; onShowNode: (nodeId: string) => void }) {
  const [open, setOpen] = useState(false);
  const part = useEditor((s) => (f.nodeId ? (s.nodes.find((n) => n.id === f.nodeId)?.data.label ?? null) : null));
  const meta = SEVERITY[f.severity];
  const Icon = SEVERITY_ICON[f.severity];
  const inline = !f.suggestion && f.detail.length <= SHORT_DETAIL;
  return (
    <li className="flex gap-3 px-4 py-3.5">
      <Icon className="mt-0.5 size-4 shrink-0" style={{ color: meta.color }} aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
          <p className="text-[14px] font-semibold leading-5 text-ink">
            <span className="sr-only">{meta.label}: </span>
            {f.title}
          </p>
          <div className="flex shrink-0 items-center gap-3 text-xs">
            {f.nodeId && part && (
              <button type="button" onClick={() => onShowNode(f.nodeId!)} className="font-medium text-accent hover:underline" aria-label={`Show ${part} on the canvas`}>
                Show on canvas
              </button>
            )}
            {!inline && (
              <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open} className="flex items-center gap-0.5 font-medium text-ink-3 hover:text-ink">
                Why
                <ChevronDown className={cn("size-3.5 transition-transform", open && "rotate-180")} aria-hidden />
              </button>
            )}
          </div>
        </div>
        {f.suggestion && (
          <p className="mt-1 text-[13px] leading-relaxed text-ink-2">
            <span className="font-medium text-accent">Try: </span>
            {f.suggestion}
          </p>
        )}
        <p hidden={!inline && !open} className={cn("text-xs leading-relaxed text-ink-2", inline ? "mt-1" : "mt-2 rounded-md bg-surface-2 px-3 py-2")}>
          {f.detail}
        </p>
      </div>
    </li>
  );
}

/** The first findings of a run, most severe first; "All N findings" opens the full list. */
export function FixFirst({ findings, title, onShowNode, onShowAll, limit = 2 }: { findings: Finding[]; title: string; onShowNode: (nodeId: string) => void; onShowAll: () => void; limit?: number }) {
  if (!findings.length) return null;
  return (
    <section aria-label="Fix first">
      <div className="mb-2.5 flex items-baseline justify-between">
        <h3 className="text-[15px] font-semibold text-ink">{title}</h3>
        {findings.length > limit && (
          <Button size="sm" variant="ghost" onClick={onShowAll}>
            All {findings.length} findings <ArrowRight />
          </Button>
        )}
      </div>
      <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface">
        {findings.slice(0, limit).map((f, i) => (
          <FindingRow key={`${f.kind}-${f.nodeId ?? ""}-${i}`} f={f} onShowNode={onShowNode} />
        ))}
      </ul>
    </section>
  );
}
