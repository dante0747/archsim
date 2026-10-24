"""Side-by-side comparison of two simulation reports with explainable differences.

The comparison diffs the two *input snapshots* (what was actually simulated), so explanations
refer to real parameter changes rather than to what a user believes they changed.
"""

from __future__ import annotations

from typing import Any

KPIS: list[tuple[str, str, str, str]] = [
    ("offeredRps", "Offered load", "req/s", "neutral"),
    ("throughputRps", "Throughput (successful)", "req/s", "higher"),
    ("successRate", "Success rate", "ratio", "higher"),
    ("errorRate", "Error rate", "ratio", "lower"),
    ("timeoutRate", "Timeout rate", "ratio", "lower"),
    ("latencyMs.p50", "Latency p50", "ms", "lower"),
    ("latencyMs.p95", "Latency p95", "ms", "lower"),
    ("latencyMs.p99", "Latency p99", "ms", "lower"),
    ("latencyMs.mean", "Latency mean", "ms", "lower"),
    ("retryAmplification", "Attempts per request", "x", "lower"),
]

NODE_FIELDS = (
    "utilization",
    "cpuUtilization",
    "arrivalRps",
    "meanQueue",
    "peakQueue",
    "p95QueueWaitMs",
    "rejectedTotal",
    "hitRatio",
)

NOISE = 0.03  # relative change treated as "about the same"


def _get(d: dict[str, Any], dotted: str) -> Any:
    cur: Any = d
    for p in dotted.split("."):
        if not isinstance(cur, dict):
            return None
        cur = cur.get(p)
    return cur


def _flatten(prefix: str, value: Any, out: dict[str, Any]) -> None:
    if isinstance(value, dict):
        for k, v in value.items():
            _flatten(f"{prefix}.{k}" if prefix else k, v, out)
    elif isinstance(value, list) and value and isinstance(value[0], dict):
        for i, v in enumerate(value):
            _flatten(f"{prefix}[{i}]", v, out)
    else:
        out[prefix] = value


def flatten_input(inp: dict[str, Any]) -> tuple[dict[str, Any], dict[str, str]]:
    """Flatten an input snapshot to ``path -> value`` plus ``path -> human label``."""
    flat: dict[str, Any] = {}
    labels: dict[str, str] = {}
    arch = inp.get("architecture", {})
    for n in arch.get("nodes", []):
        base = f"node[{n['id']}]"
        name = n.get("label") or n["id"]
        flat[f"{base}.type"] = n.get("type")
        labels[f"{base}.type"] = f"{name}: type"
        sub: dict[str, Any] = {}
        _flatten("", n.get("config", {}), sub)
        for k, v in sub.items():
            flat[f"{base}.{k}"] = v
            labels[f"{base}.{k}"] = f"{name}: {k}"
    for e in arch.get("edges", []):
        base = f"edge[{e['id']}]"
        name = f"link {e['source']} → {e['target']}"
        for k in ("source", "target", "sourcePort", "targetPort"):
            flat[f"{base}.{k}"] = e.get(k)
            labels[f"{base}.{k}"] = f"{name}: {k}"
        sub = {}
        _flatten("", e.get("config", {}), sub)
        for k, v in sub.items():
            flat[f"{base}.{k}"] = v
            labels[f"{base}.{k}"] = f"{name}: {k}"
    sub = {}
    _flatten("", inp.get("workload", {}), sub)
    for k, v in sub.items():
        flat[f"workload.{k}"] = v
        labels[f"workload.{k}"] = f"Workload: {k}"
    return flat, labels


def input_diff(a: dict[str, Any], b: dict[str, Any]) -> list[dict[str, Any]]:
    fa, la = flatten_input(a)
    fb, lb = flatten_input(b)
    diffs = []
    for path in sorted(set(fa) | set(fb)):
        va, vb = fa.get(path), fb.get(path)
        if va != vb:
            diffs.append(
                {"path": path, "label": la.get(path) or lb.get(path) or path, "a": va, "b": vb}
            )
    return diffs


