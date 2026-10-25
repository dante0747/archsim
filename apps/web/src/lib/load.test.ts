import { describe, expect, it } from "vitest";
import catalogJson from "@/test/fixtures/catalog.json";
import { DEFAULT_WORKLOAD } from "./document";
import { estimateLoad, peakRps } from "./load";
import type { Catalog, EdgeSpec, NodeSpec, Workload } from "./types";

const catalog = catalogJson as unknown as Catalog;
const node = (id: string, type: string, config: Record<string, unknown> = {}): NodeSpec => ({ id, type, label: id, config });
const edge = (source: string, target: string, requestTypes: ("read" | "write")[] = ["read", "write"], sourcePort = "out"): EdgeSpec => ({
  id: `${source}-${target}`,
  source,
  sourcePort,
  target,
  targetPort: "in",
  config: { requestTypes, latencyMs: { kind: "constant", value: 0.5 }, maxAttempts: 1, retryBackoffMs: 50 },
});
const wl = (patch: Partial<Workload> = {}): Workload => ({ ...DEFAULT_WORKLOAD, rps: 1000, readRatio: 0.8, ...patch });
const near = (l: { read: number; write: number } | null | undefined, read: number, write: number) => {
  expect(l!.read).toBeCloseTo(read, 6);
  expect(l!.write).toBeCloseTo(write, 6);
};

describe("peakRps", () => {
  it("takes the highest rate of the pattern", () => {
    expect(peakRps(wl())).toBe(1000);
    expect(peakRps(wl({ pattern: { kind: "ramp", startRps: 100, endRps: 1500 } }))).toBe(1500);
    expect(peakRps(wl({ pattern: { kind: "burst", burstRps: 4000, startS: 10, durationS: 5 } }))).toBe(4000);
  });
});

describe("estimateLoad", () => {
  const nodes = [
    node("clients", "traffic_source"),
    node("lb", "load_balancer"),
    node("big", "service", { replicas: 3 }),
    node("small", "service", { replicas: 1 }),
    node("cache", "cache", { hitModel: "fixed_ratio", hitRatio: 0.75 }),
    node("db", "database"),
  ];
  const edges = [
    edge("clients", "lb"),
    edge("lb", "big"),
    edge("lb", "small"),
    edge("big", "cache", ["read"]),
    edge("big", "db", ["write"]),
    edge("cache", "db", ["read"], "miss"),
  ];

  it("spreads over balancer targets by replicas, follows links, and applies cache hits", () => {
    const load = estimateLoad(nodes, edges, catalog, wl());
    near(load.get("lb"), 800, 200);
    near(load.get("big"), 600, 150);
    near(load.get("small"), 200, 50);
    near(load.get("cache"), 600, 0);
    // writes from the service plus a quarter of the reads that miss
    near(load.get("db"), 150, 150);
  });

  it("gives no estimate below a TTL cache, and splits by client share", () => {
    const ttl = nodes.map((n) => (n.id === "cache" ? node("cache", "cache", { hitModel: "ttl_keyspace" }) : n));
    expect(estimateLoad(ttl, edges, catalog, wl()).get("db")).toBeNull();
    const two = [node("m", "mobile_app"), node("w", "web_client"), node("api", "service")];
    const load = estimateLoad(two, [edge("m", "api"), edge("w", "api")], catalog, wl({ readRatio: 1 }));
    expect(load.get("m")!.read).toBeCloseTo(1000 * (50 / 85));
    expect(load.get("api")!.read).toBeCloseTo(1000);
  });
});
