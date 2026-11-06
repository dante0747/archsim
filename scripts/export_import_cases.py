"""Write the importers' test cases for the Java engine, or check the committed file.

Stage 3's importers come to Java in steps: the docker-compose and Kubernetes importers
(``import_compose``, ``import_k8s``), the Terraform importer (``import_terraform``), then
``import_design``, which validates what they build and which the golden files record. The
importers are checked on what this script asks the frozen engine (0.16.0) to import:

* the engine's own fixtures, and every template's compose file and Kubernetes manifests, as the
  exports write them (the round trip);
* what the engine's tests import: tools and look-alike images, infrastructure images by their full
  name, entry points, placeholder requests, autoscalers, cache misses, memory limits, ArchSim's
  own parts written badly;
* YAML as PyYAML reads it: merge keys, ``yes``/``on``/``off``, octal and base-60 numbers,
  timestamps, numbers as keys, and lists where mappings usually are;
* keys in the order only PyYAML's merge and duplicate rules give, and values ``dict()`` reads as
  pairs;
* Terraform plans and states: every resource type the importer maps, sized and unsized, what it
  attaches in its second pass, the links it reads from the configuration or guesses, and JSON as
  Python's ``json`` reads it (``NaN``, repeated keys, long whole numbers);
* the words of each refusal the importers raise themselves, and of each value of the wrong kind
  they refuse, as ``import_design`` refuses it. Text that is not JSON is here only where no value
  starts (``json``'s "Expecting value" at the start, a byte order mark): past that, the words are
  the parser's.

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
from archsim_engine.importers.terraform import import_terraform

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "archsim-engine/src/test/resources/io/archsim/engine/imports/cases.json"
FIXTURES = ROOT / "packages/engine/tests/fixtures/import"
EXAMPLES = ROOT / "examples"
REGISTRY = create_default_registry(include_plugins=False)
Json = dict[str, Any]

IMPORTERS = {
    "compose": ("archsim_engine.importers.compose.import_compose", import_compose),
    "k8s": ("archsim_engine.importers.k8s.import_k8s", import_k8s),
    "terraform": ("archsim_engine.importers.terraform.import_terraform", import_terraform),
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


def res(type_: str, name: str, values: Any = None, **extra: Any) -> Json:
    """A resource as ``terraform show -json`` writes it."""
    resource: Json = {"address": f"{type_}.{name}", "mode": "managed", "type": type_, "name": name}
    if values is not None:
        resource["values"] = values
    resource.update(extra)
    return resource


def plan(
    resources: list[Any],
    config: list[Any] | None = None,
    key: str = "planned_values",
    children: list[Any] | None = None,
) -> str:
    """A plan (or, with ``key="values"``, a state) holding resources and their configuration."""
    root: Json = {"resources": resources}
    if children is not None:
        root["child_modules"] = children
    out: Json = {key: {"root_module": root}}
    if config is not None:
        out["configuration"] = {"root_module": {"resources": config}}
    return json.dumps(out)


def mapping(name: str, queue: str, function: str) -> Json:
    """A Lambda event source mapping's configuration, naming its queue and function."""
    return {
        "address": f"aws_lambda_event_source_mapping.{name}",
        "expressions": {
            "event_source_arn": {"references": [f"{queue}.arn", queue]},
            "function_name": {"references": [f"{function}.arn", function]},
        },
    }


LAMBDA = {"runtime": "python3.12", "memory_size": 512}

