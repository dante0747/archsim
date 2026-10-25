import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import { DEFAULT_WORKLOAD } from "./document";
import { applyAutoSizes, autoSizes } from "./sizing";
import type { ArchitectureDocument, Catalog, EdgeSpec, NodeSpec, Workload } from "./types";

const catalog = catalogJson as unknown as Catalog;
const C = (value: number) => ({ kind: "constant" as const, value });

const node = (id: string, type: string, config: Record<string, unknown> = {}): NodeSpec => ({ id, type, label: id, config });
const edge = (source: string, target: string, requestTypes: ("read" | "write")[] = ["read", "write"], sourcePort = "out"): EdgeSpec => ({
  id: `${source}-${target}`,
  source,
  sourcePort,
  target,
  targetPort: "in",
  config: { requestTypes, latencyMs: C(0.5) },
});

function doc(nodes: NodeSpec[], edges: EdgeSpec[], workload: Partial<Workload> = {}): ArchitectureDocument {
  return {
    schemaVersion: "1.0",
    kind: "archsim.architecture",
    metadata: { name: "t" },
    architecture: { nodes, edges },
    workload: { ...DEFAULT_WORKLOAD, ...workload },
    scenarios: [],
    layout: { nodes: {} },
  };
}

describe("auto-sizing", () => {
  it("sizes a service's CPU for its peak share at a 60% target, and memory for the requests it holds", () => {
    const d = doc(
      [node("clients", "traffic_source"), node("api", "service", { replicas: 2, workersPerReplica: 8, queueCapacity: 50, serviceTimeMs: C(10) })],
      [edge("clients", "api")],
      { rps: 300 },
    );
    const s = autoSizes(d, catalog).get("api")!;
    // 150 req/s per replica × 10 ms = 1.5 cores busy; / 0.6 = 2.5 → 4 vCPU
    expect(s.cpuCores).toBe(4);
    // 256 MiB + (8 + 50) × 2 MiB = 372 MiB, × 1.25 = 465 MiB → 0.5 GiB
    expect(s.memoryGib).toBe(0.5);
    expect(s.storageGib).toBeNull();
    expect(s.why.cpuCores).toContain("150 req/s per replica");
  });

  it("uses the full scale of an autoscaler, caps CPU at what the workers can use, and has a floor", () => {
    const sizes = autoSizes(
      doc(
        [
          node("clients", "traffic_source"),
          node("lb", "load_balancer"),
          node("hpa", "service", { autoscaling: true, replicas: 2, maxReplicas: 10, workersPerReplica: 16, serviceTimeMs: C(20) }),
          node("narrow", "service", { replicas: 1, workersPerReplica: 2, serviceTimeMs: C(50) }),
          node("idle", "service", {}),
        ],
        [edge("clients", "lb"), edge("lb", "hpa", ["read"]), edge("lb", "narrow", ["write"])],
        { rps: 1000, readRatio: 0.5 },
      ),
      catalog,
    );
    // 500 req/s over 10 replicas × 20 ms = 1 core; / 0.6 → 2 vCPU
    expect(sizes.get("hpa")!.cpuCores).toBe(2);
    // 500 req/s × 50 ms = 25 cores would be needed, but 2 workers can only use 2
    expect(sizes.get("narrow")!.cpuCores).toBe(2);
    expect(sizes.get("narrow")!.why.cpuCores).toContain("capped at 2");
    // not connected: the smallest sizes
    expect(sizes.get("idle")).toMatchObject({ cpuCores: 1, memoryGib: 0.5, peakRps: 0 });
  });

  it("sizes databases from the busiest primary, the working set and the data size", () => {
    const sizes = autoSizes(
      doc(
        [
          node("clients", "traffic_source"),
          node("db", "database", { readReplicas: 1, readServiceTimeMs: C(4), writeServiceTimeMs: C(10), cpuShare: 0.5, workingSetGib: 30, dataSizeGib: 140, shards: 2 }),
          node("plain", "database", {}),
        ],
        [edge("clients", "db"), edge("clients", "plain")],
        { rps: 2000, readRatio: 0.8 },
      ),
      catalog,
    );
    const db = sizes.get("db")!;
    // per primary: 1600/2/2 = 400 reads × 4 ms + 200 writes × 10 ms = 3.6 s/s, half of it CPU = 1.8 cores; / 0.6 = 3 → 4
    expect(db.cpuCores).toBe(4);
    // 15 GiB of hot data per instance / 0.75 = 20 GiB → 32 GiB
    expect(db.memoryGib).toBe(32);
    // 70 GiB per instance / 0.7 = 100 GiB
    expect(db.storageGib).toBe(100);
    // no data size: keys × item size, with a 10 GiB floor; memory 4 GiB per vCPU
    expect(sizes.get("plain")!.storageGib).toBe(10);
    expect(sizes.get("plain")!.why.storageGib).toContain("set the data size");
    expect(sizes.get("plain")!.memoryGib).toBe(4 * sizes.get("plain")!.cpuCores!);
  });

  it("sizes cache memory for the keyspace and Kafka storage for its retention; managed services are skipped", () => {
    const sizes = autoSizes(
      doc(
        [
          node("clients", "traffic_source"),
          node("cache", "cache", { itemSizeKb: 1 }),
          node("log", "message_queue", { technology: "kafka", brokers: 3, messageSizeKb: 1 }),
          node("sqs", "message_queue", { technology: "sqs" }),
        ],
        [edge("clients", "cache", ["read"]), edge("clients", "log", ["write"]), edge("clients", "sqs", ["write"])],
        { rps: 100, readRatio: 0.5, keyCount: 1_000_000 },
      ),
      catalog,
    );
    // 1M × 1 KiB = 0.95 GiB / 0.8 × 1.25 = 1.49 → 2 GiB
    expect(sizes.get("cache")!.memoryGib).toBe(2);
    // 50 msg/s × 1 KiB × 7 days × 3 copies / 3 brokers = 28.8 GiB / 0.7 = 41 → 50 GiB
    expect(sizes.get("log")!.storageGib).toBe(50);
    expect(sizes.get("sqs")!.skipped).toContain("managed");
    expect(sizes.get("sqs")!.cpuCores).toBeNull();
  });

  it("fills only empty specs and leaves the document alone when nothing is missing", () => {
    const d = doc(
      [node("clients", "traffic_source"), node("api", "service", { cpuCores: 3, memoryGib: null, serviceTimeMs: C(5) })],
      [edge("clients", "api")],
    );
    const filled = applyAutoSizes(d, catalog);
    const api = filled.architecture.nodes.find((n) => n.id === "api")!;
    expect(api.config.cpuCores).toBe(3);
    expect(api.config.memoryGib).toBe(0.5);
    expect(d.architecture.nodes[1].config.memoryGib).toBeNull(); // the original is not mutated
    expect(applyAutoSizes(filled, catalog)).toBe(filled);
  });
});

