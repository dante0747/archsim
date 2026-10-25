import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import { DEFAULT_WORKLOAD } from "./document";
import { countBySeverity, reviewDesign, type ReviewFinding } from "./review";
import type { Catalog, EdgeConfig, EdgeSpec, NodeSpec } from "./types";

const catalog = catalogJson as unknown as Catalog;

const node = (id: string, type: string, config: Record<string, unknown> = {}, label = id): NodeSpec => ({ id, type, label, config });
const edge = (source: string, target: string, config: Partial<EdgeConfig> = {}, sourcePort = "out"): EdgeSpec => ({
  id: `${source}-${target}`,
  source,
  sourcePort,
  target,
  targetPort: "in",
  config: { requestTypes: ["read", "write"], latencyMs: { kind: "constant", value: 0.5 }, maxAttempts: 1, retryBackoffMs: 50, ...config },
});

const rules = (fs: ReviewFinding[]) => fs.map((f) => f.rule);
const find = (fs: ReviewFinding[], rule: string) => fs.filter((f) => f.rule === rule);

/** Clients → LB → API → DB with API → cache for reads. */
function baseDesign(apiConfig: Record<string, unknown> = {}, edgeOverrides: Record<string, Partial<EdgeConfig>> = {}) {
  const nodes = [
    node("clients", "traffic_source", { timeoutMs: 3000, maxAttempts: 1 }, "Clients"),
    node("lb", "load_balancer", {}, "Load Balancer"),
    node("api", "service", { replicas: 3, ...apiConfig }, "API"),
    node("cache", "cache", {}, "Redis"),
    node("db", "database", { readReplicas: 1 }, "Postgres"),
  ];
  const edges = [
    edge("clients", "lb", edgeOverrides["clients-lb"]),
    edge("lb", "api", { timeoutMs: 2000, ...edgeOverrides["lb-api"] }),
    edge("api", "cache", { requestTypes: ["read"], timeoutMs: 50, ...edgeOverrides["api-cache"] }),
    edge("api", "db", { requestTypes: ["write"], timeoutMs: 500, ...edgeOverrides["api-db"] }),
  ];
  return { nodes, edges };
}

