/**
 * Money formatting and the cost-vs-latency frontier. Prices and cost estimates are computed by
 * the engine (archsim_engine/cost.py): reports carry `cost`, and POST /analyze prices a design
 * before it runs. The two helpers below mirror the engine's rules so the inspector can word a
 * price before the next analysis arrives.
 */
import type { PricingSource } from "@/lib/api";
import type { ComponentType, InputProvenance, NodeConfig, Pricing } from "@/lib/types";

/**
 * Where a part's `costPerHour` came from (engine: `cost.price_origin`): "set" by the user, "preset"
 * (the illustrative price of a preset of the part's technology: a price snapshot reprices it),
 * "default" (the catalog default), or null (none). Provenance measured or assumed means the user's.
 */
export function priceOrigin(comp: ComponentType, config: NodeConfig, provenance?: InputProvenance["kind"] | null): "set" | "preset" | "default" | null {
  const own = config.costPerHour;
  if (typeof own !== "number" || !Number.isFinite(own)) return null;
  if (provenance === "measured" || provenance === "assumed") return "set";
  if (own === comp.defaultConfig.costPerHour) return "default";
  const tech = config.technology ?? comp.defaultConfig.technology;
  const presetPrice = (comp.presets ?? []).some((p) => p.technology === tech && p.config.costPerHour === own);
  return provenance === "illustrative" || presetPrice ? "preset" : "set";
}

/**
 * The snapshot the engine prices a design with (engine: `pricing.snapshot_for`): the pinned one
 * (`snapshotDate`, `snapshotSha256`), else the newest of the provider (in its region when set).
 * null for generic rates, or when this machine has no matching snapshot.
 */
export function snapshotInUse(sources: PricingSource[], pricing: Pricing | null | undefined): PricingSource | null {
  if (!pricing || pricing.provider === "generic") return null;
  let best: PricingSource | null = null;
  for (const s of sources) {
    if (s.illustrative || s.provider !== pricing.provider) continue;
    if (pricing.region && s.region !== pricing.region) continue;
    if (pricing.snapshotDate && s.date !== pricing.snapshotDate) continue;
    if (pricing.snapshotSha256 && s.sha256 !== pricing.snapshotSha256) continue;
    if (!best || (s.date ?? "") > (best.date ?? "")) best = s;
  }
  return best;
}

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
