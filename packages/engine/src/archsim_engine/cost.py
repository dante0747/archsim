"""Cost estimate: the monthly cost of a design from the cost assumptions on each part
(``costPerHour`` per instance; ``costPerMillionRequests`` for third-party APIs), and the cost per
million successful requests at the throughput a run achieved.

Price per instance-hour, the first that applies:

1. a price a person set on the part (``set``): one that differs from the catalog default and
   from the illustrative price of every preset of the part's technology, or whose provenance
   (``nodes/<id>/costPerHour``) is measured or assumed;
2. with a price snapshot (``pricing`` names a provider): the on-demand price of the cheapest
   instance type that covers the part's CPU and memory, plus its storage at the snapshot's rate
   (``snapshot``), or the snapshot's rate for a managed load balancer, queue or serverless
   function;
3. a technology preset's illustrative price (``preset``);
4. the part's size at illustrative on-demand rates (``size``, the ``generic`` provider);
5. the catalog default (``default``).

A price equal to the catalog default counts as not set (normalized and stored documents carry
every default); a preset's price is illustrative, so a snapshot reprices it.

These are planning estimates, not quotes: a snapshot's prices are list prices on its date (no
discounts, data transfer or per-request load balancer units), and the generic rates are
illustrative.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from archsim_engine.load import Json, fixed, js_num, kind_of, num, round_half_up
from archsim_engine.pricing import (
    GENERIC,
    GENERIC_GIB_HOUR,
    GENERIC_STORAGE_GIB_MONTH,
    GENERIC_VCPU_HOUR,
    PricingError,
    Sku,
    Snapshot,
    choose_machine,
    snapshot_for,
)
from archsim_engine.registry import ComponentRegistry

HOURS_PER_MONTH = 730
SECONDS_PER_MONTH = HOURS_PER_MONTH * 3600
PRICE_PER_VCPU_HOUR = GENERIC_VCPU_HOUR
"""Illustrative on-demand price per vCPU-hour (roughly a large cloud's general-purpose VMs)."""
PRICE_PER_GIB_HOUR = GENERIC_GIB_HOUR
"""Illustrative on-demand price per GiB-hour of memory."""
PRICE_PER_STORAGE_GIB_MONTH = GENERIC_STORAGE_GIB_MONTH
"""Illustrative block storage price per GiB-month."""
CLUSTER_TYPE = "kubernetes_cluster"
MANAGED_LOAD_BALANCERS = {"aws_alb"}
"""Billed per hour at the snapshot's ``loadBalancerHour``. API gateways are not: providers bill
them per request, a rate snapshots do not carry, so they keep an illustrative price."""
MANAGED_QUEUES = {"sqs", "pubsub"}
PER_REQUEST_UNPRICED = {"api_gateway"}


@dataclass(frozen=True)
class Quote:
    """A price per instance-hour and where it came from: ``set`` on the part by a person, a
    ``snapshot`` instance type (``sku``), a technology ``preset``'s illustrative price, the
    part's ``size`` at generic rates, or the catalog ``default``."""

    price: float | None
    source: str | None
    sku: str | None = None
    note: str | None = None


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


def _preset_prices(node: Json, registry: ComponentRegistry) -> set[float]:
    """The illustrative ``costPerHour`` of the presets of the part's technology."""
    if node["type"] not in registry:
        return set()
    tech = config_value(node, registry, "technology")
    return {
        price
        for p in registry.get(node["type"]).presets
        if p.technology == tech and (price := num(p.config.get("costPerHour"))) is not None
    }


def price_origin(
    node: Json, registry: ComponentRegistry, provenance: dict[str, str] | None = None
) -> str | None:
    """Where a part's ``costPerHour`` came from: ``set`` by a person, ``preset`` (the
    illustrative price of a preset of its technology, or marked illustrative), ``default`` (the
    catalog default) or None (no price). ``provenance``: the document's provenance kind by path;
    measured or assumed means set by a person, whatever the value."""
    own = num((node.get("config") or {}).get("costPerHour"))
    if own is None:
        return None
    kind = (provenance or {}).get(f"nodes/{node['id']}/costPerHour")
    if kind in ("measured", "assumed"):
        return "set"
    if own == num(_defaults(node, registry).get("costPerHour")):
        return "default"
    if kind == "illustrative" or own in _preset_prices(node, registry):
        return "preset"
    return "set"


def quote(
    node: Json,
    registry: ComponentRegistry,
    snap: Snapshot = GENERIC,
    option: str = "on_demand",
    families: tuple[str, ...] = (),
    size_only: bool = False,
    provenance: dict[str, str] | None = None,
) -> Quote:
    """Price of one instance-hour of a part. ``size_only``: the price of its size (instance type
    or generic rates) even when a price is set on the part."""
    cfg = node.get("config") or {}
    fallback = num(_defaults(node, registry).get("costPerHour"))
    own = num(cfg.get("costPerHour"))
    origin = None if size_only else price_origin(node, registry, provenance)
    if origin == "set":
        return Quote(own, "set")
    cluster = node["type"] == CLUSTER_TYPE
    # a Kubernetes cluster is billed per node, at the node's size
    cpu = num(config_value(node, registry, "nodeVcpu" if cluster else "cpuCores"))
    mem = num(config_value(node, registry, "nodeMemoryGib" if cluster else "memoryGib"))
    storage = None if cluster else num(cfg.get("storageGib"))
    note = None
    if snap.has_shapes() and (cpu is not None or mem is not None):
        full = {**_defaults(node, registry), **cfg}
        m = choose_machine(snap, node["type"], full, cpu, mem, option, families)
        if m is not None and m.sku is not None:
            price = m.sku.price(option) or 0.0
            rate = snap.rates.get("storageGibMonth")
            if storage and rate is not None:
                price += storage * rate / HOURS_PER_MONTH
            return Quote(
                round_half_up(price * 10000) / 10000,
                "snapshot",
                m.sku.sku,
                "self-managed on compute instances" if m.self_managed else None,
            )
        if m is not None:
            note = f"no {m.category} type in {snap.label} covers its size"
    if origin == "preset":
        # a preset's price is illustrative: used when the snapshot cannot price the part
        return Quote(own, "preset", None, f"{note}: priced at its preset's price" if note else None)
    size = size_price_per_hour(cpu, mem, storage)
    if size is not None:
        return Quote(size, "size", None, f"{note}: priced at generic rates" if note else None)
    if size_only:
        return Quote(None, None)
    default = own if own is not None else fallback
    return Quote(default, "default") if default is not None else Quote(None, None)


def hourly_price(node: Json, registry: ComponentRegistry) -> tuple[float | None, str | None]:
    """Price of one instance-hour at generic rates and where it came from: set, size or default."""
    q = quote(node, registry)
    return q.price, q.source


def sku_named(snap: Snapshot, name: str | None) -> Sku | None:
    """The snapshot's SKU of that name, in any of its price lists."""
    if not name:
        return None
    return next((s for items in snap.skus.values() for s in items if s.sku == name), None)


def surplus_credits(
    sku: Sku | None, base_busy: float | None, instances: float
) -> tuple[float, str] | None:
    """A burstable type whose CPU at the base load is above its baseline: the monthly cost of the
    surplus CPU credits (unlimited mode: EC2 and RDS), or a note that it is throttled to its
    baseline when its credits run out (standard mode: no credit price). None when it stays under."""
    if sku is None or not sku.burstable or sku.baseline_cpu is None or base_busy is None:
        return None
    baseline = sku.baseline_cpu * sku.vcpu
    over = base_busy - baseline
    if over <= 1e-9:
        return None
    what = (
        f"{fixed(base_busy, 2)} cores busy at the base load, over its baseline of "
        f"{fixed(baseline, 2)} ({js_num(round_half_up(sku.baseline_cpu * 100))}% of each vCPU)"
    )
    if sku.credit_vcpu_hour is None:
        return 0.0, f"{what}: it is throttled to its baseline when its CPU credits run out"
    monthly = over * sku.credit_vcpu_hour * HOURS_PER_MONTH * instances
    return monthly, (
        f"+ ${fixed(monthly, 2)} a month of surplus CPU credits: {what}, "
        f"{fixed(over, 2)} vCPU × ${js_num(sku.credit_vcpu_hour)} per vCPU-hour"
    )


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
    nodes: list[Json],
    registry: ComponentRegistry,
    report: Json | None = None,
    pricing: Json | None = None,
    provenance: dict[str, str] | None = None,
    base_busy: dict[str, float] | None = None,
) -> Json:
    """Monthly cost of ``nodes`` (a normalized design, or a run's ``input.architecture.nodes``)
    with the prices ``pricing`` names (generic when unset). ``provenance``: the document's
    provenance kind by path (a price marked measured or assumed is the user's). ``base_busy``:
    the CPU cores one instance of each part keeps busy at the steady base load, so a burstable
    type above its baseline is charged its surplus CPU credits.

    With a report, third-party calls are priced at the measured call rate, autoscaled services
    at their average active instances, and the cost per million successful requests is derived.
    """
    notes: list[str] = []
    try:
        snap = snapshot_for(pricing)
    except PricingError as exc:
        snap = GENERIC
        notes.append(f"{exc} Priced at generic illustrative rates instead.")
    option = str((pricing or {}).get("purchaseOption") or "on_demand")
    families = tuple((pricing or {}).get("families") or ())
    snapshot_priced = False
    by_node: list[Json] = []
    any_price = False
    missing_external_rate = False
    size_priced = False
    preset_kept = False
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
        # a price the user set wins over a snapshot's managed rate too
        user_set = price_origin(n, registry, provenance) == "set"
        managed = None if user_set else _managed_line(n, technology, snap, nr, label)
        if managed is not None:
            any_price = True
            snapshot_priced = True
            by_node.append(managed)
            continue
        q = quote(n, registry, snap, option, families, provenance=provenance)
        if q.price is not None:
            any_price = True
        if q.source == "size":
            size_priced = True
        if q.source == "snapshot":
            snapshot_priced = True
        if q.source == "preset" and not snap.illustrative:
            preset_kept = True
        count, basis = instances_of(n, registry, nr)
        note = q.note
        if technology in PER_REQUEST_UNPRICED and not snap.illustrative and q.source != "set":
            note = (
                f"{note}; " if note else ""
            ) + "an API gateway is billed per request, which the price snapshot does not cover"
        credits = (
            surplus_credits(sku_named(snap, q.sku), (base_busy or {}).get(n["id"]), count)
            if q.source == "snapshot"
            else None
        )
        if credits is not None:
            note = (f"{note}; " if note else "") + credits[1]
        line: Json = {
            "id": n["id"],
            "label": label,
            "type": n["type"],
            "technology": technology,
            "instances": count,
            "unitPrice": q.price,
            "priceSource": q.source,
            "unit": "hour",
            "monthly": (q.price * count * HOURS_PER_MONTH if q.price is not None else 0.0)
            + (credits[0] if credits else 0.0),
            "basis": basis + (f"; {note}" if note else ""),
        }
        if credits and credits[0] > 0:
            line["creditsMonthly"] = credits[0]
        if q.sku:
            line["sku"] = q.sku
            line["snapshotDate"] = snap.snapshot_date
        by_node.append(line)
    by_node.sort(key=lambda x: -x["monthly"])
    monthly = sum(x["monthly"] for x in by_node)
    thr = num(((report or {}).get("summary") or {}).get("throughputRps")) if report else None
    per_million = monthly / (thr * SECONDS_PER_MONTH / 1e6) if thr and thr > 0 else None
    if not any_price:
        notes.append(
            "No component has a price yet: set Cost per hour on components to estimate cost."
        )
    if snapshot_priced:
        names = ", ".join(n for n, _ in snap.sources)
        notes.append(
            f"Prices: {snap.provider} {snap.region}, {option.replace('_', '-')} list prices of "
            f"{snap.snapshot_date} ({names}). Each part runs on the cheapest instance type that "
            "covers its CPU and memory (a burstable t4g type only when its CPU at the base load "
            "stays under its baseline, or when its family is named); "
            "discounts, data transfer and load balancer capacity units are not included."
        )
    if preset_kept:
        notes.append(
            "Parts the price snapshot does not cover keep their technology preset's illustrative "
            "price."
        )
    if size_priced:
        # whenever any line is priced at the generic rates, mixed with snapshot lines or not
        notes.append(
            (
                "Parts the price snapshot does not cover are"
                if not snap.illustrative
                else "Parts without a set price are"
            )
            + " priced from their size at illustrative on-demand rates "
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
        }
        if snap.illustrative
        else {"kind": "snapshot", **{k: v for k, v in snap.rates.items() if v is not None}},
        "pricing": {**snap.to_json(), "purchaseOption": option, "families": list(families)},
    }


