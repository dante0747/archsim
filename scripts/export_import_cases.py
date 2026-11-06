"""Write the YAML importers' test cases for the Java engine, or check the committed file.

Stage 3's importers come to Java in two steps: first the docker-compose and Kubernetes importers
(``import_compose``, ``import_k8s``), then the Terraform importer and ``import_design``, which
validates what they build and which the golden files record. Until then, the YAML importers are
checked on what this script asks the frozen engine (0.16.0) to import:

* the engine's own fixtures, and every template's compose file and Kubernetes manifests, as the
  exports write them (the round trip);
* what the engine's tests import: tools and look-alike images, infrastructure images by their full
  name, entry points, placeholder requests, autoscalers, cache misses, memory limits, ArchSim's
  own parts written badly;
* YAML as PyYAML reads it: merge keys, ``yes``/``on``/``off``, octal and base-60 numbers,
  timestamps, numbers as keys, and lists where mappings usually are;
* keys in the order only PyYAML's merge and duplicate rules give, and values ``dict()`` reads as
  pairs;
* the words of each refusal the importers raise themselves, and of each value of the wrong kind
  they refuse, as ``import_design`` refuses it.

It writes each call with the engine's output (the design as built, before validation, and the
import report) to archsim-engine's test resources, where ``ImportCasesTests`` checks the Java
against them as the golden files are checked.

Usage:
    uv run python scripts/export_import_cases.py           # write the cases
    uv run python scripts/export_import_cases.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library may differ in the last bit of ``exp`` or ``log``, which the exports' auto
sizes use. It is a manual step for that reason, not a CI one.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine.components import create_default_registry
from archsim_engine.export import export_document
from archsim_engine.importers.compose import import_compose
from archsim_engine.importers.k8s import import_k8s

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "archsim-engine/src/test/resources/io/archsim/engine/imports/cases.json"
FIXTURES = ROOT / "packages/engine/tests/fixtures/import"
EXAMPLES = ROOT / "examples"
REGISTRY = create_default_registry(include_plugins=False)
Json = dict[str, Any]

IMPORTERS = {
    "compose": ("archsim_engine.importers.compose.import_compose", import_compose),
    "k8s": ("archsim_engine.importers.k8s.import_k8s", import_k8s),
}


def compose_one(image: str) -> str:
    return f'services:\n  x:\n    image: "{image}"\n'


TOOL_IMAGES = [
    "provectuslabs/kafka-ui:latest",
    "mongo-express:1.0",
    "prometheuscommunity/postgres-exporter:v0.15.0",
    "acme/postgres-migrations:3",
    "prom/mysqld-exporter:v0.15.1",
    "nginx/nginx-prometheus-exporter:1.3",
    "acme/nats-consumer:2",
    "ghcr.io/acme/server:1.0",
    "oliver006/redis_exporter:v1.62.0",
    "dpage/pgadmin4:8",
    "adminer:4",
    "phpmyadmin:5",
    "redis/redisinsight:latest",
    "tchiotludo/akhq:0.25.0",
    "rediscommander/redis-commander:latest",
]

INFRASTRUCTURE_IMAGES = [
    "postgres:16",
    "postgis/postgis:16-3.4",
    "timescale/timescaledb-ha:pg16",
    "bitnami/postgresql:16",
    "registry.local:5000/library/postgres:16",
    "mcr.microsoft.com/mssql/server:2022-latest",
    "couchbase/server:community",
    "docker.elastic.co/elasticsearch/elasticsearch:8.15.3",
    "mongo@sha256:" + "a" * 64,
    "redis/redis-stack-server:7.2.0-v10",
    "bitnami/redis:7.2",
    "confluentinc/cp-kafka:7.6.0",
    "apache/kafka:3.8.0",
    "rabbitmq:3-management",
    "nginx:1.27-alpine",
    "envoyproxy/envoy:v1.31-latest",
    "localhost/valkey/valkey:8",
    "gvenzl/oracle-free:23-slim",
    "softwaremill/elasticmq-native:1.6.9",
    "redpandadata/redpanda:v24.1.1",
]

K8S_TWO_APPS = """
apiVersion: apps/v1
kind: Deployment
metadata: {name: web}
spec:
  template:
    spec:
      containers:
        - {name: web, image: "acme/web:1", resources: {requests: {cpu: 500m, memory: 512Mi}}}
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: api}
spec:
  replicas: 3
  template:
    spec:
      containers: [{name: api, image: "acme/api:1"}]
