/**
 * Design review: an architecture linter over the document. Pure and static (no simulation): it
 * flags resilience risks reviewers look for (single points of failure, missing timeouts, stacked
 * retries, timeout inversion, pool exhaustion, unbounded queues...) with concrete numbers.
 */
import { isDistribution, meanOf } from "./distributions";
import { estimateLoad, peakRps } from "./load";
import { type Kind, kindOf } from "./theme";
import type { Catalog, EdgeSpec, NodeConfig, NodeSpec, Workload } from "./types";

export type ReviewSeverity = "high" | "medium" | "low";

export interface ReviewFinding {
  id: string;
  severity: ReviewSeverity;
  rule: string;
  title: string;
  detail: string;
  suggestion: string;
  nodeIds: string[];
  edgeIds: string[];
}

export const REVIEW_RULES: Record<string, string> = {
  spof: "Single point of failure",
  "cache-dependency": "Cache without fallback",
  "missing-timeout": "Missing timeout",
  "retry-amplification": "Retry amplification",
  "retry-no-backoff": "Retries without backoff",
  "timeout-inversion": "Timeout inversion",
  "unbounded-wait": "Unbounded wait",
  "pool-exhaustion": "Connection-pool exhaustion",
  "connection-storm": "Serverless connection storm",
  "autoscaling-range": "Autoscaling range",
  "queue-unbounded": "Unbounded backlog",
  "queue-sink": "Queue without consumers",
  "queue-partitions": "Idle consumers",
  "optimistic-cache": "Optimistic hit ratio",
  "hot-partition": "Hot partition risk",
  "consistency-all": "Consistency ALL",
  "external-errors": "Unhandled third-party errors",
  "edge-rate-limit": "No rate limit at the edge",
  "deep-chain": "Long synchronous chain",
  "cpu-capacity": "CPU capacity at peak",
  "buffer-cache": "Working set larger than memory",
  "cache-capacity": "Cache smaller than its keyspace",
};

const SEV_ORDER: Record<ReviewSeverity, number> = { high: 0, medium: 1, low: 2 };
const MANAGED_LB = new Set(["aws_alb", "api_gateway"]);
// Engine technology ids (the old aws_sqs / google_pubsub spellings never matched).
const MANAGED_MQ = new Set(["sqs", "pubsub", "aws_sqs", "google_pubsub"]);

const fmtInt = (n: number) => Math.round(n).toLocaleString("en-US");
const fmtMs = (n: number) => `${fmtInt(n)} ms`;

function num(v: unknown): number | null {
  return typeof v === "number" && Number.isFinite(v) ? v : null;
}

interface Ctx {
  nodes: NodeSpec[];
  edges: EdgeSpec[];
  byId: Map<string, NodeSpec>;
  cfg: Map<string, NodeConfig>;
  kind: Map<string, Kind>;
  out: Map<string, EdgeSpec[]>;
  inc: Map<string, EdgeSpec[]>;
  reachable: Set<string>;
  sources: NodeSpec[];
}

function buildCtx(nodes: NodeSpec[], edges: EdgeSpec[], catalog: Catalog | null): Ctx {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const cfg = new Map<string, NodeConfig>();
  const kind = new Map<string, Kind>();
  for (const n of nodes) {
    const comp = catalog?.components.find((c) => c.type === n.type);
    cfg.set(n.id, { ...(comp?.defaultConfig ?? {}), ...(n.config ?? {}) });
    kind.set(n.id, kindOf(n.type, comp?.category));
  }
  const out = new Map<string, EdgeSpec[]>();
  const inc = new Map<string, EdgeSpec[]>();
  for (const e of edges) {
    if (!byId.has(e.source) || !byId.has(e.target)) continue;
    out.set(e.source, [...(out.get(e.source) ?? []), e]);
    inc.set(e.target, [...(inc.get(e.target) ?? []), e]);
  }
  const sources = nodes.filter((n) => kind.get(n.id) === "traffic");
  const reachable = new Set<string>();
  const stack = sources.map((s) => s.id);
  while (stack.length) {
    const id = stack.pop()!;
    if (reachable.has(id)) continue;
    reachable.add(id);
    for (const e of out.get(id) ?? []) stack.push(e.target);
  }
  return { nodes, edges, byId, cfg, kind, out, inc, reachable, sources };
}

