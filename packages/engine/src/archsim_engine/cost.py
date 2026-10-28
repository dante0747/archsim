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

Managed and per-request parts (with a snapshot that has the rate): a managed load balancer per
hour plus its capacity units, an API gateway, a serverless function (requests and GB-seconds),
a managed queue (three requests per message: send, receive, delete) and DynamoDB (provisioned
capacity units per hour, or on-demand request units, plus storage) at the snapshot's rates.
Services on Kubernetes without a cluster in the design run on implied nodes: their pods' share
of the cheapest node type that fits them, plus a managed control plane.

Per-request costs are at the workload's steady **base** load (``load.base_rps``: the rate
outside a burst, a ramp's low end, a trace's median), as if it lasted the whole month: after a
run, each part's rate measured while the offered load was at the base, times base ÷ that offered
rate (so fan-out, cache misses and retries are kept, a burst's retries are not); before one, the
static load estimate at the base rate. A function's
GB-seconds use its duration at the base load.

These are planning estimates, not quotes: a snapshot's prices are list prices on its date (no
discounts, Multi-AZ, data transfer or free tiers), and the generic rates are illustrative.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from archsim_engine.load import (
    Json,
    base_rps,
    estimate_load,
    fixed,
    js_num,
    kind_of,
    mean_ms,
    num,
    round_half_up,
    ttl_miss_shares,
)
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
SQS_REQUESTS_PER_MESSAGE = 3
"""SQS bills each API call: a message is sent, received and deleted."""
ALB_KB_PER_REQUEST = 10.0
"""Illustrative: what an application load balancer processes per request (request and
response), for its capacity units; new connections, active connections and rule evaluations
are assumed to need fewer."""
ALB_GB_PER_LCU_HOUR = 1.0
"""An application load balancer's capacity unit (LCU) covers 1 GB processed an hour (EC2
targets)."""
BASE_BUCKET_SLACK = 1.25
"""Time buckets whose offered rate is within this factor of the base rate count as the base load
(for a function's duration at the base load)."""
NOT_MODELLED = (
    "Not modelled: Multi-AZ deployments, reserved instances and savings plans, data transfer and "
    "NAT, and free tiers."
)


@dataclass(frozen=True)
class Usage:
    """What a part handles at the steady base load: requests per second (reads and writes) and,
    for a function, how long one takes (ms)."""

    rps: float
    read: float
    write: float
    duration_ms: float | None = None


def _base_buckets(report: Json, base: float) -> list[int]:
    """The run's time buckets inside its window whose offered rate is at the base load."""
    ts = report.get("timeseries") or {}
    t, offered = ts.get("t") or [], ts.get("offeredRps") or []
    win = report.get("window") or {}
    lo, hi = float(win.get("warmupS") or 0.0), float(win.get("durationS") or 0.0)
    inside = [i for i, x in enumerate(t) if lo <= float(x) < hi and i < len(offered)]
    at_base = [i for i in inside if (num(offered[i]) or 0.0) <= base * BASE_BUCKET_SLACK + 1e-9]
    return at_base or inside


def _base_duration(node_report: Json, buckets: list[int]) -> float | None:
    """A part's mean time per request at the base load (ms): requests in flight ÷ arrivals over
    the base-load buckets (Little's law), else its mean latency over the run."""
    ts = node_report.get("timeseries") or {}
    inflight, arrivals = ts.get("inflightMean"), ts.get("arrivalRps")
    if inflight and arrivals:
        held = sum(num(inflight[i]) or 0.0 for i in buckets if i < len(inflight))
        rate = sum(num(arrivals[i]) or 0.0 for i in buckets if i < len(arrivals))
        if rate > 0:
            return held / rate * 1000.0
    return num(((node_report.get("summary") or {}).get("latencyMs") or {}).get("mean"))


