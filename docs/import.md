# Importing a design

Start from what you already run instead of drawing it: a docker-compose file, Kubernetes
manifests or a Terraform plan become an ArchSim design plus an **import report**. Infrastructure
files say what runs and how big it is; they rarely say who calls whom, and never how fast
anything is. So the import maps what the file states, marks every number it set as **assumed**
(with where it came from), lists what it guessed and what it ignored, and suggests links
instead of inventing them. Service times, hit ratios and timeouts start at ArchSim's defaults
(illustrative); [calibration](calibration.md) can replace them with measurements.

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
| `mapped` | What became which part, and settings read from the file. |
| `guessed` | Choices the import made without the file saying so: an application image that became a service, a link inferred from `depends_on` or a URL in the environment, a Clients part at the entry point, a Kubernetes cluster part, read replicas counted from pods. Check these. |
| `ignored` | What has no counterpart in a design (volumes, networks, health checks, IAM roles, log groups...). |
| `suggestions` | Links the import did not add because the file does not say (Terraform rarely does): `{source, target, reason}`. |
| `issues` | Validation of the imported design: errors to fix before a run, warnings. |

Every number the import set carries provenance `assumed`, with `source` ("imported from
docker-compose") and, where it was derived, a `note`: `deploy.replicas`, `container resources`,
`HorizontalPodAutoscaler`, "from instance type db.r6g.large (link)", "guessed: 3 pods as a
primary and read replicas". The inspector shows it next to the setting.

## What each format maps

**docker-compose.** Services become parts by image (`postgres` -> PostgreSQL, `redis` ->
Redis, Kafka and Redpanda images -> Kafka, `nginx` -> Nginx, MongoDB, MySQL, Cassandra,
RabbitMQ, NATS, HAProxy, Envoy, Traefik, Kong and others); anything else becomes a service on
Docker. `deploy.replicas`, `deploy.resources.limits` and a PostgreSQL or MySQL connection limit
in the command are read. Links come from `depends_on` and from environment values that are URLs
naming another service (`DATABASE_URL=postgresql://db:5432/app`); each is listed as a guess
with read and write requests assumed. A Clients part is added at the entry (a service with
published ports, preferring a proxy).

**Kubernetes.** Deployments, StatefulSets, DaemonSets and Knative Services become parts by image;
replicas, resource requests, StatefulSet storage, HorizontalPodAutoscalers (min, max, target
utilization, scale-up period), Ingresses (a gateway routing to its backends) and ExternalName
Services (third-party APIs) are read. Links come from container environment URLs that name a
Service. The manifests do not describe nodes, so a Kubernetes cluster part with the default node
pool is added and listed as a guess.

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
size. The only links added are those the code states (a Lambda event source mapping: queue ->
function); everything else is a suggestion.

## Round trip

ArchSim's own exports carry what an import needs to give the design back exactly: each part's
id, technology and outgoing links (Compose labels `archsim.node`, `archsim.technology`,
`archsim.calls`, `archsim.runtime`, `archsim.platform`; Kubernetes annotations `archsim.dev/node`,
`archsim.dev/technology`, `archsim.dev/calls`, `archsim.dev/runtime`, `archsim.dev/platform`,
`archsim.dev/type`; a Knative Service's replicas as `autoscaling.knative.dev/initial-scale`), plus the parts nothing deploys (clients, Kubernetes clusters) in an
`x-archsim` block (Compose) or a `<name>-archsim` ConfigMap (Kubernetes, safe to delete).
`archsim.calls` lists `target[/port][:types]`, for example `db/miss:read,queue:write`. A test
exports every template to both formats and imports it back: the same parts, types,
technologies, replicas, autoscaling and links, with no guesses. Settings the exports do not carry
(service times, cache hit ratios, timeouts, workload) come back as defaults.

## Limits

- Only what the files state: no call graph beyond links and URLs, no latency, no traffic. Use
  calibration, or set them by hand.
- Helm charts and Kustomize overlays must be rendered first (`helm template`, `kubectl kustomize`);
  HCL is not read, only `terraform show -json`.
- Image matching is by name; a custom PostgreSQL image named `acme/db` becomes a service (listed
  as a guess). Replace that part in the editor.
- Fixtures in `packages/engine/tests/fixtures/import/` are written for these tests in the style
  of common examples; they are not real deployments.
