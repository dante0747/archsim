"""docker-compose.yml scaffolding from an architecture document."""

from __future__ import annotations

import re
from typing import Any

from archsim_engine.export.tech import (
    DeployNode,
    compose_resources,
    deploy_nodes,
    header,
    js_round,
    num,
    service_replicas,
    slug,
    specs_of,
    store_instances,
    tier_lines,
)
from archsim_engine.export.yaml import comments, js_number, to_yaml
from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import encode_calls, encode_parts

Json = dict[str, Any]

SCHEME = {
    "postgresql": "postgresql",
    "cockroachdb": "postgresql",
    "yugabytedb": "postgresql",
    "mysql": "mysql",
    "mariadb": "mysql",
    "mongodb": "mongodb",
    "redis": "redis",
    "valkey": "redis",
    "redis_streams": "redis",
    "memcached": "memcached",
    "kafka": "kafka",
    "rabbitmq": "amqp",
    "nats": "nats",
    "cassandra": "cassandra",
    "scylladb": "cassandra",
}


def upper_env(name: str) -> str:
    return re.sub(r"[^A-Z0-9]+", "_", name.upper())


def dependency_url(t: DeployNode) -> str:
    """Connection URL a caller would use for a dependency (host = compose service name)."""
    scheme = SCHEME.get(t.technology or "", "http")
    return f"{scheme}://{t.name}:{t.tech.port if t.tech else 80}"


def to_docker_compose(doc: Json, registry: ComponentRegistry) -> str:
    nodes = deploy_nodes(doc, registry)
    by_id = {n.node["id"]: n for n in nodes}

    def deployable(n: DeployNode | None) -> bool:
        return bool(n) and n.kind not in ("traffic", "external") and not (n.tech and n.tech.managed)  # type: ignore[union-attr]

    services: Json = {}
    external: list[Json] = []
    lb_port = 8080
    edges = doc["architecture"]["edges"]

    for n in nodes:
        node, kind, cfg, name, tech = n.node, n.kind, n.cfg, n.name, n.tech
        if kind == "traffic":
            continue
        calls = encode_calls([e for e in edges if e["source"] == node["id"]])
        if kind == "external" or (tech and tech.managed):
            entry: Json = {
                "id": node["id"],
                "name": name,
                "label": node.get("label") or node["id"],
                "technology": n.technology or str(cfg.get("technology") or "generic"),
                "note": "third-party API, not deployed; point the callers at its URL"
                if kind == "external"
                else ((tech.note if tech else None) or "managed service"),
            }
            if calls:
                entry["calls"] = calls
            external.append(entry)
            continue
        targets: list[DeployNode] = []
        for e in edges:
            if e["source"] == node["id"]:
                t = by_id.get(e["target"])
                if t is not None and deployable(t) and t not in targets:
                    targets.append(t)
        deps = [d.name for d in targets]
        labels: Json = {"archsim.node": node["id"]}
        if calls:
            labels["archsim.calls"] = calls
        if n.tier == "experimental":
            labels["archsim.tier"] = "experimental"
        svc: Json = {}
        if kind == "compute":
            svc["image"] = f"your-org/{name}:latest"
            deploy: Json = {"replicas": service_replicas(cfg)}
            res = compose_resources(cfg)
            if res is not None:
                deploy["resources"] = res
            svc["deploy"] = deploy
            if cfg.get("autoscaling") is True:
                labels["archsim.autoscaling"] = (
                    f"{js_number(num(cfg.get('minReplicas'), 1))}-{js_number(num(cfg.get('maxReplicas'), 1))} replicas at "
                    f"{js_round(num(cfg.get('targetUtilization'), 0.6) * 100)}% utilization"
                )
            if cfg.get("platform"):
                labels["archsim.platform"] = str(cfg["platform"])
            if isinstance(cfg.get("technology"), str) and cfg["technology"] != "generic":
                labels["archsim.runtime"] = cfg["technology"]
            env = {f"{upper_env(t.name)}_URL": dependency_url(t) for t in targets}
            if env:
                svc["environment"] = env
        elif tech is not None:
            labels["archsim.technology"] = str(cfg.get("technology") or "generic")
            svc["image"] = tech.image
            if tech.env:
                svc["environment"] = dict(tech.env)
            if tech.command:
                svc["command"] = list(tech.command)
            if kind == "database" and n.technology == "postgresql":
                svc["command"] = [
                    "postgres",
                    "-c",
                    f"max_connections={js_number(num(cfg.get('maxConnections'), 100))}",
                ]
            if kind == "database" and n.technology in ("mysql", "mariadb"):
                svc["command"] = [
                    f"--max-connections={js_number(num(cfg.get('maxConnections'), 151))}"
                ]
            if kind == "network":
                svc["ports"] = [f"{lb_port}:{tech.port}"]
                lb_port += 1
                if num(cfg.get("rateLimitRps"), 0) > 0:
                    labels["archsim.rate-limit"] = (
                        f"{js_number(num(cfg.get('rateLimitRps'), 0))} req/s (configure in the proxy)"
                    )
            res = compose_resources(cfg)
            if res is not None:
                svc["deploy"] = {"resources": res}
            if kind in ("database", "messaging"):
                svc["volumes"] = [f"{name}-data:{tech.data_path or '/data'}"]
                storage = specs_of(cfg)[2]
                if storage is not None:
                    labels["archsim.storage"] = (
                        f"{js_number(storage)} GiB per instance (size the volume to match)"
                    )
                inst = store_instances(cfg)
                if inst > 1:
                    labels["archsim.topology"] = (
                        f"{js_number(inst)} instances modelled (shards {js_number(num(cfg.get('shards'), 1))}, read replicas "
                        f"{js_number(num(cfg.get('readReplicas'), 0))}, RF {js_number(num(cfg.get('replicationFactor'), 1))}); "
                        "configure replication/sharding separately"
                    )
                if kind == "messaging":
                    labels["archsim.messaging"] = (
                        f"{js_number(num(cfg.get('partitions'), 1))} partitions, {js_number(num(cfg.get('consumers'), 1))} consumers"
                    )
            if tech.note:
                labels["archsim.note"] = tech.note
        if deps:
            svc["depends_on"] = deps
        svc["labels"] = labels
        services[name] = svc

    volumes: Json = {}
    for svc in services.values():
        for v in svc.get("volumes", []):
            if isinstance(v, str):
                volumes[v.split(":")[0]] = {}

    src = next((n for n in nodes if n.kind == "traffic"), None)
    w = doc["workload"]
    body: Json = {"name": slug(doc["metadata"]["name"]), "services": services}
    if volumes:
        body["volumes"] = volumes
    if external:
        body["x-external-dependencies"] = external
    parts = encode_parts(doc)
    if parts:
        body["x-archsim"] = {"parts": parts}
    lines = header(doc["metadata"]["name"], "docker-compose scaffolding")
    if src is not None:
        lines.append(
            f"Modelled load: {src.node.get('label') or src.node['id']} send {js_number(w['rps'])} req/s "
            f"({js_round(w['readRatio'] * 100)}% reads)."
        )
    lines.append("App images (your-org/...) are placeholders for your own services.")
    lines += tier_lines(nodes)
    return f"{comments(lines)}\n{to_yaml(body)}\n"