---
apiVersion: v1
kind: Service
metadata: {name: api-svc}
spec:
  selector: {app: nothing-matches}
  ports: [{port: 80}]
"""

K8S_PLACEHOLDER = (
    "apiVersion: apps/v1\nkind: Deployment\n"
    "metadata: {name: api, annotations: {archsim.dev/node: api}}\n"
    "spec:\n  replicas: 2\n  template:\n    spec:\n      containers:\n"
    "        - name: api\n          image: your-org/api:latest\n"
    "          resources: {requests: {cpu: 500m, memory: 512Mi}, limits: {memory: 1Gi}}\n"
)

HPA = """
apiVersion: apps/v1
kind: Deployment
metadata: {name: api}
spec:
  template:
    spec:
      containers: [{name: api, image: "acme/api:1"}]
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: api}
spec:
  scaleTargetRef: {kind: Deployment, name: api}
  minReplicas: 2
  maxReplicas: 9
  metrics:
    - type: Resource
      resource: {name: cpu, target: {type: Utilization, averageUtilization: 65}}
    - type: Resource
      resource: {name: memory, target: {type: Utilization, averageUtilization: 80}}
"""

HPA_V1 = """
apiVersion: apps/v1
kind: Deployment
metadata: {name: api}
spec:
  replicas: 3
  template:
    spec:
      containers: [{name: api, image: "acme/api:1"}]
---
apiVersion: autoscaling/v1
kind: HorizontalPodAutoscaler
metadata: {name: api}
spec:
  scaleTargetRef: {kind: Deployment, name: api}
  minReplicas: 2
  maxReplicas: 6
  targetCPUUtilizationPercentage: 70
"""

K8S_EVERYTHING = """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: shop
  labels: {app.kubernetes.io/part-of: Shop}
spec:
  replicas: 2
  selector: {matchLabels: {app: shop}}
  template:
    metadata: {labels: {app: shop, tier: web}}
    spec:
      containers:
        - name: shop
          image: acme/shop:2
          resources: {limits: {cpu: "1.5", memory: 2Gi}}
          env:
            - {name: DB_URL, value: "postgresql://orders-db:5432/shop"}
            - {name: CACHE_URL, value: "redis://cache.default.svc:6379"}
            - {name: QUEUE, value: "amqp://events:5672"}
            - {name: SEARCH, value: "http://[::1"}
            - {name: PLAIN, value: 42}
---
apiVersion: apps/v1
kind: StatefulSet
metadata: {name: orders-db}
spec:
  replicas: 3
  selector: {matchLabels: {app: orders-db}}
  template:
    metadata: {labels: {app: orders-db}}
    spec:
      containers: [{name: pg, image: "postgres:16", resources: {requests: {cpu: 2, memory: 8Gi}}}]
  volumeClaimTemplates:
    - metadata: {name: data}
      spec: {resources: {requests: {storage: 100Gi}}}
---
apiVersion: apps/v1
kind: StatefulSet
metadata: {name: events}
spec:
  replicas: 3
  template:
    spec:
      containers: [{name: kafka, image: "apache/kafka:3.8.0"}]
  volumeClaimTemplates:
    - spec: {resources: {requests: {storage: 50G}}}
---
apiVersion: apps/v1
kind: DaemonSet
metadata: {name: agent}
spec:
  template:
    spec:
      containers: [{name: agent, image: "acme/agent:1"}]
---
apiVersion: apps/v1
kind: DaemonSet
metadata: {name: cache}
spec:
  template:
    spec:
      containers: [{name: redis, image: "redis:7"}]
---
apiVersion: serving.knative.dev/v1
kind: Service
metadata: {name: thumbs}
spec:
  template:
    metadata:
      annotations:
        autoscaling.knative.dev/max-scale: "200"
        autoscaling.knative.dev/initial-scale: "0"
    spec:
      containerConcurrency: 4
      containers: [{name: thumbs, image: "acme/thumbs:1"}]
---
apiVersion: v1
kind: Service
metadata: {name: shop-svc}
spec:
  type: LoadBalancer
  selector: {app: shop, tier: web}
---
apiVersion: v1
kind: Service
metadata: {name: db}
spec:
  selector: {app.kubernetes.io/name: orders-db, role: primary}
