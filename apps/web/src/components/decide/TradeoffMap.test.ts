import { describe, expect, it } from "vitest";
import { type PlacedLabel, placeLabels } from "./TradeoffMap";

const PLOT = { left: 64, right: 1000 };

/** What the map says a label covers: its text at 6.4 px a character, 12 px tall. */
function labelBox(l: PlacedLabel) {
  const width = l.text.length * 6.4;
  const x0 = l.anchor === "start" ? l.x : l.x - width;
  return { x0, x1: x0 + width, y0: l.y - 6, y1: l.y + 6 };
}

/** A marker's box: a point drawn 7 px across its centre, and a pixel around it. */
function markerBox(p: { x: number; y: number; r?: number }) {
  const r = p.r ?? 8;
  return { x0: p.x - r, x1: p.x + r, y0: p.y - r, y1: p.y + r };
}

type Box = ReturnType<typeof labelBox>;
const touch = (a: Box, b: Box) => a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1;

/**
 * Black Friday on Kubernetes with every scenario simulated and the design run once more, as the
 * README's trade-offs shot draws it on a 1,357-px map: four runs within $8 and 35 ms of each other.
 */
const BLACK_FRIDAY = [
  { id: "fixed3", label: "No autoscaling (3 fixed pods)", x: 351.5, y: 81.2 },
  { id: "payments", label: "Payments provider 5× slower (60–90 s)", x: 372.2, y: 86.6 },
  { id: "cache", label: "Lose the cache for 20 s", x: 372.2, y: 155.9 },
  { id: "gateway", label: "Gateway rate limit 900 req/s", x: 363.0, y: 159.8 },
  { id: "current", label: "Current design", x: 372.2, y: 167.4, r: 12 },
  { id: "baseline", label: "Baseline (HPA 3→14 pods)", x: 372.2, y: 167.4 },
  { id: "fixed14", label: "No autoscaling (14 fixed pods)", x: 1119.0, y: 231.6 },
];
const MAP = { left: 64, right: 1357, top: 20, bottom: 356 };

describe("placeLabels", () => {
  it("sets a label of up to 40 characters whole, right of its point and just above it, clear of the frontier", () => {
    const label = "Bigger database: 4 vCPU / 16 GiB";

    const placed = placeLabels([{ id: "a", label, x: 300, y: 100 }], PLOT).get("a");

    expect(placed).toEqual({ x: 311, y: 92, anchor: "start", text: label });
  });

  it("ends a label longer than 40 characters in an ellipsis, after a whole word", () => {
    const placed = placeLabels([{ id: "a", label: "As drawn: 3 API VMs, database 2 vCPU / 8 GiB", x: 300, y: 100 }], PLOT).get("a");

    expect(placed?.text).toBe("As drawn: 3 API VMs, database 2 vCPU…");
  });

  it("sets a label left of its point when the right side would run past the plot", () => {
    const placed = placeLabels([{ id: "a", label: "Read replica: 2 × 2 vCPU", x: 950, y: 100 }], PLOT).get("a");

    expect(placed).toMatchObject({ x: 939, anchor: "end", text: "Read replica: 2 × 2 vCPU" });
  });

  it("cuts a label that fits neither side to the wider one", () => {
    const narrow = { left: 0, right: 200 };

    const placed = placeLabels([{ id: "a", label: "Payments provider 5× slower (60–90 s)", x: 80, y: 100 }], narrow).get("a");

    expect(placed?.anchor).toBe("start");
    expect(placed!.x + placed!.text.length * 6.4).toBeLessThanOrEqual(200);
    expect(placed?.text.endsWith("…")).toBe(true);
  });

  it("sets a label on its point's other side when the first would cover a point beside it", () => {
    const labels = placeLabels(
      [
        { id: "low", label: "Bigger database: 4 vCPU / 16 GiB", x: 300, y: 106 },
        { id: "high", label: "Read replica: 2 × 2 vCPU", x: 320, y: 100 },
      ],
      PLOT,
    );

    expect(labels.get("high")).toMatchObject({ x: 331, y: 92, anchor: "start" });
    expect(labels.get("low")).toMatchObject({ x: 289, y: 98, anchor: "end" });
  });

  it("moves a label down a line when neither side of its point is free", () => {
    const labels = placeLabels(
      [
        { id: "low", label: "Bigger database: 4 vCPU / 16 GiB", x: 300, y: 106 },
        { id: "right", label: "Read replica: 2 × 2 vCPU", x: 320, y: 96 },
        { id: "left", label: "Two API VMs", x: 280, y: 96 },
      ],
      PLOT,
    );

    expect(labels.get("low")?.y).toBe(110);
  });

  it("keeps every label of a tight cluster off every marker and every other label", () => {
    const labels = placeLabels(BLACK_FRIDAY, MAP);

    const boxes = [...labels.values()].map(labelBox);
    for (const [i, box] of boxes.entries()) {
      for (const point of BLACK_FRIDAY) expect(touch(box, markerBox(point))).toBe(false);
      for (const other of boxes.slice(i + 1)) expect(touch(box, other)).toBe(false);
    }
    expect([...labels.keys()].sort()).toEqual(["cache", "current", "fixed14", "fixed3", "gateway", "payments"]);
  });

  it("names the runs at one point in one label", () => {
    const labels = placeLabels(BLACK_FRIDAY, MAP);

    expect(labels.get("current")?.text).toBe("Current design · Baseline (HPA 3→14 pods)");
    expect(labels.has("baseline")).toBe(false);
  });

  it("sets a label clear of its point's wider marker", () => {
    const placed = placeLabels([{ id: "a", label: "Current design", x: 300, y: 100, r: 12 }], PLOT).get("a");

    expect(placed?.x).toBe(315);
  });

  it("keeps a label off a box it is told to keep clear", () => {
    const objective = { x0: 200, x1: 400, y0: 86, y1: 98 };

    const placed = placeLabels([{ id: "a", label: "Bigger database", x: 300, y: 100 }], PLOT, [objective]).get("a");

    expect(touch(labelBox(placed!), objective)).toBe(false);
  });

  it("keeps a label inside the map's top and bottom", () => {
    const placed = placeLabels([{ id: "a", label: "Bigger database", x: 300, y: 22 }], { ...PLOT, top: 20, bottom: 356 }).get("a");

    expect(labelBox(placed!).y0).toBeGreaterThanOrEqual(20);
  });

  it("leaves a label out rather than set it on a marker or another label", () => {
    // a point boxed in by its neighbours on every side and every line within reach
    const crowd = [-60, -48, -36, -24, -12, 0, 12, 24, 36, 48, 60].flatMap((dy) =>
      [-30, -14, 14, 30].map((dx) => ({ id: `${dx},${dy}`, label: "x", x: 300 + dx, y: 200 + dy })),
    );

    const labels = placeLabels([{ id: "a", label: "Hemmed in", x: 300, y: 200 }], PLOT, crowd.map(markerBox));

    expect(labels.has("a")).toBe(false);
  });
});
