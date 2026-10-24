import { describe, expect, it } from "vitest";
import { verdictOf } from "@/components/editor/workflow";
import { FAMILIES, familyOf, kindOf, shortName, technologyLabel } from "./theme";
import type { Finding, Report } from "./types";

const report = (findings: Partial<Finding>[]) => ({ findings }) as unknown as Report;

describe("theme helpers", () => {
  it("maps component types and categories to kinds", () => {
    expect(kindOf("traffic_source")).toBe("traffic");
    expect(kindOf("load_balancer")).toBe("network");
    expect(kindOf("cache")).toBe("cache");
    expect(kindOf("message_queue")).toBe("messaging");
    expect(kindOf("external_api")).toBe("external");
    expect(kindOf("graph_db", "storage")).toBe("database");
    expect(kindOf("mystery")).toBe("other");
  });

  it("groups every kind into exactly one library family", () => {
    const kinds = ["traffic", "network", "compute", "cache", "database", "messaging", "external", "other"] as const;
    for (const k of kinds) expect(FAMILIES.filter((f) => f.kinds.includes(k))).toHaveLength(1);
    expect(familyOf("database").label).toBe("Data");
  });

  it("names technologies", () => {
    expect(technologyLabel(undefined, "postgresql")).toBe("PostgreSQL");
    expect(technologyLabel({ presets: [{ id: "x", name: "Neo4j graph", technology: "neo4j", family: "Graph", description: "", config: {}, tags: [] }] }, "neo4j")).toBe("Neo4j graph");
    expect(technologyLabel(undefined, "my_custom-db")).toBe("My Custom Db");
    expect(technologyLabel(undefined, undefined)).toBeNull();
  });

  it("derives short rail names", () => {
    expect(shortName("load_balancer", "Load Balancer")).toBe("Balancer");
    expect(shortName("api_gateway", "API Gateway (Kong)")).toBe("API");
  });
});

describe("verdict", () => {
  it("prefers the primary bottleneck, then hot components, else healthy", () => {
    expect(verdictOf(report([{ kind: "cache", title: "x" }, { kind: "bottleneck", title: "API is the primary bottleneck" }]))).toEqual({
      title: "API is the primary bottleneck",
      tone: "bad",
    });
    expect(verdictOf(report([{ kind: "hot", title: "DB is running hot" }])).tone).toBe("warn");
    expect(verdictOf(report([{ kind: "healthy", title: "ok" }])).tone).toBe("ok");
  });
});
