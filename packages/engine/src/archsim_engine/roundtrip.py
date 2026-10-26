"""What ArchSim's exports carry so an import gives the design back: each part's outgoing links
as ``target[/port][:types]`` (comma separated) and the parts nothing deploys (clients, Kubernetes
clusters) as a list of ``{id, type, label, config, calls}``. Shared by the exporters and the
importers so the format has one definition."""

from __future__ import annotations

import json
from typing import Any

Json = dict[str, Any]

NOT_DEPLOYED = ("traffic_source", "kubernetes_cluster")

EXPORT_PLACEHOLDER_RESOURCES: Json = {
    "requests": {"cpu": "500m", "memory": "512Mi"},
    "limits": {"memory": "1Gi"},
}
"""Container resources the Kubernetes export writes for a service with no CPU or memory set.
An import skips exactly these, and only in ArchSim's own manifests (``archsim.dev/node``):
anywhere else they are someone's real requests."""


def encode_calls(edges: list[Json]) -> str:
    """``target[/port][:types]`` per outgoing link, comma separated (what the exporters write)."""
    parts = []
    for e in edges:
        port = e.get("sourcePort") or "out"
        types = (e.get("config") or {}).get("requestTypes") or ["read", "write"]
        text = e["target"] + (f"/{port}" if port != "out" else "")
        if sorted(types) != ["read", "write"]:
            text += ":" + "+".join(types)
        parts.append(text)
    return ",".join(parts)


def decode_calls(text: str) -> list[tuple[str, str | None, list[str] | None]]:
    out = []
    for item in (x.strip() for x in text.split(",")):
        if not item:
            continue
        target, _, types = item.partition(":")
        target, _, port = target.partition("/")
        out.append((target, port or None, types.split("+") if types else None))
    return out


def encode_parts(doc: Json) -> list[Json]:
    """The parts no export deploys (clients, Kubernetes clusters), with their outgoing links."""
    edges = doc["architecture"]["edges"]
    out = []
    for n in doc["architecture"]["nodes"]:
        if n["type"] not in NOT_DEPLOYED:
            continue
        part: Json = {"id": n["id"], "type": n["type"], "label": n.get("label") or n["id"]}
        if n.get("config"):
            part["config"] = n["config"]
        calls = encode_calls([e for e in edges if e["source"] == n["id"]])
        if calls:
            part["calls"] = calls
        out.append(part)
    return out


def parse_parts(value: Any) -> list[Json]:
    """Exported parts from a YAML list (Compose ``x-archsim.parts``) or a JSON string (the
    Kubernetes ConfigMap's ``parts.json``); malformed entries are skipped."""
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return []
    if not isinstance(value, list):
        return []
    return [
        p
        for p in value
        if isinstance(p, dict) and isinstance(p.get("id"), str) and p.get("type") in NOT_DEPLOYED
    ]
