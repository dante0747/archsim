"""Container images, ports and deployment hints per technology, shared by the exporters."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from typing import Any

from archsim_engine.load import kind_of
from archsim_engine.registry import ComponentRegistry
from archsim_engine.technology import TIER_NOTE

Json = dict[str, Any]


@dataclass(frozen=True)
class TechSpec:
    image: str
    port: int
    managed: bool = False
    env: dict[str, str] = field(default_factory=dict)
    command: tuple[str, ...] = ()
    note: str | None = None
    data_path: str | None = None


def _t(image: str, port: int, **kw: Any) -> TechSpec:
    return TechSpec(image, port, **kw)


TECH: dict[str, TechSpec] = {
    # relational
    "postgresql": _t(
        "postgres:16",
        5432,
        data_path="/var/lib/postgresql/data",
        env={"POSTGRES_PASSWORD": "change-me"},
    ),
    "mysql": _t(
        "mysql:8.4", 3306, data_path="/var/lib/mysql", env={"MYSQL_ROOT_PASSWORD": "change-me"}
    ),
    "mariadb": _t(
        "mariadb:11", 3306, data_path="/var/lib/mysql", env={"MARIADB_ROOT_PASSWORD": "change-me"}
    ),
    "sqlserver": _t(
        "mcr.microsoft.com/mssql/server:2022-latest",
        1433,
        data_path="/var/opt/mssql",
        env={"ACCEPT_EULA": "Y", "MSSQL_SA_PASSWORD": "Change-me-123"},
    ),
    "oracle": _t(
        "gvenzl/oracle-free:23-slim",
        1521,
        data_path="/opt/oracle/oradata",
        env={"ORACLE_PASSWORD": "change-me"},
        note="community image; check licensing",
    ),
    "cockroachdb": _t(
        "cockroachdb/cockroach:v24.2.4",
        26257,
        data_path="/cockroach/cockroach-data",
        command=("start-single-node", "--insecure"),
    ),
    "yugabytedb": _t(
        "yugabytedb/yugabyte:latest",
        5433,
        data_path="/root/var",
        command=("bin/yugabyted", "start", "--background=false"),
    ),
    # NoSQL / search
    "mongodb": _t("mongo:7", 27017, data_path="/data/db"),
    "cassandra": _t("cassandra:5", 9042, data_path="/var/lib/cassandra"),
    "scylladb": _t("scylladb/scylla:6.1", 9042, data_path="/var/lib/scylla"),
    "dynamodb": _t(
        "amazon/dynamodb-local:latest",
        8000,
        data_path="/home/dynamodblocal/data",
        note="local stand-in for a managed DynamoDB-style table",
    ),
    "couchbase": _t("couchbase/server:community", 8091, data_path="/opt/couchbase/var"),
    "elasticsearch": _t(
        "docker.elastic.co/elasticsearch/elasticsearch:8.15.3",
        9200,
        data_path="/usr/share/elasticsearch/data",
        env={"discovery.type": "single-node", "xpack.security.enabled": "false"},
    ),
    "opensearch": _t(
        "opensearchproject/opensearch:2",
        9200,
        data_path="/usr/share/opensearch/data",
        env={"discovery.type": "single-node", "DISABLE_SECURITY_PLUGIN": "true"},
    ),
    # caches
    "redis": _t("redis:7", 6379),
    "valkey": _t("valkey/valkey:8", 6379),
    "memcached": _t("memcached:1.6", 11211),
    "varnish": _t("varnish:7", 80),
    "cdn": _t("", 443, managed=True, note="CDN edge (managed service)"),
    # edge
    "nginx": _t("nginx:1.27", 80),
    "haproxy": _t("haproxy:3.0", 80),
    "envoy": _t("envoyproxy/envoy:v1.31-latest", 10000),
    "traefik": _t("traefik:v3.1", 80),
    "kong": _t("kong:3.7", 8000, env={"KONG_DATABASE": "off"}),
    "aws_alb": _t("", 80, managed=True, note="managed load balancer"),
    "api_gateway": _t("", 443, managed=True, note="managed API gateway"),
    # messaging
    "kafka": _t("apache/kafka:3.8.0", 9092, data_path="/var/lib/kafka/data"),
    "rabbitmq": _t("rabbitmq:3-management", 5672, data_path="/var/lib/rabbitmq"),
    "nats": _t("nats:2", 4222, data_path="/data"),
    "redis_streams": _t("redis:7", 6379, data_path="/data"),
    "sqs": _t(
        "softwaremill/elasticmq-native:1.6.9",
        9324,
        data_path="/data",
        note="SQS-compatible local stand-in",
    ),
    "pubsub": _t("", 443, managed=True, note="managed Pub/Sub-style service"),
}
"""Images by the engine's technology values (the web app's copy keyed SQS and Pub/Sub as
``aws_sqs`` and ``google_pubsub``, which matched no technology, so those parts got no image)."""

DEFAULT_BY_KIND = {
    "database": "postgresql",
    "cache": "redis",
    "network": "nginx",
    "messaging": "rabbitmq",
}

TECH_NAMES = {
    "postgresql": "PostgreSQL", "mysql": "MySQL", "mariadb": "MariaDB", "sqlserver": "SQL Server",
    "oracle": "Oracle", "cockroachdb": "CockroachDB", "yugabytedb": "YugabyteDB", "mongodb": "MongoDB",
    "cassandra": "Cassandra", "scylladb": "ScyllaDB", "dynamodb": "DynamoDB-style", "couchbase": "Couchbase",
    "elasticsearch": "Elasticsearch", "opensearch": "OpenSearch", "redis": "Redis", "valkey": "Valkey",
    "memcached": "Memcached", "cdn": "CDN edge", "varnish": "Varnish", "nginx": "Nginx", "haproxy": "HAProxy",
    "envoy": "Envoy", "traefik": "Traefik", "kong": "Kong", "aws_alb": "ALB-style", "api_gateway": "API gateway",
    "kafka": "Kafka", "rabbitmq": "RabbitMQ", "sqs": "SQS-style", "pubsub": "Pub/Sub-style", "nats": "NATS",
    "redis_streams": "Redis Streams", "java": "Java", "nodejs": "Node.js", "go": "Go", "python": "Python",
    "dotnet": ".NET", "rust": "Rust", "php": "PHP", "ruby": "Ruby", "kubernetes": "Kubernetes",
    "eks": "EKS-style", "gke": "GKE-style", "aks": "AKS-style", "k3s": "k3s", "openshift": "OpenShift",
    "mobile": "Mobile app", "ios": "iOS", "android": "Android", "react_native": "React Native",
    "flutter": "Flutter", "browser": "Web browser", "react": "React", "nextjs": "Next.js", "vue": "Vue",
    "angular": "Angular", "svelte": "Svelte", "api_client": "API client", "rest": "REST / OpenAPI",
    "graphql": "GraphQL", "generic": "Generic",
}  # fmt: skip


@dataclass
class DeployNode:
    node: Json
    kind: str
    cfg: Json
    name: str
    technology: str | None
    tech: TechSpec | None
    tier: str | None


def dns_name(ident: str) -> str:
    """DNS-1123-ish: lowercase letters, digits and dashes."""
    s = re.sub(r"[^a-z0-9-]+", "-", ident.lower()).strip("-")[:63]
    return s or "node"


def slug(text: str) -> str:
    return dns_name(text or "architecture")[:40] or "architecture"


def num(v: Any, fallback: float) -> float:
    if isinstance(v, bool) or not isinstance(v, int | float) or not math.isfinite(v):
        return fallback
    return v


def js_round(x: float) -> int:
    """``Math.round``: halves go up."""
    return math.floor(x + 0.5)


def technology_label(registry: ComponentRegistry, type_: str, technology: Any) -> str | None:
    if not isinstance(technology, str) or not technology:
        return None
    if technology in TECH_NAMES:
        return TECH_NAMES[technology]
    if type_ in registry:
        preset = next((p for p in registry.get(type_).presets if p.technology == technology), None)
        if preset:
            return preset.name
    return re.sub(r"\b\w", lambda m: m.group().upper(), re.sub(r"[_-]+", " ", technology))


def deploy_nodes(doc: Json, registry: ComponentRegistry) -> list[DeployNode]:
    """The parts that become deployments (a Kubernetes cluster is where they run, not one)."""
    out = []
    for node in doc["architecture"]["nodes"]:
        if node["type"] == "kubernetes_cluster":
            continue
        model = registry.get(node["type"]) if node["type"] in registry else None
        cfg = {**(model.default_config() if model else {}), **(node.get("config") or {})}
        kind = kind_of(node["type"], registry)
        tech_value = cfg.get("technology")
        technology = (
            tech_value
            if isinstance(tech_value, str) and tech_value != "generic"
            else DEFAULT_BY_KIND.get(kind)
        )
        profile = (
            model.technology_profiles.get(tech_value)
            if model and isinstance(tech_value, str)
            else None
        )
        out.append(
            DeployNode(
                node=node,
                kind=kind,
                cfg=cfg,
                name=dns_name(node["id"]),
                technology=technology,
                tech=TECH.get(technology) if technology else None,
                tier=profile.tier if profile else None,
            )
        )
    return out


def specs_of(cfg: Json) -> tuple[float | None, float | None, float | None]:
    def v(x: Any) -> float | None:
        ok = isinstance(x, int | float) and not isinstance(x, bool) and math.isfinite(x) and x > 0
        return x if ok else None

    return v(cfg.get("cpuCores")), v(cfg.get("memoryGib")), v(cfg.get("storageGib"))


def _is_int(x: float) -> bool:
    return float(x).is_integer()


def k8s_cpu(c: float) -> str:
    return str(int(c)) if _is_int(c) else f"{js_round(c * 1000)}m"


def k8s_memory(g: float) -> str:
    return f"{int(g)}Gi" if _is_int(g) else f"{js_round(g * 1024)}Mi"


def k8s_storage(g: float) -> str:
    return f"{math.ceil(g)}Gi"


def k8s_resources(cfg: Json) -> Json | None:
    cpu, mem, _ = specs_of(cfg)
    if cpu is None and mem is None:
        return None
    q: Json = {}
    if cpu is not None:
        q["cpu"] = k8s_cpu(cpu)
    if mem is not None:
        q["memory"] = k8s_memory(mem)
    return {"requests": dict(q), "limits": dict(q)}


def compose_resources(cfg: Json) -> Json | None:
    cpu, mem, _ = specs_of(cfg)
    if cpu is None and mem is None:
        return None
    q: Json = {}
    if cpu is not None:
        from archsim_engine.export.yaml import js_number

        q["cpus"] = js_number(cpu)
    if mem is not None:
        q["memory"] = f"{int(mem)}G" if _is_int(mem) else f"{js_round(mem * 1024)}M"
    return {"limits": dict(q), "reservations": dict(q)}


def service_replicas(cfg: Json) -> float:
    if cfg.get("autoscaling") is True:
        return max(1, num(cfg.get("minReplicas"), num(cfg.get("replicas"), 1)))
    return max(1, num(cfg.get("replicas"), 1))


def store_instances(cfg: Json) -> float:
    shards = max(1, num(cfg.get("shards"), 1))
    copies = max(1 + num(cfg.get("readReplicas"), 0), num(cfg.get("replicationFactor"), 1))
    return shards * copies


def tier_lines(nodes: list[DeployNode]) -> list[str]:
    exp = [n for n in nodes if n.tier == "experimental"]
    if not exp:
        return []
    parts = ", ".join(
        f"{n.node.get('label') or n.node['id']}: {n.cfg.get('technology')}" for n in exp
    )
    return [f"Experimental technologies ({parts}): {TIER_NOTE}"]


def header(name: str, what: str) -> list[str]:
    return [
        f'Generated by ArchSim from "{name}": {what}.',
        "Starting-point scaffolding, not a production config. Review images, versions, credentials,",
        "resources, probes and storage before using it. Capacity values come from the model's assumptions;",
        "CPU, memory and storage come from each part's resource specs where you set them.",
    ]
