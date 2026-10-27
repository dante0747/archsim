"""Turns runtime state into the JSON simulation report.

Report sections: ``provenance`` (exactly what was simulated), ``window``, ``summary`` (client-side
KPIs and request accounting), ``timeseries``, ``nodes`` and ``edges`` (per-component metrics,
including each node's ``explain`` calculations and technology ``behaviour``), ``findings``
(bottleneck analysis), ``assumptions`` (what was asserted vs. modeled), ``traces`` (a sample of
individual requests, step by step), ``explain`` (system-level calculations, including where
the time of a request goes), ``estimate`` (what queueing formulas predict for the same input,
:mod:`archsim_engine.estimate`) and ``confidence`` (how much of the result rests on measured or
documented inputs, :mod:`archsim_engine.confidence`).
"""

from __future__ import annotations

import hashlib
import json
import math
import platform
from typing import TYPE_CHECKING, Any

import simpy

from archsim_engine.analysis import analyze
from archsim_engine.confidence import report_confidence
from archsim_engine.cost import estimate_cost
from archsim_engine.distributions import Constant, Exponential, LogNormal, Uniform
from archsim_engine.estimate import estimate_design
from archsim_engine.explain import formula, generic, num, pct
from archsim_engine.metrics import Timeline, _round, round_series, safe_ratio
from archsim_engine.provenance import input_provenance, provenance_counts
from archsim_engine.registry import ComponentRegistry
from archsim_engine.review import review_design
from archsim_engine.scenarios import simulation_input
from archsim_engine.sizing import sizing_summary
from archsim_engine.technology import TIER_NOTE
from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION, SCHEMA_VERSION

if TYPE_CHECKING:
    from archsim_engine.components.traffic_source import TrafficSourceRuntime
    from archsim_engine.engine import PreparedRun
    from archsim_engine.runtime import SimContext
    from archsim_engine.schema import ArchitectureDocument

MODEL_NOTES = [
    "Results come from a synthetic discrete-event simulation of the model you drew, driven by "
    "the assumptions you entered. They are estimates, not measurements, and not a production "
    "guarantee.",
    "Without calibration against real telemetry, absolute numbers (latency, capacity) can be far "
    "off. Comparisons between scenarios under the same assumptions are more trustworthy than "
    "any single absolute value.",
    "Arrivals are open-loop: clients keep sending at the configured rate even when the system "
    "slows down (no back-pressure on users).",
    "Network latency is sampled per link and direction. Bandwidth, packet loss, connection setup "
    "and TLS are not modeled.",
    "Components behave like the technology you picked (row locks and group commit, replica "
    "lag, single-threaded caches, event loops, GC pauses, partitions, visibility timeouts...), "
    "with documented defaults where they exist and illustrative ones elsewhere; every such "
    "setting is listed under Technology behaviour and can be overridden. CPU contention and "
    "memory limits apply when you set CPU and memory. Noisy neighbours are not modeled. Capacity "
    "is fixed unless a service has autoscaling on or runs serverless.",
    "Faults (outages, slowdowns, link latency) apply only inside their time windows; components "
    "do not fail on their own otherwise (third-party APIs may have an error rate).",
    "Summary statistics exclude the warm-up period. Percentiles come from a log-bucketed "
    "histogram with about 2% resolution.",
    "The same document, scenario, seed and engine version reproduce identical results on the "
    "same platform.",
]

REASON_TEXT = {
    "queue_full": "queue was full",
    "queue_timeout": "waited longer than the queue timeout",
    "max_connections": "connection limit reached",
    "dependency_timeout": "timed out waiting for a dependency",
    "client_timeout": "client timeout",
    "miss_unrouted": "miss had nowhere to go",
    "outage": "component was down (injected outage)",
    "throttled": "throttled (concurrency or provisioned throughput exceeded)",
    "rate_limited": "rate limited",
    "upstream_error": "third-party API returned an error",
    "out_of_memory": "ran out of memory (request refused)",
    "gateway_timeout": "the gateway gave up waiting for its target (504)",
    "circuit_open": "circuit breaker open: too many requests in flight to the target (503)",
    "no_ready_pods": "no ready pod to serve it: its pods were Pending or starting (503)",
}

