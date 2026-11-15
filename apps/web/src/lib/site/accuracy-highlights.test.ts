import { describe, expect, it } from "vitest";
import { type Accuracy, type Experiment, accuracy } from "@/lib/accuracy";
import { accuracyHighlights } from "./accuracy-highlights";

function experiment(id: string, sample: Experiment["sample"], error: number, withinTolerance: boolean): Experiment {
  const pct = `${error > 0 ? "+" : error < 0 ? "-" : ""}${Math.round(Math.abs(error) * 100)}%`;
  return { id, sample, knee: { error, withinTolerance, text: { error: pct, absError: pct.replace(/^[+-]/, "") } } } as unknown as Experiment;
}

function data(experiments: Experiment[], machines = ["one CPU"]): Accuracy {
  return {
    ...accuracy,
    thresholds: { ...accuracy.thresholds, kneeError: 0.25 },
    summary: { ...accuracy.summary, experiments: experiments.length, runs: 12, machines, validated: [], firstDate: "2026-10-26", latestDate: "2026-11-04" },
    experiments,
  };
}

describe("accuracyHighlights", () => {
  it("leads with the held-out knees and says how many over-promised capacity, and by how much", () => {
    const h = accuracyHighlights(
      data([experiment("a", "held-out", 0.7, false), experiment("b", "held-out", 0.27, false), experiment("c", "held-out", -0.06, true), experiment("d", "tuned-on", -0.16, true)]),
    );

    expect(h.tolerance).toBe("±25%");
    expect(h.heldOut).toEqual({ within: 1, total: 3, over: 2, overRange: "27% to 70%", under: 0 });
    expect(h.tunedOn).toEqual({ within: 1, total: 1, range: "−16%" });
  });

  it("gives the range of the tuned-on errors, signed, and the runs behind every figure", () => {
    const h = accuracyHighlights(data([experiment("a", "tuned-on", -0.16, true), experiment("b", "tuned-on", 0.16, true)], ["one", "two"]));

    expect(h.heldOut).toBeNull();
    expect(h.tunedOn?.range).toBe("−16% to +16%");
    expect(h).toMatchObject({ validated: 0, experiments: 2, runs: 12, machines: 2, period: "26 Oct 2026 to 4 Nov 2026" });
  });

  it("agrees with the accuracy page's data", () => {
    const h = accuracyHighlights(accuracy);
    const held = accuracy.experiments.filter((x) => x.sample === "held-out");

    expect(h.experiments).toBe(accuracy.summary.experiments);
    expect(h.heldOut?.total).toBe(held.length);
    expect(h.heldOut?.within).toBe(held.filter((x) => x.knee.withinTolerance).length);
    expect(h.validated).toBe(accuracy.summary.validated.length);
  });
});