def base_usage(
    registry: ComponentRegistry,
    report: Json | None = None,
    document: Json | None = None,
    estimate: Json | None = None,
) -> tuple[dict[str, Usage], float | None, float | None]:
    """Each part's usage at the workload's steady base load, the base rate, and the factor a
    run's measured rates were scaled by (None before a run). After a run: its rate measured in the
    run's base-load time buckets, times base ÷ the offered rate in them (the whole window when no
    bucket is at the base), split into reads and writes like the static estimate. Before one: the static
    load estimate at the base rate, with a function's duration from the instant estimate."""
    doc = document or (report or {}).get("input")
    if not doc or "workload" not in doc:
        # no workload to know the base load by: a report's measured rates as they are
        measured = {
            nr["id"]: Usage(rate, rate, 0.0, None)
            for nr in (report or {}).get("nodes") or []
            if (rate := num((nr.get("summary") or {}).get("arrivalRps"))) is not None
        }
        return measured, None, None
    base = base_rps(doc["workload"])
    try:
        loads = estimate_load(
            doc,
            registry,
            ttl_miss_share=1.0,
            miss_shares=ttl_miss_shares(doc, registry, rate=base),
            rate=base,
        )
    except Exception:  # an invalid document: priced without usage
        loads = {}
    ratio = min(1.0, max(0.0, float(doc["workload"].get("readRatio", 1.0))))
    out: dict[str, Usage] = {}
    if report and report.get("nodes"):
        offered = num((report.get("summary") or {}).get("offeredRps"))
        scale = base / offered if offered and offered > 0 else 1.0
        buckets = _base_buckets(report, base)
        # the offered rate over the base-load buckets, so a part's rate there is scaled to the
        # base exactly (a burst's retries stay out of it)
        series = (report.get("timeseries") or {}).get("offeredRps") or []
        at_base = [num(series[i]) or 0.0 for i in buckets if i < len(series)]
        offered_base = sum(at_base) / len(at_base) if at_base else 0.0
        for nr in report["nodes"]:
            rate = num((nr.get("summary") or {}).get("arrivalRps"))
            if rate is None:
                continue
            arrivals = (nr.get("timeseries") or {}).get("arrivalRps") or []
            seen = [num(arrivals[i]) or 0.0 for i in buckets if i < len(arrivals)]
            if seen and offered_base > 0:
                rps = sum(seen) / len(seen) * base / offered_base
            else:
                rps = rate * scale
            ld = loads.get(nr["id"])
            share = ld.read / ld.total if ld is not None and ld.total > 0 else ratio
            out[nr["id"]] = Usage(rps, rps * share, rps * (1 - share), _base_duration(nr, buckets))
        return out, base, scale
    rows = {r["id"]: r for r in (estimate or {}).get("parts") or []}
    for nid, ld in loads.items():
        if ld is None:
            continue
        row = rows.get(nid) or {}
        # time in the part (waiting, working, its own calls); an overloaded part has no
        # residence, so its time per request without waiting
        stay = num(row.get("meanResidenceMs"))
        if stay is None:
            stay = num(row.get("meanHoldMs"))
        out[nid] = Usage(ld.total, ld.read, ld.write, stay)
    return out, base, None


def _qty(x: float) -> str:
    """3.94 M, 12.3 k, 730, 0.5."""
    if x >= 1e9:
        return f"{fixed(x / 1e9, 2)} B"
    if x >= 1e6:
        return f"{fixed(x / 1e6, 2)} M"
    if x >= 1e4:
        return f"{fixed(x / 1e3, 1)} k"
    if x >= 100:
        return f"{int(round_half_up(x)):,}"
    return js_num(round_half_up(x, 2))


def _price(x: float) -> str:
    if x >= 1:
        return fixed(x, 2)
    return f"{x:.10f}".rstrip("0").rstrip(".") if x > 0 else "0"


