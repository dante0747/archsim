"""Findings about data stores and dependencies (see findings_ext).

* ``hot_shard``         - one shard of a sharded database gets far more than its fair share
* ``throttling``        - provisioned throughput, rate or concurrency limits rejected requests
* ``external_errors``   - a third-party API failed calls (and how many client requests it took down)
* ``external_latency``  - a third-party API accounts for most of the request latency
* ``read_replicas``     - how much of the read load replicas absorbed
* ``replication_lag``   - replicas fell behind the primary (stale reads)
* ``lock_contention``   - writes queued for row/document locks on hot keys
"""

from __future__ import annotations

from typing import Any

from archsim_engine.schema import ArchitectureDocument

HOT_SHARD_RATIO = 1.5  # share / fair share above which a shard is called hot
HOT_SHARD_BUSY_RATIO = 1.25  # ...or this ratio when the hot shard is also at least 85% busy
HOT_SHARD_BUSY = 0.85
MIN_SAMPLES = 50


def _f(v: Any) -> float:
    return float(v) if isinstance(v, int | float) else 0.0


def _hot_shard(n: dict[str, Any], resolved: ArchitectureDocument) -> dict[str, Any] | None:
    cfg = n.get("config") or {}
    shards = int(cfg.get("shards") or 1)
    if shards < 2:
        return None
    s = n["summary"]
    counters: dict[str, int] = s.get("counters") or {}
    counts = [int(counters.get(f"shard{i}", 0)) for i in range(shards)]
    # A scattered read (an Elasticsearch-style search) runs on every shard but is counted once,
    # on shard 0: it loads the shards evenly, so only keyed operations can make one hot.
    counts[0] = max(0, counts[0] - int(counters.get("scatterReads", 0)))
    total = sum(counts)
    if total < MIN_SAMPLES:
        return None
    hot = max(range(shards), key=lambda i: counts[i])
    share = counts[hot] / total
    fair = 1.0 / shards
    ratio = share / fair
    if ratio < HOT_SHARD_BUSY_RATIO:
        return None
    # Instances of a shard that serve its requests: the primary alone when reads go to the
    # primary (MongoDB's default read preference), else the primary and its replicas.
    behaviour = {b.get("id") for b in n.get("behaviour") or []}
    serving = 1 if "primary_reads" in behaviour else 1 + int(cfg.get("readReplicas") or 0)
    per_shard_slots = serving * int(cfg.get("maxConnections") or 1)
    hold = _f(s.get("meanHoldMs"))
    rate = _f(s.get("arrivalRps"))
    est = (rate * share * hold / 1000.0) / per_shard_slots if (hold and rate) else None
    util = s.get("utilization")
    # With CPU cores set, a shard usually runs out of CPU long before it runs out of connections.
    cores, cpu_share, service = (
        _f(cfg.get("cpuCores")),
        _f(cfg.get("cpuShare")),
        _f(s.get("meanServiceMs")),
    )
    if cores and cpu_share and service and rate:
        est_cpu = rate * share * service * cpu_share / (1000.0 * cores * serving)
        if est is None or est_cpu > est:
            est, util = est_cpu, s.get("cpuCoreUtilization")
    if ratio < HOT_SHARD_RATIO and (est is None or est < HOT_SHARD_BUSY):
        return None
    wl = resolved.workload
    label = n["label"]
    detail = (
        f"With {shards} shards each should get about {fair:.0%} of requests, but key popularity "
        f"(Zipf s={wl.key_skew:g} over {wl.key_count:,} keys) sends {share:.0%} to shard {hot}, "
        f"{ratio:.1f}x its fair share."
    )
    if est is not None:
        detail += f" That shard is an estimated {est:.0%} busy"
        detail += f" while the whole cluster averages {util:.0%}." if util is not None else "."
    return {
        "severity": "critical" if est is not None and est >= 0.95 else "warning",
        "kind": "hot_shard",
        "nodeId": n["id"],
        "title": f"Hot shard: {label} shard {hot} gets {share:.0%} of requests",
        "detail": detail,
        "suggestion": (
            "Pick a partition key with more even popularity, cache the hottest keys in front of the "
            "database, or split hot keys (for example with a suffix). More shards do not help a "
            "single hot key."
        ),
        "evidence": {
            "shardShares": [round(c / total, 4) for c in counts],
            "hotShard": hot,
            "share": round(share, 4),
            "fairShare": round(fair, 4),
            "estimatedHotShardUtilization": round(est, 4) if est is not None else None,
        },
    }


