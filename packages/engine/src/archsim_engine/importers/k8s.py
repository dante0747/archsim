"""Kubernetes manifests -> design.

Deployments, StatefulSets and Knative Services become parts by their image's full name
(listed as guesses); replicas (one pod when unset), HPAs (min, max, CPU target, scale-up
period) and resource requests become settings and specs; StatefulSet volume claims become
storage; an Ingress becomes the gateway in front of the services it routes to; ``ExternalName``
services become third-party APIs or managed services; a Kubernetes cluster part holds the
services (by their platform: it is never linked). Links come from environment URLs naming
another workload's service (assumed) or, for ArchSim's own exports, the ``archsim.dev/calls``
annotation.
"""

from __future__ import annotations

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
from archsim_engine.importers.compose import apply_labels, exported_technology
from archsim_engine.load import kind_of
from archsim_engine.registry import ComponentRegistry
from archsim_engine.roundtrip import (
    EXPORT_PLACEHOLDER_RESOURCES,
    decode_calls,
    parse_parts,
)

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
    tools: set[str] = set()
    unset_replicas: set[str] = set()  # parts whose replicas Kubernetes' default gave
    for wname, w in workloads.items():
        meta = w.get("metadata") or {}
        ann = {str(k): str(v) for k, v in (meta.get("annotations") or {}).items()}
        exported = "archsim.dev/node" in ann  # ArchSim's own export states the part
        spec = w.get("spec") or {}
        knative = str(w.get("apiVersion", "")).startswith("serving.knative.dev")
        c = _container(spec)
        image = str(c.get("image") or "")
        img = classify_image(image) if image else None
        type_ = img.type if img else "service"
        tech = img.technology if img else None
        stated = exported_technology(ann)
        if stated != "" and img and img.kind == "infrastructure":
            tech = stated  # an ArchSim export says which technology (or generic)
            type_ = type_for_technology(registry, type_, tech)  # Redis Streams: a queue
        node_id = b.unique_id(ann.get("archsim.dev/node") or wname)
        by_name[wname] = node_id
        if img and img.kind == "tool":
            tools.add(node_id)
        config: Json = {"technology": tech} if tech else {}
        settings: list[str] = []
        if type_ == "service":
            config["platform"] = "serverless" if knative else "kubernetes"
            if config["platform"] == "kubernetes" and not ann.get("archsim.dev/platform"):
                compute.append(node_id)
        res = c.get("resources") or {}
        req = res.get("requests") or res.get("limits") or {}
        cpu, mem = parse_cpu(req.get("cpu")), parse_memory_gib(req.get("memory"))
        # ArchSim's exporter writes these requests when a part has no CPU or memory set; only
        # its own manifests (marked with archsim.dev/node) carry them as a placeholder
        placeholder = exported and type_ == "service" and res == EXPORT_PLACEHOLDER_RESOURCES
        if cpu and not placeholder:
            config["cpuCores"] = cpu
            b.note(node_id, "cpuCores", "container resources")
            settings.append(f"{cpu:g} CPU")
        if mem and not placeholder:
            config["memoryGib"] = mem
            b.note(node_id, "memoryGib", "container resources")
            settings.append(f"{mem:g} GiB memory")
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
        elif w["kind"] == "DaemonSet":
            if type_ == "service":
                report.guessed.append(
                    f"DaemonSet {wname}: one pod per node, so its replicas are left at "
                    "ArchSim's default; set them to the node count"
                )
        else:
            stated_replicas = isinstance(replicas, int)
            if not stated_replicas:
                replicas = 1  # Kubernetes runs one pod when spec.replicas is not set
                unset_replicas.add(node_id)
            source = "spec.replicas" if stated_replicas else "not set: Kubernetes runs 1 pod"
            if type_ == "service":
                config["replicas"] = replicas
                b.note(node_id, "replicas", source)
                if stated_replicas:
                    settings.append(f"replicas {replicas}")
            elif type_ == "message_queue":
                config["brokers"] = replicas
                b.note(node_id, "brokers", f"StatefulSet replicas ({source})")
                if stated_replicas:
                    settings.append(f"{replicas} brokers")
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
                settings.append(f"{storage:g} GiB storage")
        read = apply_labels(config, ann, type_)
        if read:
            settings.append(f"{', '.join(read)} from its annotations")
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
        part = registry.get(type_).display_name + (f" ({tech})" if tech else "")
        if exported or img is None or image.startswith("your-org/"):
            report.mapped.append(
                f"{w['kind']} {wname}: {image or 'no image'} -> {part}"
                + (f"; {'; '.join(settings)}" if settings else "")
            )
        else:
            # the part type and technology come from the image name: a guess, with the reason
            if img.kind == "infrastructure":
                report.guessed.append(f"{w['kind']} {wname}: {part}, because '{image}' {img.why}")
            else:
                report.guessed.append(
                    f"{w['kind']} {wname}: '{image}' {img.why}, so it became a service"
                )
            if settings:
                report.mapped.append(f"{w['kind']} {wname}: {'; '.join(settings)}")
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
        target = _selected(spec.get("selector"), sname, workloads, by_name)
        if target is None:
            report.ignored.append(f"Service {sname} (its selector matches no workload)")
            continue
        by_name.setdefault(sname, target)
        if spec.get("type") == "LoadBalancer":
            entry.append(target)

    for hpa in hpas:
        hname = (hpa.get("metadata") or {}).get("name", "")
        spec = hpa.get("spec") or {}
        ref = (spec.get("scaleTargetRef") or {}).get("name")
        node = b.node(by_name.get(str(ref), ""))
        if node is None:
            report.ignored.append(f"HorizontalPodAutoscaler {hname} (no matching workload)")
            continue
        cfg = node["config"]
        cfg.update(
            autoscaling=True,
            minReplicas=int(spec.get("minReplicas") or 1),
            maxReplicas=int(spec.get("maxReplicas") or 1),
        )
        for key in ("minReplicas", "maxReplicas"):
            b.note(node["id"], key, "HorizontalPodAutoscaler")
        if node["id"] in unset_replicas and node["type"] == "service":
            cfg["replicas"] = cfg["minReplicas"]
            b.note(node["id"], "replicas", "not set: the HorizontalPodAutoscaler's minReplicas")
        cpu_target, why, other = _cpu_target(spec)
        if cpu_target is not None:
            cfg["targetUtilization"] = min(0.95, max(0.1, cpu_target))
            b.note(node["id"], "targetUtilization", why)
        else:
            report.guessed.append(
                f"HorizontalPodAutoscaler {hname} has no CPU utilization target: {node['id']} "
                "scales on CPU at ArchSim's default target"
            )
        if other:
            report.ignored.append(
                f"HorizontalPodAutoscaler {hname}: {', '.join(other)} metric"
                f"{'s' if len(other) > 1 else ''} (ArchSim scales on CPU utilization)"
            )
        policies = (((spec.get("behavior") or {}).get("scaleUp") or {}).get("policies")) or []
        if policies and policies[0].get("periodSeconds"):
            cfg["scaleIntervalS"] = policies[0]["periodSeconds"]
        report.mapped.append(f"HorizontalPodAutoscaler {hname} -> autoscaling on {node['id']}")

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
            for target, port, types, extra in decode_calls(calls[src]):
                b.link(src, target, port, types, extra)
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
            for target, port, types, extra in decode_calls(text):
                b.link(src, target, port, types, extra)
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
    has_clients = any(p["type"] == "traffic_source" for p in parts)
    finish(b, report, entry, add_entry=not has_clients, skip=tools)
    doc = b.document(lambda t: kind_of(t, registry))
    return doc, report