def _managed_line(
    n: Json, technology: str | None, snap: Snapshot, nr: Json | None, label: str
) -> Json | None:
    """Managed load balancers (per hour) and queues (per request) at a snapshot's rates; None
    when the snapshot has no rate for it (it is then priced like any other part)."""
    if snap.illustrative:
        return None
    base = {"id": n["id"], "label": label, "type": n["type"], "technology": technology}
    lb_hour = snap.rates.get("loadBalancerHour")
    if technology in MANAGED_LOAD_BALANCERS and lb_hour is not None:
        return {
            **base,
            "instances": 1,
            "unitPrice": lb_hour,
            "priceSource": "snapshot",
            "unit": "hour",
            "monthly": lb_hour * HOURS_PER_MONTH,
            "basis": "1 load balancer (capacity units not included)",
            "snapshotDate": snap.snapshot_date,
        }
    gb_second = snap.rates.get("serverlessGbSecond")
    cfg = n.get("config") or {}
    if n["type"] == "service" and cfg.get("platform") == "serverless" and gb_second is not None:
        # billed per invocation and per GB-second of its duration (residence time: a function
        # is billed while it waits on its own calls)
        summary = (nr or {}).get("summary") or {}
        rate = num(summary.get("arrivalRps"))
        mean_ms = num((summary.get("latencyMs") or {}).get("mean"))
        mem = num(cfg.get("memoryGib"))
        per_call = None
        if mean_ms is not None and mem is not None:
            per_call = (snap.rates.get("serverlessPerMillionRequests") or 0.0) / 1e6 + (
                mean_ms / 1000 * mem * gb_second
            )
        return {
            **base,
            "instances": 0,
            "unitPrice": per_call * 1e6 if per_call is not None else None,
            "priceSource": "snapshot",
            "unit": "million",
            "monthly": rate * SECONDS_PER_MONTH * per_call
            if rate is not None and per_call is not None
            else 0.0,
            "basis": (
                f"{int(round_half_up(rate)):,} invocations/s × {fixed(mean_ms, 1)} ms × "
                f"{js_num(mem)} GiB"
            )
            if rate is not None and mean_ms is not None and mem is not None
            else "per invocation (run a simulation to price it)",
            "snapshotDate": snap.snapshot_date,
        }
    per_million = snap.rates.get("queuePerMillionRequests")
    if technology in MANAGED_QUEUES and per_million is not None:
        rate = num(((nr or {}).get("summary") or {}).get("arrivalRps")) if nr else None
        return {
            **base,
            "instances": 0,
            "unitPrice": per_million,
            "priceSource": "snapshot",
            "unit": "million",
            "monthly": rate * SECONDS_PER_MONTH * per_million / 1e6 if rate is not None else 0.0,
            "basis": f"{int(round_half_up(rate)):,} requests/s"
            if rate is not None
            else "per request",
            "snapshotDate": snap.snapshot_date,
        }
    return None
