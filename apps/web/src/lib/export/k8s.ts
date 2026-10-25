/** Kubernetes manifests (multi-document YAML) generated from an architecture document. */
import type { ArchitectureDocument, Catalog } from "@/lib/types";
import { dependencyUrl } from "./compose";
import { HEADER, deployNodes, k8sResources, k8sStorage, num, serviceReplicas, slug, specsOf, storeInstances, type DeployNode } from "./tech";
import { comments, toYaml, type YamlValue } from "./yaml";

const APP_PORT = 8080;

function meta(n: DeployNode, part: string, extra: Record<string, string> = {}): Record<string, YamlValue> {
  return {
    name: n.name,
    labels: { "app.kubernetes.io/name": n.name, "app.kubernetes.io/part-of": part, ...extra },
  };
}

const selector = (n: DeployNode) => ({ "app.kubernetes.io/name": n.name });

function clusterService(n: DeployNode, part: string, port: number, targetPort: number, opts: { headless?: boolean; type?: string } = {}): YamlValue {
  return {
    apiVersion: "v1",
    kind: "Service",
    metadata: meta(n, part),
    spec: {
      type: opts.type,
      clusterIP: opts.headless ? "None" : undefined,
      selector: selector(n),
      ports: [{ name: "main", port, targetPort }],
    },
  };
}

function envOf(n: DeployNode, deps: DeployNode[]): YamlValue[] | undefined {
  const env = [...(n.tech?.env ? Object.entries(n.tech.env).map(([name, value]) => ({ name, value })) : []), ...deps.map((d) => ({ name: `${d.name.toUpperCase().replace(/-/g, "_")}_URL`, value: dependencyUrl(d) }))];
  return env.length ? env : undefined;
}