describe("reviewDesign", () => {
  it("flags a single replica on the request path as high", () => {
    const { nodes, edges } = baseDesign({ replicas: 1 });
    const spof = find(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD), "spof").find((f) => f.nodeIds[0] === "api");
    expect(spof?.severity).toBe("high");
    expect(spof?.title).toBe("API runs a single replica");
  });

  it("does not flag replicated services, and flags a database without replica", () => {
    const { nodes, edges } = baseDesign();
    nodes[4] = node("db", "database", { readReplicas: 0 }, "Postgres");
    const fs = reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD);
    expect(find(fs, "spof").map((f) => f.nodeIds[0])).toEqual(expect.arrayContaining(["db"]));
    expect(find(fs, "spof").some((f) => f.nodeIds[0] === "api")).toBe(false);
  });

  it("groups missing timeouts per caller with the strongest severity", () => {
    const { nodes, edges } = baseDesign({}, { "api-db": { timeoutMs: null }, "api-cache": { timeoutMs: null } });
    const mt = find(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD), "missing-timeout");
    expect(mt).toHaveLength(1);
    expect(mt[0].severity).toBe("medium");
    expect(mt[0].edgeIds.sort()).toEqual(["api-cache", "api-db"]);
    expect(mt[0].title).toContain("2 dependencies");
  });

  it("shows the multiplication behind retry amplification", () => {
    const { nodes, edges } = baseDesign({}, { "lb-api": { maxAttempts: 3 }, "api-db": { maxAttempts: 3 } });
    const amp = find(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD), "retry-amplification");
    expect(amp).toHaveLength(1);
    expect(amp[0].severity).toBe("high");
    expect(amp[0].detail).toContain("3 × 3 = 9");
    expect(amp[0].title).toBe("Up to 9 attempts reach Postgres per request");
  });

  it("detects timeout inversion, including the client timeout", () => {
    const { nodes, edges } = baseDesign({}, { "lb-api": { timeoutMs: 400 }, "api-db": { timeoutMs: 300, maxAttempts: 2, retryBackoffMs: 50 } });
    const inv = find(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD), "timeout-inversion");
    expect(inv.map((f) => f.edgeIds[0])).toContain("lb-api");
    expect(inv[0].detail).toContain("650 ms"); // 300 × 2 + 50 backoff
    nodes[0] = node("clients", "traffic_source", { timeoutMs: 1000 }, "Clients");
    const inv2 = find(reviewDesign(nodes, [edges[0], { ...edges[1], config: { ...edges[1].config, timeoutMs: 2000 } }, edges[2], edges[3]], catalog, DEFAULT_WORKLOAD), "timeout-inversion");
    expect(inv2.some((f) => f.edgeIds[0] === "clients-lb" && f.detail.includes("client timeout"))).toBe(true);
  });

  it("warns when retries have no backoff", () => {
    const { nodes, edges } = baseDesign({}, { "api-db": { maxAttempts: 2, retryBackoffMs: 0 } });
    expect(rules(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD))).toContain("retry-no-backoff");
  });

  it("flags pool exhaustion when blocking workers outnumber connections", () => {
    const { nodes, edges } = baseDesign({ replicas: 10, workersPerReplica: 8 });
    nodes[4] = node("db", "database", { maxConnections: 10, readReplicas: 1 }, "Postgres");
    const pool = find(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD), "pool-exhaustion");
    expect(pool[0]?.severity).toBe("medium");
    expect(pool[0]?.detail).toContain("80 blocking workers");
    expect(pool[0]?.detail).toContain("accepts 20 connections");
  });

  it("flags serverless connection storms", () => {
    const { nodes, edges } = baseDesign({ platform: "serverless", concurrencyLimit: 1000 });
    const storm = find(reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD), "connection-storm");
    expect(storm[0]?.severity).toBe("high");
    expect(storm[0]?.title).toContain("1,000 connections");
  });

  it("covers queues, caches, shards and the edge", () => {
    const nodes = [
      node("clients", "traffic_source", {}, "Clients"),
      node("api", "service", { replicas: 2 }, "API"),
      node("mq", "message_queue", { maxDepth: null, partitions: 3, consumers: 6, brokers: 1, technology: "kafka" }, "Kafka"),
      node("cache", "cache", { hitRatio: 0.995 }, "Redis"),
      node("db", "database", { shards: 8, readReplicas: 1 }, "Mongo"),
    ];
    const edges = [edge("clients", "api"), edge("api", "mq", { requestTypes: ["write"], timeoutMs: 100 }), edge("api", "cache", { requestTypes: ["read"], timeoutMs: 20 }), edge("api", "db", { requestTypes: ["write"], timeoutMs: 200 })];
    const fs = reviewDesign(nodes, edges, catalog, { ...DEFAULT_WORKLOAD, keySkew: 0.99 });
    expect(rules(fs)).toEqual(expect.arrayContaining(["queue-unbounded", "queue-sink", "queue-partitions", "optimistic-cache", "hot-partition", "edge-rate-limit", "cache-dependency"]));
    expect(find(fs, "spof").some((f) => f.nodeIds[0] === "mq")).toBe(true);
  });

  it("sorts by severity and counts", () => {
    const { nodes, edges } = baseDesign({ replicas: 1 }, { "api-db": { timeoutMs: null } });
    const fs = reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD);
    const order = fs.map((f) => f.severity);
    expect([...order].sort((a, b) => ["high", "medium", "low"].indexOf(a) - ["high", "medium", "low"].indexOf(b))).toEqual(order);
    const c = countBySeverity(fs);
    expect(c.high + c.medium + c.low).toBe(fs.length);
    expect(new Set(fs.map((f) => f.id)).size).toBe(fs.length);
  });
});

describe("reviewDesign with resource specs", () => {
  const svc = { cpuCores: 1, serviceTimeMs: { kind: "constant", value: 20 } };
  const at = (rps: number) => ({ ...DEFAULT_WORKLOAD, rps });

  it("checks CPU at the peak only where cores are set", () => {
    // 3 replicas x 1 core / 20 ms = 150 req/s
    const { nodes, edges } = baseDesign(svc);
    const over = find(reviewDesign(nodes, edges, catalog, at(400)), "cpu-capacity");
    expect(over).toHaveLength(1);
    expect(over[0]).toMatchObject({ severity: "high", title: "API needs more CPU at peak", nodeIds: ["api"] });
    expect(over[0].detail).toContain("about 150 req/s");
    expect(find(reviewDesign(nodes, edges, catalog, at(130)), "cpu-capacity")[0]?.severity).toBe("medium");
    expect(find(reviewDesign(nodes, edges, catalog, at(50)), "cpu-capacity")).toHaveLength(0);
    const plain = baseDesign({ serviceTimeMs: svc.serviceTimeMs });
    expect(find(reviewDesign(plain.nodes, plain.edges, catalog, at(400)), "cpu-capacity")).toHaveLength(0);
  });

  it("flags a database working set that does not fit and a small TTL cache", () => {
    const { nodes, edges } = baseDesign();
    const db = nodes.find((n) => n.id === "db")!;
    db.config = { ...db.config, memoryGib: 8, workingSetGib: 64 };
    const cache = nodes.find((n) => n.id === "cache")!;
    cache.config = { hitModel: "ttl_keyspace", memoryGib: 0.01, itemSizeKb: 1 };
    const fs = reviewDesign(nodes, edges, catalog, DEFAULT_WORKLOAD);
    expect(find(fs, "buffer-cache")[0]).toMatchObject({ severity: "medium", nodeIds: ["db"] });
    expect(find(fs, "buffer-cache")[0].detail).toContain("91% of reads go to disk");
    expect(find(fs, "cache-capacity")[0].title).toBe("Redis holds 8,388 of 100,000 keys");
  });
});