TRACE_KEEP = {"slowest": 8, "failed": 8, "sample": 18}


def _select_traces(traces: list[dict[str, Any]], labels: dict[str, str]) -> list[dict[str, Any]]:
    """A readable sample of traced requests: evenly spread ones, the slowest, the median and
    failures, each with its spans relative to the request's start."""
    measured = [t for t in traces if t["measured"]] or traces
    ok = sorted((t for t in measured if t["status"] == "ok"), key=lambda t: t["end"] - t["start"])
    failed = [t for t in measured if t["status"] != "ok"]
    picked: dict[int, set[str]] = {}

    def take(items: list[dict[str, Any]], n: int, tag: str) -> None:
        if not items or n <= 0:
            return
        step = max(1, len(items) / n)
        for i in range(min(n, len(items))):
            t = items[int(i * step)]
            picked.setdefault(t["id"], set()).add(tag)

    for t in ok[-TRACE_KEEP["slowest"] :]:
        picked.setdefault(t["id"], set()).add("slowest")
    if ok:
        picked.setdefault(ok[len(ok) // 2]["id"], set()).add("median")
    take(sorted(failed, key=lambda t: t["start"]), TRACE_KEEP["failed"], "failed")
    take(sorted(ok, key=lambda t: t["start"]), TRACE_KEEP["sample"], "sample")
    by_id = {t["id"]: t for t in measured}
    out = []
    for tid in sorted(picked, key=lambda i: by_id[i]["start"]):
        t = by_id[tid]
        t0 = t["start"]
        spans = [
            {
                "nodeId": n,
                "label": labels.get(n or "", n),
                "edgeId": e,
                "kind": kind,
                "startMs": round(a - t0, 3),
                "endMs": round(b - t0, 3),
                "depth": d,
                "detail": detail,
            }
            for (n, e, kind, a, b, d, detail) in sorted(t["spans"], key=lambda x: (x[3], x[5]))
        ]
        out.append(
            {
                "id": tid,
                "op": t["op"],
                "client": t["client"],
                "startS": round(t0 / 1000.0, 3),
                "durationMs": round(t["end"] - t0, 3),
                "status": t["status"],
                "reason": t["reason"],
                "failedAt": t["failedAt"],
                "attempts": t["attempts"],
                "tags": sorted(picked[tid]),
                "spans": spans,
            }
        )
    return out


def _latency_breakdown(
    traces: list[dict[str, Any]], labels: dict[str, str]
) -> list[dict[str, Any]]:
    """Mean time a successful request spends in each component (excluding its downstream
    calls), on the network and backing off, from the traced requests."""
    ok = [t for t in traces if t["status"] == "ok" and t["measured"]]
    if not ok:
        return []
    totals: dict[str, float] = {}
    for t in ok:
        end = t["end"]
        spans = [sp for sp in t["spans"] if sp[4] <= end + 1e-9]
        for n, _e, kind, a, b, d, _detail in spans:
            dur = b - a
            if kind == "network":
                totals["__network"] = totals.get("__network", 0.0) + dur
            elif kind == "backoff":
                totals["__backoff"] = totals.get("__backoff", 0.0) + dur
            elif kind == "handle" and n is not None:
                inner = sum(
                    (b2 - a2)
                    for n2, _e2, k2, a2, b2, d2, _x in spans
                    if a2 >= a - 1e-9
                    and b2 <= b + 1e-9
                    and ((k2 == "network" and d2 == d) or (k2 == "handle" and d2 == d + 1))
                )
                totals[n] = totals.get(n, 0.0) + max(0.0, dur - inner)
    count = len(ok)
    grand = sum(totals.values()) or 1.0
    rows = []
    for key, total in sorted(totals.items(), key=lambda kv: -kv[1]):
        label = {"__network": "Network", "__backoff": "Retry backoff"}.get(key) or labels.get(
            key, key
        )
        rows.append(
            {
                "nodeId": None if key.startswith("__") else key,
                "label": label,
                "meanMs": round(total / count, 3),
                "share": round(total / grand, 4),
            }
        )
    return rows


def canonical_digest(obj: Any) -> str:
    data = json.dumps(obj, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(data.encode()).hexdigest()


def _describe_value(v: Any) -> str:
    if isinstance(v, Constant | Exponential | Uniform | LogNormal):
        return v.describe()
    if isinstance(v, float):
        return f"{v:g}"
    return str(v)


def _tier_notes(technologies: list[dict[str, Any]]) -> list[str]:
    """A model note naming the parts whose technology is experimental (none when all are core)."""
    names = [t["label"] for t in technologies if t["tier"] == "experimental"]
    if not names:
        return []
    shown = ", ".join(names[:5]) + (f" and {len(names) - 5} more" if len(names) > 5 else "")
    return [f"Experimental technologies ({shown}): {TIER_NOTE[:1].lower()}{TIER_NOTE[1:]}"]


def _user_assumptions(prep: PreparedRun, registry: ComponentRegistry) -> list[dict[str, Any]]:
    """Every performance input of what was simulated, with where it came from."""
    return input_provenance(prep.resolved, registry)


def _sum_series(series_list: list[list[float]], n: int) -> list[float]:
    out = [0.0] * n
    for s in series_list:
        for i in range(n):
            out[i] += s[i]
    return out


def _client_groups(
    sources: list[TrafficSourceRuntime],
    shares: list[float],
    labels: dict[str, str],
    resolved: ArchitectureDocument,
    window_s: float,
) -> list[dict[str, Any]]:
    """What each client group experienced in the measurement window."""
    types = {n.id: n.type for n in resolved.architecture.nodes}
    total_share = sum(shares) or 1.0
    out = []
    for src, share in zip(sources, shares, strict=True):
        g = src.group
        m = g.measured
        out.append(
            {
                "nodeId": src.id,
                "label": labels.get(src.id, src.id),
                "type": types.get(src.id),
                "share": _round(share / total_share),
                "arrived": m.arrived,
                "succeeded": m.succeeded,
                "failed": m.errors,
                "timedOut": m.timeouts,
                "offeredRps": _round(m.arrived / window_s if window_s else 0.0),
                "successRate": _round(safe_ratio(m.succeeded, m.arrived)),
                "retryAmplification": _round(safe_ratio(g.attempts.window, g.offered.window)),
                "latencyMs": g.latency.summary(),
            }
        )
    return out


def build_report(
    *,
    prep: PreparedRun,
    registry: ComponentRegistry,
    ctx: SimContext,
    sources: list[TrafficSourceRuntime],
    shares: list[float],
    tl: Timeline,
    t_final: float,
    drain_s: float,
    wall_seconds: float,
    events: int = 0,
) -> dict[str, Any]:
    resolved = prep.resolved
    wl = resolved.workload
    configs = prep.validation.configs
    n_used = max(1, min(tl.n, math.ceil(t_final / tl.bucket_ms)))
    bucket_s = tl.bucket_ms / 1000.0
    window_s = tl.window_ms / 1000.0
    drained = ctx.idle()

    def cut(series: list[Any]) -> list[Any]:
        return series[:n_used]

    # -- client / end-to-end ---------------------------------------------------------------------
    client = sources[0].client  # shared by every client group
    measured = client.cohorts["measured"]
    warm = client.cohorts["warmup"]
    total = {
        k: measured.to_dict()[k] + warm.to_dict()[k]
        for k in ("arrived", "succeeded", "failed", "timedOut", "outstanding")
    }
    client_invariant = (
        client.offered.total == total["arrived"]
        and client.succeeded.total == total["succeeded"]
        and client.errors.total == total["failed"]
        and client.timeouts.total == total["timedOut"]
        and sum(src.m.inflight.value for src in sources) == total["outstanding"]
        and total["outstanding"] >= 0
    )
    labels = {n.id: n.label or n.id for n in resolved.architecture.nodes}
    failures = []
    fail_total = measured.errors + measured.timeouts
    for (status, reason, node_id, edge_id), count in sorted(
        client.failures.items(), key=lambda kv: (-kv[1], str(kv[0]))
    ):
        failures.append(
            {
                "status": status,
                "reason": reason,
                "reasonText": REASON_TEXT.get(reason, reason),
                "nodeId": node_id,
                "nodeLabel": labels.get(node_id or "", node_id),
                "edgeId": edge_id,
                "count": count,
                "share": _round(count / fail_total) if fail_total else None,
            }
        )
    arrived = measured.arrived
    summary: dict[str, Any] = {
        "arrived": arrived,
        "succeeded": measured.succeeded,
        "failed": measured.errors,
        "timedOut": measured.timeouts,
        "outstanding": measured.outstanding,
        "offeredRps": _round(arrived / window_s if window_s else 0.0),
        "throughputRps": _round(client.succeeded.window_rate()),
        "errorRps": _round(client.errors.window_rate()),
        "timeoutRps": _round(client.timeouts.window_rate()),
        "successRate": _round(safe_ratio(measured.succeeded, arrived)),
        "errorRate": _round(safe_ratio(measured.errors, arrived)),
        "timeoutRate": _round(safe_ratio(measured.timeouts, arrived)),
        "attempts": client.attempts.window,
        "retryAmplification": _round(safe_ratio(client.attempts.window, client.offered.window)),
        "latencyMs": client.latency.summary(),
        "latencyWithFailuresMs": client.latency_with_failures.summary(),
        "latencyByOp": {op: h.summary() for op, h in client.latency_by_op.items()},
        "failureBreakdown": failures,
        "clients": _client_groups(sources, shares, labels, resolved, window_s),
        "accounting": {
            "warmup": warm.to_dict(),
            "measured": measured.to_dict(),
            "total": total,
            "invariantHolds": client_invariant,
            "drained": drained,
        },
    }

    timeseries = {
        "bucketS": bucket_s,
        "t": [round(i * bucket_s, 3) for i in range(n_used)],
        "offeredRps": cut(round_series(client.offered.rate_series())),
        "throughputRps": cut(round_series(client.succeeded.rate_series())),
        "errorRps": cut(round_series(client.errors.rate_series())),
        "timeoutRps": cut(round_series(client.timeouts.rate_series())),
        "attemptRps": cut(round_series(client.attempts.rate_series())),
        "latencyP50Ms": cut(client.latency_series.percentile_series(50)),
        "latencyP95Ms": cut(client.latency_series.percentile_series(95)),
        "latencyP99Ms": cut(client.latency_series.percentile_series(99)),
    }

    # -- per node ---------------------------------------------------------------------------------
    nodes_out: list[dict[str, Any]] = []
    for n in resolved.architecture.nodes:
        rt = ctx.components[n.id]
        m = rt.m
        model = registry.get(n.type)
        slots: float | int | None = m.slots
        occ = rt.occupancy
        pool_based = occ is m.busy
        cap = rt.capacity_gauge()
        if cap is not None:
            # time-varying capacity (autoscaling): utilization = busy slot-time / available slot-time
            slots = _round(cap.window_mean(), 3)
            util = occ.window_area / cap.window_area if cap.window_area else None
            cpu_util = (
                m.cpu.window_area / cap.window_area
                if (cap.window_area and m.has_cpu_split)
                else None
            )
        else:
            util = occ.window_mean() / slots if slots else None
            cpu_util = m.cpu.window_mean() / slots if (slots and m.has_cpu_split) else None
        arrivals_w = m.arrivals.window
        rejected = {r: c.window for r, c in sorted(m.rejected.items())}
        rejected_w = sum(rejected.values())
        hold = m.hold.window_mean()
        svc = m.service.window_mean()
        # Capacity: the lowest of the slot ceiling (workers/connections over the observed holding
        # time) and any ceiling set by resource specs (CPU cores, memory).
        limits = rt.resource_capacity(hold, svc)
        ceilings: dict[str, float] = dict(limits)
        if pool_based and slots and hold:
            ceilings["slots"] = slots * 1000.0 / hold
        cap_by = min(ceilings, key=lambda k: ceilings[k]) if ceilings else None
        if cap_by == "slots":
            # At saturation the slot ceiling reflects time spent waiting for a core (or memory);
            # a resource ceiling within 3% of it is the root cause, so name that instead.
            near = [k for k in limits if limits[k] <= ceilings["slots"] * 1.03]
            if near:
                cap_by = min(near, key=lambda k: limits[k])
        only: list[float] = [v for k, v in limits.items() if k == "cpu"]
        if pool_based and slots and svc:
            only.append(slots * 1000.0 / svc)
        counters = {name: c.window for name, c in sorted(m.extra.items())}
        hits, misses = counters.get("hits"), counters.get("misses")
        hit_ratio = (
            safe_ratio(hits or 0, (hits or 0) + (misses or 0))
            if hits is not None or misses is not None
            else None
        )
        node_summary: dict[str, Any] = {
            "arrivals": arrivals_w,
            "arrivalRps": _round(m.arrivals.window_rate()),
            "completed": m.completed.window,
            "failedDownstream": m.failed_downstream.window,
            "rejected": rejected,
            "rejectedTotal": rejected_w,
            "rejectionRate": _round(safe_ratio(rejected_w, arrivals_w)),
            "slots": slots,
            "utilization": _round(util),
            "cpuUtilization": _round(cpu_util),
            "meanInSystem": _round(m.inflight.window_mean()),
            "meanQueue": _round(m.queue.window_mean()),
            "peakQueue": m.queue.peak_window,
            "meanQueueWaitMs": _round(m.queue_wait.window_mean()),
            "p95QueueWaitMs": _round(m.queue_wait_hist.percentile(95)),
            "p99QueueWaitMs": _round(m.queue_wait_hist.percentile(99)),
            "meanHoldMs": _round(hold),
            "meanServiceMs": _round(svc),
            "capacityRps": _round(ceilings[cap_by]) if cap_by else None,
            "capacityLimit": cap_by,
            "cpuCapacityRps": _round(limits["cpu"]) if "cpu" in limits else None,
            "serviceOnlyCapacityRps": _round(min(only)) if only else None,
            "hitRatio": _round(hit_ratio),
            "counters": counters,
        }
        accounting = {
            "arrivals": m.arrivals.total,
            "completed": m.completed.total,
            "failedDownstream": m.failed_downstream.total,
            "rejected": m.rejected_total(),
            "inProgress": m.inflight.value,
        }
        accounting["invariantHolds"] = accounting["arrivals"] == (
            accounting["completed"]
            + accounting["failedDownstream"]
            + accounting["rejected"]
            + accounting["inProgress"]
        )
        ts: dict[str, Any] = {
            "arrivalRps": cut(round_series(m.arrivals.rate_series())),
            "completedRps": cut(round_series(m.completed.rate_series())),
            "rejectedRps": cut(
                round_series(_sum_series([c.rate_series() for c in m.rejected.values()], tl.n))
            ),
            "failedRps": cut(round_series(m.failed_downstream.rate_series())),
            "queueMean": cut(round_series(m.queue.mean_series())),
            "queueMax": cut(m.queue.max_series()),
            "queueWaitMs": cut(m.queue_wait.series()),
        }
        if cap is not None:
            ts["utilization"] = cut(
                round_series(
                    [a / c if c else None for a, c in zip(occ.area, cap.area, strict=True)]
                )
            )
            if m.has_cpu_split:
                ts["cpuUtilization"] = cut(
                    round_series(
                        [a / c if c else None for a, c in zip(m.cpu.area, cap.area, strict=True)]
                    )
                )
        elif slots:
            ts["utilization"] = cut(round_series([a / (slots * tl.bucket_ms) for a in occ.area]))
            if m.has_cpu_split:
                ts["cpuUtilization"] = cut(
                    round_series([a / (slots * tl.bucket_ms) for a in m.cpu.area])
                )
        for name, series in rt.extra_timeseries().items():
            ts[name] = cut(round_series(series))
        node_summary["latencyMs"] = m.residence.window().summary()
        ts["inflightMean"] = cut(round_series(m.inflight.mean_series()))
        ts["latencyP50Ms"] = cut(m.residence.percentile_series(50))
        ts["latencyP95Ms"] = cut(m.residence.percentile_series(95))
        node_summary.update(rt.extra_summary())
        if hits is not None or misses is not None:
            h_b = m.extra["hits"].buckets if "hits" in m.extra else [0] * tl.n
            m_b = m.extra["misses"].buckets if "misses" in m.extra else [0] * tl.n
            ts["hitRatio"] = cut(
                [_round(h / (h + mm)) if (h + mm) else None for h, mm in zip(h_b, m_b, strict=True)]
            )
        i0 = math.ceil(tl.window_start / tl.bucket_ms)
        i1 = max(i0 + 1, math.floor(tl.window_end / tl.bucket_ms))
        util_ts = ts.get("utilization")
        window_utils = [u for u in (util_ts or [])[i0:i1] if u is not None]
        node_summary["peakUtilization"] = _round(max(window_utils)) if window_utils else None
        node_entry: dict[str, Any] = {
            "id": n.id,
            "type": n.type,
            "label": n.label or n.id,
            "category": model.category,
            "summary": node_summary,
            "accounting": accounting,
            "timeseries": ts,
            "config": configs[n.id].model_dump(mode="json", by_alias=True),
            "behaviour": rt.behaviour(),
        }
        node_entry["explain"] = (
            generic(node_entry, window_s, pool_based) + rt.explain(node_summary)
            if not model.is_traffic_source
            else []
        )
        nodes_out.append(node_entry)

    # -- per edge ---------------------------------------------------------------------------------
    flows = prep.validation.edge_flows
    edges_out = []
    for e in resolved.architecture.edges:
        er = ctx.edges[e.id]
        edges_out.append(
            {
                "id": e.id,
                "source": e.source,
                "sourcePort": e.source_port,
                "target": e.target,
                "requestTypes": list(e.config.request_types),
                "flowTypes": sorted(flows.get(e.id, set())),
                "attempts": er.attempts.window,
                "attemptsTotal": er.attempts.total,
                "callRps": _round(er.attempts.window_rate()),
                "retries": er.retries.window,
                "timeouts": er.timeouts.window,
                "failures": er.failures.window,
                "timeseries": {
                    "callRps": cut(round_series(er.attempts.rate_series())),
                    "timeoutRps": cut(round_series(er.timeouts.rate_series())),
                    "failureRps": cut(round_series(er.failures.rate_series())),
                },
            }
        )

    report: dict[str, Any] = {
        "reportVersion": REPORT_VERSION,
        "window": {
            "warmupS": wl.warmup_s,
            "durationS": wl.duration_s,
            "measuredS": window_s,
            "drainS": drain_s,
            "simulatedS": round(t_final / 1000.0, 3),
        },
        "summary": summary,
        "timeseries": timeseries,
        "nodes": nodes_out,
        "edges": edges_out,
    }
    report["findings"] = analyze(report, resolved)
    report["traces"] = _select_traces(ctx.traces, labels)
    breakdown = _latency_breakdown(ctx.traces, labels)
    sys_explain: list[dict[str, Any]] = []
    if arrived:
        sys_explain.append(
            formula(
                "throughput",
                "Throughput",
                "throughput = successful requests ÷ measurement window",
                f"{measured.succeeded:,} ÷ {num(window_s)} s",
                summary["throughputRps"],
                "req/s",
                metric="throughputRps",
            )
        )
        sys_explain.append(
            formula(
                "success_rate",
                "Success rate",
                "success rate = succeeded ÷ arrived",
                f"{measured.succeeded:,} ÷ {arrived:,}",
                summary["successRate"],
                "",
                note=f"Failed {measured.errors:,}, timed out {measured.timeouts:,}.",
            )
        )
        if client.offered.window:
            sys_explain.append(
                formula(
                    "retry_amplification",
                    "Retry amplification",
                    "attempts ÷ requests",
                    f"{client.attempts.window:,} ÷ {client.offered.window:,}",
                    summary["retryAmplification"],
                    "x",
                    note="Above 1, retries add load exactly when the system is struggling.",
                    metric="attemptRps",
                )
            )
    if breakdown:
        top = breakdown[0]
        sys_explain.append(
            formula(
                "latency_breakdown",
                "Where a request's time goes",
                "time in component = its handling time − time spent in its downstream calls",
                f"mean over {sum(1 for t in ctx.traces if t['status'] == 'ok' and t['measured']):,} traced requests",
                sum(r["meanMs"] for r in breakdown),
                "ms",
                note=f"Largest share: {top['label']} with {pct(top['share'])} ({num(top['meanMs'], 2)} ms).",
                metric="latencyP50Ms",
            )
        )
    report["explain"] = {"system": sys_explain, "latencyBreakdown": breakdown}
    input_snapshot = simulation_input(resolved)
    technologies = registry.technology_tiers(input_snapshot["architecture"]["nodes"])
    user_inputs = _user_assumptions(prep, registry)
    report["assumptions"] = {
        "modelNotes": MODEL_NOTES + _tier_notes(technologies),
        "technologies": technologies,
        "userInputs": user_inputs,
        "provenanceCounts": provenance_counts(user_inputs),
        "calibration": (
            resolved.calibration.model_dump(mode="json", by_alias=True)
            if resolved.calibration
            else None
        ),
        "components": {
            t: {
                "modeled": list(registry.get(t).modeled),
                "notModeled": list(registry.get(t).not_modeled),
            }
            for t in sorted({n.type for n in resolved.architecture.nodes})
        },
        "validationWarnings": [i.to_dict() for i in prep.validation.warnings],
    }
    report["sizing"] = sizing_summary(prep.document, resolved, prep.sizes, registry)
    report["input"] = input_snapshot
    # priced and reviewed on what was simulated (auto sizes filled in, scenario applied)
    report["cost"] = estimate_cost(
        input_snapshot["architecture"]["nodes"],
        registry,
        report,
        input_snapshot.get("pricing"),
        {k: v.kind for k, v in resolved.provenance.items()},
    )
    report["review"] = review_design(input_snapshot, registry)
    report["estimate"] = estimate_design(resolved, registry, input_snapshot, configs)
    report["confidence"] = report_confidence(
        report,
        user_inputs,
        {e.id: e.config.latency_ms.expected() for e in resolved.architecture.edges},
        registry,
    )
    report["provenance"] = {
        "engineVersion": ENGINE_VERSION,
        "schemaVersion": SCHEMA_VERSION,
        "reportVersion": REPORT_VERSION,
        "seed": wl.seed,
        "scenarioId": prep.scenario_id,
        "scenarioName": prep.scenario_name,
        "overrides": prep.overrides,
        "inputDigest": canonical_digest(input_snapshot),
        "resultDigest": canonical_digest(
            {k: report[k] for k in ("summary", "timeseries", "nodes", "edges", "window")}
        ),
        "requestsGenerated": client.offered.total,
        "eventsProcessed": events,
        "wallSeconds": round(wall_seconds, 3),
        "python": platform.python_version(),
        "simpy": simpy.__version__,
    }
    return report
