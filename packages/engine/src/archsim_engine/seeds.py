"""Ranges across seeds: one design simulated with seeds base to base + N - 1.

One run answers for one random sequence of arrivals and service times. Running the same input
with a few seeds shows how much of a verdict is the design and how much is luck: "Meets the
objectives in 4 of 5 runs · p95 98-131 ms (median 104) · seeds 42-46" (with en dashes). Runs that disagree on
the verdict are *borderline*, and their confidence is capped at medium.

``summarize_seeds`` takes each run's seed and summary (the report's ``summary``, or the API's
compact run summary with flat ``p50``/``p95``/``p99``); runs without a summary (queued, running,
failed) count towards ``runs`` but not ``finished``.
"""

from __future__ import annotations

import math
import statistics
from collections.abc import Mapping, Sequence
from typing import Any

from archsim_engine.slo import evaluate

Json = dict[str, Any]

DASH = "\u2013"  # en dash, as in "seeds 42-46"

CONFIDENCE_ORDER = ("low", "medium", "high")
BORDERLINE_CAP = "medium"
"""The highest confidence a borderline verdict may carry."""


def _metrics(summary: Mapping[str, Any]) -> Json:
    """The figures a range is made of, from a report summary or a compact run summary."""
    latency = summary.get("latencyMs")
    if isinstance(latency, Mapping):
        p50, p95, p99 = latency.get("p50"), latency.get("p95"), latency.get("p99")
    else:
        p50, p95, p99 = summary.get("p50"), summary.get("p95"), summary.get("p99")
    return {
        "p50": p50,
        "p95": p95,
        "p99": p99,
        "successRate": summary.get("successRate"),
        "throughputRps": summary.get("throughputRps"),
    }


def value_range(values: Sequence[float | None]) -> Json | None:
    """``{min, max, median}`` of the values that are set, or None when none is."""
    vals = [float(v) for v in values if v is not None]
    if not vals:
        return None
    return {"min": min(vals), "max": max(vals), "median": statistics.median(vals)}


def fmt_ms(v: float) -> str:
    """Like the web app's latency figures: one decimal under 10 ms, else whole milliseconds
    (halves round up, as in JavaScript)."""
    return f"{math.floor(v * 10 + 0.5) / 10:.1f}" if v < 10 else f"{math.floor(v + 0.5):.0f}"


def range_text(label: str, r: Json | None) -> str | None:
    """``p95 98-131 ms (median 104)`` (an en dash), or ``p95 98 ms`` when every run agrees."""
    if r is None:
        return None
    lo, hi, med = fmt_ms(r["min"]), fmt_ms(r["max"]), fmt_ms(r["median"])
    if lo == hi:
        return f"{label} {lo} ms"
    return f"{label} {lo}{DASH}{hi} ms (median {med})"


def seeds_text(seeds: Sequence[int]) -> str:
    if not seeds:
        return ""
    lo, hi = min(seeds), max(seeds)
    contiguous = sorted(seeds) == list(range(lo, hi + 1))
    if lo == hi:
        return f"seed {lo}"
    return f"seeds {lo}{DASH}{hi}" if contiguous else "seeds " + ", ".join(str(s) for s in seeds)


def cap_confidence(level: str, cap: str | None) -> str:
    """``level`` lowered to ``cap`` when it is above it."""
    if cap is None or level not in CONFIDENCE_ORDER or cap not in CONFIDENCE_ORDER:
        return level
    return CONFIDENCE_ORDER[min(CONFIDENCE_ORDER.index(level), CONFIDENCE_ORDER.index(cap))]


def summarize_seeds(
    runs: Sequence[Mapping[str, Any]], objectives: Mapping[str, Any] | None = None
) -> Json:
    """The verdict split and ranges of a group of runs that differ only in their seed.

    ``runs``: ``[{"seed": int, "summary": dict | None}, ...]``. ``objectives``: the document's
    (``latencyP95Ms``, ``latencyP99Ms``, ``availability``, ``minThroughputRps``)."""
    objectives = dict(objectives or {})
    seeds = [int(r["seed"]) for r in runs]
    done = [(int(r["seed"]), _metrics(r["summary"])) for r in runs if r.get("summary")]
    met = missed = undecided = 0
    per_run: list[Json] = []
    total_objectives = 0
    for seed, m in done:
        result = evaluate(
            objectives,
            {
                "latencyMs": {"p95": m["p95"], "p99": m["p99"]},
                "successRate": m["successRate"],
                "throughputRps": m["throughputRps"],
            },
        )
        total_objectives = result["total"]
        if result["pass"] is True:
            met += 1
        elif result["pass"] is False:
            missed += 1
        else:
            undecided += 1
        per_run.append({"seed": seed, "pass": result["pass"], **m})
    finished = len(done)
    borderline = met > 0 and missed > 0
    if total_objectives == 0 or finished == 0:
        verdict: str | None = None
    elif borderline:
        verdict = "borderline"
    elif met == finished:
        verdict = "holds"
    elif missed == finished:
        verdict = "fails"
    else:
        verdict = None  # some runs had no data to decide
    p95 = value_range([m["p95"] for _, m in done])
    parts: list[str] = []
    if finished and total_objectives:
        if met or not missed:
            parts.append(f"Meets the objectives in {met} of {finished} runs")
        else:
            parts.append(f"Misses the objectives in {missed} of {finished} runs")
    elif finished:
        parts.append(f"{finished} runs, no objectives set")
    p95_text = range_text("p95", p95)
    if p95_text:
        parts.append(p95_text)
    parts.append(seeds_text(seeds))
    return {
        "runs": len(runs),
        "finished": finished,
        "seeds": {"first": min(seeds) if seeds else None, "last": max(seeds) if seeds else None},
        "objectives": total_objectives,
        "met": met,
        "missed": missed,
        "undecided": undecided,
        "verdict": verdict,
        "borderline": borderline,
        "confidenceCap": BORDERLINE_CAP if borderline else None,
        "latencyMs": {
            "p50": value_range([m["p50"] for _, m in done]),
            "p95": p95,
            "p99": value_range([m["p99"] for _, m in done]),
        },
        "successRate": value_range([m["successRate"] for _, m in done]),
        "throughputRps": value_range([m["throughputRps"] for _, m in done]),
        "perRun": per_run,
        "text": " · ".join(p for p in parts if p),
    }
