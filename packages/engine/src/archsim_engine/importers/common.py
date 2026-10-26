"""Shared pieces of the importers: the design being built, the import report, image and size
parsing, the last steps that make an imported design run (an entry point, cache misses, memory
that fits), and what ArchSim's own exports carry so they round-trip: each part's id and
outgoing links (``archsim.node`` / ``archsim.calls``, ``archsim.dev/node`` /
``archsim.dev/calls``) and the parts nothing deploys (clients, Kubernetes clusters); see
``archsim_engine.roundtrip``."""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from typing import Any, Literal
from urllib.parse import urlparse

from archsim_engine.load import kind_of
from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import (
    decode_calls,
)
from archsim_engine.version import SCHEMA_VERSION

Json = dict[str, Any]

# Infrastructure images, matched against the whole repository name (registry host, tag and
# digest removed): the image's last path part must be one of the names, or the repository
# must end with one of the paths. Only the technologies' own images and their common
# distributions (Bitnami, Confluent, Timescale, PostGIS, Redis Stack...) are listed, so an
# exporter, UI or client named after a technology is not mistaken for it.
# (part type, technology, names, repository paths)
IMAGE_RULES: list[tuple[str, str, tuple[str, ...], tuple[str, ...]]] = [
    (
        "database",
        "postgresql",
        (
            "postgres",
            "postgresql",
            "postgis",
            "timescaledb",
            "timescaledb-ha",
            "pgvector",
            "postgresql-repmgr",
            "crunchy-postgres",
        ),
        (),
    ),
    ("database", "mysql", ("mysql", "mysql-server", "percona-server"), ()),
    ("database", "mariadb", ("mariadb", "mariadb-galera"), ()),
    ("database", "sqlserver", ("mssql-server-linux", "azure-sql-edge"), ("mssql/server",)),
    ("database", "oracle", ("oracle-xe", "oracle-free", "oracle-database"), ("oracle/database",)),
    ("database", "cockroachdb", ("cockroach",), ()),
    ("database", "yugabytedb", ("yugabyte",), ()),
    (
        "database",
        "mongodb",
        (
            "mongo",
            "mongodb",
            "mongodb-sharded",
            "mongodb-community-server",
            "mongodb-enterprise-server",
        ),
        (),
    ),
    ("database", "cassandra", ("cassandra",), ()),
    ("database", "scylladb", ("scylla",), ()),
    ("database", "dynamodb", ("dynamodb-local",), ()),
    ("database", "couchbase", ("couchbase",), ("couchbase/server",)),
    ("database", "elasticsearch", ("elasticsearch",), ()),
    ("database", "opensearch", ("opensearch",), ()),
    ("cache", "redis", ("redis", "redis-stack", "redis-stack-server", "redis-cluster"), ()),
    ("cache", "valkey", ("valkey", "valkey-cluster"), ()),
    ("cache", "memcached", ("memcached",), ()),
    ("cache", "varnish", ("varnish",), ()),
    (
        "message_queue",
        "kafka",
        ("kafka", "kafka-native", "cp-kafka", "cp-server", "redpanda"),
        (),
    ),
    ("message_queue", "rabbitmq", ("rabbitmq",), ()),
    ("message_queue", "nats", ("nats", "nats-streaming"), ()),
    ("message_queue", "sqs", ("elasticmq", "elasticmq-native"), ()),
    ("load_balancer", "nginx", ("nginx", "nginx-unprivileged", "openresty"), ()),
    (
        "load_balancer",
        "haproxy",
        ("haproxy", "haproxy-alpine", "haproxy-debian", "haproxy-ubuntu"),
        (),
    ),
    ("load_balancer", "envoy", ("envoy", "envoy-alpine", "envoy-distroless"), ()),
    ("load_balancer", "traefik", ("traefik",), ()),
    ("load_balancer", "kong", ("kong", "kong-gateway"), ()),
]

