import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { Catalog } from "@/lib/types";
import { catalogStats, techChips } from "./tech-chips";

const catalog = catalogJson as unknown as Catalog;

describe("techChips", () => {
  it("lists every technology preset plus the client technologies, without duplicates", () => {
    const chips = techChips(catalog);
    const presets = catalog.components.reduce((n, c) => n + (c.presets?.length ?? 0), 0);
    expect(chips.length).toBe(presets + 11);
    expect(new Set(chips.map((c) => c.key)).size).toBe(chips.length);
    expect(chips.find((c) => c.technology === "postgresql")?.kind).toBe("database");
    expect(chips.find((c) => c.technology === "ios")).toMatchObject({ name: "iOS", kind: "traffic" });
  });
});

describe("catalogStats", () => {
  it("counts the technology profiles by tier and the kinds of clients", () => {
    const presets = catalog.components.flatMap((c) => c.presets ?? []);
    const tier = (t: string | null) => presets.filter((p) => (p.tier ?? null) === t).length;

    const stats = catalogStats(catalog);

    expect(stats.technologies).toBe(presets.length);
    expect(stats.clientKinds).toBe(catalog.components.filter((c) => c.isTrafficSource).length);
    expect(stats.tiers).toBe(`${tier("core")} core, ${tier("experimental")} experimental${tier(null) ? `, ${tier(null)} third-party APIs` : ""}`);
  });
});
