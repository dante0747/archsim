import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  type AgainstEstimates,
  type EstimateComparison,
  type Experiment,
  type KneeErrors,
  type Repeatability,
  type ResourcePart,
  type Spread,
  accuracy,
  arithmeticOf,
  arithmeticSentence,
  byKneeError,
  cpuSentence,
  formatDate,
  formatTime,
  kneeScale,
  kneeSentence,
  leadSentence,
  lowLoadSentence,
  minus,
  p95Sentence,
  coverageAsClaimed,
  coverageSentence,
  rangeSource,
  repeatSentence,
  splitSentence,
  spreadSentence,
  topStepText,
  measuredOn,
} from "./accuracy";

// Synthetic cases built on a real entry, so these tests keep passing when the benchmarks rerun.
const base = accuracy.experiments[0];

function exp(over: Partial<Experiment> & { error?: number | null; within?: boolean; measured?: string; predicted?: string; errorText?: string }): Experiment {
  const { error = -0.1, within = true, measured = "2,291", predicted = "2,064", errorText, ...rest } = over;
  const text = errorText ?? (error === null ? "-" : `${error > 0 ? "+" : "-"}${Math.round(Math.abs(error) * 100)}%`);
  return {
    ...base,
    knee: {
      ...base.knee,
      error,
      withinTolerance: within,
      text: { measured, predicted, error: text, absError: text.replace(/^[+-]/, "") },
    },
    ...rest,
  };
}

const spread: Spread = {
  runs: 3,
  disturbed: 0,
  minRps: 1895,
  maxRps: 3162.3,
  plusMinus: 0.25,
  text: "1,895 to 3,162 req/s",
  plusMinusText: "±25%",
  values: [],
};