def item(what: str, quantity: float, unit_price: float, unit: str, per: float = 1.0) -> Json:
    """One priced quantity of a cost line, e.g. "3.94 M requests x $0.20/M": ``quantity`` units a
    month at ``unit_price`` per ``per`` of them."""
    per_text = "M" if per == 1e6 else unit
    return {
        "what": what,
        "quantity": quantity,
        "unit": unit,
        "per": per,
        "unitPrice": unit_price,
        "monthly": quantity / per * unit_price,
        "text": f"{_qty(quantity)} {what} × ${_price(unit_price)}/{per_text}",
    }


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
            if m.category == "database" and not m.self_managed:
                # a managed database's storage has its own rate (RDS gp3)
                rate = snap.rates.get("rdsStorageGibMonth") or rate
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
    node: Json, registry: ComponentRegistry, node_report: Json | None = None, scale: float = 1.0
) -> tuple[float, str]:
    """Billable instances of a part and how that count was derived. An autoscaled part's
    average active instances in the run, times ``scale`` (the base load ÷ the run's offered load),
    at least its minimum."""

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
            at = ""
            if abs(scale - 1.0) > 1e-9:
                avg = max(max(1.0, get("replicas") or 1.0), avg * scale)
                at = " at the base load"
            return avg, f"avg {_plural(round_half_up(avg, 1), 'active instance')}{at}"
        r = max(1.0, get("replicas") or 1.0)
        return r, _plural(r, "replica")
    if t == CLUSTER_TYPE:
        avg = _mean(series["nodes"]) if series.get("nodes") else None
        if avg is not None:
            at = ""
            if abs(scale - 1.0) > 1e-9:
                avg = max(max(1.0, get("minNodes") or 1.0), avg * scale)
                at = " at the base load"
            return avg, f"avg {_plural(round_half_up(avg, 1), 'node')}{at}"
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
    *,
    document: Json | None = None,
    estimate: Json | None = None,
) -> Json:
    """Monthly cost of ``nodes`` (a normalized design, or a run's ``input.architecture.nodes``)
    with the prices ``pricing`` names (generic when unset). ``provenance``: the document's
    provenance kind by path (a price marked measured or assumed is the user's). ``base_busy``:
    the CPU cores one instance of each part keeps busy at the steady base load, so a burstable
    type above its baseline is charged its surplus CPU credits. ``document``: the design as
    simulated (a run's ``input``), for the load at the base rate before a run; ``estimate``: its
    instant estimate, for a function's duration before a run.

    Per-request parts are priced at the steady base load (:func:`base_usage`); with a report,
    autoscaled services at their average active instances scaled to it, and the cost per million
    successful requests is derived.
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
    usage, base, scale = base_usage(registry, report, document, estimate)
    workload = (document or (report or {}).get("input") or {}).get("workload") or {}
    implied_pods = False
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
            use = usage.get(n["id"])
            rate = use.rps if use is not None else None
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
                    "basis": f"{fixed(rate, 1)} calls/s at the base load"
                    if rate is not None
                    else "per call",
                    **(
                        {"items": [item("calls", rate * SECONDS_PER_MONTH, price, "call", 1e6)]}
                        if price is not None and rate is not None
                        else {}
                    ),
                }
            )
            continue
        # a price the user set wins over a snapshot's managed rate too
        user_set = price_origin(n, registry, provenance) == "set"
        managed = (
            None
            if user_set
            else _managed_line(n, technology, snap, usage.get(n["id"]), label, registry, workload)
        )
        if managed is not None:
            any_price = True
            snapshot_priced = True
            by_node.append(managed)
            continue
        pod = None if user_set else _implied_pods(n, registry, snap, option)
        if pod is not None:
            implied_pods = True
            any_price = True
            snapshot_priced = True
            count, basis = instances_of(n, registry, nr, scale or 1.0)
            node_sku, share, per_hour = pod
            by_node.append(
                {
                    "id": n["id"],
                    "label": label,
                    "type": n["type"],
                    "technology": technology,
                    "instances": count,
                    "unitPrice": round_half_up(per_hour * 10000) / 10000,
                    "priceSource": "snapshot",
                    "unit": "hour",
                    "monthly": per_hour * count * HOURS_PER_MONTH,
                    "basis": (
                        f"{basis.replace('replica', 'pod')} on implied {node_sku.sku} nodes "
                        f"(no cluster in the design): {fixed(share, 2)} of a node each, "
                        f"{_pct_text(KUBERNETES_ALLOCATABLE)} of a node allocatable"
                    ),
                    "sku": node_sku.sku,
                    "snapshotDate": snap.snapshot_date,
                }
            )
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
        count, basis = instances_of(n, registry, nr, scale or 1.0)
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
        plane = snap.rates.get("kubernetesControlPlaneHour")
        if n["type"] == CLUSTER_TYPE and q.source == "snapshot" and plane:
            # a managed cluster also pays for its control plane
            line["monthly"] += plane * HOURS_PER_MONTH
            line["basis"] += f"; + its managed control plane at ${js_num(plane)}/h"
            line["controlPlaneMonthly"] = plane * HOURS_PER_MONTH
        if q.sku:
            line["sku"] = q.sku
            line["snapshotDate"] = snap.snapshot_date
        by_node.append(line)
    plane = snap.rates.get("kubernetesControlPlaneHour")
    if implied_pods and plane:
        by_node.append(
            {
                "id": "kubernetes-control-plane",
                "label": "Kubernetes control plane",
                "type": CLUSTER_TYPE,
                "technology": None,
                "instances": 1,
                "unitPrice": plane,
                "priceSource": "snapshot",
                "unit": "hour",
                "monthly": plane * HOURS_PER_MONTH,
                "basis": "1 managed control plane, for the pods without a cluster in the design",
                "snapshotDate": snap.snapshot_date,
                "items": [item("control plane-hours", HOURS_PER_MONTH, plane, "hour")],
            }
        )
    by_node.sort(key=lambda x: -x["monthly"])
    monthly = sum(x["monthly"] for x in by_node)
    thr = num(((report or {}).get("summary") or {}).get("throughputRps")) if report else None
    if thr is not None and scale is not None:
        thr *= scale  # successful requests at the base load the month is priced at
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
            "stays under its baseline, or when its family is named)."
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
    per_request = any(x["unit"] == "million" and x["monthly"] > 0 for x in by_node)
    if (per_request or per_million is not None) and base is not None:
        how = (
            "each part's rate measured while the run's offered load was at the base, scaled to it "
            "exactly, so fan-out, cache misses and retries are kept"
            if scale is not None
            else "the static load estimate at that rate (run a simulation to measure it)"
        )
        notes.append(
            f"Per-request prices and cost per request are at the steady base load of "
            f"{fixed(base, 1)} req/s as if it lasted all {HOURS_PER_MONTH} hours of a month: {how}. "
            "A burst's or ramp's peak is not billed all month."
        )
    elif per_request or per_million is not None:
        notes.append(
            f"Per-call prices and cost per request assume the run's traffic rate continues for {HOURS_PER_MONTH} hours a month."
        )
    if snapshot_priced:
        notes.append(NOT_MODELLED)
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


def _pct_text(v: float) -> str:
    return f"{js_num(round_half_up(v * 100))}%"


KUBERNETES_ALLOCATABLE = 0.9
"""Share of a node's CPU and memory left for pods (the cluster default, ``allocatableShare``)."""


