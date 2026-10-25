import { describe, expect, it } from "vitest";
import starterJson from "@/test/fixtures/starter.json";
import { FEATURED, orderTemplates, templateFacts, templateMeta } from "@/components/templates/templates-meta";
import type { ArchitectureDocument } from "@/lib/types";

describe("templates", () => {
  it("puts the featured templates first, then the rest by name", () => {
    const items = ["queueing-lab", "cache-aside-starter", "black-friday-kubernetes", "iot-cassandra-quorum"].map((id) => ({ id, name: id }));
    expect(orderTemplates(items).map((x) => x.id)).toEqual(["black-friday-kubernetes", "cache-aside-starter", "iot-cassandra-quorum", "queueing-lab"]);
    expect(FEATURED[0]).toBe("black-friday-kubernetes");
  });

  it("knows each built-in template and falls back for unknown ones", () => {
    expect(templateMeta({ id: "mongodb-hot-shard", tags: [] }).category).toBe("Data");
    expect(templateMeta({ id: "something-new", tags: ["education"] }).category).toBe("Learn");
    expect(templateMeta({ id: "something-new", tags: [] }).insight).toMatch(/Simulate/);
  });

  it("reads parts, peak load and technologies from the design", () => {
    const facts = templateFacts(starterJson as unknown as ArchitectureDocument);
    expect(facts.parts).toBeGreaterThan(3);
    expect(facts.peakRps).toBeGreaterThan(0);
    expect(facts.technologies.map((t) => t.technology)).toContain("postgresql");
    expect(new Set(facts.technologies.map((t) => t.technology)).size).toBe(facts.technologies.length);
  });
});
