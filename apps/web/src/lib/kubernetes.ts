/**
 * Kubernetes on the canvas: which services run on which cluster, and what their pods ask for.
 * Mirrors the engine's rules (components/kubernetes.py: cluster_for, pod_request) so the design
 * view agrees with what a run will do.
 */

export const CLUSTER_TYPE = "kubernetes_cluster";

type Config = Record<string, unknown>;
interface PartLike {
  id: string;
  data: { type: string; label: string; config: Config };
}

const num = (v: unknown, fallback: number) => (typeof v === "number" && Number.isFinite(v) ? v : fallback);

export function isOnKubernetes(type: string, config: Config): boolean {
  return type === "service" && config.platform === "kubernetes";
}

/** The cluster a service runs on: the one it names, else the only cluster in the design. */
export function clusterOf(part: PartLike, clusterIds: string[]): string | null {
  if (!isOnKubernetes(part.data.type, part.data.config) || clusterIds.length === 0) return null;
  const named = part.data.config.cluster;
  if (typeof named === "string" && named) return clusterIds.includes(named) ? named : null;
  return clusterIds.length === 1 ? clusterIds[0] : null;
}

export function clusterIdsOf(parts: PartLike[]): string[] {
  return parts.filter((p) => p.data.type === CLUSTER_TYPE).map((p) => p.id);
}

/** Services whose pods run on the cluster. */
export function membersOf(parts: PartLike[], clusterId: string): PartLike[] {
  const ids = clusterIdsOf(parts);
  return parts.filter((p) => clusterOf(p, ids) === clusterId);
}

/** Pods a service starts with: its replicas, within the autoscaling range when autoscaling. */
export function startingPods(config: Config): number {
  const replicas = Math.max(0, Math.round(num(config.replicas, 1)));
  if (config.autoscaling !== true) return replicas;
  const lo = Math.round(num(config.minReplicas, 1));
  const hi = Math.round(num(config.maxReplicas, Math.max(lo, replicas)));
  return Math.min(Math.max(replicas, lo), hi);
}

/** CPU (vCPU) and memory (GiB) one pod requests: the service's size, else the cluster default. */
export function podRequest(service: Config, cluster: Config): { cpu: number; mem: number } {
  return {
    cpu: num(service.cpuCores, num(cluster.defaultCpuRequest, 0.5)),
    mem: num(service.memoryGib, num(cluster.defaultMemoryRequestGib, 0.5)),
  };
}

export interface ClusterShape {
  nodes: number;
  /** Allocatable vCPU and GiB per node (capacity minus what the system reserves). */
  allocCpu: number;
  allocMem: number;
}

export function clusterShape(cluster: Config): ClusterShape {
  const share = num(cluster.allocatableShare, 0.9);
  return {
    nodes: Math.max(0, Math.round(num(cluster.nodes, 3))),
    allocCpu: num(cluster.nodeVcpu, 4) * share,
    allocMem: num(cluster.nodeMemoryGib, 16) * share,
  };
}

export interface ClusterPlan {
  nodes: number;
  /** Pods bound to each node at the start (least-allocated first, as the scheduler does). */
  podsPerNode: number[];
  pending: number;
  /** Pending pods per service id. */
  pendingBy: Record<string, number>;
  pods: number;
  cpuRequested: number;
  cpuAllocatable: number;
}

/**
 * Where the starting pods land: each pod goes to the node with the most free CPU that fits it,
 * the rest wait as Pending. Same rule as the engine's scheduler at t = 0.
 */
export function planCluster(cluster: Config, members: PartLike[]): ClusterPlan {
  const shape = clusterShape(cluster);
  const free = Array.from({ length: shape.nodes }, () => ({ cpu: shape.allocCpu, mem: shape.allocMem, pods: 0 }));
  let pending = 0;
  const pendingBy: Record<string, number> = {};
  let pods = 0;
  let cpuRequested = 0;
  for (const m of members) {
    const req = podRequest(m.data.config, cluster);
    for (let i = 0; i < startingPods(m.data.config); i++) {
      pods++;
      let best = -1;
      for (let n = 0; n < free.length; n++) {
        const f = free[n];
        if (f.cpu + 1e-9 >= req.cpu && f.mem + 1e-9 >= req.mem && (best < 0 || f.cpu > free[best].cpu)) best = n;
      }
      if (best < 0) {
        pending++;
        pendingBy[m.id] = (pendingBy[m.id] ?? 0) + 1;
        continue;
      }
      free[best].cpu -= req.cpu;
      free[best].mem -= req.mem;
      free[best].pods++;
      cpuRequested += req.cpu;
    }
  }
  return {
    nodes: shape.nodes,
    podsPerNode: free.map((f) => f.pods),
    pending,
    pendingBy,
    pods,
    cpuRequested,
    cpuAllocatable: shape.nodes * shape.allocCpu,
  };
}

/** Spread `pods` over `nodes` as evenly as the least-allocated scheduler keeps them. */
export function spreadPods(pods: number, nodes: number): number[] {
  if (nodes <= 0) return [];
  const base = Math.floor(pods / nodes);
  const extra = pods - base * nodes;
  return Array.from({ length: nodes }, (_, i) => base + (i < extra ? 1 : 0));
}