def _verdict(va: Any, vb: Any, better: str) -> tuple[float | None, float | None, str]:
    if va is None or vb is None:
        return None, None, "n/a"
    delta = vb - va
    rel = delta / abs(va) if va else (None if delta == 0 else float("inf"))
    small = (rel is not None and abs(rel) < NOISE) or abs(delta) < 1e-9
    if small:
        return delta, rel if rel != float("inf") else None, "same"
    if better == "neutral":
        return delta, rel if rel != float("inf") else None, "changed"
    improved = delta > 0 if better == "higher" else delta < 0
    return delta, rel if rel != float("inf") else None, "better" if improved else "worse"


def _primary_bottleneck(report: dict[str, Any]) -> dict[str, Any] | None:
    for f in report.get("findings", []):
        if f.get("kind") == "bottleneck":
            return f
    return None


def _fmt(v: Any, unit: str) -> str:
    if v is None:
        return "n/a"
    if unit == "ratio":
        return f"{v:.1%}"
    if unit == "ms":
        return f"{v:,.1f} ms"
    if unit == "req/s":
        return f"{v:,.0f} req/s"
    return f"{v:,.2f}{unit}"


def compare_reports(
    a: dict[str, Any], b: dict[str, Any], label_a: str = "A", label_b: str = "B"
) -> dict[str, Any]:
    sa, sb = a["summary"], b["summary"]
    kpis = []
    for key, label, unit, better in KPIS:
        va, vb = _get(sa, key), _get(sb, key)
        delta, rel, verdict = _verdict(va, vb, better)
        kpis.append(
            {
                "key": key,
                "label": label,
                "unit": unit,
                "better": better,
                "a": va,
                "b": vb,
                "delta": delta,
                "relativeDelta": rel,
                "verdict": verdict,
            }
        )

    nodes_a = {n["id"]: n for n in a["nodes"]}
    nodes_b = {n["id"]: n for n in b["nodes"]}
    node_rows = []
    for nid in list(nodes_a) + [i for i in nodes_b if i not in nodes_a]:
        na, nb = nodes_a.get(nid), nodes_b.get(nid)
        node_rows.append(
            {
                "id": nid,
                "label": (na or nb or {}).get("label", nid),
                "type": (na or nb or {}).get("type"),
                "statusA": na.get("status") if na else None,
                "statusB": nb.get("status") if nb else None,
                "a": {f: na["summary"].get(f) for f in NODE_FIELDS} if na else None,
                "b": {f: nb["summary"].get(f) for f in NODE_FIELDS} if nb else None,
            }
        )

    diffs = input_diff(a.get("input", {}), b.get("input", {}))
    pa, pb = a.get("provenance", {}), b.get("provenance", {})
    warnings = []
    if pa.get("seed") != pb.get("seed"):
        warnings.append(
            "The runs use different random seeds, so small differences may be random variation."
        )
    if pa.get("engineVersion") != pb.get("engineVersion"):
        warnings.append(
            f"The runs used different engine versions ({pa.get('engineVersion')} vs {pb.get('engineVersion')})."
        )
    if (
        not diffs
        and pa.get("resultDigest") != pb.get("resultDigest")
        and pa.get("seed") == pb.get("seed")
    ):
        warnings.append(
            "Inputs are identical but results differ; this indicates different engine builds or platforms."
        )

    explanations = _explain(a, b, kpis, node_rows, diffs, label_a, label_b)
    return {
        "labels": {"a": label_a, "b": label_b},
        "provenance": {"a": pa, "b": pb},
        "inputDiff": diffs,
        "kpis": kpis,
        "nodes": node_rows,
        "bottleneck": {
            "a": _primary_bottleneck(a),
            "b": _primary_bottleneck(b),
        },
        "explanations": explanations,
        "warnings": warnings,
        "timeseries": {"a": a.get("timeseries"), "b": b.get("timeseries")},
    }