---
apiVersion: v1
kind: Service
metadata: {name: orphan}
---
apiVersion: v1
kind: Service
metadata: {name: stripe, annotations: {archsim.dev/note: "third-party API"}}
spec: {type: ExternalName, externalName: api.stripe.com}
---
apiVersion: v1
kind: Service
metadata: {name: my-cdn}
spec: {type: ExternalName, externalName: cdn.example.com}
---
apiVersion: v1
kind: Service
metadata:
  name: bus
  annotations: {archsim.dev/note: "managed Pub/Sub-style service via pubsub", archsim.dev/node: bus}
spec: {type: ExternalName, externalName: bus.example.com}
---
apiVersion: v1
kind: Service
metadata:
  name: mail
  annotations:
    archsim.dev/type: external_api
    archsim.dev/technology: email
    archsim.dev/calls: shop
spec: {type: ExternalName, externalName: mail.example.com}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: shop}
spec:
  scaleTargetRef: {name: shop}
  minReplicas: 3
  maxReplicas: 12
  metrics:
    - {type: Resource, resource: {name: cpu, target: {type: AverageValue, averageValue: 500m}}}
    - {type: Pods, pods: {metric: {name: rps}}}
    - {type: External}
  behavior: {scaleUp: {policies: [{type: Percent, value: 50, periodSeconds: 30}]}}
---
apiVersion: autoscaling/v2beta1
kind: HorizontalPodAutoscaler
metadata: {name: knative}
spec:
  scaleTargetRef: {name: thumbs}
  metrics:
    - {type: Resource, resource: {name: cpu, targetAverageUtilization: 5}}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: none}
spec:
  scaleTargetRef: {name: missing}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: {name: db}
spec:
  scaleTargetRef: {name: orders-db}
  minReplicas: 2
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata: {name: edge}
spec:
  rules:
    - http:
        paths:
          - backend: {service: {name: shop-svc}}
          - backend: {service: {name: nowhere}}
---
apiVersion: v1
kind: ConfigMap
metadata: {name: settings}
data: {LOG_LEVEL: debug}
---
apiVersion: v1
kind: Secret
metadata: {name: creds}
---
just a string
"""

COMPOSE_EVERYTHING = """
version: "3.9"
name: Store
x-defaults: &defaults
  restart: always
  logging: {driver: json-file}
  deploy:
    resources:
      limits: {cpus: "0.5", memory: 1536M}
services:
  web:
    <<: *defaults
    image: acme/web:2
    ports: ["8080:8080", 22:22]
    deploy: {replicas: 3, resources: {limits: {cpus: "1", memory: 2g}}}
    labels: ["archsim.platform=kubernetes", "team=web", "on"]
    environment:
      - API_URL=http://api:8000/v1
      - DEBUG
      - FEATURE=on
    depends_on: [api]
  api:
    <<: *defaults
    build: ./api
    scale: 2
    labels: {owner: 7, release: 2024-05-01, built: 2001-12-14t21:59:43.10-05:00, utc: 2002-12-14 01:02:03Z}
    environment:
      DATABASE_URL: postgresql://db:5432/store
      CACHE: redis://User:Secret@CACHE:6379/0
      QUEUE: "amqp://queue"
      MODE: 0777
      RATIO: 1:30
      FLAG: yes
      OFF: off
      NOTHING: ~
      SCI: 1e5
      HEX: 0x1F
    depends_on:
      db: {condition: service_healthy}
      queue: {}
    healthcheck: {test: ["CMD", "true"]}
    networks: [back]
    command: ["serve", "--workers", 4]
  db:
    image: postgres:16
    command: postgres -c max_connections=300
    deploy: {replicas: 2, resources: {limits: {memory: 4G}}}
    volumes: ["db-data:/var/lib/postgresql/data"]
    env_file: .env
    secrets: [pw]
  mysql:
    image: mysql:8.4
    command: ["--max-connections=500"]
  cache:
    image: redis:7
    container_name: cache
  queue:
    image: rabbitmq:3-management
    configs: [cfg]
  stats:
    image: prom/statsd-exporter:latest
    ports: ["9102:9102"]
  empty:
  replicas-bool:
    image: acme/flag:1
    deploy: {replicas: true}
x-external-dependencies:
  - {id: stripe, name: stripe, label: Stripe, technology: payments, calls: api}
  - {name: edge-cdn, technology: cdn}
  - {technology: email}
  - {}
