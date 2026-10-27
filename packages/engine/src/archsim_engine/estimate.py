"""Instant estimate: what textbook queueing formulas predict for a design, without simulating.

Cheap enough for ``analyze_document``, which runs on every edit, and recorded in every report too
(for exactly what was simulated), so Results can set a run against what the formulas predicted.

At the workload's peak, with the per-part load of :func:`archsim_engine.load.estimate_load`
(first attempts only, fixed hit ratios; a TTL cache's hit ratio is estimated from its TTL and the
key popularity):

- **Utilization** of a part: rho = λ ÷ (pools x slots x μ), with μ = 1000 ÷ mean holding time. A
  slot (worker, connection, command executor, consumer) is held for the part's own service time,
  plus its downstream calls when it waits on them (thread-per-request services, a load balancer's
  connections, serverless instances, queue consumers). CPU cores and rate limits can bind first;
  ``limitedBy`` says which.
- **Waiting**: an M/M/c queue per pool (a service replica, a database instance, a cache shard):
  Wq = C(c, a) ÷ (c·μ - λ) (:func:`archsim_engine.explain.erlang_c`). Load balancers, serverless
  functions and third-party APIs turn requests away instead of queueing them, so they add no wait.
- **Latency** of each request type, summed along its path: the network both ways on each link,
  each part's wait and own time, and the calls it makes in order, each weighted by how often it is
  made (cache misses, load-balancer shares). Work behind a message queue is off the path: the
  producer waits only for the publish. The mean comes from these sums; the **p95 is a rough
  approximation**: a lognormal with each path's mean and variance, mixed by traffic share.
- **Overload**: a part at rho ≥ 1 cannot keep up; the design then saturates at about peak ÷ rho of
  the busiest part, and no latency is given.

Not included: retries, timeouts, faults, row-lock waits, buffer-cache misses, cold starts, GC
pauses, autoscaling delays and the memory model. The simulation includes them, which is why both
are shown.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass
from typing import Any

from archsim_engine.distributions import Constant, Empirical, Exponential, LogNormal, Uniform
from archsim_engine.explain import erlang_c
from archsim_engine.load import NodeLoad, estimate_load, kind_of, peak_rps
from archsim_engine.registry import ComponentRegistry
from archsim_engine.scenarios import simulation_input
from archsim_engine.schema import ArchitectureDocument, EdgeSpec, NodeSpec

Json = dict[str, Any]
OPS = ("read", "write")
BUSY = 0.8
"""Utilization from which a part counts as busy (the design review's CPU threshold)."""
_Z95 = 1.6448536269514722
METHOD = "Estimate from queueing formulas (not simulated)"
NOT_INCLUDED = (
    "Queueing formulas (M/M/c) at the peak load, not a simulation: first attempts only, with no "
    "retries, timeouts, faults, lock waits, buffer-cache misses, cold starts, GC pauses or "
    "autoscaling delays."
)
P95_NOTE = (
    "The p95 is a rough approximation: a lognormal with each request path's mean and variance, "
    "mixed by traffic share."
)


# ---------------------------------------------------------------------------------------------
# moments of a time (ms)
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Moments:
    """Mean and variance of a time in ms."""

    mean: float = 0.0
    var: float = 0.0

    def __add__(self, other: Moments) -> Moments:
        return Moments(self.mean + other.mean, self.var + other.var)


def times(p: float, x: Moments) -> Moments:
    """``x`` taken ``p`` times on average: the whole calls for sure, the fraction with that
    probability (a cache miss, a load balancer's share)."""
    if p <= 0:
        return Moments()
    k = math.floor(p + 1e-12)
    f = max(0.0, p - k)
    var = k * x.var + f * (x.var + x.mean**2) - (f * x.mean) ** 2
    return Moments(p * x.mean, max(0.0, var))


def moments(d: Any) -> Moments:
    """Mean and variance of a distribution (ms); zero for anything else."""
    if isinstance(d, Constant):
        return Moments(d.value, 0.0)
    if isinstance(d, Exponential):
        return Moments(d.mean, d.mean**2)
    if isinstance(d, Uniform):
        return Moments((d.min + d.max) / 2, (d.max - d.min) ** 2 / 12)
    if isinstance(d, LogNormal):
        m = d.expected()
        return Moments(m, m * m * math.expm1(d.sigma**2))
    if isinstance(d, Empirical):
        q = d.quantiles
        second = sum(
            (p1 - p0) * (v0 * v0 + v0 * v1 + v1 * v1) / 3
            for (p0, v0), (p1, v1) in itertools.pairwise(q)
        )
        m = d.expected()
        return Moments(m, max(0.0, second - m * m))
    return Moments()


def mmc_wait(slots: int, lam: float, hold_ms: float) -> tuple[float, Moments] | None:
    """Chance to wait and the wait's moments (ms) in an M/M/c queue; None when unstable."""
    if lam <= 0 or hold_ms <= 0:
        return 0.0, Moments()
    mu = 1000.0 / hold_ms
    pw = erlang_c(slots, lam / mu)
    if pw is None:
        return None
    rate = slots * mu - lam  # per second
    mean = pw / rate * 1000.0
    second = 2.0 * pw / rate**2 * 1e6
    return pw, Moments(mean, max(0.0, second - mean * mean))


def lognormal_p95(components: list[tuple[float, Moments]]) -> float | None:
    """p95 of a mixture of lognormals matched to each component's mean and variance."""
    parts = [(w, m) for w, m in components if w > 0]
    total = sum(w for w, _ in parts)
    if not parts or total <= 0:
        return None
    fitted: list[tuple[float, float, float]] = []  # weight, mu, sigma
    for w, m in parts:
        if m.mean <= 0:
            fitted.append((w / total, -math.inf, 0.0))
            continue
        s2 = math.log1p(m.var / (m.mean * m.mean))
        fitted.append((w / total, math.log(m.mean) - s2 / 2, math.sqrt(s2)))

    def cdf(x: float) -> float:
        lx = math.log(x)
        out = 0.0
        for w, mu, sigma in fitted:
            if sigma == 0:
                out += w if lx >= mu else 0.0
            else:
                out += w * 0.5 * math.erfc(-(lx - mu) / (sigma * math.sqrt(2)))
        return out

    finite = [(mu, s) for _, mu, s in fitted if math.isfinite(mu)]
    if not finite:
        return 0.0
    lo = math.exp(min(mu - 6 * s for mu, s in finite))
    hi = math.exp(max(mu + 6 * s for mu, s in finite))
    for _ in range(80):
        mid = math.sqrt(lo * hi)
        if cdf(mid) < 0.95:
            lo = mid
        else:
            hi = mid
    return hi


# ---------------------------------------------------------------------------------------------
# TTL cache hit ratio
# ---------------------------------------------------------------------------------------------


def ttl_hit_ratio(read_rps: float, write_rps: float, keys: int, skew: float, ttl_s: float) -> float:
    """Expected hit ratio of a TTL cache for Zipf(skew) key popularity.

    A key read after it expired (or after a write invalidated it) misses and is cached again;
    reads in the next ``min(TTL, time to the next write)`` hit. Per key with read rate r and write
    rate w that window lasts W = (1 - e^(-w·TTL)) ÷ w on average, so its hit ratio is
    r·W ÷ (1 + r·W). Evictions for lack of memory are not included."""
    if read_rps <= 0 or keys <= 0 or ttl_s <= 0:
        return 0.0
    exact = min(keys, 2000)

    def weight(k: float) -> float:
        return k**-skew

    def mass(a: float, b: float) -> float:
        """∫ x^-s dx from a to b (the popularity of keys a+0.5 ... b-0.5)."""
        if abs(skew - 1.0) < 1e-9:
            return math.log(b / a)
        return (b ** (1 - skew) - a ** (1 - skew)) / (1 - skew)

    buckets: list[tuple[float, float]] = [(weight(k), 1.0) for k in range(1, exact + 1)]
    lo = exact
    while lo < keys:
        hi = min(keys, max(lo + 1, math.ceil(lo * 1.05)))
        count = hi - lo
        buckets.append((mass(lo + 0.5, hi + 0.5) / count, float(count)))
        lo = hi
    total = sum(w * n for w, n in buckets)
    if total <= 0:
        return 0.0
    hits = 0.0
    for w, n in buckets:
        share = w / total
        r = read_rps * share
        wr = write_rps * share
        window = ttl_s if wr * ttl_s < 1e-9 else -math.expm1(-wr * ttl_s) / wr
        hits += n * share * (r * window / (1 + r * window))
    return min(1.0, max(0.0, hits))


# ---------------------------------------------------------------------------------------------
# per part
# ---------------------------------------------------------------------------------------------


@dataclass
class _Shape:
    """How a part serves requests, for the formulas."""

    own: dict[str, Moments]
    pools: int = 1
    slots: int | None = None
    queues: bool = True
    blocks: bool = False
    cpu: float | None = None  # CPU utilization at the peak
    limit: float | None = None  # share of a rate or throughput limit used at the peak
    populate: bool = False  # a read-through cache writes the value back after a miss


def _shape(node: NodeSpec, cfg: Any, load: NodeLoad, registry: ComponentRegistry) -> _Shape | None:
    t = node.type
    if t == "service":
        own = moments(cfg.service_time_ms)
        serverless = cfg.platform == "serverless"
        replicas = cfg.replicas
        if cfg.autoscaling:
            replicas = max(cfg.max_replicas, cfg.replicas)
        cpu = None
        if cfg.cpu_cores and not serverless:
            cpu = load.total * own.mean / (1000.0 * cfg.cpu_cores * max(1, replicas))
        if serverless:
            return _Shape({"read": own, "write": own}, 1, cfg.concurrency_limit, False, True)
        return _Shape(
            {"read": own, "write": own},
            max(1, replicas),
            cfg.slots_per_replica(),
            True,
            cfg.blocks(),
            cpu,
        )
    if t == "database":
        read = moments(cfg.read_service_time_ms)
        write = moments(cfg.write_service_time_ms)
        if cfg.is_on("commit_flush"):
            write = write + moments(cfg.commit_flush_ms)
        copies = 1 + cfg.read_replicas
        limits: list[float] = []
        if cfg.throughput_limit_rps:
            limits.append(load.total / cfg.throughput_limit_rps)
        if cfg.metered():
            if cfg.capacity_mode == "provisioned":
                rcu, wcu = cfg.read_capacity_units, cfg.write_capacity_units
            else:
                from archsim_engine.components.database import (
                    ON_DEMAND_READ_UNITS,
                    ON_DEMAND_WRITE_UNITS,
                )

                rcu, wcu = ON_DEMAND_READ_UNITS, ON_DEMAND_WRITE_UNITS
            limits.append(
                max(load.read * cfg.read_units() / rcu, load.write * cfg.write_units() / wcu)
            )
        cpu = None
        if cfg.cpu_cores:
            primary = (
                ((load.read / copies) * read.mean + load.write * write.mean)
                * cfg.cpu_share
                / 1000.0
                / max(1, cfg.shards)
            )
            cpu = primary / cfg.cpu_cores
        return _Shape(
            {"read": read, "write": write},
            max(1, cfg.partitions() * copies),
            cfg.max_connections,
            True,
            False,
            cpu,
            max(limits) if limits else None,
        )
    if t == "cache":
        own = moments(cfg.service_time_ms)
        cpu = load.total * own.mean / 1000.0 / cfg.cpu_cores if cfg.cpu_cores else None
        return _Shape(
            {"read": own, "write": own},
            max(1, cfg.shards),
            cfg.executors(),
            True,
            False,
            cpu,
            populate=bool(cfg.populate_on_miss),
        )
    if t == "load_balancer":
        own = moments(cfg.overhead_ms)
        limit = load.total / cfg.rate_limit_rps if cfg.rate_limit_rps else None
        return _Shape({"read": own, "write": own}, 1, cfg.max_connections, False, True, None, limit)
    if t == "external_api":
        own = moments(cfg.latency_ms)
        limit = load.total / cfg.rate_limit_rps if cfg.rate_limit_rps else None
        return _Shape(
            {"read": own, "write": own}, 1, cfg.concurrency_limit, False, False, None, limit
        )
    if t == "message_queue":
        publish = moments(cfg.publish_time_ms)
        linger = float(cfg.setting("linger_ms", 0.0) or 0.0)
        if linger > 0:
            publish = publish + Moments(linger / 2, linger * linger / 12)
        if cfg.setting("acks", "leader") == "all":
            publish = publish + moments(cfg.replication_ms)
        # slots are the consumers; their own time is the consumer's work per message
        consumer = moments(cfg.consumer_time_ms)
        slots = registry.get(t).capacity_slots(cfg)
        return _Shape(
            {"read": consumer, "write": consumer, "publish": publish}, 1, slots, True, True
        )
    return None


def _round(v: float | None, digits: int = 3) -> float | None:
    return None if v is None or not math.isfinite(v) else round(v, digits)


def _sync_reach(
    nodes: list[NodeSpec], out: dict[str, list[EdgeSpec]], kinds: dict[str, str]
) -> tuple[set[str], set[str]]:
    """Parts and links on a client's request path: everything reached from the clients, except
    the work behind a message queue (its consumers run in the background)."""
    seen: set[str] = set()
    links: set[str] = set()
    stack = [n.id for n in nodes if kinds[n.id] == "traffic"]
    while stack:
        nid = stack.pop()
        if nid in seen:
            continue
        seen.add(nid)
        if kinds[nid] == "messaging":
            continue
        for e in out.get(nid, []):
            links.add(e.id)
            stack.append(e.target)
    return seen, links


def estimate_design(
    resolved: ArchitectureDocument,
    registry: ComponentRegistry,
    snapshot: Json | None = None,
    configs: dict[str, Any] | None = None,
) -> Json:
    """The instant estimate of ``resolved`` (a scenario-resolved, auto-sized document);
    ``snapshot`` is its ``simulation_input`` and ``configs`` its parsed node configs, when the
    caller has them already."""
    snapshot = snapshot if snapshot is not None else simulation_input(resolved)
    nodes = resolved.architecture.nodes
    edges = resolved.architecture.edges
    labels = {n.id: n.label or n.id for n in nodes}
    kinds = {n.id: kind_of(n.type, registry) for n in nodes}
    parsed: dict[str, Any] = dict(configs or {})
    for n in nodes:
        if n.id not in parsed and n.type in registry:
            try:
                parsed[n.id] = registry.get(n.type).parse_config(n.config)
            except Exception:  # invalid configs are reported by validation
                continue
    out: dict[str, list[EdgeSpec]] = {}
    for e in edges:
        out.setdefault(e.source, []).append(e)
    for es in out.values():
        es.sort(key=lambda e: (e.config.order, e.id))
    wl = snapshot["workload"]
    peak = peak_rps(wl)
    notes: list[str] = []

    # TTL caches: estimate their hit ratio from the reads that reach them, then the load again
    miss_shares: dict[str, float] = {}
    ttl = [
        n
        for n in nodes
        if n.type == "cache" and getattr(parsed.get(n.id), "hit_model", None) == "ttl_keyspace"
    ]
    for _ in range(len(ttl) + 1):
        loads = estimate_load(snapshot, registry, miss_shares=miss_shares)
        pending = [n for n in ttl if n.id not in miss_shares and loads.get(n.id) is not None]
        if not pending:
            break
        for n in pending:
            ld = loads[n.id]
            assert ld is not None
            hit = ttl_hit_ratio(
                ld.read,
                ld.write,
                int(wl["keyCount"]),
                float(wl["keySkew"]),
                parsed[n.id].ttl_seconds,
            )
            miss_shares[n.id] = 1 - hit
            notes.append(
                f"{labels[n.id]}'s hit ratio is estimated at {hit:.0%} from its TTL and how "
                "popular the keys are (evictions not included)."
            )
    flows: dict[str, NodeLoad | None] = {}
    loads = estimate_load(snapshot, registry, miss_shares=miss_shares, edge_loads=flows)
    on_path, sync_links = _sync_reach(nodes, out, kinds)

    link = {e.id: times(2, moments(e.config.latency_ms)) for e in edges}
    resid: dict[str, dict[str, Moments | None]] = {}
    rows: dict[str, Json] = {}
    own_time: dict[str, dict[str, Moments]] = {}

    def calls(nid: str, op: str, edges_: list[EdgeSpec], here: NodeLoad) -> Moments | None:
        """Time of the calls a part makes for one request of type ``op`` (in order)."""
        rate = here.read if op == "read" else here.write
        total = Moments()
        if rate <= 0:
            return total
        for e in edges_:
            sent = flows.get(e.id)
            if sent is None:
                return None
            p = (sent.read if op == "read" else sent.write) / rate
            if p <= 0:
                continue
            target = resid.get(e.target, {}).get(op)
            if target is None:
                return None
            total = total + times(p, link[e.id] + target)
        return total

    # downstream first: reverse of the call order
    order: list[str] = []
    indeg = {n.id: 0 for n in nodes}
    for e in edges:
        if e.target in indeg and e.source in indeg:
            indeg[e.target] += 1
    queue = [n.id for n in nodes if indeg[n.id] == 0]
    while queue:
        nid = queue.pop(0)
        order.append(nid)
        for e in out.get(nid, []):
            if e.target in indeg:
                indeg[e.target] -= 1
                if indeg[e.target] == 0:
                    queue.append(e.target)
    by_id = {n.id: n for n in nodes}
    for nid in reversed(order):
        node = by_id[nid]
        if kinds[nid] == "traffic":
            continue
        resid[nid] = {"read": None, "write": None}
        here = loads.get(nid)
        cfg = parsed.get(nid)
        row: Json = {
            "id": nid,
            "label": labels[nid],
            "type": node.type,
            "onPath": nid in on_path,
            "arrivalRps": _round(here.total if here else None, 2),
            "status": "unknown",
        }
        rows[nid] = row
        if here is None:
            row["note"] = "Its load depends on the run (a cache's hit ratio upstream)."
            continue
        shape = _shape(node, cfg, here, registry) if cfg is not None else None
        if shape is None:
            row["note"] = "No capacity model for this kind of part."
            continue
        if node.type == "message_queue":
            # the producer waits for the publish only; consumers work in the background
            publish = shape.own["publish"]
            resid[nid] = {"read": publish, "write": publish}
            own_time[nid] = {"read": publish, "write": publish}
        outs = out.get(nid, [])
        down: dict[str, Moments | None] = {op: calls(nid, op, outs, here) for op in OPS}
        hold: dict[str, Moments | None] = {}
        for op in OPS:
            d = down[op]
            if not shape.blocks:
                hold[op] = shape.own[op]
            else:
                hold[op] = None if d is None else shape.own[op] + d
        mix = [(here.read, hold["read"]), (here.write, hold["write"])]
        if any(r > 0 and h is None for r, h in mix):
            row["note"] = "It waits on a part that is overloaded or has no estimate."
            continue
        busy = sum(r * h.mean for r, h in mix if h is not None and r > 0)
        mean_hold = busy / here.total if here.total > 0 else 0.0
        slot_util = None
        if shape.slots:
            slot_util = here.total * mean_hold / (1000.0 * shape.slots * shape.pools)
        candidates = {"slots": slot_util, "cpu": shape.cpu, "rate limit": shape.limit}
        known = {k: v for k, v in candidates.items() if v is not None}
        util = max(known.values()) if known else None
        limited_by = max(known, key=lambda k: known[k]) if known else None
        wait = Moments()
        pw: float | None = 0.0
        stable = util is None or util < 1.0
        if stable and shape.queues and shape.slots and here.total > 0:
            got = mmc_wait(shape.slots, here.total / shape.pools, mean_hold)
            if got is None:
                stable = False
            else:
                pw, wait = got
        row.update(
            {
                "slots": shape.slots,
                "pools": shape.pools,
                "meanServiceMs": _round(
                    (here.read * shape.own["read"].mean + here.write * shape.own["write"].mean)
                    / here.total
                    if here.total > 0
                    else shape.own["read"].mean
                ),
                "meanHoldMs": _round(mean_hold),
                "utilization": _round(util, 4),
                "slotUtilization": _round(slot_util, 4),
                "cpuUtilization": _round(shape.cpu, 4),
                "limitedBy": limited_by,
                "waitProbability": _round(pw, 4) if stable else None,
                "meanWaitMs": _round(wait.mean) if stable else None,
                "status": "overloaded"
                if not stable
                else ("busy" if util is not None and util >= BUSY else "ok"),
            }
        )
        if not stable or node.type == "message_queue":
            continue
        own_time[nid] = {}
        for op in OPS:
            mine = wait + shape.own[op]
            d = down[op]
            if d is None:
                continue
            extra = Moments()
            if shape.populate and op == "read":
                # read-through: after a miss the value is written back (one more command)
                miss = sum(
                    sent.read
                    for e in outs
                    if e.source_port == "miss" and (sent := flows.get(e.id)) is not None
                )
                if here.read > 0 and miss > 0:
                    extra = times(miss / here.read, wait + shape.own["read"])
            own_time[nid][op] = mine + extra
            resid[nid][op] = mine + d + extra

    # clients: each request type's path, mixed by traffic share
    components: list[tuple[float, Moments]] = []
    paths: list[Json] = []
    complete = True
    for n in nodes:
        if kinds[n.id] != "traffic":
            continue
        here = loads.get(n.id)
        if here is None or here.total <= 0:
            continue
        for op in OPS:
            rate = here.read if op == "read" else here.write
            if rate <= 0:
                continue
            t = calls(n.id, op, out.get(n.id, []), here)
            if t is None:
                complete = False
                continue
            components.append((rate, t))
            paths.append(
                {
                    "clientId": n.id,
                    "client": labels[n.id],
                    "op": op,
                    "rps": _round(rate, 2),
                    "meanMs": _round(t.mean),
                }
            )
    total_rps = sum(w for w, _ in components)

    # where a request's time goes: each part's wait and own time, and the network
    breakdown: list[Json] = []
    if components and complete and total_rps > 0:
        for nid, per_op in own_time.items():
            if nid not in on_path:
                continue
            here = loads.get(nid)
            if here is None:
                continue
            ms = (
                here.read * per_op.get("read", Moments()).mean
                + here.write * per_op.get("write", Moments()).mean
            ) / total_rps
            rows[nid]["perRequestMs"] = _round(ms)
            breakdown.append({"nodeId": nid, "label": labels[nid], "meanMs": _round(ms)})
        net = 0.0
        for e in edges:
            sent = flows.get(e.id)
            if e.id in sync_links and sent is not None:
                net += sent.total * link[e.id].mean / total_rps
        breakdown.append({"nodeId": None, "label": "Network", "meanMs": _round(net)})
        grand = sum(b["meanMs"] or 0.0 for b in breakdown) or 1.0
        for b in breakdown:
            b["share"] = _round((b["meanMs"] or 0.0) / grand, 4)
        breakdown.sort(key=lambda b: -(b["meanMs"] or 0.0))

    measured_rows = [r for r in rows.values() if r.get("utilization") is not None]
    busiest = max(measured_rows, key=lambda r: r["utilization"], default=None)
    overloaded = [r for r in rows.values() if r["status"] == "overloaded"]
    # a queue whose consumers fall behind builds a backlog; its producers are not slowed
    path_overloaded = any(r["onPath"] and r["type"] != "message_queue" for r in overloaded)
    latency: Json | None = None
    if components and complete and not path_overloaded:
        mean = sum(w * m.mean for w, m in components) / total_rps
        latency = {"mean": _round(mean, 2), "p95": _round(lognormal_p95(components), 2)}
    elif not path_overloaded and not complete:
        notes.append("Latency not estimated: the load on some parts depends on the run.")
    util_max = busiest["utilization"] if busiest else None
    saturation = peak / util_max if util_max else None
    if busiest is None:
        summary = "No estimate: no part with known capacity carries load."
    elif overloaded:
        worst = max(overloaded, key=lambda r: r.get("utilization") or 0.0)
        need = worst.get("utilization")
        summary = (
            f"Overloaded at {peak:,.0f} req/s: {worst['label']} "
            + (f"needs {need:.0%} of its capacity" if need is not None else "cannot keep up")
            + (f"; it saturates at about {saturation:,.0f} req/s." if saturation else ".")
        )
    else:
        lead = (
            f"p95 ≈ {_fmt_ms(latency['p95'])}; "
            if latency and latency.get("p95") is not None
            else ""
        )
        summary = f"{lead}busiest {busiest['label']} {util_max:.0%}"
    if wl.get("pattern", {}).get("kind", "constant") != "constant":
        notes.append(
            f"At the pattern's peak of {peak:,.0f} req/s; a run's p95 also covers quieter periods."
        )
    return {
        "method": METHOD,
        "atRps": _round(peak, 2),
        "latencyMs": latency,
        "p95Approximate": True,
        "busiest": (
            {
                "id": busiest["id"],
                "label": busiest["label"],
                "utilization": busiest["utilization"],
                "limitedBy": busiest["limitedBy"],
            }
            if busiest
            else None
        ),
        "overloaded": bool(overloaded),
        "saturationRps": _round(saturation, 1),
        "summary": summary,
        "paths": paths,
        "breakdown": breakdown,
        "parts": [rows[n.id] for n in nodes if n.id in rows],
        "notes": [NOT_INCLUDED, P95_NOTE, *notes],
    }


def _fmt_ms(v: float) -> str:
    if v >= 1000:
        return f"{v / 1000:.2f} s"
    return f"{v:.0f} ms" if v >= 10 else f"{v:.1f} ms"
