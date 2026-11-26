"use client";

/**
 * "How sure is this": the caveats a verdict carries, each in a few words with its explanation one
 * click away. In order: an answer estimated from a sample, the seeds behind it, the confidence in
 * its inputs, technologies whose model is not validated, and what queueing formulas predict.
 */
import { Calculator, Dices, FlaskConical, Info } from "lucide-react";
import { checkSeeds } from "@/components/decide/SeedRange";
import { ConfidenceBadge } from "@/components/report-view/ConfidenceBadge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/overlays";
import { compareEstimate } from "@/lib/confidence";
import { BORDERLINE_REASON_TEXT, CHECK_SEEDS, NEAR_OBJECTIVE, capConfidence, isEstimated, nearObjective, seedRange, simulationModeOf } from "@/lib/seed-groups";
import type { Confidence, Report, Run } from "@/lib/types";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useSeedGroup } from "@/store/seed-groups";
import { showInputs } from "./Confidence";
import { BorderlineBadge, EstimatedBadge } from "./SeedRange";

/** An "i" that opens the full explanation of a caveat. */
function More({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button type="button" aria-label={label} className="rounded-full p-0.5 text-ink-3 transition-colors hover:bg-surface-3 hover:text-ink">
          <Info className="size-3.5" />
        </button>
      </PopoverTrigger>
      <PopoverContent>{children}</PopoverContent>
    </Popover>
  );
}

function Caveat({ testId, children }: { testId?: string; children: React.ReactNode }) {
  return (
    <li data-testid={testId} className="flex min-w-0 items-center gap-1.5 text-[13px] leading-6 text-ink-2">
      {children}
    </li>
  );
}

/** The run's seeds: a seed group's range, or the offer to check more seeds when one run is a close call. */
function SeedsCaveat({ run, report }: { run: Run; report: Report }) {
  const objectives = useEditor((s) => s.objectives);
  const group = useSeedGroup(run.groupId);
  const starting = useRuns((s) => s.starting);
  if (run.groupId && group) {
    const range = seedRange(group.runs, objectives);
    const active = group.runs.filter((r) => r.status === "queued" || r.status === "running").length;
    const failed = group.runs.filter((r) => r.status === "failed" || r.status === "cancelled").length;
    return (
      <li data-testid="seed-range" data-verdict={range.verdict ?? "none"} className="flex min-w-0 flex-wrap items-center gap-1.5 text-[13px] leading-6 text-ink-2">
        {range.borderline && <BorderlineBadge />}
        <Dices className="size-4 shrink-0 text-ink-3" aria-hidden />
        <span className="text-ink">{range.finished ? range.text : `Running ${range.runs} seeds…`}</span>
        {active > 0 && range.finished > 0 && (
          <span className="text-ink-3">
            ({range.finished} of {range.runs} finished)
          </span>
        )}
        {failed > 0 && active === 0 && <span className="text-ink-3">({failed} did not finish)</span>}
      </li>
    );
  }
  if (run.groupId || !nearObjective(report, objectives)) return null;
  return (
    <Caveat testId="check-seeds">
      <Dices className="size-4 shrink-0 text-ink-3" aria-hidden />
      <span>
        One seed ({run.seed}), and p95 is within {Math.round(NEAR_OBJECTIVE * 100)}% of the objective: another seed could change the answer.
      </span>
      <Button size="sm" variant="soft" onClick={() => void checkSeeds(run)} disabled={starting} className="ml-1">
        Check {CHECK_SEEDS} seeds
      </Button>
    </Caveat>
  );
}

/** The confidence in the run's inputs, capped at medium while its seed group is borderline. */
function ConfidenceCaveat({ run, confidence, fromDesign }: { run: Run; confidence: Confidence; fromDesign: boolean }) {
  const objectives = useEditor((s) => s.objectives);
  const group = useSeedGroup(run.groupId);
  const borderline = !!group && seedRange(group.runs, objectives).borderline;
  const c = borderline ? capConfidence(confidence, "medium", BORDERLINE_REASON_TEXT) : confidence;
  return (
    <Caveat>
      <ConfidenceBadge level={c.level} />
      <span data-testid="confidence-reason" className="min-w-0 truncate" title={c.reason}>
        {c.reason}
      </span>
      <More label="About the confidence">
        <p className="text-ink">{c.howToRaise}</p>
        {c.drivers.length > 0 && <p className="mt-1.5">Replace first: {c.drivers.slice(0, 3).map((d) => `${d.label} ${d.fieldLabel.toLowerCase()}`).join(", ")}.</p>}
        {fromDesign && <p className="mt-1.5 text-ink-3">This is the current design&apos;s confidence: the run predates it. Simulate again to record it with the run.</p>}
        <div className="mt-3 flex items-center gap-3">
          <Button size="sm" variant="secondary" onClick={showInputs}>
            Show the inputs
          </Button>
          <a href="/accuracy" target="_blank" rel="noreferrer" className="text-xs font-medium text-accent hover:underline">
            Measured accuracy
          </a>
        </div>
      </More>
    </Caveat>
  );
}

/** The verdict's caveats, one short line each. Renders nothing when there are none. */
export function Caveats({ run, report, confidence, fromDesign }: { run: Run; report: Report; confidence: Confidence | null; fromDesign: boolean }) {
  const objectives = useEditor((s) => s.objectives);
  const mode = simulationModeOf(report);
  const experimental = (report.assumptions.technologies ?? []).filter((t) => t.tier === "experimental");
  const formulas = compareEstimate(report);
  const seeds = !!run.groupId || nearObjective(report, objectives);
  if (!isEstimated(report) && !seeds && !confidence && !experimental.length && !formulas) return null;
  return (
    <ul aria-label="How sure is this" className="flex flex-col gap-1">
      {isEstimated(report) && mode && (
        <Caveat testId="estimated-line">
          <EstimatedBadge report={report} />
          <span>Answered from a sample of the run</span>
          <More label="About the quick check">{mode.note}</More>
        </Caveat>
      )}
      <SeedsCaveat run={run} report={report} />
      {confidence && <ConfidenceCaveat run={run} confidence={confidence} fromDesign={fromDesign} />}
      {experimental.length > 0 && (
        <Caveat>
          <FlaskConical className="size-4 shrink-0 text-ink-3" aria-hidden />
          <span>{experimental.length === 1 ? "1 experimental part" : `${experimental.length} experimental parts`}</span>
          <More label="About experimental parts">
            {experimental.map((t) => t.label).join(", ")} {experimental.length === 1 ? "uses a technology" : "use technologies"} whose model is not yet validated
            against measurements.
          </More>
        </Caveat>
      )}
      {formulas && report.estimate && (
        <Caveat testId="estimate-vs-sim">
          <Calculator className="size-4 shrink-0 text-ink-3" aria-hidden />
          <span>
            {formulas.p95.estimate != null ? (
              <>
                Queueing formulas predict p95 ≈ <span className="tabular text-ink">{Math.round(formulas.p95.estimate)} ms</span>
              </>
            ) : (
              "Queueing formulas predict an overload"
            )}
          </span>
          <More label="About the formulas">
            <p className="text-ink">{formulas.text}</p>
            {report.estimate.notes.length > 0 && (
              <ul className="mt-2 list-disc space-y-1 pl-4">
                {report.estimate.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            )}
          </More>
        </Caveat>
      )}
    </ul>
  );
}
