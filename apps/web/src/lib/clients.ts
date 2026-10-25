/**
 * Client groups: a design can have several client parts (Clients, Mobile app, Web browser, API
 * consumers). The workload is split between them by their traffic share, which is relative.
 */
import { isClientType } from "./theme";
import type { Catalog, NodeConfig } from "./types";

interface NodeLike {
  id: string;
  data: { type: string; config: NodeConfig };
}

export interface ClientShare {
  id: string;
  /** Fraction of the workload (0-1). */
  fraction: number;
}

/** Each client part's fraction of the workload, in document order. */
export function clientShares(nodes: NodeLike[], catalog: Catalog | null): ClientShare[] {
  const clients = nodes.filter((n) => isClientType(n.data.type, catalog));
  const weights = clients.map((n) => {
    const own = n.data.config.trafficShare;
    const fallback = catalog?.components.find((c) => c.type === n.data.type)?.defaultConfig.trafficShare;
    const w = typeof own === "number" ? own : typeof fallback === "number" ? fallback : 100;
    return w > 0 ? w : 0;
  });
  const total = weights.reduce((a, b) => a + b, 0);
  return clients.map((n, i) => ({ id: n.id, fraction: total > 0 ? weights[i] / total : 0 }));
}

/** The fraction of the workload one client part sends, or null if it is not a client. */
export function clientFraction(nodes: NodeLike[], catalog: Catalog | null, id: string): number | null {
  return clientShares(nodes, catalog).find((c) => c.id === id)?.fraction ?? null;
}
