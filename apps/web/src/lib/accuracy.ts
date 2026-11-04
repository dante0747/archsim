/**
 * The accuracy benchmarks as data, for the /accuracy page. `src/data/accuracy.json` is written by
 * `benchmarks/report.py --json` (a copy of docs/validation/accuracy.json; a test keeps them equal),
 * from the same numbers as docs/validation.md. Text fields (`text`) are the report's own
 * formatting, so the page and validation.md show the same figures.
 */
import raw from "@/data/accuracy.json";

// The repository is private: the page names these files instead of linking to them.
export const VALIDATION_DOC = "docs/validation.md";
export const CALIBRATION_DOC = "docs/calibration.md";
export const BENCHMARK_WORKFLOW = ".github/workflows/benchmark.yml";

export interface Knee {
  kneeRps: number | null;
  bound: "interpolated" | "above" | "below" | string;
  between?: number[];
  aboveRps?: number | null;
  belowRps?: number | null;
}

export interface Step {
  offeredRps: number;
  atOrBelowKnee: boolean;
  unstable: boolean;
  measured: { p95Ms: number | null; successRate: number | null };
  predicted: { p95Ms: number | null; successRate: number | null };
  p95Error: number | null;
  p95Within: boolean;
  text: { offered: string; measuredP95: string; predictedP95: string; p95Error: string; measuredSuccess: string; predictedSuccess: string };
}

export interface SpreadRun {
  run: string;
  protocol: string;
  calibratedAt: string | null;
  kneeRps: number;
  text: string;
}

export interface Spread {
  runs: number;
  /** Runs on this machine, OS build and power mode left out because they were disturbed. */
  disturbed: number;
  minRps: number;
  maxRps: number;
  plusMinus: number;
  text: string;
  plusMinusText: string;
  values: SpreadRun[];
}

export interface LowLoadStep {
  offeredRps: number;
  fractionOfPredictedKnee: number;
  measuredP95Ms: number | null;
  predictedP95Ms: number | null;
  diffMs: number | null;
  text: { offered: string; fraction: string; measuredP95: string; predictedP95: string; diff: string; p95Error: string };
}

/** Criteria 1, 2 and 3a are judged over the runs that repeat the latest (`runs` of them, 3 needed). */
export interface Criteria {
  knee: { pass: boolean; error: number | null; runs: number; disturbedRuns: number; thisRun: number | null };
  p95: { pass: boolean; steps: number; misses: number[]; runs: number; runsPassing: number };
  stable: { pass: boolean; unstable: number[]; runs: number; runsPassing: number };
  reproduced: { pass: boolean; protocol: string | null; runs: number; machines: number };
  all: boolean;
}

export interface Environment {
  cpu: string | null;
  os: string | null;
  /** Windows' power mode, recorded since 31 Oct 2026. */
  powerMode: string | null;
  docker: string | null;
  dockerCpus: number | string | null;
  dockerMemoryGiB: number;
  images: Record<string, string>;
  python: string | null;
  cpusets: { container: string; cpus: string; count: number }[];
  cpusetsRecorded: boolean;
  text: string;
}

/** One part at one load step: busy cores (CPU seconds per second) and memory, both ways. */
export interface ResourceCell {
  measuredCores: number | null;
  predictedCores: number | null;
  coresError: number | null;
  measuredMemoryMiB: number | null;
  predictedMemoryMiB: number | null;
  memoryError: number | null;
  text: { measuredCores: string; predictedCores: string; coresError: string; measuredMemory: string; predictedMemory: string; memoryError: string };
}

/** One measured container, its model node's predictions and how far off they were up to the knee. */
export interface ResourcePart {
  container: string;
  name: string;
  modelled: { cores: boolean; memory: boolean };
  /** Why the model has no figure of that kind for this part, when it has none. */
  notModelled: { cores: string | null; memory: string | null };
  belowKnee: {
    steps: number;
    medianAbsCoresError: number | null;
    maxAbsCoresError: number | null;
    medianAbsMemoryError: number | null;
    maxAbsMemoryError: number | null;
  };
  /** At the highest load step at or below the measured knee. */
  top: ResourceCell | null;
  text: { medianAbsCoresError: string; maxAbsCoresError: string; medianAbsMemoryError: string };
}