TERRAFORM_AWS = plan(
    [
        res(
            "aws_db_instance",
            "main",
            {
                "identifier": "main",
                "engine": "postgres",
                "instance_class": "db.r6g.large",
                "allocated_storage": 100,
            },
        ),
        res("aws_db_instance", "replica_a", {"identifier": "ra", "replicate_source_db": "main"}),
        res(
            "aws_db_instance",
            "replica_b",
            {"identifier": "rb", "replicate_source_db": "arn:aws:rds:main"},
        ),
        res(
            "aws_db_instance",
            "legacy",
            {"engine": "oracle-ee", "instance_class": "db.x2g.16xlarge"},
        ),
        res(
            "aws_db_instance", "small", {"engine": "aurora-mysql", "instance_class": "db.t3.medium"}
        ),
        res("aws_db_instance", "odd", {"engine": "mariadb", "instance_class": "db.m5.metal"}),
        res("aws_db_instance", "bare", {}),
        res("aws_db_instance", "flat", {"engine": "sqlserver-ex", "instance_class": "large"}),
        res(
            "aws_rds_cluster",
            "aurora",
            {"cluster_identifier": "aurora", "engine": "aurora-postgresql"},
        ),
        res(
            "aws_rds_cluster_instance",
            "aurora_1",
            {"cluster_identifier": "aurora", "instance_class": "db.r6g.xlarge"},
        ),
        res(
            "aws_rds_cluster_instance",
            "aurora_2",
            {"cluster_identifier": "aurora", "instance_class": "db.r6g.xlarge"},
        ),
        res(
            "aws_rds_cluster_instance",
            "aurora_3",
            {"cluster_identifier": "aurora", "instance_class": "db.r6g.2xlarge"},
        ),
        res("aws_rds_cluster_instance", "lost", {"cluster_identifier": "elsewhere"}),
        res(
            "aws_elasticache_cluster",
            "memo",
            {"cluster_id": "memo", "engine": "memcached", "node_type": "cache.m7g.large"},
        ),
        res(
            "aws_elasticache_replication_group",
            "sessions",
            {"engine": "redis", "num_node_groups": 3, "node_type": "cache.r6g.xlarge"},
        ),
        res(
            "aws_elasticache_replication_group",
            "valkey",
            {"engine": "valkey", "num_node_groups": True, "node_type": "cache.c7gn.large"},
        ),
        res("aws_elasticache_cluster", "plain", {"node_type": "cache.t4g.micro"}),
        res(
            "aws_lambda_function",
            "api",
            {
                "function_name": "api",
                "runtime": "nodejs20.x",
                "memory_size": 1024,
                "reserved_concurrent_executions": 10,
            },
        ),
        res(
            "aws_lambda_function",
            "go",
            {
                "function_name": "go",
                "runtime": "provided.al2",
                "memory_size": 256.5,
                "reserved_concurrent_executions": -1,
            },
        ),
        res("aws_lambda_function", "bare_fn", {"function_name": "bare-fn"}),
        res(
            "aws_lambda_function",
            "flag",
            {"function_name": "flag", "runtime": "java21", "memory_size": True},
        ),
        res(
            "aws_lambda_function",
            "text",
            {"function_name": "text", "runtime": "dotnet8", "memory_size": "512"},
        ),
        res(
            "aws_sqs_queue",
            "orders",
            {"name": "orders", "fifo_queue": True, "visibility_timeout_seconds": 30},
        ),
        res("aws_sqs_queue", "plain_queue", {"name": "plain", "fifo_queue": False}),
        res(
            "aws_msk_cluster",
            "events",
            {
                "cluster_name": "events",
                "number_of_broker_nodes": 3,
                "broker_node_group_info": [{"instance_type": "kafka.m5.large"}],
            },
        ),
        res(
            "aws_msk_cluster",
            "bare_msk",
            {"broker_node_group_info": {"instance_type": "m5.xlarge"}},
        ),
        res("aws_msk_cluster", "odd_msk", {"broker_node_group_info": ["x"]}),
        res("aws_lb", "public", {"name": "public"}),
        res("aws_lb", "internal", {"name": "internal", "load_balancer_type": "network"}),
        res("aws_alb", "old", {"name": "old", "load_balancer_type": "application"}),
        res("aws_api_gateway_rest_api", "rest", {"name": "rest"}),
        res("aws_apigatewayv2_api", "http", {"name": "http"}),
        res("aws_cloudfront_distribution", "cdn", {}),
        res(
            "aws_dynamodb_table",
            "carts",
            {
                "name": "carts",
                "billing_mode": "PROVISIONED",
                "read_capacity": 5,
                "write_capacity": 2,
            },
        ),
        res(
            "aws_dynamodb_table",
            "events_table",
            {"name": "events", "billing_mode": "PAY_PER_REQUEST", "read_capacity": 5},
        ),
        res("aws_eks_cluster", "main", {"name": "main"}),
        res(
            "aws_eks_node_group",
            "workers",
            {
                "cluster_name": "main",
                "instance_types": ["m5.xlarge"],
                "scaling_config": [{"desired_size": 3, "min_size": 2, "max_size": 6}],
            },
        ),
        res("aws_eks_node_group", "orphans", {"cluster_name": "other"}),
        res("aws_ecs_service", "web", {"name": "web", "desired_count": 3}),
        res("aws_iam_role", "role", {"name": "role", "x": float("nan"), "y": float("inf")}),
        res("aws_s3_bucket", "assets", [1, 2]),
        res("aws_lambda_event_source_mapping", "orders", {}),
    ],
    [mapping("orders", "aws_sqs_queue.orders", "aws_lambda_function.api")],
)

