"use client";

/** One calculation behind a number: the formula, this run's numbers in it, the result. */
import { ExternalLink } from "lucide-react";
import { fmtNum } from "@/lib/format";
import type { ExplainEntry } from "@/lib/types";
import { cn } from "@/lib/utils";

/** The result in a readable form: ratios as percentages, big numbers with separators. */
export function explainResult(e: Pick<ExplainEntry, "result" | "unit">): string {
  const v = e.result;
  if (v == null) return "–";
  if (e.unit === "") return Math.abs(v) <= 1.5 ? `${fmtNum(v * 100, v * 100 < 10 ? 1 : 0)}%` : fmtNum(v, 2);
  const digits = Math.abs(v) >= 100 ? 0 : Math.abs(v) >= 10 ? 1 : 2;
  return `${fmtNum(v, digits)}${e.unit === "x" ? "×" : ` ${e.unit}`}`;
}

export function FormulaCard({ entry, compact = false, className }: { entry: ExplainEntry; compact?: boolean; className?: string }) {
  return (
    <article className={cn("flex flex-col rounded-lg border border-line bg-surface", compact ? "p-3" : "p-4", className)}>
      <header className="flex items-baseline gap-3">
        <h4 className="min-w-0 flex-1 truncate text-[13px] font-semibold text-ink">{entry.title}</h4>
        <span className={cn("tabular shrink-0 font-semibold text-ink", compact ? "text-[15px]" : "text-xl tracking-[-0.02em]")}>
          {explainResult(entry)}
        </span>
      </header>
      <p className={cn("mt-2 rounded-md bg-surface-2 px-2.5 py-1.5 font-code text-ink-2", compact ? "text-[11px]" : "text-xs")}>{entry.formula}</p>
      <p className={cn("mt-1.5 break-words font-code text-ink-3", compact ? "text-[11px]" : "text-xs")}>= {entry.substituted}</p>
      {entry.note && !compact && <p className="mt-2.5 text-[13px] leading-relaxed text-ink-2">{entry.note}</p>}
      {entry.source && !compact && (
        <a href={entry.source} target="_blank" rel="noreferrer" className="mt-2 inline-flex w-fit items-center gap-1 text-xs text-accent hover:underline">
          Where this comes from <ExternalLink className="size-3" />
        </a>
      )}
    </article>
  );
}