export interface Resources {
  measuredBy: "window" | "scaled" | "mixed" | string;
  measuredByText: string;
  /** "predictions": saved before measuring; "re-simulated": from the calibration, after the fact. */
  predictedBy: "predictions" | "re-simulated";
  predictedByText: string;
  predictedEngineVersion: string | null;
  memoryMeasured: boolean;
  topRps: number | null;
  parts: ResourcePart[];
  steps: { offeredRps: number; atOrBelowKnee: boolean; text: { offered: string }; parts: Record<string, ResourceCell> }[];
}

/**
 * ArchSim's predicted range for the knee. Engine 0.14.0's runs took it from their replicate calibration sets
 * (`replicates`, `values`; nominal 50%); since W28 it is an 80% range that carries the calibration's run-to-run
 * variation and the model's own error (`method`, `sigma`: its log standard deviation).
 */
export interface KneeRange {
  lowRps: number;
  highRps: number;
  nominalRps: number;
  nominalCoverage: number;
  containsMeasured: boolean | null;
  values?: number[];
  replicates?: number;
  method?: string;
  sigma?: number;
  calibrationSigma?: number;
  modelSigma?: number;
}

/** "too narrow" or "too wide" when the runs inside the range would be that unlikely (under 5%) at its nominal coverage. */
export type CoverageVerdict = "too narrow" | "too wide" | "as claimed";

export interface Repeatability {
  /** Counted runs: the disturbed ones are left out. */
  runs: number;
  disturbed: number;
  protocol: string | null;
  engineVersion: string | null;
  errors: number[];
  medianError: number | null;
  minError: number | null;
  maxError: number | null;
  withRange: number;
  inRange: number;
  nominalCoverage: number | null;
  /** The runs inside the range that its nominal coverage expects. */
  expectedInRange?: number;
  coverageVerdict?: CoverageVerdict | null;
  text: { medianError: string; errors: string; coverage: string };
}

export interface Experiment {
  id: string;
  name: string;
  title: string;
  technologies: string[];
  objective: { p95Ms: number; minSuccess: number };
  date: string;
  calibratedAt: string;
  predictedAt: string;
  engineVersion: string | null;
  protocol: string;
  runs: number;
  inSample: boolean;
  /** "held-out": predicted by a frozen engine that was not tuned on it, before it was first measured (the out-of-sample test); "tuned-on": the model was built with its results in view. */
  sample: "held-out" | "tuned-on";
  /** A flaw of the benchmark that limited this run's measurements, fixed since; null when none. */
  caveat: string | null;
  tier: "validated" | "not-validated";
  criteria: Criteria;
  knee: {
    measured: Knee;
    predicted: Knee;
    searchedRps: number;
    error: number | null;
    withinTolerance: boolean;
    text: { measured: string; predicted: string; error: string; absError: string };
  };
  /** ArchSim's predicted range for the knee. */
  kneeRange: KneeRange | null;
  /** Load steps this run missed below one it met: then it was disturbed and is not counted. */
  disturbed: number[];
  /** The runs that repeat this one: same protocol, machine, OS build, power mode and engine. */
  repeatability: Repeatability | null;
  spread: Spread | null;
  spreadExceedsError: boolean;
  p95BelowKnee: {
    steps: number;
    within: number;
    misses: number[];
    pass: boolean;
    medianAbsError: number | null;
    maxAbsError: number | null;
    worst: Step | null;
    text: { medianAbsError: string; maxAbsError: string };
  };
  lowLoad: LowLoadStep[];
  steps: Step[];
  /** Predicted against measured CPU and memory; null when the run recorded neither. */
  resources: Resources | null;
  environment: Environment;
}