def _throttling(n: dict[str, Any]) -> dict[str, Any] | None:
    s = n["summary"]
    arrivals = int(s.get("arrivals") or 0)
    if arrivals < MIN_SAMPLES:
        return None
    rej: dict[str, int] = s.get("rejected") or {}
    counters: dict[str, int] = s.get("counters") or {}
    # databases reject throttled requests; external APIs answer them as failed calls (counters)
    throttled = int(rej.get("throttled", 0)) + int(counters.get("throttled", 0))
    limited = int(rej.get("rate_limited", 0)) + int(counters.get("rateLimited", 0))
    count = throttled + limited
    share = count / arrivals
    if share <= 0.01:
        return None
    cfg = n.get("config") or {}
    label = n["label"]
    if n["type"] == "database":
        limit = _f(cfg.get("throughputLimitRps"))
        shards = int(cfg.get("shards") or 1)
        what = f"the provisioned throughput of {limit:,.0f} ops/s per shard ({shards} shard{'s' if shards > 1 else ''})"
        suggestion = (
            "Raise the provisioned throughput (or use on-demand capacity), cache hot reads in "
            "front of the store, or spread the load over more partitions with an even key."
        )
    else:
        parts = []
        if limited:
            parts.append(f"the rate limit of {_f(cfg.get('rateLimitRps')):,.0f} req/s")
        if throttled:
            parts.append(
                f"the concurrency limit of {int(cfg.get('concurrencyLimit') or 0):,} calls in flight"
            )
        what = " and ".join(parts) or "the provider's limits"
        suggestion = (
            "Stay under the limit: queue or batch the calls, cache responses, retry with "
            "exponential backoff and jitter, or ask for a higher limit."
        )
    return {
        "severity": "critical" if share > 0.1 else "warning",
        "kind": "throttling",
        "nodeId": n["id"],
        "title": f"{label} throttled {share:.0%} of requests",
        "detail": f"{count:,} of {arrivals:,} requests ({share:.1%}) were turned away because they exceeded {what}.",
        "suggestion": suggestion,
        "evidence": {
            "throttled": throttled,
            "rateLimited": limited,
            "arrivals": arrivals,
            "share": round(share, 4),
        },
    }


