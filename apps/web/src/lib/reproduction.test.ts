import { describe, expect, it } from "vitest";
import { reproduction } from "./reproduction";

describe("reproduction", () => {
  it("calls an identical result digest an exact reproduction", () => {
    const outcome = reproduction(
      { engineVersion: "0.16.0", resultDigest: "d3dc10450ee3066a" },
      { engineVersion: "0.16.0", resultDigest: "d3dc10450ee3066a" },
    );

    expect(outcome).toEqual({
      exact: true,
      description: "Same input and seed gave an identical result digest (d3dc10450e).",
    });
  });

  it("names both engine versions when the re-run was simulated by another engine", () => {
    const outcome = reproduction(
      { engineVersion: "0.16.0", resultDigest: "d3dc10450ee3066a" },
      { engineVersion: "1.0.0", resultDigest: "b03ca1028e89b4e1" },
    );

    expect(outcome).toEqual({
      exact: false,
      description: "Results differ from the original run, simulated by engine 0.16.0 (this one: 1.0.0).",
    });
  });

  it("suggests another engine, build or platform when the versions agree", () => {
    const outcome = reproduction(
      { engineVersion: "0.16.0", resultDigest: "d3dc10450ee3066a" },
      { engineVersion: "0.16.0", resultDigest: "b03ca1028e89b4e1" },
    );

    expect(outcome).toEqual({
      exact: false,
      description: "Results differ from the original run (a different engine, engine build or platform?).",
    });
  });

  it("does not call two runs without a digest an exact reproduction", () => {
    const outcome = reproduction(
      { engineVersion: "0.16.0", resultDigest: null },
      { engineVersion: "0.16.0", resultDigest: null },
    );

    expect(outcome.exact).toBe(false);
  });
});