TERRAFORM_GCP = plan(
    [
        res(
            "google_sql_database_instance",
            "pg",
            {
                "name": "pg",
                "database_version": "POSTGRES_15",
                "settings": [{"tier": "db-custom-2-7680"}],
            },
        ),
        res(
            "google_sql_database_instance",
            "my",
            {
                "name": "my",
                "database_version": "MYSQL_8_0",
                "settings": [{"tier": "db-n1-standard-2"}],
            },
        ),
        res(
            "google_sql_database_instance",
            "highmem",
            {
                "name": "hm",
                "database_version": "SQLSERVER_2019",
                "settings": {"tier": "db-n1-highmem-4"},
            },
        ),
        res(
            "google_sql_database_instance",
            "e2",
            {"name": "e2", "settings": [{"tier": "e2-standard-4"}]},
        ),
        res(
            "google_sql_database_instance",
            "micro",
            {"name": "micro", "settings": [{"tier": "db-f1-micro"}]},
        ),
        res("google_redis_instance", "cache", {"name": "cache", "memory_size_gb": 5}),
        res("google_pubsub_topic", "events", {"name": "events"}),
        res("google_pubsub_subscription", "events_sub", {"name": "events-sub"}),
        res("google_container_cluster", "gke", {"name": "gke"}),
        res(
            "google_container_node_pool",
            "pool",
            {
                "cluster": "gke",
                "node_count": 3,
                "node_config": [{"machine_type": "n2-highmem-8"}],
                "autoscaling": [{"min_node_count": 1, "max_node_count": 5}],
            },
        ),
        res(
            "google_container_node_pool",
            "pool_2",
            {
                "cluster": "gke",
                "initial_node_count": 2,
                "node_config": [{"machine_type": "c3-highcpu-4"}],
            },
        ),
        res("google_cloud_run_v2_service", "run", {"name": "run"}),
        res("google_cloud_run_service", "run_v1", {"name": "run-v1"}),
    ],
    key="values",
)

