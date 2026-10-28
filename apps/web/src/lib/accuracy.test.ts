import { describe, expect, it } from "vitest";
import {
  type Experiment,
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
  spreadSentence,
  topStepText,
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
  minRps: 1895,
  maxRps: 3162.3,
  plusMinus: 0.25,
  text: "1,895 to 3,162 req/s",
  plusMinusText: "±25%",
  values: [],
};

describe("accuracy data", () => {
  it("has every experiment with the fields the page reads", () => {
    expect(accuracy.experiments.map((x) => x.id)).toEqual(["pg-mixed", "pg-hot-row", "cache-aside", "kafka"]);
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
      "Latency (p95) within ±100% or 3 ms at 4 of 6 load steps up to the measured knee. Worst: at 2,268 req/s it predicted 1,916 ms where the system measured 10.4 ms.",
    );
    const met = exp({ p95BelowKnee: { ...base.p95BelowKnee, steps: 2, within: 2, pass: true, text: { medianAbsError: "29%", maxAbsError: "55%" } } });
    expect(p95Sentence(met, accuracy.thresholds)).toBe("Latency (p95) within ±100% or 3 ms at 2 of 2 load steps up to the measured knee; the typical miss was 29%.");
  });

  it("describes light load", () => {
    const step = (offered: string, diffMs: number, diff: string) => ({ ...base.lowLoad[0], diffMs, text: { ...base.lowLoad[0].text, offered, diff } });
    expect(lowLoadSentence(exp({ lowLoad: [step("252", 8.7, "+8.70 ms"), step("503", 8.72, "+8.72 ms")] }))).toBe(
      "At light load (252 and 503 req/s), predicted p95 was 8.70 to 8.72 ms above the measured one.",
    );
    expect(lowLoadSentence(exp({ lowLoad: [step("100", -0.5, "-0.50 ms"), step("200", 1.2, "+1.20 ms")] }))).toBe(
      "At light load (100 and 200 req/s), predicted p95 was −0.50 to +1.20 ms off from the measured one.",
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
      "ArchSim's CPU predictions (busy cores) were off by a median of 8% (PostgreSQL in “Mixed”) to 88% (Redis in “Cache”) at the load steps up to the knee.",
    );
    expect(cpuSentence([exp({ resources: null })])).toBeNull();
    expect(topStepText({ ...real, topRps: real.steps[0].offeredRps })).toBe(real.steps[0].text.offered);
    expect(topStepText({ ...real, topRps: null })).toBeNull();
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