def _external(n: dict[str, Any], report: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    s = n["summary"]
    arrivals = int(s.get("arrivals") or 0)
    if arrivals < MIN_SAMPLES:
        return out
    label = n["label"]
    errors = int((s.get("counters") or {}).get("upstreamErrors", 0))
    if errors:
        share = errors / arrivals
        reached = sum(
            int(r.get("count") or 0)
            for r in report["summary"].get("failureBreakdown", [])
            if r.get("nodeId") == n["id"] and r.get("reason") == "upstream_error"
        )
        detail = f"{errors:,} of {arrivals:,} calls ({share:.1%}) failed with provider errors"
        detail += (
            f"; {reached:,} client requests failed because of them."
            if reached
            else "; none reached the clients."
        )
        out.append(
            {
                "severity": "warning" if share >= 0.01 or reached else "info",
                "kind": "external_errors",
                "nodeId": n["id"],
                "title": f"{label} failed {share:.1%} of calls",
                "detail": detail,
                "suggestion": (
                    "Retry idempotent calls with exponential backoff and jitter (max attempts on the "
                    "link), set a timeout, and add a fallback or circuit breaker so provider errors "
                    "do not fail whole requests."
                ),
                "evidence": {
                    "errors": errors,
                    "arrivals": arrivals,
                    "share": round(share, 4),
                    "reachedClients": reached,
                },
            }
        )
    hold = _f(s.get("meanHoldMs"))
    p50 = _f(report["summary"]["latencyMs"].get("p50"))
    arrived = int(report["summary"].get("arrived") or 0)
    if hold and p50 and hold >= 0.5 * p50 and arrived and arrivals >= 0.2 * arrived:
        out.append(
            {
                "severity": "info",
                "kind": "external_latency",
                "nodeId": n["id"],
                "title": f"{label} dominates request latency",
                "detail": (
                    f"Calls to {label} take {hold:,.0f} ms on average, while the median request takes "
                    f"{p50:,.0f} ms end to end."
                ),
                "suggestion": (
                    "If the user does not need the result right away, call it asynchronously through "
                    "a queue; otherwise cache responses and keep its timeout well below the client's."
                ),
                "evidence": {"meanCallMs": round(hold, 3), "requestP50Ms": round(p50, 3)},
            }
        )
    return out


def _read_replicas(n: dict[str, Any]) -> dict[str, Any] | None:
    cfg = n.get("config") or {}
    replicas = int(cfg.get("readReplicas") or 0)
    if replicas < 1:
        return None
    counters: dict[str, int] = n["summary"].get("counters") or {}
    reads = int(counters.get("reads", 0))
    writes = int(counters.get("writes", 0))
    if reads < MIN_SAMPLES:
        return None
    replica_reads = int(counters.get("replicaReads", 0))
    share = replica_reads / reads
    write_share = writes / (reads + writes) if (reads + writes) else 0.0
    suggestion = (
        "Writes are most of the load, so replicas add little: shard the data or make writes cheaper."
        if write_share > 0.5
        else "Replica reads can be stale while replicas replay the primary's writes (see replication lag)."
    )
    return {
        "severity": "info",
        "kind": "read_replicas",
        "nodeId": n["id"],
        "title": f"{n['label']} read replicas served {share:.0%} of reads",
        "detail": (
            f"{replicas} replica{'s' if replicas > 1 else ''} per shard took {replica_reads:,} of "
            f"{reads:,} reads; all {writes:,} writes still went to the primary."
        ),
        "suggestion": suggestion,
        "evidence": {
            "replicaReads": replica_reads,
            "reads": reads,
            "writes": writes,
            "share": round(share, 4),
        },
    }


LAG_WARN_MS = 1000.0  # replica lag above which reads are noticeably stale
LOCK_WARN_SHARE = 0.25  # mean lock wait as a share of the write's own time


def _replication_lag(n: dict[str, Any]) -> dict[str, Any] | None:
    s = n["summary"]
    lag = s.get("maxReplicationLagMs")
    if not isinstance(lag, int | float) or lag < LAG_WARN_MS:
        return None
    counters: dict[str, int] = s.get("counters") or {}
    stale = int(counters.get("staleReads", 0))
    replica_reads = int(counters.get("replicaReads", 0))
    writes = int(counters.get("writes", 0))
    cfg = n.get("config") or {}
    label = n["label"]
    detail = (
        f"Replicas replay the primary's writes; they fell up to {lag / 1000:.1f} s behind. "
        f"{writes:,} writes in the window had to be replayed"
    )
    detail += (
        f", and {stale:,} of {replica_reads:,} replica reads returned stale data."
        if replica_reads
        else "."
    )
    workers = cfg.get("replicaApplyWorkers")
    return {
        "severity": "warning",
        "kind": "replication_lag",
        "nodeId": n["id"],
        "title": f"{label} replicas lag up to {lag / 1000:.1f} s behind the primary",
        "detail": detail,
        "suggestion": (
            "Writes outpace replay. Make writes cheaper, shard the writes, use a database with "
            "parallel replay"
            + (" (or raise replica apply workers)" if workers is None else "")
            + ", or read your own writes from the primary."
        ),
        "evidence": {
            "maxLagMs": lag,
            "staleReads": stale,
            "replicaReads": replica_reads,
            "writes": writes,
        },
    }


def _lock_contention(n: dict[str, Any], resolved: ArchitectureDocument) -> dict[str, Any] | None:
    s = n["summary"]
    wait = s.get("meanLockWaitMs")
    svc = _f(s.get("meanServiceMs"))
    counters: dict[str, int] = s.get("counters") or {}
    waits = int(counters.get("lockWaits", 0))
    writes = int(counters.get("writes", 0))
    if not isinstance(wait, int | float) or writes < MIN_SAMPLES or wait <= 0:
        return None
    if svc and wait < LOCK_WARN_SHARE * svc:
        return None
    wl = resolved.workload
    return {
        "severity": "warning" if wait >= svc else "info",
        "kind": "lock_contention",
        "nodeId": n["id"],
        "title": f"Writes to {n['label']} wait {wait:.1f} ms on average for row locks",
        "detail": (
            f"{waits:,} of {writes:,} writes found their row locked by another write. Key "
            f"popularity (Zipf s={wl.key_skew:g} over {wl.key_count:,} keys) concentrates writes "
            "on a few hot rows, and a waiting write holds its connection."
        ),
        "suggestion": (
            "Spread hot rows (shard counters, append instead of update), shorten transactions, or "
            "batch updates to the hottest keys."
        ),
        "evidence": {"meanLockWaitMs": wait, "lockWaits": waits, "writes": writes},
    }


def _pending_pods(n: dict[str, Any]) -> dict[str, Any] | None:
    s = n["summary"]
    peak = int(s.get("podsPendingPeak") or 0)
    if peak <= 0:
        return None
    adds = int((s.get("counters") or {}).get("nodeAdds", 0))
    cfg = n.get("config") or {}
    longest = s.get("maxPendingS") or 0.0
    end = int(s.get("podsPendingEnd") or 0)
    detail = (
        f"Up to {peak} pod(s) waited for a node with room for their requests, the longest "
        f"{longest:.0f} s. "
    )
    if cfg.get("clusterAutoscaler"):
        detail += (
            f"The cluster autoscaler added {adds} node(s), each ready about "
            f"{float(cfg.get('nodeProvisionS') or 0):.0f} s after it was asked for"
        )
        detail += f"; {end} pod(s) were still Pending at the end." if end else "."
    else:
        detail += (
            "The cluster autoscaler is off, so Pending pods wait until other pods are removed."
        )
    return {
        "severity": "warning" if end or longest >= 30 else "info",
        "kind": "pods_pending",
        "nodeId": n["id"],
        "title": f"Pods waited up to {longest:.0f} s for room on {n['label']}",
        "detail": detail,
        "suggestion": (
            "Keep headroom (more nodes, or a lower HPA target), use faster-provisioning nodes or "
            "smaller pod requests, or raise the maximum node count."
        ),
        "evidence": {
            "podsPendingPeak": peak,
            "maxPendingS": longest,
            "nodeAdds": adds,
            "podsPendingEnd": end,
        },
    }


def findings(report: dict[str, Any], resolved: ArchitectureDocument) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for n in report["nodes"]:
        if n["type"] == "kubernetes_cluster":
            f = _pending_pods(n)
            if f:
                out.append(f)
            continue
        if n["type"] == "database":
            for f in (
                _hot_shard(n, resolved),
                _read_replicas(n),
                _replication_lag(n),
                _lock_contention(n, resolved),
            ):
                if f:
                    out.append(f)
        if n["type"] == "external_api":
            out.extend(_external(n, report))
        t = _throttling(n)
        if t:
            out.append(t)
    return out
