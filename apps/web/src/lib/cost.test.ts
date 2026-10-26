import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { PricingSource } from "@/lib/api";
import type { Catalog } from "@/lib/types";
import { formatUsd, paretoFrontier, priceOrigin, snapshotInUse } from "./cost";

const catalog = catalogJson as unknown as Catalog;

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