# Images that run next to infrastructure without being it: exporters, web UIs, admin consoles,
# migration jobs, clients and consumers named after a technology (``kafka-ui``,
# ``mongo-express``, ``postgres-migrations``, ``nats-consumer``), any ``*-exporter``, and the
# well-known tools below. They become plain services and are never taken as the entry point.
INFRA_WORDS = {
    "postgres",
    "postgresql",
    "pg",
    "mysql",
    "mysqld",
    "mariadb",
    "mssql",
    "oracle",
    "cockroach",
    "cockroachdb",
    "yugabyte",
    "mongo",
    "mongodb",
    "cassandra",
    "scylla",
    "dynamodb",
    "couchbase",
    "elasticsearch",
    "elastic",
    "opensearch",
    "redis",
    "valkey",
    "memcached",
    "varnish",
    "kafka",
    "redpanda",
    "rabbitmq",
    "rabbit",
    "nats",
    "sqs",
    "elasticmq",
    "nginx",
    "haproxy",
    "envoy",
    "traefik",
    "kong",
}
TOOL_WORD = re.compile(
    r"exporter|ui|gui|express|admin|commander|insight|console|dashboard|manager|monitor|"
    r"metrics|migrations?|migrator|migrate|consumer|producer|worker|client|cli|tools?|operator|"
    r"backup|restore|init|setup|seed|seeder|connect|sentinel|proxy|bouncer"
)
TOOL_IMAGE_NAMES = {
    "pgadmin",
    "pgadmin4",
    "adminer",
    "phpmyadmin",
    "redisinsight",
    "akhq",
    "kafdrop",
    "cmak",
    "flyway",
    "liquibase",
    "migrate",
    "pgbouncer",
    "pgpool",
    "proxysql",
    "kcat",
    "kafkacat",
}


@dataclass(frozen=True)
class ImageKind:
    """What an image is taken for: ``infrastructure`` (a part type and technology),
    ``tool`` or ``application`` (both a plain service), and why, for the import report."""

    kind: Literal["infrastructure", "tool", "application"]
    type: str
    technology: str | None
    why: str


def image_repository(image: str) -> str:
    """The repository of an image reference, lower case, without registry host, tag or digest:
    ``mcr.microsoft.com/mssql/server:2022`` -> ``mssql/server``."""
    ref = image.strip().split("@", 1)[0].lower()
    parts = ref.split("/")
    if ":" in parts[-1]:
        parts[-1] = parts[-1].rsplit(":", 1)[0]
    # a first part with a dot or a port, or "localhost", is a registry host
    if len(parts) > 1 and (re.search(r"[.:]", parts[0]) or parts[0] == "localhost"):
        parts = parts[1:]
    return "/".join(p for p in parts if p)


def is_tool_image(name: str) -> bool:
    """An exporter, UI, admin console, migration job or client (see ``INFRA_WORDS``)."""
    if name in TOOL_IMAGE_NAMES or re.search(r"[-_]exporter$", name):
        return True
    words = re.split(r"[-_.]", name)
    return any(w in INFRA_WORDS for w in words) and any(TOOL_WORD.fullmatch(w) for w in words)


def classify_image(image: str) -> ImageKind:
    """What a container image is, by its full repository name (see ``IMAGE_RULES``)."""
    repo = image_repository(image)
    name = repo.rsplit("/", 1)[-1]
    if is_tool_image(name):
        return ImageKind(
            "tool",
            "service",
            None,
            "looks like a tool that runs next to infrastructure (an exporter, UI, admin "
            "console, migration job, client or consumer), not the infrastructure itself",
        )
    for type_, tech, names, paths in IMAGE_RULES:
        if name in names or any(repo == p or repo.endswith(f"/{p}") for p in paths):
            return ImageKind("infrastructure", type_, tech, f"is a {tech} image (by its name)")
    return ImageKind("application", "service", None, "is not a known infrastructure image")


# managed or third-party technologies named in ArchSim's exports
MANAGED_TYPES = {
    "aws_alb": "load_balancer",
    "api_gateway": "load_balancer",
    "cdn": "cache",
    "pubsub": "message_queue",
    "sqs": "message_queue",
}

COLUMNS = {
    "traffic": 0,
    "network": 1,
    "compute": 2,
    "cache": 3,
    "messaging": 3,
    "database": 4,
    "external": 5,
    "other": 2,
}


