# Importing a design

Start from what you already run instead of drawing it: a docker-compose file, Kubernetes
manifests or a Terraform plan become an ArchSim design plus an **import report**. Infrastructure
files say what runs and how big it is; they rarely say who calls whom, and never how fast
anything is. So the import maps what the file states, marks every number it set as **assumed**
(with where it came from), lists what it guessed and what it ignored, and suggests links
instead of inventing them where it can. Service times, hit ratios and timeouts start at
ArchSim's defaults (illustrative); [calibration](calibration.md) can replace them with
measurements.

```bash
archsim import compose docker-compose.yml -o design.json --report import-report.json
archsim import k8s manifests.yaml -o design.json
terraform show -json plan.out > plan.json && archsim import terraform plan.json -o design.json
```

The API has `POST /import` (`{format, text, name?}` -> `{document, report}`), and the web app
has **Import from compose, Kubernetes, Terraform…** in the editor's menu and command palette: it
detects the format, shows the report, and replaces the current design when you apply it (undo
with Ctrl Z; save to keep it). Nothing is sent anywhere: the API reads the text you give it.

## The import report

| Field | What it holds |
| --- | --- |
| `mapped` | What the file states: settings read from it (replicas, CPU and memory, storage, connection limits, autoscalers), the resources a Terraform plan names, and the parts of ArchSim's own exports. |
| `guessed` | Choices the import made without the file saying so, each with its reason. Check these. A part's type and technology taken from its image name ("db: Database (postgresql), because 'postgres:16' is a postgresql image"), an image that became a plain service (an application image, or a tool such as an exporter or a UI), links inferred from `depends_on` or a URL in the environment, a cache's miss link, a load balancer's targets in a Terraform plan, the Clients part (and a load balancer in front when there are several entry points), a Kubernetes cluster part, read replicas counted from pods, memory at idle lowered to fit a small memory limit. |
| `ignored` | What has no counterpart in a design (volumes, networks, health checks, IAM roles, log groups...) or could not be attached: a Service whose selector matches no workload, an autoscaler metric other than CPU, a read replica or node pool whose source is not in the plan. |
| `suggestions` | Links the import did not add because the file does not say (Terraform rarely does): `{source, target, reason}`. |
| `issues` | Validation of the imported design: errors to fix before a run, warnings. |

Every number the import set carries provenance `assumed`, with `source` ("imported from
docker-compose") and, where it was derived, a `note`: `deploy.replicas`, "not set: Docker
Compose runs one container", `container resources`, `HorizontalPodAutoscaler`, "from instance
type db.r6g.large (link)", "guessed: 3 pods as a primary and read replicas". The inspector shows
it next to the setting.

Text that is not the format (YAML or JSON that does not parse, a list where a mapping belongs,
nesting too deep to read) is refused with what was expected: exit code 2 from `archsim import`,
422 from `POST /import`. The CLI reads UTF-8, and UTF-8 or UTF-16 with a byte order mark (what
`terraform show -json > plan.json` writes in Windows PowerShell 5.1).

## What each format maps

**Images (docker-compose and Kubernetes).** A container becomes a database, cache, queue or load
balancer only when its image's full repository name is that technology's image or a common
distribution of it: `postgres`, `postgis/postgis`, `timescale/timescaledb-ha`,
`bitnami/postgresql`; `mysql`, `mariadb`, `mcr.microsoft.com/mssql/server`; `mongo`,
`cassandra`, `scylladb/scylla`, `couchbase/server`, Elasticsearch and OpenSearch; `redis`,
`redis/redis-stack-server`, `valkey`, `memcached`, `varnish`; `apache/kafka`,
`confluentinc/cp-kafka`, Redpanda, `rabbitmq`, `nats`; `nginx`, HAProxy, Envoy, Traefik and
Kong. The registry host, tag and digest do not matter. Exporters, web UIs, admin consoles,
migration jobs and clients named after a technology (`kafka-ui`, `mongo-express`,
`postgres-exporter`, `acme/postgres-migrations`, `pgadmin4`, `adminer`) become plain services and
are never taken as the entry point; so does any other image (`ghcr.io/acme/server` is not SQL
Server). Every image-based decision is listed under `guessed` with its reason.

**docker-compose.** `deploy.replicas` (or `scale`; one container when neither is set, as Compose
runs it), `deploy.resources.limits` and a PostgreSQL or MySQL connection limit in the command
are read. Links come from `depends_on` and from environment values that are URLs naming another
service (`DATABASE_URL=postgresql://db:5432/app`); each is listed as a guess with read and write
requests assumed.

**Kubernetes.** Deployments, StatefulSets, DaemonSets and Knative Services become parts by image.
`spec.replicas` (one pod when unset, or the autoscaler's minimum), resource requests as written
(only ArchSim's own manifests, marked `archsim.dev/node`, carry placeholder requests, which are
skipped), StatefulSet storage, Ingresses (a gateway routing to its backends) and ExternalName
Services (third-party APIs) are read. HorizontalPodAutoscalers give min, max, scale-up period and
the CPU target: `targetCPUUtilizationPercentage` (autoscaling/v1) or the CPU `Resource` metric
(v2); with no metric at all, Kubernetes' default of 80%. ArchSim scales on CPU only, so memory
and custom metrics are listed under `ignored`. A Service attaches to the workload whose pod
labels its selector matches. Links come from container environment URLs that name a Service.
The manifests do not describe nodes, so a Kubernetes cluster part with the default node pool is
added and listed as a guess; services run on it by their platform, and it is never linked.

**Terraform** (the JSON of `terraform show -json`, plan or state). Mapped resources: on AWS
`aws_db_instance` (and read replicas), `aws_rds_cluster` and its instances, `aws_dynamodb_table`,
`aws_elasticache_cluster` and `_replication_group`, `aws_lambda_function`, `aws_ecs_service`,
`aws_sqs_queue`, `aws_msk_cluster`, `aws_lb`/`aws_alb`, `aws_api_gateway_rest_api`,
`aws_apigatewayv2_api`, `aws_cloudfront_distribution`, `aws_eks_cluster` and node groups; on GCP
`google_sql_database_instance`, `google_redis_instance`, `google_pubsub_topic` and
subscriptions, `google_cloud_run_service` (v1 and v2), `google_container_cluster` and node pools;
on Azure PostgreSQL and MySQL flexible servers, `azurerm_redis_cache`, Service Bus queues and
topics, function apps, `azurerm_kubernetes_cluster` and `azurerm_application_gateway`. Instance
types become CPU and memory from the providers' published sizes (AWS `db.r6g.large` -> 2 vCPU,
16 GiB; GCP `db-custom-2-7680`; Azure `Standard_D4s_v5`); an unknown type is listed for you to
size. Links the code states are added: a Lambda event source mapping links the queue it names
(by exact address: `aws_sqs_queue.orders`, never `aws_sqs_queue.orders_dlq`) to its function.
Plans rarely say what a load balancer routes to, so it is linked to the services nothing else
calls, and a CDN's misses go to the only load balancer; both are listed as guesses. Everything
else is a suggestion; a Kubernetes cluster is never suggested as a caller or a target.

## A design that runs

The import finishes the design so it validates and simulates, and lists each step as a guess:

- **Entry point.** One Clients part sends to the entry: a published proxy (Compose), a
  LoadBalancer Service or an Ingress (Kubernetes), a load balancer or CDN (Terraform), else the
  load balancer or service nothing calls. Clients send to one part, so several entries get a
  load balancer in front of them. Never a Kubernetes cluster part, never a tool.
- **Cache misses.** A cache's miss port links to the database its callers also use (when there
  is exactly one), or to the only database in the design.
