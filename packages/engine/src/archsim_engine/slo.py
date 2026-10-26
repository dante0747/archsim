"""Service-level objectives: is a run good enough? (Same checks as the web app's verdict.)

Each objective set on the document (p95, p99, availability, minimum throughput) is checked
against the run's summary: latency must be at most the target, availability and throughput at
least. ``pass`` is None when no objective is set or a metric has no data.
"""

from __future__ import annotations

from typing import Any

Json = dict[str, Any]

CHECKS = (
    ("p95", "Latency p95", "latencyP95Ms", lambda s: s["latencyMs"].get("p95"), "lower"),
    ("p99", "Latency p99", "latencyP99Ms", lambda s: s["latencyMs"].get("p99"), "lower"),
    ("availability", "Availability", "availability", lambda s: s.get("successRate"), "higher"),
    ("throughput", "Throughput", "minThroughputRps", lambda s: s.get("throughputRps"), "higher"),
)


def slo_checks(objectives: Json, summary: Json) -> list[Json]:
    out = []
    for key, label, field, actual_of, better in CHECKS:
        target = objectives.get(field)
        if target is None:
            continue
        actual = actual_of(summary)
        passed = (
            None
            if actual is None
            else (actual <= target if better == "lower" else actual >= target)
        )
        out.append(
            {
                "key": key,
                "label": label,
                "target": target,
                "actual": actual,
                "pass": passed,
                "better": better,
            }
        )
    return out


def evaluate(objectives: Json, summary: Json) -> Json:
    checks = slo_checks(objectives, summary)
    decided = [c for c in checks if c["pass"] is not None]
    met = sum(1 for c in decided if c["pass"])
    if not checks:
        verdict = None
    elif len(decided) < len(checks):
        verdict = False if met < len(decided) else None
    else:
        verdict = met == len(decided)
    return {"checks": checks, "pass": verdict, "met": met, "total": len(checks)}


def verdict_text(result: Json) -> str:
    if result["pass"] is None:
        return "No objectives set" if not result["total"] else "Not enough data to decide"
    if result["pass"]:
        return f"Meets all {result['total']} objectives"
    missed = [c for c in result["checks"] if c["pass"] is False]
    parts = [
        f"{c['label']} {_fmt(c['key'], c['actual'])} (target {'<=' if c['better'] == 'lower' else '>='} {_fmt(c['key'], c['target'])})"
        for c in missed
    ]
    return f"Misses {len(missed)} of {result['total']} objectives: " + "; ".join(parts)


def _fmt(key: str, v: float | None) -> str:
    if v is None:
        return "n/a"
    if key in ("p95", "p99"):
        return f"{v:,.1f} ms"
    if key == "availability":
        return f"{v * 100:.2f}%"
    return f"{v:,.0f} req/s"