def _implied_pods(
    n: Json, registry: ComponentRegistry, snap: Snapshot, option: str
) -> tuple[Sku, float, float] | None:
    """A service on Kubernetes with no cluster in the design: its pods run on implied nodes. The
    node type whose allocatable CPU and memory (90%) fit one pod at the lowest price per pod, the
    share of a node a pod takes, and the price per pod-hour (burstable, HPC and bare-metal types
    are not node pools). None without instance types."""
    cfg = n.get("config") or {}
    if n["type"] != "service" or cfg.get("platform") != "kubernetes" or not snap.has_shapes():
        return None
    cpu, mem = num(cfg.get("cpuCores")), num(cfg.get("memoryGib"))
    if cpu is None and mem is None:
        return None
    best: tuple[float, float, str, Sku, float] | None = None
    for s in snap.skus.get("compute") or ():
        price = s.price(option)
        if s.burstable or not price or s.family.startswith("hpc") or ".metal" in s.sku:
            continue  # not a general node pool: burstable, HPC or bare-metal types
        a_cpu, a_mem = s.vcpu * KUBERNETES_ALLOCATABLE, s.memory_gib * KUBERNETES_ALLOCATABLE
        if a_cpu < (cpu or 0) - 1e-9 or a_mem < (mem or 0) - 1e-9:
            continue
        share = max((cpu or 0) / a_cpu, (mem or 0) / a_mem)
        key = (share * price, s.vcpu, s.sku, s, share)
        if best is None or key[:3] < best[:3]:
            best = key
    if best is None:
        return None
    per_pod, _, _, sku, share = best
    return sku, share, per_pod


