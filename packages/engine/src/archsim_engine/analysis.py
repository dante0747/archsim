"""Rule-based bottleneck analysis over simulation results.

Each node gets a ``status``:

* ``saturated`` - utilization >= 95%, or >= 1% of arrivals rejected, or its queue kept growing
* ``hot``       - utilization >= 85%
* ``warm``      - utilization >= 70%
* ``ok`` / ``idle``

A saturated service whose workers spend most of their occupied time *blocked on downstream calls*
while a downstream component is also saturated is reported as a **symptom**, not a root cause.
Every finding cites the numbers it is based on; nothing here is invented.
"""

from __future__ import annotations

import math
from collections import deque
from typing import Any

from archsim_engine.findings_ext import extension_findings
from archsim_engine.schema import ArchitectureDocument

STATUS_RANK = {"saturated": 3, "hot": 2, "warm": 1, "ok": 0, "idle": -1}

SLOT_NOUN = {
    "service": "workers",
    "database": "connections",
    "cache": "command executors",
    "load_balancer": "connections",
}

SUGGESTION = {
    "service": "Add replicas or workers per replica, or reduce the service time.",
    "database": (
        "Raise the connection limit only if the database has spare CPU/IO; otherwise raise the "
        "cache hit ratio, add read replicas or optimize the slow queries."
    ),
    "cache": "Add command executors (shard the cache) or reduce command time.",
    "load_balancer": "Raise the balancer's connection limit or scale it out.",
}


