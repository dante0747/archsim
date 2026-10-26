"""Cost estimate: the monthly cost of a design from the cost assumptions on each part
(``costPerHour`` per instance; ``costPerMillionRequests`` for third-party APIs), and the cost per
million successful requests at the throughput a run achieved.

Price per instance-hour: the price set on the part; without one, the part's size (CPU, memory,
storage) at illustrative on-demand rates; without a size, the catalog default. A price equal to
the catalog default counts as not set (normalized and stored documents carry every default).

These are planning estimates built from assumptions, not quotes: every price is a config value
marked as an assumption in the inspector, and the per-size rates are illustrative.
"""

from __future__ import annotations

from typing import Any

from archsim_engine.load import Json, fixed, js_num, kind_of, num, round_half_up
from archsim_engine.registry import ComponentRegistry

HOURS_PER_MONTH = 730
SECONDS_PER_MONTH = HOURS_PER_MONTH * 3600
PRICE_PER_VCPU_HOUR = 0.037
"""Illustrative on-demand price per vCPU-hour (roughly a large cloud's general-purpose VMs)."""
PRICE_PER_GIB_HOUR = 0.0028
"""Illustrative on-demand price per GiB-hour of memory."""
PRICE_PER_STORAGE_GIB_MONTH = 0.08
"""Illustrative block storage price per GiB-month."""
CLUSTER_TYPE = "kubernetes_cluster"


def size_price_per_hour(
    cpu: float | None, memory_gib: float | None, storage_gib: float | None = None
) -> float | None:
    """Hourly price of one instance of this size, or None when CPU and memory are both unset."""
    if cpu is None and memory_gib is None:
        return None
    compute = (cpu or 0.0) * PRICE_PER_VCPU_HOUR + (memory_gib or 0.0) * PRICE_PER_GIB_HOUR
    disk = (storage_gib or 0.0) * PRICE_PER_STORAGE_GIB_MONTH / 730
    return round_half_up((compute + disk) * 10000) / 10000


def _defaults(node: Json, registry: ComponentRegistry) -> Json:
    return registry.get(node["type"]).default_config() if node["type"] in registry else {}


def config_value(node: Json, registry: ComponentRegistry, key: str) -> Any:
    """Config value with a fallback to the default for the part's type."""
    v = (node.get("config") or {}).get(key)
    return v if v is not None else _defaults(node, registry).get(key)


def hourly_price(node: Json, registry: ComponentRegistry) -> tuple[float | None, str | None]:
    """Price of one instance-hour and where it came from: set, size or default."""
    cfg = node.get("config") or {}
    fallback = num(_defaults(node, registry).get("costPerHour"))
    own = num(cfg.get("costPerHour"))
    if own is not None and own != fallback:
        return own, "set"
    if node["type"] == CLUSTER_TYPE:
        # a Kubernetes cluster is billed per node, at the node's size
        size = size_price_per_hour(
            num(config_value(node, registry, "nodeVcpu")),
            num(config_value(node, registry, "nodeMemoryGib")),
        )
    else:
        size = size_price_per_hour(
            num(cfg.get("cpuCores")), num(cfg.get("memoryGib")), num(cfg.get("storageGib"))
        )
    if size is not None:
        return size, "size"
    price = own if own is not None else fallback
    return (price, "default") if price is not None else (None, None)


def _plural(n: float, word: str) -> str:
    shown = js_num(n) if float(n).is_integer() else fixed(n, 1)
    return f"{shown} {word}{'' if n == 1 else 's'}"


def _mean(values: list[Any]) -> float | None:
    xs = [float(v) for v in values if num(v) is not None]
    return sum(xs) / len(xs) if xs else None


def instances_of(
    node: Json, registry: ComponentRegistry, node_report: Json | None = None
) -> tuple[float, str]:
    """Billable instances of a part and how that count was derived."""

    def get(k: str) -> float | None:
        return num(config_value(node, registry, k))

    if kind_of(node["type"], registry) == "traffic":
        return 0.0, "not billed"
    t = node["type"]
    series = ((node_report or {}).get("timeseries") or {}) if node_report else {}
    if t == "external_api":
        return 0.0, "per call"
    if t == "service":
        avg = _mean(series["replicas"]) if series.get("replicas") else None
        if avg is not None:
            return avg, f"avg {_plural(round_half_up(avg, 1), 'active instance')}"
        r = max(1.0, get("replicas") or 1.0)
        return r, _plural(r, "replica")
    if t == CLUSTER_TYPE:
        avg = _mean(series["nodes"]) if series.get("nodes") else None
        if avg is not None:
            return avg, f"avg {_plural(round_half_up(avg, 1), 'node')}"
        nodes = get("nodes")
        n = max(0.0, 3.0 if nodes is None else nodes)
        return n, _plural(n, "node")
    if t == "database":
        shards = max(1.0, get("shards") or 1.0)
        rf = max(1.0, get("replicationFactor") or 1.0)
        per_shard = max(rf, 1 + max(0.0, get("readReplicas") or 0.0))
        count = shards * per_shard
        basis = (
            f"{_plural(shards, 'shard')} × {_plural(per_shard, 'node')}"
            if shards > 1
            else _plural(per_shard, "node")
        )
        return count, basis
    if t == "message_queue":
        b = max(1.0, get("brokers") or 1.0)
        return b, _plural(b, "broker")
    if t in ("cache", "load_balancer"):
        n = max(1.0, get("nodes") or 1.0)
        return n, _plural(n, "node")
    replicas = get("replicas")
    n = max(1.0, replicas if replicas is not None else (get("instances") or 1.0))
    return n, _plural(n, "instance")