def type_for_technology(registry: ComponentRegistry, type_: str, tech: str | None) -> str:
    """The part type a stated technology belongs to: an image can serve several (``redis``
    runs a Redis cache or Redis Streams), the technology says which."""
    if tech is None or type_ not in registry or tech in registry.get(type_).technology_profiles:
        return type_
    for other in ("database", "cache", "message_queue", "load_balancer"):
        if other in registry and tech in registry.get(other).technology_profiles:
            return other
    return type_


def parse_cpu(v: Any) -> float | None:
    """Kubernetes / Compose CPU: 2, "500m", "0.5"."""
    if v is None:
        return None
    s = str(v).strip()
    try:
        return round(float(s[:-1]) / 1000, 4) if s.endswith("m") else float(s)
    except ValueError:
        return None


_MEM = {
    "ki": 2**10,
    "mi": 2**20,
    "gi": 2**30,
    "ti": 2**40,
    "k": 1e3,
    "m": 1e6,
    "g": 1e9,
    "t": 1e12,
    "b": 1,
}


def parse_memory_gib(v: Any) -> float | None:
    """ "512Mi", "4Gi", "1536M", "4G", "512m" (Compose), plain bytes -> GiB."""
    if v is None:
        return None
    m = re.fullmatch(r"\s*([0-9.]+)\s*([A-Za-z]*)\s*", str(v))
    if not m:
        return None
    num, unit = float(m.group(1)), m.group(2).lower()
    if unit in ("", "b"):
        factor = 1.0
    elif unit in _MEM:
        factor = _MEM[unit]
    elif unit.endswith("b") and unit[:-1] in _MEM:
        factor = _MEM[unit[:-1]]
    else:
        return None
    # Compose "1536M" and "4G" are binary in practice (Docker treats them as MiB / GiB)
    if unit in ("m", "g", "mb", "gb", "k", "kb", "t", "tb"):
        factor = {"k": 2**10, "m": 2**20, "g": 2**30, "t": 2**40}[unit[0]]
    gib = num * factor / 2**30
    return round(gib, 4)


def url_host(value: Any) -> str | None:
    """The host of a URL-ish value (postgresql://db:5432, redis://cache, http://api:8080/x)."""
    if not isinstance(value, str) or "://" not in value:
        return None
    try:
        host = urlparse(value).hostname
    except ValueError:
        return None
    return host


@dataclass
class ImportReport:
    """What the import mapped, what it guessed (marked assumed), what it ignored, and links it
    suggests instead of guessing."""

    source: str
    mapped: list[str] = field(default_factory=list)
    guessed: list[str] = field(default_factory=list)
    ignored: list[str] = field(default_factory=list)
    suggestions: list[Json] = field(default_factory=list)
    issues: list[Json] = field(default_factory=list)

    def to_dict(self) -> Json:
        return {
            "source": self.source,
            "mapped": self.mapped,
            "guessed": self.guessed,
            "ignored": self.ignored,
            "suggestions": self.suggestions,
            "issues": self.issues,
        }


