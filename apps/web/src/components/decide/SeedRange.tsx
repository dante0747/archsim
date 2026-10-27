"use client";

/**
 * How much of a verdict is the design and how much is the seed (W9).
 *
 * - A run of a seed group shows the group: "Meets the objectives in 4 of 5 runs · p95 98–131 ms
 *   (median 104) · seeds 42–46", with an amber Borderline when the runs disagree (and the
 *   confidence line capped at medium).
 * - A single run whose p95 is within 15% of the objective offers "Check 5 seeds".
 * - A quick check's report is labelled "estimated", and charts say they cover only the sample.
 */
import { Dices, FlaskConical } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Tip } from "@/components/ui/misc";
import { BORDERLINE_REASON_TEXT, CHECK_SEEDS, NEAR_OBJECTIVE, capConfidence, getRunInput, isEstimated, nearObjective, seedRange, simulationModeOf } from "@/lib/seed-groups";
import { formatSloValue } from "@/lib/slo";
import type { Confidence, Report, Run } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useEditor } from "@/store/editor";
import { useRuns } from "@/store/runs";
import { useSeedGroup } from "@/store/seed-groups";
import { ConfidenceLine } from "./Confidence";

/** Start a seed group from a finished run: its stored input, scenario and mode, 5 seeds from its own. */
export async function checkSeeds(run: Run): Promise<void> {
  try {
    const doc = await getRunInput(run.id);
    await useRuns.getState().startRun(doc, run.scenarioId, { seeds: CHECK_SEEDS, mode: run.mode ?? "full" });
  } catch (e) {
    toast.error("Could not start the seed check", { description: e instanceof Error ? e.message : String(e) });
  }
}

export function BorderlineBadge({ className }: { className?: string }) {
  return (
    <Tip content="The runs disagree: with these objectives the answer depends on the seed. Treat it as a no until the design has headroom.">
      <span
        data-testid="borderline-badge"
        className={cn("inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-warning-soft px-2 py-px text-[12.5px] font-semibold leading-5 text-ink", className)}
      >
        <span className="size-2 rounded-full" style={{ background: "var(--status-warning)" }} aria-hidden />
        Borderline
      </span>
    </Tip>
  );
}

/** "Estimated" next to anything a quick check answered. */
export function EstimatedBadge({ report, className }: { report: Report | null | undefined; className?: string }) {
  const mode = simulationModeOf(report);
  if (mode?.kind !== "sampled") return null;
  return (
    <Tip content={mode.note}>
      <span
        data-testid="estimated-badge"
        className={cn("inline-flex items-center gap-1 whitespace-nowrap rounded-full bg-warning-soft px-2 py-px text-[12px] font-medium leading-5 text-ink", className)}
      >
        <FlaskConical className="size-3.5" aria-hidden />
        Estimated
      </span>
    </Tip>
  );
}

/** For charts: what a sampled report's time series cover. */
export function SampleNote({ report, className }: { report: Report | null | undefined; className?: string }) {
  const mode = simulationModeOf(report);
  if (!mode) return null;
  const text =
    mode.kind === "sampled"
      ? `Estimated from a quick check: these charts cover only the ${mode.simulatedDurationS.toLocaleString("en-US")} s that were simulated (warm-up and a steady sample), not the ${mode.requestedDurationS.toLocaleString("en-US")} s run. Rates, success and percentiles stand for the whole run; counts cover the sample.`
      : mode.note;
  return (
    <p data-testid="sample-note" className={cn("flex items-start gap-2 rounded-md border border-line bg-surface-2/60 px-3 py-2 text-xs leading-relaxed text-ink-2", className)}>
      <FlaskConical className="mt-0.5 size-3.5 shrink-0 text-ink-3" aria-hidden />
      <span>{text}</span>
    </p>
  );
}

/** Under the verdict: the quick-check label, then the seed group's range or the offer to check more seeds. */
export function SeedRange({ run, report }: { run: Run; report: Report }) {
  const objectives = useEditor((s) => s.objectives);
  const group = useSeedGroup(run.groupId);
  const starting = useRuns((s) => s.starting);
  const mode = simulationModeOf(report);
  const estimated = isEstimated(report);

  let seeds: React.ReactNode = null;
  if (run.groupId && group) {
    const range = seedRange(group.runs, objectives);
    const active = group.runs.filter((r) => r.status === "queued" || r.status === "running").length;
    const failed = group.runs.filter((r) => r.status === "failed" || r.status === "cancelled").length;
    seeds = (
      <p data-testid="seed-range" data-verdict={range.verdict ?? "none"} className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 text-[14px] leading-relaxed text-ink">
        {range.borderline && <BorderlineBadge />}
        <Dices className="size-4 shrink-0 text-ink-3" aria-hidden />
        <span>{range.finished ? range.text : `Running ${range.runs} seeds…`}</span>
        {active > 0 && range.finished > 0 && (
          <span className="text-ink-3">
            ({range.finished} of {range.runs} finished)
          </span>
        )}
        {failed > 0 && active === 0 && <span className="text-ink-3">({failed} did not finish)</span>}
      </p>
    );
  } else if (!run.groupId && nearObjective(report, objectives)) {
    const target = objectives.latencyP95Ms ?? 0;
    seeds = (
      <div data-testid="check-seeds" className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2 rounded-md border border-line bg-surface-2/60 px-3 py-2">
        <p className="min-w-0 flex-1 text-[13px] leading-relaxed text-ink-2">
          p95 {formatSloValue("p95", report.summary.latencyMs.p95)} is within {Math.round(NEAR_OBJECTIVE * 100)}% of the {formatSloValue("p95", target)} objective, and this is one
          seed ({run.seed}). Another could change the answer.
        </p>
        <Button size="sm" variant="secondary" onClick={() => void checkSeeds(run)} disabled={starting}>
          <Dices /> Check {CHECK_SEEDS} seeds
        </Button>
      </div>
    );
  }

  return (
    <>
      {estimated && mode && (
        <p className="mt-2 flex flex-wrap items-center gap-2 text-[13px] text-ink-2" data-testid="estimated-line">
          <EstimatedBadge report={report} />
          <span>{mode.note}</span>
        </p>
      )}
      {mode?.kind === "full" && <p className="mt-2 text-[13px] text-ink-3">{mode.note}</p>}
      {seeds}
    </>
  );
}

/** The confidence line, capped at medium while the run's seed group is borderline. */
export function SeedConfidenceLine({ run, confidence, fromDesign }: { run: Run; confidence: Confidence; fromDesign?: boolean }) {
  const objectives = useEditor((s) => s.objectives);
  const group = useSeedGroup(run.groupId);
  const borderline = !!group && seedRange(group.runs, objectives).borderline;
  return <ConfidenceLine confidence={borderline ? capConfidence(confidence, "medium", BORDERLINE_REASON_TEXT) : confidence} fromDesign={fromDesign} />;
}