export interface HistoryRun {
  dir: string;
  protocol: string;
  calibratedAt: string | null;
  /** The hardware (its CPU); an OS update on it is not a second machine. */
  machine: string;
  os: string | null;
  /** Windows' power mode, recorded since 31 Oct 2026; null when unknown. */
  powerMode: string | null;
  engineVersion: string | null;
  measuredKnee: Knee;
  predictedKnee: Knee;
  kneeError: number | null;
  kneeRange: { lowRps: number; highRps: number; nominalCoverage: number; containsMeasured: boolean | null; method?: string | null } | null;
  /** The run's own checks; `disturbed` lists the steps it missed below one it met (then it is not counted). */
  checks: { kneeError: number | null; disturbed: number[] };
  text: { measuredKnee: string; predictedKnee: string; kneeError: string };
}

export interface Accuracy {
  generatedBy: string;
  objective: { p95Ms: number; minSuccess: number };
  thresholds: { kneeError: number; p95Factor: number; p95Ms: number; lowLoadFraction: number };
  summary: {
    experiments: number;
    runs: number;
    firstDate: string | null;
    latestDate: string;
    machines: string[];
    kneeErrorMin: number | null;
    kneeErrorMax: number | null;
    kneeErrorRangeText: string;
    kneeWithinTolerance: string[];
    p95WithinTolerance: string[];
    validated: string[];
    withSpread: string[];
    spreadExceedsError: string[];
    inSample: string[];
    /** Experiments whose latest run carries a harness caveat. */
    caveats: string[];
    /** Experiments whose latest conditions ran at least 3 times. */
    repeated: string[];
    /**
     * The predicted range over every experiment's runs under its latest conditions: the runs inside it, the hits
     * its nominal coverage expects, that coverage when every range claims the same, and the verdict.
     */
    coverage: {
      withRange: number;
      inRange: number;
      expectedInRange?: number;
      nominalCoverage?: number | null;
      verdict?: CoverageVerdict | null;
    };
    /** The same over the held-out experiments alone (none until they are measured): the range's out-of-sample test. */
    coverageHeldOut?: Accuracy["summary"]["coverage"];
    /** Kept runs left out of the criteria, repeatability and spread because they were disturbed. */
    disturbedRuns: number;
    technologies: { name: string; experiments: string[]; validated: boolean }[];
  };
  experiments: Experiment[];
  history: Record<string, HistoryRun[]>;
}

export const accuracy = raw as unknown as Accuracy;

/** A signed figure from the report ("-10%") with a typographic minus ("−10%"). */
export function minus(text: string): string {
  return text.replace(/^-(?=\d)/, "−").replace(/ -(?=\d)/g, " −");
}

/** "26 Oct 2026" from an ISO date or timestamp (UTC, so the server and every reader agree). */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso);
  return d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}

/** "26 Oct, 08:00 UTC" from a timestamp. */
export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso);
  const day = d.toLocaleDateString("en-GB", { day: "numeric", month: "short", timeZone: "UTC" });
  const time = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: "UTC" });
  return `${day}, ${time} UTC`;
}

/**
 * A load axis from 0 that fits `top` with a little room: 3 to 5 round ticks (steps of 1, 2, 2.5
 * or 5 times a power of ten).
 */
export function kneeScale(top: number): { max: number; ticks: number[] } {
  const want = Math.max(top, 1) * 1.08;
  const raw = want / 4;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw) ?? 10 * mag;
  const max = Math.ceil(want / step) * step;
  const ticks: number[] = [];
  for (let t = 0; t <= max + step / 1000; t += step) ticks.push(Math.round(t * 1000) / 1000);
  return { max, ticks };
}

/** Plain words for "percent": "-10%" -> "10%", for sentences that say "too high" or "too low". */
function abs(x: Experiment): string {
  return x.knee.text.absError;
}

/**
 * One line on the capacity knee, good or bad: "within 6% (1,006 against 945 req/s measured), a
 * little high" / "97% too high: predicted 444 req/s where the system managed 225".
 */
