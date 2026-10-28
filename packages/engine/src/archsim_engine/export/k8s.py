"""Kubernetes manifests (multi-document YAML) from an architecture document."""

from __future__ import annotations

import copy
import json
from typing import Any

from archsim_engine.export.compose import dependency_url
from archsim_engine.export.tech import (
    DeployNode,
    deploy_nodes,
    header,
    js_round,
    k8s_resources,
    k8s_storage,
    num,
    service_replicas,
    slug,
    specs_of,
    store_instances,
    tier_lines,
)
from archsim_engine.export.yaml import OMIT, comments, js_number, to_yaml
from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import EXPORT_PLACEHOLDER_RESOURCES, encode_calls, encode_parts

Json = dict[str, Any]
APP_PORT = 8080


def _meta(n: DeployNode, part: str, calls: str = "", extra: Json | None = None) -> Json:
    """Name and labels, plus the part's id (and its outgoing links on workloads) so an import
    of these manifests gives the design back."""
    annotations: Json = {"archsim.dev/node": n.node["id"]}
    if calls:
        annotations["archsim.dev/calls"] = calls
    annotations.update({k: v for k, v in (extra or {}).items() if v not in (None, "")})
    if n.tier == "experimental":
        annotations["archsim/tier"] = "experimental"
    return {
        "name": n.name,
        "labels": {"app.kubernetes.io/name": n.name, "app.kubernetes.io/part-of": part},
        "annotations": annotations,
    }


def _selector(n: DeployNode) -> Json:
    return {"app.kubernetes.io/name": n.name}


def _cluster_service(
    n: DeployNode,
    part: str,
    port: int,
    target: int,
    headless: bool = False,
    type_: str | None = None,
) -> Json:
    return {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": _meta(n, part),
        "spec": {
            "type": type_ if type_ else OMIT,
            "clusterIP": "None" if headless else OMIT,
            "selector": _selector(n),
            "ports": [{"name": "main", "port": port, "targetPort": target}],
        },
    }


def _env(n: DeployNode, deps: list[DeployNode]) -> Any:
    env = [{"name": k, "value": v} for k, v in (n.tech.env if n.tech else {}).items()]
    env += [
        {"name": f"{d.name.upper().replace('-', '_')}_URL", "value": dependency_url(d)}
        for d in deps
    ]
    return env if env else OMIT


