/**
 * Auto-sizing: the CPU, memory and storage each part needs for the design's own peak load.
 *
 * In the web app an empty spec means "Auto": the size worked out here is shown on the part and
 * filled into the document whenever it is simulated, priced or exported. A value typed into a
 * spec replaces it. The rules follow what the engine does with each spec
 * (docs/simulation-model.md, "Resource specs"), so an auto-sized part is not the bottleneck at
 * the peak it was sized for:
 *
 * - CPU: the CPU work per second at the peak (from the static load estimate) at a 60% target,
 *   rounded up to a common size, at least 1 vCPU (below one core the engine slows every request).
 * - Memory: what the engine's memory model needs (requests held, buffer cache, keys) plus 25%.
 * - Storage: the data per instance kept under 70% full.
 *
 * A few ratios have no measured basis in the model (proxy throughput per vCPU, broker memory per
 * vCPU, database memory without a working set); they are marked illustrative in the explanation.
 */
import { isDistribution, meanOf } from "./distributions";
import { estimateLoad, peakRps } from "./load";
import type { ArchitectureDocument, Catalog, ComponentType, NodeConfig, Workload } from "./types";

/** CPU utilization a part is sized for at the peak. */
export const TARGET_CPU = 0.6;
export const SPEC_KEYS = ["cpuCores", "memoryGib", "storageGib"] as const;
export type SpecKey = (typeof SPEC_KEYS)[number];

const CPU_STEPS = [1, 2, 4, 8, 16, 32, 48, 64, 96, 128, 192, 256, 384, 512];
const MEM_STEPS = [0.5, 1, 2, 4, 8, 16, 32, 64, 128, 256, 384, 512, 768, 1024, 1536, 2048];
const DISK_STEPS = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000];
/** Illustrative: requests per second an L7 proxy handles comfortably on one vCPU. */
const PROXY_RPS_PER_VCPU = 5000;
/** Illustrative: messages per second a broker handles comfortably on one vCPU. */
const BROKER_MSGS_PER_VCPU = 10000;
/** Kafka's default retention (log.retention.hours = 168). */
const LOG_RETENTION_S = 7 * 24 * 3600;
const LOG_BROKERS = new Set(["kafka", "redis_streams"]);
/** Largest auto memory for one service replica (a huge queue would otherwise ask for more). */
const MAX_SERVICE_MEMORY_GIB = 64;
/** Managed services you do not size yourself. */
const MANAGED = new Set(["aws_alb", "api_gateway", "cdn", "dynamodb", "sqs", "pubsub"]);

export interface PartSize {
  cpuCores: number | null;
  memoryGib: number | null;
  storageGib: number | null;
  /** Peak requests per second reaching the part (static estimate). */
  peakRps: number;
  /** How each spec was worked out, in one line. */
  why: Partial<Record<SpecKey, string>>;
  /** Set when the part is not sized (managed service, unknown load). */
  skipped?: string;
}

const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const msOf = (v: unknown): number => (isDistribution(v) ? meanOf(v) : 0);

/** A setting's value, resolving "auto" or empty through the technology's defaults (as the engine does). */
function setting(comp: ComponentType, cfg: NodeConfig, key: string): unknown {
  const own = cfg[key];
  if (own != null && own !== "auto") return own;
  const tech = typeof cfg.technology === "string" ? cfg.technology : "";
  return comp.technologyDefaults?.[key]?.[tech] ?? null;
}

function stepUp(x: number, steps: number[]): number {
  for (const s of steps) if (s >= x - 1e-9) return s;
  const last = steps[steps.length - 1];
  return Math.ceil(x / last) * last;
}

/** Short number for explanations: 1,234 · 12.3 · 0.42. */
export function fmt(v: number): string {
  if (!Number.isFinite(v)) return "–";
  if (Math.abs(v) >= 100) return Math.round(v).toLocaleString("en-US");
  if (Math.abs(v) >= 10) return String(Math.round(v * 10) / 10);
  return String(Math.round(v * 100) / 100);
}
const gib = (v: number) => (v < 1 ? `${Math.round(v * 1024)} MiB` : `${fmt(v)} GiB`);
const pct = (v: number) => `${Math.round(v * 100)}%`;

function cpuFor(busyCores: number, intro: string): { cpu: number; why: string } {
  const cpu = Math.min(512, stepUp(Math.max(busyCores / TARGET_CPU, 0), CPU_STEPS));
  return { cpu, why: `${intro} = ${fmt(busyCores)} cores busy; at a ${pct(TARGET_CPU)} target that is ${fmt(busyCores / TARGET_CPU)} → ${cpu} vCPU` };
}

function memFor(needGib: number, floor: number): number {
  return Math.min(24576, stepUp(Math.max(needGib, floor), MEM_STEPS));
}