const label = (c: Ctx, id: string) => {
  const n = c.byId.get(id);
  return n?.label || id;
};

/** Total connections a database accepts across shards and replicas. */
function dbConnections(cfg: NodeConfig): number {
  const conns = num(cfg.maxConnections) ?? 10;
  const shards = Math.max(1, num(cfg.shards) ?? 1);
  const copies = Math.max(1 + (num(cfg.readReplicas) ?? 0), num(cfg.replicationFactor) ?? 1);
  return conns * shards * copies;
}

/** Most workers a service can run concurrently (at max scale). */
function serviceWorkers(cfg: NodeConfig): number {
  const per = num(cfg.workersPerReplica) ?? 4;
  const replicas = cfg.autoscaling === true ? Math.max(num(cfg.maxReplicas) ?? 0, num(cfg.replicas) ?? 1) : (num(cfg.replicas) ?? 1);
  return per * replicas;
}

/** Worst-case time a node may spend waiting on its own downstream calls (explicit timeouts only). */
function downstreamBudget(c: Ctx, id: string): { ms: number; via: EdgeSpec } | null {
  let best: { ms: number; via: EdgeSpec } | null = null;
  for (const e of c.out.get(id) ?? []) {
    const t = num(e.config.timeoutMs);
    if (t == null) continue;
    const attempts = Math.max(1, num(e.config.maxAttempts) ?? 1);
    const backoff = num(e.config.retryBackoffMs) ?? 0;
    let ms = t * attempts;
    for (let i = 0; i < attempts - 1; i++) ms += backoff * 2 ** i;
    if (!best || ms > best.ms) best = { ms, via: e };
  }
  return best;
}

