"use client";

/**
 * How much of a verdict is the design and how much is the seed (W9): starting a seed group, the
 * Borderline badge for runs that disagree, and the labels of an answer estimated from a sample
 * ("Estimated", and the note charts carry). The verdict's caveats (Caveats.tsx) use them.
 */
import { FlaskConical } from "lucide-react";
import { toast } from "sonner";
import { Tip } from "@/components/ui/misc";
import { CHECK_SEEDS, getRunInput, simulationModeOf } from "@/lib/seed-groups";
import type { Report, Run } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useRuns } from "@/store/runs";

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