function diskFor(needGib: number): number {
  return Math.min(1_000_000, stepUp(Math.max(needGib, 10), DISK_STEPS));
}

function sizeService(comp: ComponentType, cfg: NodeConfig, l: number): Omit<PartSize, "peakRps"> {
  const baseline = num(cfg.memoryBaselineMb) ?? 256;
  const perReq = num(cfg.memoryPerRequestMb) ?? 2;
  if (cfg.platform === "serverless") {
    const mb = baseline + perReq;
    return {
      cpuCores: 1,
      memoryGib: memFor((mb * 1.25) / 1024, 0.5),
      storageGib: null,
      why: {
        cpuCores: "One request per instance: 1 vCPU runs it at full speed",
        memoryGib: `${fmt(baseline)} MiB base + 1 request × ${fmt(perReq)} MiB, +25% headroom`,
      },
    };
  }
  const autoscaling = cfg.autoscaling === true;
  const replicas = Math.max(1, autoscaling ? Math.max(num(cfg.maxReplicas) ?? 1, num(cfg.replicas) ?? 1) : (num(cfg.replicas) ?? 1));
  const workers = Math.max(1, num(cfg.workersPerReplica) ?? 4);
  const processes = Math.max(1, num(cfg.processesPerReplica) ?? 1);
  const eventLoop = setting(comp, cfg, "runtimeModel") === "event_loop";
  const handlers = eventLoop ? processes : workers * processes;
  const ms = msOf(cfg.serviceTimeMs);
  const perReplica = l / replicas;
  const sized = cpuFor(
    (perReplica * ms) / 1000,
    `${fmt(perReplica)} req/s per replica at peak (${fmt(l)} over ${replicas}${autoscaling ? " at full scale" : ""}) × ${fmt(ms)} ms of CPU each`,
  );
  let { cpu, why } = sized;
  if (cpu > handlers && handlers >= 1) {
    const capped = Math.max(1, [...CPU_STEPS].reverse().find((s) => s <= handlers) ?? 1);
    if (capped < cpu) {
      why = `${why}; capped at ${capped} because ${handlers} ${eventLoop ? "event loop" : "worker"}${handlers === 1 ? "" : "s"} cannot use more cores (add workers or replicas)`;
      cpu = capped;
    }
  }
  // memory for every request a replica can hold (working and a full queue), so it never refuses
  // for memory before its queue is full; a huge queue is capped at a large replica
  const queue = Math.max(0, num(cfg.queueCapacity) ?? 0);
  const held = workers * processes + queue;
  const mb = baseline + held * perReq;
  const wanted = memFor((mb * 1.25) / 1024, 0.5);
  const mem = Math.min(wanted, MAX_SERVICE_MEMORY_GIB);
  const memWhy = `${fmt(baseline)} MiB base + ${fmt(held)} requests held (${workers * processes} working, ${fmt(queue)} queued) × ${fmt(perReq)} MiB = ${gib(mb / 1024)}, +25% headroom`;
  return {
    cpuCores: cpu,
    memoryGib: mem,
    storageGib: null,
    why: {
      cpuCores: why,
      memoryGib:
        mem < wanted
          ? `${memWhy}; capped at ${MAX_SERVICE_MEMORY_GIB} GiB, so past about ${fmt(Math.floor((mem * 1024 - baseline) / Math.max(perReq, 1e-9)))} requests held it refuses (out of memory)`
          : memWhy,
    },
  };
}