def _queue_growing(series: list[float | None], i0: int, i1: int) -> bool:
    window = [v or 0.0 for v in series[i0:i1]]
    if len(window) < 4:
        return False
    q = max(1, len(window) // 4)
    first = sum(window[:q]) / q
    last = sum(window[-q:]) / q
    return last > max(2.0, 1.5 * first + 1.0)


def classify(node: dict[str, Any], i0: int, i1: int) -> tuple[str, list[str]]:
    s = node["summary"]
    if node["category"] == "traffic":  # client groups
        return "ok", []
    if not s["arrivals"]:
        return "idle", ["received no requests"]
    util = s["utilization"] or 0.0
    rej = s["rejectionRate"] or 0.0
    growing = _queue_growing(node["timeseries"].get("queueMean", []), i0, i1)
    if node["type"] == "message_queue" and rej < 0.01 and (util >= 0.85 or growing):
        # Producers still get their acks: a saturated consumer group shows up as consumer lag
        # (see the queue_lag finding), not as a bottleneck on the client's request path.
        return "hot", [f"consumers {util:.0%} busy"] + (["backlog kept growing"] if growing else [])
    reasons = []
    if util >= 0.95:
        reasons.append(f"utilization {util:.0%}")
    if rej >= 0.01:
        reasons.append(f"{rej:.1%} of requests rejected")
    if growing:
        reasons.append("queue kept growing")
    if reasons:
        return "saturated", reasons
    if util >= 0.85:
        return "hot", [f"utilization {util:.0%}"]
    if util >= 0.70:
        return "warm", [f"utilization {util:.0%}"]
    return "ok", []


def _downstream(start: str, out: dict[str, list[str]]) -> list[str]:
    seen = {start}
    order = []
    q = deque(out.get(start, []))
    while q:
        v = q.popleft()
        if v in seen:
            continue
        seen.add(v)
        order.append(v)
        q.extend(out.get(v, []))
    return order


def _fmt_rps(v: float | None) -> str:
    return "n/a" if v is None else f"{v:,.0f} req/s"


def analyze(report: dict[str, Any], resolved: ArchitectureDocument) -> list[dict[str, Any]]:
    nodes: list[dict[str, Any]] = report["nodes"]
    summary = report["summary"]
    window = report["window"]
    bucket_s = report["timeseries"]["bucketS"]
    i0 = math.ceil(window["warmupS"] / bucket_s)
    i1 = math.floor(window["durationS"] / bucket_s)
    by_id = {n["id"]: n for n in nodes}
    out: dict[str, list[str]] = {}
    for e in resolved.architecture.edges:
        out.setdefault(e.source, []).append(e.target)

    for n in nodes:
        n["status"], n["statusReasons"] = classify(n, i0, i1)

    findings: list[dict[str, Any]] = []
    if summary["arrived"] == 0:
        findings.append(
            {
                "severity": "info",
                "kind": "no_traffic",
                "nodeId": None,
                "title": "No requests were generated",
                "detail": "The workload offered 0 requests in the measurement window, so there is nothing to analyse.",
                "evidence": {},
            }
        )
        return findings

    candidates = [n for n in nodes if n["status"] in ("saturated", "hot")]
    roots: list[dict[str, Any]] = []
    symptoms: list[tuple[dict[str, Any], dict[str, Any], float]] = []
    for n in candidates:
        s = n["summary"]
        util = s["utilization"] or 0.0
        own = (s["cpuUtilization"] / util) if (s["cpuUtilization"] is not None and util) else 1.0
        hot_down = [
            by_id[d]
            for d in _downstream(n["id"], out)
            if d in by_id and by_id[d]["status"] in ("saturated", "hot")
        ]
        if hot_down and (own < 0.5 or n["type"] == "load_balancer"):
            symptoms.append((n, hot_down[0], own))
        else:
            roots.append(n)
    roots.sort(
        key=lambda n: (
            STATUS_RANK[n["status"]],
            n["summary"]["rejectionRate"] or 0.0,
            n["summary"]["utilization"] or 0.0,
        ),
        reverse=True,
    )

    for rank, n in enumerate(roots):
        s = n["summary"]
        noun = SLOT_NOUN.get(n["type"], "slots")
        util = s["utilization"] or 0.0
        if s.get("slots") is not None and s.get("utilization") is not None:
            parts = [f"On average {util:.0%} of its {s['slots']:,} {noun} were busy"]
            peak = s.get("peakUtilization")
            if peak is not None and peak - util >= 0.1:
                parts[0] += f", peaking at {peak:.0%}"
            parts[0] += "."
        else:
            # Components without a slot pool (e.g. unlimited external APIs) saturate by rejecting.
            parts = [f"It received {_fmt_rps(s['arrivalRps'])}."]
        if (s["meanQueue"] or 0) >= 0.5 or s["peakQueue"]:
            q = f"The queue averaged {s['meanQueue'] or 0:.1f} requests (peak {s['peakQueue']:,})"
            if "queue kept growing" in n["statusReasons"]:
                q += " and kept growing"
            parts.append(q + ".")
        if s["rejectedTotal"]:
            reasons = ", ".join(f"{k.replace('_', ' ')}: {v:,}" for k, v in s["rejected"].items())
            parts.append(
                f"{s['rejectedTotal']:,} requests ({(s['rejectionRate'] or 0):.1%}) were rejected ({reasons})."
            )
        if s["capacityRps"]:
            parts.append(
                f"At the observed {s['meanHoldMs']:.1f} ms per request it can sustain about "
                f"{_fmt_rps(s['capacityRps'])}; it received {_fmt_rps(s['arrivalRps'])}."
            )
        suggestion = SUGGESTION.get(n["type"], "Increase this component's capacity.")
        cpu = s["cpuUtilization"]
        if cpu is not None and util and cpu / util < 0.7:
            parts.append(
                f"Only {cpu / util:.0%} of busy worker time was its own processing; the rest was "
                "spent blocked on downstream calls."
            )
            suggestion += " Faster dependencies (or non-blocking I/O) would also free workers."
        if n["status"] == "saturated":
            title = (
                f"{n['label']} is the primary bottleneck"
                if rank == 0
                else f"{n['label']} is also saturated"
            )
        else:
            title = f"{n['label']} is running hot ({util:.0%} busy)"
        findings.append(
            {
                "severity": "critical" if n["status"] == "saturated" else "warning",
                "kind": "bottleneck" if n["status"] == "saturated" else "hot",
                "nodeId": n["id"],
                "title": title,
                "detail": " ".join(parts),
                "suggestion": suggestion,
                "evidence": {
                    "utilization": s["utilization"],
                    "meanQueue": s["meanQueue"],
                    "peakQueue": s["peakQueue"],
                    "rejectedTotal": s["rejectedTotal"],
                    "arrivalRps": s["arrivalRps"],
                    "capacityRps": s["capacityRps"],
                },
            }
        )

    for n, down, own in symptoms:
        s = n["summary"]
        detail = (
            f"Its {SLOT_NOUN.get(n['type'], 'slots')} were {(s['utilization'] or 0):.0%} occupied, "
            if n["type"] != "load_balancer"
            else "It is backed up "
        )
        if n["type"] != "load_balancer":
            detail += (
                f"but only {own:.0%} of that time was its own processing; the rest was spent "
                f"blocked on downstream calls while {down['label']} is {down['status']}."
            )
        else:
            detail += f"because {down['label']} downstream is {down['status']}."
        findings.append(
            {
                "severity": "warning",
                "kind": "symptom",
                "nodeId": n["id"],
                "title": f"{n['label']} is saturated as a consequence of {down['label']}",
                "detail": detail + f" Relieving {down['label']} should relieve it too.",
                "evidence": {
                    "utilization": s["utilization"],
                    "cpuUtilization": s["cpuUtilization"],
                    "blockedOn": down["id"],
                },
            }
        )

    # -- client-visible effects --------------------------------------------------------------------
    offered = summary["offeredRps"] or 0.0
    thr = summary["throughputRps"] or 0.0
    if offered > 0 and thr < 0.97 * offered:
        findings.append(
            {
                "severity": "critical" if thr < 0.8 * offered else "warning",
                "kind": "throughput_gap",
                "nodeId": None,
                "title": f"Only {thr:,.0f} of {offered:,.0f} req/s completed successfully",
                "detail": (
                    f"Success rate {(summary['successRate'] or 0):.1%}; "
                    f"{summary['failed']:,} requests failed and {summary['timedOut']:,} timed out "
                    "in the measurement window."
                ),
                "evidence": {"offeredRps": offered, "throughputRps": thr},
            }
        )
    if summary["failureBreakdown"]:
        top = summary["failureBreakdown"][0]
        where = top["nodeLabel"] or "the system"
        if top["reason"] == "client_timeout":
            # The client group that gave up (several groups can have different timeouts).
            src = next((n for n in resolved.architecture.nodes if n.id == top["nodeId"]), None)
            tmo = src.config.get("timeoutMs") if src else None
            text = (
                f"{top['count']:,} requests exceeded the {tmo:g} ms client timeout"
                if tmo
                else f"{top['count']:,} requests hit the client timeout"
            )
        elif top["reason"] == "dependency_timeout":
            text = f"{top['count']:,} requests failed at {where}: it {top['reasonText']} (link {top['edgeId']})"
        else:
            text = f"{top['count']:,} requests failed at {where}: {top['reasonText']}"
        findings.append(
            {
                "severity": "warning",
                "kind": "failures",
                "nodeId": top["nodeId"],
                "title": "Main cause of failed requests",
                "detail": text + f" ({(top['share'] or 0):.0%} of all failures).",
                "evidence": {"breakdown": summary["failureBreakdown"][:5]},
            }
        )
    amp = summary["retryAmplification"]
    if amp and amp > 1.05:
        findings.append(
            {
                "severity": "warning",
                "kind": "retries",
                "nodeId": None,
                "title": f"Client retries amplified load by {amp:.2f}x",
                "detail": (
                    f"{summary['attempts']:,} attempts were sent for {summary['arrived']:,} user "
                    "requests. Retries add load exactly when the system is struggling."
                ),
                "evidence": {"retryAmplification": amp},
            }
        )

    # -- work done for clients that already gave up -------------------------------------------------
    wasted_rows = []
    for n in nodes:
        s = n["summary"]
        wasted = s["counters"].get("wasted", 0)
        if (
            n["type"] == "load_balancer"
            or n["category"] == "traffic"
            or not wasted
            or not s["completed"]
        ):
            continue
        share = wasted / s["completed"]
        if share >= 0.01:
            wasted_rows.append((share, wasted, n))
    if wasted_rows:
        wasted_rows.sort(key=lambda r: (-r[0], r[2]["id"]))
        share, wasted, worst = wasted_rows[0]
        others = "; ".join(f"{n['label']} {sh:.0%}" for sh, _, n in wasted_rows)
        findings.append(
            {
                "severity": "critical" if share >= 0.25 else "warning",
                "kind": "wasted_work",
                "nodeId": worst["id"],
                "title": f"{share:.0%} of completed work at {worst['label']} was wasted",
                "detail": (
                    f"{wasted:,} requests finished at {worst['label']} after their client had already "
                    f"timed out (share of completed work: {others}). That capacity helped no one, "
                    "which is how overload turns into near-zero goodput."
                ),
                "suggestion": "Shed load early: set a queue limit or a queue timeout below the client timeout.",
                "evidence": {"wasted": wasted, "completed": worst["summary"]["completed"]},
            }
        )

    # -- caches -------------------------------------------------------------------------------------
    for n in nodes:
        if n["type"] != "cache" or n["summary"]["hitRatio"] is None:
            continue
        s = n["summary"]
        misses = s["counters"].get("misses", 0)
        miss_rps = misses / window["measuredS"] if window["measuredS"] else 0.0
        target = next(
            (
                by_id[e.target]["label"]
                for e in resolved.architecture.edges
                if e.source == n["id"] and e.source_port == "miss" and e.target in by_id
            ),
            "the data store",
        )
        findings.append(
            {
                "severity": "info",
                "kind": "cache",
                "nodeId": n["id"],
                "title": f"{n['label']} hit ratio {s['hitRatio']:.0%}",
                "detail": f"{s['hitRatio']:.0%} of reads were served from the cache; {miss_rps:,.0f} reads/s continued to {target}.",
                "evidence": {"hitRatio": s["hitRatio"], "missRps": round(miss_rps, 3)},
            }
        )

    if not roots and not symptoms:
        busiest = max(
            (n for n in nodes if n["summary"]["utilization"] is not None),
            key=lambda n: n["summary"]["utilization"] or 0.0,
            default=None,
        )
        detail = "Throughput tracks the offered load."
        if busiest is not None:
            detail = (
                f"The busiest component is {busiest['label']} at "
                f"{(busiest['summary']['utilization'] or 0):.0%} utilization. " + detail
            )
        findings.insert(
            0,
            {
                "severity": "ok",
                "kind": "healthy",
                "nodeId": busiest["id"] if busiest else None,
                "title": "No component is saturated",
                "detail": detail,
                "evidence": {},
            },
        )
    findings.extend(extension_findings(report, resolved))
    return findings