def _cluster_of(node: Json, cluster_ids: list[str]) -> str | None:
    """The cluster a service runs on: the one it names, else the only cluster in the design."""
    cfg = node.get("config") or {}
    if node["type"] != "service" or cfg.get("platform") != "kubernetes" or not cluster_ids:
        return None
    named = cfg.get("cluster")
    if isinstance(named, str) and named:
        return named if named in cluster_ids else None
    return cluster_ids[0] if len(cluster_ids) == 1 else None


def estimate_cost(
    nodes: list[Json], registry: ComponentRegistry, report: Json | None = None
) -> Json:
    """Monthly cost of ``nodes`` (a normalized design, or a run's ``input.architecture.nodes``).

    With a report, third-party calls are priced at the measured call rate, autoscaled services
    at their average active instances, and the cost per million successful requests is derived.
    """
    notes: list[str] = []
    by_node: list[Json] = []
    any_price = False
    missing_external_rate = False
    size_priced = False
    report_nodes = {n["id"]: n for n in (report or {}).get("nodes", [])}
    cluster_ids = [n["id"] for n in nodes if n["type"] == CLUSTER_TYPE]
    for n in nodes:
        if kind_of(n["type"], registry) == "traffic":
            continue
        nr = report_nodes.get(n["id"])
        tech = config_value(n, registry, "technology")
        technology = tech if isinstance(tech, str) else None
        label = n.get("label") or n["id"]
        on_cluster = _cluster_of(n, cluster_ids)
        if on_cluster:
            # pods of a service on Kubernetes run on the cluster's nodes, which carry the price
            cluster = next((x for x in nodes if x["id"] == on_cluster), None)
            by_node.append(
                {
                    "id": n["id"],
                    "label": label,
                    "type": n["type"],
                    "technology": technology,
                    "instances": 0,
                    "unitPrice": None,
                    "priceSource": None,
                    "unit": "hour",
                    "monthly": 0.0,
                    "basis": f"runs on {(cluster or {}).get('label') or on_cluster}",
                }
            )
            continue
        if n["type"] == "external_api":
            price = num(config_value(n, registry, "costPerMillionRequests"))
            rate = num(((nr or {}).get("summary") or {}).get("arrivalRps")) if nr else None
            if price is not None:
                any_price = True
            if price is not None and rate is None:
                missing_external_rate = True
            monthly = (
                rate * SECONDS_PER_MONTH * price / 1e6
                if price is not None and rate is not None
                else 0.0
            )
            own = num((n.get("config") or {}).get("costPerMillionRequests"))
            by_node.append(
                {
                    "id": n["id"],
                    "label": label,
                    "type": n["type"],
                    "technology": technology,
                    "instances": 0,
                    "unitPrice": price,
                    "priceSource": ("set" if own is not None else "default")
                    if price is not None
                    else None,
                    "unit": "million",
                    "monthly": monthly,
                    "basis": f"{int(round_half_up(rate)):,} calls/s"
                    if rate is not None
                    else "per call",
                }
            )
            continue
        price, source = hourly_price(n, registry)
        if price is not None:
            any_price = True
        if source == "size":
            size_priced = True
        count, basis = instances_of(n, registry, nr)
        by_node.append(
            {
                "id": n["id"],
                "label": label,
                "type": n["type"],
                "technology": technology,
                "instances": count,
                "unitPrice": price,
                "priceSource": source,
                "unit": "hour",
                "monthly": price * count * HOURS_PER_MONTH if price is not None else 0.0,
                "basis": basis,
            }
        )
    by_node.sort(key=lambda x: -x["monthly"])
    monthly = sum(x["monthly"] for x in by_node)
    thr = num(((report or {}).get("summary") or {}).get("throughputRps")) if report else None
    per_million = monthly / (thr * SECONDS_PER_MONTH / 1e6) if thr and thr > 0 else None
    if not any_price:
        notes.append(
            "No component has a price yet: set Cost per hour on components to estimate cost."
        )
    if size_priced:
        notes.append(
            "Parts without a set price are priced from their size at illustrative on-demand rates "
            f"(${js_num(PRICE_PER_VCPU_HOUR)} per vCPU-hour, ${js_num(PRICE_PER_GIB_HOUR)} per GiB-hour, "
            f"${js_num(PRICE_PER_STORAGE_GIB_MONTH)} per GiB-month of storage)."
        )
    if missing_external_rate:
        notes.append(
            "Third-party APIs are priced per call: run a simulation to price them at the measured call rate."
        )
    if any(x["unit"] == "million" and x["monthly"] > 0 for x in by_node) or per_million is not None:
        notes.append(
            f"Per-call prices and cost per request assume the run's traffic rate continues for {HOURS_PER_MONTH} hours a month."
        )
    return {
        "monthly": monthly,
        "perMillionRequests": per_million,
        "byNode": by_node,
        "notes": notes,
        "hasCost": any_price,
        "currency": "USD",
        "hoursPerMonth": HOURS_PER_MONTH,
        "rates": {
            "kind": "illustrative",
            "perVcpuHour": PRICE_PER_VCPU_HOUR,
            "perGibHour": PRICE_PER_GIB_HOUR,
            "perStorageGibMonth": PRICE_PER_STORAGE_GIB_MONTH,
        },
    }
