/**
 * Money formatting and the cost-vs-latency frontier. Prices and cost estimates are computed by
 * the engine (archsim_engine/cost.py): reports carry `cost`, and POST /analyze prices a design
 * before it runs.
 */

/** "$1,234" (whole dollars from $100, cents below). */
export function formatUsd(v: number | null | undefined, opts: { compact?: boolean } = {}): string {
  if (v == null || !Number.isFinite(v)) return "–";
  if (opts.compact && Math.abs(v) >= 10_000) {
    return `$${new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 }).format(v)}`;
  }
  const digits = Math.abs(v) >= 100 ? 0 : 2;
  return `$${v.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

/**
 * Options no other option beats on both cost and p95 (lower is better for both), cheapest first.
 * Every option off this frontier costs more for the same or worse latency.
 */
export function paretoFrontier<T extends { cost: number; p95: number }>(points: T[]): T[] {
  const sorted = [...points].sort((a, b) => a.cost - b.cost || a.p95 - b.p95);
  const out: T[] = [];
  let best = Infinity;
  for (const p of sorted) {
    if (p.p95 < best) {
      out.push(p);
      best = p.p95;
    }
  }
  return out;
}