describe("auto-sizing with technology behaviour", () => {
  it("counts Elasticsearch searches on every shard and Node.js event loops", () => {
    const sizes = autoSizes(
      doc(
        [
          node("clients", "traffic_source"),
          node("es", "database", { technology: "elasticsearch", shards: 6, readServiceTimeMs: C(6), cpuShare: 0.5 }),
          node("pg", "database", { technology: "postgresql", shards: 6, readServiceTimeMs: C(6), cpuShare: 0.5 }),
          node("node", "service", { technology: "nodejs", processesPerReplica: 2, workersPerReplica: 64, serviceTimeMs: C(10) }),
        ],
        [edge("clients", "es", ["read"]), edge("clients", "pg", ["read"]), edge("clients", "node")],
        { rps: 300, readRatio: 1 },
      ),
      catalog,
    );
    // every search hits all 6 shards: 300 × 6 ms × 0.5 = 0.9 cores per shard; / 0.6 → 2 vCPU
    expect(sizes.get("es")!.cpuCores).toBe(2);
    expect(sizes.get("es")!.why.cpuCores).toContain("all 6 shards");
    // key-routed reads: 50 per shard × 6 ms × 0.5 = 0.15 cores → 1 vCPU
    expect(sizes.get("pg")!.cpuCores).toBe(1);
    // 300 × 10 ms = 3 cores would be needed, but 2 event loops can only use 2
    expect(sizes.get("node")!.cpuCores).toBe(2);
    expect(sizes.get("node")!.why.cpuCores).toContain("2 event loops");
  });
});