networks: {back: {}}
secrets: {pw: {file: ./pw}}
"""

ARCHSIM_PARTS = (
    "services:\n  api:\n    image: your-org/api:latest\n"
    "x-archsim:\n  parts:\n    - {id: clients, type: database}\n    - not a part\n"
    "    - {id: users, type: traffic_source, label: Users, calls: api}\n"
)

CACHE_ASIDE = (
    "services:\n"
    "  api:\n    image: acme/api:1\n    ports: ['8080:8080']\n"
    "    environment:\n      CACHE: redis://cache:6379\n      DB: postgresql://db:5432/app\n"
    "  cache:\n    image: redis:7\n"
    "  db:\n    image: postgres:16\n"
)

CACHE_TO_THE_ONLY_DATABASE = (
    "services:\n"
    "  api:\n    image: acme/api:1\n    ports: ['8080:8080']\n"
    "    environment:\n      CACHE: redis://cache:6379\n"
    "  cache:\n    image: redis:7\n"
    "  db:\n    image: postgres:16\n"
)

SMALL_MEMORY = (
    "services:\n  api:\n    image: acme/api:1\n    deploy:\n"
    "      resources: {limits: {memory: 128M}}\n"
)

TWO_ENTRIES = (
    "services:\n"
    "  web:\n    image: acme/web:1\n    ports: ['80:80']\n"
    "  admin:\n    image: acme/admin:1\n    ports: ['81:81']\n"
    "  clients:\n    image: acme/clients:1\n"
)

ARCHSIM_LABELS = """
services:
  api:
    image: your-org/api:latest
    labels:
      archsim.node: api
      archsim.calls: "db!on-miss,cache:read,events/out:write!carry-on,gone"
      archsim.autoscaling: "2-6 replicas at 70% utilization"
      archsim.runtime: go
      archsim.platform: docker
  db:
    image: postgres:16
    labels:
      archsim.node: db
      archsim.technology: generic
      archsim.storage: "50 GiB per instance (size the volume to match)"
      archsim.topology: "6 instances modelled (shards 2, read replicas 1, RF 3); configure"
  cache:
    image: redis:7
    labels: {archsim.node: cache, archsim.technology: valkey}
  events:
    image: redis:7
    labels:
      archsim.node: events
      archsim.technology: redis_streams
      archsim.messaging: "12 partitions, 3 consumers"
  edge:
    image: nginx:1.27
    labels: {archsim.node: edge, archsim.rate-limit: "500 req/s (configure in the proxy)", archsim.calls: api}
x-external-dependencies:
  - {id: cdn, name: cdn, label: CDN, technology: cdn, calls: edge}
x-archsim:
  parts:
    - {id: phones, type: traffic_source, label: Phones, config: {timeoutMs: 3000}, calls: cdn}
    - {id: k8s, type: kubernetes_cluster, label: Cluster}
"""

ORDERED = [
    (
        "compose service written twice",
        "compose",
        "services:\n  web:\n    image: acme/web:1\n  db:\n    image: postgres:16\n"
        "  web:\n    image: acme/web:2\n",
    ),
    (
        "compose merges after a service's own keys",
        "compose",
        """
x-common: &common {restart: always, logging: {driver: local}}
x-more: &more {healthcheck: {test: [x]}, restart: never}
x-env: &env {CACHE: "redis://cache:6379"}
services:
  api:
    volumes: [data]
    <<: [*common, *more]
    image: acme/api:1
    networks: [back]
    environment: {DB: "postgresql://db:5432/x", <<: *env}
  cache:
    image: redis:7
  db:
    image: postgres:16
""",
    ),
    (
        "k8s pod labels as pairs",
        "k8s",
        """
apiVersion: apps/v1
kind: Deployment
metadata: {name: web}
spec:
  template:
    metadata: {labels: [[app, web]]}
    spec:
      containers: [{name: web, image: "acme/web:1"}]
---
apiVersion: v1
kind: Service
metadata: {name: web-svc}
spec: {type: LoadBalancer, selector: {app: web}}
""",
    ),
    (
        "compose parts with settings as pairs",
        "compose",
        "services:\n  api:\n    image: your-org/api:latest\n"
        "x-archsim:\n  parts:\n    - {id: users, type: traffic_source, config: [[timeoutMs, 3000]], calls: api}\n",
    ),
]
"""Mappings whose keys come in an order only PyYAML's merge and duplicate rules give, and values
Python's ``dict()`` reads as pairs."""

