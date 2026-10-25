import { describe, expect, it } from "vitest";
import { HOURS_PER_MONTH, SECONDS_PER_MONTH, estimateCost, formatUsd, instancesOf, hourlyPrice, paretoFrontier, sizePricePerHour } from "./cost";
import type { Catalog, NodeSpec, Report } from "./types";

const catalog = {
  components: [
    { type: "service", defaultConfig: { replicas: 2, costPerHour: 0.1 } },
    { type: "database", defaultConfig: { shards: 1, readReplicas: 0, replicationFactor: 1, costPerHour: 0.5 } },
    { type: "cache", defaultConfig: { costPerHour: 0.2 } },
    { type: "external_api", defaultConfig: { costPerMillionRequests: 10 } },
    { type: "traffic_source", defaultConfig: {} },
  ],
  edgeFields: [],
  workloadFields: [],
} as unknown as Catalog;

const node = (id: string, type: string, config: Record<string, unknown> = {}): NodeSpec => ({ id, type, label: id, config });

function report(nodes: { id: string; arrivalRps?: number; replicas?: (number | null)[] }[], throughputRps: number): Report {
  return {
    summary: { throughputRps },
    nodes: nodes.map((n) => ({
      id: n.id,
      summary: { arrivalRps: n.arrivalRps ?? null },
      timeseries: n.replicas ? { replicas: n.replicas } : {},
    })),
  } as unknown as Report;
}

describe("instancesOf", () => {
  it("counts database nodes as shards × max(replication factor, primary + replicas)", () => {
    expect(instancesOf(node("db", "database", { shards: 4, readReplicas: 2 }), catalog).count).toBe(12);
    expect(instancesOf(node("db", "database", { shards: 3, replicationFactor: 3 }), catalog)).toEqual({ count: 9, basis: "3 shards × 3 nodes" });
    expect(instancesOf(node("db", "database", {}), catalog).count).toBe(1);
  });

  it("uses replicas, falling back to the catalog default, and averages autoscaled replicas", () => {
    expect(instancesOf(node("api", "service", { replicas: 5 }), catalog).count).toBe(5);
    expect(instancesOf(node("api", "service"), catalog).count).toBe(2);
    expect(instancesOf(node("api", "service", { replicas: 2 }), catalog, { timeseries: { replicas: [2, 4, null, 6] } }).count).toBe(4);
  });

  it("bills nothing for clients and prices external APIs per call", () => {
    expect(instancesOf(node("c", "traffic_source"), catalog).count).toBe(0);
    expect(instancesOf(node("x", "external_api"), catalog).basis).toBe("per call");
  });
});

describe("estimateCost", () => {
  const nodes = [node("clients", "traffic_source"), node("api", "service", { replicas: 3 }), node("db", "database"), node("pay", "external_api")];

  it("sums instance-hours over a 730-hour month without a run", () => {
    const c = estimateCost(nodes, catalog);
    expect(c.monthly).toBeCloseTo((3 * 0.1 + 0.5) * HOURS_PER_MONTH, 6);
    expect(c.perMillionRequests).toBeNull();
    expect(c.byNode.map((n) => n.id)).toEqual(["db", "api", "pay"]); // sorted by monthly cost, clients skipped
    expect(c.notes.some((n) => n.includes("run a simulation"))).toBe(true);
  });

  it("prices third-party calls at the measured rate and derives cost per million requests", () => {
    const r = report([{ id: "pay", arrivalRps: 10 }], 100);
    const c = estimateCost(nodes, catalog, r);
    const external = (10 * SECONDS_PER_MONTH * 10) / 1e6;
    expect(c.byNode.find((n) => n.id === "pay")!.monthly).toBeCloseTo(external, 6);
    const monthly = (3 * 0.1 + 0.5) * HOURS_PER_MONTH + external;
    expect(c.monthly).toBeCloseTo(monthly, 6);
    expect(c.perMillionRequests).toBeCloseTo(monthly / ((100 * SECONDS_PER_MONTH) / 1e6), 9);
  });

  it("reports when no component has a price", () => {
    const bare = { components: [{ type: "service", defaultConfig: {} }], edgeFields: [], workloadFields: [] } as unknown as Catalog;
    const c = estimateCost([node("api", "service")], bare);
    expect(c.hasCost).toBe(false);
    expect(c.monthly).toBe(0);
    expect(c.byNode[0].unitPrice).toBeNull();
  });
});

describe("formatUsd", () => {
  it("shows cents below $100 and whole dollars above", () => {
    expect(formatUsd(0.425)).toBe("$0.43");
    expect(formatUsd(1234.5)).toBe("$1,235");
    expect(formatUsd(123456, { compact: true })).toBe("$123.5K");
    expect(formatUsd(null)).toBe("–");
  });
});

describe("paretoFrontier", () => {
  it("keeps only options that no cheaper option beats on latency", () => {
    const pts = [
      { id: "a", cost: 100, p95: 300 },
      { id: "b", cost: 200, p95: 120 },
      { id: "c", cost: 250, p95: 150 }, // dominated by b
      { id: "d", cost: 400, p95: 60 },
      { id: "e", cost: 100, p95: 320 }, // same cost as a, slower
    ];
    expect(paretoFrontier(pts).map((p) => p.id)).toEqual(["a", "b", "d"]);
    expect(paretoFrontier([])).toEqual([]);
  });
});

describe("sizePricePerHour", () => {
  it("prices a size from vCPU, memory and storage, or nothing when unset", () => {
    expect(sizePricePerHour(2, 8)).toBeCloseTo(0.0964, 4);
    expect(sizePricePerHour(4, 16, 730)).toBeCloseTo(4 * 0.037 + 16 * 0.0028 + 0.08, 4);
    expect(sizePricePerHour(null, null, 100)).toBeNull();
  });
});

describe("hourlyPrice", () => {
  it("uses a set price, else the size, else the catalog default", () => {
    expect(hourlyPrice(node("api", "service", { costPerHour: 0.2, cpuCores: 2, memoryGib: 8 }), catalog)).toEqual({ price: 0.2, source: "set" });
    expect(hourlyPrice(node("api", "service", { cpuCores: 2, memoryGib: 8 }), catalog)).toEqual({ price: 0.0964, source: "size" });
    expect(hourlyPrice(node("api", "service"), catalog)).toEqual({ price: 0.1, source: "default" });
    // saved runs store every default: a price equal to the default counts as not set
    expect(hourlyPrice(node("api", "service", { costPerHour: 0.1, cpuCores: 2, memoryGib: 8 }), catalog)).toEqual({ price: 0.0964, source: "size" });
  });

  it("feeds the estimate and says when sizes were used", () => {
    const c = estimateCost([node("db", "database", { cpuCores: 4, memoryGib: 16, storageGib: 730 })], catalog);
    expect(c.byNode[0].priceSource).toBe("size");
    expect(c.byNode[0].monthly).toBeCloseTo((4 * 0.037 + 16 * 0.0028 + 0.08) * HOURS_PER_MONTH, 4);
    expect(c.notes.some((n) => n.includes("priced from their size"))).toBe(true);
  });
});
