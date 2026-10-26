import { describe, expect, it } from "vitest";
import { formatUsd, paretoFrontier } from "./cost";

describe("formatUsd", () => {
  it("shows cents below $100 and whole dollars above", () => {
    expect(formatUsd(0.425)).toBe("$0.43");
    expect(formatUsd(1234.5)).toBe("$1,235");
    expect(formatUsd(123456, { compact: true })).toBe("$123.5K");
    expect(formatUsd(null)).toBe("–");
  });
});

describe("paretoFrontier", () => {
  it("keeps only options that no cheaper option beats on latency", () => {
    const pts = [
      { id: "a", cost: 100, p95: 300 },
      { id: "b", cost: 200, p95: 120 },
      { id: "c", cost: 250, p95: 150 }, // dominated by b
      { id: "d", cost: 400, p95: 60 },
      { id: "e", cost: 100, p95: 320 }, // same cost as a, slower
    ];
    expect(paretoFrontier(pts).map((p) => p.id)).toEqual(["a", "b", "d"]);
    expect(paretoFrontier([])).toEqual([]);
  });
});