REFUSED = [
    ("compose labels a number", "compose", "services:\n  a:\n    labels: 5\n"),
    ("compose deploy a list", "compose", "services:\n  a:\n    deploy: [1]\n"),
    (
        "compose labels a number and deploy a list",
        "compose",
        "services:\n  a:\n    deploy: [1]\n    labels: 5\n",
    ),
    ("compose depends_on a number", "compose", "services:\n  a:\n    depends_on: 5\n"),
    ("compose external a number", "compose", "services:\n  a: {}\nx-external-dependencies: [1]\n"),
    ("compose external null", "compose", "services:\n  a: {}\nx-external-dependencies: [null]\n"),
    (
        "compose replicas !!int abc",
        "compose",
        "services:\n  a:\n    deploy: {replicas: !!int abc}\n",
    ),
    (
        "compose replicas !!int 0x1g",
        "compose",
        "services:\n  a:\n    deploy: {replicas: !!int 0x1g}\n",
    ),
    ("compose replicas !!int 09", "compose", "services:\n  a:\n    deploy: {replicas: !!int 09}\n"),
    (
        "compose replicas !!int 1:x",
        "compose",
        "services:\n  a:\n    deploy: {replicas: !!int 1:x}\n",
    ),
    (
        "compose replicas !!int empty",
        "compose",
        "services:\n  a:\n    deploy: {replicas: !!int ''}\n",
    ),
    ("compose image !!float abc", "compose", "services:\n  a:\n    image: !!float abc\n"),
    ("compose image !!float 1:x", "compose", "services:\n  a:\n    image: !!float 1:x\n"),
    ("compose image !!timestamp foo", "compose", "services:\n  a:\n    image: !!timestamp foo\n"),
    ("compose image !!bool maybe", "compose", "services:\n  a:\n    image: !!bool maybe\n"),
    ("compose image !!bool empty", "compose", "services:\n  a:\n    image: !!bool ''\n"),
    (
        "compose parts settings a text",
        "compose",
        "services:\n  api: {}\nx-archsim:\n  parts:\n    - {id: users, type: traffic_source, config: x}\n",
    ),
    (
        "compose parts settings a list of numbers",
        "compose",
        "services:\n  api: {}\nx-archsim:\n  parts:\n    - {id: users, type: traffic_source, config: [1]}\n",
    ),
    ("k8s no metadata", "k8s", "kind: Deployment\n"),
    ("k8s metadata a text", "k8s", "kind: Deployment\nmetadata: just-a-string\n"),
    ("k8s service spec a list", "k8s", "kind: Service\nmetadata: {name: s}\nspec: [1]\n"),
    ("k8s workload named by a number", "k8s", "kind: Deployment\nmetadata: {name: 5}\n"),
    ("k8s labels null", "k8s", "kind: Deployment\nmetadata: {name: a, labels: null}\n"),
    ("k8s annotations a list", "k8s", "kind: Deployment\nmetadata: {name: a, annotations: [x]}\n"),
    (
        "k8s container null",
        "k8s",
        "kind: Deployment\nmetadata: {name: a}\nspec: {template: {spec: {containers: [null]}}}\n",
    ),
    (
        "k8s env null",
        "k8s",
        "kind: Deployment\nmetadata: {name: a}\nspec: {template: {spec: {containers: [{image: x, env: [null]}]}}}\n",
    ),
    (
        "k8s claims a mapping",
        "k8s",
        "kind: StatefulSet\nmetadata: {name: a}\nspec: {volumeClaimTemplates: {b: 1}}\n",
    ),
    (
        "k8s claim null",
        "k8s",
        "kind: StatefulSet\nmetadata: {name: a}\nspec: {volumeClaimTemplates: [null]}\n",
    ),
    (
        "k8s config map data a list",
        "k8s",
        "kind: ConfigMap\nmetadata: {name: c}\ndata: [parts.json]\n",
    ),
    ("k8s config map data a number", "k8s", "kind: ConfigMap\nmetadata: {name: c}\ndata: 5\n"),
    ("k8s ingress named by a number", "k8s", "kind: Ingress\nmetadata: {name: 7}\n"),
    (
        "k8s external service named by a number",
        "k8s",
        "kind: Service\nmetadata: {name: 5}\nspec: {type: ExternalName}\n",
    ),
    (
        "k8s annotations and spec both lists",
        "k8s",
        "kind: Deployment\nmetadata: {name: a, annotations: [x]}\nspec: [1]\n",
    ),
    ("k8s workload named by a list", "k8s", "kind: Deployment\nmetadata: {name: [web]}\n"),
    ("k8s workload named by a mapping", "k8s", "kind: Deployment\nmetadata: {name: {a: 1}}\n"),
    ("k8s service named by a list", "k8s", "kind: Service\nmetadata: {name: [web]}\n"),
    (
        "k8s service named by a list, its selector matching",
        "k8s",
        "kind: Deployment\nmetadata: {name: web}\nspec: {template: {metadata: {labels: {app: web}}}}\n"
        "---\nkind: Service\nmetadata: {name: [web]}\nspec: {selector: {app: web}}\n",
    ),
    (
        "k8s service named by a list, its selector matching nothing",
        "k8s",
        "kind: Deployment\nmetadata: {name: web}\n---\nkind: Service\nmetadata: {name: [web]}\n"
        "spec: {selector: {app: other}}\n",
    ),
    (
        "k8s external service named by a list",
        "k8s",
        "kind: Service\nmetadata: {name: [web]}\nspec: {type: ExternalName}\n",
    ),
    (
        "k8s external service named by a mapping",
        "k8s",
        "kind: Service\nmetadata: {name: {a: 1}}\nspec: {type: ExternalName}\n",
    ),
    ("k8s ingress named by a list", "k8s", "kind: Ingress\nmetadata: {name: [edge]}\n"),
    (
        "k8s ingress backend a list",
        "k8s",
        "kind: Deployment\nmetadata: {name: web}\n---\nkind: Ingress\nmetadata: {name: edge}\n"
        "spec: {rules: [{http: {paths: [{backend: {service: {name: [web]}}}]}}]}\n",
    ),
    (
        "k8s ingress backend a mapping",
        "k8s",
        "kind: Deployment\nmetadata: {name: web}\n---\nkind: Ingress\nmetadata: {name: edge}\n"
        "spec: {rules: [{http: {paths: [{backend: {service: {name: {a: 1}}}}]}}]}\n",
    ),
    (
        "k8s ingress rule null",
        "k8s",
        "kind: Ingress\nmetadata: {name: edge}\nspec: {rules: [null]}\n",
    ),
    (
        "k8s ingress path null",
        "k8s",
        "kind: Ingress\nmetadata: {name: edge}\nspec: {rules: [{http: {paths: [null]}}]}\n",
    ),
    (
        "k8s knative max scale a word",
        "k8s",
        "apiVersion: serving.knative.dev/v1\nkind: Service\nmetadata: {name: f}\n"
        "spec: {template: {metadata: {annotations: {autoscaling.knative.dev/max-scale: lots}}}}\n",
    ),
    (
        "k8s knative max scale a list",
        "k8s",
        "apiVersion: serving.knative.dev/v1\nkind: Service\nmetadata: {name: f}\n"
        "spec: {template: {metadata: {annotations: {autoscaling.knative.dev/max-scale: [1]}}}}\n",
    ),
    (
        "k8s hpa min replicas a word",
        "k8s",
        "kind: Deployment\nmetadata: {name: a}\n---\nkind: HorizontalPodAutoscaler\nmetadata: {name: h}\n"
        "spec: {scaleTargetRef: {name: a}, minReplicas: x}\n",
    ),
    (
        "k8s hpa min replicas a list",
        "k8s",
        "kind: Deployment\nmetadata: {name: a}\n---\nkind: HorizontalPodAutoscaler\nmetadata: {name: h}\n"
        "spec: {scaleTargetRef: {name: a}, minReplicas: [1]}\n",
    ),
    (
        "k8s hpa policy null",
        "k8s",
        "kind: Deployment\nmetadata: {name: a}\n---\nkind: HorizontalPodAutoscaler\nmetadata: {name: h}\n"
        "spec: {scaleTargetRef: {name: a}, behavior: {scaleUp: {policies: [null]}}}\n",
    ),
    (
        "k8s hpa metric resource a text",
        "k8s",
        "kind: Deployment\nmetadata: {name: a}\n---\nkind: HorizontalPodAutoscaler\nmetadata: {name: h}\n"
        "spec: {scaleTargetRef: {name: a}, metrics: [{type: Resource, resource: cpu}]}\n",
    ),
]
"""Values of the wrong kind, refused as ``import_design`` refuses them: in Python's words, inside
``this is not ... the import can read (...)`` for an error of Python's runtime."""

