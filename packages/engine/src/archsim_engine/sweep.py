"""Breaking point: step the offered load across short runs and read the curve.

The knee is the first step where the system stops keeping up (successful throughput below 95%
of the offered load) or tail latency jumps (p95 at least 4x the lightest step's and 2x the
previous step's, and 5 ms more than the lightest). The useful capacity number is the highest
load that still meets every objective (contiguously from the lightest step), usually below raw
saturation. Each step runs a constant load with no faults, so the curve measures steady-state
capacity. Same rules as the web app's breaking-point finder.
"""

from __future__ import annotations

import copy
import math
from typing import Any

from archsim_engine.registry import ComponentRegistry

Json = dict[str, Any]
MAX_STEPS = 10


def _nice_step(raw: float) -> float:
    if raw <= 0:
        return 1.0
    mag = 10 ** math.floor(math.log10(raw))
    r = raw / mag
    nice = 1 if r <= 1 else 2 if r <= 2 else 2.5 if r <= 2.5 else 5 if r <= 5 else 10
    return nice * mag


def suggest_range(current_rps: float) -> tuple[float, float]:
    """A default range around the baseline load: half to three times it, on round numbers."""
    base = current_rps if current_rps > 0 else 100.0
    step = _nice_step(base / 4)
    lo = max(step, math.floor(base * 0.5 / step + 0.5) * step)
    hi = max(lo + step, math.floor(base * 3 / step + 0.5) * step)
    return lo, hi


def levels(from_rps: float, to_rps: float, steps: int) -> list[float]:
    steps = max(2, min(MAX_STEPS, int(steps)))
    lo, hi = max(0.1, min(from_rps, to_rps)), max(from_rps, to_rps)
    out: list[float] = []
    for i in range(steps):
        v = lo + (hi - lo) * i / (steps - 1)
        r = (
            math.floor(v / 5 + 0.5) * 5
            if v >= 100
            else math.floor(v + 0.5)
            if v >= 10
            else math.floor(v * 10 + 0.5) / 10
        )
        if r not in out:
            out.append(float(r))
    return out


def row_from(rps: float, report: Json) -> Json:
    s = report["summary"]
    bottleneck = next((f for f in report["findings"] if f.get("kind") == "bottleneck"), None)
    label = None
    if bottleneck and bottleneck.get("nodeId"):
        label = next((n["label"] for n in report["nodes"] if n["id"] == bottleneck["nodeId"]), None)
    lat = s["latencyMs"]
    return {
        "rps": rps,
        "offered": s.get("offeredRps"),
        "throughput": s.get("throughputRps"),
        "p50": lat.get("p50"),
        "p95": lat.get("p95"),
        "p99": lat.get("p99"),
        "successRate": s.get("successRate"),
        "bottleneck": label,
        "resultDigest": report["provenance"]["resultDigest"],
    }


def meets(row: Json, objectives: Json) -> bool | None:
    if row.get("throughput") is None:
        return None
    p95, p99, avail = (
        objectives.get("latencyP95Ms"),
        objectives.get("latencyP99Ms"),
        objectives.get("availability"),
    )
    if p95 is not None and (row["p95"] is None or row["p95"] > p95):
        return False
    if p99 is not None and (row["p99"] is None or row["p99"] > p99):
        return False
    return not (avail is not None and (row["successRate"] is None or row["successRate"] < avail))