def _managed_line(
    n: Json,
    technology: str | None,
    snap: Snapshot,
    use: Usage | None,
    label: str,
    registry: ComponentRegistry,
    workload: Json,
) -> Json | None:
    """Managed and per-request parts at a snapshot's rates (``items`` lists each priced quantity);
    None when the snapshot has no rate for the part (it is then priced like any other part).
    ``use``: the part's usage at the base load (None: unknown before a run)."""
    if snap.illustrative:
        return None
    base = {
        "id": n["id"],
        "label": label,
        "type": n["type"],
        "technology": technology,
        "snapshotDate": snap.snapshot_date,
        "priceSource": "snapshot",
    }
    cfg = n.get("config") or {}
    month = SECONDS_PER_MONTH
    rates = snap.rates

    def per_request(items: list[Json], basis: str, requests: float | None) -> Json:
        total = sum(i["monthly"] for i in items)
        return {
            **base,
            "instances": 0,
            "unitPrice": total / requests * 1e6 if requests else None,
            "unit": "million",
            "monthly": total,
            "basis": basis,
            "items": items,
        }

    lb_hour = rates.get("loadBalancerHour")
    if technology in MANAGED_LOAD_BALANCERS and lb_hour is not None:
        items = [item("load balancer-hours", HOURS_PER_MONTH, lb_hour, "hour")]
        basis = "1 load balancer"
        lcu_hour = rates.get("loadBalancerLcuHour")
        if lcu_hour is not None and use is not None:
            lcus = use.rps * ALB_KB_PER_REQUEST * 3600 / 1e6 / ALB_GB_PER_LCU_HOUR
            items.append(item("LCU-hours", lcus * HOURS_PER_MONTH, lcu_hour, "LCU-hour"))
            basis += (
                f" + {fixed(lcus, 2)} capacity units ({fixed(use.rps, 1)} req/s at the base load "
                f"× {js_num(ALB_KB_PER_REQUEST)} KB processed each, assumed; 1 LCU per "
                f"{js_num(ALB_GB_PER_LCU_HOUR)} GB an hour)"
            )
        elif lcu_hour is not None:
            basis += " (capacity units: run a simulation to price them)"
        else:
            basis += " (capacity units not included)"
        total = sum(i["monthly"] for i in items)
        return {
            **base,
            "instances": 1,
            "unitPrice": total / HOURS_PER_MONTH,
            "unit": "hour",
            "monthly": total,
            "basis": basis,
            "items": items,
        }
    gw = rates.get("apiGatewayPerMillion")
    if technology in PER_REQUEST_UNPRICED and gw is not None:
        if use is None:
            return per_request([], "per request (run a simulation to price it)", None)
        reqs = use.rps * month
        return per_request(
            [item("requests", reqs, gw, "request", 1e6)],
            f"{fixed(use.rps, 1)} req/s at the base load",
            reqs,
        )
    gb_second = rates.get("serverlessGbSecond")
    if n["type"] == "service" and cfg.get("platform") == "serverless" and gb_second is not None:
        # billed per invocation and per GB-second of its duration (residence time: a function
        # is billed while it waits on its own calls), at the base load
        mem = num(cfg.get("memoryGib"))
        if use is None or mem is None:
            return per_request([], "per invocation (run a simulation to price it)", None)
        duration, own_only = use.duration_ms, ""
        if duration is None:
            # its calls have no estimate (one waits on an overloaded part): its own time only
            duration, own_only = mean_ms(cfg.get("serviceTimeMs")), ", its own time only"
        calls = use.rps * month
        items = [
            item(
                "invocations",
                calls,
                rates.get("serverlessPerMillionRequests") or 0.0,
                "invocation",
                1e6,
            ),
            item("GB-seconds", calls * duration / 1000 * mem, gb_second, "GB-second"),
        ]
        return per_request(
            items,
            f"{fixed(use.rps, 1)} invocations/s at the base load × {fixed(duration, 1)} ms"
            f"{own_only} × {_mb(mem)}",
            calls,
        )
    per_million = rates.get("queuePerMillionRequests")
    if technology in MANAGED_QUEUES and per_million is not None:
        if use is None:
            return per_request([], "per request (run a simulation to price it)", None)
        calls_each = SQS_REQUESTS_PER_MESSAGE if technology == "sqs" else 1
        reqs = use.rps * calls_each * month
        how = " × 3 requests (send, receive, delete)" if calls_each == 3 else ""
        return per_request(
            [item("requests", reqs, per_million, "request", 1e6)],
            f"{fixed(use.rps, 1)} messages/s at the base load{how}",
            reqs,
        )
    if n["type"] == "database" and technology == "dynamodb":
        return _dynamodb_line(base, n, cfg, snap, use, registry, workload)
    return None


