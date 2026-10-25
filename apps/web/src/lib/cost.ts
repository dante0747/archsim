/**
 * Cost model: monthly cost of a design from the cost assumptions on each component
 * (`costPerHour` per instance; `costPerMillionRequests` for third-party APIs), and the cost per
 * million successful requests at the throughput a run achieved.
 *
 * These are planning estimates built from your assumptions, not quotes: every price is a config
 * value marked as an assumption in the inspector.
 */
import { isClientType } from "./theme";
import type { Catalog, NodeReport, NodeSpec, Report } from "./types";

export const HOURS_PER_MONTH = 730;

/**
 * Illustrative on-demand prices for sizing hints, roughly a large cloud's general-purpose VMs
 * (2 vCPU / 8 GiB ≈ $0.10 per hour) and block storage. Not a quote: use your provider's prices.
 */
export const PRICE_PER_VCPU_HOUR = 0.037;
export const PRICE_PER_GIB_HOUR = 0.0028;
export const PRICE_PER_STORAGE_GIB_MONTH = 0.08;

/** Hourly price of one instance of this size, or null when CPU and memory are both unset. */
export function sizePricePerHour(cpu: number | null | undefined, memoryGib: number | null | undefined, storageGib?: number | null): number | null {
  if (cpu == null && memoryGib == null) return null;
  const compute = (cpu ?? 0) * PRICE_PER_VCPU_HOUR + (memoryGib ?? 0) * PRICE_PER_GIB_HOUR;
  const disk = ((storageGib ?? 0) * PRICE_PER_STORAGE_GIB_MONTH) / 730;
  return Math.round((compute + disk) * 10000) / 10000;
}
export const SECONDS_PER_MONTH = HOURS_PER_MONTH * 3600;

export interface NodeCost {
  id: string;
  label: string;
  type: string;
  technology: string | null;
  /** Instances billed per hour (0 for per-call pricing). */
  instances: number;
  /** $/instance-hour, or $/million calls when `unit` is "million". null = no price set. */
  unitPrice: number | null;
  unit: "hour" | "million";
  monthly: number;
  /** Plain-language basis, e.g. "2 shards × 3 nodes". */
  basis: string;
}

export interface CostEstimate {
  monthly: number;
  /** $ per one million successful requests at the run's throughput; null without a run. */
  perMillionRequests: number | null;
  byNode: NodeCost[];
  notes: string[];
  /** True when at least one component carries a price. */
  hasCost: boolean;
}

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

/** Config value with a fallback to the catalog default for the component type. */
export function configValue(node: Pick<NodeSpec, "type" | "config">, catalog: Catalog | null, key: string): unknown {
  const v = node.config?.[key];
  if (v !== undefined && v !== null) return v;
  const comp = catalog?.components.find((c) => c.type === node.type);
  return comp?.defaultConfig?.[key];
}

function mean(values: (number | null)[]): number | null {
  const xs = values.filter((v): v is number => v != null && Number.isFinite(v));
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
}

const plural = (n: number, word: string) => `${n % 1 === 0 ? n : n.toFixed(1)} ${word}${n === 1 ? "" : "s"}`;

/** Billable instances of a component and how that count was derived. */
export function instancesOf(
  node: Pick<NodeSpec, "type" | "config">,
  catalog: Catalog | null,
  nodeReport?: Pick<NodeReport, "timeseries"> | null,
): { count: number; basis: string } {
  const get = (k: string) => num(configValue(node, catalog, k));
  if (isClientType(node.type, catalog)) return { count: 0, basis: "not billed" };
  switch (node.type) {
    case "external_api":
      return { count: 0, basis: "per call" };
    case "service": {
      const series = nodeReport?.timeseries?.replicas;
      const avg = series ? mean(series) : null;
      if (avg != null) return { count: avg, basis: `avg ${plural(Math.round(avg * 10) / 10, "active instance")}` };
      const r = Math.max(1, get("replicas") ?? 1);
      return { count: r, basis: plural(r, "replica") };
    }
    case "database": {
      const shards = Math.max(1, get("shards") ?? 1);
      const rf = Math.max(1, get("replicationFactor") ?? 1);
      const perShard = Math.max(rf, 1 + Math.max(0, get("readReplicas") ?? 0));
      const count = shards * perShard;
      return { count, basis: shards > 1 ? `${plural(shards, "shard")} × ${plural(perShard, "node")}` : plural(perShard, "node") };
    }
    case "message_queue": {
      const b = Math.max(1, get("brokers") ?? 1);
      return { count: b, basis: plural(b, "broker") };
    }
    case "cache":
    case "load_balancer": {
      const n = Math.max(1, get("nodes") ?? 1);
      return { count: n, basis: plural(n, "node") };
    }
    default: {
      const n = Math.max(1, get("replicas") ?? get("instances") ?? 1);
      return { count: n, basis: plural(n, "instance") };
    }
  }
}

/**
 * Estimate the monthly cost of `nodes` (the design, or a run's `report.input.architecture.nodes`).
 * With a report, third-party calls are priced at the measured call rate, autoscaled services at
 * their average active instances, and the cost per million successful requests is derived.
 */
export function estimateCost(nodes: NodeSpec[], catalog: Catalog | null, report?: Report | null): CostEstimate {
  const notes: string[] = [];
  const byNode: NodeCost[] = [];
  let anyPrice = false;
  let missingExternalRate = false;
  for (const n of nodes) {
    if (isClientType(n.type, catalog)) continue;
    const nr = report?.nodes.find((x) => x.id === n.id) ?? null;
    const technology = (configValue(n, catalog, "technology") as string | undefined) ?? null;
    const label = n.label || n.id;
    if (n.type === "external_api") {
      const price = num(configValue(n, catalog, "costPerMillionRequests"));
      const rate = nr?.summary.arrivalRps ?? null;
      if (price != null) anyPrice = true;
      if (price != null && rate == null) missingExternalRate = true;
      const monthly = price != null && rate != null ? (rate * SECONDS_PER_MONTH * price) / 1e6 : 0;
      byNode.push({
        id: n.id,
        label,
        type: n.type,
        technology,
        instances: 0,
        unitPrice: price,
        unit: "million",
        monthly,
        basis: rate != null ? `${Math.round(rate).toLocaleString("en-US")} calls/s` : "per call",
      });
      continue;
    }
    const price = num(configValue(n, catalog, "costPerHour"));
    if (price != null) anyPrice = true;
    const { count, basis } = instancesOf(n, catalog, nr);
    byNode.push({
      id: n.id,
      label,
      type: n.type,
      technology,
      instances: count,
      unitPrice: price,
      unit: "hour",
      monthly: price != null ? price * count * HOURS_PER_MONTH : 0,
      basis,
    });
  }
  byNode.sort((a, b) => b.monthly - a.monthly);
  const monthly = byNode.reduce((s, x) => s + x.monthly, 0);
  const thr = report?.summary.throughputRps ?? null;
  const perMillionRequests = thr && thr > 0 ? monthly / ((thr * SECONDS_PER_MONTH) / 1e6) : null;
  if (!anyPrice) notes.push("No component has a price yet: set Cost per hour on components to estimate cost.");
  if (missingExternalRate) notes.push("Third-party APIs are priced per call: run a simulation to price them at the measured call rate.");
  if (byNode.some((x) => x.unit === "million" && x.monthly > 0) || perMillionRequests != null) {
    notes.push(`Per-call prices and cost per request assume the run's traffic rate continues for ${HOURS_PER_MONTH} hours a month.`);
  }
  return { monthly, perMillionRequests, byNode, notes, hasCost: anyPrice };
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
