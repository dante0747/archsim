import { describe, expect, it } from "vitest";
import {
  type Experiment,
  type Repeatability,
  type ResourcePart,
  type Spread,
  accuracy,
  byKneeError,
  cpuSentence,
  formatDate,
  formatTime,
  kneeScale,
  kneeSentence,
  lowLoadSentence,
  minus,
  p95Sentence,
  coverageAsClaimed,
  coverageSentence,
  rangeSource,
  repeatSentence,
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
    // the held-out experiments lead, then the four the model was built with
    expect(accuracy.experiments.map((x) => x.id)).toEqual(["java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl", "pg-mixed", "pg-hot-row", "cache-aside", "kafka"]);
    expect(accuracy.experiments.map((x) => x.sample)).toEqual([...Array(5).fill("held-out"), ...Array(4).fill("tuned-on")]);
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
    withRange: 0,
    inRange: 0,
    nominalCoverage: null,
    text: { medianError: "-10%", errors: "-10%", coverage: "-" },
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
    const r = rep({ runs: 3, disturbed: 1, withRange: 3, inRange: 1, nominalCoverage: 0.5, text: { medianError: "+4%", errors: "+3% to +20%", coverage: "1 of 3" } });
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
      text: { medianError: "-12%", errors: "-20% to -5%", coverage: "2 of 3" },
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
