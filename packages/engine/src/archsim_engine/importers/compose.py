"""docker-compose -> design.

Services become parts by image (``postgres`` -> PostgreSQL, ``redis`` -> Redis, Kafka images
-> Kafka, ``nginx`` -> Nginx, anything else -> a service). ``deploy.replicas`` and resource
limits become replicas and specs. Links come from ``depends_on`` and from environment values
that are URLs naming another service (``DATABASE_URL=postgresql://db:5432``); they are listed
as guesses. ArchSim's own exports carry ``archsim.*`` labels (part ids, links, settings) and an
``x-archsim`` block (clients, clusters), read back exactly.
"""

from __future__ import annotations

import re
from typing import Any

from archsim_engine.importers.common import (
    MANAGED_TYPES,
    DesignBuilder,
    ImportReport,
    classify_image,
    parse_cpu,
    parse_memory_gib,
    url_host,
)
from archsim_engine.load import kind_of
from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import decode_calls, parse_parts

Json = dict[str, Any]
EXTERNAL_TECH = {"payments", "email", "sms", "identity", "llm", "object_storage"}


def _labels(svc: Json) -> dict[str, str]:
    raw = svc.get("labels") or {}
    if isinstance(raw, list):
        out = {}
        for item in raw:
            k, _, v = str(item).partition("=")
            out[k] = v
        return out
    return {str(k): str(v) for k, v in raw.items()}


def _env(svc: Json) -> dict[str, str]:
    raw = svc.get("environment") or {}
    if isinstance(raw, list):
        out = {}
        for item in raw:
            k, _, v = str(item).partition("=")
            out[k] = v
        return out
    return {str(k): str(v) for k, v in raw.items() if v is not None}


def _depends(svc: Json) -> list[str]:
    raw = svc.get("depends_on") or []
    return list(raw.keys()) if isinstance(raw, dict) else [str(x) for x in raw]


def label(labels: dict[str, str], name: str) -> str:
    """An ArchSim label (Compose ``archsim.<name>``) or annotation (``archsim.dev/<name>``)."""
    return labels.get(f"archsim.{name}") or labels.get(f"archsim.dev/{name}") or ""


def exported_technology(labels: dict[str, str]) -> str | None:
    """The technology an ArchSim export states (None for generic parts), or "" when it states
    none (not an ArchSim export: classify by image)."""
    tech = label(labels, "technology")
    if not tech:
        return ""
    return None if tech == "generic" else tech


def apply_labels(config: Json, labels: dict[str, str], type_: str) -> list[str]:
    """Settings ArchSim's exporters wrote as labels or annotations; returns what was read."""
    read = []
    if m := re.match(
        r"(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?) replicas at (\d+)% utilization",
        label(labels, "autoscaling"),
    ):
        config.update(
            autoscaling=True,
            minReplicas=int(float(m[1])),
            maxReplicas=int(float(m[2])),
            targetUtilization=int(m[3]) / 100,
        )
        read.append("autoscaling")
    if type_ == "service":
        if label(labels, "platform"):
            config["platform"] = label(labels, "platform")
            read.append("platform")
        if label(labels, "runtime"):
            config["technology"] = label(labels, "runtime")
            read.append("runtime")
    if m := re.match(r"([0-9.]+) GiB per instance", label(labels, "storage")):
        config["storageGib"] = float(m[1])
        read.append("storage")
    if m := re.search(r"shards (\d+), read replicas (\d+), RF (\d+)", label(labels, "topology")):
        config.update(shards=int(m[1]), readReplicas=int(m[2]), replicationFactor=int(m[3]))
        read.append("topology")
    if m := re.match(r"(\d+) partitions, (\d+) consumers", label(labels, "messaging")):
        config.update(partitions=int(m[1]), consumers=int(m[2]))
        read.append("partitions and consumers")
    if m := re.match(r"([0-9.]+) req/s", label(labels, "rate-limit")):
        config["rateLimitRps"] = float(m[1])
        read.append("rate limit")
    return read