export function kneeSentence(x: Experiment): string {
  const { error, withinTolerance, text } = x.knee;
  if (error === null) return `No knee error: measured ${text.measured} req/s, predicted ${text.predicted} req/s.`;
  const pair = `${text.predicted} req/s predicted against ${text.measured} req/s measured`;
  if (error === 0) return `Predicted the knee exactly (${pair}).`;
  if (withinTolerance) {
    const side = error < 0 ? "on the low side, which is the safe side" : "on the high side";
    return `Predicted the knee within ${abs(x)} (${pair}), ${side}.`;
  }
  return error > 0
    ? `Predicted the knee ${abs(x)} too high: ${text.predicted} req/s where the system managed ${text.measured} req/s.`
    : `Predicted the knee ${abs(x)} too low: ${text.predicted} req/s where the system managed ${text.measured} req/s (the safe side, but far off).`;
}

/** The experiments ordered from the closest knee prediction to the furthest. */
export function byKneeError(experiments: Experiment[]): Experiment[] {
  return [...experiments].sort((a, b) => Math.abs(a.knee.error ?? Infinity) - Math.abs(b.knee.error ?? Infinity));
}

/** "4 of 6 load steps" with the latency (p95) tolerance met, in words. */
export function p95Sentence(x: Experiment, t: Accuracy["thresholds"]): string {
  const b = x.p95BelowKnee;
  if (b.steps === 0) return "No measured step was below the knee, so latency there could not be checked.";
  const tol = `a factor of ${t.p95Factor} or ${t.p95Ms} ms`;
  const head = `Latency (p95) within ${tol} at ${b.within} of ${b.steps} load steps up to the measured knee`;
  if (!b.worst || b.pass) return `${head}; the typical miss was ${b.text.medianAbsError}.`;
  const w = b.worst.text;
  return `${head}. Worst: at ${w.offered} req/s it predicted ${w.predictedP95} ms where the system measured ${w.measuredP95} ms.`;
}

/** The light-load steps in one phrase: "+0.90 to +1.08 ms above the measured p95". */
export function lowLoadSentence(x: Experiment): string | null {
  const diffs = x.lowLoad.map((s) => s.diffMs).filter((d): d is number => d !== null);
  if (!diffs.length) return null;
  const texts = x.lowLoad.filter((s) => s.diffMs !== null).map((s) => s.text.diff.replace(" ms", ""));
  const where = diffs.every((d) => d > 0) ? "above" : diffs.every((d) => d < 0) ? "below" : "off from";
  // "0.16 to 0.44 ms below", smallest gap first, as "above" reads
  const key = (t: string) => (where === "below" ? Math.abs(Number(t)) : Number(t));
  const sorted = [...texts].sort((a, b) => key(a) - key(b));
  const range = sorted.length === 1 || sorted[0] === sorted[sorted.length - 1] ? sorted[0] : `${sorted[0]} to ${sorted[sorted.length - 1]}`;
  const shown = where === "off from" ? range : range.replace(/[+-]/g, "");
  return `At light load (${x.lowLoad.map((s) => s.text.offered).join(" and ")} req/s), predicted p95 was ${minus(shown)} ms ${where} the measured one.`;
}

/** "2,268" for the resources' top step, from that step's own text. */
export function topStepText(r: Resources): string | null {
  return r.steps.find((s) => s.offeredRps === r.topRps)?.text.offered ?? null;
}

/**
 * The CPU check in one sentence: the closest and the furthest modelled part, by median error up
 * to both knees ("off by a median of 8% (PostgreSQL in “...”) to 88% (Redis in “...”)").
 */
export function cpuSentence(experiments: Experiment[]): string | null {
  const parts = experiments.flatMap((x) =>
    (x.resources?.parts ?? []).filter((p) => p.modelled.cores && p.belowKnee.medianAbsCoresError !== null).map((p) => ({ x, p })),
  );
  if (!parts.length) return null;
  const sorted = [...parts].sort((a, b) => (a.p.belowKnee.medianAbsCoresError ?? 0) - (b.p.belowKnee.medianAbsCoresError ?? 0));
  const label = ({ x, p }: (typeof parts)[number]) => `${p.text.medianAbsCoresError} (${p.name} in “${x.name}”)`;
  const lo = sorted[0];
  const hi = sorted[sorted.length - 1];
  const range = lo === hi ? label(lo) : `${label(lo)} to ${label(hi)}`;
  return `ArchSim's CPU predictions (busy cores) were off by a median of ${range} at the load steps up to both knees.`;
}