export function reviewDesign(nodes: NodeSpec[], edges: EdgeSpec[], catalog: Catalog | null, workload: Workload): ReviewFinding[] {
  const c = buildCtx(nodes, edges, catalog);
  const out: ReviewFinding[] = [];
  const add = (f: Omit<ReviewFinding, "id">) => {
    const id = `${f.rule}:${f.nodeIds.join("+")}|${f.edgeIds.join("+")}`;
    if (!out.some((x) => x.id === id)) out.push({ ...f, id });
  };

  for (const n of nodes) {
    const cfg = c.cfg.get(n.id)!;
    const k = c.kind.get(n.id)!;
    const name = label(c, n.id);
    const onPath = c.reachable.has(n.id);

    // -- single points of failure ------------------------------------------------------------
    if (k === "compute") {
      const replicas = num(cfg.replicas) ?? 1;
      const serverless = cfg.platform === "serverless";
      const scaledMin = cfg.autoscaling === true ? (num(cfg.minReplicas) ?? replicas) : replicas;
      if (!serverless && scaledMin <= 1) {
        add({
          severity: onPath ? "high" : "medium",
          rule: "spof",
          title: `${name} runs a single replica`,
          detail: `One crash, deploy or host failure takes down every request that passes through ${name}${cfg.autoscaling === true ? " (autoscaling minimum is 1)" : ""}.`,
          suggestion: "Run at least 2 replicas (3 across zones for critical paths) behind the load balancer.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }
    if (k === "database") {
      const replicas = num(cfg.readReplicas) ?? 0;
      const rf = num(cfg.replicationFactor) ?? 1;
      const shards = Math.max(1, num(cfg.shards) ?? 1);
      if (replicas === 0 && rf <= 1 && onPath) {
        add({
          severity: "medium",
          rule: "spof",
          title: `${name} has no replica`,
          detail: `A primary failure is an outage for every read and write that reaches it${shards > 1 ? ` (each of the ${shards} shards has a single copy)` : ""}.`,
          suggestion: "Add a standby or read replicas (relational), a replica set (document stores) or replication factor 3 (wide-column).",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }
    if (k === "network" && onPath && !MANAGED_LB.has(String(cfg.technology ?? ""))) {
      add({
        severity: "low",
        rule: "spof",
        title: `${name} is a single instance`,
        detail: `${name} is modelled as one self-hosted instance; if it stops, nothing behind it is reachable.`,
        suggestion: "Run two instances with failover (floating IP, DNS) or use a managed load balancer.",
        nodeIds: [n.id],
        edgeIds: [],
      });
    }
    if (k === "messaging" && onPath && !MANAGED_MQ.has(String(cfg.technology ?? "")) && (num(cfg.brokers) ?? 1) <= 1) {
      add({
        severity: "medium",
        rule: "spof",
        title: `${name} runs on one broker`,
        detail: `Producers cannot publish while the only broker of ${name} is down, so the asynchronous path stops with it.`,
        suggestion: "Run 3 brokers with replicated partitions/queues, or use a managed queue.",
        nodeIds: [n.id],
        edgeIds: [],
      });
    }

    // -- cache as a hard dependency on the read path ------------------------------------------
    if (k === "compute") {
      const reads = (c.out.get(n.id) ?? []).filter((e) => e.config.requestTypes?.includes("read"));
      const viaCache = reads.filter((e) => c.kind.get(e.target) === "cache");
      const direct = reads.filter((e) => c.kind.get(e.target) !== "cache");
      if (viaCache.length > 0 && direct.length === 0) {
        const cacheName = label(c, viaCache[0].target);
        add({
          severity: "medium",
          rule: "cache-dependency",
          title: `Reads fail when ${cacheName} is down`,
          detail: `Every read from ${name} goes through ${cacheName}; misses continue to the data store, but if the cache itself is unavailable reads have no other path.`,
          suggestion: "Let reads degrade to the data store when the cache errors (with a circuit breaker so the store is not flooded), or run the cache with replicas.",
          nodeIds: [n.id, viaCache[0].target],
          edgeIds: viaCache.map((e) => e.id),
        });
      }
    }

    // -- unbounded waits ----------------------------------------------------------------------
    if ((k === "compute" || k === "database") && cfg.platform !== "serverless") {
      const cap = num(cfg.queueCapacity);
      if ("queueTimeoutMs" in cfg && cfg.queueTimeoutMs == null && (cap ?? 1) > 0) {
        add({
          severity: "low",
          rule: "unbounded-wait",
          title: `${name} lets requests wait forever`,
          detail: `${name} has no ${k === "database" ? "connection acquire" : "queue"} timeout, so up to ${fmtInt(cap ?? 0)} requests${k === "compute" ? " per replica" : ""} can wait with no limit while clients have long given up.`,
          suggestion: "Set a queue timeout below the caller's timeout so stale work is shed early.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }

    // -- autoscaling sanity -------------------------------------------------------------------
    if (k === "compute" && cfg.autoscaling === true && cfg.platform !== "serverless") {
      const min = num(cfg.minReplicas) ?? 1;
      const max = num(cfg.maxReplicas) ?? min;
      const target = num(cfg.targetUtilization);
      if (max <= min) {
        add({
          severity: "medium",
          rule: "autoscaling-range",
          title: `${name} cannot scale out`,
          detail: `Autoscaling is on but the maximum (${max}) is not above the minimum (${min}), so no capacity can be added under load.`,
          suggestion: "Raise maxReplicas to cover the expected peak (for example 3× the baseline).",
          nodeIds: [n.id],
          edgeIds: [],
        });
      } else if (target != null && target > 0.85) {
        add({
          severity: "low",
          rule: "autoscaling-range",
          title: `${name} scales late`,
          detail: `Target utilization ${Math.round(target * 100)}% leaves little headroom while new replicas start (${num(cfg.startupS) ?? "?"} s); a spike saturates the existing ones first.`,
          suggestion: "Target 50–70% utilization for latency-sensitive services, or keep warm spare capacity.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }

    // -- data store specifics -----------------------------------------------------------------
    if (k === "database") {
      const shards = Math.max(1, num(cfg.shards) ?? 1);
      if (shards > 1 && workload.keySkew >= 0.8) {
        add({
          severity: "medium",
          rule: "hot-partition",
          title: `${name} may have a hot shard`,
          detail: `Key popularity follows Zipf s=${workload.keySkew}: the most popular keys land on a few of the ${shards} shards, so the hottest shard can get several times its fair ${(100 / shards).toFixed(1)}% share. Simulate to measure it.`,
          suggestion: "Pick a higher-cardinality partition key, cache hot keys, or split hot keys across partitions.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
      if (cfg.consistency === "all" && (num(cfg.replicationFactor) ?? 1) > 1) {
        add({
          severity: "low",
          rule: "consistency-all",
          title: `${name} uses consistency ALL`,
          detail: `Every operation waits for all ${num(cfg.replicationFactor)} replicas: latency follows the slowest one and a single replica outage fails the request.`,
          suggestion: "Use QUORUM for both reads and writes to keep strong consistency with one replica down.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }

      // pool exhaustion / connection storm from upstream services
      const conns = dbConnections(cfg);
      let workers = 0;
      const blockers: string[] = [];
      for (const e of c.inc.get(n.id) ?? []) {
        const up = c.cfg.get(e.source)!;
        if (c.kind.get(e.source) !== "compute") continue;
        if (up.platform === "serverless") {
          const limit = num(up.concurrencyLimit) ?? 1000;
          if (limit > conns) {
            add({
              severity: "high",
              rule: "connection-storm",
              title: `${label(c, e.source)} can open ${fmtInt(limit)} connections to ${name}`,
              detail: `Up to ${fmtInt(limit)} concurrent function instances may each hold a connection, but ${name} accepts ${fmtInt(conns)}. A traffic spike turns into connection errors and lock contention.`,
              suggestion: "Put a connection pooler or proxy in front (PgBouncer, RDS Proxy-style), or cap function concurrency.",
              nodeIds: [e.source, n.id],
              edgeIds: [e.id],
            });
          }
          continue;
        }
        if (up.blockingDownstream === false) continue;
        workers += serviceWorkers(up);
        blockers.push(e.source);
      }
      if (workers > conns) {
        add({
          severity: workers > 2 * conns ? "medium" : "low",
          rule: "pool-exhaustion",
          title: `${name} has fewer connections than callers`,
          detail: `Up to ${fmtInt(workers)} blocking workers (${blockers.map((b) => label(c, b)).join(", ")}) can wait on ${name}, which accepts ${fmtInt(conns)} connections. When it slows down, ${fmtInt(workers - conns)} requests queue for a connection${num(cfg.queueTimeoutMs) != null ? ` and fail after ${fmtMs(num(cfg.queueTimeoutMs)!)}` : ""}.`,
          suggestion: "Size application pools to the database's real concurrency, add a pooler, or cap service concurrency.",
          nodeIds: [...blockers, n.id],
          edgeIds: (c.inc.get(n.id) ?? []).filter((e) => blockers.includes(e.source)).map((e) => e.id),
        });
      }
    }

    if (k === "cache") {
      const ratio = num(cfg.hitRatio);
      if ((cfg.hitModel ?? "fixed_ratio") === "fixed_ratio" && ratio != null && ratio > 0.98) {
        add({
          severity: "low",
          rule: "optimistic-cache",
          title: `${name} assumes a ${(ratio * 100).toFixed(1)}% hit ratio`,
          detail: "Hit ratios this high are rare outside static content; the data store behind it is sized for the misses you assume.",
          suggestion: "Use the measured hit ratio, or the TTL + keyspace model, and test a cold-cache scenario.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }

    if (k === "messaging") {
      if ("maxDepth" in cfg && cfg.maxDepth == null) {
        add({
          severity: "medium",
          rule: "queue-unbounded",
          title: `${name} has an unbounded backlog`,
          detail: `If consumers fall behind or a downstream dependency is down, ${name} keeps accepting messages without limit (memory, disk and recovery time grow).`,
          suggestion: "Set a maximum depth or retention and alert on consumer lag; decide what producers do when it is full.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
      if ((c.out.get(n.id) ?? []).length === 0) {
        add({
          severity: "low",
          rule: "queue-sink",
          title: `${name} has no downstream link`,
          detail: "Consumers process messages but call nothing, so the asynchronous work is not modelled beyond the queue.",
          suggestion: "Link the queue's output to the service or store its consumers write to.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
      const partitions = num(cfg.partitions);
      const consumers = num(cfg.consumers);
      if (partitions != null && consumers != null && consumers > partitions) {
        add({
          severity: "low",
          rule: "queue-partitions",
          title: `${consumers - partitions} consumers of ${name} sit idle`,
          detail: `Only one consumer per partition is active: ${partitions} partitions cap parallelism at ${partitions} although ${consumers} consumers run.`,
          suggestion: "Add partitions or reduce consumers.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }

    if (k === "external") {
      const errorRate = num(cfg.errorRate) ?? 0;
      const callers = (c.inc.get(n.id) ?? []).filter((e) => (num(e.config.maxAttempts) ?? 1) <= 1);
      if (errorRate >= 0.01 && callers.length > 0) {
        add({
          severity: "low",
          rule: "external-errors",
          title: `${(errorRate * 100).toFixed(1)}% of calls to ${name} fail`,
          detail: `${callers.map((e) => label(c, e.source)).join(", ")} call ${name} once with no retry, so every transient upstream error becomes a user-visible failure.`,
          suggestion: "Add one retry with backoff and jitter for idempotent calls, or a fallback/queue for non-critical calls.",
          nodeIds: [n.id],
          edgeIds: callers.map((e) => e.id),
        });
      }
    }
  }

  // -- missing timeouts (grouped per caller) ----------------------------------------------------
  const timeoutSeverity: Partial<Record<Kind, ReviewSeverity>> = { external: "high", database: "medium", compute: "medium", messaging: "low", cache: "low" };
  for (const n of nodes) {
    const k = c.kind.get(n.id);
    if (k === "traffic") continue;
    const missing = (c.out.get(n.id) ?? []).filter((e) => num(e.config.timeoutMs) == null && timeoutSeverity[c.kind.get(e.target)!]);
    if (!missing.length) continue;
    const sev = missing
      .map((e) => (k === "network" ? "low" : timeoutSeverity[c.kind.get(e.target)!]!))
      .sort((a, b) => SEV_ORDER[a] - SEV_ORDER[b])[0];
    const names = missing.map((e) => label(c, e.target));
    add({
      severity: sev,
      rule: "missing-timeout",
      title: `${label(c, n.id)} waits indefinitely on ${names.length === 1 ? names[0] : `${names.length} dependencies`}`,
      detail: `No timeout on ${names.join(", ")}: a slow or hung dependency keeps ${label(c, n.id)}'s workers waiting until its pool fills and requests queue behind them.`,
      suggestion: "Set per-call timeouts a little above each dependency's p99 (for example 2× p99), keeping their sum inside the caller's own budget.",
      nodeIds: [n.id, ...missing.map((e) => e.target)],
      edgeIds: missing.map((e) => e.id),
    });
  }

  // -- retries without backoff -----------------------------------------------------------------
  for (const e of edges) {
    if ((num(e.config.maxAttempts) ?? 1) > 1 && (num(e.config.retryBackoffMs) ?? 0) === 0) {
      add({
        severity: "medium",
        rule: "retry-no-backoff",
        title: `${label(c, e.source)} retries ${label(c, e.target)} immediately`,
        detail: `${num(e.config.maxAttempts)} attempts with 0 ms backoff: when ${label(c, e.target)} struggles, retries arrive at once and add load exactly when it can least absorb it.`,
        suggestion: "Use exponential backoff with jitter (e.g. 50–100 ms base) and a retry budget.",
        nodeIds: [e.source, e.target],
        edgeIds: [e.id],
      });
    }
  }
  for (const s of c.sources) {
    const cfg = c.cfg.get(s.id)!;
    if ((num(cfg.maxAttempts) ?? 1) > 1 && (num(cfg.retryBackoffMs) ?? 0) === 0) {
      add({
        severity: "medium",
        rule: "retry-no-backoff",
        title: `${label(c, s.id)} retry without backoff`,
        detail: `Clients retry ${num(cfg.maxAttempts)} times immediately after a failure or timeout, multiplying load during an incident.`,
        suggestion: "Add exponential backoff with jitter on the client.",
        nodeIds: [s.id],
        edgeIds: [],
      });
    }
  }

  // -- retry amplification and long chains (path walk from each traffic source) ------------------
  const worst = new Map<string, { product: number; factors: { label: string; n: number }[]; path: string[]; edges: string[] }>();
  let deepest: { path: string[]; edges: string[] } | null = null;
  const walk = (id: string, product: number, factors: { label: string; n: number }[], path: string[], via: string[]) => {
    const prev = worst.get(id);
    if (path.length > 1 && (!prev || product > prev.product)) worst.set(id, { product, factors, path, edges: via });
    if (!deepest || path.length > deepest.path.length) deepest = { path, edges: via };
    if (path.length > 14) return;
    for (const e of c.out.get(id) ?? []) {
      if (path.includes(e.target)) continue;
      const n = Math.max(1, num(e.config.maxAttempts) ?? 1);
      walk(e.target, product * n, n > 1 ? [...factors, { label: `${label(c, e.source)}→${label(c, e.target)}`, n }] : factors, [...path, e.target], [...via, e.id]);
    }
  };
  for (const s of c.sources) {
    const n = Math.max(1, num(c.cfg.get(s.id)!.maxAttempts) ?? 1);
    walk(s.id, n, n > 1 ? [{ label: `${label(c, s.id)} (client)`, n }] : [], [s.id], []);
  }
  // Nodes behind the last retrying hop inherit the same multiplier: report each distinct chain of
  // retries once, at the node where it lands (the closest one).
  const bySignature = new Map<string, [string, NonNullable<ReturnType<typeof worst.get>>]>();
  for (const [target, w] of worst) {
    if (w.product < 4) continue;
    const sig = w.factors.map((f) => f.label).join("|");
    const prev = bySignature.get(sig);
    if (!prev || w.path.length < prev[1].path.length) bySignature.set(sig, [target, w]);
  }
  const amplified = [...bySignature.values()].sort((a, b) => b[1].product - a[1].product);
  for (const [target, w] of amplified.slice(0, 3)) {
    const math = w.factors.map((f) => `${f.n}`).join(" × ");
    add({
      severity: w.product >= 9 ? "high" : "medium",
      rule: "retry-amplification",
      title: `Up to ${w.product} attempts reach ${label(c, target)} per request`,
      detail: `Retries multiply along the path: ${math} = ${w.product} (${w.factors.map((f) => `${f.label} ×${f.n}`).join(", ")}). When ${label(c, target)} is failing, it receives ${w.product}× the traffic exactly when it is weakest.`,
      suggestion: "Retry at one layer only (usually the outermost), use a retry budget, and fail fast below it.",
      nodeIds: w.path,
      edgeIds: w.edges,
    });
  }
  const deep = deepest as { path: string[]; edges: string[] } | null;
  if (deep && deep.path.length >= 6) {
    add({
      severity: "low",
      rule: "deep-chain",
      title: `${deep.path.length - 1} synchronous hops on one request path`,
      detail: `${deep.path.map((id) => label(c, id)).join(" → ")}: every hop adds latency and its own failure probability to the request.`,
      suggestion: "Collapse hops, call independent dependencies in parallel, or move non-critical work to a queue.",
      nodeIds: deep.path,
      edgeIds: deep.edges,
    });
  }

  // -- timeout inversion ------------------------------------------------------------------------
  for (const e of edges) {
    if (!c.byId.has(e.source) || !c.byId.has(e.target)) continue;
    const fromSource = c.kind.get(e.source) === "traffic";
    const callerTimeout = fromSource ? num(c.cfg.get(e.source)!.timeoutMs) : num(e.config.timeoutMs);
    if (callerTimeout == null) continue;
    const budget = downstreamBudget(c, e.target);
    if (!budget || callerTimeout >= budget.ms) continue;
    const attempts = Math.max(1, num(budget.via.config.maxAttempts) ?? 1);
    const t = num(budget.via.config.timeoutMs)!;
    const caller = fromSource ? `${label(c, e.source)} (client timeout)` : label(c, e.source);
    add({
      severity: "medium",
      rule: "timeout-inversion",
      title: `${label(c, e.source)} gives up before ${label(c, e.target)} does`,
      detail: `${caller} waits ${fmtMs(callerTimeout)} for ${label(c, e.target)}, but ${label(c, e.target)} may wait up to ${fmtMs(budget.ms)} on ${label(c, budget.via.target)}${attempts > 1 ? ` (${fmtMs(t)} × ${attempts} attempts + backoff)` : ""}: it keeps working on requests nobody is waiting for.`,
      suggestion: "Make timeouts shrink downstream (each caller waits longer than its callee's whole budget) or propagate deadlines.",
      nodeIds: [e.source, e.target, budget.via.target],
      edgeIds: [e.id, budget.via.id],
    });
  }

  // -- resource specs (only where set): CPU at the peak load, memory against the data -----------
  const loads = estimateLoad(nodes, edges, catalog, workload);
  const peak = peakRps(workload);
  const msOf = (v: unknown) => (isDistribution(v) ? meanOf(v) : null);
  const pct = (x: number) => `${Math.round(x * 100)}%`;
  for (const n of nodes) {
    const cfg = c.cfg.get(n.id)!;
    const name = label(c, n.id);
    const cores = num(cfg.cpuCores);
    const l = loads.get(n.id);
    if (cores != null && l && l.read + l.write > 0) {
      // CPU work per second at the peak, in cores, on the busiest instance
      let busy: { util: number; what: string } | null = null;
      if (n.type === "service" && cfg.platform !== "serverless") {
        const ms = msOf(cfg.serviceTimeMs);
        const replicas = cfg.autoscaling === true ? Math.max(num(cfg.maxReplicas) ?? 1, num(cfg.replicas) ?? 1) : Math.max(1, num(cfg.replicas) ?? 1);
        if (ms) {
          const cap = (cores * replicas * 1000) / ms;
          busy = { util: (l.read + l.write) / cap, what: `${cores} vCPU × ${replicas} replica${replicas === 1 ? "" : "s"} at ${ms.toFixed(1)} ms of CPU work per request handle about ${fmtInt(cap)} req/s` };
        }
      } else if (n.type === "database") {
        const read = msOf(cfg.readServiceTimeMs) ?? 0;
        const write = msOf(cfg.writeServiceTimeMs) ?? 0;
        const share = num(cfg.cpuShare) ?? 0.5;
        const shards = Math.max(1, num(cfg.shards) ?? 1);
        const copies = 1 + Math.max(0, num(cfg.readReplicas) ?? 0);
        // the primary of each shard takes its share of reads plus every write
        const primaryCores = ((l.read / copies) * read + l.write * write) * share / 1000 / shards;
        busy = { util: primaryCores / cores, what: `each primary needs about ${primaryCores.toFixed(1)} of its ${cores} vCPU (${pct(share)} of ${read.toFixed(1)} ms reads and ${write.toFixed(1)} ms writes)` };
      } else if (n.type === "cache") {
        const ms = msOf(cfg.serviceTimeMs);
        if (ms) {
          const need = ((l.read + l.write) * ms) / 1000;
          busy = { util: need / cores, what: `commands need about ${need.toFixed(1)} of its ${cores} vCPU` };
        }
      }
      if (busy && busy.util >= 0.8) {
        const over = busy.util >= 1;
        add({
          severity: over ? "high" : "medium",
          rule: "cpu-capacity",
          title: over ? `${name} needs more CPU at peak` : `${name} has little CPU headroom`,
          detail: `At the ${fmtInt(peak)} req/s peak about ${fmtInt(l.read + l.write)} req/s reach ${name}; ${busy.what}, so its CPU would be ${pct(busy.util)} busy${over ? " and requests queue for a core" : ""}. This is a static estimate (first attempts, fixed hit ratios); a run measures it.`,
          suggestion: "Give it more CPU cores or replicas, or reduce the CPU work per request.",
          nodeIds: [n.id],
          edgeIds: [],
        });
      }
    }
    if (n.type === "database") {
      const mem = num(cfg.memoryGib);
      const ws = num(cfg.workingSetGib);
      if (mem != null && ws != null) {
        const shards = Math.max(1, num(cfg.shards) ?? 1);
        const hit = Math.min(1, (0.75 * mem) / (ws / shards));
        if (hit < 0.9) {
          add({
            severity: hit < 0.5 ? "medium" : "low",
            rule: "buffer-cache",
            title: `${name}'s hot data does not fit in memory`,
            detail: `75% of ${mem} GiB is buffer cache for about ${+(ws / shards).toFixed(1)} GiB of hot data per instance, so about ${pct(1 - hit)} of reads go to disk.`,
            suggestion: "Give each instance more memory, shard the data, or cache the hottest reads in front.",
            nodeIds: [n.id],
            edgeIds: [],
          });
        }
      }
    }
    if (n.type === "cache" && (cfg.hitModel ?? "fixed_ratio") === "ttl_keyspace") {
      const mem = num(cfg.memoryGib);
      if (mem != null) {
        const keys = Math.max(1, Math.floor((0.8 * mem * 1024 * 1024) / (num(cfg.itemSizeKb) ?? 1)));
        if (keys < workload.keyCount) {
          add({
            severity: "low",
            rule: "cache-capacity",
            title: `${name} holds ${fmtInt(keys)} of ${fmtInt(workload.keyCount)} keys`,
            detail: `80% of ${mem} GiB fits about ${fmtInt(keys)} items of ${num(cfg.itemSizeKb) ?? 1} KiB; the least recently used keys are evicted, so expect a lower hit ratio than with unbounded memory.`,
            suggestion: "Add memory if the hit ratio matters, or shorten what you cache.",
            nodeIds: [n.id],
            edgeIds: [],
          });
        }
      }
    }
  }

  // -- rate limit at the edge ------------------------------------------------------------------
  if (c.sources.length > 0) {
    const limited = nodes.some((n) => c.reachable.has(n.id) && (num(c.cfg.get(n.id)!.rateLimitRps) ?? 0) > 0 && c.kind.get(n.id) === "network");
    if (!limited) {
      const firstHops = c.sources.flatMap((s) => (c.out.get(s.id) ?? []).map((e) => e.target));
      add({
        severity: "low",
        rule: "edge-rate-limit",
        title: "No rate limit at the edge",
        detail: "Nothing in front of the system caps traffic, so a spike, a retry storm or a bot flood reaches every tier at full force.",
        suggestion: "Add a rate limit on the gateway or load balancer sized to the capacity you have measured.",
        nodeIds: firstHops,
        edgeIds: [],
      });
    }
  }

  return out.sort((a, b) => SEV_ORDER[a.severity] - SEV_ORDER[b.severity] || a.rule.localeCompare(b.rule) || a.title.localeCompare(b.title));
}

export function countBySeverity(findings: ReviewFinding[]): Record<ReviewSeverity, number> {
  const r: Record<ReviewSeverity, number> = { high: 0, medium: 0, low: 0 };
  for (const f of findings) r[f.severity]++;
  return r;
}
