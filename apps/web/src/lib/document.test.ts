import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { defaultEdgeConfig, edgeSourcePort, fromDocument, getPath, setPath, sideHandle, sideOf, slugify, toDocument, uniqueId, withUnsetApiPrices, withWholeCores } from "./document";
import { convertDistribution, describeDistribution, isDistribution, meanOf, validateDistribution } from "./distributions";
import type { ArchitectureDocument, Catalog } from "./types";

const starter = starterJson as unknown as ArchitectureDocument;
const catalog = catalogJson as unknown as Catalog;

describe("link sides", () => {
  it("defaults to right → left and keeps documents unchanged", () => {
    const g = fromDocument(starter);
    expect(g.edges.every((e) => e.sourceHandle === sideHandle("right") && e.targetHandle === sideHandle("left"))).toBe(true);
    expect(toDocument(g).layout.edges).toBeUndefined();
  });

  it("saves non-default sides in the layout and reads them back, ports untouched", () => {
    const g = fromDocument(starter);
    const first = g.edges[0];
    g.edges[0] = { ...first, sourceHandle: sideHandle("bottom"), targetHandle: sideHandle("top") };
    const doc = toDocument(g);
    expect(doc.layout.edges).toEqual({ [first.id]: { sourceSide: "bottom", targetSide: "top" } });
    expect(doc.architecture.edges[0].sourcePort).toBe(starter.architecture.edges[0].sourcePort);
    const again = fromDocument(doc);
    expect(sideOf(again.edges[0].sourceHandle)).toBe("bottom");
    expect(sideOf(again.edges[0].targetHandle)).toBe("top");
    expect(edgeSourcePort(again.edges[0])).toBe(starter.architecture.edges[0].sourcePort);
  });

  it("tells side handles from port names", () => {
    expect(sideOf("s-left")).toBe("left");
    expect(sideOf("miss")).toBeNull();
    expect(edgeSourcePort({ sourceHandle: "s-top", data: undefined })).toBe("out");
    expect(edgeSourcePort({ sourceHandle: "miss", data: undefined })).toBe("miss");
  });
});

describe("document conversion", () => {
  it("round-trips topology, workload and scenarios unchanged", () => {
    const g = fromDocument(starter);
    const doc = toDocument(g);
    expect(doc.architecture).toEqual({
      nodes: starter.architecture.nodes.map((n) => ({ ...n, description: n.description ?? null })),
      edges: starter.architecture.edges.map((e) => ({ ...e, label: e.label ?? null })),
      // the starter is a 1.3 design: the upgrade to 1.11 keeps its vCPUs whole cores
      siblingSpeed: 1,
    });
    expect(doc.scenarios).toEqual(starter.scenarios);
    expect(doc.workload).toMatchObject(starter.workload);
    expect(doc.layout.nodes.api).toEqual(starter.layout.nodes.api);
  });

  it("toDocument_ofADesignWithNoName_namesItAsTheApiDoes", () => {
    const g = fromDocument(starter);

    // the API reads no design whose name is empty: a cleared name field must not make one
    expect(toDocument({ ...g, metadata: { ...g.metadata, name: "" } }).metadata.name).toBe("Untitled architecture");
    expect(toDocument({ ...g, metadata: { ...g.metadata, name: "   " } }).metadata.name).toBe("Untitled architecture");
  });

  it("toDocument_ofANamedDesign_keepsItsNameWithoutTheSpacesAroundIt", () => {
    const g = fromDocument(starter);

    const doc = toDocument({ ...g, metadata: { ...g.metadata, description: "Kept", name: " Checkout " } });

    expect(doc.metadata).toEqual({ ...g.metadata, description: "Kept", name: "Checkout" });
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

describe("withUnsetApiPrices", () => {
  const design = (schemaVersion: string, price: number) =>
    ({
      schemaVersion,
      kind: "archsim.architecture",
      metadata: { name: "d" },
      architecture: {
        nodes: [
          { id: "mail", type: "external_api", label: "Mail", config: { costPerMillionRequests: price } },
          { id: "api", type: "service", label: "API", config: { costPerMillionRequests: 0 } },
        ],
        edges: [],
      },
      workload: {},
    }) as unknown as ArchitectureDocument;
  it("reads an older design's API price of 0 as unset, as the model's upgrade to 1.9 does", () => {
    const doc = withUnsetApiPrices(design("1.8", 0));
    expect(doc.architecture.nodes[0].config).toEqual({});
    expect(doc.architecture.nodes[1].config).toEqual({ costPerMillionRequests: 0 });
  });
  it("keeps a 0 the design's provenance says a person stated", () => {
    const doc = { ...design("1.8", 0), provenance: { "nodes/mail/costPerMillionRequests": { kind: "assumed" } } } as unknown as ArchitectureDocument;
    expect(withUnsetApiPrices(doc).architecture.nodes[0].config).toEqual({ costPerMillionRequests: 0 });
  });
  it("keeps a price that is not 0, and any price in a design of 1.9 or later", () => {
    expect(withUnsetApiPrices(design("1.8", 100)).architecture.nodes[0].config).toEqual({ costPerMillionRequests: 100 });
    expect(withUnsetApiPrices(design("1.9", 0)).architecture.nodes[0].config).toEqual({ costPerMillionRequests: 0 });
    expect(withUnsetApiPrices(design("1.10", 0)).architecture.nodes[0].config).toEqual({ costPerMillionRequests: 0 });
  });
});

describe("withWholeCores", () => {
  const design = (schemaVersion: string, architecture: Record<string, unknown> = {}) =>
    ({
      schemaVersion,
      kind: "archsim.architecture",
      metadata: { name: "d" },
      architecture: { nodes: [], edges: [], ...architecture },
      workload: {},
    }) as unknown as ArchitectureDocument;

  it("pins an older design's vCPUs to whole cores, as the model's upgrade to 1.11 does", () => {
    expect(withWholeCores(design("1.10")).architecture.siblingSpeed).toBe(1);
    expect(withWholeCores(design("1.7")).architecture.siblingSpeed).toBe(1);
  });

  it("keeps a sibling speed an older design sets", () => {
    expect(withWholeCores(design("1.10", { siblingSpeed: 0.7 })).architecture.siblingSpeed).toBe(0.7);
  });

  it("leaves a 1.11 design on the default", () => {
    expect(withWholeCores(design("1.11")).architecture).not.toHaveProperty("siblingSpeed");
  });

  it("gives a document without an architecture none", () => {
    const doc = { ...design("1.10"), architecture: undefined } as unknown as ArchitectureDocument;

    expect(withWholeCores(doc)).toEqual(doc);
  });

  it("returns a new document and leaves the one it reads as it was", () => {
    const doc = design("1.10");

    withWholeCores(doc);

    expect(doc.architecture).not.toHaveProperty("siblingSpeed");
  });

  it("keeps the design's sibling speed through edits and saves", () => {
    const saved = toDocument(fromDocument(design("1.11", { siblingSpeed: 0.7 })));

    expect(saved.architecture.siblingSpeed).toBe(0.7);
  });

  it("saves an older design pinned to whole cores, at the current schema", () => {
    const saved = toDocument(fromDocument(design("1.10")));

    expect(saved.schemaVersion).toBe("1.11");
    expect(saved.architecture.siblingSpeed).toBe(1);
  });

  it("leaves a cleared sibling speed out of the saved document", () => {
    const g = fromDocument(design("1.10"));

    const saved = toDocument({ ...g, extras: { ...g.extras!, siblingSpeed: null } });

    expect(saved.architecture).not.toHaveProperty("siblingSpeed");
  });
});
