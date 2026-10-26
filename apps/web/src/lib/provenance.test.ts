import { beforeEach, describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import starter from "@/test/fixtures/starter.json";
import { describeDistribution, isDistribution, meanOf } from "./distributions";
import { fromDocument, toDocument, withoutProvenance } from "./document";
import type { ArchitectureDocument, Catalog, Distribution } from "./types";
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
  return doc;
};

describe("documents keep what the editor does not edit", () => {
  it("round-trips provenance, the calibration record and autoSize false", () => {
    const doc = calibrated();
    const back = toDocument(fromDocument(doc));
    expect(back.schemaVersion).toBe("1.1");
    expect(back.provenance).toEqual(doc.provenance);
    expect(back.calibration).toEqual(doc.calibration);
    expect(back.architecture.autoSize).toBe(false);
    // plain documents stay plain
    const plain = toDocument(fromDocument(starter as unknown as ArchitectureDocument));
    expect(plain.provenance).toBeUndefined();
    expect(plain.calibration).toBeUndefined();
    expect("autoSize" in plain.architecture).toBe(false);
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