TERRAFORM_AZURE = plan(
    [
        res(
            "azurerm_postgresql_flexible_server",
            "pg",
            {"name": "pg", "sku_name": "GP_Standard_D2ds_v4", "storage_mb": 32768},
        ),
        res(
            "azurerm_mysql_flexible_server", "my", {"name": "my", "sku_name": "MO_Standard_E8s_v5"}
        ),
        res(
            "azurerm_postgresql_flexible_server",
            "burst",
            {"name": "burst", "sku_name": "B_Standard_B1ms"},
        ),
        res("azurerm_redis_cache", "cache", {"name": "cache"}),
        res(
            "azurerm_kubernetes_cluster",
            "aks",
            {
                "name": "aks",
                "default_node_pool": [
                    {
                        "vm_size": "Standard_D4s_v5",
                        "node_count": 3,
                        "enable_auto_scaling": True,
                        "min_count": 2,
                        "max_count": 5,
                    }
                ],
            },
        ),
        res(
            "azurerm_kubernetes_cluster",
            "aks_2",
            {
                "name": "aks-2",
                "default_node_pool": {
                    "vm_size": "Standard_D4ls_v5",
                    "min_count": 1,
                    "auto_scaling_enabled": True,
                    "max_count": 3,
                },
            },
        ),
        res(
            "azurerm_kubernetes_cluster",
            "aks_3",
            {"name": "aks-3", "default_node_pool": [{"vm_size": "Standard_F8s_v2"}]},
        ),
        res("azurerm_linux_function_app", "fn", {"name": "fn"}),
        res("azurerm_windows_function_app", "fn_win", {"name": "fn-win"}),
        res("azurerm_servicebus_queue", "queue", {"name": "queue"}),
        res("azurerm_servicebus_topic", "topic", {"name": "topic"}),
        res("azurerm_application_gateway", "gateway", {"name": "gateway"}),
    ]
)

TERRAFORM_MODULES = json.dumps(
    {
        "planned_values": {
            "root_module": {
                "resources": [
                    res("aws_lambda_function", "consumer", {"function_name": "consumer", **LAMBDA}),
                    res("aws_s3_bucket", "logs", {"bucket": "logs"}, mode="data"),
                ],
                "child_modules": [
                    {
                        "address": "module.queue",
                        "resources": [
                            res(
                                "aws_sqs_queue",
                                "jobs",
                                {"name": "jobs"},
                                address="module.queue.aws_sqs_queue.jobs",
                            ),
                            res(
                                "aws_lambda_event_source_mapping",
                                "jobs",
                                {},
                                address="module.queue.aws_lambda_event_source_mapping.jobs",
                            ),
                        ],
                        "child_modules": [
                            {
                                "resources": [
                                    res(
                                        "aws_sqs_queue",
                                        "dlq",
                                        {"name": "jobs-dlq"},
                                        address="module.queue.module.dlq.aws_sqs_queue.dlq",
                                    )
                                ]
                            }
                        ],
                    }
                ],
            }
        },
        "configuration": {
            "root_module": {
                "resources": [
                    {"address": "aws_lambda_function.consumer", "expressions": {"runtime": "x"}}
                ],
                "module_calls": {
                    "queue": {
                        "module": {
                            "resources": [
                                {
                                    "address": "aws_lambda_event_source_mapping.jobs",
                                    "expressions": {
                                        "event_source_arn": {
                                            "references": ["aws_sqs_queue.jobs.arn"]
                                        },
                                        "function_name": {
                                            "references": ["aws_lambda_function.consumer"]
                                        },
                                        "batch_size": {"constant_value": 10},
                                    },
                                }
                            ]
                        }
                    }
                },
            }
        },
    }
)

TERRAFORM_LINKS_ALL = plan(
    [
        res("aws_lb", "front", {"name": "front"}),
        res("aws_lb", "back", {"name": "back"}),
        res("aws_ecs_service", "a", {"name": "a", "desired_count": 2}),
        res("aws_ecs_service", "b", {"name": "b"}),
        res("aws_db_instance", "db", {"identifier": "db", "engine": "mysql"}),
        res("aws_cloudfront_distribution", "cdn", {}),
    ]
)

TERRAFORM_CDN_TO_THE_ONE_SERVICE = plan(
    [
        res("aws_cloudfront_distribution", "cdn", {}),
        res("aws_ecs_service", "site", {"name": "site"}),
        res("aws_lambda_function", "worker", {"function_name": "worker", **LAMBDA}),
        res("aws_sqs_queue", "jobs", {"name": "jobs"}),
        res("aws_lambda_event_source_mapping", "jobs", {}),
        res("aws_lambda_event_source_mapping", "unknown", {}),
    ],
    [
        mapping("jobs", "aws_sqs_queue.jobs", "aws_lambda_function.worker"),
        {
            "address": "aws_lambda_event_source_mapping.unknown",
            "expressions": {
                "event_source_arn": {"references": "aws_sqs_queue.jobs"},
                "function_name": {"constant_value": "worker"},
            },
        },
    ],
)

