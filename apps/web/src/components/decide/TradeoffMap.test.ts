import { describe, expect, it } from "vitest";
import { placeLabels } from "./TradeoffMap";

const PLOT = { left: 64, right: 1000 };

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

  it("moves a label that would overlap one above it down a line", () => {
    const labels = placeLabels(
      [
        { id: "low", label: "Bigger database: 4 vCPU / 16 GiB", x: 300, y: 106 },
        { id: "high", label: "Read replica: 2 × 2 vCPU", x: 320, y: 100 },
      ],
      PLOT,
    );

    expect(labels.get("high")?.y).toBe(92);
    expect(labels.get("low")?.y).toBe(110);
  });
});