def analyze(rows: list[Json], objectives: Json) -> Json:
    rows = sorted((r for r in rows if r.get("throughput") is not None), key=lambda r: r["rps"])
    if not rows:
        return {
            "rows": rows,
            "knee": None,
            "kneeReason": None,
            "maxWithinSlo": None,
            "saturatedAt": None,
            "verdict": "No completed steps yet.",
        }
    base = next((r["p95"] for r in rows if r["p95"] is not None), None)
    knee, reason = None, None
    for i, r in enumerate(rows):
        offered = r.get("offered") or r["rps"]
        if offered > 0 and (r["throughput"] or 0) < 0.95 * offered:
            knee, reason = r, "throughput"
            break
        prev = rows[i - 1]["p95"] if i > 0 else None
        if (
            base is not None
            and prev is not None
            and r["p95"] is not None
            and r["p95"] >= 4 * base
            and r["p95"] >= 2 * prev
            and r["p95"] - base >= 5
        ):
            knee, reason = r, "latency"
            break
    max_ok = None
    for r in rows:
        if meets(r, objectives) is True:
            max_ok = r
        else:
            break
    saturated_at = (knee or rows[-1])["bottleneck"]

    def fmt(v: float) -> str:
        return f"{math.floor(v + 0.5):,}"

    if max_ok is None:
        verdict = (
            f"Misses the objectives already at {fmt(rows[0]['rps'])} req/s, the lightest step."
        )
    elif max_ok is rows[-1] and knee is None:
        verdict = f"Holds {fmt(rows[-1]['rps'])} req/s within the objectives with no sign of saturation. Try a higher range to find the limit."
    else:
        verdict = f"Holds {fmt(max_ok['rps'])} req/s within the objectives"
        if knee:
            verdict += f"; {'latency climbs sharply' if reason == 'latency' else 'saturates'} around {fmt(knee['rps'])} req/s"
        if saturated_at:
            verdict += f" at {saturated_at}"
        verdict += "."
    return {
        "rows": rows,
        "knee": knee,
        "kneeReason": reason,
        "maxWithinSlo": max_ok,
        "saturatedAt": saturated_at,
        "verdict": verdict,
    }


def _run_step(document: Json, limits: Any) -> Json:
    """One sweep step in a worker process (top level, so it pickles)."""
    from archsim_engine import run_simulation

    return run_simulation(document, limits=limits)


def breaking_point(
    document: Json,
    registry: ComponentRegistry,
    *,
    scenario_id: str | None = None,
    from_rps: float | None = None,
    to_rps: float | None = None,
    steps: int = 8,
    duration_s: float = 20.0,
    warmup_s: float = 5.0,
    limits: Any = None,
    workers: int = 1,
) -> Json:
    """Simulate the design (or a scenario) at evenly spaced constant loads and analyze the curve.
    ``workers`` > 1 runs the steps in that many processes (default registry only); every step
    is seeded, so the result is the same either way."""
    from archsim_engine import run_simulation
    from archsim_engine.scenarios import load_document, resolve_scenario

    # sized as drawn (and with the scenario applied): every step runs on the same sizes, like the
    # web app's sweep, so the curve shows the capacity of the design as it is
    base, _ = resolve_scenario(load_document(document), scenario_id, registry)
    raw = base.to_json_dict()
    raw["scenarios"] = []
    lo, hi = (
        suggest_range(raw["workload"]["rps"])
        if from_rps is None or to_rps is None
        else (from_rps, to_rps)
    )
    duration = max(2.0, duration_s)
    warmup = max(0.0, min(warmup_s, duration - 1))
    loads = levels(lo, hi, steps)
    docs = []
    for rps in loads:
        step = copy.deepcopy(raw)
        step["workload"].update(
            rps=rps, durationS=duration, warmupS=warmup, pattern={"kind": "constant"}, faults=[]
        )
        docs.append(step)
    from archsim_engine.engine import default_registry

    if workers > 1 and len(docs) > 1 and registry is default_registry():
        import multiprocessing
        from concurrent.futures import ProcessPoolExecutor

        ctx = multiprocessing.get_context("spawn")
        with ProcessPoolExecutor(max_workers=min(workers, len(docs)), mp_context=ctx) as pool:
            reports = list(pool.map(_run_step, docs, [limits] * len(docs)))
    else:
        reports = [run_simulation(d, registry=registry, limits=limits) for d in docs]
    rows = [row_from(rps, report) for rps, report in zip(loads, reports, strict=True)]
    objectives = raw.get("objectives") or {}
    return {**analyze(rows, objectives), "objectives": objectives, "range": [lo, hi]}
