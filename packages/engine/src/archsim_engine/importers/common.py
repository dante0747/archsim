"""Shared pieces of the importers: the design being built, the import report, image and size
parsing, and what ArchSim's own exports carry so they round-trip: each part's id and outgoing
links (``archsim.node`` / ``archsim.calls``, ``archsim.dev/node`` / ``archsim.dev/calls``) and
the parts nothing deploys (clients, Kubernetes clusters); see ``archsim_engine.roundtrip``."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse

from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import (
    decode_calls,
)
from archsim_engine.version import SCHEMA_VERSION

Json = dict[str, Any]

# image name (last path part, no tag) -> (part type, technology)
IMAGE_RULES: list[tuple[str, str, str]] = [
    (r"^(postgres|postgis|timescaledb|pgvector)", "database", "postgresql"),
    (r"^mysql", "database", "mysql"),
    (r"^mariadb", "database", "mariadb"),
    (r"^mssql|^server$", "database", "sqlserver"),
    (r"^oracle", "database", "oracle"),
    (r"^cockroach", "database", "cockroachdb"),
    (r"^yugabyte", "database", "yugabytedb"),
    (r"^mongo", "database", "mongodb"),
    (r"^cassandra", "database", "cassandra"),
    (r"^scylla", "database", "scylladb"),
    (r"^dynamodb", "database", "dynamodb"),
    (r"^couchbase", "database", "couchbase"),
    (r"^elasticsearch", "database", "elasticsearch"),
    (r"^opensearch$", "database", "opensearch"),
    (r"^(redis|redis-stack)$", "cache", "redis"),
    (r"^valkey", "cache", "valkey"),
    (r"^memcached", "cache", "memcached"),
    (r"^varnish", "cache", "varnish"),
    (r"^(kafka|cp-kafka|cp-server|redpanda)", "message_queue", "kafka"),
    (r"^rabbitmq", "message_queue", "rabbitmq"),
    (r"^nats", "message_queue", "nats"),
    (r"^elasticmq", "message_queue", "sqs"),
    (r"^(nginx|openresty)", "load_balancer", "nginx"),
    (r"^haproxy", "load_balancer", "haproxy"),
    (r"^envoy", "load_balancer", "envoy"),
    (r"^traefik", "load_balancer", "traefik"),
    (r"^kong", "load_balancer", "kong"),
]

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


def classify_image(image: str) -> tuple[str, str] | None:
    """(part type, technology) for a container image, or None for an application image."""
    path = (
        image.split("@")[0].rsplit(":", 1)[0]
        if ":" in image.split("/")[-1]
        else image.split("@")[0]
    )
    name = path.split("/")[-1].lower()
    if "mssql" in path.lower():
        return "database", "sqlserver"
    for pattern, type_, tech in IMAGE_RULES:
        if re.search(pattern, name):
            return type_, tech
    return None


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
