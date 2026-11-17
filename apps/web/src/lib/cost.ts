/**
 * Money formatting and the cost-vs-latency frontier. Prices and cost estimates are computed by
 * the engine (`CostEstimator`, in archsim-engine's `io.archsim.engine.cost`): reports carry
 * `cost`, and POST /analyze prices a design before it runs. The two helpers below mirror the
 * engine's rules so the inspector can word a price before the next analysis arrives.
 */
import type { PricingSource } from "@/lib/api";
import type { ComponentType, CostEstimate, InputProvenance, NodeConfig, NodeCost, Pricing } from "@/lib/types";

const PROVIDERS: Record<string, string> = { aws: "AWS", azure: "Azure", gcp: "Google Cloud" };
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "Oct 2026" from "2026-10-27" (no time zone involved); the input as it is when it is not a date. */
function monthOf(date: string): string {
  const m = /^(\d{4})-(\d{2})/.exec(date);
  return m && MONTHS[Number(m[2]) - 1] ? `${MONTHS[Number(m[2]) - 1]} ${m[1]}` : date;
}

export interface PriceSource {
  /** Short, for next to a dollar figure: "AWS us-east-1 · Oct 2026" or "illustrative rates". */
  label: string;
  /** One sentence for a tooltip or a report. */
  detail: string;
  illustrative: boolean;
}

/**
 * Where a cost estimate's prices come from (engine: `cost.rates.kind` and `cost.pricing`), so no
 * dollar figure is shown without its source. A snapshot estimate with parts the snapshot could not
 * price says "+ illustrative"; one priced only with the user's own prices says so.
 */
export function priceSource(cost: Pick<CostEstimate, "rates" | "pricing" | "byNode"> | null | undefined): PriceSource {
  const priced = (cost?.byNode ?? []).filter((c) => c.unitPrice != null && c.priceSource != null);
  const guessed = priced.filter((c) => c.priceSource === "size" || c.priceSource === "preset" || c.priceSource === "default").length;
  const p = cost?.pricing;
  const snapshot = (cost?.rates?.kind ?? (p && !p.illustrative ? "snapshot" : "illustrative")) === "snapshot" && !!p;
  if (snapshot && p) {
    const provider = PROVIDERS[p.provider] ?? p.provider;
    const where = [provider, p.region].filter(Boolean).join(" ");
    const when = p.snapshotDate ? monthOf(p.snapshotDate) : "";
    const label = [where, when].filter(Boolean).join(" · ") + (guessed ? " + illustrative" : "");
    const detail =
      `On-demand list prices of ${where}${p.snapshotDate ? ` on ${p.snapshotDate}` : ""}` +
      (p.sources?.length ? ` (${p.sources.map((s) => s.name).join(", ")})` : "") +
      (guessed ? `; ${guessed} ${guessed === 1 ? "part is" : "parts are"} priced at illustrative rates` : "") +
      ". No discounts or data transfer.";
    return { label, detail, illustrative: false };
  }
  if (priced.length > 0 && priced.every((c) => c.priceSource === "set")) {
    return { label: "prices set on the parts", detail: "Prices set on the parts in the design.", illustrative: false };
  }
  return {
    label: "illustrative rates",
    detail: "ArchSim's illustrative on-demand rates, not a cloud's price list.",
    illustrative: true,
  };
}

/**
 * An hour at the workload's peak beside an hour at its base load, which the month is billed at
 * (engine 1.1+, ADR-0074): null when the report has no hourly figures or the peak costs what the
 * base does.
 */
export function peakHour(cost: Pick<CostEstimate, "hourly"> | null | undefined): { base: number; peak: number } | null {
  const hourly = cost?.hourly;
  return hourly && hourly.peak !== hourly.base ? { base: hourly.base, peak: hourly.peak } : null;
}

/** The hours a month the engine bills (engine 1.1+, report 1.16). */
const HOURS_PER_MONTH = 730;

/**
 * Which month a cost bills: its peak hours at the peak and the rest at the base load, or all
 * of it at the base load (schema 1.8's `peakHoursPerMonth`, ADR-0074).
 */
export function monthText(cost: Pick<CostEstimate, "peakHours"> | null | undefined): string {
  const hours = cost?.peakHours ?? 0;
  if (hours <= 0) {
    return "The month is billed at the steady base load: set Peak hours a month in Design → Traffic to bill the peak.";
  }
  const rest = Math.round((HOURS_PER_MONTH - hours) * 10) / 10;
  return `The month bills ${hours} ${hours === 1 ? "hour" : "hours"} at the peak and the other ${rest} at the base load.`;
}

/** How many instances a part that scales runs at the peak, "12 at the peak"; null when it runs as many as at the base. */
export function peakCount(line: Pick<NodeCost, "instances" | "peakInstances">): string | null {
  const peak = line.peakInstances;
  return peak != null && peak !== line.instances ? `${Math.round(peak * 10) / 10} at the peak` : null;
}

/**
 * What the month leaves out for want of a price (engine 1.2+, ADR-0081): "leaves out E-mail API (no price)", or
 * the count and names of several; null when it prices every part, or the report predates the list.
 */
export function costLeftOut(cost: Pick<CostEstimate, "unpriced" | "byNode"> | null | undefined): string | null {
  const ids = cost?.unpriced ?? [];
  if (!ids.length || !cost) return null;
  const names = ids.map((id) => cost.byNode.find((c) => c.id === id)?.label ?? id);
  return names.length === 1 ? `leaves out ${names[0]} (no price)` : `leaves out ${names.length} parts with no price: ${names.join(", ")}`;
}

/**
 * The label beside a part's line that says where its price came from, and what it means (ADR-0081); null for a
 * snapshot's price, which its instance type names, and a part priced from its size or the catalog default keep
 * their own labels.
 */
export function linePriceLabel(line: NodeCost): { text: string; title: string } | null {
  if (line.unitPrice == null && line.type === "external_api") {
    return { text: "not priced", title: "No price per million calls set, and no preset price for its technology: the month leaves it out" };
  }
  if (line.priceSource === "set") return { text: "set on the part", title: "A price set on this part in the design" };
  if (line.priceSource === "preset" && line.unit === "million") {
    return {
      text: "preset price",
      title: `The ${line.technology ?? "technology"} preset's illustrative price per million calls: set your provider's price on the part`,
    };
  }
  if (line.priceSource === "preset") {
    return { text: "preset price", title: "The technology preset's illustrative price: choose a price snapshot to price it as an instance type" };
  }
  if (line.priceSource === "size") return { text: "from size", title: "No price set: priced from its CPU, memory and storage at illustrative on-demand rates" };
  if (line.priceSource === "default") return { text: "default price", title: "The catalog's default price for this kind of part (illustrative)" };
  return null;
}

/**
 * A line's month: "not priced" for a part the month leaves out (ADR-0081), "–" for one priced elsewhere (a pod on its
 * cluster's nodes), else its cost.
 */
export function lineMonthlyText(line: NodeCost, cost: Pick<CostEstimate, "unpriced">): string {
  if (cost.unpriced?.includes(line.id)) return "not priced";
  return line.unitPrice == null && line.monthly === 0 ? "–" : formatUsd(line.monthly);
}

/**
 * Where a part's `costPerHour` came from (engine: its `PriceBasis`): "set" by the user, "preset"
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
 * The snapshot the engine prices a design with (model: `PriceSnapshots.forPricing`): the pinned one
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
