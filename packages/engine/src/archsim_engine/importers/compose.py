"""docker-compose -> design.

Services become parts by their image's full name (``postgres`` -> PostgreSQL, ``redis`` ->
Redis, Kafka images -> Kafka, ``nginx`` -> Nginx; exporters, UIs, migrators and anything else
-> a service), listed as guesses. ``deploy.replicas`` (one container when unset) and resource
limits become replicas and specs. Links come from ``depends_on`` and from environment values
that are URLs naming another service (``DATABASE_URL=postgresql://db:5432``); they are listed
as guesses. ArchSim's own exports carry ``archsim.*`` labels (part ids, links, some settings)
and an ``x-archsim`` block (clients, clusters); see docs/import.md for what comes back.
"""

from __future__ import annotations

import re
from typing import Any

from archsim_engine.importers.common import (
    MANAGED_TYPES,
    DesignBuilder,
    ImportReport,
    classify_image,
    finish,
    parse_cpu,
    parse_memory_gib,
    type_for_technology,
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

    tools: set[str] = set()
    for svc_name, svc in data["services"].items():
        svc = svc or {}
        if not isinstance(svc, dict):
            raise ValueError(f"service '{svc_name}' is not a mapping")
        labels = _labels(svc)
        image = str(svc.get("image") or "")
        node_id = b.unique_id(labels.get("archsim.node") or svc_name)
        by_service[svc_name] = node_id
        exported = "archsim.node" in labels  # ArchSim's own export states the part
        img = classify_image(image) if image else None
        type_ = img.type if img else "service"
        tech = img.technology if img else None
        stated = exported_technology(labels)
        if stated != "" and img and img.kind == "infrastructure":
            tech = stated  # an ArchSim export says which technology (or generic)
            type_ = type_for_technology(registry, type_, tech)  # Redis Streams: a queue
        if img and img.kind == "tool":
            tools.add(node_id)
        config: Json = {}
        if tech:
            config["technology"] = tech
        settings: list[str] = []
        deploy = svc.get("deploy") or {}
        if type_ == "service":
            config.setdefault("platform", "docker")
            if isinstance(deploy.get("replicas"), int):
                config["replicas"] = deploy["replicas"]
                b.note(node_id, "replicas", "deploy.replicas")
                settings.append(f"replicas {config['replicas']}")
            elif isinstance(svc.get("scale"), int):
                config["replicas"] = svc["scale"]
                b.note(node_id, "replicas", "scale")
                settings.append(f"replicas {config['replicas']}")
            else:
                # Compose runs one container unless told otherwise (ArchSim's default is 2)
                config["replicas"] = 1
                b.note(node_id, "replicas", "not set: Docker Compose runs one container")
        limits = ((deploy.get("resources") or {}).get("limits")) or {}
        cpu, mem = parse_cpu(limits.get("cpus")), parse_memory_gib(limits.get("memory"))
        if cpu:
            config["cpuCores"] = cpu
            settings.append(f"{cpu:g} CPU")
        if mem:
            config["memoryGib"] = mem
            settings.append(f"{mem:g} GiB memory")
        for key in ("cpuCores", "memoryGib"):
            if key in config:
                b.note(node_id, key, "deploy.resources.limits")
        cmd = (
            " ".join(str(c) for c in svc.get("command") or [])
            if isinstance(svc.get("command"), list)
            else str(svc.get("command") or "")
        )
        if m := re.search(r"max_connections=(\d+)|--max-connections=(\d+)", cmd):
            config["maxConnections"] = int(m[1] or m[2])
            b.note(node_id, "maxConnections", "from the container command")
            settings.append(f"connection limit {config['maxConnections']}")
        read = apply_labels(config, labels, type_)
        if read:
            settings.append(f"{', '.join(read)} from its labels")
        if "archsim.calls" in labels:
            calls[node_id] = labels["archsim.calls"]
        what = f"image {image}" if image else "a build context"
        part = registry.get(type_).display_name + (f" ({tech})" if tech else "")
        if exported or img is None or image.startswith("your-org/"):
            # stated by ArchSim's labels, or an application built here: no image to guess from
            report.mapped.append(
                f"{svc_name}: {what} -> {part}" + (f"; {'; '.join(settings)}" if settings else "")
            )
        else:
            # the part type and technology come from the image name: a guess, with the reason
            if img.kind == "infrastructure":
                report.guessed.append(f"{svc_name}: {part}, because '{image}' {img.why}")
            else:
                report.guessed.append(f"{svc_name}: '{image}' {img.why}, so it became a service")
            if settings:
                report.mapped.append(f"{svc_name}: {'; '.join(settings)}")
        if type_ != "service" and "replicas" in deploy:
            report.ignored.append(
                f"{svc_name}.deploy.replicas (a {registry.get(type_).display_name.lower()}'s "
                "instances are set in the editor)"
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
            for target, port, types, extra in decode_calls(calls[src]):
                b.link(src, target, port, types, extra)
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
            for target, port, types, extra in decode_calls(text):
                b.link(src, target, port, types, extra)
    b.link_parts(parts)
    if parts:
        report.mapped.append(
            f"x-archsim: {', '.join(p['id'] for p in parts)} (parts ArchSim exported but nothing deploys)"
        )
    has_clients = any(p["type"] == "traffic_source" for p in parts)
    finish(b, report, published, add_entry=not has_clients, skip=tools)
    doc = b.document(lambda t: kind_of(t, registry))
    return doc, report
