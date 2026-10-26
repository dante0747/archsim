"""Kubernetes manifests -> design.

Deployments, StatefulSets and Knative Services become parts by image; replicas, HPAs (min, max,
target utilization, scale-up period) and resource requests become settings and specs;
StatefulSet volume claims become storage; an Ingress becomes the gateway in front of the
services it routes to; ``ExternalName`` services become third-party APIs or managed services; a
Kubernetes cluster part holds the services. Links come from environment URLs naming another
workload's service (assumed) or, for ArchSim's own exports, the ``archsim.dev/calls``
annotation.
"""

from __future__ import annotations

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
from archsim_engine.importers.compose import (
    _add_clients,
    apply_labels,
    exported_technology,
)
from archsim_engine.load import kind_of
from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import decode_calls, parse_parts

Json = dict[str, Any]
WORKLOADS = ("Deployment", "StatefulSet", "DaemonSet")


def _container(spec: Json) -> Json:
    containers = ((spec.get("template") or {}).get("spec") or {}).get("containers") or []
    return containers[0] if containers else {}


def import_k8s(
    text: str, registry: ComponentRegistry, name: str | None = None
) -> tuple[Json, ImportReport]:
    from archsim_engine.safe_yaml import load_all_yaml

    docs = [d for d in load_all_yaml(text) if isinstance(d, dict)]
    if not docs:
        raise ValueError("no Kubernetes objects found")
    report = ImportReport("Kubernetes manifests")
    part_of = next(
        (
            d.get("metadata", {}).get("labels", {}).get("app.kubernetes.io/part-of")
            for d in docs
            if d.get("metadata")
        ),
        None,
    )
    b = DesignBuilder(
        registry, name or (part_of or "Imported from Kubernetes"), "Kubernetes manifests"
    )
    workloads: dict[str, Json] = {}
    services: list[Json] = []
    hpas: list[Json] = []
    ingresses: list[Json] = []
    parts: list[Json] = []  # ArchSim's clients and clusters (its exports' ConfigMap)
    for d in docs:
        kind, api = d.get("kind"), str(d.get("apiVersion", ""))
        if kind in WORKLOADS or (kind == "Service" and api.startswith("serving.knative.dev")):
            workloads[d["metadata"]["name"]] = d
        elif kind == "Service":
            services.append(d)
        elif kind == "HorizontalPodAutoscaler":
            hpas.append(d)
        elif kind == "Ingress":
            ingresses.append(d)
        elif kind == "ConfigMap" and "parts.json" in (d.get("data") or {}):
            parts += parse_parts(d["data"]["parts.json"])
        else:
            report.ignored.append(f"{kind} {d.get('metadata', {}).get('name', '')}".strip())

    by_name: dict[str, str] = {}  # workload or Service name -> part id
    calls: dict[str, str] = {}
    entry: list[str] = []
    compute: list[str] = []
    for wname, w in workloads.items():
        meta = w.get("metadata") or {}
        ann = {str(k): str(v) for k, v in (meta.get("annotations") or {}).items()}
        spec = w.get("spec") or {}
        knative = str(w.get("apiVersion", "")).startswith("serving.knative.dev")
        c = _container(spec)
        image = str(c.get("image") or "")
        cls = classify_image(image) if image else None
        type_, tech = ("service", None) if cls is None else cls
        stated = exported_technology(ann)
        if stated != "" and cls is not None:
            tech = stated  # an ArchSim export says which technology (or generic)
        node_id = b.unique_id(ann.get("archsim.dev/node") or wname)
        by_name[wname] = node_id
        config: Json = {"technology": tech} if tech else {}
        if type_ == "service":
            config["platform"] = "serverless" if knative else "kubernetes"
            if config["platform"] == "kubernetes" and not ann.get("archsim.dev/platform"):
                compute.append(node_id)
        res = c.get("resources") or {}
        req = res.get("requests") or res.get("limits") or {}
        cpu, mem = parse_cpu(req.get("cpu")), parse_memory_gib(req.get("memory"))
        placeholder = (
            type_ == "service" and req.get("cpu") == "500m" and req.get("memory") == "512Mi"
        )
        if cpu and not placeholder:
            config["cpuCores"] = cpu
            b.note(node_id, "cpuCores", "container resources")
        if mem and not placeholder:
            config["memoryGib"] = mem
            b.note(node_id, "memoryGib", "container resources")
        replicas = spec.get("replicas")
        if knative:
            tmpl = spec.get("template") or {}
            scaling = (tmpl.get("metadata") or {}).get("annotations") or {}
            max_scale = scaling.get("autoscaling.knative.dev/max-scale")
            if max_scale:
                config["concurrencyLimit"] = int(float(max_scale))
            initial = scaling.get("autoscaling.knative.dev/initial-scale")
            if initial:
                config["replicas"] = max(1, int(float(initial)))
                b.note(node_id, "replicas", "autoscaling.knative.dev/initial-scale")
            cc = (tmpl.get("spec") or {}).get("containerConcurrency")
            if isinstance(cc, int) and cc > 0:
                config["workersPerReplica"] = cc
        elif isinstance(replicas, int):
            if type_ == "service":
                config["replicas"] = replicas
                b.note(node_id, "replicas", "spec.replicas")
            elif type_ == "message_queue":
                config["brokers"] = replicas
                b.note(node_id, "brokers", "StatefulSet replicas")
        claims = spec.get("volumeClaimTemplates") or []
        if claims:
            storage = parse_memory_gib(
                (((claims[0].get("spec") or {}).get("resources") or {}).get("requests") or {}).get(
                    "storage"
                )
            )
            if storage and type_ in ("database", "message_queue"):
                config["storageGib"] = storage
                b.note(node_id, "storageGib", "volumeClaimTemplates storage request")
        read = apply_labels(config, ann, type_)
        if (
            type_ == "database"
            and "topology" not in read
            and isinstance(replicas, int)
            and replicas > 1
        ):
            config["readReplicas"] = replicas - 1
            b.note(
                node_id, "readReplicas", f"guessed: {replicas} pods as a primary and read replicas"
            )
            report.guessed.append(
                f"{wname}: {replicas} pods read as a primary and {replicas - 1} read replicas"
            )
        if "archsim.dev/calls" in ann:
            calls[node_id] = ann["archsim.dev/calls"]
        report.mapped.append(
            f"{w['kind']} {wname}: {image or 'no image'} -> {registry.get(type_).display_name}"
            + (f" ({tech})" if tech else "")
        )
        b.add(node_id, type_, wname, config)

    for svc in services:
        sname = svc["metadata"]["name"]
        spec = svc.get("spec") or {}
        if spec.get("type") == "ExternalName":
            svc_ann = svc["metadata"].get("annotations") or {}
            note = str(svc_ann.get("archsim.dev/note") or "")
            tech = next(
                (t for t in MANAGED_TYPES if t.replace("_", " ") in note or t in sname), None
            )
            stated_type = str(svc_ann.get("archsim.dev/type") or "")
            stated_tech = exported_technology({str(k): str(v) for k, v in svc_ann.items()})
            node_id = b.unique_id(str(svc_ann.get("archsim.dev/node") or sname))
            if "archsim.dev/calls" in svc_ann:
                calls[node_id] = str(svc_ann["archsim.dev/calls"])
            if stated_type in registry and stated_tech != "":
                b.add(
                    node_id, stated_type, sname, {"technology": stated_tech} if stated_tech else {}
                )
                report.mapped.append(
                    f"Service {sname} (ExternalName) -> {registry.get(stated_type).display_name}"
                )
            elif "third-party" in note or tech is None:
                b.add(node_id, "external_api", sname, {})
                report.mapped.append(f"Service {sname} (ExternalName) -> third-party API")
            else:
                b.add(node_id, MANAGED_TYPES[tech], sname, {"technology": tech})
                report.mapped.append(f"Service {sname} (ExternalName) -> managed {tech}")
            by_name[sname] = node_id
            continue
        selector = (spec.get("selector") or {}).get("app.kubernetes.io/name") or (
            spec.get("selector") or {}
        ).get("app")
        target = by_name.get(str(selector)) if selector else by_name.get(sname)
        if target:
            by_name.setdefault(sname, target)
            if spec.get("type") == "LoadBalancer":
                entry.append(target)

    for hpa in hpas:
        ref = (hpa.get("spec") or {}).get("scaleTargetRef", {}).get("name")
        node = b.node(by_name.get(str(ref), ""))
        if node is None:
            report.ignored.append(
                f"HorizontalPodAutoscaler {hpa['metadata']['name']} (no matching workload)"
            )
            continue
        spec = hpa["spec"]
        cfg = node["config"]
        cfg.update(
            autoscaling=True,
            minReplicas=int(spec.get("minReplicas") or 1),
            maxReplicas=int(spec.get("maxReplicas") or 1),
        )
        for m in spec.get("metrics") or []:
            goal = (m.get("resource") or {}).get("target") or {}
            if goal.get("averageUtilization"):
                cfg["targetUtilization"] = goal["averageUtilization"] / 100
        for key in ("minReplicas", "maxReplicas", "targetUtilization"):
            b.note(node["id"], key, "HorizontalPodAutoscaler")
        policies = (((spec.get("behavior") or {}).get("scaleUp") or {}).get("policies")) or []
        if policies and policies[0].get("periodSeconds"):
            cfg["scaleIntervalS"] = policies[0]["periodSeconds"]
        report.mapped.append(
            f"HorizontalPodAutoscaler {hpa['metadata']['name']} -> autoscaling on {node['id']}"
        )

    for ing in ingresses:
        gw = b.unique_id(ing["metadata"]["name"])
        b.add(gw, "load_balancer", ing["metadata"]["name"], {"technology": "nginx"})
        report.mapped.append(f"Ingress {ing['metadata']['name']} -> gateway (Nginx-style)")
        for rule in (ing.get("spec") or {}).get("rules") or []:
            for path in ((rule.get("http") or {}).get("paths")) or []:
                backend = ((path.get("backend") or {}).get("service") or {}).get("name")
                if backend and by_name.get(backend):
                    b.link(gw, by_name[backend])
        entry.append(gw)

    b.add_parts(parts)
    for wname, w in workloads.items():
        src = by_name[wname]
        if src in calls:
            for target, port, types in decode_calls(calls[src]):
                b.link(src, target, port, types)
            continue
        for env in _container(w.get("spec") or {}).get("env") or []:
            host = url_host(env.get("value"))
            if host and host in by_name and b.link(src, by_name[host]):
                report.guessed.append(
                    f"link {src} -> {by_name[host]} (from {env.get('name')}); request types assumed"
                )

    workload_ids = {by_name[w] for w in workloads}
    for src, text in calls.items():
        if src not in workload_ids:  # managed services and third-party APIs (ExternalName)
            for target, port, types in decode_calls(text):
                b.link(src, target, port, types)
    b.link_parts(parts)
    if parts:
        report.mapped.append(
            f"ConfigMap parts.json: {', '.join(p['id'] for p in parts)} (parts ArchSim exported but nothing deploys)"
        )
    if compute and not any(p["type"] == "kubernetes_cluster" for p in parts):
        cid = b.unique_id("cluster")
        b.add(cid, "kubernetes_cluster", "Kubernetes cluster", {"technology": "kubernetes"})
        report.guessed.append(
            "a Kubernetes cluster part with the default node pool (the manifests do not describe nodes)"
        )
    if not any(p["type"] == "traffic_source" for p in parts):
        _add_clients(b, report, entry, registry)
    doc = b.document(lambda t: kind_of(t, registry))
    return doc, report
