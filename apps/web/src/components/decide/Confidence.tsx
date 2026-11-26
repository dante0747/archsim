"use client";

/**
 * The confidence badge every verdict carries, the way to the inputs behind it, and the instant
 * estimate from queueing formulas shown while a run is queued or running.
 */
import { Calculator } from "lucide-react";
import { ConfidenceBadge } from "@/components/report-view/ConfidenceBadge";
import { Tip } from "@/components/ui/misc";
import { estimateText } from "@/lib/confidence";
import type { Estimate } from "@/lib/types";
import { useDecide } from "@/store/decide";

export { ConfidenceBadge };

/** Opens the inputs list (Details → Assumptions), where the confidence section explains the level. */
export function showInputs() {
  useDecide.getState().setDetailsView("assumptions");
  requestAnimationFrame(() => document.getElementById("confidence")?.scrollIntoView({ block: "start" }));
}

function Notes({ estimate }: { estimate: Estimate }) {
  return (
    <span className="block max-w-96 space-y-1">
      {estimate.notes.map((n) => (
        <span key={n} className="block">
          {n}
        </span>
      ))}
    </span>
  );
}

/** While a run is queued or running: what the formulas predict for the design. */
export function EstimatePending({ estimate }: { estimate: Estimate }) {
  return (
    <div className="flex items-start gap-2.5 rounded-md border border-dashed border-line-strong bg-surface p-3 text-[13px] text-ink-2" data-testid="estimate-pending">
      <Calculator className="mt-0.5 size-4 shrink-0 text-ink-3" aria-hidden />
      <p className="min-w-0 leading-relaxed">
        {estimateText(estimate)}
        {"."}{" "}
        <Tip content={<Notes estimate={estimate} />}>
          <button type="button" className="rounded-sm text-ink-3 underline decoration-dotted underline-offset-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-accent">
            rough: what it leaves out
          </button>
        </Tip>
      </p>
    </div>
  );
}
