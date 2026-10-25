import { describe, expect, it } from "vitest";
import { fixtureReport } from "@/test/fixtures/dashboard-report";
import {
  clampRange,
  fmtAxisTime,
  fmtRange,
  fullRange,
  lastDrawable,
  legendStats,
  maxSeries,
  measuredRange,
  niceScale,
  sumSeries,
  timeTicks,
  viewStats,
  visibleBuckets,
  zoomOutRange,
} from "./range";

describe("time ranges", () => {
  const report = fixtureReport();

  it("spans the recording and the measurement window", () => {
    expect(fullRange(report)).toEqual({ from: 0, to: 40 });
    expect(measuredRange(report)).toEqual({ from: 4, to: 36 });
  });

  it("zooms out around the centre and stops at the whole run", () => {
    const full = { from: 0, to: 40 };
    expect(zoomOutRange({ from: 18, to: 22 }, full)).toEqual({ from: 16, to: 24 });
    // Pushed back inside the run at the edges.
    expect(zoomOutRange({ from: 0, to: 6 }, full)).toEqual({ from: 0, to: 12 });
    expect(zoomOutRange({ from: 34, to: 40 }, full)).toEqual({ from: 28, to: 40 });
    expect(zoomOutRange({ from: 5, to: 30 }, full)).toBeNull();
  });

  it("clamps a drag selection to the run and a minimum width", () => {
    const full = { from: 0, to: 40 };
    expect(clampRange({ from: 30, to: 50 }, full)).toEqual({ from: 30, to: 40 });
    expect(clampRange({ from: 12, to: 10 }, full)).toEqual({ from: 10, to: 12 });
    expect(clampRange({ from: 10, to: 10.5 }, full, 2)).toEqual({ from: 9.25, to: 11.25 });
    expect(clampRange({ from: 39.5, to: 40 }, full, 2)).toEqual({ from: 38, to: 40 });
  });

  it("slices buckets by their centre, the range and the playhead", () => {
    // 20 buckets of 2 s: centres at 1, 3, 5, ...
    expect(visibleBuckets(20, 2, { from: 0, to: 40 })).toEqual({ i0: 0, i1: 19 });
    expect(visibleBuckets(20, 2, { from: 4, to: 10 })).toEqual({ i0: 2, i1: 4 });
    expect(visibleBuckets(20, 2, { from: 3, to: 3.5 })).toEqual({ i0: 1, i1: 1 });
    expect(visibleBuckets(20, 2, { from: 3.2, to: 4.8 })).toBeNull();
    expect(visibleBuckets(20, 2, { from: 0, to: 40 }, 7.5)).toEqual({ i0: 0, i1: 3 });
    expect(visibleBuckets(20, 2, { from: 0, to: 40 }, 0.5)).toBeNull();
    expect(visibleBuckets(0, 2, { from: 0, to: 40 })).toBeNull();
    expect(lastDrawable(20, 2, null)).toBe(19);
    expect(lastDrawable(20, 2, 7.5)).toBe(3);
  });
});

describe("legend statistics", () => {
  it("computes mean, max and last over the window, skipping gaps", () => {
    const s = [1, null, 3, 8, null, 2];
    expect(legendStats(s, 0, 5)).toEqual({ mean: 3.5, max: 8, last: 2 });
    expect(legendStats(s, 1, 3)).toEqual({ mean: 5.5, max: 8, last: 8 });
    expect(legendStats(s, 4, 4)).toEqual({ mean: null, max: null, last: null });
    expect(legendStats(undefined, 0, 3)).toEqual({ mean: null, max: null, last: null });
  });

  it("interpolates the last value at the playhead while replaying", () => {
    const s = [0, 10, 20, 30];
    // Range 0..8 with 2 s buckets; playhead at 4 s sits between centres 3 s (10) and 5 s (20).
    const st = viewStats(s, 4, 2, { from: 0, to: 8 }, 4);
    expect(st.max).toBe(10);
    expect(st.last).toBeCloseTo(15);
    expect(viewStats(s, 4, 2, { from: 0, to: 8 }, null)).toEqual({ mean: 15, max: 30, last: 30 });
  });

  it("sums and maxes series bucket by bucket", () => {
    expect(sumSeries([[1, null, 2], [3, null, null]], 3)).toEqual([4, null, 2]);
    expect(maxSeries([[1, null, 2], [3, null, null]], 3)).toEqual([3, null, 2]);
  });
});

describe("axes", () => {
  it("picks nice maxima and ticks", () => {
    expect(niceScale(87, 4)).toEqual({ max: 100, ticks: [0, 25, 50, 75, 100] });
    expect(niceScale(0.93, 4).max).toBe(1);
    expect(niceScale(0, 4).max).toBe(1);
    expect(niceScale(1234, 4).max).toBe(1500);
  });

  it("spaces time ticks by pixels", () => {
    expect(timeTicks({ from: 0, to: 120 }, 600, 64)).toEqual([0, 15, 30, 45, 60, 75, 90, 105, 120]);
    expect(timeTicks({ from: 13, to: 31 }, 600, 64)).toEqual([14, 16, 18, 20, 22, 24, 26, 28, 30]);
    expect(fmtAxisTime(45)).toBe("45 s");
    expect(fmtAxisTime(125)).toBe("2:05");
    expect(fmtRange({ from: 10, to: 120 })).toBe("10–2:00");
    expect(fmtRange({ from: 12.25, to: 48 })).toBe("12.3–48 s");
  });
});
