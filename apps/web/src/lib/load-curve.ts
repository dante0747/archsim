/** A service's service time by load (schema 1.6): at `rps` requests per second one replica
 * receives, its service time is the configured one times `factor`. The engine interpolates in
 * log(req/s) between points and keeps the nearest point's factor outside them. */
export interface LoadPoint {
  rps: number;
  factor: number;
}

/** The points of a stored value, sorted by rate (anything else reads as no points). */
export function loadPoints(value: unknown): LoadPoint[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((p): p is LoadPoint => !!p && typeof p === "object" && Number.isFinite(p.rps) && Number.isFinite(p.factor))
    .map((p) => ({ rps: p.rps, factor: p.factor }))
    .sort((a, b) => a.rps - b.rps);
}

/** "50: 3.6, 1674: 1": the text the editor shows. */
export function formatLoadCurve(points: LoadPoint[]): string {
  return points.map((p) => `${p.rps}: ${p.factor}`).join(", ");
}

/** The points typed as "req/s: factor" pairs separated by commas or new lines; null when a pair
 * does not read, a rate or factor is not positive (factor at most 100), or a rate repeats. An
 * empty text is no points. */
export function parseLoadCurve(text: string): LoadPoint[] | null {
  const parts = text
    .split(/[,\n;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  const out: LoadPoint[] = [];
  for (const part of parts) {
    const m = /^([0-9]*\.?[0-9]+(?:e[+-]?\d+)?)\s*(?:req\/s)?\s*[:=x×]\s*([0-9]*\.?[0-9]+(?:e[+-]?\d+)?)$/i.exec(part);
    if (!m) return null;
    const rps = Number(m[1]);
    const factor = Number(m[2]);
    if (!(rps > 0) || !(factor > 0) || factor > 100 || rps > 10_000_000) return null;
    if (out.some((p) => p.rps === rps)) return null;
    out.push({ rps, factor });
  }
  return out.sort((a, b) => a.rps - b.rps);
}

/** "×3.6 at 50 req/s to ×1 at 1,674 req/s" (or "Same at every load"). */
export function describeLoadCurve(points: LoadPoint[]): string {
  if (!points.length) return "Same at every load";
  const at = (p: LoadPoint) => `×${Number(p.factor.toPrecision(3))} at ${Math.round(p.rps).toLocaleString("en-US")} req/s`;
  if (points.length === 1) return `×${Number(points[0].factor.toPrecision(3))} at every load`;
  return `${at(points[0])} to ${at(points[points.length - 1])}`;
}