def _mb(gib: float) -> str:
    return f"{int(round_half_up(gib * 1024)):,} MB"


def _dynamodb_line(
    base: Json,
    n: Json,
    cfg: Json,
    snap: Snapshot,
    use: Usage | None,
    registry: ComponentRegistry,
    workload: Json,
) -> Json | None:
    """DynamoDB: provisioned capacity units per hour, or on-demand request units at the base
    load, plus storage."""
    rates = snap.rates
    model = registry.get("database")
    try:
        config: Any = model.parse_config({**model.default_config(), **cfg})
    except Exception:
        return None
    items: list[Json] = []
    if config.capacity_mode == "provisioned":
        rcu_h, wcu_h = rates.get("dynamodbRcuHour"), rates.get("dynamodbWcuHour")
        if rcu_h is None or wcu_h is None:
            return None
        rcu, wcu = float(config.read_capacity_units), float(config.write_capacity_units)
        items += [
            item("RCU-hours", rcu * HOURS_PER_MONTH, rcu_h, "RCU-hour"),
            item("WCU-hours", wcu * HOURS_PER_MONTH, wcu_h, "WCU-hour"),
        ]
        basis = f"{js_num(rcu)} RCU + {js_num(wcu)} WCU provisioned"
    else:
        rd, wr = rates.get("dynamodbReadPerMillion"), rates.get("dynamodbWritePerMillion")
        if rd is None or wr is None:
            return None
        if use is None:
            return {
                **base,
                "instances": 0,
                "unitPrice": None,
                "unit": "million",
                "monthly": 0.0,
                "basis": "per request unit (run a simulation to price it)",
                "items": [],
            }
        ru, wu = config.read_units(), config.write_units()
        items += [
            item("read request units", use.read * ru * SECONDS_PER_MONTH, rd, "unit", 1e6),
            item("write request units", use.write * wu * SECONDS_PER_MONTH, wr, "unit", 1e6),
        ]
        basis = (
            f"{fixed(use.read, 1)} reads/s × {js_num(ru)} unit + {fixed(use.write, 1)} writes/s × "
            f"{js_num(wu)} unit at the base load, on demand"
        )
    storage = rates.get("dynamodbStorageGibMonth")
    data = num(cfg.get("dataSizeGib"))
    if data is None:
        data = float(workload.get("keyCount") or 0) * float(config.item_size_kb) * 1000 / 2**30
    if storage is not None and data > 0:
        items.append(item("GiB-months stored", data, storage, "GiB-month"))
    total = sum(i["monthly"] for i in items)
    hourly = config.capacity_mode == "provisioned"
    return {
        **base,
        "instances": 1 if hourly else 0,
        "unitPrice": total / HOURS_PER_MONTH if hourly else None,
        "unit": "hour" if hourly else "million",
        "monthly": total,
        "basis": basis,
        "items": items,
    }