export function toKubernetes(doc: ArchitectureDocument, catalog?: Catalog | null): string {
  const part = slug(doc.metadata.name);
  const nodes = deployNodes(doc, catalog);
  const byId = new Map(nodes.map((n) => [n.node.id, n]));
  const docs: YamlValue[] = [];

  for (const n of nodes) {
    const { node, kind, cfg, tech } = n;
    if (kind === "traffic") continue;
    const deps = [...new Set(doc.architecture.edges.filter((e) => e.source === node.id).map((e) => byId.get(e.target)))].filter(
      (d): d is DeployNode => !!d && d.kind !== "traffic",
    );

    // Managed services and third-party APIs: an ExternalName service callers can resolve.
    if (kind === "external" || tech?.managed) {
      docs.push({
        apiVersion: "v1",
        kind: "Service",
        metadata: { ...meta(n, part), annotations: { "archsim.dev/note": kind === "external" ? "third-party API, replace externalName with its host" : (tech?.note ?? "managed service") } },
        spec: { type: "ExternalName", externalName: `${n.name}.example.com` },
      });
      continue;
    }

    if (kind === "compute") {
      const image = `your-org/${n.name}:latest`;
      const container: Record<string, YamlValue> = {
        name: n.name,
        image,
        ports: [{ containerPort: APP_PORT }],
        env: envOf(n, deps),
        readinessProbe: { httpGet: { path: "/healthz", port: APP_PORT }, periodSeconds: 5 },
        // the part's resource specs; placeholders when none are set
        resources: k8sResources(cfg) ?? { requests: { cpu: "500m", memory: "512Mi" }, limits: { memory: "1Gi" } },
      };
      if (cfg.platform === "serverless") {
        docs.push({
          apiVersion: "serving.knative.dev/v1",
          kind: "Service",
          metadata: meta(n, part),
          spec: {
            template: {
              metadata: {
                annotations: {
                  "autoscaling.knative.dev/min-scale": "0",
                  "autoscaling.knative.dev/max-scale": String(num(cfg.concurrencyLimit, 1000)),
                },
              },
              spec: { containerConcurrency: Math.max(1, num(cfg.workersPerReplica, 1)), containers: [{ ...container, readinessProbe: undefined }] },
            },
          },
        });
        continue;
      }
      docs.push({
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: meta(n, part),
        spec: {
          replicas: serviceReplicas(cfg),
          selector: { matchLabels: selector(n) },
          template: { metadata: { labels: selector(n) }, spec: { containers: [container] } },
        },
      });
      docs.push(clusterService(n, part, 80, APP_PORT));
      if (cfg.autoscaling === true) {
        docs.push({
          apiVersion: "autoscaling/v2",
          kind: "HorizontalPodAutoscaler",
          metadata: meta(n, part),
          spec: {
            scaleTargetRef: { apiVersion: "apps/v1", kind: "Deployment", name: n.name },
            minReplicas: Math.max(1, num(cfg.minReplicas, 1)),
            maxReplicas: Math.max(1, num(cfg.maxReplicas, num(cfg.minReplicas, 1))),
            metrics: [
              {
                type: "Resource",
                resource: { name: "cpu", target: { type: "Utilization", averageUtilization: Math.round(num(cfg.targetUtilization, 0.6) * 100) } },
              },
            ],
            behavior: { scaleUp: { policies: [{ type: "Percent", value: 100, periodSeconds: Math.max(1, Math.round(num(cfg.scaleIntervalS, 15))) }] } },
          },
        });
      }
      continue;
    }

    if (!tech) continue;
    const container: Record<string, YamlValue> = {
      name: n.name,
      image: tech.image,
      ports: [{ containerPort: tech.port }],
      env: envOf(n, kind === "network" ? deps : []),
      args: tech.command ? [...tech.command] : undefined,
      resources: k8sResources(cfg),
    };

    if (kind === "database" || kind === "messaging") {
      const instances = storeInstances(cfg);
      const annotations: Record<string, string> = {};
      if (instances > 1) annotations["archsim.dev/topology"] = `shards ${num(cfg.shards, 1)}, read replicas ${num(cfg.readReplicas, 0)}, RF ${num(cfg.replicationFactor, 1)}: use an operator for replication/sharding`;
      if (kind === "messaging") annotations["archsim.dev/messaging"] = `${num(cfg.partitions, 1)} partitions, ${num(cfg.consumers, 1)} consumers`;
      if (tech.note) annotations["archsim.dev/note"] = tech.note;
      docs.push({
        apiVersion: "apps/v1",
        kind: "StatefulSet",
        metadata: { ...meta(n, part), annotations: Object.keys(annotations).length ? annotations : undefined },
        spec: {
          serviceName: n.name,
          replicas: kind === "messaging" ? Math.max(1, num(cfg.brokers, 1)) : instances,
          selector: { matchLabels: selector(n) },
          template: {
            metadata: { labels: selector(n) },
            spec: { containers: [{ ...container, volumeMounts: [{ name: "data", mountPath: tech.dataPath ?? "/data" }] }] },
          },
          volumeClaimTemplates: [
            {
              metadata: { name: "data" },
              spec: { accessModes: ["ReadWriteOnce"], resources: { requests: { storage: k8sStorage(specsOf(cfg).storageGib ?? 10) } } },
            },
          ],
        },
      });
      docs.push(clusterService(n, part, tech.port, tech.port, { headless: true }));
      continue;
    }

    // caches and self-hosted load balancers / gateways
    docs.push({
      apiVersion: "apps/v1",
      kind: "Deployment",
      metadata: meta(n, part),
      spec: {
        replicas: 1,
        selector: { matchLabels: selector(n) },
        template: { metadata: { labels: selector(n) }, spec: { containers: [container] } },
      },
    });
    docs.push(clusterService(n, part, kind === "network" ? 80 : tech.port, tech.port, { type: kind === "network" ? "LoadBalancer" : undefined }));
  }

  const head = comments([
    ...HEADER(doc.metadata.name, "Kubernetes manifests"),
    "Apply into a namespace of your choice: kubectl apply -n <namespace> -f <file>.yaml",
    "Serverless services use Knative Serving; StatefulSets need a StorageClass.",
  ]);
  return `${head}\n${docs.map((d) => `---\n${toYaml(d)}`).join("\n")}\n`;
}
