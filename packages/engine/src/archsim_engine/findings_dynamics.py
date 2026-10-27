"""Findings about faults, autoscaling and async queues (see findings_ext).

Kinds: ``fault_impact`` (one per fault), ``autoscaling``, ``serverless``, ``queue_lag``.
"""

from __future__ import annotations

import math
from typing import Any

from archsim_engine.schema import ArchitectureDocument, Fault


def _fmt_ms(v: float | None) -> str:
    if v is None:
        return "n/a"
    if v >= 10_000:
        return f"{v / 1000:,.1f} s"
    return f"{v:,.0f} ms" if v >= 10 else f"{v:.1f} ms"


def _pct(v: float | None) -> str:
    if v is None:
        return "n/a"
    return f"{v:.1%}" if v < 0.995 or v >= 1 else f"{v:.2%}"


class _Series:
    """Client-side timeseries helpers over bucket index ranges."""

    def __init__(self, report: dict[str, Any]) -> None:
        ts = report["timeseries"]
        self.ts = ts
        self.b: float = ts["bucketS"]
        self.n: int = len(ts["t"])
        win = report["window"]
        self.i_warm = min(self.n, math.ceil(win["warmupS"] / self.b))
        self.i_dur = min(self.n, max(1, math.ceil(win["durationS"] / self.b)))

    def idx(self, t_s: float, up: bool = False) -> int:
        i = math.ceil(t_s / self.b - 1e-9) if up else math.floor(t_s / self.b + 1e-9)
        return max(0, min(self.n, i))

    def counts(self, i0: int, i1: int) -> tuple[float, float]:
        ok = bad = 0.0
        thr, err, tmo = self.ts["throughputRps"], self.ts["errorRps"], self.ts["timeoutRps"]
        for i in range(max(0, i0), min(self.n, i1)):
            ok += thr[i] or 0.0
            bad += (err[i] or 0.0) + (tmo[i] or 0.0)
        return ok, bad

    def share(self, i0: int, i1: int) -> float | None:
        ok, bad = self.counts(i0, i1)
        return ok / (ok + bad) if ok + bad > 0 else None

    def p95(self, i0: int, i1: int) -> float | None:
        vals = [v for v in self.ts["latencyP95Ms"][max(0, i0) : min(self.n, i1)] if v is not None]
        return sum(vals) / len(vals) if vals else None


def _fault_subject(f: Fault, resolved: ArchitectureDocument, labels: dict[str, str]) -> str:
    if f.kind == "latency":
        e = next((e for e in resolved.architecture.edges if e.id == f.target), None)
        link = (
            f"{labels.get(e.source, e.source)} → {labels.get(e.target, e.target)}"
            if e
            else f.target
        )
        return f"+{f.extra_ms:g} ms latency on {link}"
    name = labels.get(f.target, f.target)
    if f.kind == "slowdown":
        return f"{f.factor:g}x slowdown of {name}"
    return f"outage of {name}"


def _fault_suggestion(f: Fault, target_type: str | None) -> str:
    if f.kind == "slowdown":
        return (
            "Set timeouts below the client timeout and add a circuit breaker so slow calls release "
            "workers quickly; keep capacity headroom for degraded periods."
        )
    if f.kind == "latency":
        return (
            "Budget timeouts for the extra latency, retry on another instance, or cut the number "
            "of sequential calls across this link."
        )
    if target_type == "cache":
        return (
            "Treat cache errors as misses and fall back to the database: turn on 'Carry on if "
            "this call fails' on the link to the cache and set the link to the database to 'Reads "
            "only on a cache miss' (check the database can take every read), or run a replicated "
            "cache."
        )
    if target_type == "database":
        return (
            "Add a replica with automatic failover, and fail fast (timeouts, circuit breaker) so "
            "callers do not pile up while it is down."
        )
    if target_type in ("service", "load_balancer"):
        return (
            "Run redundant instances behind a load balancer and retry on another instance, so "
            "losing one component does not stop traffic."
        )
    return (
        "Add a fallback (or queue the work for later), with a timeout and a circuit breaker so "
        "the failure stays contained."
    )