TERRAFORM_STORES_ONLY = plan(
    [
        res("aws_dynamodb_table", "a", {"name": "a"}),
        res("aws_sqs_queue", "b", {"name": "b"}),
        res("aws_cloudfront_distribution", "cdn", {}),
    ]
)

TERRAFORM_NAMES = plan(
    [
        res("aws_sqs_queue", "numbered", {"name": 5}),
        res("aws_sqs_queue", "listed", {"name": ["a", "b"]}),
        res("aws_sqs_queue", "unnamed", None),
        res("aws_sqs_queue", "x" * 80, {"name": "y" * 150}),
        res("aws_eks_cluster", "named_by_resource", {}),
        res(
            "aws_eks_node_group",
            "nodes",
            {
                "cluster_name": "named_by_resource",
                "instance_types": [],
                "scaling_config": {"desired_size": None},
            },
        ),
        res(
            "aws_eks_node_group",
            "text_types",
            {"cluster_name": "named_by_resource", "instance_types": "m5.large"},
        ),
        res("aws_db_instance", "numbered_source", {"identifier": "five", "replicate_source_db": 5}),
    ]
)

TERRAFORM_JSON = (
    '{"planned_values": {"root_module": {"resources": ['
    '{"address": "aws_s3_bucket.b", "type": "aws_s3_bucket", "values": {"n": NaN, "i": Infinity, "j": -Infinity}},'
    '{"address": "aws_sqs_queue.q", "type": "aws_sqs_queue", "values": {"name": "first", "name": "second"}},'
    '{"address": "aws_lambda_function.f", "type": "aws_lambda_function", "values": {"function_name": "f", '
    '"memory_size": 123456789012345678901234567890, "reserved_concurrent_executions": 1e2}}'
    "]}}}"
)

TERRAFORM_LONG_NUMBERS = plan(
    [
        res("aws_sqs_queue", "named", {"name": int("1" * 400)}),
        res("aws_sqs_queue", "fifo", {"name": "fifo", "fifo_queue": int("2" * 400)}),
        res(
            "aws_lambda_function",
            "reserved",
            {"function_name": "reserved", "reserved_concurrent_executions": int("3" * 400)},
        ),
    ]
)

TERRAFORM = [
    ("orders-aws-plan", "terraform", (FIXTURES / "orders-aws-plan.json").read_text("utf-8"), None),
    (
        "orders-aws-plan named",
        "terraform",
        (FIXTURES / "orders-aws-plan.json").read_text("utf-8"),
        "Orders",
    ),
    ("terraform aws", "terraform", TERRAFORM_AWS, None),
    ("terraform gcp state", "terraform", TERRAFORM_GCP, None),
    ("terraform azure", "terraform", TERRAFORM_AZURE, None),
    ("terraform modules", "terraform", TERRAFORM_MODULES, None),
    ("terraform links to every free service", "terraform", TERRAFORM_LINKS_ALL, None),
    ("terraform cdn to the one service", "terraform", TERRAFORM_CDN_TO_THE_ONE_SERVICE, None),
    ("terraform stores only", "terraform", TERRAFORM_STORES_ONLY, None),
    ("terraform names", "terraform", TERRAFORM_NAMES, None),
    ("terraform json as Python reads it", "terraform", TERRAFORM_JSON, None),
    ("terraform whole numbers too long for a float", "terraform", TERRAFORM_LONG_NUMBERS, None),
    (
        "terraform queue the mapping names, not one it prefixes",
        "terraform",
        plan(
            [
                res("aws_sqs_queue", "orders", {"name": "orders"}),
                res("aws_sqs_queue", "orders_dlq", {"name": "orders-dlq"}),
                res("aws_lambda_function", "fulfil", {"function_name": "fulfil", **LAMBDA}),
                res("aws_lambda_function", "redrive", {"function_name": "redrive", **LAMBDA}),
                res("aws_lambda_event_source_mapping", "dlq", {}),
                res("aws_lambda_event_source_mapping", "main", {}),
            ],
            [
                mapping("dlq", "aws_sqs_queue.orders_dlq", "aws_lambda_function.redrive"),
                mapping("main", "aws_sqs_queue.orders", "aws_lambda_function.fulfil"),
            ],
        ),
        None,
    ),
    (
        "terraform replica without its source, small function",
        "terraform",
        plan(
            [
                res(
                    "aws_db_instance",
                    "replica",
                    {"identifier": "r", "engine": "postgres", "replicate_source_db": "gone"},
                ),
                res(
                    "aws_lambda_function",
                    "api",
                    {"function_name": "api", "runtime": "nodejs20.x", "memory_size": 128},
                ),
            ],
            [],
        ),
        None,
    ),
]


