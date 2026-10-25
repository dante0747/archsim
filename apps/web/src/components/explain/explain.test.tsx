import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import { switchToPreset } from "@/components/build/presets";
import { describeAuto } from "@/components/editor/FieldControl";
import { explainResult } from "@/components/explain/FormulaCard";
import { TraceWaterfall, kindInfo } from "@/components/explain/TraceWaterfall";
import type { Catalog, TraceRecord } from "@/lib/types";

const catalog = catalogJson as unknown as Catalog;
const db = catalog.components.find((c) => c.type === "database")!;
const preset = (id: string) => db.presets!.find((p) => p.id === id)!;

afterEach(cleanup);

describe("switching presets", () => {
  it("drops the previous technology's settings but keeps the user's own", () => {
    const mongo = switchToPreset({ ...db.defaultConfig, queueTimeoutMs: 250 }, db, preset("mongodb-sharded"));
    expect(mongo.scatterReads).toBe(0.1);
    expect(mongo.shards).toBe(4);
    const pg = switchToPreset(mongo, db, preset("postgresql"));
    expect(pg.technology).toBe("postgresql");
    expect(pg.scatterReads).toBeUndefined();
    expect(pg.shards).toBe(1);
    expect(pg.readFrom).toBe("auto");
    expect(pg.queueTimeoutMs).toBe(250); // no preset sets it: the user's value stays
  });
});

describe("calculations", () => {
  it("formats results by unit", () => {
    expect(explainResult({ result: 0.734, unit: "" })).toBe("73%");
    expect(explainResult({ result: 1234.5, unit: "req/s" })).toBe("1,235 req/s");
    expect(explainResult({ result: 2.5, unit: "x" })).toBe("2.50×");
    expect(explainResult({ result: null, unit: "ms" })).toBe("–");
  });

  it("describes technology defaults for Auto settings", () => {
    expect(db.technologyDefaults?.replicaApplyWorkers?.postgresql).toBe(1);
    expect(describeAuto({ unit: undefined }, 1)).toBe("1");
    expect(describeAuto({ unit: "ms" }, 60000)).toBe("60,000 ms");
    expect(describeAuto({}, "allkeys_lru")).toBe("allkeys lru");
    expect(describeAuto({}, null)).toBe("none");
  });
});

describe("trace waterfall", () => {
  const trace: TraceRecord = {
    id: 7,
    op: "write",
    client: "clients",
    startS: 12.3,
    durationMs: 20,
    status: "ok",
    reason: null,
    failedAt: null,
    attempts: 1,
    tags: ["median"],
    spans: [
      { nodeId: null, label: null, edgeId: "c-db", kind: "network", startMs: 0, endMs: 2, depth: 0, detail: null },
      { nodeId: "db", label: "PostgreSQL", edgeId: null, kind: "handle", startMs: 2, endMs: 18, depth: 1, detail: null },
      { nodeId: "db", label: "PostgreSQL", edgeId: null, kind: "lock", startMs: 2, endMs: 9, depth: 1, detail: "row lock on key 0" },
      { nodeId: "db", label: "PostgreSQL", edgeId: null, kind: "commit", startMs: 15, endMs: 18, depth: 1, detail: null },
    ],
  };

  it("draws one row per step plus the request", () => {
    render(<TraceWaterfall trace={trace} edgeLabel={(id) => `Network ${id}`} />);
    expect(screen.getByText("Request 7 (write)")).toBeTruthy();
    expect(screen.getByText("Network c-db")).toBeTruthy();
    expect(screen.getByText("PostgreSQL · waiting for a lock")).toBeTruthy();
    expect(screen.getByText("PostgreSQL · log flush")).toBeTruthy();
  });

  it("groups kinds for the legend", () => {
    expect(kindInfo("lock").group).toBe("wait");
    expect(kindInfo("commit").group).toBe("durability");
    expect(kindInfo("gc").group).toBe("pause");
    expect(kindInfo("something_new").label).toBe("something new");
  });
});
