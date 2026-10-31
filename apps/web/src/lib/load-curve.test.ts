import { describe, expect, it } from "vitest";
import { describeLoadCurve, formatLoadCurve, loadPoints, parseLoadCurve } from "./load-curve";

describe("service time by load", () => {
  it("reads req/s: factor pairs, sorted by rate", () => {
    expect(parseLoadCurve("1674: 1, 50: 3.6\n458 req/s × 1.5")).toEqual([
      { rps: 50, factor: 3.6 },
      { rps: 458, factor: 1.5 },
      { rps: 1674, factor: 1 },
    ]);
    expect(parseLoadCurve("")).toEqual([]);
  });

  it("refuses what the engine would refuse", () => {
    expect(parseLoadCurve("50")).toBeNull();
    expect(parseLoadCurve("0: 1")).toBeNull();
    expect(parseLoadCurve("50: 0")).toBeNull();
    expect(parseLoadCurve("50: 101")).toBeNull();
    expect(parseLoadCurve("50: 2, 50: 1")).toBeNull();
  });

  it("round-trips and reads in words", () => {
    const points = loadPoints([
      { rps: 1674, factor: 1 },
      { rps: 50, factor: 3.6 },
      "junk",
    ]);
    expect(parseLoadCurve(formatLoadCurve(points))).toEqual(points);
    expect(describeLoadCurve(points)).toBe("×3.6 at 50 req/s to ×1 at 1,674 req/s");
    expect(describeLoadCurve([])).toBe("Same at every load");
    expect(loadPoints(null)).toEqual([]);
  });
});
