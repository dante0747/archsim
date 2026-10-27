import { describe, expect, it } from "vitest";
import { libraryItems } from "@/components/build/presets";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { defaultSource, experimentalParts, offeredWithLabs, tierNote, tierOf } from "./tier";
import type { ArchitectureDocument, Catalog } from "./types";

const catalog = catalogJson as unknown as Catalog;
const comp = (type: string) => catalog.components.find((c) => c.type === type);

describe("technology tiers (from the engine's catalog)", () => {
  it("looks up a technology's tier, null for parts without profiles", () => {
    expect(tierOf(comp("database"), "postgresql")).toBe("core");
    expect(tierOf(comp("database"), "mongodb")).toBe("experimental");
    expect(tierOf(comp("external_api"), "payments")).toBeNull();
    expect(tierOf(comp("database"), undefined)).toBeNull();
  });

  it("gives every library item a tier where the part has technology profiles", () => {
    const items = libraryItems(catalog);
    const byKey = Object.fromEntries(items.map((i) => [i.key, i.tier]));
    expect(byKey["service:go-k8s-hpa"]).toBe("core");
    expect(byKey["service:python-k8s-hpa"]).toBe("experimental");
    expect(byKey["database"]).toBe("core"); // "Any database" is the generic part
    expect(byKey["external_api:payments-api"]).toBeNull();
  });

  it("knows which auto defaults are documented", () => {
    expect(defaultSource(comp("database"), "replicaApplyWorkers", "postgresql")).toMatch(/^https:\/\/www\.postgresql\.org\//);
    expect(defaultSource(comp("database"), "replicaApplyWorkers", "mongodb")).toBeNull();
  });

  it("uses the engine's wording for the badge", () => {
    expect(tierNote(catalog.tierNote)).toBe(catalog.tierNote);
    expect(catalog.tierNote).toMatch(/not yet validated against measurements/);
  });
});

describe("Labs (experimental technologies)", () => {
  it("offers experimental technologies only with Labs on, and everything else always", () => {
    expect(offeredWithLabs("experimental", false)).toBe(false);
    expect(offeredWithLabs("experimental", true)).toBe(true);
    expect(offeredWithLabs("core", false)).toBe(true);
    expect(offeredWithLabs(null, false)).toBe(true); // generic parts and third-party APIs
  });

  it("hides about half of the library without Labs, never a generic part", () => {
    const items = libraryItems(catalog);
    const offered = items.filter((i) => offeredWithLabs(i.tier, false));
    expect(offered.length).toBeLessThan(items.length);
    expect(offered.some((i) => i.key === "database")).toBe(true);
    expect(offered.every((i) => i.tier !== "experimental")).toBe(true);
  });

  it("names a design's parts that use experimental technologies", () => {
    const starter = starterJson as unknown as ArchitectureDocument;
    expect(experimentalParts(starter, catalog)).toEqual([]);
    expect(experimentalParts(starter, null)).toEqual([]);
    const withMongo = structuredClone(starter);
    const db = withMongo.architecture.nodes.find((n) => n.id === "db")!;
    db.config = { ...db.config, technology: "mongodb" };
    expect(experimentalParts(withMongo, catalog)).toEqual(["PostgreSQL"]);
  });
});
