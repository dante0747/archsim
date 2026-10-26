import { describe, expect, it } from "vitest";
import { DEFAULT_WORKLOAD } from "./document";
import { peakRps } from "./load";
import type { Workload } from "./types";

const wl = (patch: Partial<Workload> = {}): Workload => ({ ...DEFAULT_WORKLOAD, rps: 1000, readRatio: 0.8, ...patch });

describe("peakRps", () => {
  it("takes the highest rate of the pattern", () => {
    expect(peakRps(wl())).toBe(1000);
    expect(peakRps(wl({ pattern: { kind: "ramp", startRps: 100, endRps: 1500 } }))).toBe(1500);
    expect(peakRps(wl({ pattern: { kind: "burst", burstRps: 4000, startS: 10, durationS: 5 } }))).toBe(4000);
  });
});
