"""Design review: an architecture linter over the document. Pure and static (no simulation): it
flags resilience risks reviewers look for (single points of failure, missing timeouts, stacked
retries, timeout inversion, pool exhaustion, unbounded queues...) with concrete numbers.

Works on a normalized document dict (``scenarios.normalize_document``). Findings are dicts with
``id``, ``severity`` (high, medium, low), ``rule``, ``title``, ``detail``, ``suggestion``,
``nodeIds`` and ``edgeIds``, sorted by severity.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from archsim_engine.components.host import machine_speeds
from archsim_engine.components.load_curve import capacity_rps, curve_of, load_factor
from archsim_engine.load import (
    Json,
    estimate_load,
    fixed,
    fmt_int,
    kind_of,
    mean_ms,
    num,
    peak_rps,
    round_half_up,
)
from archsim_engine.registry import ComponentRegistry

REVIEW_RULES: dict[str, str] = {
    "spof": "Single point of failure",
    "cache-dependency": "Cache without fallback",
    "missing-timeout": "Missing timeout",
    "retry-amplification": "Retry amplification",
    "retry-no-backoff": "Retries without backoff",
    "timeout-inversion": "Timeout inversion",
    "unbounded-wait": "Unbounded wait",
    "pool-exhaustion": "Connection-pool exhaustion",
    "connection-storm": "Serverless connection storm",
    "autoscaling-range": "Autoscaling range",
    "queue-unbounded": "Unbounded backlog",
    "queue-sink": "Queue without consumers",
    "queue-partitions": "Idle consumers",
    "optimistic-cache": "Optimistic hit ratio",
    "hot-partition": "Hot partition risk",
    "consistency-all": "Consistency ALL",
    "external-errors": "Unhandled third-party errors",
    "edge-rate-limit": "No rate limit at the edge",
    "deep-chain": "Long synchronous chain",
    "cpu-capacity": "CPU capacity at peak",
    "buffer-cache": "Working set larger than memory",
    "cache-capacity": "Cache smaller than its keyspace",
}

SEV_ORDER = {"high": 0, "medium": 1, "low": 2}
MANAGED_LB = {"aws_alb", "api_gateway"}
MANAGED_MQ = {"sqs", "pubsub", "aws_sqs", "google_pubsub"}
PATH_WALK_BUDGET = 20_000
"""Most path steps the retry-amplification walk takes (layered fan-out has exponentially many
paths); deterministic, so the same document always gives the same findings."""


def _fmt_ms(n: float) -> str:
    return f"{fmt_int(n)} ms"


def _pct(x: float) -> str:
    return f"{int(round_half_up(x * 100))}%"


@dataclass
class _Ctx:
    nodes: list[Json]
    edges: list[Json]
    by_id: dict[str, Json]
    cfg: dict[str, Json]
    kind: dict[str, str]
    out: dict[str, list[Json]] = field(default_factory=dict)
    inc: dict[str, list[Json]] = field(default_factory=dict)
    reachable: set[str] = field(default_factory=set)
    sources: list[Json] = field(default_factory=list)

    def label(self, nid: str) -> str:
        n = self.by_id.get(nid)
        return (n.get("label") if n else None) or nid


def _build_ctx(doc: Json, registry: ComponentRegistry) -> _Ctx:
    nodes: list[Json] = doc["architecture"]["nodes"]
    edges: list[Json] = doc["architecture"]["edges"]
    by_id = {n["id"]: n for n in nodes}
    cfg: dict[str, Json] = {}
    kind: dict[str, str] = {}
    for n in nodes:
        defaults = registry.get(n["type"]).default_config() if n["type"] in registry else {}
        cfg[n["id"]] = {**defaults, **(n.get("config") or {})}
        kind[n["id"]] = kind_of(n["type"], registry)
    c = _Ctx(nodes, edges, by_id, cfg, kind)
    for e in edges:
        if e["source"] not in by_id or e["target"] not in by_id:
            continue
        c.out.setdefault(e["source"], []).append(e)
        c.inc.setdefault(e["target"], []).append(e)
    c.sources = [n for n in nodes if kind[n["id"]] == "traffic"]
    stack = [s["id"] for s in c.sources]
    while stack:
        nid = stack.pop()
        if nid in c.reachable:
            continue
        c.reachable.add(nid)
        stack.extend(e["target"] for e in c.out.get(nid, []))
    return c


def _ecfg(e: Json) -> Json:
    return e.get("config") or {}


def _db_connections(cfg: Json) -> float:
    """Total connections a database accepts across shards and replicas."""
    conns = num(cfg.get("maxConnections"))
    conns = 10.0 if conns is None else conns
    shards = max(1.0, num(cfg.get("shards")) or 1.0)
    rf = num(cfg.get("replicationFactor"))
    copies = max(1 + (num(cfg.get("readReplicas")) or 0.0), 1.0 if rf is None else rf)
    return conns * shards * copies


def _service_workers(cfg: Json) -> float:
    """Most workers a service can run concurrently (at max scale)."""
    per = num(cfg.get("workersPerReplica"))
    per = 4.0 if per is None else per
    replicas = num(cfg.get("replicas"))
    replicas = 1.0 if replicas is None else replicas
    if cfg.get("autoscaling") is True:
        replicas = max(num(cfg.get("maxReplicas")) or 0.0, replicas)
    return per * replicas


def _downstream_budget(c: _Ctx, nid: str) -> tuple[float, Json] | None:
    """Worst-case time a node may spend waiting on its own downstream calls (explicit timeouts)."""
    best: tuple[float, Json] | None = None
    for e in c.out.get(nid, []):
        t = num(_ecfg(e).get("timeoutMs"))
        if t is None:
            continue
        attempts = max(1, int(num(_ecfg(e).get("maxAttempts")) or 1))
        backoff = num(_ecfg(e).get("retryBackoffMs")) or 0.0
        ms = t * attempts + sum(backoff * 2**i for i in range(attempts - 1))
        if best is None or ms > best[0]:
            best = (ms, e)
    return best


def _js_num_or_q(v: float | None) -> str:
    if v is None:
        return "?"
    return str(int(v)) if float(v).is_integer() else repr(v)


def review_design(doc: Json, registry: ComponentRegistry) -> list[Json]:
    """Review a normalized document dict; returns findings sorted by severity."""
    c = _build_ctx(doc, registry)
    workload = doc["workload"]
    out: list[Json] = []
    seen: set[str] = set()

    def add(**f: Any) -> None:
        fid = f"{f['rule']}:{'+'.join(f['nodeIds'])}|{'+'.join(f['edgeIds'])}"
        if fid not in seen:
            seen.add(fid)
            out.append({"id": fid, **f})

    for n in c.nodes:
        nid = n["id"]
        cfg = c.cfg[nid]
        k = c.kind[nid]
        name = c.label(nid)
        on_path = nid in c.reachable

        # -- single points of failure --------------------------------------------------------
        if k == "compute" and n["type"] == "service":
            replicas = num(cfg.get("replicas"))
            replicas = 1.0 if replicas is None else replicas
            serverless = cfg.get("platform") == "serverless"
            scaled_min = (
                (num(cfg.get("minReplicas")) or replicas)
                if cfg.get("autoscaling") is True
                else replicas
            )
            if not serverless and scaled_min <= 1:
                add(
                    severity="high" if on_path else "medium",
                    rule="spof",
                    title=f"{name} runs a single replica",
                    detail=(
                        f"One crash, deploy or host failure takes down every request that passes through "
                        f"{name}{' (autoscaling minimum is 1)' if cfg.get('autoscaling') is True else ''}."
                    ),
                    suggestion="Run at least 2 replicas (3 across zones for critical paths) behind the load balancer.",
                    nodeIds=[nid],
                    edgeIds=[],
                )
        if k == "database":
            replicas = num(cfg.get("readReplicas")) or 0.0
            rf = num(cfg.get("replicationFactor"))
            rf = 1.0 if rf is None else rf
            shards = max(1, int(num(cfg.get("shards")) or 1))
            if replicas == 0 and rf <= 1 and on_path:
                each = f" (each of the {shards} shards has a single copy)" if shards > 1 else ""
                add(
                    severity="medium",
                    rule="spof",
                    title=f"{name} has no replica",
                    detail=f"A primary failure is an outage for every read and write that reaches it{each}.",
                    suggestion=(
                        "Add a standby or read replicas (relational), a replica set (document stores) or "
                        "replication factor 3 (wide-column)."
                    ),
                    nodeIds=[nid],
                    edgeIds=[],
                )
        if k == "network" and on_path and str(cfg.get("technology") or "") not in MANAGED_LB:
            add(
                severity="low",
                rule="spof",
                title=f"{name} is a single instance",
                detail=f"{name} is modelled as one self-hosted instance; if it stops, nothing behind it is reachable.",
                suggestion="Run two instances with failover (floating IP, DNS) or use a managed load balancer.",
                nodeIds=[nid],
                edgeIds=[],
            )
        brokers = num(cfg.get("brokers"))
        if (
            k == "messaging"
            and on_path
            and str(cfg.get("technology") or "") not in MANAGED_MQ
            and (1.0 if brokers is None else brokers) <= 1
        ):
            add(
                severity="medium",
                rule="spof",
                title=f"{name} runs on one broker",
                detail=f"Producers cannot publish while the only broker of {name} is down, so the asynchronous path stops with it.",
                suggestion="Run 3 brokers with replicated partitions/queues, or use a managed queue.",
                nodeIds=[nid],
                edgeIds=[],
            )

        # -- cache as a hard dependency on the read path --------------------------------------
        if k == "compute":
            reads = [
                e for e in c.out.get(nid, []) if "read" in (_ecfg(e).get("requestTypes") or [])
            ]
            via_cache = [
                e
                for e in reads
                if c.kind.get(e["target"]) == "cache" and not _ecfg(e).get("readsOnMiss")
            ]
            fetch = [e for e in reads if _ecfg(e).get("readsOnMiss")]
            hard = [e for e in via_cache if not _ecfg(e).get("continueOnFailure")]
            if via_cache and (hard or not fetch):
                cache_name = c.label((hard or via_cache)[0]["target"])
                detail = (
                    f"{name} reads {c.label(fetch[0]['target'])} on a cache miss, but a failed "
                    f"lookup on {cache_name} fails the read instead of counting as a miss."
                    if fetch
                    else f"Every read from {name} goes through {cache_name}; if the cache itself "
                    "is unavailable, reads have no other path."
                )
                add(
                    severity="medium",
                    rule="cache-dependency",
                    title=f"Reads fail when {cache_name} is down",
                    detail=detail,
                    suggestion=(
                        f"Turn on 'Carry on if this call fails' on the link to {cache_name} and give "
                        f"{name} a link to the data store set to 'Reads only on a cache miss', so "
                        "reads fall back to the data store (check it can take every read), or run "
                        "the cache with replicas."
                    ),
                    nodeIds=[nid, (hard or via_cache)[0]["target"]],
                    edgeIds=[e["id"] for e in (hard or via_cache)],
                )

        # -- unbounded waits -----------------------------------------------------------------
        if k in ("compute", "database") and cfg.get("platform") != "serverless":
            cap = num(cfg.get("queueCapacity"))
            if (
                "queueTimeoutMs" in cfg
                and cfg.get("queueTimeoutMs") is None
                and (1.0 if cap is None else cap) > 0
            ):
                what = "connection acquire" if k == "database" else "queue"
                per = " per replica" if k == "compute" else ""
                add(
                    severity="low",
                    rule="unbounded-wait",
                    title=f"{name} lets requests wait forever",
                    detail=(
                        f"{name} has no {what} timeout, so up to {fmt_int(cap or 0)} requests{per} can wait with "
                        "no limit while clients have long given up."
                    ),
                    suggestion="Set a queue timeout below the caller's timeout so stale work is shed early.",
                    nodeIds=[nid],
                    edgeIds=[],
                )

        # -- autoscaling sanity --------------------------------------------------------------
        if (
            k == "compute"
            and cfg.get("autoscaling") is True
            and cfg.get("platform") != "serverless"
        ):
            mn = num(cfg.get("minReplicas"))
            mn = 1.0 if mn is None else mn
            mx = num(cfg.get("maxReplicas"))
            mx = mn if mx is None else mx
            target = num(cfg.get("targetUtilization"))
            if mx <= mn:
                add(
                    severity="medium",
                    rule="autoscaling-range",
                    title=f"{name} cannot scale out",
                    detail=(
                        f"Autoscaling is on but the maximum ({_js_num_or_q(mx)}) is not above the minimum "
                        f"({_js_num_or_q(mn)}), so no capacity can be added under load."
                    ),
                    suggestion="Raise maxReplicas to cover the expected peak (for example 3× the baseline).",
                    nodeIds=[nid],
                    edgeIds=[],
                )
            elif target is not None and target > 0.85:
                add(
                    severity="low",
                    rule="autoscaling-range",
                    title=f"{name} scales late",
                    detail=(
                        f"Target utilization {_pct(target)} leaves little headroom while new replicas start "
                        f"({_js_num_or_q(num(cfg.get('startupS')))} s); a spike saturates the existing ones first."
                    ),
                    suggestion="Target 50–70% utilization for latency-sensitive services, or keep warm spare capacity.",
                    nodeIds=[nid],
                    edgeIds=[],
                )

        # -- data store specifics ------------------------------------------------------------
        if k == "database":
            shards = max(1, int(num(cfg.get("shards")) or 1))
            skew = float(workload["keySkew"])
            if shards > 1 and skew >= 0.8:
                add(
                    severity="medium",
                    rule="hot-partition",
                    title=f"{name} may have a hot shard",
                    detail=(
                        f"Key popularity follows Zipf s={_js_num_or_q(skew)}: the most popular keys land on a few of "
                        f"the {shards} shards, so the hottest shard can get several times its fair "
                        f"{fixed(100 / shards, 1)}% share. Simulate to measure it."
                    ),
                    suggestion="Pick a higher-cardinality partition key, cache hot keys, or split hot keys across partitions.",
                    nodeIds=[nid],
                    edgeIds=[],
                )
            rf = num(cfg.get("replicationFactor"))
            if cfg.get("consistency") == "all" and (1.0 if rf is None else rf) > 1:
                add(
                    severity="low",
                    rule="consistency-all",
                    title=f"{name} uses consistency ALL",
                    detail=(
                        f"Every operation waits for all {_js_num_or_q(rf)} replicas: latency follows the slowest one "
                        "and a single replica outage fails the request."
                    ),
                    suggestion="Use QUORUM for both reads and writes to keep strong consistency with one replica down.",
                    nodeIds=[nid],
                    edgeIds=[],
                )

            # pool exhaustion / connection storm from upstream services
            conns = _db_connections(cfg)
            workers = 0.0
            blockers: list[str] = []
            for e in c.inc.get(nid, []):
                up = c.cfg[e["source"]]
                if c.kind.get(e["source"]) != "compute":
                    continue
                if up.get("platform") == "serverless":
                    limit = num(up.get("concurrencyLimit"))
                    limit = 1000.0 if limit is None else limit
                    if limit > conns:
                        src = c.label(e["source"])
                        add(
                            severity="high",
                            rule="connection-storm",
                            title=f"{src} can open {fmt_int(limit)} connections to {name}",
                            detail=(
                                f"Up to {fmt_int(limit)} concurrent function instances may each hold a connection, "
                                f"but {name} accepts {fmt_int(conns)}. A traffic spike turns into connection errors "
                                "and lock contention."
                            ),
                            suggestion=(
                                "Put a connection pooler or proxy in front (PgBouncer, RDS Proxy-style), or cap "
                                "function concurrency."
                            ),
                            nodeIds=[e["source"], nid],
                            edgeIds=[e["id"]],
                        )
                    continue
                if up.get("blockingDownstream") is False:
                    continue
                workers += _service_workers(up)
                blockers.append(e["source"])
            if workers > conns:
                qt = num(cfg.get("queueTimeoutMs"))
                fail = f" and fail after {_fmt_ms(qt)}" if qt is not None else ""
                add(
                    severity="medium" if workers > 2 * conns else "low",
                    rule="pool-exhaustion",
                    title=f"{name} has fewer connections than callers",
                    detail=(
                        f"Up to {fmt_int(workers)} blocking workers ({', '.join(c.label(b) for b in blockers)}) can wait "
                        f"on {name}, which accepts {fmt_int(conns)} connections. When it slows down, "
                        f"{fmt_int(workers - conns)} requests queue for a connection{fail}."
                    ),
                    suggestion=(
                        "Size application pools to the database's real concurrency, add a pooler, or cap service "
                        "concurrency."
                    ),
                    nodeIds=[*blockers, nid],
                    edgeIds=[e["id"] for e in c.inc.get(nid, []) if e["source"] in blockers],
                )

        if k == "cache":
            ratio = num(cfg.get("hitRatio"))
            if (
                (cfg.get("hitModel") or "fixed_ratio") == "fixed_ratio"
                and ratio is not None
                and ratio > 0.98
            ):
                add(
                    severity="low",
                    rule="optimistic-cache",
                    title=f"{name} assumes a {fixed(ratio * 100, 1)}% hit ratio",
                    detail=(
                        "Hit ratios this high are rare outside static content; the data store behind it is sized "
                        "for the misses you assume."
                    ),
                    suggestion="Use the measured hit ratio, or the TTL + keyspace model, and test a cold-cache scenario.",
                    nodeIds=[nid],
                    edgeIds=[],
                )

        if k == "messaging":
            if "maxDepth" in cfg and cfg.get("maxDepth") is None:
                add(
                    severity="medium",
                    rule="queue-unbounded",
                    title=f"{name} has an unbounded backlog",
                    detail=(
                        f"If consumers fall behind or a downstream dependency is down, {name} keeps accepting "
                        "messages without limit (memory, disk and recovery time grow)."
                    ),
                    suggestion=(
                        "Set a maximum depth or retention and alert on consumer lag; decide what producers do when "
                        "it is full."
                    ),
                    nodeIds=[nid],
                    edgeIds=[],
                )
            if not c.out.get(nid):
                add(
                    severity="low",
                    rule="queue-sink",
                    title=f"{name} has no downstream link",
                    detail="Consumers process messages but call nothing, so the asynchronous work is not modelled beyond the queue.",
                    suggestion="Link the queue's output to the service or store its consumers write to.",
                    nodeIds=[nid],
                    edgeIds=[],
                )
            partitions = num(cfg.get("partitions"))
            consumers = num(cfg.get("consumers"))
            if partitions is not None and consumers is not None and consumers > partitions:
                add(
                    severity="low",
                    rule="queue-partitions",
                    title=f"{int(consumers - partitions)} consumers of {name} sit idle",
                    detail=(
                        f"Only one consumer per partition is active: {int(partitions)} partitions cap parallelism at "
                        f"{int(partitions)} although {int(consumers)} consumers run."
                    ),
                    suggestion="Add partitions or reduce consumers.",
                    nodeIds=[nid],
                    edgeIds=[],
                )

        if k == "external":
            error_rate = num(cfg.get("errorRate")) or 0.0
            callers = [
                e for e in c.inc.get(nid, []) if (num(_ecfg(e).get("maxAttempts")) or 1) <= 1
            ]
            if error_rate >= 0.01 and callers:
                add(
                    severity="low",
                    rule="external-errors",
                    title=f"{fixed(error_rate * 100, 1)}% of calls to {name} fail",
                    detail=(
                        f"{', '.join(c.label(e['source']) for e in callers)} call {name} once with no retry, so every "
                        "transient upstream error becomes a user-visible failure."
                    ),
                    suggestion=(
                        "Add one retry with backoff and jitter for idempotent calls, or a fallback/queue for "
                        "non-critical calls."
                    ),
                    nodeIds=[nid],
                    edgeIds=[e["id"] for e in callers],
                )

    # -- missing timeouts (grouped per caller) ----------------------------------------------
    timeout_severity = {
        "external": "high",
        "database": "medium",
        "compute": "medium",
        "messaging": "low",
        "cache": "low",
    }
    for n in c.nodes:
        nid = n["id"]
        k = c.kind[nid]
        if k == "traffic":
            continue
        missing = [
            e
            for e in c.out.get(nid, [])
            if num(_ecfg(e).get("timeoutMs")) is None
            and c.kind.get(e["target"]) in timeout_severity
        ]
        if not missing:
            continue
        sev = sorted(
            ("low" if k == "network" else timeout_severity[c.kind[e["target"]]] for e in missing),
            key=lambda s: SEV_ORDER[s],
        )[0]
        names = [c.label(e["target"]) for e in missing]
        who = names[0] if len(names) == 1 else f"{len(names)} dependencies"
        add(
            severity=sev,
            rule="missing-timeout",
            title=f"{c.label(nid)} waits indefinitely on {who}",
            detail=(
                f"No timeout on {', '.join(names)}: a slow or hung dependency keeps {c.label(nid)}'s workers waiting "
                "until its pool fills and requests queue behind them."
            ),
            suggestion=(
                "Set per-call timeouts a little above each dependency's p99 (for example 2× p99), keeping their sum "
                "inside the caller's own budget."
            ),
            nodeIds=[nid, *(e["target"] for e in missing)],
            edgeIds=[e["id"] for e in missing],
        )

    # -- retries without backoff ------------------------------------------------------------
    for e in c.edges:
        attempts = num(_ecfg(e).get("maxAttempts"))
        if (attempts or 1) > 1 and (num(_ecfg(e).get("retryBackoffMs")) or 0) == 0:
            add(
                severity="medium",
                rule="retry-no-backoff",
                title=f"{c.label(e['source'])} retries {c.label(e['target'])} immediately",
                detail=(
                    f"{_js_num_or_q(attempts)} attempts with 0 ms backoff: when {c.label(e['target'])} struggles, "
                    "retries arrive at once and add load exactly when it can least absorb it."
                ),
                suggestion="Use exponential backoff with jitter (e.g. 50–100 ms base) and a retry budget.",
                nodeIds=[e["source"], e["target"]],
                edgeIds=[e["id"]],
            )
    for s in c.sources:
        cfg = c.cfg[s["id"]]
        attempts = num(cfg.get("maxAttempts"))
        if (attempts or 1) > 1 and (num(cfg.get("retryBackoffMs")) or 0) == 0:
            add(
                severity="medium",
                rule="retry-no-backoff",
                title=f"{c.label(s['id'])} retry without backoff",
                detail=f"Clients retry {_js_num_or_q(attempts)} times immediately after a failure or timeout, multiplying load during an incident.",
                suggestion="Add exponential backoff with jitter on the client.",
                nodeIds=[s["id"]],
                edgeIds=[],
            )

    # -- retry amplification and long chains (path walk from each traffic source) -------------
    worst: dict[str, dict[str, Any]] = {}
    deepest: dict[str, list[str]] | None = None
    steps = 0

    def walk(
        nid: str, product: int, factors: list[tuple[str, int]], path: list[str], via: list[str]
    ) -> None:
        nonlocal deepest, steps
        steps += 1
        prev = worst.get(nid)
        if len(path) > 1 and (prev is None or product > prev["product"]):
            worst[nid] = {"product": product, "factors": factors, "path": path, "edges": via}
        if deepest is None or len(path) > len(deepest["path"]):
            deepest = {"path": path, "edges": via}
        if len(path) > 14 or steps > PATH_WALK_BUDGET:
            return
        for e in c.out.get(nid, []):
            if e["target"] in path:
                continue
            m = max(1, int(num(_ecfg(e).get("maxAttempts")) or 1))
            label = f"{c.label(e['source'])}→{c.label(e['target'])}"
            walk(
                e["target"],
                product * m,
                [*factors, (label, m)] if m > 1 else factors,
                [*path, e["target"]],
                [*via, e["id"]],
            )

    for s in c.sources:
        m = max(1, int(num(c.cfg[s["id"]].get("maxAttempts")) or 1))
        walk(s["id"], m, [(f"{c.label(s['id'])} (client)", m)] if m > 1 else [], [s["id"]], [])
    # Nodes behind the last retrying hop inherit the same multiplier: report each distinct chain of
    # retries once, at the node where it lands (the closest one).
    by_signature: dict[str, tuple[str, dict[str, Any]]] = {}
    for landing, w in worst.items():
        if w["product"] < 4:
            continue
        sig = "|".join(f[0] for f in w["factors"])
        prev_sig = by_signature.get(sig)
        if prev_sig is None or len(w["path"]) < len(prev_sig[1]["path"]):
            by_signature[sig] = (landing, w)
    amplified = sorted(by_signature.values(), key=lambda tw: -tw[1]["product"])
    for landing, w in amplified[:3]:
        math_text = " × ".join(str(f[1]) for f in w["factors"])
        parts = ", ".join(f"{f[0]} ×{f[1]}" for f in w["factors"])
        add(
            severity="high" if w["product"] >= 9 else "medium",
            rule="retry-amplification",
            title=f"Up to {w['product']} attempts reach {c.label(landing)} per request",
            detail=(
                f"Retries multiply along the path: {math_text} = {w['product']} ({parts}). When {c.label(landing)} is "
                f"failing, it receives {w['product']}× the traffic exactly when it is weakest."
            ),
            suggestion="Retry at one layer only (usually the outermost), use a retry budget, and fail fast below it.",
            nodeIds=w["path"],
            edgeIds=w["edges"],
        )
    if deepest is not None and len(deepest["path"]) >= 6:
        add(
            severity="low",
            rule="deep-chain",
            title=f"{len(deepest['path']) - 1} synchronous hops on one request path",
            detail=(
                f"{' → '.join(c.label(x) for x in deepest['path'])}: every hop adds latency and its own failure "
                "probability to the request."
            ),
            suggestion="Collapse hops, call independent dependencies in parallel, or move non-critical work to a queue.",
            nodeIds=deepest["path"],
            edgeIds=deepest["edges"],
        )

    # -- timeout inversion -------------------------------------------------------------------
    for e in c.edges:
        if e["source"] not in c.by_id or e["target"] not in c.by_id:
            continue
        from_source = c.kind.get(e["source"]) == "traffic"
        caller_timeout = (
            num(c.cfg[e["source"]].get("timeoutMs"))
            if from_source
            else num(_ecfg(e).get("timeoutMs"))
        )
        if caller_timeout is None:
            continue
        budget = _downstream_budget(c, e["target"])
        if budget is None or caller_timeout >= budget[0]:
            continue
        ms, via = budget
        attempts = max(1, int(num(_ecfg(via).get("maxAttempts")) or 1))
        t = num(_ecfg(via).get("timeoutMs")) or 0.0
        caller = f"{c.label(e['source'])} (client timeout)" if from_source else c.label(e["source"])
        retries = f" ({_fmt_ms(t)} × {attempts} attempts + backoff)" if attempts > 1 else ""
        add(
            severity="medium",
            rule="timeout-inversion",
            title=f"{c.label(e['source'])} gives up before {c.label(e['target'])} does",
            detail=(
                f"{caller} waits {_fmt_ms(caller_timeout)} for {c.label(e['target'])}, but {c.label(e['target'])} may "
                f"wait up to {_fmt_ms(ms)} on {c.label(via['target'])}{retries}: it keeps working on requests nobody "
                "is waiting for."
            ),
            suggestion=(
                "Make timeouts shrink downstream (each caller waits longer than its callee's whole budget) or "
                "propagate deadlines."
            ),
            nodeIds=[e["source"], e["target"], via["target"]],
            edgeIds=[e["id"], via["id"]],
        )

    # -- resource specs: CPU at the peak load, memory against the data ------------------------
    loads = estimate_load(doc, registry)
    peak = peak_rps(workload)
    # CPU threads that slow each other down at the peak (schema 1.7): the design's host machine
    # or a part's own hyperthreads
    speeds = machine_speeds(
        doc["architecture"].get("host"),
        {
            n["id"]: (n["type"], c.cfg[n["id"]], ld.read, ld.write)
            for n in c.nodes
            if (ld := loads.get(n["id"])) is not None
        },
        peak,
    )
    for n in c.nodes:
        nid = n["id"]
        cfg = c.cfg[nid]
        name = c.label(nid)
        cores = num(cfg.get("cpuCores"))
        load = loads.get(nid)
        if cores is not None and load is not None and load.total > 0:
            busy: tuple[float, str] | None = None
            if n["type"] == "service" and cfg.get("platform") != "serverless":
                share = num(cfg.get("cpuShare"))
                share = 1.0 if share is None else share
                ms = mean_ms(cfg.get("serviceTimeMs")) * share
                reps = num(cfg.get("replicas")) or 1.0
                replicas = (
                    max(num(cfg.get("maxReplicas")) or 1.0, reps)
                    if cfg.get("autoscaling") is True
                    else max(1.0, reps)
                )
                if ms:
                    # the rate where a replica's cores fill, each request at that rate's factor
                    curve = curve_of(cfg.get("serviceTimeByLoad"))
                    per_replica = capacity_rps(curve, cores * 1000 / ms)
                    cap = per_replica * replicas
                    ms *= load_factor(curve, per_replica)
                    busy = (
                        load.total / cap,
                        f"{_js_num_or_q(cores)} vCPU × {_js_num_or_q(replicas)} replica{'' if replicas == 1 else 's'} "
                        f"at {fixed(ms, 1)} ms of CPU work per request handle about {fmt_int(cap)} req/s",
                    )
            elif n["type"] == "database":
                read = mean_ms(cfg.get("readServiceTimeMs"))
                write = mean_ms(cfg.get("writeServiceTimeMs"))
                share = num(cfg.get("cpuShare"))
                share = 0.5 if share is None else share
                n_shards = max(1.0, num(cfg.get("shards")) or 1.0)
                copies = 1 + max(0.0, num(cfg.get("readReplicas")) or 0.0)
                primary = (
                    ((load.read / copies) * read + load.write * write) * share / 1000 / n_shards
                )
                busy = (
                    primary / cores,
                    f"each primary needs about {fixed(primary, 1)} of its {_js_num_or_q(cores)} vCPU ({_pct(share)} "
                    f"of {fixed(read, 1)} ms reads and {fixed(write, 1)} ms writes)",
                )
            elif n["type"] == "cache":
                ms = mean_ms(cfg.get("serviceTimeMs"))
                if ms:
                    need = load.total * ms / 1000
                    busy = (
                        need / cores,
                        f"commands need about {fixed(need, 1)} of its {_js_num_or_q(cores)} vCPU",
                    )
            if busy is not None and (speed := speeds.get(nid)) is not None:
                where = (
                    "the host machine" if doc["architecture"].get("host") else "its hyperthreads"
                )
                busy = (
                    busy[0] / speed,
                    f"{busy[1]} at full speed, at {fixed(speed, 2)} of it on {where}",
                )
            if busy is not None and busy[0] >= 0.8:
                over = busy[0] >= 1
                add(
                    severity="high" if over else "medium",
                    rule="cpu-capacity",
                    title=f"{name} needs more CPU at peak"
                    if over
                    else f"{name} has little CPU headroom",
                    detail=(
                        f"At the {fmt_int(peak)} req/s peak about {fmt_int(load.total)} req/s reach {name}; {busy[1]}, so "
                        f"its CPU would be {_pct(busy[0])} busy{' and requests queue for a core' if over else ''}. This "
                        "is a static estimate (first attempts, fixed hit ratios); a run measures it."
                    ),
                    suggestion="Give it more CPU cores or replicas, or reduce the CPU work per request.",
                    nodeIds=[nid],
                    edgeIds=[],
                )
        if n["type"] == "database":
            mem = num(cfg.get("memoryGib"))
            ws = num(cfg.get("workingSetGib"))
            if mem is not None and ws is not None:
                n_shards = max(1.0, num(cfg.get("shards")) or 1.0)
                hit = min(1.0, 0.75 * mem / (ws / n_shards))
                if hit < 0.9:
                    add(
                        severity="medium" if hit < 0.5 else "low",
                        rule="buffer-cache",
                        title=f"{name}'s hot data does not fit in memory",
                        detail=(
                            f"75% of {_js_num_or_q(mem)} GiB is buffer cache for about "
                            f"{_js_num_or_q(round_half_up(ws / n_shards, 1))} GiB of hot data per instance, so about "
                            f"{_pct(1 - hit)} of reads go to disk."
                        ),
                        suggestion="Give each instance more memory, shard the data, or cache the hottest reads in front.",
                        nodeIds=[nid],
                        edgeIds=[],
                    )
        if n["type"] == "cache" and (cfg.get("hitModel") or "fixed_ratio") == "ttl_keyspace":
            mem = num(cfg.get("memoryGib"))
            if mem is not None:
                item = num(cfg.get("itemSizeKb"))
                item = 1.0 if item is None else item
                keys = max(1, int(0.8 * mem * 1024 * 1024 // item))
                key_count = int(workload["keyCount"])
                if keys < key_count:
                    add(
                        severity="low",
                        rule="cache-capacity",
                        title=f"{name} holds {keys:,} of {key_count:,} keys",
                        detail=(
                            f"80% of {_js_num_or_q(mem)} GiB fits about {keys:,} items of {_js_num_or_q(item)} KiB; the "
                            "least recently used keys are evicted, so expect a lower hit ratio than with unbounded memory."
                        ),
                        suggestion="Add memory if the hit ratio matters, or shorten what you cache.",
                        nodeIds=[nid],
                        edgeIds=[],
                    )

    # -- rate limit at the edge --------------------------------------------------------------
    if c.sources:
        limited = any(
            nid in c.reachable
            and (num(c.cfg[nid].get("rateLimitRps")) or 0) > 0
            and c.kind[nid] == "network"
            for nid in c.by_id
        )
        if not limited:
            first_hops = [e["target"] for s in c.sources for e in c.out.get(s["id"], [])]
            add(
                severity="low",
                rule="edge-rate-limit",
                title="No rate limit at the edge",
                detail=(
                    "Nothing in front of the system caps traffic, so a spike, a retry storm or a bot flood reaches "
                    "every tier at full force."
                ),
                suggestion="Add a rate limit on the gateway or load balancer sized to the capacity you have measured.",
                nodeIds=first_hops,
                edgeIds=[],
            )

    order: Callable[[Json], tuple[int, str, str, str]] = lambda f: (  # noqa: E731
        SEV_ORDER[f["severity"]],
        f["rule"],
        f["title"].casefold(),
        f["title"],
    )
    return sorted(out, key=order)


def count_by_severity(findings: list[Json]) -> dict[str, int]:
    out = {"high": 0, "medium": 0, "low": 0}
    for f in findings:
        out[f["severity"]] += 1
    return out
