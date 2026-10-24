/** Visual identity of component kinds and request types (colors live in globals.css). */

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

export const kindColor = (k: Kind) => `var(--kind-${k})`;
export const kindSoft = (k: Kind, pct = 14) => `color-mix(in srgb, var(--kind-${k}) ${pct}%, transparent)`;

export const KIND_LABEL: Record<Kind, string> = {
  traffic: "Traffic",
  network: "Network",
  compute: "Compute",
  cache: "Cache",
  database: "Storage",
  other: "Other",
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

export const REQ_COLOR = { read: "var(--req-read)", write: "var(--req-write)" } as const;