describe("accuracy data", () => {
  it("has every experiment with the fields the page reads", () => {
    // the newest held-out set leads, then the nine its engine was tuned on
    expect(accuracy.experiments.map((x) => x.id)).toEqual(["python-cores", "python-wide", "java-hot-row", "go-pool", "cache-miss", "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl", "pg-mixed", "pg-hot-row", "cache-aside", "kafka"]);
    expect(accuracy.experiments.map((x) => x.sample)).toEqual([...Array(5).fill("held-out"), ...Array(9).fill("tuned-on")]);
    for (const x of accuracy.experiments) {
      expect(x.name).toBeTruthy();
      expect(x.knee.text.measured).toMatch(/\d/);
      expect(x.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(x.environment.text).toContain("Docker");
      expect(accuracy.history[x.id]?.length).toBeGreaterThan(0);
    }
    expect(accuracy.summary.kneeErrorRangeText).toMatch(/^[+-]\d+% to [+-]\d+%$/);
  });
});

describe("plain-language lines", () => {
  it("says a close knee is close, and which side it errs on", () => {
    expect(kneeSentence(exp({ error: 0.06, measured: "945", predicted: "1,006" }))).toBe(
      "Predicted the knee within 6% (1,006 req/s predicted against 945 req/s measured), on the high side.",
    );
    expect(kneeSentence(exp({ error: -0.1 }))).toBe(
      "Predicted the knee within 10% (2,064 req/s predicted against 2,291 req/s measured), on the low side, which is the safe side.",
    );
  });

  it("says a bad knee is bad", () => {
    expect(kneeSentence(exp({ error: 0.97, within: false, measured: "225", predicted: "444" }))).toBe(
      "Predicted the knee 97% too high: 444 req/s where the system managed 225 req/s.",
    );
    expect(kneeSentence(exp({ error: -0.4, within: false }))).toContain("40% too low");
    expect(kneeSentence(exp({ error: null, within: false, measured: "> 1,359", predicted: "906" }))).toBe(
      "No knee error: measured > 1,359 req/s, predicted 906 req/s.",
    );
  });

  it("orders experiments from the closest knee to the furthest", () => {
    const xs = [exp({ id: "a", error: 0.97 }), exp({ id: "b", error: null }), exp({ id: "c", error: -0.1 }), exp({ id: "d", error: 0.06 })];
    expect(byKneeError(xs).map((x) => x.id)).toEqual(["d", "c", "a", "b"]);
  });

  it("compares the run-to-run spread with the error", () => {
    expect(spreadSentence(exp({ error: -0.1, spread, spreadExceedsError: true }))).toBe(
      "The measured knee ranged from 1,895 to 3,162 req/s over 3 runs on this machine (±25% around the middle): wider than the −10% error, which is inside run-to-run noise.",
    );
    expect(spreadSentence(exp({ error: 0.97, spread, spreadExceedsError: false }))).toContain("the +97% error is larger than that");
    expect(spreadSentence(exp({ spread: null, runs: 1 }))).toBe("Measured once so far, so its run-to-run spread is unknown.");
  });

  it("describes latency below the knee with its worst step", () => {
    const worst = { ...base.steps[0], text: { ...base.steps[0].text, offered: "2,268", measuredP95: "10.4", predictedP95: "1,916" } };
    const missed = exp({ p95BelowKnee: { ...base.p95BelowKnee, steps: 6, within: 4, pass: false, worst } });
    expect(p95Sentence(missed, accuracy.thresholds)).toBe(
      "Latency (p95) within a factor of 2 or 3 ms at 4 of 6 load steps up to the measured knee. Worst: at 2,268 req/s it predicted 1,916 ms where the system measured 10.4 ms.",
    );
    const met = exp({ p95BelowKnee: { ...base.p95BelowKnee, steps: 2, within: 2, pass: true, text: { medianAbsError: "29%", maxAbsError: "55%" } } });
    expect(p95Sentence(met, accuracy.thresholds)).toBe("Latency (p95) within a factor of 2 or 3 ms at 2 of 2 load steps up to the measured knee; the typical miss was 29%.");
  });

  it("describes light load", () => {
    const step = (offered: string, diffMs: number, diff: string) => ({ ...base.lowLoad[0], diffMs, text: { ...base.lowLoad[0].text, offered, diff } });
    expect(lowLoadSentence(exp({ lowLoad: [step("252", 8.7, "+8.70 ms"), step("503", 8.72, "+8.72 ms")] }))).toBe(
      "At light load (252 and 503 req/s), predicted p95 was 8.70 to 8.72 ms above the measured one.",
    );
    expect(lowLoadSentence(exp({ lowLoad: [step("100", -0.5, "-0.50 ms"), step("200", 1.2, "+1.20 ms")] }))).toBe(
      "At light load (100 and 200 req/s), predicted p95 was −0.50 to +1.20 ms off from the measured one.",
    );
    // all below: smallest gap first, as for "above"
    expect(lowLoadSentence(exp({ lowLoad: [step("1,238", -0.16, "-0.16 ms"), step("2,475", -0.44, "-0.44 ms")] }))).toBe(
      "At light load (1,238 and 2,475 req/s), predicted p95 was 0.16 to 0.44 ms below the measured one.",
    );
    expect(lowLoadSentence(exp({ lowLoad: [] }))).toBeNull();
  });

  it("sums up the CPU check from the closest and the furthest modelled part", () => {
    const real = accuracy.experiments.find((x) => x.resources)?.resources;
    expect(real).toBeTruthy();
    if (!real) return;
    const part = (name: string, median: number | null, modelled = true): ResourcePart => ({
      ...real.parts[0],
      name,
      modelled: { cores: modelled, memory: false },
      belowKnee: { ...real.parts[0].belowKnee, medianAbsCoresError: median },
      text: { ...real.parts[0].text, medianAbsCoresError: median === null ? "-" : `${Math.round(median * 100)}%` },
    });
    const a = exp({ name: "Mixed", resources: { ...real, parts: [part("Service", 0.65), part("PostgreSQL", 0.08)] } });
    const b = exp({ name: "Cache", resources: { ...real, parts: [part("Redis", 0.88), part("Broker", null, false)] } });
    expect(cpuSentence([a, b])).toBe(
      "ArchSim's CPU predictions (busy cores) were off by a median of 8% (PostgreSQL in “Mixed”) to 88% (Redis in “Cache”) at the load steps up to both knees.",
    );
    expect(cpuSentence([exp({ resources: null })])).toBeNull();
    expect(topStepText({ ...real, topRps: real.steps[0].offeredRps })).toBe(real.steps[0].text.offered);
    expect(topStepText({ ...real, topRps: null })).toBeNull();
  });
});

describe("repeated runs", () => {
  const rep = (over: Partial<Repeatability>): Repeatability => ({
    runs: 1,
    disturbed: 0,
    protocol: "v5",
    engineVersion: "0.15.0",
    errors: [-0.1],
    medianError: -0.1,
    minError: -0.1,
    maxError: -0.1,
    meanAbsError: 0.1,
    withRange: 0,
    inRange: 0,
    nominalCoverage: null,
    text: { medianError: "-10%", errors: "-10%", meanAbsError: "10%", coverage: "-" },
    ...over,
  });

  it("says how many runs the thresholds still need", () => {
    expect(repeatSentence(exp({ repeatability: rep({}) }))).toBe("1 counted run under these conditions (protocol v5, engine 0.15.0); the thresholds need 3.");
  });

  it("says which runs were disturbed and left out", () => {
    expect(repeatSentence(exp({ repeatability: rep({ disturbed: 2 }) }))).toBe(
      "1 counted run under these conditions (protocol v5, engine 0.15.0); the thresholds need 3. " +
        "2 disturbed runs are not counted: each missed the objective at one load step and met it at a higher one.",
    );
    const r = rep({ runs: 3, disturbed: 1, withRange: 3, inRange: 1, nominalCoverage: 0.5, text: { medianError: "+4%", errors: "+3% to +20%", meanAbsError: "9%", coverage: "1 of 3" } });
    expect(repeatSentence(exp({ repeatability: r }))).toBe(
      "Over 3 runs under these conditions (protocol v5, engine 0.15.0) the knee error had a median of +4% (+3% to +20%). " +
        "1 disturbed run is not counted: it missed the objective at one load step and met it at a higher one. " +
        "The measured knee fell inside ArchSim's predicted range in 1 of 3 runs (expected about 50%).",
    );
    expect(spreadSentence(exp({ error: -0.1, spread: { ...spread, disturbed: 2 }, spreadExceedsError: true }))).toContain(
      "over 3 runs on this machine (±25% around the middle, leaving out 2 disturbed runs)",
    );
  });

  it("gives the median over repeated runs and how often the predicted range held", () => {
    const r = rep({
      runs: 3,
      withRange: 3,
      inRange: 2,
      nominalCoverage: 0.5,
      text: { medianError: "-12%", errors: "-20% to -5%", meanAbsError: "12%", coverage: "2 of 3" },
    });
    expect(repeatSentence(exp({ repeatability: r }))).toBe(
      "Over 3 runs under these conditions (protocol v5, engine 0.15.0) the knee error had a median of −12% (−20% to −5%). " +
        "The measured knee fell inside ArchSim's predicted range in 2 of 3 runs (expected about 50%).",
    );
  });

  it("says how often the predicted range held the measured knee, against its own nominal coverage", () => {
    type Verdict = "too narrow" | "too wide" | "as claimed";
    const summary = (withRange: number, inRange: number, verdict: Verdict, nominal: number | null = 0.5, expectedInRange = withRange * (nominal ?? 0)) => ({
      ...accuracy.summary,
      coverage: { withRange, inRange, expectedInRange, nominalCoverage: nominal, verdict },
      coverageHeldOut: undefined,
    });
    const head = "ArchSim's predicted range for the knee held the measured one in";
    expect(coverageSentence(summary(12, 1, "too narrow"))).toBe(`${head} 1 of 12 runs, where about 50% was expected: the range is too narrow.`);
    expect(coverageSentence(summary(12, 5, "as claimed"))).toBe(`${head} 5 of 12 runs, where about 50% was expected.`);
    expect(coverageSentence(summary(12, 10, "as claimed", 0.8))).toBe(`${head} 10 of 12 runs, where about 80% was expected.`);
    expect(coverageSentence(summary(12, 12, "too wide", 0.5))).toBe(`${head} 12 of 12 runs, where about 50% was expected: the range is wider than it needs to be.`);
    // ranges with different nominal coverages: the share their expected hits make
    expect(coverageSentence(summary(6, 4, "as claimed", null, 3.9))).toBe(`${head} 4 of 6 runs, where about 65% was expected.`);
    expect(coverageSentence(summary(0, 0, "as claimed"))).toBeNull();
    // the held-out experiments, once measured, are the range's out-of-sample test
    const held = { ...summary(15, 12, "as claimed", 0.8), coverageHeldOut: { withRange: 5, inRange: 4, expectedInRange: 4, nominalCoverage: 0.8, verdict: "as claimed" as Verdict } };
    expect(coverageSentence(held)).toBe(`${head} 12 of 15 runs, where about 80% was expected. On the held-out experiments alone, 4 of 5.`);
    expect(coverageAsClaimed(summary(12, 10, "as claimed", 0.8))).toBe(true);
    expect(coverageAsClaimed(summary(12, 1, "too narrow"))).toBe(false);
    expect(coverageAsClaimed(summary(0, 0, "as claimed"))).toBe(false);
  });

  it("says where a predicted range came from", () => {
    const old = { lowRps: 4800, highRps: 5000, nominalRps: 4956, nominalCoverage: 0.5, containsMeasured: false, values: [4800, 5000, 4950], replicates: 3 };
    expect(rangeSource(old)).toBe("from 3 replicate calibration sets");
    const w28 = { lowRps: 3700, highRps: 6600, nominalRps: 4950, nominalCoverage: 0.8, containsMeasured: true, method: "...", sigma: 0.22 };
    expect(rangeSource(w28)).toBe("80% range: the calibration's run-to-run variation and the model's own error");
  });

  it("says nothing without a record of runs", () => {
    expect(repeatSentence(exp({ repeatability: null }))).toBe("");
  });
});

describe("formatting", () => {
  it("uses a typographic minus", () => {
    expect(minus("-14% to +97%")).toBe("−14% to +97%");
    expect(minus("-14% to -2%")).toBe("−14% to −2%");
    expect(minus("+6%")).toBe("+6%");
    expect(minus("-")).toBe("-");
  });

  it("formats dates in UTC", () => {
    expect(formatDate("2026-10-26")).toBe("26 Oct 2026");
    expect(formatTime("2026-10-26T08:00:55Z")).toBe("26 Oct, 08:00 UTC");
    expect(formatDate(null)).toBe("-");
  });

  it("picks round load ticks", () => {
    expect(kneeScale(3162)).toEqual({ max: 4000, ticks: [0, 1000, 2000, 3000, 4000] });
    expect(kneeScale(819)).toEqual({ max: 1000, ticks: [0, 250, 500, 750, 1000] });
    expect(kneeScale(1006)).toEqual({ max: 1500, ticks: [0, 500, 1000, 1500] });
  });
});

describe("where the runs were measured", () => {
  const on = (cpu: string, os: string) =>
    ({
      ...accuracy,
      summary: { ...accuracy.summary, runs: 3, machines: [cpu] },
      experiments: accuracy.experiments.map((x) => ({ ...x, environment: { ...x.environment, cpu, os } })),
    }) as typeof accuracy;

  it("says plainly that every run so far was on one laptop, under Docker Desktop", () => {
    expect(measuredOn(on("11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz", "Windows-11-10.0.26300-SP0"))).toEqual({
      short: "one laptop under Docker Desktop",
      sentence: "All 3 runs were measured on one laptop (11th Gen Intel Core i7-1165G7), every stack under Docker Desktop on Windows 11: not on a server, and not yet on a second machine.",
    });
  });

  it("calls a machine it does not know a machine, and Docker on Linux plain Docker", () => {
    expect(measuredOn(on("AMD EPYC 7B13", "Linux-6.8.0-x86_64-with-glibc2.39"))).toEqual({
      short: "one machine under Docker",
      sentence: "All 3 runs were measured on one machine (AMD EPYC 7B13), every stack under Docker on Linux: not yet on a second machine.",
    });
  });

  it("names how many machines there were once there are more", () => {
    const two = { ...accuracy, summary: { ...accuracy.summary, runs: 9, machines: ["AMD EPYC 7B13", "Apple M3"] } };

    expect(measuredOn(two)).toEqual({ short: "2 machines", sentence: "The 9 runs were measured on 2 machines: AMD EPYC 7B13 and Apple M3." });
  });
});

describe("against plain arithmetic", () => {
  // a comparison over 15 held-out runs of 5 experiments, its figures written here
  const errors = (median: string, worst: number, worstText: string): KneeErrors => ({
    runs: 15,
    medianAbsError: parseFloat(median) / 100,
    worstError: worst,
    worst: { experiment: "go-pool", dir: "run-2/" },
    text: { medianAbsError: median, worstError: worstText },
  });
  type Split = { verdict: EstimateComparison["verdict"]; simulatorNearer: number; estimateNearer: number; experiments?: number };
  const against = ({ verdict, simulatorNearer, estimateNearer, experiments = 5 }: Split, worst = 0.787, worstText = "+79%"): AgainstEstimates => ({
    runs: 15,
    experiments,
    simulator: errors("16.5%", -0.274, "-27%"),
    estimates: [
      { ...errors("16.2%", worst, worstText), estimate: "bottleneckArithmetic", name: "Bottleneck arithmetic", simulatorNearer, estimateNearer, verdict },
      { ...errors("27.5%", 3.16, "+316%"), estimate: "analyticEstimate", name: "Engine's analytic estimate", simulatorNearer: 4, estimateNearer: 1, verdict: "level" },
    ],
  });

  it("gives the README's figures, from the data", () => {
    // vitest runs in apps/web: the README is two levels up, its lines wrapped
    const readme = readFileSync(resolve(process.cwd(), "../..", "README.md"), "utf8").replace(/\s+/g, " ");
    const data = accuracy.summary.againstEstimatesHeldOut;
    const arithmetic = arithmeticOf(data);
    if (!data || !arithmetic) throw new Error("no held-out comparison in accuracy.json");
    const medians = `median error ${data.simulator.text.medianAbsError} against ${arithmetic.text.medianAbsError}`;
    const worsts = `${minus(data.simulator.text.worstError)} against ${minus(arithmetic.text.worstError)}`;

    expect(readme).toContain(medians);
    expect(readme).toContain(worsts);
    expect(arithmeticSentence(data)).toContain(medians);
    expect(arithmeticSentence(data)).toContain(worsts);
  });

  it("calls a tie level, and the worst case ArchSim's advantage when it is", () => {
    const tie = against({ verdict: "level", simulatorNearer: 3, estimateNearer: 2 });

    expect(arithmeticSentence(tie)).toBe(
      "On a typical held-out run ArchSim is level with plain bottleneck arithmetic on the same calibration (median error 16.5% against 16.2%, over 15 runs). " +
        "Its advantage is the worst case: −27% against +79%.",
    );
    expect(splitSentence(tie)).toBe(
      "By each experiment's median error, ArchSim's knee was the nearer to the measured one in 3 of the 5 held-out experiments, the arithmetic's in 2: " +
        "a sign test cannot tell the two apart, so for the knee the simulation adds nothing yet on a typical run.",
    );
  });

  it("says when the experiments put ArchSim nearer, and whether its worst run is too", () => {
    const nearer = against({ verdict: "simulator nearer", simulatorNearer: 6, estimateNearer: 0, experiments: 6 });

    expect(arithmeticSentence(nearer)).toBe(
      "On a typical held-out run ArchSim is nearer the measured knee than plain bottleneck arithmetic on the same calibration (median error 16.5% against 16.2%, over 15 runs). " +
        "Its worst run is nearer too: −27% against +79%.",
    );
    expect(splitSentence(nearer)).toBe(
      "By each experiment's median error, ArchSim's knee was the nearer to the measured one in 6 of the 6 held-out experiments, the arithmetic's in 0: " +
        "more uneven a split than chance would make, by a sign test.",
    );
  });

  it("says when the experiments put the arithmetic nearer, and ArchSim's worst run no nearer", () => {
    const further = against({ verdict: "estimate nearer", simulatorNearer: 0, estimateNearer: 6, experiments: 6 }, -0.2, "-20%");

    expect(arithmeticSentence(further)).toBe(
      "On a typical held-out run ArchSim is further from the measured knee than plain bottleneck arithmetic on the same calibration (median error 16.5% against 16.2%, over 15 runs). " +
        "Its worst run is no nearer: −27% against −20%.",
    );
  });

  it("leads the page with the comparison in a few words, the verdict choosing them", () => {
    const tie = against({ verdict: "level", simulatorNearer: 3, estimateNearer: 2 });
    const nearer = against({ verdict: "simulator nearer", simulatorNearer: 6, estimateNearer: 0, experiments: 6 });
    const further = against({ verdict: "estimate nearer", simulatorNearer: 0, estimateNearer: 6, experiments: 6 }, -0.2, "-20%");

    expect(leadSentence(tie)).toBe(
      "On a typical held-out run its predicted knee is about as close as plain bottleneck arithmetic on the same calibration; its edge so far is a smaller worst miss.",
    );
    expect(leadSentence(nearer)).toBe(
      "On a typical held-out run its predicted knee is nearer the measured one than plain bottleneck arithmetic on the same calibration, and its worst miss is smaller too.",
    );
    expect(leadSentence(further)).toBe(
      "On a typical held-out run its predicted knee is further from the measured one than plain bottleneck arithmetic on the same calibration, and its worst miss is no smaller.",
    );
  });

  it("says nothing before a held-out run has the arithmetic's knee", () => {
    const none = against({ verdict: "level", simulatorNearer: 0, estimateNearer: 0 });
    none.estimates[0] = { ...none.estimates[0], runs: 0 };

    expect(arithmeticOf(none)).toBeNull();
    expect(arithmeticSentence(none)).toBeNull();
    expect(splitSentence(none)).toBeNull();
    expect(leadSentence(none)).toBeNull();
    expect(arithmeticSentence(null)).toBeNull();
  });
});
