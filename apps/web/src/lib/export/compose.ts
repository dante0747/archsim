/** docker-compose.yml scaffolding generated from an architecture document. */
import type { ArchitectureDocument, Catalog } from "@/lib/types";
import { HEADER, composeResources, deployNodes, num, serviceReplicas, slug, specsOf, storeInstances, type DeployNode } from "./tech";
import { comments, toYaml, type YamlValue } from "./yaml";

function upperEnv(name: string): string {
  return name.toUpperCase().replace(/[^A-Z0-9]+/g, "_");
}

const SCHEME: Record<string, string> = {
  postgresql: "postgresql",
  cockroachdb: "postgresql",
  yugabytedb: "postgresql",
  mysql: "mysql",
  mariadb: "mysql",
  mongodb: "mongodb",
  redis: "redis",
  valkey: "redis",
  redis_streams: "redis",
  memcached: "memcached",
  kafka: "kafka",
  rabbitmq: "amqp",
  nats: "nats",
  cassandra: "cassandra",
  scylladb: "cassandra",
};

/** Connection URL a caller would use for a dependency (host = compose service name). */
export function dependencyUrl(t: DeployNode): string {
  const scheme = (t.technology && SCHEME[t.technology]) || "http";
  return `${scheme}://${t.name}:${t.tech?.port ?? 80}`;
}

export function toDockerCompose(doc: ArchitectureDocument, catalog?: Catalog | null): string {
  const nodes = deployNodes(doc, catalog);
  const byId = new Map(nodes.map((n) => [n.node.id, n]));
  const deployable = (n: DeployNode | undefined): n is DeployNode =>
    !!n && n.kind !== "traffic" && n.kind !== "external" && !n.tech?.managed;
  const services: Record<string, YamlValue> = {};
  const external: YamlValue[] = [];
  let lbPort = 8080;

  for (const n of nodes) {
    const { node, kind, cfg, name, tech } = n;
    if (kind === "traffic") continue;
    if (kind === "external" || tech?.managed) {
      external.push({
        name,
        label: node.label || node.id,
        technology: n.technology ?? String(cfg.technology ?? "generic"),
        note: kind === "external" ? "third-party API, not deployed; point the callers at its URL" : (tech?.note ?? "managed service"),
      });
      continue;
    }
    const targets = [
      ...new Set(
        doc.architecture.edges
          .filter((e) => e.source === node.id)
          .map((e) => byId.get(e.target))
          .filter(deployable),
      ),
    ];
    const deps = targets.map((d) => d.name);
    const labels: Record<string, string> = { "archsim.node": node.id };
    const svc: Record<string, YamlValue> = {};

    if (kind === "compute") {
      svc.image = `your-org/${name}:latest`;
      svc.deploy = { replicas: serviceReplicas(cfg), resources: composeResources(cfg) };
      if (cfg.autoscaling === true) labels["archsim.autoscaling"] = `${num(cfg.minReplicas, 1)}-${num(cfg.maxReplicas, 1)} replicas at ${Math.round(num(cfg.targetUtilization, 0.6) * 100)}% utilization`;
      if (cfg.platform) labels["archsim.platform"] = String(cfg.platform);
      if (typeof cfg.technology === "string" && cfg.technology !== "generic") labels["archsim.runtime"] = cfg.technology;
      const env: Record<string, string> = {};
      for (const t of targets) env[`${upperEnv(t.name)}_URL`] = dependencyUrl(t);
      if (Object.keys(env).length) svc.environment = env;
    } else if (tech) {
      svc.image = tech.image;
      if (tech.env) svc.environment = { ...tech.env };
      if (tech.command) svc.command = [...tech.command];
      if (kind === "database" && n.technology === "postgresql") svc.command = ["postgres", "-c", `max_connections=${num(cfg.maxConnections, 100)}`];
      if (kind === "database" && (n.technology === "mysql" || n.technology === "mariadb")) svc.command = [`--max-connections=${num(cfg.maxConnections, 151)}`];
      if (kind === "network") {
        svc.ports = [`${lbPort++}:${tech.port}`];
        if (num(cfg.rateLimitRps, 0) > 0) labels["archsim.rate-limit"] = `${num(cfg.rateLimitRps, 0)} req/s (configure in the proxy)`;
      }
      const res = composeResources(cfg);
      if (res) svc.deploy = { resources: res };
      if (kind === "database" || kind === "messaging") {
        svc.volumes = [`${name}-data:${tech.dataPath ?? "/data"}`];
        const storage = specsOf(cfg).storageGib;
        if (storage != null) labels["archsim.storage"] = `${storage} GiB per instance (size the volume to match)`;
        const inst = storeInstances(cfg);
        if (inst > 1) labels["archsim.topology"] = `${inst} instances modelled (shards ${num(cfg.shards, 1)}, read replicas ${num(cfg.readReplicas, 0)}, RF ${num(cfg.replicationFactor, 1)}); configure replication/sharding separately`;
        if (kind === "messaging") {
          labels["archsim.messaging"] = `${num(cfg.partitions, 1)} partitions, ${num(cfg.consumers, 1)} consumers`;
        }
      }
      if (tech.note) labels["archsim.note"] = tech.note;
    }
    if (deps.length) svc.depends_on = deps;
    svc.labels = labels;
    services[name] = svc;
  }

  const volumes: Record<string, YamlValue> = {};
  for (const svc of Object.values(services)) {
    const vols = (svc as Record<string, YamlValue>).volumes;
    if (Array.isArray(vols)) for (const v of vols) if (typeof v === "string") volumes[v.split(":")[0]] = {};
  }

  const src = nodes.find((n) => n.kind === "traffic");
  const w = doc.workload;
  const body: Record<string, YamlValue> = { name: slug(doc.metadata.name), services };
  if (Object.keys(volumes).length) body.volumes = volumes;
  if (external.length) body["x-external-dependencies"] = external;
  const head = comments([
    ...HEADER(doc.metadata.name, "docker-compose scaffolding"),
    src ? `Modelled load: ${src.node.label || src.node.id} send ${w.rps} req/s (${Math.round(w.readRatio * 100)}% reads).` : "",
    "App images (your-org/...) are placeholders for your own services.",
  ].filter(Boolean));
  return `${head}\n${toYaml(body)}\n`;
}
