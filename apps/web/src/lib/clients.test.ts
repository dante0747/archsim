import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import { clientFraction, clientShares } from "./clients";
import { isClientType, technologyLabel } from "./theme";
import type { Catalog, NodeConfig } from "./types";

const catalog = catalogJson as unknown as Catalog;
const n = (id: string, type: string, config: NodeConfig = {}) => ({ id, data: { type, config } });

describe("client parts", () => {
  it("knows every client type, from the catalog or by name", () => {
    for (const t of ["traffic_source", "mobile_app", "web_client", "api_consumer"]) {
      expect(isClientType(t, catalog)).toBe(true);
      expect(isClientType(t, null)).toBe(true);
    }
    expect(isClientType("service", catalog)).toBe(false);
  });

  it("splits traffic by relative share, using catalog defaults when unset", () => {
    const nodes = [n("m", "mobile_app"), n("w", "web_client"), n("p", "api_consumer"), n("api", "service")];
    expect(clientShares(nodes, catalog).map((s) => [s.id, s.fraction])).toEqual([
      ["m", 0.5],
      ["w", 0.35],
      ["p", 0.15],
    ]);
    const custom = [n("m", "mobile_app", { trafficShare: 30 }), n("c", "traffic_source", { trafficShare: 10 })];
    expect(clientFraction(custom, catalog, "m")).toBeCloseTo(0.75);
    expect(clientFraction(custom, catalog, "api")).toBeNull();
  });

  it("a single client sends everything", () => {
    expect(clientShares([n("c", "traffic_source")], catalog)).toEqual([{ id: "c", fraction: 1 }]);
  });

  it("names client technologies", () => {
    expect(technologyLabel(undefined, "nextjs")).toBe("Next.js");
    expect(technologyLabel(undefined, "react_native")).toBe("React Native");
    expect(technologyLabel(undefined, "rest")).toBe("REST / OpenAPI");
  });
});
