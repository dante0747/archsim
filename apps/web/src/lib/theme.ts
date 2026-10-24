/**
 * Visual identity of component kinds: each kind has a schematic shape (see components/shapes.tsx),
 * not a color. Colors are reserved for interaction (accent) and results (status).
 */

export type Kind = "traffic" | "network" | "compute" | "cache" | "database" | "other";

const BY_TYPE: Record<string, Kind> = {
  traffic_source: "traffic",
  load_balancer: "network",
  service: "compute",
  cache: "cache",
  database: "database",
};

const BY_CATEGORY: Record<string, Kind> = {
  traffic: "traffic",
  network: "network",
  compute: "compute",
  storage: "database",
};

export function kindOf(type: string, category?: string): Kind {
  return BY_TYPE[type] ?? (category ? BY_CATEGORY[category] : undefined) ?? "other";
}

export const KIND_LABEL: Record<Kind, string> = {
  traffic: "Traffic",
  network: "Network",
  compute: "Compute",
  cache: "Cache",
  database: "Storage",
  other: "Other",
};

/** Short mono tag shown on nodes (like a file extension). */
export const KIND_TAG: Record<Kind, string> = {
  traffic: "src",
  network: "lb",
  compute: "svc",
  cache: "kv",
  database: "db",
  other: "box",
};

/** Short names for the component rail. */
const SHORT: Record<string, string> = {
  traffic_source: "Clients",
  load_balancer: "Balancer",
  service: "Service",
  cache: "Cache",
  database: "Database",
};

export function shortName(type: string, displayName: string): string {
  return SHORT[type] ?? displayName.split(/[ /(]/)[0];
}

/** Request types are told apart by glyph shape, not color. */
export const REQ_GLYPH = { read: "●", write: "■" } as const;
