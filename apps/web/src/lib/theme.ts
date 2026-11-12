/**
 * Visual identity of component kinds: each kind has a schematic shape (see components/shapes.tsx),
 * not a color. Colors are reserved for interaction (accent) and results (status).
 */
import type { Catalog, ComponentType } from "./types";

export type Kind = "traffic" | "network" | "compute" | "cache" | "database" | "messaging" | "external" | "other";

const BY_TYPE: Record<string, Kind> = {
  traffic_source: "traffic",
  mobile_app: "traffic",
  web_client: "traffic",
  api_consumer: "traffic",
  load_balancer: "network",
  service: "compute",
  kubernetes_cluster: "compute",
  cache: "cache",
  database: "database",
  message_queue: "messaging",
  external_api: "external",
};

const BY_CATEGORY: Record<string, Kind> = {
  traffic: "traffic",
  network: "network",
  compute: "compute",
  storage: "database",
  messaging: "messaging",
  external: "external",
};

/** Each component family has its own hue (CSS variables, light and dark). */
export const KIND_COLOR: Record<Kind, string> = {
  traffic: "var(--fam-traffic)",
  network: "var(--fam-network)",
  compute: "var(--fam-compute)",
  cache: "var(--fam-cache)",
  database: "var(--fam-database)",
  messaging: "var(--fam-messaging)",
  external: "var(--fam-external)",
  other: "var(--fam-external)",
};

export function kindOf(type: string, category?: string): Kind {
  return BY_TYPE[type] ?? (category ? BY_CATEGORY[category] : undefined) ?? "other";
}

/** True for client parts (Clients, Mobile app, Web browser, API consumers). */
export function isClientType(type: string, catalog?: Catalog | null): boolean {
  const c = catalog?.components.find((x) => x.type === type);
  return c?.isTrafficSource ?? kindOf(type, c?.category) === "traffic";
}

export const KIND_LABEL: Record<Kind, string> = {
  traffic: "Traffic",
  network: "Network",
  compute: "Compute",
  cache: "Cache",
  database: "Storage",
  messaging: "Messaging",
  external: "External",
  other: "Other",
};

/** Short mono tag shown on nodes (like a file extension). */
export const KIND_TAG: Record<Kind, string> = {
  traffic: "src",
  network: "lb",
  compute: "svc",
  cache: "kv",
  database: "db",
  messaging: "mq",
  external: "ext",
  other: "box",
};

/** Families of the technology library, in rail order. */
export interface Family {
  key: string;
  label: string;
  /** Short rail label. */
  short: string;
  kinds: Kind[];
  blurb: string;
}

export const FAMILIES: Family[] = [
  { key: "clients", label: "Clients", short: "Clients", kinds: ["traffic"], blurb: "Who sends the traffic" },
  { key: "edge", label: "Edge & gateways", short: "Edge", kinds: ["network"], blurb: "Load balancers, proxies, API gateways" },
  { key: "compute", label: "Compute", short: "Compute", kinds: ["compute"], blurb: "Services on VMs, containers, Kubernetes, serverless" },
  { key: "data", label: "Data", short: "Data", kinds: ["database"], blurb: "Relational, document, wide-column, key-value, search" },
  { key: "cache", label: "Cache", short: "Cache", kinds: ["cache"], blurb: "In-memory caches and CDN edges" },
  { key: "messaging", label: "Messaging", short: "Queues", kinds: ["messaging"], blurb: "Logs, brokers and managed queues" },
  { key: "external", label: "External", short: "External", kinds: ["external", "other"], blurb: "Third-party APIs and SaaS" },
];

export function familyOf(kind: Kind): Family {
  return FAMILIES.find((f) => f.kinds.includes(kind)) ?? FAMILIES[FAMILIES.length - 1];
}

/** Short names for the component rail. */
const SHORT: Record<string, string> = {
  traffic_source: "Clients",
  mobile_app: "Mobile",
  web_client: "Browser",
  api_consumer: "API users",
  load_balancer: "Balancer",
  service: "Service",
  kubernetes_cluster: "Cluster",
  cache: "Cache",
  database: "Database",
  message_queue: "Queue",
  external_api: "External",
};

export function shortName(type: string, displayName: string): string {
  return SHORT[type] ?? displayName.split(/[ /(]/)[0];
}

const TECH_NAMES: Record<string, string> = {
  postgresql: "PostgreSQL",
  mysql: "MySQL",
  mariadb: "MariaDB",
  sqlserver: "SQL Server",
  oracle: "Oracle",
  cockroachdb: "CockroachDB",
  yugabytedb: "YugabyteDB",
  mongodb: "MongoDB",
  cassandra: "Cassandra",
  scylladb: "ScyllaDB",
  dynamodb: "DynamoDB-style",
  couchbase: "Couchbase",
  elasticsearch: "Elasticsearch",
  opensearch: "OpenSearch",
  redis: "Redis",
  valkey: "Valkey",
  memcached: "Memcached",
  cdn: "CDN edge",
  varnish: "Varnish",
  nginx: "Nginx",
  haproxy: "HAProxy",
  envoy: "Envoy",
  traefik: "Traefik",
  kong: "Kong",
  aws_alb: "ALB-style",
  api_gateway: "API gateway",
  kafka: "Kafka",
  rabbitmq: "RabbitMQ",
  sqs: "SQS-style",
  pubsub: "Pub/Sub-style",
  nats: "NATS",
  redis_streams: "Redis Streams",
  java: "Java",
  nodejs: "Node.js",
  go: "Go",
  python: "Python",
  dotnet: ".NET",
  rust: "Rust",
  php: "PHP",
  ruby: "Ruby",
  // Kubernetes clusters
  kubernetes: "Kubernetes",
  eks: "EKS-style",
  gke: "GKE-style",
  aks: "AKS-style",
  k3s: "k3s",
  openshift: "OpenShift",
  // client groups
  mobile: "Mobile app",
  ios: "iOS",
  android: "Android",
  react_native: "React Native",
  flutter: "Flutter",
  browser: "Web browser",
  react: "React",
  nextjs: "Next.js",
  vue: "Vue",
  angular: "Angular",
  svelte: "Svelte",
  api_client: "API client",
  rest: "REST / OpenAPI",
  graphql: "GraphQL",
  generic: "Generic",
};

/**
 * Display name of a technology value: the name of a preset with that technology if the catalog has
 * one, else a known name, else the value humanized. null when no technology is set.
 */
export function technologyLabel(comp: Pick<ComponentType, "presets"> | undefined, technology: unknown): string | null {
  if (typeof technology !== "string" || !technology) return null;
  const known = TECH_NAMES[technology];
  if (known) return known;
  const preset = comp?.presets?.find((p) => p.technology === technology);
  if (preset) return preset.name;
  return technology.replace(/[_-]+/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

/** Request types are told apart by glyph shape, not color. */
export const REQ_GLYPH = { read: "●", write: "■" } as const;
