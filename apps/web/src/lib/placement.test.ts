import { describe, expect, it } from "vitest";
import { freeSpot, STEP_X, STEP_Y } from "./placement";

const at = (x: number, y: number) => ({ position: { x, y } });

describe("freeSpot", () => {
  it("keeps the wanted spot when it is free", () => {
    expect(freeSpot([], { x: 10, y: 20 })).toEqual({ x: 10, y: 20 });
    expect(freeSpot([at(0, 0)], { x: STEP_X, y: 0 })).toEqual({ x: STEP_X, y: 0 });
  });

  it("never returns a spot on top of an existing part", () => {
    // The bug: the canvas hint put a new service exactly where the parts panel had put one.
    const placed = [at(0, 0), at(STEP_X, 0)];
    const p = freeSpot(placed, { x: STEP_X, y: 0 });
    expect(p).toEqual({ x: STEP_X, y: STEP_Y });
  });

  it("tries below, then above, then further right", () => {
    const placed = [at(0, 0), at(0, STEP_Y)];
    expect(freeSpot(placed, { x: 0, y: 0 })).toEqual({ x: 0, y: -STEP_Y });
    expect(freeSpot([...placed, at(0, -STEP_Y)], { x: 0, y: 0 })).toEqual({ x: STEP_X, y: 0 });
  });

  it("respects measured sizes", () => {
    const tall = { position: { x: 0, y: 0 }, measured: { width: 212, height: 400 } };
    const p = freeSpot([tall], { x: 0, y: STEP_Y });
    expect(p.y >= 400 || p.x >= 212).toBe(true);
  });

  it("falls back to a new column when the neighbourhood is full", () => {
    const placed = [];
    for (let c = 0; c <= 6; c++) for (let r = -6; r <= 6; r++) placed.push(at(c * STEP_X, r * STEP_Y));
    const p = freeSpot(placed, { x: 0, y: 0 });
    expect(placed.some((n) => n.position.x === p.x && n.position.y === p.y)).toBe(false);
    expect(p.x).toBeGreaterThan(6 * STEP_X);
  });
});