def _explain(
    a: dict[str, Any],
    b: dict[str, Any],
    kpis: list[dict[str, Any]],
    nodes: list[dict[str, Any]],
    diffs: list[dict[str, Any]],
    la: str,
    lb: str,
) -> list[str]:
    out: list[str] = []
    if diffs:
        shown = [f"{d['label']} {d['a']} → {d['b']}" for d in diffs[:6]]
        more = f" (+{len(diffs) - 6} more)" if len(diffs) > 6 else ""
        out.append(f"{lb} differs from {la} in: " + "; ".join(shown) + more + ".")
    else:
        out.append(f"{la} and {lb} simulate identical inputs.")

    by_key = {k["key"]: k for k in kpis}
    thr = by_key["throughputRps"]
    if thr["verdict"] in ("better", "worse") and thr["relativeDelta"] is not None:
        out.append(
            f"Successful throughput {'rose' if thr['delta'] > 0 else 'fell'} "
            f"{abs(thr['relativeDelta']):.0%} ({_fmt(thr['a'], 'req/s')} → {_fmt(thr['b'], 'req/s')})."
        )
    p95 = by_key["latencyMs.p95"]
    if p95["verdict"] in ("better", "worse") and p95["a"] is not None and p95["b"] is not None:
        out.append(
            f"p95 latency {'dropped' if p95['delta'] < 0 else 'increased'} from "
            f"{_fmt(p95['a'], 'ms')} to {_fmt(p95['b'], 'ms')}."
        )
    sr = by_key["successRate"]
    if sr["verdict"] in ("better", "worse"):
        out.append(
            f"Success rate changed from {_fmt(sr['a'], 'ratio')} to {_fmt(sr['b'], 'ratio')}."
        )

    ba, bb = _primary_bottleneck(a), _primary_bottleneck(b)
    name = {n["id"]: n["label"] for n in nodes}
    if ba and bb and ba["nodeId"] != bb["nodeId"]:
        out.append(
            f"The bottleneck moved from {name.get(ba['nodeId'], ba['nodeId'])} to "
            f"{name.get(bb['nodeId'], bb['nodeId'])}."
        )
    elif ba and not bb:
        out.append(f"{name.get(ba['nodeId'], ba['nodeId'])} is no longer saturated in {lb}.")
    elif bb and not ba:
        out.append(f"{name.get(bb['nodeId'], bb['nodeId'])} becomes saturated in {lb}.")
    elif ba and bb:
        out.append(f"{name.get(ba['nodeId'], ba['nodeId'])} is the bottleneck in both runs.")

    # biggest utilization shifts and data-store load changes
    shifts = []
    for n in nodes:
        if not n["a"] or not n["b"]:
            continue
        ua, ub = n["a"]["utilization"], n["b"]["utilization"]
        if ua is not None and ub is not None and abs(ub - ua) >= 0.1:
            shifts.append((abs(ub - ua), f"{n['label']} utilization {ua:.0%} → {ub:.0%}"))
        if n["type"] in ("database", "cache"):
            ra, rb = n["a"]["arrivalRps"] or 0.0, n["b"]["arrivalRps"] or 0.0
            if max(ra, rb) > 0 and abs(rb - ra) / max(ra, rb) >= 0.1:
                shifts.append(
                    (abs(rb - ra) / max(ra, rb), f"{n['label']} load {ra:,.0f} → {rb:,.0f} ops/s")
                )
        ha, hb = n["a"].get("hitRatio"), n["b"].get("hitRatio")
        if ha is not None and hb is not None and abs(hb - ha) >= 0.05:
            shifts.append((abs(hb - ha), f"{n['label']} hit ratio {ha:.0%} → {hb:.0%}"))
    shifts.sort(reverse=True)
    if shifts:
        out.append("Largest component changes: " + "; ".join(s for _, s in shifts[:4]) + ".")
    return out
