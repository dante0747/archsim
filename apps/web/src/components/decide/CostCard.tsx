"use client";

/**
 * The month's cost of what was simulated: the total, one bar split by part (largest first) with a
 * legend, and the fine print (instance types, what each line counts, the peak hour, notes) folded.
 */
import { ChevronRight } from "lucide-react";
import { costLeftOut, formatUsd, lineMonthlyText, linePriceLabel, monthText, peakCount, peakHour, priceSource } from "@/lib/cost";
import type { CostEstimate, NodeCost } from "@/lib/types";

/** The bar's colours, in order; the parts after the last share the neutral one. */
const SEGMENT = ["var(--series-1)", "var(--series-2)", "var(--series-3)", "var(--series-7)", "var(--series-6)", "var(--series-5)"];
const REST = "var(--ink-3)";

function colorOf(i: number): string {
  return SEGMENT[i] ?? REST;
}

/** A part's share of the month in whole dollars, cents only below $10. */
function partUsd(v: number): string {
  return v >= 10 ? `$${Math.round(v).toLocaleString("en-US")}` : formatUsd(v);
}

function FinePrint({ cost, lines }: { cost: CostEstimate; lines: NodeCost[] }) {
  const hour = peakHour(cost);
  return (
    <details className="group mt-4 border-t border-line pt-3">
      <summary className="flex cursor-pointer list-none items-center gap-1.5 text-xs font-medium text-ink-3 hover:text-ink [&::-webkit-details-marker]:hidden">
        <ChevronRight className="size-3.5 transition-transform group-open:rotate-90" aria-hidden />
        What each line counts
      </summary>
      <ul className="mt-3 space-y-2.5">
        {lines.map((c) => {
          const label = linePriceLabel(c);
          return (
            <li key={c.id} className="text-xs">
              <div className="flex items-baseline justify-between gap-2">
                <span className="min-w-0 truncate text-ink">
                  {c.label}
                  {c.sku && <span className="ml-1.5 font-mono text-[11px] text-ink-3">{c.sku}</span>}
                  {label && (
                    <span className="ml-1.5 text-[11px] text-ink-3" title={label.title} data-testid="cost-price-label">
                      {label.text}
                    </span>
                  )}
                </span>
                <span className="tabular shrink-0 text-ink-2">{lineMonthlyText(c, cost)}</span>
              </div>
              <p className="text-[11px] leading-4 text-ink-3">
                {c.basis}
                {peakCount(c) && <span data-testid="cost-peak-count"> · {peakCount(c)}</span>}
                {c.items && c.items.length > 0 && <span data-testid="cost-units"> · {c.items.map((i) => i.text).join(" + ")}</span>}
              </p>
            </li>
          );
        })}
      </ul>
      {hour && (
        <p className="mt-3 text-xs text-ink-2" data-testid="cost-peak-hour" title={monthText(cost)}>
          An hour at the peak: <span className="tabular text-ink">{formatUsd(hour.peak)}</span>
          <span className="text-ink-3">
            {" "}
            · {formatUsd(hour.base)} at the base load
            {(cost.peakHours ?? 0) > 0 && ` · ${cost.peakHours} peak ${cost.peakHours === 1 ? "hour" : "hours"} a month`}
          </span>
        </p>
      )}
      {cost.notes.length > 0 && (
        <ul className="mt-2 space-y-1">
          {cost.notes.map((n) => (
            <li key={n} className="text-xs leading-relaxed text-ink-3">
              ≈ {n}
            </li>
          ))}
        </ul>
      )}
    </details>
  );
}

export function CostCard({ cost }: { cost: CostEstimate | undefined }) {
  if (!cost) {
    return (
      <section aria-label="Cost" className="rounded-xl border border-line bg-surface p-5">
        <h3 className="text-[15px] font-semibold text-ink">Cost</h3>
        <p className="mt-2 text-[13px] text-ink-2">This run predates cost estimates. Simulate again to price it.</p>
      </section>
    );
  }
  if (!cost.hasCost) {
    return (
      <section aria-label="Cost" className="rounded-xl border border-line bg-surface p-5">
        <h3 className="text-[15px] font-semibold text-ink">Cost</h3>
        <p className="mt-2 text-[13px] text-ink-2">
          No prices set. Pick a price list in Design, or set a <b className="font-medium text-ink">cost per hour</b> on parts.
        </p>
      </section>
    );
  }
  const lines = [...cost.byNode].filter((c) => c.monthly > 0).sort((a, b) => b.monthly - a.monthly);
  const total = lines.reduce((sum, c) => sum + c.monthly, 0) || 1;
  const source = priceSource(cost);
  const leftOut = costLeftOut(cost);
  return (
    <section aria-label="Cost" className="rounded-xl border border-line bg-surface p-5">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-[15px] font-semibold text-ink">Cost</h3>
        <span className="truncate text-xs text-ink-3" title={source.detail} data-testid="cost-source">
          {source.label}
        </span>
      </div>
      <p className="mt-2 flex flex-wrap items-baseline gap-x-2">
        <span className="tabular text-[28px] font-semibold leading-8 tracking-tight text-ink">{formatUsd(cost.monthly)}</span>
        <span className="text-[13px] text-ink-3">a month</span>
        {cost.perMillionRequests != null && <span className="tabular text-[13px] text-ink-3">· {formatUsd(cost.perMillionRequests)} per million requests</span>}
      </p>
      {leftOut && (
        <p className="mt-1 text-xs text-[var(--status-warning)]" data-testid="cost-left-out">
          {leftOut}
        </p>
      )}
      <div className="mt-4 flex h-2.5 gap-0.5 overflow-hidden rounded-full" aria-hidden>
        {lines.map((c, i) => (
          <span key={c.id} style={{ width: `${(c.monthly / total) * 100}%`, background: colorOf(i) }} />
        ))}
      </div>
      <ul aria-label="Cost by part" className="mt-3 grid gap-x-6 gap-y-1.5 sm:grid-cols-2">
        {lines.map((c, i) => (
          <li key={c.id} className="flex items-center gap-2 text-[13px]">
            <span className="size-2.5 shrink-0 rounded-[3px]" style={{ background: colorOf(i) }} aria-hidden />
            <span className="min-w-0 flex-1 truncate text-ink-2">{c.label}</span>
            <span className="tabular shrink-0 text-ink">{partUsd(c.monthly)}</span>
          </li>
        ))}
      </ul>
      <FinePrint cost={cost} lines={lines} />
    </section>
  );
}
