"""Static load estimate: roughly how many requests per second reach each part at the workload's
peak, without simulating. Used by auto-sizing (``sizing.py``) and the design review
(``review.py``).

The peak rate is split by the read/write mix and by each client part's traffic share, then pushed
along the links in call order: a load balancer (and a queue) spreads a request type over the links
that carry it, weighted by the targets' replicas; a service calls every dependency that carries the
type; a cache forwards reads to its miss link at (1 - hit ratio) and absorbs writes (read-through);
in cache-aside the service's link set to ``readsOnMiss`` gets that share instead, and the cache
one more read per miss for the write-back. First attempts
only (no retries). Where the share is unknown (a TTL cache's misses) the parts downstream get no
estimate, unless ``ttl_miss_share`` assumes one.

Functions here work on a *normalized* document dict (``scenarios.normalize_document``): camelCase
keys and every node config expanded with its defaults.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from archsim_engine.registry import ComponentRegistry

Json = dict[str, Any]

_BY_TYPE = {
    "traffic_source": "traffic",
    "mobile_app": "traffic",
    "web_client": "traffic",
    "api_consumer": "traffic",
    "load_balancer": "network",
    "service": "compute",
    "kubernetes_cluster": "compute",
    "cache": "cache",
    "database": "database",
    "message_queue": "messaging",
    "external_api": "external",
}
_BY_CATEGORY = {
    "traffic": "traffic",
    "network": "network",
    "compute": "compute",
    "storage": "database",
    "messaging": "messaging",
    "external": "external",
}


def kind_of(type_: str, registry: ComponentRegistry) -> str:
    """Component family: traffic, network, compute, cache, database, messaging, external, other."""
    if type_ in _BY_TYPE:
        return _BY_TYPE[type_]
    if type_ in registry:
        model = registry.get(type_)
        if model.is_traffic_source:
            return "traffic"
        return _BY_CATEGORY.get(model.category, "other")
    return "other"


def num(v: Any) -> float | None:
    """A finite number, else None (booleans are not numbers)."""
    if isinstance(v, bool) or not isinstance(v, int | float):
        return None
    return float(v) if math.isfinite(v) else None


def mean_ms(d: Any) -> float:
    """Mean of a distribution dict in ms (0 when it is not one)."""
    if not isinstance(d, dict):
        return 0.0
    kind = d.get("kind")
    if kind == "constant":
        return float(d["value"])
    if kind == "exponential":
        return float(d["mean"])
    if kind == "uniform":
        return (float(d["min"]) + float(d["max"])) / 2
    if kind == "lognormal":
        sigma = math.log(float(d["p95"]) / float(d["median"])) / 1.6448536269514722
        return math.exp(math.log(float(d["median"])) + sigma * sigma / 2)
    if kind == "empirical":
        q = [(float(p), float(v)) for p, v in d.get("quantiles") or []]
        return sum((p1 - p0) * (v0 + v1) / 2 for (p0, v0), (p1, v1) in itertools.pairwise(q))
    return 0.0


def round_half_up(x: float, digits: int = 0) -> float:
    """``Math.round``-style rounding (ties away from zero for positives), not banker's rounding."""
    q = Decimal(1).scaleb(-digits)
    return float(Decimal(x).quantize(q, rounding=ROUND_HALF_UP))


def js_num(x: float) -> str:
    """A number as JavaScript prints it: 12 not 12.0, 12.5 as 12.5."""
    if math.isfinite(x) and float(x).is_integer():
        return str(int(x))
    return repr(float(x))


def fmt_int(x: float) -> str:
    """1,234 (rounded half up)."""
    return f"{int(round_half_up(x)):,}"


def fixed(x: float, digits: int) -> str:
    """``Number.toFixed``: rounds the exact binary value half up."""
    q = Decimal(1).scaleb(-digits)
    return str(Decimal(x).quantize(q, rounding=ROUND_HALF_UP))