def _outage_effect(
    report: dict[str, Any], f: Fault, labels: dict[str, str]
) -> tuple[str, dict[str, int]] | None:
    """What an outage did at its part: the calls it refused while down, and how many client
    requests failed there rather than carrying on without it (a fallback, or a retry that found
    another instance). Those refusals are the outage, not overload, so the part is not named a
    bottleneck for them (analysis.load_rejection_rate)."""
    node = next((n for n in report["nodes"] if n["id"] == f.target), None)
    if node is None:
        return None
    refused = int((node["summary"].get("rejected") or {}).get("outage", 0))
    failed = sum(
        int(b["count"])
        for b in report["summary"]["failureBreakdown"]
        if b["reason"] == "outage" and b["nodeId"] == f.target
    )
    name = labels.get(f.target, f.target)
    text = f"{name} refused {refused:,} calls while it was down"
    if not refused:
        text = f"{name} received no calls while it was down."
    elif failed:
        text += f"; {failed:,} client requests failed because of it."
    else:
        text += "; no client request failed there: its callers carried on without it."
    return text, {"refusedCalls": refused, "failedAtTarget": failed}


def _fault_findings(
    report: dict[str, Any], resolved: ArchitectureDocument, s: _Series
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    labels = {n.id: n.label or n.id for n in resolved.architecture.nodes}
    types = {n.id: n.type for n in resolved.architecture.nodes}
    duration = report["window"]["durationS"]
    for f in resolved.workload.faults:
        end = min(f.start_s + f.duration_s, duration)
        if f.start_s >= duration:
            continue
        i_s, i_e = s.idx(f.start_s), max(s.idx(f.start_s) + 1, s.idx(end, up=True))
        base_lo, base_hi = s.i_warm, i_s
        if base_hi - base_lo < 1:  # fault starts during warm-up: compare with after it instead
            base_lo, base_hi = i_e, s.i_dur
        base_share, base_p95 = s.share(base_lo, base_hi), s.p95(base_lo, base_hi)
        dur_share, dur_p95 = s.share(i_s, i_e), s.p95(i_s, i_e)
        if dur_share is None:
            continue
        subject = _fault_subject(f, resolved, labels)
        window = f"{f.start_s:g}-{end:g} s"
        drop = (base_share - dur_share) if base_share is not None else 0.0
        ratio = dur_p95 / base_p95 if (dur_p95 and base_p95) else None

        # recovery: first bucket after the window that looks like the baseline again
        recovery_s: float | None = None
        recovered = True
        if base_share is not None and (drop > 0.005 or (ratio or 1) > 1.25):
            recovered = False
            for j in range(i_e, s.i_dur):
                sh = s.share(j, j + 1)
                p = s.p95(j, j + 1)
                if sh is None:
                    continue
                if sh >= base_share - 0.01 and (
                    base_p95 is None or p is None or p <= base_p95 * 1.25
                ):
                    recovery_s = max(0.0, s.ts["t"][j] - end)
                    recovered = True
                    break

        if dur_share < 0.95 or (ratio or 0) > 3:
            severity = "critical"
        elif dur_share < 0.99 or drop > 0.005 or (ratio or 0) > 1.5:
            severity = "warning"
        else:
            severity = "ok"

        if base_share is not None and drop > 0.005:
            title = f"During the {subject} ({window}) success fell from {_pct(base_share)} to {_pct(dur_share)}"
        elif ratio is not None and ratio > 1.2:
            title = f"During the {subject} ({window}) p95 latency rose from {_fmt_ms(base_p95)} to {_fmt_ms(dur_p95)}"
        else:
            title = f"The system absorbed the {subject} ({window})"
        parts = [
            f"Inside the fault window {_pct(dur_share)} of requests succeeded (before: {_pct(base_share)}); "
            f"p95 latency averaged {_fmt_ms(dur_p95)} (before: {_fmt_ms(base_p95)})."
        ]
        if not recovered:
            parts.append("It had not recovered by the time arrivals stopped.")
        elif recovery_s is not None:
            parts.append(
                "It recovered as soon as the fault ended."
                if recovery_s < s.b
                else f"It took about {recovery_s:g} s after the fault ended to recover."
            )
        outage = _outage_effect(report, f, labels) if f.kind == "outage" else None
        if outage is not None:
            parts.append(outage[0])
        finding: dict[str, Any] = {
            "severity": severity,
            "kind": "fault_impact",
            "nodeId": f.target if f.kind != "latency" else None,
            "title": title,
            "detail": " ".join(parts),
            "evidence": {
                "faultId": f.id,
                "faultKind": f.kind,
                "target": f.target,
                "startS": f.start_s,
                "endS": end,
                "successBefore": base_share,
                "successDuring": dur_share,
                "p95BeforeMs": base_p95,
                "p95DuringMs": dur_p95,
                "recoveryS": recovery_s,
                "recovered": recovered,
                **(outage[1] if outage is not None else {}),
            },
        }
        if severity != "ok":
            finding["suggestion"] = _fault_suggestion(f, types.get(f.target))
        out.append(finding)
    return out


def _first_index(values: list[Any], pred: Any) -> int | None:
    for i, v in enumerate(values):
        if v is not None and pred(v):
            return i
    return None


def _service_findings(report: dict[str, Any], s: _Series) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for n in report["nodes"]:
        if n["type"] != "service":
            continue
        cfg = n.get("config") or {}
        summ = n["summary"]
        counters = summ.get("counters", {})
        reps = [v for v in n["timeseries"].get("replicas", []) if v is not None]
        label = n["label"]
        if cfg.get("platform") == "serverless":
            cold = counters.get("coldStarts", 0)
            cold_total = summ.get("coldStartsTotal", cold)
            throttled = summ["rejected"].get("throttled", 0)
            peak = summ.get("replicasPeak")
            limit = cfg.get("concurrencyLimit")
            share = cold / summ["arrivals"] if summ["arrivals"] else 0.0
            detail = (
                f"{cold_total:,} cold starts in the whole run ({cold:,} in the measurement window, "
                f"{share:.1%} of its requests); up to {peak} instances ran (limit {limit})."
            )
            if throttled:
                detail += f" {throttled:,} requests were throttled at the concurrency limit."
            f: dict[str, Any] = {
                "severity": "warning" if share > 0.05 else "info",
                "kind": "serverless",
                "nodeId": n["id"],
                "title": f"{label}: {cold_total:,} cold starts, up to {peak} instances",
                "detail": detail,
                "evidence": {
                    "coldStarts": cold,
                    "coldStartsTotal": cold_total,
                    "throttled": throttled,
                    "instancesPeak": peak,
                    "coldStartShare": share,
                },
            }
            if share > 0.05:
                f["suggestion"] = (
                    "Keep instances warm (provisioned concurrency) or cut cold-start time (smaller bundle, lighter runtime)."
                )
            out.append(f)
            continue
        if not cfg.get("autoscaling") or not reps:
            continue
        start, peak, end = reps[0], max(reps), reps[-1]
        rejected_ts = n["timeseries"].get("rejectedRps", [])
        max_r = cfg.get("maxReplicas")
        util = summ.get("utilization") or 0.0
        if peak > start + 0.01:
            i_up = _first_index(n["timeseries"]["replicas"], lambda v, lo=start: v > lo + 0.01) or 0
            i_peak = (
                _first_index(n["timeseries"]["replicas"], lambda v, hi=peak: v >= hi - 0.01) or i_up
            )
            rej = round(sum((v or 0.0) for v in rejected_ts[: i_peak + 1]) * s.b)
            t_up = s.ts["t"][i_up] if i_up < s.n else None
            t_peak = s.ts["t"][i_peak] if i_peak < s.n else None
            detail = (
                f"The first new replica was taking traffic by about {t_up:g} s and capacity reached "
                f"{peak:.0f} replicas by about {t_peak:g} s."
            )
            if rej:
                detail += f" {rej:,} requests were rejected while it scaled."
            if end < peak - 0.01:
                detail += f" It scaled back to {end:.0f} replicas when load dropped."
            f = {
                "severity": "warning" if rej else "info",
                "kind": "autoscaling",
                "nodeId": n["id"],
                "title": f"{label} autoscaled from {start:.0f} to {peak:.0f} replicas",
                "detail": detail,
                "evidence": {
                    "replicasStart": start,
                    "replicasPeak": peak,
                    "replicasEnd": end,
                    "firstScaleUpS": t_up,
                    "peakReachedS": t_peak,
                    "rejectedWhileScaling": rej,
                },
            }
            if rej:
                f["suggestion"] = (
                    "Scale earlier (lower target utilization or a shorter evaluation interval), keep "
                    "more minimum replicas, or reduce start-up time."
                )
            out.append(f)
        if max_r and peak >= max_r - 0.01 and util >= 0.85:
            out.append(
                {
                    "severity": "warning",
                    "kind": "autoscaling",
                    "nodeId": n["id"],
                    "title": f"{label} hit its autoscaling maximum ({max_r} replicas)",
                    "detail": f"It ran at {util:.0%} utilization with no room left to scale.",
                    "suggestion": "Raise max replicas (and check the database and other dependencies can take the extra load).",
                    "evidence": {"maxReplicas": max_r, "utilization": util},
                }
            )
    return out


def _queue_findings(report: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for n in report["nodes"]:
        if n["type"] != "message_queue":
            continue
        summ = n["summary"]
        counters = summ.get("counters", {})
        lag = [v for v in n["timeseries"].get("queueMax", []) if v is not None]
        peak = int(max(lag)) if lag else 0
        processed = counters.get("processed", 0)
        dead = counters.get("deadLettered", 0)
        backlog = summ.get("backlogAtEnd", 0) or 0
        wait95 = summ.get("p95QueueWaitMs")
        age95 = summ.get("p95MessageAgeMs")
        label = n["label"]
        title = (
            f"{label} lag peaked at {peak:,} messages"
            if peak > 0
            else f"{label} kept up with no backlog"
        )
        parts = [
            f"Messages waited {_fmt_ms(wait95)} (p95) before a consumer picked them up; "
            f"publish-to-processed age p95 {_fmt_ms(age95)}.",
            f"{processed:,} processed, {dead:,} dead-lettered.",
        ]
        if backlog:
            parts.append(f"{backlog:,} messages were still queued when the run ended.")
        severity = "warning" if (backlog or dead or (wait95 or 0) > 10_000) else "info"
        f: dict[str, Any] = {
            "severity": severity,
            "kind": "queue_lag",
            "nodeId": n["id"],
            "title": title,
            "detail": " ".join(parts),
            "evidence": {
                "lagPeak": peak,
                "p95WaitMs": wait95,
                "p95AgeMs": age95,
                "processed": processed,
                "deadLettered": dead,
                "backlogAtEnd": backlog,
            },
        }
        if severity == "warning":
            f["suggestion"] = (
                "Add consumers (and partitions), make consumers faster, or fix the failing "
                "downstream; lag is acceptable only while it drains within your freshness target."
            )
        out.append(f)
    return out


def findings(report: dict[str, Any], resolved: ArchitectureDocument) -> list[dict[str, Any]]:
    if not report["timeseries"]["t"]:
        return []
    s = _Series(report)
    return [
        *_fault_findings(report, resolved, s),
        *_service_findings(report, s),
        *_queue_findings(report),
    ]
