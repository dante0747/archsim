/**
 * The accuracy page's headline figures, for the home page: the held-out knees first, with how
 * many over-promised capacity (the unsafe side) and by how much, then the tuned-on ones, and the
 * runs behind them. Computed from the same data as /accuracy, so the two never disagree.
 */
import { type Accuracy, type Experiment, formatDate, minus } from "@/lib/accuracy";

export interface AccuracyHighlights {
  /** The knee error a prediction must be within to count as close ("±25%"). */
  tolerance: string;
  heldOut: { within: number; total: number; over: number; overRange: string | null; under: number } | null;
  tunedOn: { within: number; total: number; range: string } | null;
  validated: number;
  experiments: number;
  runs: number;
  machines: number;
  period: string;
}

const byError = (a: Experiment, b: Experiment) => (a.knee.error ?? 0) - (b.knee.error ?? 0);

function range(xs: Experiment[], text: (x: Experiment) => string): string | null {
  if (xs.length === 0) return null;
  const sorted = [...xs].sort(byError);
  const [low, high] = [text(sorted[0]), text(sorted[sorted.length - 1])];
  return low === high ? low : `${low} to ${high}`;
}

export function accuracyHighlights(data: Accuracy): AccuracyHighlights {
  const scored = data.experiments.filter((x) => x.knee.error !== null);
  const held = scored.filter((x) => x.sample === "held-out");
  const tuned = scored.filter((x) => x.sample !== "held-out");
  const missed = held.filter((x) => !x.knee.withinTolerance);
  const over = missed.filter((x) => (x.knee.error ?? 0) > 0);
  const s = data.summary;
  return {
    tolerance: `±${Math.round(data.thresholds.kneeError * 100)}%`,
    heldOut: held.length
      ? {
          within: held.length - missed.length,
          total: held.length,
          over: over.length,
          overRange: range(over, (x) => x.knee.text.absError),
          under: missed.length - over.length,
        }
      : null,
    tunedOn: tuned.length
      ? { within: tuned.filter((x) => x.knee.withinTolerance).length, total: tuned.length, range: range(tuned, (x) => minus(x.knee.text.error)) ?? "" }
      : null,
    validated: s.validated.length,
    experiments: s.experiments,
    runs: s.runs,
    machines: s.machines.length,
    period: s.firstDate && s.firstDate !== s.latestDate ? `${formatDate(s.firstDate)} to ${formatDate(s.latestDate)}` : formatDate(s.latestDate),
  };
}
