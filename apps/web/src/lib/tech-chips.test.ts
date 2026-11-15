import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import type { Catalog } from "@/lib/types";
import { techChips } from "./tech-chips";

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
