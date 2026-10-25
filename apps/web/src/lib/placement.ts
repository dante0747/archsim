/** Where to put a new part so it never lands on top of an existing one. */

type Pos = { x: number; y: number };
type Placed = { position: Pos; measured?: { width?: number; height?: number } };

/** A card's size before React Flow has measured it (ArchNodeView is 212 px wide). */
const CARD_W = 212;
const CARD_H = 134;
/** Space kept clear around every card. */
const GAP = 24;
/** One search step: a card plus room for a link between neighbours. */
export const STEP_X = 320;
export const STEP_Y = 190;

function overlaps(p: Pos, n: Placed): boolean {
  const w = n.measured?.width ?? CARD_W;
  const h = n.measured?.height ?? CARD_H;
  return (
    p.x < n.position.x + w + GAP &&
    n.position.x < p.x + CARD_W + GAP &&
    p.y < n.position.y + h + GAP &&
    n.position.y < p.y + CARD_H + GAP
  );
}

/** Candidate offsets in steps, nearest first: same column (below before above), then columns
 * further right. Never to the left, so "put it after X" stays after X. */
const OFFSETS: readonly [number, number][] = (() => {
  const out: [number, number][] = [];
  for (let col = 0; col <= 6; col++) for (let row = -6; row <= 6; row++) out.push([col, row]);
  const dist = ([c, r]: [number, number]) => (c * STEP_X) ** 2 + (r * STEP_Y) ** 2;
  return out.sort((a, b) => dist(a) - dist(b) || Number(a[1] < 0) - Number(b[1] < 0) || a[0] - b[0]);
})();

/** `want` if a card there would not overlap any placed card, else the nearest free spot. */
export function freeSpot(placed: readonly Placed[], want: Pos): Pos {
  const free = (p: Pos) => placed.every((n) => !overlaps(p, n));
  for (const [c, r] of OFFSETS) {
    const p = { x: Math.round(want.x + c * STEP_X), y: Math.round(want.y + r * STEP_Y) };
    if (free(p)) return p;
  }
  // Everything near is taken: start a new column right of the rightmost card.
  const right = Math.max(...placed.map((n) => n.position.x + (n.measured?.width ?? CARD_W)));
  return { x: Math.round(right + STEP_X - CARD_W), y: Math.round(want.y) };
}
