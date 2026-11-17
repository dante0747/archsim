/**
 * Seed groups and quick checks (W9).
 *
 * A seed group runs one input with seeds base … base + N − 1, so a verdict can be read as "Meets
 * the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds 42–46" instead of resting
 * on one random sequence. Runs that disagree are *borderline*, and their confidence is capped at
 * medium. A quick check (`mode: "sampled"`) answers a long steady run from a sample; its report
 * is an estimate, and its charts cover only the sample.
 *
 * The API's `GET /run-groups/{id}` answers ranges from the engine (`SeedSummary`) against the
 * objectives the runs were made with; the web app recomputes the split with the objectives as they
 * are now, as the scorecard does for a single run. `seedRange` mirrors the engine's `SeedSummary`
 * (archsim-engine's `io.archsim.engine.seeds`).
 */
import { request } from "./api";
import { evaluateSlo } from "./slo";
import type { Analysis, ArchitectureDocument, Confidence, ConfidenceLevel, Objectives, Report, Run } from "./types";

export type RunMode = "full" | "sampled";

/** Seeds a "Check 5 seeds" group runs (within the hosted limit of 5; 10 locally). */
export const CHECK_SEEDS = 5;
/** A single run's p95 this close to its objective (as a share of it) is worth checking with more seeds. */
export const NEAR_OBJECTIVE = 0.15;

export interface ValueRange {
  min: number;
  max: number;
  median: number;
}

/** `SeedSummary` (engine), as `GET /run-groups/{id}` answers it. */
export interface SeedSummary {
  runs: number;
  finished: number;
  seeds: { first: number | null; last: number | null };
  objectives: number;
  met: number;
  missed: number;
  undecided: number;
  verdict: "holds" | "fails" | "borderline" | null;
  borderline: boolean;
  confidenceCap: ConfidenceLevel | null;
  latencyMs: { p50: ValueRange | null; p95: ValueRange | null; p99: ValueRange | null };
  successRate: ValueRange | null;
  throughputRps: ValueRange | null;
  text: string;
}

export interface RunGroup {
  id: string;
  projectId: string;
  kind: "seeds" | "sweep";
  baseSeed: number;
  size: number;
  mode: RunMode;
  scenarioId: string | null;
  label: string | null;
  createdAt: string;
  status: "running" | "succeeded" | "partial" | "failed";
  runs: Run[];
  summary: SeedSummary;
}

/** How a report was simulated (report `simulationMode`; absent on an ordinary full run). */
export interface SimulationMode {
  kind: "sampled" | "full";
  /** "sampled" when a quick check was asked for but the sample was not steady. */
  requestedMode?: "sampled";
  requestedDurationS: number;
  simulatedDurationS: number;
  note: string;
}

/** Whether "Quick check (estimated)" can answer the design (analysis `quickCheck`). */
export interface QuickCheck {
  eligible: boolean;
  reason: string;
  requestedS: number;
  simulatedS: number | null;
  sampleS: number | null;
}

export const getRunGroup = (id: string) => request<RunGroup>(`/run-groups/${encodeURIComponent(id)}`);
export const getRunInput = (id: string) => request<ArchitectureDocument>(`/runs/${encodeURIComponent(id)}/input`);

export function simulationModeOf(report: Report | null | undefined): SimulationMode | null {
  return ((report as (Report & { simulationMode?: SimulationMode }) | null | undefined)?.simulationMode ?? null) as SimulationMode | null;
}

/** The report answers from a sample: an estimate, whose counts and charts cover only the sample. */
export function isEstimated(report: Report | null | undefined): boolean {
  return simulationModeOf(report)?.kind === "sampled";
}

export function quickCheckOf(analysis: Analysis | null | undefined): QuickCheck | null {
  return (analysis as (Analysis & { quickCheck?: QuickCheck }) | null | undefined)?.quickCheck ?? null;
}

const DASH = "–";

/** Like the scorecard's latency figures: one decimal under 10 ms, else whole milliseconds. */
export function fmtRangeMs(v: number): string {
  return v < 10 ? v.toFixed(1) : String(Math.floor(v + 0.5));
}

export function rangeOf(values: (number | null | undefined)[]): ValueRange | null {
  const vals = values.filter((v): v is number => v != null && Number.isFinite(v)).sort((a, b) => a - b);
  if (!vals.length) return null;
  const mid = Math.floor(vals.length / 2);
  const median = vals.length % 2 ? vals[mid] : (vals[mid - 1] + vals[mid]) / 2;
  return { min: vals[0], max: vals[vals.length - 1], median };
}