- **Small memory limits.** ArchSim assumes 256 MiB per replica at idle. A smaller limit (a 256M
  container, a 128 MB function) would leave no room for a single request, so memory at idle is
  set to half the limit, noted, and listed.

A test imports every fixture and checks that the design validates without errors and runs.

## Round trip

ArchSim's exports carry what an import needs to give the design's structure back: each part's
id, type and technology, outgoing links (with port and request types), replicas, autoscaling
(min, max, target utilization; on Kubernetes also the scale-up period), platform and runtime,
and the parts nothing deploys (clients, Kubernetes clusters, with their settings). They travel
as Compose labels (`archsim.node`, `archsim.technology`, `archsim.calls`, `archsim.runtime`,
`archsim.platform`, ...), Kubernetes annotations (`archsim.dev/node`, `archsim.dev/technology`,
`archsim.dev/calls`, `archsim.dev/runtime`, `archsim.dev/platform`, `archsim.dev/type`; a
Knative Service's replicas as `autoscaling.knative.dev/initial-scale`), and an `x-archsim` block
(Compose) or a `<name>-archsim` ConfigMap (Kubernetes, safe to delete). `archsim.calls` lists
`target[/port][:types]`, for example `db/miss:read,queue:write`. A test exports every template to
both formats and imports it back: the same parts, types, technologies, replicas, autoscaling and
links, with no guesses.

It is not the same design, and it does not simulate the same. Not carried: service times, cache
hit ratios and TTLs, link timeouts and retries, workers per replica and queue sizes, connection
limits (Compose carries PostgreSQL's and MySQL's only, Kubernetes none), load balancer policies,
cold starts, part labels (a part takes its id), the workload, scenarios and objectives; these
come back as ArchSim's defaults. CPU and memory left to auto-size come back fixed at the sizes the export wrote. The
cache-aside starter's p95, for example, moves from about 98 ms to about 55 ms after a round trip
with the same workload. Keep the original design, or calibrate again, when the numbers matter.

## Limits

- Only what the files state: no call graph beyond links and URLs, no latency, no traffic. Use
  calibration, or set them by hand.
- Helm charts and Kustomize overlays must be rendered first (`helm template`, `kubectl kustomize`);
  HCL is not read, only `terraform show -json`.
- Image matching is by full name; a custom PostgreSQL image named `acme/db` becomes a service
  (listed as a guess). Replace that part in the editor.
- Terraform target groups and listeners are not read: check the load balancer links the import
  guessed.
- Fixtures in `packages/engine/tests/fixtures/import/` are written for these tests in the style
  of common examples; they are not real deployments.
