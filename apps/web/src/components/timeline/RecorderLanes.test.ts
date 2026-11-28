import { describe, expect, it } from "vitest";
import { placeSloLabel } from "./RecorderLanes";

/** The latency strip as the README's hero shot draws it: 974 px wide and 44 px tall, its SLO line at about 26.5 px. */
const STRIP = { width: 974, height: 44 };
const LINE = 26.5;
const TEXT = "SLO 500 ms";

/** A p95 curve across the strip, a point every 16 px as a 120 s run's 2 s buckets draw it, at the heights `at` gives (null: no data). */
function curve(at: (x: number) => number | null): ([number, number] | null)[] {
  return Array.from({ length: 61 }, (_, i) => {
    const x = 8 + i * 16;
    const y = at(x);
    return y == null ? null : [x, y];
  });
}

describe("placeSloLabel", () => {
  it("sets the label above its line at the right end when the curve leaves that clear", () => {
    const placed = placeSloLabel(TEXT, LINE, curve(() => LINE + 5), STRIP);

    expect(placed).toEqual({ x: 970, y: 23.5, anchor: "end", halo: false });
  });

  it("moves the label to its line's left end when the curve climbs through the right end, as Black Friday's p95 does as its run drains", () => {
    const spike = curve((x) => (x < 960 ? LINE + 3.4 : 7.7));

    const placed = placeSloLabel(TEXT, LINE, spike, STRIP);

    expect(placed).toEqual({ x: 4, y: 23.5, anchor: "start", halo: false });
  });

  it("sets the label below its line when the curve runs above the line at both ends", () => {
    const placed = placeSloLabel(TEXT, LINE, curve(() => 18), STRIP);

    expect(placed).toEqual({ x: 970, y: 36.5, anchor: "end", halo: false });
  });

  it("sets the label below its line only where the strip has room for it", () => {
    const low = 36;

    const placed = placeSloLabel(TEXT, low, curve(() => 30), STRIP);

    expect(placed).toEqual({ x: 970, y: 33, anchor: "end", halo: true });
  });

  it("keeps the label above its line at the right end, with a halo the curve passes behind, when the curve crosses every place", () => {
    const zigzag = curve((x) => (((x - 8) / 16) % 2 === 0 ? 16 : 36));

    const placed = placeSloLabel(TEXT, LINE, zigzag, STRIP);

    expect(placed).toEqual({ x: 970, y: 23.5, anchor: "end", halo: true });
  });

  it("reads a point between two gaps in the data as no line at all", () => {
    const lone = curve((x) => (x < 900 ? LINE + 5 : x === 952 ? 18 : null));

    const placed = placeSloLabel(TEXT, LINE, lone, STRIP);

    expect(placed).toEqual({ x: 970, y: 23.5, anchor: "end", halo: false });
  });
});
