import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { PricingSource } from "@/lib/api";
import type { Catalog, CostEstimate, NodeCost } from "@/lib/types";
import { formatUsd, paretoFrontier, peakCount, peakHour, priceOrigin, priceSource, snapshotInUse } from "./cost";

const catalog = catalogJson as unknown as Catalog;

describe("formatUsd", () => {
  it("shows cents below $100 and whole dollars above", () => {
    expect(formatUsd(0.425)).toBe("$0.43");
    expect(formatUsd(1234.5)).toBe("$1,235");
    expect(formatUsd(123456, { compact: true })).toBe("$123.5K");
    expect(formatUsd(null)).toBe("–");
  });
});

describe("peakHour", () => {
  it("prices an hour at the peak beside an hour at the base load", () => {
    expect(peakHour({ hourly: { base: 1.13, peak: 1.65 } })).toEqual({ base: 1.13, peak: 1.65 });
  });
  it("has nothing to add when the peak costs what the base does, or a report from before engine 1.1", () => {
    expect(peakHour({ hourly: { base: 1.13, peak: 1.13 } })).toBeNull();
    expect(peakHour({})).toBeNull();
    expect(peakHour(null)).toBeNull();
  });
});

describe("peakCount", () => {
  it("says how many instances a part runs at the peak when it scales", () => {
    expect(peakCount({ instances: 3, peakInstances: 12 })).toBe("12 at the peak");
    expect(peakCount({ instances: 3, peakInstances: 4.5 })).toBe("4.5 at the peak");
  });
  it("says nothing for a part that runs as many at the peak, or a report without the count", () => {
    expect(peakCount({ instances: 3, peakInstances: 3 })).toBeNull();
    expect(peakCount({ instances: 3 })).toBeNull();
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

describe("priceOrigin", () => {
  const db = catalog.components.find((c) => c.type === "database")!;
  it("calls a technology preset's price illustrative, not the user's", () => {
    expect(priceOrigin(db, { technology: "mysql", costPerHour: 0.33 })).toBe("preset");
    expect(priceOrigin(db, { technology: "mysql", costPerHour: 0.42 })).toBe("set");
    // another technology's preset price was typed by someone
    expect(priceOrigin(db, { technology: "postgresql", costPerHour: 0.33 })).toBe("set");
    expect(priceOrigin(db, { technology: "mysql", costPerHour: db.defaultConfig.costPerHour as number })).toBe("default");
    expect(priceOrigin(db, { technology: "mysql" })).toBeNull();
    // provenance says who chose it
    expect(priceOrigin(db, { technology: "mysql", costPerHour: 0.33 }, "assumed")).toBe("set");
  });
});

describe("snapshotInUse", () => {
  const src = (region: string, date: string, newest: boolean): PricingSource => ({ provider: "aws", region, date, illustrative: false, sha256: `${region}${date}`, newest });
  const sources: PricingSource[] = [
    { provider: "generic", region: null, date: null, illustrative: true },
    src("us-east-1", "2026-11-01", true),
    src("us-east-1", "2026-10-01", false),
    src("eu-west-1", "2026-09-01", true),
  ];
  it("matches the provider alone when the design names no region, like the engine", () => {
    expect(snapshotInUse(sources, { provider: "aws", region: null })?.date).toBe("2026-11-01");
    expect(snapshotInUse(sources, { provider: "aws", region: "eu-west-1" })?.date).toBe("2026-09-01");
  });
  it("uses exactly the pinned snapshot, never another", () => {
    expect(snapshotInUse(sources, { provider: "aws", region: "us-east-1", snapshotDate: "2026-10-01" })?.date).toBe("2026-10-01");
    expect(snapshotInUse(sources, { provider: "aws", region: "us-east-1", snapshotDate: "2026-08-01" })).toBeNull();
    expect(snapshotInUse(sources, { provider: "gcp" })).toBeNull();
    expect(snapshotInUse(sources, { provider: "generic" })).toBeNull();
  });
});

describe("priceSource", () => {
  const line = (id: string, priceSource: NodeCost["priceSource"], extra: Partial<NodeCost> = {}): NodeCost => ({
    id,
    label: id,
    type: "service",
    technology: null,
    instances: 1,
    unitPrice: 0.1,
    priceSource,
    unit: "hour",
    monthly: 73,
    basis: "1 replica",
    ...extra,
  });
  const aws: CostEstimate["pricing"] = {
    provider: "aws",
    region: "us-east-1",
    snapshotDate: "2026-10-27",
    illustrative: false,
    sources: [{ name: "AWS Price List bulk API", url: "https://pricing.us-east-1.amazonaws.com/" }],
    purchaseOption: "on_demand",
    families: [],
  };
  const generic: CostEstimate["pricing"] = { ...aws, provider: "generic", region: null, snapshotDate: null, illustrative: true, sources: [] };

  it("names the provider, region and month of a snapshot", () => {
    const s = priceSource({ rates: { kind: "snapshot" }, pricing: aws, byNode: [line("api", "snapshot", { sku: "c6g.xlarge" })] });
    expect(s).toMatchObject({ label: "AWS us-east-1 · Oct 2026", illustrative: false });
    expect(s.detail).toContain("on 2026-10-27 (AWS Price List bulk API)");
  });
  it("says when parts the snapshot cannot price use illustrative rates", () => {
    const s = priceSource({ rates: { kind: "snapshot" }, pricing: aws, byNode: [line("api", "snapshot"), line("gw", "preset")] });
    expect(s.label).toBe("AWS us-east-1 · Oct 2026 + illustrative");
    expect(s.detail).toContain("1 part is priced at illustrative rates");
  });
  it("calls generic rates illustrative, and the user's own prices theirs", () => {
    expect(priceSource({ rates: { kind: "illustrative" }, pricing: generic, byNode: [line("api", "size")] })).toMatchObject({ label: "illustrative rates", illustrative: true });
    expect(priceSource({ rates: { kind: "illustrative" }, pricing: generic, byNode: [line("api", "set")] }).label).toBe("your prices");
    // reports from before price sources were recorded
    expect(priceSource({ byNode: [] }).label).toBe("illustrative rates");
    expect(priceSource(null).label).toBe("illustrative rates");
  });
});