def import_compose(
    text: str, registry: ComponentRegistry, name: str | None = None
) -> tuple[Json, ImportReport]:
    from archsim_engine.safe_yaml import load_yaml

    data = load_yaml(text) or {}
    if not isinstance(data, dict) or not isinstance(data.get("services"), dict):
        raise ValueError("not a docker-compose file: no 'services' mapping")
    report = ImportReport("docker-compose")
    b = DesignBuilder(
        registry, name or str(data.get("name") or "Imported from docker-compose"), "docker-compose"
    )
    by_service: dict[str, str] = {}
    calls: dict[str, str] = {}
    published: list[str] = []
    for key in data:
        if key not in (
            "services",
            "name",
            "volumes",
            "x-external-dependencies",
            "x-archsim",
            "version",
        ):
            report.ignored.append(f"top-level '{key}'")

    for svc_name, svc in data["services"].items():
        svc = svc or {}
        labels = _labels(svc)
        image = str(svc.get("image") or "")
        node_id = b.unique_id(labels.get("archsim.node") or svc_name)
        by_service[svc_name] = node_id
        cls = classify_image(image) if image else None
        app = cls is None
        type_, tech = ("service", None) if app else cls  # type: ignore[misc]
        stated = exported_technology(labels)
        if stated != "" and not app:
            tech = stated  # an ArchSim export says which technology (or generic)
        config: Json = {}
        if tech:
            config["technology"] = tech
        if type_ == "service":
            config.setdefault("platform", "docker")
        deploy = svc.get("deploy") or {}
        if isinstance(deploy.get("replicas"), int):
            config["replicas"] = deploy["replicas"]
        limits = ((deploy.get("resources") or {}).get("limits")) or {}
        cpu, mem = parse_cpu(limits.get("cpus")), parse_memory_gib(limits.get("memory"))
        if cpu:
            config["cpuCores"] = cpu
        if mem:
            config["memoryGib"] = mem
        cmd = (
            " ".join(svc.get("command") or [])
            if isinstance(svc.get("command"), list)
            else str(svc.get("command") or "")
        )
        if m := re.search(r"max_connections=(\d+)|--max-connections=(\d+)", cmd):
            config["maxConnections"] = int(m[1] or m[2])
            b.note(node_id, "maxConnections", "from the container command")
        if "replicas" in config:
            b.note(node_id, "replicas", "deploy.replicas")
        for key in ("cpuCores", "memoryGib"):
            if key in config:
                b.note(node_id, key, "deploy.resources.limits")
        read = apply_labels(config, labels, type_)
        if "archsim.calls" in labels:
            calls[node_id] = labels["archsim.calls"]
        what = f"image {image}" if image else "a build context"
        report.mapped.append(
            f"{svc_name}: {what} -> {registry.get(type_).display_name}"
            + (f" ({tech})" if tech else "")
            + (f"; replicas {config['replicas']}" if "replicas" in config else "")
            + (f"; read {', '.join(read)} from its labels" if read else "")
        )
        if app and image and not image.startswith("your-org/"):
            report.guessed.append(
                f"{svc_name}: '{image}' is not a known infrastructure image, so it became a service"
            )
        for k in svc:
            if k in (
                "volumes",
                "networks",
                "healthcheck",
                "restart",
                "build",
                "container_name",
                "env_file",
                "logging",
                "secrets",
                "configs",
            ):
                report.ignored.append(f"{svc_name}.{k}")
        if svc.get("ports"):
            published.append(node_id)
        b.add(node_id, type_, svc_name, config)

    for dep in data.get("x-external-dependencies") or []:
        tech = str(dep.get("technology") or "")
        node_id = b.unique_id(str(dep.get("id") or dep.get("name") or tech or "external"))
        label = str(dep.get("label") or dep.get("name") or node_id)
        if dep.get("calls"):
            calls[node_id] = str(dep["calls"])
        if tech in MANAGED_TYPES:
            b.add(node_id, MANAGED_TYPES[tech], label, {"technology": tech})
            report.mapped.append(f"{label}: managed {tech} (x-external-dependencies)")
        else:
            b.add(
                node_id,
                "external_api",
                label,
                {"technology": tech} if tech in EXTERNAL_TECH else {},
            )
            report.mapped.append(f"{label}: third-party API (x-external-dependencies)")

    parts = parse_parts((data.get("x-archsim") or {}).get("parts"))
    b.add_parts(parts)

    # links: exact from archsim.calls, else depends_on and URL environment values (guesses)
    for svc_name, svc in data["services"].items():
        src = by_service[svc_name]
        if src in calls:
            for target, port, types in decode_calls(calls[src]):
                b.link(src, target, port, types)
            continue
        targets = [(d, "depends_on") for d in _depends(svc or {})]
        for k, v in _env(svc or {}).items():
            host = url_host(v)
            if host and host in by_service:
                targets.append((host, k))
        for target, why in targets:
            dst = by_service.get(target)
            if dst and b.link(src, dst):
                report.guessed.append(
                    f"link {src} -> {dst} (from {why}); request types read and write assumed"
                )

    deployed = set(by_service.values())
    for src, text in calls.items():
        if src not in deployed:  # managed services and third-party APIs (x-external-dependencies)
            for target, port, types in decode_calls(text):
                b.link(src, target, port, types)
    b.link_parts(parts)
    if parts:
        report.mapped.append(
            f"x-archsim: {', '.join(p['id'] for p in parts)} (parts ArchSim exported but nothing deploys)"
        )
    if not any(p["type"] == "traffic_source" for p in parts):
        _add_clients(b, report, published, registry)
    doc = b.document(lambda t: kind_of(t, registry))
    return doc, report


def _add_clients(
    b: DesignBuilder, report: ImportReport, published: list[str], registry: ComponentRegistry
) -> None:
    """Clients are not infrastructure: one Clients part at the entry (assumed)."""
    called = {e["target"] for e in b.edges}
    kinds = {n["id"]: kind_of(n["type"], registry) for n in b.nodes}
    entry = [n for n in published if kinds[n] == "network"] or [
        n for n in published if n not in called
    ]
    if not entry:
        entry = [
            n["id"]
            for n in b.nodes
            if n["id"] not in called and kinds[n["id"]] in ("network", "compute")
        ]
    if not entry:
        return
    cid = b.unique_id("clients")
    b.add(cid, "traffic_source", "Clients", {})
    for target in entry:
        b.link(cid, target)
    report.guessed.append(
        f"a Clients part sending to {', '.join(entry)} (the entry point; clients are not in the file)"
    )
