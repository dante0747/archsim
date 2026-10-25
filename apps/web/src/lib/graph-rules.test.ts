import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import { fromDocument } from "./document";
import { checkConnection, wouldCreateCycle } from "./graph-rules";
import type { ArchitectureDocument, Catalog } from "./types";

const catalog = catalogJson as unknown as Catalog;
const { nodes, edges } = fromDocument(starterJson as unknown as ArchitectureDocument);

const check = (source: string, target: string, sourceHandle = "out", targetHandle = "in") =>
  checkConnection({ source, target, sourceHandle, targetHandle }, nodes, edges, catalog);

describe("connection rules", () => {
  it("resolves side handles to the component's ports", () => {
    const withoutApiDb = edges.filter((e) => e.id !== "api-db");
    const res = checkConnection({ source: "api", target: "db", sourceHandle: "s-bottom", targetHandle: "s-top" }, nodes, withoutApiDb, catalog);
    expect(res.ok && [res.sourcePort.name, res.targetPort.name]).toEqual(["out", "in"]);
    const miss = checkConnection({ source: "cache", target: "db", sourceHandle: "s-left", targetHandle: "s-right" }, nodes, edges.filter((e) => e.id !== "cache-db"), catalog);
    expect(miss.ok && miss.sourcePort.name).toBe("miss");
    // An existing link is still found whatever sides it is drawn on.
    expect(checkConnection({ source: "api", target: "db", sourceHandle: "s-top", targetHandle: "s-bottom" }, nodes, edges, catalog)).toMatchObject({ ok: false });
  });

  it("accepts a valid new link with compatible request types", () => {
    const withoutApiDb = edges.filter((e) => e.id !== "api-db");
    const res = checkConnection({ source: "api", target: "db", sourceHandle: "out", targetHandle: "in" }, nodes, withoutApiDb, catalog);
    expect(res.ok).toBe(true);
    if (res.ok) expect(res.requestTypes).toEqual(["read", "write"]);
  });

  it("restricts cache miss links to reads", () => {
    const res = checkConnection(
      { source: "cache", target: "db", sourceHandle: "miss", targetHandle: "in" },
      nodes,
      edges.filter((e) => e.id !== "cache-db"),
      catalog,
    );
    expect(res.ok && res.requestTypes).toEqual(["read"]);
  });

  it("rejects self links, sources as targets and databases as sources", () => {
    expect(check("api", "api")).toMatchObject({ ok: false, reason: expect.stringMatching(/itself/) });
    expect(check("api", "clients")).toMatchObject({ ok: false, reason: expect.stringMatching(/cannot receive/) });
    expect(check("db", "api")).toMatchObject({ ok: false, reason: expect.stringMatching(/cannot send/) });
  });

  it("enforces port arity and duplicates", () => {
    expect(check("clients", "api")).toMatchObject({ ok: false, reason: expect.stringMatching(/only 1 link/) });
    expect(check("cache", "db", "miss")).toMatchObject({ ok: false, reason: expect.stringMatching(/only 1 link/) });
    expect(check("lb", "api")).toMatchObject({ ok: false, reason: expect.stringMatching(/already linked/) });
  });

  it("rejects links that would create a loop", () => {
    expect(wouldCreateCycle("db", "clients", edges)).toBe(true);
    expect(wouldCreateCycle("clients", "db", edges)).toBe(false);
    const freeMissPort = edges.filter((e) => e.id !== "cache-db");
    expect(
      checkConnection({ source: "cache", target: "api", sourceHandle: "miss", targetHandle: "in" }, nodes, freeMissPort, catalog),
    ).toMatchObject({ ok: false, reason: expect.stringMatching(/loop/) });
  });
});