SHAPES = {"compose": "a docker-compose file", "k8s": "Kubernetes manifests"}

UNREADABLE = [
    ("compose-no-services", "compose", "version: '3'\n"),
    ("compose-empty", "compose", ""),
    ("compose-a-list", "compose", "- a\n- b\n"),
    ("compose-services-a-list", "compose", "services: [a]\n"),
    ("compose-service-a-string", "compose", "services:\n  web: nginx\n"),
    ("k8s-empty", "k8s", ""),
    ("k8s-comment", "k8s", "# nothing\n"),
    ("k8s-not-a-manifest", "k8s", "just text\n"),
    ("k8s-only-lists", "k8s", "- a\n---\n- b\n"),
]


def template_cases() -> list[tuple[str, str, str, str | None]]:
    cases = []
    for path in sorted(EXAMPLES.glob("*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        for fmt in ("compose", "k8s"):
            text = export_document(document, fmt, REGISTRY)
            cases.append((f"{path.stem} {fmt}", fmt, text, None))
    return cases


def input_cases() -> list[tuple[str, str, str, str | None]]:
    cases: list[tuple[str, str, str, str | None]] = [
        ("voting-compose", "compose", (FIXTURES / "voting-compose.yml").read_text("utf-8"), None),
        (
            "voting-compose named",
            "compose",
            (FIXTURES / "voting-compose.yml").read_text("utf-8"),
            "Voting app",
        ),
        ("shop-k8s", "k8s", (FIXTURES / "shop-k8s.yaml").read_text("utf-8"), None),
        ("k8s two apps", "k8s", K8S_TWO_APPS, None),
        ("k8s placeholder requests", "k8s", K8S_PLACEHOLDER, None),
        ("k8s hpa v2", "k8s", HPA, None),
        ("k8s hpa v1", "k8s", HPA_V1, "Autoscaled"),
        ("k8s everything", "k8s", K8S_EVERYTHING, None),
        ("compose everything", "compose", COMPOSE_EVERYTHING, None),
        ("compose archsim parts", "compose", ARCHSIM_PARTS, None),
        ("compose cache-aside", "compose", CACHE_ASIDE, None),
        ("compose cache to the only database", "compose", CACHE_TO_THE_ONLY_DATABASE, None),
        ("compose small memory", "compose", SMALL_MEMORY, None),
        ("compose two entries", "compose", TWO_ENTRIES, None),
        ("compose archsim labels", "compose", ARCHSIM_LABELS, None),
    ]
    cases += [(f"tool {image}", "compose", compose_one(image), None) for image in TOOL_IMAGES]
    cases += [
        (f"infrastructure {image}", "compose", compose_one(image), None)
        for image in INFRASTRUCTURE_IMAGES
    ]
    return cases


def entry(name: str, fmt: str, text: str, title: str | None) -> Json:
    fn, importer = IMPORTERS[fmt]
    given = {"text": text, "name": title}
    try:
        document, report = importer(text, REGISTRY, title)
    except ValueError as exc:
        return {"name": name, "fn": fn, "input": given, "error": refusal(str(exc))}
    except (AttributeError, TypeError, KeyError, IndexError) as exc:
        # as import_design refuses a value of the wrong kind
        detail = f"missing {exc}" if isinstance(exc, KeyError) else str(exc)
        message = f"this is not {SHAPES[fmt]} the import can read ({detail})"
        return {"name": name, "fn": fn, "input": given, "error": refusal(message)}
    return {
        "name": name,
        "fn": fn,
        "input": given,
        "output": {"document": document, "report": report.to_dict()},
    }


def refusal(message: str) -> Json:
    return {"message": message, "type": "ValueError"}


def cases() -> list[Json]:
    out = [entry(*case) for case in input_cases() + template_cases()]
    out += [entry(name, fmt, text, None) for name, fmt, text in ORDERED + REFUSED + UNREADABLE]
    return out


def render() -> str:
    return json.dumps(cases(), indent=1, ensure_ascii=False, allow_nan=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args()
    text = render()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} differs from a fresh export", file=sys.stderr)
            return 1
        print(f"{OUT} is up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