@dataclass
class DesignBuilder:
    registry: ComponentRegistry
    name: str
    source: str = "the imported file"
    nodes: list[Json] = field(default_factory=list)
    edges: list[Json] = field(default_factory=list)
    notes: dict[str, str] = field(default_factory=dict)  # provenance path -> how it was derived
    from_archsim: set[str] = field(default_factory=set)  # parts read back from ArchSim's export

    def note(self, node_id: str, key: str, text: str) -> None:
        """How an imported number was derived (shown with its provenance)."""
        self.notes[f"nodes/{node_id}/{key}"] = text

    def has(self, node_id: str) -> bool:
        return any(n["id"] == node_id for n in self.nodes)

    def unique_id(self, base: str) -> str:
        base = re.sub(r"[^A-Za-z0-9_.-]+", "-", base).strip("-.")[:60] or "part"
        if not re.match(r"^[A-Za-z0-9]", base):
            base = f"p{base}"
        out, i = base, 2
        while self.has(out):
            out = f"{base}-{i}"
            i += 1
        return out

    def add(self, node_id: str, type_: str, label: str, config: Json) -> Json:
        node = {"id": node_id, "type": type_, "label": label[:120], "config": config}
        self.nodes.append(node)
        return node

    def node(self, node_id: str) -> Json | None:
        return next((n for n in self.nodes if n["id"] == node_id), None)

    def link(
        self, source: str, target: str, port: str | None = None, types: list[str] | None = None
    ) -> bool:
        """Add a link (once). Inferred links are listed as guesses in the import report."""
        src, dst = self.node(source), self.node(target)
        if src is None or dst is None or source == target:
            return False
        if any(e["source"] == source and e["target"] == target for e in self.edges):
            return False
        model = self.registry.get(src["type"]) if src["type"] in self.registry else None
        outs = [p.name for p in model.out_ports()] if model else ["out"]
        if not outs:
            return False
        port = port if port in outs else outs[0]
        edge_id = f"{source}-{target}"
        n = 2
        while any(e["id"] == edge_id for e in self.edges):
            edge_id = f"{source}-{target}-{n}"
            n += 1
        self.edges.append(
            {
                "id": edge_id,
                "source": source,
                "sourcePort": port,
                "target": target,
                "targetPort": "in",
                "config": {"requestTypes": types or ["read", "write"]},
            }
        )
        return True

    def add_parts(self, parts: list[Json]) -> None:
        """ArchSim's own parts (clients, clusters) from an export; links come with ``link_parts``."""
        for p in parts:
            if self.has(p["id"]) or p["type"] not in self.registry:
                continue
            self.add(
                p["id"], p["type"], str(p.get("label") or p["id"]), dict(p.get("config") or {})
            )
            self.from_archsim.add(p["id"])

    def link_parts(self, parts: list[Json]) -> None:
        for p in parts:
            for target, port, types in decode_calls(str(p.get("calls") or "")):
                self.link(p["id"], target, port, types)

    def provenance(self) -> Json:
        """Every number the import set is assumed (someone wrote it in the file), with its source
        and, when it was derived or guessed, how."""
        out: Json = {}
        for n in self.nodes:
            for key, value in (n.get("config") or {}).items():
                if isinstance(value, bool) or not isinstance(value, int | float):
                    continue
                path = f"nodes/{n['id']}/{key}"
                origin = "ArchSim's export" if n["id"] in self.from_archsim else self.source
                entry: Json = {"kind": "assumed", "source": f"imported from {origin}"}
                if path in self.notes:
                    entry["note"] = self.notes[path]
                out[path] = entry
        return out

    def kind(self, node_id: str) -> str:
        n = self.node(node_id)
        return kind_of(n["type"], self.registry) if n else "other"

    def callers(self, node_id: str) -> list[str]:
        return [e["source"] for e in self.edges if e["target"] == node_id]

    def callees(self, node_id: str, port: str | None = None) -> list[str]:
        return [
            e["target"]
            for e in self.edges
            if e["source"] == node_id and (port is None or e["sourcePort"] == port)
        ]

    def document(self, kind_of: Any) -> Json:
        columns: dict[int, int] = {}
        layout: Json = {}
        for n in self.nodes:
            col = COLUMNS.get(kind_of(n["type"]), 2)
            row = columns.get(col, 0)
            columns[col] = row + 1
            layout[n["id"]] = {"x": col * 300, "y": row * 170}
        doc: Json = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": "archsim.architecture",
            "metadata": {"name": self.name[:120] or "Imported design"},
            "architecture": {"nodes": self.nodes, "edges": self.edges},
            "workload": {},
            "scenarios": [],
            "layout": {"nodes": layout},
        }
        prov = self.provenance()
        if prov:
            doc["provenance"] = prov
        return doc


# --- finishing an imported design so it validates and runs -----------------------------------