function sizeDatabase(comp: ComponentType, cfg: NodeConfig, read: number, write: number, w: Workload): Omit<PartSize, "peakRps"> {
  const shards = Math.max(1, num(cfg.shards) ?? 1);
  const rf = Math.max(1, num(cfg.replicationFactor) ?? 1);
  const copies = 1 + Math.max(0, num(cfg.readReplicas) ?? 0);
  const readMs = msOf(cfg.readServiceTimeMs);
  const writeMs = msOf(cfg.writeServiceTimeMs);
  const share = num(cfg.cpuShare) ?? 0.5;
  let busy: number;
  let intro: string;
  if (rf > 1) {
    // a ring or replica set: every write goes to RF nodes, a read to as many as the consistency asks
    const k = cfg.consistency === "all" ? rf : cfg.consistency === "quorum" ? Math.floor(rf / 2) + 1 : 1;
    const nodes = shards * Math.max(rf, copies);
    busy = ((read * readMs * k + write * writeMs * rf) * share) / 1000 / nodes;
    intro = `${fmt(read)} reads/s × ${k} replica${k === 1 ? "" : "s"} × ${fmt(readMs)} ms + ${fmt(write)} writes/s × ${rf} replicas × ${fmt(writeMs)} ms, ${pct(share)} of it CPU, over ${nodes} nodes`;
  } else {
    // the primary of each shard takes its share of the reads plus every write; a scattered read
    // (Elasticsearch searches every shard) reaches every shard
    const scatter = shards > 1 ? Math.min(1, Math.max(0, num(setting(comp, cfg, "scatterReads")) ?? 0)) : 0;
    const readsPerShard = read * (scatter + (1 - scatter) / shards);
    busy = (((readsPerShard / copies) * readMs + (write / shards) * writeMs) * share) / 1000;
    intro = `Each primary: ${fmt(readsPerShard / copies)} reads/s${scatter > 0 ? ` (${scatter >= 1 ? "every read searches all" : `${pct(scatter)} of reads search all`} ${shards} shards)` : ""} × ${fmt(readMs)} ms + ${fmt(write / shards)} writes/s × ${fmt(writeMs)} ms, ${pct(share)} of it CPU`;
  }
  const { cpu, why } = cpuFor(busy, intro);
  const ws = num(cfg.workingSetGib);
  let mem: number;
  let memWhy: string;
  if (ws != null) {
    const per = ws / shards;
    mem = memFor(per / 0.75, 2);
    memWhy = `${gib(per)} of hot data per instance in a buffer cache of 75% of memory, so reads stay in memory`;
  } else {
    mem = memFor(4 * cpu, 2);
    memWhy = "4 GiB per vCPU, a common database ratio (illustrative); set a hot working set to size the buffer cache";
  }
  const given = num(cfg.dataSizeGib);
  const itemKb = num(cfg.itemSizeKb) ?? 1;
  const data = given ?? (w.keyCount * itemKb * 1000) / 2 ** 30;
  const perInstance = data / shards;
  const disk = diskFor(perInstance / 0.7);
  return {
    cpuCores: cpu,
    memoryGib: mem,
    storageGib: disk,
    why: {
      cpuCores: why,
      memoryGib: memWhy,
      storageGib: `${given != null ? `${gib(data)} of data` : `${fmt(w.keyCount)} keys × ${fmt(itemKb)} KB ≈ ${gib(data)} (set the data size for a better estimate)`}${shards > 1 ? ` over ${shards} shards` : ""}, kept under 70% full (at least 10 GiB)`,
    },
  };
}

function sizeCache(cfg: NodeConfig, l: number, w: Workload): Omit<PartSize, "peakRps"> {
  const shards = Math.max(1, num(cfg.shards) ?? 1);
  const conc = Math.max(1, num(cfg.concurrency) ?? 1);
  const ms = msOf(cfg.serviceTimeMs);
  const perShard = l / shards;
  let { cpu, why } = cpuFor((perShard * ms) / 1000, `${fmt(perShard)} commands/s per node × ${fmt(ms)} ms`);
  if (cpu > conc) {
    const capped = Math.max(1, [...CPU_STEPS].reverse().find((s) => s <= conc) ?? 1);
    if (capped < cpu) {
      why = `${why}; capped at ${capped} because it runs ${conc} command${conc === 1 ? "" : "s"} at a time (add shards)`;
      cpu = capped;
    }
  }
  const itemKb = num(cfg.itemSizeKb) ?? 1;
  const dataGib = (w.keyCount * itemKb) / shards / 1024 / 1024;
  return {
    cpuCores: cpu,
    memoryGib: memFor((dataGib / 0.8) * 1.25, 0.5),
    storageGib: null,
    why: {
      cpuCores: why,
      memoryGib: `${fmt(w.keyCount)} keys × ${fmt(itemKb)} KiB${shards > 1 ? ` over ${shards} shards` : ""} = ${gib(dataGib)} per node; 80% of memory holds keys, +25% headroom`,
    },
  };
}

function sizeBalancer(l: number): Omit<PartSize, "peakRps"> {
  const cpu = Math.min(512, stepUp(Math.max(1, l / PROXY_RPS_PER_VCPU), CPU_STEPS));
  return {
    cpuCores: cpu,
    memoryGib: memFor(cpu, 1),
    storageGib: null,
    why: {
      cpuCores: `${fmt(l)} req/s at peak at about ${fmt(PROXY_RPS_PER_VCPU)} req/s per vCPU (illustrative)`,
      memoryGib: "1 GiB per vCPU: a proxy keeps little state (illustrative)",
    },
  };
}