/** How the latest run's conditions repeated, and how often the predicted range held the measured knee. */
export function repeatSentence(x: Experiment, minRuns = 3): string {
  const r = x.repeatability;
  if (!r) return "";
  const where = `protocol ${r.protocol ?? "-"}, engine ${r.engineVersion ?? "-"}`;
  const head =
    r.runs < minRuns
      ? `${r.runs} counted run${r.runs === 1 ? "" : "s"} under these conditions (${where}); the thresholds need ${minRuns}.`
      : `Over ${r.runs} runs under these conditions (${where}) the knee error had a median of ${minus(r.text.medianError)} (${minus(r.text.errors)}).`;
  const left = r.disturbed
    ? ` ${r.disturbed} disturbed run${r.disturbed === 1 ? " is" : "s are"} not counted: ${r.disturbed === 1 ? "it" : "each"} missed the objective at one load step and met it at a higher one.`
    : "";
  if (!r.withRange) return head + left;
  const nominal = r.nominalCoverage === null ? "" : ` (expected about ${Math.round(r.nominalCoverage * 100)}%)`;
  return `${head}${left} The measured knee fell inside ArchSim's predicted range in ${r.text.coverage} runs${nominal}.`;
}

/** The share of runs a set of ranges should hold: their common nominal coverage, else the expected hits over the runs. */
function expectedShare(c: Accuracy["summary"]["coverage"]): number | null {
  if (c.nominalCoverage !== undefined && c.nominalCoverage !== null) return c.nominalCoverage;
  if (c.expectedInRange !== undefined && c.withRange) return c.expectedInRange / c.withRange;
  return null;
}

/**
 * How often ArchSim's predicted range held the measured knee, over every experiment's runs under its latest conditions,
 * against its nominal coverage, and the report's verdict (a count under 5% likely at that coverage is too narrow or
 * too wide). Null before any run had a range.
 */
export function coverageSentence(s: Accuracy["summary"]): string | null {
  const c = s.coverage;
  if (!c.withRange) return null;
  const share = expectedShare(c);
  const expected = share === null ? "" : `, where about ${Math.round(share * 100)}% was expected`;
  const head = `ArchSim's predicted range for the knee held the measured one in ${c.inRange} of ${c.withRange} runs${expected}`;
  const verdict = c.verdict === "too narrow" ? ": the range is too narrow." : c.verdict === "too wide" ? ": the range is wider than it needs to be." : ".";
  const h = s.coverageHeldOut;
  const heldOut = h?.withRange ? ` On the held-out experiments alone, ${h.inRange} of ${h.withRange}.` : "";
  return `${head}${verdict}${heldOut}`;
}

/** Whether the predicted range held the measured knee about as often as it claims (false before any run had one). */
export function coverageAsClaimed(s: Accuracy["summary"]): boolean {
  return s.coverage.withRange > 0 && s.coverage.verdict === "as claimed";
}

/** What the predicted range is, for its tooltip: from the replicate calibration sets, or W28's nominal range. */
export function rangeSource(r: KneeRange): string {
  if (r.replicates) return `from ${r.replicates} replicate calibration sets`;
  return `${Math.round(r.nominalCoverage * 100)}% range: the calibration's run-to-run variation and the model's own error`;
}

/** What the run-to-run spread says next to the knee error. */
export function spreadSentence(x: Experiment): string {
  const s = x.spread;
  if (!s) return x.runs > 1 ? "No second run measured a knee on this machine, OS build and power mode yet, so its run-to-run spread is unknown." : "Measured once so far, so its run-to-run spread is unknown.";
  const left = s.disturbed ? `, leaving out ${s.disturbed} disturbed run${s.disturbed === 1 ? "" : "s"}` : "";
  const head = `The measured knee ranged from ${s.text} over ${s.runs} runs on this machine (${s.plusMinusText} around the middle${left})`;
  if (x.knee.error === null) return `${head}.`;
  return x.spreadExceedsError
    ? `${head}: wider than the ${minus(x.knee.text.error)} error, which is inside run-to-run noise.`
    : `${head}: the ${minus(x.knee.text.error)} error is larger than that.`;
}
