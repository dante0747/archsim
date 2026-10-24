/** Container images, ports and deployment hints per technology, shared by the exporters. */
import { kindOf, type Kind } from "@/lib/theme";
import type { ArchitectureDocument, Catalog, NodeConfig, NodeSpec } from "@/lib/types";

export interface TechSpec {
  image: string;
  port: number;
  /** Managed / cloud-only service: no container, referenced as an external dependency. */
  managed?: boolean;
  env?: Record<string, string>;
  command?: string[];
  note?: string;
  /** Where the image keeps its data (volume mount point). */
  dataPath?: string;
}

const T: Record<string, TechSpec> = {
  // relational
  postgresql: { dataPath: "/var/lib/postgresql/data", image: "postgres:16", port: 5432, env: { POSTGRES_PASSWORD: "change-me" } },
  mysql: { dataPath: "/var/lib/mysql", image: "mysql:8.4", port: 3306, env: { MYSQL_ROOT_PASSWORD: "change-me" } },
  mariadb: { dataPath: "/var/lib/mysql", image: "mariadb:11", port: 3306, env: { MARIADB_ROOT_PASSWORD: "change-me" } },
  sqlserver: { dataPath: "/var/opt/mssql", image: "mcr.microsoft.com/mssql/server:2022-latest", port: 1433, env: { ACCEPT_EULA: "Y", MSSQL_SA_PASSWORD: "Change-me-123" } },
  oracle: { dataPath: "/opt/oracle/oradata", image: "gvenzl/oracle-free:23-slim", port: 1521, env: { ORACLE_PASSWORD: "change-me" }, note: "community image; check licensing" },
  cockroachdb: { dataPath: "/cockroach/cockroach-data", image: "cockroachdb/cockroach:v24.2.4", port: 26257, command: ["start-single-node", "--insecure"] },
  yugabytedb: { dataPath: "/root/var", image: "yugabytedb/yugabyte:latest", port: 5433, command: ["bin/yugabyted", "start", "--background=false"] },
  // NoSQL / search
  mongodb: { dataPath: "/data/db", image: "mongo:7", port: 27017 },
  cassandra: { dataPath: "/var/lib/cassandra", image: "cassandra:5", port: 9042 },
  scylladb: { dataPath: "/var/lib/scylla", image: "scylladb/scylla:6.1", port: 9042 },
  dynamodb: { dataPath: "/home/dynamodblocal/data", image: "amazon/dynamodb-local:latest", port: 8000, note: "local stand-in for a managed DynamoDB-style table" },
  couchbase: { dataPath: "/opt/couchbase/var", image: "couchbase/server:community", port: 8091 },
  elasticsearch: { dataPath: "/usr/share/elasticsearch/data",
    image: "docker.elastic.co/elasticsearch/elasticsearch:8.15.3",
    port: 9200,
    env: { "discovery.type": "single-node", "xpack.security.enabled": "false" },
  },
  opensearch: { dataPath: "/usr/share/opensearch/data", image: "opensearchproject/opensearch:2", port: 9200, env: { "discovery.type": "single-node", DISABLE_SECURITY_PLUGIN: "true" } },
  // caches
  redis: { image: "redis:7", port: 6379 },
  valkey: { image: "valkey/valkey:8", port: 6379 },
  memcached: { image: "memcached:1.6", port: 11211 },
  varnish: { image: "varnish:7", port: 80 },
  cdn: { image: "", port: 443, managed: true, note: "CDN edge (managed service)" },
  // edge
  nginx: { image: "nginx:1.27", port: 80 },
  haproxy: { image: "haproxy:3.0", port: 80 },
  envoy: { image: "envoyproxy/envoy:v1.31-latest", port: 10000 },
  traefik: { image: "traefik:v3.1", port: 80 },
  kong: { image: "kong:3.7", port: 8000, env: { KONG_DATABASE: "off" } },
  aws_alb: { image: "", port: 80, managed: true, note: "managed load balancer" },
  api_gateway: { image: "", port: 443, managed: true, note: "managed API gateway" },
  // messaging
  kafka: { dataPath: "/var/lib/kafka/data", image: "apache/kafka:3.8.0", port: 9092 },
  rabbitmq: { dataPath: "/var/lib/rabbitmq", image: "rabbitmq:3-management", port: 5672 },
  nats: { dataPath: "/data", image: "nats:2", port: 4222 },
  redis_streams: { dataPath: "/data", image: "redis:7", port: 6379 },
  aws_sqs: { dataPath: "/data", image: "softwaremill/elasticmq-native:1.6.9", port: 9324, note: "SQS-compatible local stand-in" },
  google_pubsub: { image: "", port: 443, managed: true, note: "managed Pub/Sub-style service" },
};

const DEFAULT_BY_KIND: Partial<Record<Kind, string>> = {
  database: "postgresql",
  cache: "redis",
  network: "nginx",
  messaging: "rabbitmq",
};

export interface DeployNode {
  node: NodeSpec;
  kind: Kind;
  cfg: NodeConfig;
  name: string;
  technology: string | null;
  tech: TechSpec | null;
}

/** DNS-1123-ish name: lowercase letters, digits and dashes. */
export function dnsName(id: string): string {
  const s = id
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 63);
  return s || "node";
}

export function slug(text: string): string {
  return dnsName(text || "architecture").slice(0, 40) || "architecture";
}

export function deployNodes(doc: ArchitectureDocument, catalog?: Catalog | null): DeployNode[] {
  return doc.architecture.nodes.map((node) => {
    const comp = catalog?.components.find((c) => c.type === node.type);
    const cfg: NodeConfig = { ...(comp?.defaultConfig ?? {}), ...(node.config ?? {}) };
    const kind = kindOf(node.type, comp?.category);
    const technology = typeof cfg.technology === "string" && cfg.technology !== "generic" ? cfg.technology : (DEFAULT_BY_KIND[kind] ?? null);
    const tech = technology ? (T[technology] ?? null) : null;
    return { node, kind, cfg, name: dnsName(node.id), technology, tech };
  });
}

export function num(v: unknown, fallback: number): number {
  return typeof v === "number" && Number.isFinite(v) ? v : fallback;
}

/** Replicas a service should start with (autoscaling: the minimum). */
export function serviceReplicas(cfg: NodeConfig): number {
  if (cfg.autoscaling === true) return Math.max(1, num(cfg.minReplicas, num(cfg.replicas, 1)));
  return Math.max(1, num(cfg.replicas, 1));
}

/** Instances of a data store: shards × copies (primary + read replicas, or the replication factor). */
export function storeInstances(cfg: NodeConfig): number {
  const shards = Math.max(1, num(cfg.shards, 1));
  const copies = Math.max(1 + num(cfg.readReplicas, 0), num(cfg.replicationFactor, 1));
  return shards * copies;
}

export const HEADER = (name: string, what: string) => [
  `Generated by ArchSim from "${name}": ${what}.`,
  "Starting-point scaffolding, not a production config. Review images, versions, credentials,",
  "resources, probes and storage before using it. Capacity values come from the model's assumptions.",
];
