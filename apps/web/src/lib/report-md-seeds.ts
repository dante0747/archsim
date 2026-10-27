/**
 * The decision report's lines about seed groups and quick checks (W9): the verdict across seeds
 * with its ranges, borderline verdicts (confidence capped at medium), and "estimated" on a
 * report a quick check answered from a sample.
 */
import { BORDERLINE_REASON_TEXT, capConfidence, rangeOf, seedRange, seedsText, simulationModeOf } from "./seed-groups";
import type { Confidence, Objectives, Report, Run } from "./types";

export type SeedRun = Pick<Run, "seed" | "status" | "summary">;

const ms = (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(2)} s` : `${v >= 100 ? Math.round(v) : v.toFixed(1)} ms`);
const pct = (v: number) => `${(v * 100).toFixed(2)}%`;

/** Summary bullets: the verdict across seeds, and that the run is an estimate. */
export function seedSummaryLines(report: Report, seedRuns: SeedRun[] | null | undefined, objectives: Objectives): string[] {
  const out: string[] = [];
  if (seedRuns && seedRuns.length > 1) {
    const range = seedRange(seedRuns, objectives);
    out.push(`- **Across seeds:** ${range.text}.`);
    if (range.borderline) out.push(`- **Borderline:** the runs disagree on the verdict; with these objectives the answer depends on the seed.`);
  }
  const mode = simulationModeOf(report);
  if (mode?.kind === "sampled") out.push(`- **Estimated (quick check):** ${mode.note}`);
  else if (mode?.kind === "full") out.push(`- **Simulated in full:** ${mode.note}`);
  return out;
}

/** Results: one row per figure across the group's finished runs. */
export function seedResultLines(seedRuns: SeedRun[] | null | undefined, table: (head: string[], rows: unknown[][]) => string): string[] {
  if (!seedRuns || seedRuns.length < 2) return [];
  const done = seedRuns.filter((r) => r.status === "succeeded" && r.summary);
  if (!done.length) return [];
  const row = (label: string, values: (number | null)[], fmt: (v: number) => string) => {
    const r = rangeOf(values);
    return [label, r ? fmt(r.min) : "–", r ? fmt(r.median) : "–", r ? fmt(r.max) : "–"];
  };
  return [
    `Across ${done.length} of ${seedRuns.length} runs (${seedsText(seedRuns.map((r) => r.seed))}):`,
    "",
    table(
      ["Metric", "Lowest", "Median", "Highest"],
      [
        row("Latency p50", done.map((r) => r.summary!.p50), ms),
        row("Latency p95", done.map((r) => r.summary!.p95), ms),
        row("Latency p99", done.map((r) => r.summary!.p99), ms),
        row("Success rate", done.map((r) => r.summary!.successRate), pct),
      ],
    ),
    "",
  ];
}

/** The confidence a report states: capped at medium while its seeds disagree. */
export function seedCappedConfidence(confidence: Confidence | null, seedRuns: SeedRun[] | null | undefined, objectives: Objectives): Confidence | null {
  if (!confidence || !seedRuns || seedRuns.length < 2) return confidence;
  return seedRange(seedRuns, objectives).borderline ? capConfidence(confidence, "medium", BORDERLINE_REASON_TEXT) : confidence;
}