function sizeQueue(cfg: NodeConfig, l: number, w: Workload): Omit<PartSize, "peakRps"> {
  const brokers = Math.max(1, num(cfg.brokers) ?? 1);
  const cpu = Math.min(512, stepUp(Math.max(1, l / brokers / BROKER_MSGS_PER_VCPU), CPU_STEPS));
  const msgKb = num(cfg.messageSizeKb) ?? 1;
  const log = LOG_BROKERS.has(String(cfg.technology));
  const peak = peakRps(w);
  // a log keeps messages for its retention at the usual rate; other brokers delete on ack and
  // only need room for a backlog (an hour of peak publishes)
  const rate = log ? l * (peak > 0 ? Math.min(1, w.rps / peak) : 1) : l;
  const seconds = log ? LOG_RETENTION_S : 3600;
  const copies = log ? Math.min(3, brokers) : 1;
  const gibStored = (rate * msgKb * 1024 * seconds * copies) / brokers / 2 ** 30;
  return {
    cpuCores: cpu,
    memoryGib: memFor(2 * cpu, 2),
    storageGib: diskFor(gibStored / 0.7),
    why: {
      cpuCores: `${fmt(l / brokers)} messages/s per broker at peak at about ${fmt(BROKER_MSGS_PER_VCPU)} per vCPU (illustrative)`,
      memoryGib: "2 GiB per vCPU for buffers and page cache (illustrative)",
      storageGib: log
        ? `${fmt(rate)} messages/s × ${fmt(msgKb)} KiB kept 7 days (Kafka's default retention)${copies > 1 ? ` × ${copies} copies` : ""}${brokers > 1 ? ` over ${brokers} brokers` : ""} = ${gib(gibStored)}, kept under 70% full`
        : `An hour of peak publishes as a backlog buffer (${fmt(l)} messages/s × ${fmt(msgKb)} KiB${brokers > 1 ? ` over ${brokers} brokers` : ""}) = ${gib(gibStored)}, kept under 70% full`,
    },
  };
}

/** The auto size of every part that has resource specs. */
export function autoSizes(doc: ArchitectureDocument, catalog: Catalog | null): Map<string, PartSize> {
  const { nodes, edges } = doc.architecture;
  const w = doc.workload;
  const loads = estimateLoad(nodes, edges, catalog, w, { ttlMissShare: 1 });
  const out = new Map<string, PartSize>();
  for (const n of nodes) {
    const comp = catalog?.components.find((c) => c.type === n.type);
    if (!comp || !comp.fields.some((f) => (SPEC_KEYS as readonly string[]).includes(f.key))) continue;
    const cfg: NodeConfig = { ...(comp.defaultConfig ?? {}), ...(n.config ?? {}) };
    const load = loads.get(n.id);
    const read = load?.read ?? 0;
    const write = load?.write ?? 0;
    const l = read + write;
    const empty = { cpuCores: null, memoryGib: null, storageGib: null, peakRps: l, why: {} };
    if (MANAGED.has(String(cfg.technology))) {
      out.set(n.id, { ...empty, skipped: "A managed service: the provider sizes it." });
      continue;
    }
    if (load === null) {
      out.set(n.id, { ...empty, skipped: "Its load cannot be estimated before a run." });
      continue;
    }
    let s: Omit<PartSize, "peakRps"> | null = null;
    if (n.type === "service") s = sizeService(comp, cfg, l);
    else if (n.type === "database") s = sizeDatabase(comp, cfg, read, write, w);
    else if (n.type === "cache") s = sizeCache(cfg, l, w);
    else if (n.type === "load_balancer") s = sizeBalancer(l);
    else if (n.type === "message_queue") s = sizeQueue(cfg, l, w);
    if (!s) continue;
    // only specs the part has
    const has = (k: SpecKey) => comp.fields.some((f) => f.key === k);
    out.set(n.id, {
      cpuCores: has("cpuCores") ? s.cpuCores : null,
      memoryGib: has("memoryGib") ? s.memoryGib : null,
      storageGib: has("storageGib") ? s.storageGib : null,
      peakRps: l,
      why: Object.fromEntries(Object.entries(s.why).filter(([k]) => has(k as SpecKey))),
    });
  }
  return out;
}

/** The document with every empty spec filled with its auto size (what runs and exports use). */
export function applyAutoSizes(doc: ArchitectureDocument, catalog: Catalog | null): ArchitectureDocument {
  const sizes = autoSizes(doc, catalog);
  let changed = false;
  const nodes = doc.architecture.nodes.map((n) => {
    const s = sizes.get(n.id);
    if (!s) return n;
    const patch: NodeConfig = {};
    for (const k of SPEC_KEYS) {
      if (s[k] != null && num(n.config?.[k]) == null) patch[k] = s[k];
    }
    if (!Object.keys(patch).length) return n;
    changed = true;
    return { ...n, config: { ...n.config, ...patch } };
  });
  return changed ? { ...doc, architecture: { ...doc.architecture, nodes } } : doc;
}
