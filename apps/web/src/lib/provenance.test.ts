import { beforeEach, describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starter from "@/test/fixtures/starter.json";
import { describeDistribution, isDistribution, meanOf } from "./distributions";
import { fromDocument, replayVerdict, sameValue, stillMeasured, toDocument, withoutProvenance } from "./document";
import type { ArchitectureDocument, Catalog, Distribution, InputProvenance, ReplayCheck } from "./types";
import { useEditor } from "@/store/editor";

const catalog = catalogJson as unknown as Catalog;

const calibrated = (): ArchitectureDocument => {
  const doc = structuredClone(starter) as unknown as ArchitectureDocument;
  doc.schemaVersion = "1.1";
  doc.architecture.autoSize = false;
  doc.provenance = {
    "nodes/api/serviceTimeMs": { kind: "measured", source: "Prometheus http://prom:9090", query: "q", timeRange: "a/b", fitError: 0.03 },
    "edges/api-db/latencyMs": { kind: "measured", source: "Prometheus http://prom:9090" },
    "workload/rps": { kind: "measured", source: "Prometheus http://prom:9090" },
  };
  doc.calibration = { at: "2026-10-26T09:00:00Z", source: "Prometheus http://prom:9090", inputs: 3, replay: [], notes: [] };
  doc.pricing = { provider: "example-cloud", region: "test-1", purchaseOption: "on_demand", families: ["gp"] };
  return doc;
};

describe("documents keep what the editor does not edit", () => {
  it("round-trips provenance, the calibration record, pricing and autoSize false", () => {
    const doc = calibrated();
    const back = toDocument(fromDocument(doc));
    expect(back.schemaVersion).toBe("1.3");
    expect(back.provenance).toEqual(doc.provenance);
    expect(back.pricing).toEqual(doc.pricing);
    expect(back.calibration).toEqual(doc.calibration);
    expect(back.architecture.autoSize).toBe(false);
    // plain documents stay plain: the starter keeps the price snapshot it pins, and a document
    // without one keeps generic rates (no pricing written)
    const plain = toDocument(fromDocument(starter as unknown as ArchitectureDocument));
    expect(plain.provenance).toBeUndefined();
    expect(plain.calibration).toBeUndefined();
    expect(plain.pricing).toEqual((starter as unknown as ArchitectureDocument).pricing);
    expect("autoSize" in plain.architecture).toBe(false);
    const unpriced = structuredClone(starter) as unknown as ArchitectureDocument;
    delete unpriced.pricing;
    expect(toDocument(fromDocument(unpriced)).pricing).toBeUndefined();
  });

  it("drops provenance of edited and deleted inputs", () => {
    const extras = fromDocument(calibrated()).extras;
    expect(Object.keys(withoutProvenance(extras, ["workload/rps"])?.provenance ?? {})).not.toContain("workload/rps");
    expect(Object.keys(withoutProvenance(extras, [], ["nodes/api/"])?.provenance ?? {})).toEqual(["edges/api-db/latencyMs", "workload/rps"]);
    expect(withoutProvenance(extras, ["nodes/none/x"])).toBe(extras);
  });
});

describe("the editor store", () => {
  beforeEach(() => {
    useEditor.getState().load({ id: "p", name: "P", revision: 1, document: calibrated() } as never, catalog);
  });

  it("marks a measured value assumed once it is edited, and undo brings the provenance back", () => {
    const st = useEditor.getState();
    st.setNodeConfig("api", "serviceTimeMs", { kind: "lognormal", median: 9, p95: 30 });
    expect(useEditor.getState().toDocument().provenance?.["nodes/api/serviceTimeMs"]).toBeUndefined();
    useEditor.getState().undo();
    expect(useEditor.getState().toDocument().provenance?.["nodes/api/serviceTimeMs"]?.kind).toBe("measured");
  });

  it("keeps the calibration record through saves", () => {
    expect(useEditor.getState().toDocument().calibration?.inputs).toBe(3);
  });
});

describe("measured (empirical) distributions", () => {
  const d: Distribution = { kind: "empirical", quantiles: [[0, 1], [0.5, 2], [1, 4]] };
  it("are recognised, averaged and described", () => {
    expect(isDistribution(d)).toBe(true);
    expect(meanOf(d)).toBeCloseTo(0.75 + 1.5);
    expect(describeDistribution(d)).toBe("measured p50 2 / p95 3.80 ms");
  });
});

describe("measured means: still the value calibration set", () => {
  const p: InputProvenance = { kind: "measured", source: "Prometheus", value: { kind: "lognormal", median: 6, p95: 18.553012 } };

  it("compares values, not where they are written", () => {
    expect(stillMeasured(p, { p95: 18.553012, kind: "lognormal", median: 6.0 })).toBe(true);
    expect(stillMeasured(p, { kind: "lognormal", median: 9, p95: 30 })).toBe(false);
    expect(stillMeasured({ kind: "measured", value: 0.85 }, 0.2)).toBe(false);
    expect(stillMeasured({ kind: "measured", value: 0.85 }, 0.85 + 1e-12)).toBe(true);
    expect(stillMeasured({ kind: "measured" }, 123)).toBe(true); // older records: taken at their word
    expect(stillMeasured({ kind: "assumed" }, 1)).toBe(false);
    expect(sameValue([[0, 1], [1, 2]], [[0, 1], [1, 2.5]])).toBe(false);
    expect(sameValue({ a: 1 }, { a: 1, b: 2 })).toBe(false);
  });

  it("survives a round trip through the editor", () => {
    const doc = calibrated();
    const api = doc.architecture.nodes.find((n) => n.id === "api")!;
    doc.provenance!["nodes/api/serviceTimeMs"].value = structuredClone(api.config.serviceTimeMs);
    const back = toDocument(fromDocument(doc));
    const after = back.architecture.nodes.find((n) => n.id === "api")!.config.serviceTimeMs;
    expect(stillMeasured(back.provenance?.["nodes/api/serviceTimeMs"], after)).toBe(true);
  });
});

describe("the replay verdict", () => {
  const check = (scope: ReplayCheck["scope"], label: string, metric: ReplayCheck["metric"], error: number): ReplayCheck => ({
    scope, id: null, label, metric, observed: 1, simulated: 1 + error, error,
  });

  it("names the worst check, not only the end-to-end p95", () => {
    const record = { at: "a", source: "s", inputs: 7, notes: [], replay: [check("end_to_end", "End to end", "p95", -0.049), check("component", "PostgreSQL", "p99", -0.1407)] };
    expect(replayVerdict(record)).toBe("The model reproduces the observed p95 within 5% (under); the largest of 2 replay errors is -14% (PostgreSQL p99).");
    expect(replayVerdict({ ...record, replay: [check("component", "API", "throughputRps", 0.4)] })).toBe("The largest of 1 replay errors is +40% (API throughput).");
    expect(replayVerdict({ ...record, replay: [] })).toBe("");
  });
});
