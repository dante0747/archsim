import { beforeEach, describe, expect, it } from "vitest";
import { expectedArrivals, rateAt } from "@/components/editor/WorkloadPanel";
import catalogJson from "@/test/fixtures/catalog.json";
import starterJson from "@/test/fixtures/starter.json";
import type { ArchitectureDocument, Catalog, Project } from "@/lib/types";
import { contentKey, useEditor } from "./editor";

const catalog = catalogJson as unknown as Catalog;
const project: Project = {
  id: "p1",
  name: "Starter",
  description: null,
  revision: 3,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  nodeCount: 5,
  scenarioCount: 6,
  document: starterJson as unknown as ArchitectureDocument,
};

const api = () => useEditor.getState().nodes.find((n) => n.id === "api")!;

describe("editor store", () => {
  beforeEach(() => useEditor.getState().load(structuredClone(project), catalog));

  it("loads a project clean", () => {
    const s = useEditor.getState();
    expect(s.nodes).toHaveLength(5);
    expect(s.edges).toHaveLength(4);
    expect(s.dirty).toBe(false);
    expect(s.revision).toBe(3);
  });

  it("edits config immutably and supports undo/redo", () => {
    const before = api().data.config;
    useEditor.getState().setNodeConfig("api", "replicas", 3);
    expect(api().data.config.replicas).toBe(3);
    expect(before.replicas).toBe(2);
    expect(useEditor.getState().dirty).toBe(true);
    useEditor.getState().undo();
    expect(api().data.config.replicas).toBe(2);
    useEditor.getState().redo();
    expect(api().data.config.replicas).toBe(3);
  });

  it("coalesces rapid edits of the same field into one undo step", () => {
    const s = useEditor.getState();
    s.setNodeConfig("api", "replicas", 3);
    s.setNodeConfig("api", "replicas", 4);
    s.setNodeConfig("api", "replicas", 5);
    expect(useEditor.getState().past).toHaveLength(1);
    useEditor.getState().undo();
    expect(api().data.config.replicas).toBe(2);
  });

  it("sets nested distribution parameters", () => {
    useEditor.getState().setNodeConfig("api", "serviceTimeMs", { kind: "constant", value: 4 });
    expect(api().data.config.serviceTimeMs).toEqual({ kind: "constant", value: 4 });
  });

  it("adds nodes with catalog defaults and unique ids", () => {
    const id1 = useEditor.getState().addNode("service", { x: 0, y: 0 });
    const id2 = useEditor.getState().addNode("service", { x: 0, y: 0 });
    expect(id1).toBe("service");
    expect(id2).toBe("service-2");
    const n = useEditor.getState().nodes.find((x) => x.id === id2)!;
    expect(n.data.config).toEqual(catalog.components.find((c) => c.type === "service")!.defaultConfig);
    expect(n.selected).toBe(true);
    expect(useEditor.getState().rightTab).toBe("inspect");
  });

  it("validates connections before adding them", () => {
    const bad = useEditor.getState().connect({ source: "db", target: "api", sourceHandle: "out", targetHandle: "in" });
    expect(bad.ok).toBe(false);
    const id = useEditor.getState().addNode("database", { x: 0, y: 0 })!;
    const ok = useEditor.getState().connect({ source: "api", target: id, sourceHandle: "out", targetHandle: "in" });
    expect(ok.ok).toBe(true);
    const edge = useEditor.getState().edges.find((e) => e.target === id)!;
    expect(edge.data?.config.requestTypes).toEqual(["read", "write"]);
  });

  it("links from a client part start with its network latency", () => {
    const mobile = useEditor.getState().addNode("mobile_app", { x: 0, y: 400 })!;
    expect(useEditor.getState().connect({ source: mobile, target: "lb", sourceHandle: "s-right", targetHandle: "s-left" }).ok).toBe(true);
    const edge = useEditor.getState().edges.find((e) => e.source === mobile)!;
    expect(edge.data?.config.latencyMs).toEqual({ kind: "lognormal", median: 35, p95: 120 });
    // other links keep the plain default
    const plain = useEditor.getState().edges.find((e) => e.id === "lb-api")!;
    expect(plain.data?.config.latencyMs).not.toEqual(edge.data?.config.latencyMs);
  });

  it("deleting a node removes its links", () => {
    useEditor.getState().deleteElements(["cache"], []);
    const s = useEditor.getState();
    expect(s.nodes.some((n) => n.id === "cache")).toBe(false);
    expect(s.edges.some((e) => e.source === "cache" || e.target === "cache")).toBe(false);
  });

  it("content key ignores layout but tracks config", () => {
    const k1 = contentKey(useEditor.getState());
    useEditor.setState((s) => ({ nodes: s.nodes.map((n) => ({ ...n, position: { x: 1, y: 2 } })) }));
    expect(contentKey(useEditor.getState())).toBe(k1);
    useEditor.getState().setWorkload({ rps: 10 });
    expect(contentKey(useEditor.getState())).not.toBe(k1);
  });

  it("exports a document that keeps the project name", () => {
    useEditor.getState().setMetadata({ name: "Renamed" });
    const doc = useEditor.getState().toDocument();
    expect(doc.metadata.name).toBe("Renamed");
    expect(doc.schemaVersion).toBe("1.6");
  });
});

describe("workload math (mirrors the engine)", () => {
  const base = (starterJson as unknown as ArchitectureDocument).workload;
  it("computes rates and expected arrivals for patterns", () => {
    expect(expectedArrivals(base)).toBe(400 * 60);
    const burst = { ...base, pattern: { kind: "burst" as const, burstRps: 900, startS: 30, durationS: 10 } };
    expect(rateAt(burst, 35)).toBe(900);
    expect(rateAt(burst, 45)).toBe(400);
    expect(expectedArrivals(burst)).toBe(400 * 50 + 900 * 10);
    const ramp = { ...base, pattern: { kind: "ramp" as const, startRps: 0, endRps: 100 } };
    expect(rateAt(ramp, 30)).toBe(50);
    expect(expectedArrivals(ramp)).toBe(50 * 60);
  });
});