def to_kubernetes(
    doc: Json, registry: ComponentRegistry, cpu_requests: dict[str, float] | None = None
) -> str:
    part = slug(doc["metadata"]["name"])
    nodes = deploy_nodes(doc, registry)
    by_id = {n.node["id"]: n for n in nodes}
    docs: list[Json] = []
    edges = doc["architecture"]["edges"]

    for n in nodes:
        node, kind, cfg, tech = n.node, n.kind, n.cfg, n.tech
        if kind == "traffic":
            continue
        calls = encode_calls([e for e in edges if e["source"] == node["id"]])
        deps: list[DeployNode] = []
        for e in edges:
            if e["source"] == node["id"]:
                d = by_id.get(e["target"])
                if d is not None and d.kind != "traffic" and d not in deps:
                    deps.append(d)

        # managed services and third-party APIs: an ExternalName service callers can resolve
        if kind == "external" or (tech and tech.managed):
            meta = _meta(
                n,
                part,
                calls,
                {
                    "archsim.dev/type": node["type"],
                    "archsim.dev/technology": str(cfg.get("technology") or "generic"),
                },
            )
            meta["annotations"]["archsim.dev/note"] = (
                "third-party API, replace externalName with its host"
                if kind == "external"
                else ((tech.note if tech else None) or "managed service")
            )
            docs.append(
                {
                    "apiVersion": "v1",
                    "kind": "Service",
                    "metadata": meta,
                    "spec": {"type": "ExternalName", "externalName": f"{n.name}.example.com"},
                }
            )
            continue

        if kind == "compute":
            runtime = cfg.get("technology")
            identity = {
                "archsim.dev/platform": cfg.get("platform"),
                "archsim.dev/runtime": runtime
                if isinstance(runtime, str) and runtime != "generic"
                else None,
            }
            container: Json = {
                "name": n.name,
                "image": f"your-org/{n.name}:latest",
                "ports": [{"containerPort": APP_PORT}],
                "env": _env(n, deps),
                "readinessProbe": {
                    "httpGet": {"path": "/healthz", "port": APP_PORT},
                    "periodSeconds": 5,
                },
                "resources": k8s_resources(cfg, (cpu_requests or {}).get(node["id"]))
                or copy.deepcopy(EXPORT_PLACEHOLDER_RESOURCES),
            }
            if cfg.get("platform") == "serverless":
                docs.append(
                    {
                        "apiVersion": "serving.knative.dev/v1",
                        "kind": "Service",
                        "metadata": _meta(n, part, calls, identity),
                        "spec": {
                            "template": {
                                "metadata": {
                                    "annotations": {
                                        "autoscaling.knative.dev/min-scale": "0",
                                        "autoscaling.knative.dev/initial-scale": js_number(
                                            service_replicas(cfg)
                                        ),
                                        "autoscaling.knative.dev/max-scale": js_number(
                                            num(cfg.get("concurrencyLimit"), 1000)
                                        ),
                                    }
                                },
                                "spec": {
                                    "containerConcurrency": max(
                                        1, num(cfg.get("workersPerReplica"), 1)
                                    ),
                                    "containers": [{**container, "readinessProbe": OMIT}],
                                },
                            }
                        },
                    }
                )
                continue
            docs.append(
                {
                    "apiVersion": "apps/v1",
                    "kind": "Deployment",
                    "metadata": _meta(n, part, calls, identity),
                    "spec": {
                        "replicas": service_replicas(cfg),
                        "selector": {"matchLabels": _selector(n)},
                        "template": {
                            "metadata": {"labels": _selector(n)},
                            "spec": {"containers": [container]},
                        },
                    },
                }
            )
            docs.append(_cluster_service(n, part, 80, APP_PORT))
            if cfg.get("autoscaling") is True:
                docs.append(
                    {
                        "apiVersion": "autoscaling/v2",
                        "kind": "HorizontalPodAutoscaler",
                        "metadata": _meta(n, part),
                        "spec": {
                            "scaleTargetRef": {
                                "apiVersion": "apps/v1",
                                "kind": "Deployment",
                                "name": n.name,
                            },
                            "minReplicas": max(1, num(cfg.get("minReplicas"), 1)),
                            "maxReplicas": max(
                                1, num(cfg.get("maxReplicas"), num(cfg.get("minReplicas"), 1))
                            ),
                            "metrics": [
                                {
                                    "type": "Resource",
                                    "resource": {
                                        "name": "cpu",
                                        "target": {
                                            "type": "Utilization",
                                            "averageUtilization": js_round(
                                                num(cfg.get("targetUtilization"), 0.6) * 100
                                            ),
                                        },
                                    },
                                }
                            ],
                            "behavior": {
                                "scaleUp": {
                                    "policies": [
                                        {
                                            "type": "Percent",
                                            "value": 100,
                                            "periodSeconds": max(
                                                1, js_round(num(cfg.get("scaleIntervalS"), 15))
                                            ),
                                        }
                                    ]
                                }
                            },
                        },
                    }
                )
            continue

        if tech is None:
            continue
        infra = {"archsim.dev/technology": str(cfg.get("technology") or "generic")}
        container = {
            "name": n.name,
            "image": tech.image,
            "ports": [{"containerPort": tech.port}],
            "env": _env(n, deps if kind == "network" else []),
            "args": list(tech.command) if tech.command else OMIT,
            "resources": k8s_resources(cfg) or OMIT,
        }
        if kind in ("database", "messaging"):
            instances = store_instances(cfg)
            annotations: Json = {}
            if instances > 1:
                annotations["archsim.dev/topology"] = (
                    f"shards {js_number(num(cfg.get('shards'), 1))}, read replicas {js_number(num(cfg.get('readReplicas'), 0))}, "
                    f"RF {js_number(num(cfg.get('replicationFactor'), 1))}: use an operator for replication/sharding"
                )
            if kind == "messaging":
                annotations["archsim.dev/messaging"] = (
                    f"{js_number(num(cfg.get('partitions'), 1))} partitions, {js_number(num(cfg.get('consumers'), 1))} consumers"
                )
            if tech.note:
                annotations["archsim.dev/note"] = tech.note
            meta = _meta(n, part, calls, infra)
            meta["annotations"].update(annotations)
            docs.append(
                {
                    "apiVersion": "apps/v1",
                    "kind": "StatefulSet",
                    "metadata": meta,
                    "spec": {
                        "serviceName": n.name,
                        "replicas": max(1, num(cfg.get("brokers"), 1))
                        if kind == "messaging"
                        else instances,
                        "selector": {"matchLabels": _selector(n)},
                        "template": {
                            "metadata": {"labels": _selector(n)},
                            "spec": {
                                "containers": [
                                    {
                                        **container,
                                        "volumeMounts": [
                                            {"name": "data", "mountPath": tech.data_path or "/data"}
                                        ],
                                    }
                                ]
                            },
                        },
                        "volumeClaimTemplates": [
                            {
                                "metadata": {"name": "data"},
                                "spec": {
                                    "accessModes": ["ReadWriteOnce"],
                                    "resources": {
                                        "requests": {"storage": k8s_storage(specs_of(cfg)[2] or 10)}
                                    },
                                },
                            }
                        ],
                    },
                }
            )
            docs.append(_cluster_service(n, part, tech.port, tech.port, headless=True))
            continue

        # caches and self-hosted load balancers / gateways
        docs.append(
            {
                "apiVersion": "apps/v1",
                "kind": "Deployment",
                "metadata": _meta(n, part, calls, infra),
                "spec": {
                    "replicas": 1,
                    "selector": {"matchLabels": _selector(n)},
                    "template": {
                        "metadata": {"labels": _selector(n)},
                        "spec": {"containers": [container]},
                    },
                },
            }
        )
        docs.append(
            _cluster_service(
                n,
                part,
                80 if kind == "network" else tech.port,
                tech.port,
                type_="LoadBalancer" if kind == "network" else None,
            )
        )

    parts = encode_parts(doc)
    if parts:
        docs.append(
            {
                "apiVersion": "v1",
                "kind": "ConfigMap",
                "metadata": {
                    "name": f"{part}-archsim",
                    "labels": {"app.kubernetes.io/part-of": part},
                    "annotations": {
                        "archsim.dev/note": "ArchSim parts nothing deploys (clients, clusters), "
                        "for importing these manifests back; safe to delete"
                    },
                },
                "data": {"parts.json": json.dumps(parts, separators=(",", ":"))},
            }
        )

    head = comments(
        [
            *header(doc["metadata"]["name"], "Kubernetes manifests"),
            "Apply into a namespace of your choice: kubectl apply -n <namespace> -f <file>.yaml",
            "Serverless services use Knative Serving; StatefulSets need a StorageClass.",
            *tier_lines(nodes),
        ]
    )
    body = "\n".join(f"---\n{to_yaml(d)}" for d in docs)
    return f"{head}\n{body}\n"
