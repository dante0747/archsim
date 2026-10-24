import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { defaultEdgeConfig, fromDocument, getPath, setPath, slugify, toDocument, uniqueId } from "./document";
import { convertDistribution, describeDistribution, isDistribution, meanOf, validateDistribution } from "./distributions";
import type { ArchitectureDocument, Catalog } from "./types";

const starter = starterJson as unknown as ArchitectureDocument;
const catalog = catalogJson as unknown as Catalog;

describe("document conversion", () => {
  it("round-trips topology, workload and scenarios unchanged", () => {
    const g = fromDocument(starter);
    const doc = toDocument(g);
    expect(doc.architecture).toEqual({
      nodes: starter.architecture.nodes.map((n) => ({ ...n, description: n.description ?? null })),
      edges: starter.architecture.edges.map((e) => ({ ...e, label: e.label ?? null })),
    });
    expect(doc.scenarios).toEqual(starter.scenarios);
    expect(doc.workload).toMatchObject(starter.workload);
    expect(doc.layout.nodes.api).toEqual(starter.layout.nodes.api);
  });

  it("keeps layout separate from topology", () => {
    const g = fromDocument(starter);
    const moved = { ...g, nodes: g.nodes.map((n) => ({ ...n, position: { x: n.position.x + 100, y: 0 } })) };
    expect(toDocument(moved).architecture).toEqual(toDocument(g).architecture);
    expect(toDocument(moved).layout.nodes.api.x).toBe(starter.layout.nodes.api.x + 100);
  });

  it("generates valid unique ids", () => {
    expect(slugify("API Servers!")).toBe("api-servers");
    expect(slugify("---")).toBe("node");
    expect(uniqueId("api", ["api", "api-2"])).toBe("api-3");
    expect(/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(uniqueId("Über Service ✨", []))).toBe(true);
  });

  it("gets and sets nested paths immutably", () => {
    const obj = { a: { b: 1 }, c: 2 };
    const next = setPath(obj, "a.b", 5);
    expect(next).toEqual({ a: { b: 5 }, c: 2 });
    expect(obj.a.b).toBe(1);
    expect(getPath(next, "a.b")).toBe(5);
    expect(getPath(next, "x.y")).toBeUndefined();
  });

  it("builds edge defaults from the catalog", () => {
    const cfg = defaultEdgeConfig(catalog, ["read"]);
    expect(cfg.requestTypes).toEqual(["read"]);
    expect(cfg.latencyMs).toEqual({ kind: "constant", value: 0.5 });
    expect(cfg.maxAttempts).toBe(1);
  });
});

describe("distributions", () => {
  it("computes means and descriptions", () => {
    expect(meanOf({ kind: "constant", value: 3 })).toBe(3);
    expect(meanOf({ kind: "uniform", min: 2, max: 4 })).toBe(3);
    expect(meanOf({ kind: "lognormal", median: 8, p95: 25 })).toBeCloseTo(10.17, 1);
    expect(describeDistribution({ kind: "lognormal", median: 8, p95: 25 })).toBe("p50 8 / p95 25 ms");
  });

  it("converts kinds keeping the mean roughly constant", () => {
    const d = convertDistribution({ kind: "exponential", mean: 10 }, "uniform");
    expect(d).toEqual({ kind: "uniform", min: 5, max: 15 });
    expect(meanOf(convertDistribution(d, "constant"))).toBe(10);
  });

  it("validates parameters", () => {
    expect(validateDistribution({ kind: "lognormal", median: 10, p95: 5 })).toMatch(/p95/);
    expect(validateDistribution({ kind: "uniform", min: 5, max: 1 })).toMatch(/max/);
    expect(validateDistribution({ kind: "exponential", mean: 1 })).toBeNull();
  });

  it("does not mistake a traffic pattern for a distribution", () => {
    expect(isDistribution({ kind: "constant" })).toBe(false);
    expect(isDistribution({ kind: "constant", value: 1 })).toBe(true);
    expect(isDistribution({ kind: "burst", burstRps: 1, startS: 1, durationS: 1 })).toBe(false);
  });
});