def one(type_: str, values: Any, **extra: Any) -> str:
    return plan([res(type_, "x", values, **extra)])


TERRAFORM_REFUSED = [
    ("terraform not json", "terraform", "not json"),
    ("terraform empty", "terraform", ""),
    ("terraform whitespace", "terraform", "  \n  "),
    ("terraform hcl", "terraform", "resource aws_s3_bucket b {}"),
    ("terraform byte order mark", "terraform", "\ufeff{}"),
    ("terraform a word cut short", "terraform", "nul"),
    ("terraform a minus alone", "terraform", "-"),
    (
        "terraform function memory not a number",
        "terraform",
        one("aws_lambda_function", {"function_name": "f", "memory_size": float("nan")}),
    ),
    (
        "terraform storage not a number",
        "terraform",
        one("azurerm_postgresql_flexible_server", {"name": "p", "storage_mb": float("nan")}),
    ),
    (
        "terraform custom tier of too many digits",
        "terraform",
        one("google_sql_database_instance", {"settings": [{"tier": "db-custom-2-" + "1" * 5000}]}),
    ),
    (
        "terraform first-generation tier of too many digits",
        "terraform",
        one(
            "google_sql_database_instance", {"settings": [{"tier": "db-n1-standard-" + "1" * 5000}]}
        ),
    ),
    (
        "terraform machine type of too many digits",
        "terraform",
        one("google_sql_database_instance", {"settings": [{"tier": "e2-standard-" + "1" * 5000}]}),
    ),
    (
        "terraform azure size of too many digits",
        "terraform",
        one("azurerm_mysql_flexible_server", {"sku_name": "Standard_D" + "1" * 5000 + "s_v5"}),
    ),
    ("terraform a list", "terraform", "[]"),
    ("terraform a number", "terraform", "5"),
    ("terraform a text", "terraform", '"plan"'),
    ("terraform null", "terraform", "null"),
    ("terraform empty plan", "terraform", "{}"),
    ("terraform values a list", "terraform", '{"values": []}'),
    ("terraform values null", "terraform", '{"values": null, "planned_values": {}}'),
    ("terraform root module a list", "terraform", '{"values": {"root_module": [1]}}'),
    (
        "terraform resources a mapping",
        "terraform",
        '{"values": {"root_module": {"resources": {"a": 1}}}}',
    ),
    (
        "terraform resource without an address",
        "terraform",
        '{"values": {"root_module": {"resources": [{"type": "aws_lb"}]}}}',
    ),
    ("terraform resource a number", "terraform", '{"values": {"root_module": {"resources": [1]}}}'),
    (
        "terraform resource without a type",
        "terraform",
        '{"values": {"root_module": {"resources": [{"address": "a.b"}]}}}',
    ),
    (
        "terraform child modules a mapping",
        "terraform",
        '{"values": {"root_module": {"resources": [], "child_modules": {"a": 1}}}}',
    ),
    (
        "terraform child module a list",
        "terraform",
        '{"values": {"root_module": {"child_modules": [[1]]}}}',
    ),
    ("terraform values a list on a known type", "terraform", one("aws_lb", [1])),
    ("terraform values a list on an unknown type", "terraform", one("aws_s3_bucket", [1])),
    ("terraform engine a number", "terraform", one("aws_db_instance", {"engine": 5})),
    (
        "terraform instance class a number",
        "terraform",
        one("aws_db_instance", {"instance_class": 5}),
    ),
    (
        "terraform instance class a list",
        "terraform",
        one("aws_elasticache_cluster", {"node_type": ["a.b"]}),
    ),
    (
        "terraform reserved concurrency a text",
        "terraform",
        one("aws_lambda_function", {"reserved_concurrent_executions": "5"}),
    ),
    ("terraform runtime a number", "terraform", one("aws_lambda_function", {"runtime": 3})),
    (
        "terraform tier a number",
        "terraform",
        one("google_sql_database_instance", {"settings": [{"tier": 5}]}),
    ),
    (
        "terraform storage a text",
        "terraform",
        one("azurerm_postgresql_flexible_server", {"storage_mb": "1024"}),
    ),
    ("terraform sku a number", "terraform", one("azurerm_mysql_flexible_server", {"sku_name": 5})),
    (
        "terraform vm size a number",
        "terraform",
        one("azurerm_kubernetes_cluster", {"default_node_pool": [{"vm_size": 5}]}),
    ),
    (
        "terraform msk instance type a number",
        "terraform",
        one("aws_msk_cluster", {"broker_node_group_info": [{"instance_type": 5}]}),
    ),
    (
        "terraform cluster instances a text",
        "terraform",
        plan(
            [
                res("aws_rds_cluster", "c", {"cluster_identifier": "c"}),
                res(
                    "aws_rds_cluster_instance",
                    "i",
                    {"cluster_identifier": "c", "instance_class": 7},
                ),
            ]
        ),
    ),
    (
        "terraform node pool machine type a number",
        "terraform",
        plan(
            [
                res("google_container_cluster", "g", {"name": "g"}),
                res(
                    "google_container_node_pool",
                    "p",
                    {"cluster": "g", "node_config": [{"machine_type": 4}]},
                ),
            ]
        ),
    ),
    (
        "terraform configuration resource without an address",
        "terraform",
        plan([res("aws_lb", "a", {})], [{"expressions": {}}]),
    ),
    (
        "terraform configuration expressions a list",
        "terraform",
        plan([res("aws_lb", "a", {})], [{"address": "aws_lb.a", "expressions": [1]}]),
    ),
    (
        "terraform configuration module call a list",
        "terraform",
        json.dumps(
            {
                "values": {"root_module": {"resources": [res("aws_lb", "a", {})]}},
                "configuration": {"root_module": {"module_calls": {"m": [1]}}},
            }
        ),
    ),
    (
        "terraform configuration a list",
        "terraform",
        json.dumps(
            {
                "values": {"root_module": {"resources": [res("aws_lb", "a", {})]}},
                "configuration": [1],
            }
        ),
    ),
    ("terraform nested too deeply", "terraform", "[" * 20_000 + "]" * 20_000),
]
"""Terraform text the importer refuses: in its own words, or in Python's for a value of the wrong
kind, as ``import_design`` refuses it."""

SHAPES = {
    "compose": "a docker-compose file",
    "k8s": "Kubernetes manifests",
    "terraform": "`terraform show -json` output",
}

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
    except RecursionError:
        message = f"the text nests too deeply to read as {SHAPES[fmt]}"
        return {"name": name, "fn": fn, "input": given, "error": refusal(message)}
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
    out = [entry(*case) for case in input_cases() + template_cases() + TERRAFORM]
    refused = ORDERED + REFUSED + UNREADABLE + TERRAFORM_REFUSED
    out += [entry(name, fmt, text, None) for name, fmt, text in refused]
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
