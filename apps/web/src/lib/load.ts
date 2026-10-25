/**
 * Static load estimate: roughly how many requests per second reach each part at the workload's
 * peak, without simulating. Used by the design review for capacity checks.
 *
 * The peak rate is split by the read/write mix and by each client part's traffic share, then
 * pushed along the links in call order: a load balancer (and a queue) spreads a request type over
 * the links that carry it, weighted by the targets' replicas; a service calls every dependency
 * that carries the type; a cache forwards reads to its miss link at (1 - hit ratio) and absorbs
 * writes. First attempts only (no retries). Where the share is unknown (a TTL cache's misses) the
 * parts downstream get no estimate.
 */
import { clientShares } from "./clients";
import { kindOf } from "./theme";
import type { Catalog, EdgeSpec, NodeConfig, NodeSpec, Workload } from "./types";

export interface NodeLoad {
  read: number;
  write: number;
}

/** Highest arrival rate of the workload pattern (req/s). */
export function peakRps(w: Workload): number {
  const p = w.pattern;
  if (p.kind === "ramp") return Math.max(p.startRps, p.endRps);
  if (p.kind === "burst") return Math.max(w.rps, p.burstRps);
  return w.rps;
}

const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

/** Replicas a load balancer weighs a target by (services: replicas, at max scale when autoscaling). */
function weight(cfg: NodeConfig, type: string): number {
  if (type !== "service") return 1;
  const replicas = num(cfg.replicas) ?? 1;
  return Math.max(1, cfg.autoscaling === true ? Math.max(num(cfg.maxReplicas) ?? replicas, replicas) : replicas);
}

/**
 * Peak load per node id; `null` when it cannot be estimated statically. `ttlMissShare` assumes a
 * miss share for TTL caches instead of giving up (sizing uses 1: every read may miss).
 */
export function estimateLoad(
  nodes: NodeSpec[],
  edges: EdgeSpec[],
  catalog: Catalog | null,
  workload: Workload,
  opts: { ttlMissShare?: number } = {},
): Map<string, NodeLoad | null> {
  const cfg = new Map<string, NodeConfig>();
  for (const n of nodes) {
    const comp = catalog?.components.find((c) => c.type === n.type);
    cfg.set(n.id, { ...(comp?.defaultConfig ?? {}), ...(n.config ?? {}) });
  }
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const out = new Map<string, EdgeSpec[]>();
  const indeg = new Map<string, number>(nodes.map((n) => [n.id, 0]));
  for (const e of edges) {
    if (!byId.has(e.source) || !byId.has(e.target)) continue;
    out.set(e.source, [...(out.get(e.source) ?? []), e]);
    indeg.set(e.target, (indeg.get(e.target) ?? 0) + 1);
  }

  const load = new Map<string, NodeLoad | null>(nodes.map((n) => [n.id, { read: 0, write: 0 }]));
  const peak = peakRps(workload);
  const r = Math.min(1, Math.max(0, workload.readRatio));
  for (const s of clientShares(nodes.map((n) => ({ id: n.id, data: { type: n.type, config: n.config ?? {} } })), catalog)) {
    load.set(s.id, { read: peak * s.fraction * r, write: peak * s.fraction * (1 - r) });
  }

  const add = (id: string, l: NodeLoad | null) => {
    const cur = load.get(id);
    if (cur === null) return;
    load.set(id, l === null ? null : { read: cur!.read + l.read, write: cur!.write + l.write });
  };

  // Kahn's order (designs are acyclic; parts on a cycle keep whatever reached them).
  const queue = nodes.filter((n) => (indeg.get(n.id) ?? 0) === 0).map((n) => n.id);
  while (queue.length) {
    const id = queue.shift()!;
    const n = byId.get(id)!;
    const l = load.get(id) ?? null;
    const c = cfg.get(id)!;
    const kind = kindOf(n.type, catalog?.components.find((x) => x.type === n.type)?.category);
    const outs = out.get(id) ?? [];
    for (const e of outs) {
      const carries = (op: "read" | "write") => (e.config.requestTypes ?? ["read", "write"]).includes(op);
      let sent: NodeLoad | null;
      if (l === null) {
        sent = null;
      } else if (kind === "network" || kind === "messaging") {
        const share = (op: "read" | "write") => {
          const cands = outs.filter((x) => (x.config.requestTypes ?? ["read", "write"]).includes(op));
          const total = cands.reduce((sum, x) => sum + weight(cfg.get(x.target)!, byId.get(x.target)!.type), 0);
          return carries(op) && total > 0 ? weight(cfg.get(e.target)!, byId.get(e.target)!.type) / total : 0;
        };
        sent = { read: l.read * share("read"), write: l.write * share("write") };
      } else if (kind === "cache") {
        if (e.sourcePort !== "miss") sent = { read: 0, write: 0 };
        else if ((c.hitModel ?? "fixed_ratio") === "fixed_ratio") sent = { read: carries("read") ? l.read * (1 - (num(c.hitRatio) ?? 0)) : 0, write: 0 };
        else if (opts.ttlMissShare != null) sent = { read: carries("read") ? l.read * opts.ttlMissShare : 0, write: 0 };
        else sent = null; // TTL misses depend on the run
      } else {
        // clients and services send each request type to every link that carries it
        sent = { read: carries("read") ? l.read : 0, write: carries("write") ? l.write : 0 };
      }
      add(e.target, sent);
      indeg.set(e.target, (indeg.get(e.target) ?? 1) - 1);
      if (indeg.get(e.target) === 0) queue.push(e.target);
    }
  }
  return load;
}