def _pod_labels(workload: Json) -> dict[str, str]:
    spec = workload.get("spec") or {}
    labels = dict(((spec.get("template") or {}).get("metadata") or {}).get("labels") or {})
    labels.update((spec.get("selector") or {}).get("matchLabels") or {})
    return {str(k): str(v) for k, v in labels.items()}


def _selected(
    selector: Any, service: str, workloads: dict[str, Json], by_name: dict[str, str]
) -> str | None:
    """The part a Service sends to: the workload whose pod labels its selector matches, else
    the workload named by the selector's ``app`` / ``app.kubernetes.io/name`` (or, with no
    selector, the Service's own name)."""
    if not isinstance(selector, dict) or not selector:
        return by_name.get(service)
    want = {str(k): str(v) for k, v in selector.items()}
    for wname, w in workloads.items():
        labels = _pod_labels(w)
        if all(labels.get(k) == v for k, v in want.items()):
            return by_name[wname]
    named = want.get("app.kubernetes.io/name") or want.get("app")
    return by_name.get(named) if named in workloads else None


def _cpu_target(spec: Json) -> tuple[float | None, str, list[str]]:
    """The CPU utilization target of an HPA (autoscaling/v1, v2beta1 or v2), how it was found,
    and the other metrics it names (memory, custom metrics: ArchSim scales on CPU only)."""
    target: float | None = None
    why = "HorizontalPodAutoscaler"
    other: list[str] = []
    v1 = spec.get("targetCPUUtilizationPercentage")
    if isinstance(v1, int | float) and not isinstance(v1, bool):
        target, why = v1 / 100, "HorizontalPodAutoscaler targetCPUUtilizationPercentage"
    metrics = [m for m in spec.get("metrics") or [] if isinstance(m, dict)]
    for m in metrics:
        res = m.get("resource") or m.get("containerResource") or {}
        if m.get("type") in ("Resource", "ContainerResource") and res.get("name") == "cpu":
            goal = res.get("target") or {}
            util = goal.get("averageUtilization") or res.get("targetAverageUtilization")
            if isinstance(util, int | float) and not isinstance(util, bool):
                target, why = util / 100, "HorizontalPodAutoscaler CPU metric"
                continue
            other.append("cpu (an average value, not a utilization)")
            continue
        other.append(str(res.get("name") or m.get("type") or "unnamed"))
    if target is None and v1 is None and not metrics:
        target, why = 0.8, "not set: Kubernetes' default of 80% CPU"
    return target, why, other