def fit_memory(b: DesignBuilder, report: ImportReport) -> None:
    """A service whose memory limit is below ArchSim's default memory at idle (plus one
    request) could hold no request at all, which makes the design invalid. Real processes do
    run in such limits (a 128 MB function, a 256M container), so the idle figure is lowered to
    half the limit, noted, and listed as a guess instead."""
    defaults = b.registry.get("service").default_config()
    for n in b.nodes:
        cfg = n["config"]
        mem = cfg.get("memoryGib")
        if n["type"] != "service" or "memoryBaselineMb" in cfg:
            continue
        if isinstance(mem, bool) or not isinstance(mem, int | float) or mem <= 0:
            continue
        baseline = float(defaults["memoryBaselineMb"])
        per_request = float(cfg.get("memoryPerRequestMb", defaults["memoryPerRequestMb"]))
        mib = mem * 1024
        if mib - baseline >= per_request:
            continue
        lowered = math.floor(mib / 2)
        cfg["memoryBaselineMb"] = lowered
        b.note(
            n["id"],
            "memoryBaselineMb",
            f"half the {mib:g} MiB memory limit: ArchSim's default of {baseline:g} MiB at idle "
            "does not fit in it",
        )
        report.guessed.append(
            f"{n['id']}: memory at idle {lowered} MiB, half its {mib:g} MiB limit (ArchSim's "
            f"default of {baseline:g} MiB does not fit); set the real figure"
        )


def wire_cache_misses(b: DesignBuilder, report: ImportReport) -> None:
    """A cache's misses go to the database its callers use (when there is exactly one), or to
    the only database in the design; the link is a guess. A cache whose misses go nowhere
    makes the design invalid."""
    databases = [n["id"] for n in b.nodes if n["type"] == "database"]
    for n in list(b.nodes):
        if n["type"] != "cache" or n["config"].get("technology") == "cdn":
            continue
        if b.callees(n["id"], "miss"):
            continue
        callers = b.callers(n["id"])
        used = list(
            dict.fromkeys(
                t
                for c in callers
                for t in b.callees(c)
                if (b.node(t) or {}).get("type") == "database"
            )
        )
        if len(used) == 1:
            users = [c for c in callers if used[0] in b.callees(c)]
            target, why = (
                used[0],
                f"{', '.join(users)} also call{'s' if len(users) == 1 else ''} it",
            )
        elif not used and len(databases) == 1:
            target, why = databases[0], "the only database"
        else:
            continue
        if b.link(n["id"], target, "miss", ["read"]):
            report.guessed.append(
                f"link {n['id']} -> {target} on its miss port (reads that miss; {why})"
            )


def add_clients(
    b: DesignBuilder, report: ImportReport, preferred: list[str], skip: set[str] | None = None
) -> None:
    """Clients are not infrastructure: one Clients part (assumed) sending to the entry point.
    The entry is a preferred part (published ports, a LoadBalancer Service, an Ingress, a load
    balancer in a plan), else a load balancer or service nothing calls; never a Kubernetes
    cluster part (services run on it, nothing sends to it) nor a tool. Clients send to one part,
    so several entries get a load balancer in front of them (assumed)."""
    skip = skip or set()
    called = {e["target"] for e in b.edges}

    def candidate(node_id: str) -> bool:
        n = b.node(node_id)
        return (
            n is not None
            and n["type"] != "kubernetes_cluster"
            and node_id not in skip
            and b.kind(node_id) in ("network", "compute")
        )

    preferred = [p for p in dict.fromkeys(preferred) if candidate(p)]
    entry = [n for n in preferred if b.kind(n) == "network"] or [
        n for n in preferred if n not in called
    ]
    if not entry:
        free = [n["id"] for n in b.nodes if candidate(n["id"]) and n["id"] not in called]
        entry = [n for n in free if b.kind(n) == "network"] or free
    if not entry:
        return
    cid = b.unique_id("clients")
    b.add(cid, "traffic_source", "Clients", {})
    if len(entry) == 1:
        b.link(cid, entry[0])
        report.guessed.append(
            f"a Clients part sending to {entry[0]} (the entry point; clients are not in the file)"
        )
        return
    gw = b.unique_id("entry")
    b.add(gw, "load_balancer", "Entry load balancer", {})
    for target in entry:
        b.link(gw, target)
    b.link(cid, gw)
    report.guessed.append(
        f"a Clients part sending to {gw}, a load balancer added in front of the entry points "
        f"{', '.join(entry)} (clients send to one part; clients are not in the file)"
    )


def finish(
    b: DesignBuilder,
    report: ImportReport,
    entries: list[str],
    *,
    add_entry: bool = True,
    skip: set[str] | None = None,
) -> None:
    """What every import does last: fit memory limits, route cache misses, add the Clients."""
    fit_memory(b, report)
    wire_cache_misses(b, report)
    if add_entry:
        add_clients(b, report, entries, skip)