@dataclass
class NodeLoad:
    read: float
    write: float

    @property
    def total(self) -> float:
        return self.read + self.write


def peak_rps(workload: Json) -> float:
    """Highest arrival rate of the workload pattern (req/s)."""
    p = workload.get("pattern") or {"kind": "constant"}
    rps = float(workload["rps"])
    if p.get("kind") == "ramp":
        return max(float(p["startRps"]), float(p["endRps"]))
    if p.get("kind") == "burst":
        return max(rps, float(p["burstRps"]))
    if p.get("kind") == "trace":
        # same as Workload.peak_rate: the highest point inside the run, or the rate at its end
        from archsim_engine.schema import TracePattern

        trace = TracePattern.model_validate(p)
        duration = float(workload.get("durationS") or 0)
        inside = [r for t, r in trace.points if t <= duration]
        return max([*inside, trace.rate(duration)])
    return rps


def client_shares(nodes: list[Json], registry: ComponentRegistry) -> list[tuple[str, float]]:
    """Each client part's fraction of the workload (relative traffic shares), in document order."""
    clients = [n for n in nodes if kind_of(n["type"], registry) == "traffic"]
    weights = []
    for n in clients:
        w = num(n.get("config", {}).get("trafficShare"))
        w = 100.0 if w is None else w
        weights.append(w if w > 0 else 0.0)
    total = sum(weights)
    return [
        (n["id"], (w / total) if total > 0 else 0.0) for n, w in zip(clients, weights, strict=True)
    ]


def _carries(edge: Json, op: str) -> bool:
    return op in (edge.get("config", {}).get("requestTypes") or ["read", "write"])


def _on_miss(edge: Json) -> bool:
    """The link gets reads only after a cache lookup missed (cache-aside)."""
    return edge.get("config", {}).get("readsOnMiss") is True


def _weight(cfg: Json, type_: str) -> float:
    """Replicas a load balancer weighs a target by (services: at max scale when autoscaling)."""
    if type_ != "service":
        return 1.0
    replicas = num(cfg.get("replicas")) or 1.0
    if cfg.get("autoscaling") is True:
        replicas = max(num(cfg.get("maxReplicas")) or replicas, replicas)
    return max(1.0, replicas)