export function rangeText(label: string, r: ValueRange | null): string | null {
  if (!r) return null;
  const [lo, hi, med] = [fmtRangeMs(r.min), fmtRangeMs(r.max), fmtRangeMs(r.median)];
  return lo === hi ? `${label} ${lo} ms` : `${label} ${lo}${DASH}${hi} ms (median ${med})`;
}

export function seedsText(seeds: number[]): string {
  if (!seeds.length) return "";
  const sorted = [...seeds].sort((a, b) => a - b);
  const [lo, hi] = [sorted[0], sorted[sorted.length - 1]];
  if (lo === hi) return `seed ${lo}`;
  const contiguous = sorted.every((s, i) => s === lo + i);
  return contiguous ? `seeds ${lo}${DASH}${hi}` : `seeds ${seeds.join(", ")}`;
}

export interface SeedRange {
  runs: number;
  finished: number;
  objectives: number;
  met: number;
  missed: number;
  verdict: "holds" | "fails" | "borderline" | null;
  borderline: boolean;
  p95: ValueRange | null;
  successRate: ValueRange | null;
  /** "Meets the objectives in 4 of 5 runs · p95 98–131 ms (median 104) · seeds 42–46" */
  text: string;
}

/** The verdict split and ranges of a group's runs, against `objectives` (as `summarize_seeds`). */
export function seedRange(runs: Pick<Run, "seed" | "status" | "summary">[], objectives: Objectives): SeedRange {
  const done = runs.filter((r) => r.status === "succeeded" && r.summary);
  let met = 0;
  let missed = 0;
  let total = 0;
  for (const r of done) {
    const s = r.summary!;
    const result = evaluateSlo(objectives, {
      summary: {
        latencyMs: { p95: s.p95, p99: s.p99 },
        successRate: s.successRate,
        throughputRps: s.throughputRps,
        lostMessages: s.lostMessages,
        availability: s.availability,
        messageAgeP95Ms: s.messageAgeP95Ms,
      } as Report["summary"],
    });
    total = result.total;
    if (result.pass === true) met++;
    else if (result.pass === false) missed++;
  }
  const finished = done.length;
  const borderline = met > 0 && missed > 0;
  const verdict =
    total === 0 || finished === 0 ? null : borderline ? "borderline" : met === finished ? "holds" : missed === finished ? "fails" : null;
  const p95 = rangeOf(done.map((r) => r.summary!.p95));
  const parts: (string | null)[] = [];
  if (finished && total) parts.push(met || !missed ? `Meets the objectives in ${met} of ${finished} runs` : `Misses the objectives in ${missed} of ${finished} runs`);
  else if (finished) parts.push(`${finished} runs, no objectives set`);
  parts.push(rangeText("p95", p95), seedsText(runs.map((r) => r.seed)));
  return {
    runs: runs.length,
    finished,
    objectives: total,
    met,
    missed,
    verdict,
    borderline,
    p95,
    successRate: rangeOf(done.map((r) => r.summary!.successRate)),
    text: parts.filter(Boolean).join(" · "),
  };
}

const LEVELS: ConfidenceLevel[] = ["low", "medium", "high"];

/** Why a borderline verdict's confidence is capped at medium. */
export const BORDERLINE_REASON_TEXT = "The runs disagree on the verdict, so it is borderline";

/** Confidence lowered to `cap` (borderline runs: medium), with the reason saying why. */
export function capConfidence(confidence: Confidence, cap: ConfidenceLevel | null, why: string): Confidence {
  if (!cap || LEVELS.indexOf(confidence.level) <= LEVELS.indexOf(cap)) return confidence;
  return { ...confidence, level: cap, reason: why, reasons: [why, ...confidence.reasons] };
}

/**
 * Whether a single run's p95 is within 15% of the p95 objective, either side: close enough that
 * another seed could flip the verdict, so Results offers "Check 5 seeds".
 */
export function nearObjective(report: Pick<Report, "summary">, objectives: Objectives): boolean {
  const target = objectives.latencyP95Ms;
  const p95 = report.summary.latencyMs.p95;
  if (target == null || target <= 0 || p95 == null) return false;
  return Math.abs(p95 - target) <= NEAR_OBJECTIVE * target;
}
