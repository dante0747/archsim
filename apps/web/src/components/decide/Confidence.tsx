"use client";

/**
 * The confidence badge every verdict carries, and the instant estimate from queueing formulas:
 * shown while a run is queued or running, then set against what the run simulated.
 */
import { Calculator } from "lucide-react";
import { ConfidenceBadge } from "@/components/report-view/ConfidenceBadge";
import { Tip } from "@/components/ui/misc";
import { compareEstimate, estimateText } from "@/lib/confidence";
import type { Confidence, Estimate, Report } from "@/lib/types";
import { useDecide } from "@/store/decide";

export { ConfidenceBadge };

/** Opens the inputs list (Details → Assumptions), where the confidence section explains the level. */
export function showInputs() {
  useDecide.getState().setDetailsView("assumptions");
  requestAnimationFrame(() => document.getElementById("confidence")?.scrollIntoView({ block: "start" }));
}

/**
 * "Low confidence: 31 of 35 inputs are illustrative defaults · how to raise it". ``fromDesign``:
 * the run predates confidence, so this is the current design's (it may have changed since).
 */
export function ConfidenceLine({ confidence, fromDesign = false }: { confidence: Confidence; fromDesign?: boolean }) {
  return (
    <p className="mt-3 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-[13.5px] leading-relaxed text-ink-2" data-testid="confidence-line">
      <ConfidenceBadge level={confidence.level} />
      <span data-testid="confidence-reason">
        {confidence.reason}
        {fromDesign && <span className="text-ink-3"> (current design; simulate again to record it with the run)</span>}
      </span>
      <span aria-hidden className="text-ink-3">
        ·
      </span>
      <Tip
        content={
          <span className="block max-w-80 space-y-1">
            <span className="block">{confidence.howToRaise}</span>
            {confidence.drivers.length > 0 && (
              <span className="block text-ink-2">Replace first: {confidence.drivers.slice(0, 3).map((d) => `${d.label} ${d.fieldLabel.toLowerCase()}`).join(", ")}.</span>
            )}
          </span>
        }
      >
        <button type="button" onClick={showInputs} className="rounded-sm font-medium text-ink underline decoration-dotted underline-offset-2 hover:decoration-solid focus-visible:outline-2 focus-visible:outline-accent">
          how to raise it
        </button>
      </Tip>
      <span aria-hidden className="text-ink-3">
        ·
      </span>
      <a
        href="/accuracy"
        target="_blank"
        rel="noreferrer"
        className="rounded-sm font-medium text-ink underline decoration-dotted underline-offset-2 hover:decoration-solid focus-visible:outline-2 focus-visible:outline-accent"
      >
        measured accuracy
      </a>
    </p>
  );
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

/** After the run: the formulas' prediction for exactly this input, against the simulation. */
export function EstimateVsSimulation({ report }: { report: Report }) {
  const cmp = compareEstimate(report);
  if (!cmp || !report.estimate) return null;
  return (
    <p className="mt-3 flex items-start gap-1.5 text-xs leading-relaxed text-ink-3" data-testid="estimate-vs-sim">
      <Calculator className="mt-0.5 size-3.5 shrink-0" aria-hidden />
      <span>
        {cmp.text}{" "}
        <Tip content={<Notes estimate={report.estimate} />}>
          <button type="button" className="rounded-sm underline decoration-dotted underline-offset-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-accent">
            not simulated
          </button>
        </Tip>
      </span>
    </p>
  );
}