def estimate_load(
    doc: Json,
    registry: ComponentRegistry,
    ttl_miss_share: float | None = None,
    *,
    miss_shares: dict[str, float] | None = None,
    edge_loads: dict[str, NodeLoad | None] | None = None,
) -> dict[str, NodeLoad | None]:
    """Peak load per node id; ``None`` when it cannot be estimated statically.

    ``miss_shares`` gives the miss share of particular TTL caches (cache id -> share), taking
    precedence over ``ttl_miss_share``. When ``edge_loads`` is given, it is filled with the first
    attempts sent over each link (link id -> load, ``None`` when unknown)."""
    nodes: list[Json] = doc["architecture"]["nodes"]
    edges: list[Json] = doc["architecture"]["edges"]
    by_id = {n["id"]: n for n in nodes}
    cfg = {n["id"]: n.get("config", {}) for n in nodes}
    out: dict[str, list[Json]] = {}
    indeg = {n["id"]: 0 for n in nodes}
    for e in edges:
        if e["source"] not in by_id or e["target"] not in by_id:
            continue
        out.setdefault(e["source"], []).append(e)
        indeg[e["target"]] = indeg.get(e["target"], 0) + 1

    load: dict[str, NodeLoad | None] = {n["id"]: NodeLoad(0.0, 0.0) for n in nodes}
    w = doc["workload"]
    peak = peak_rps(w)
    r = min(1.0, max(0.0, float(w["readRatio"])))
    for cid, fraction in client_shares(nodes, registry):
        load[cid] = NodeLoad(peak * fraction * r, peak * fraction * (1 - r))

    def add(nid: str, sent: NodeLoad | None) -> None:
        cur = load.get(nid)
        if cur is None:
            return
        load[nid] = None if sent is None else NodeLoad(cur.read + sent.read, cur.write + sent.write)

    def miss_share(cache_id: str) -> float | None:
        """Share of reads a cache misses (None when it depends on the run)."""
        c = cfg[cache_id]
        if (c.get("hitModel") or "fixed_ratio") == "fixed_ratio":
            return 1 - (num(c.get("hitRatio")) or 0.0)
        if miss_shares is not None and cache_id in miss_shares:
            return miss_shares[cache_id]
        return ttl_miss_share

    def aside_lookups(outs: list[Json]) -> list[Json]:
        """A service's read links to caches that leave misses to it (cache-aside), when it has
        a link that reads the data store on a miss."""
        if not any(_on_miss(x) and _carries(x, "read") for x in outs):
            return []
        return [
            x
            for x in outs
            if _carries(x, "read")
            and not _on_miss(x)
            and by_id[x["target"]]["type"] == "cache"
            and not any(y.get("sourcePort") == "miss" for y in out.get(x["target"], []))
        ]

    def fetch_share(outs: list[Json]) -> float | None:
        """Share of a service's reads that miss at least one of its cache-aside lookups."""
        hit_all = 1.0
        for x in aside_lookups(outs):
            s = miss_share(x["target"])
            if s is None:
                return None
            hit_all *= 1 - s
        return 1 - hit_all if aside_lookups(outs) else 0.0

    # Kahn's order (designs are acyclic; parts on a cycle keep whatever reached them).
    queue = [n["id"] for n in nodes if indeg.get(n["id"], 0) == 0]
    while queue:
        nid = queue.pop(0)
        node = by_id[nid]
        here = load.get(nid)
        kind = kind_of(node["type"], registry)
        outs = out.get(nid, [])
        for e in outs:
            sent: NodeLoad | None
            if here is None:
                sent = None
            elif kind in ("network", "messaging"):

                def share(op: str, e: Json = e, outs: list[Json] = outs) -> float:
                    cands = [x for x in outs if _carries(x, op)]
                    total = sum(
                        _weight(cfg[x["target"]], by_id[x["target"]]["type"]) for x in cands
                    )
                    if not _carries(e, op) or total <= 0:
                        return 0.0
                    return _weight(cfg[e["target"]], by_id[e["target"]]["type"]) / total

                sent = NodeLoad(here.read * share("read"), here.write * share("write"))
            elif kind == "cache":
                missed = miss_share(nid)
                if e.get("sourcePort") != "miss":
                    sent = NodeLoad(0.0, 0.0)
                elif missed is None:
                    sent = None  # TTL misses depend on the run
                else:
                    sent = NodeLoad(here.read * missed if _carries(e, "read") else 0.0, 0.0)
            else:
                # clients and services send each request type to every link that carries it.
                # Cache-aside: a link that reads only on a cache miss gets the share of reads
                # that missed, and each lookup that misses writes the value back (one more read
                # on the cache).
                read: float | None = here.read if _carries(e, "read") else 0.0
                if read and kind == "compute":
                    if _on_miss(e):
                        fs = fetch_share(outs)
                        read = None if fs is None else read * fs
                    elif e in aside_lookups(outs) and cfg[e["target"]].get("populateOnMiss", True):
                        # The lookups are known; the write-backs depend on the miss share. A TTL
                        # cache whose share depends on the run counts its lookups alone, like a
                        # read-through cache, whose reads are known and only its misses are not.
                        ms = miss_share(e["target"])
                        read = read * (1 + ms) if ms is not None else read
                write = here.write if _carries(e, "write") else 0.0
                sent = None if read is None else NodeLoad(read, write)
            add(e["target"], sent)
            if edge_loads is not None:
                edge_loads[e["id"]] = sent
            indeg[e["target"]] = indeg.get(e["target"], 1) - 1
            if indeg[e["target"]] == 0:
                queue.append(e["target"])
    return load
