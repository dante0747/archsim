"""Recorded runs: per template, the base design's simulation and one scenario's, on a compressed
time axis, with every function of a report on them and the functions that compare the two."""

from __future__ import annotations

import copy
import dataclasses
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any
from unittest import mock

import archsim_engine
from archsim_engine import check as check_module
from archsim_engine import report as report_module
from archsim_engine import scenarios as scenarios_module
from golden.adapters import call
from golden.adapters.support import doc_of, registry
from golden.codec import Json, pointer
from golden.file import GoldenFile, same

RUN_TARGET_REQUESTS = 1500
"""A recorded run's time axis is compressed so it offers about this many requests..."""
RUN_MIN_S = 2.0
"""...but lasts at least this long (s), and never longer than the template's own run."""


def second_run(raw: Json) -> Json | None:
    """The scenario recorded beside the base design: the first one with faults, else the first
    that changes the traffic pattern, else the last."""
    scenarios = raw.get("scenarios") or []
    for path in ("faults", "pattern"):
        for s in scenarios:
            if any(o["target"]["kind"] == "workload" and o["path"] == path for o in s["overrides"]):
                return s
    return scenarios[-1] if scenarios else None


def _scale_pattern(pattern: Json, f: float) -> Json:
    p = dict(pattern)
    if p.get("kind") == "burst":
        p["startS"] = round(p["startS"] * f, 3)
        p["durationS"] = round(p["durationS"] * f, 3)
    elif p.get("kind") == "trace":
        p["points"] = [[round(t * f, 3), r] for t, r in p["points"]]
    return p


def _scale_faults(faults: list[Json], f: float) -> list[Json]:
    return [
        {**x, "startS": round(x["startS"] * f, 3), "durationS": round(x["durationS"] * f, 3)}
        for x in faults
    ]


def _scale_workload_value(path: str, value: Any, f: float) -> Any:
    if path in ("durationS", "warmupS"):
        return round(value * f, 3)
    if path == "pattern":
        return _scale_pattern(value, f)
    if path == "faults":
        return _scale_faults(value, f)
    if path.startswith(("pattern.", "faults")):
        raise ValueError(f"cannot compress the time of an override of workload.{path}")
    return value


def compressed(raw: Json, sid: str | None) -> tuple[Json, float]:
    """The template (with only scenario ``sid``) on a time axis compressed by a factor ``f`` so
    the run offers about RUN_TARGET_REQUESTS requests (at least RUN_MIN_S long): its duration,
    warm-up, burst and faults all scale by ``f``, so they keep their places in the run."""
    resolved, _ = scenarios_module.resolve_scenario(
        scenarios_module.load_document(raw), sid, registry()
    )
    wl = resolved.workload
    mean_rps = wl.expected_arrivals() / wl.duration_s
    target = max(RUN_MIN_S, RUN_TARGET_REQUESTS / mean_rps) if mean_rps > 0 else wl.duration_s
    f = min(wl.duration_s, round(target, 1)) / wl.duration_s
    doc = copy.deepcopy(raw)
    doc["scenarios"] = [s for s in doc.get("scenarios") or [] if s["id"] == sid]
    w = doc["workload"]
    for key in ("durationS", "warmupS"):
        if key in w:
            w[key] = round(w[key] * f, 3)
    if "pattern" in w:
        w["pattern"] = _scale_pattern(w["pattern"], f)
    if "faults" in w:
        w["faults"] = _scale_faults(w["faults"], f)
    for s in doc["scenarios"]:
        for o in s["overrides"]:
            if o["target"]["kind"] == "workload":
                o["value"] = _scale_workload_value(o["path"], o["value"], f)
    return doc, f


@contextmanager
def spying(module: Any, name: str) -> Iterator[list[tuple[tuple[Any, ...], Json, Any]]]:
    """Calls of ``module.name`` while inside: (args, kwargs, result) each."""
    calls: list[tuple[tuple[Any, ...], Json, Any]] = []
    real = getattr(module, name)

    def wrapper(*args: Any, **kwargs: Any) -> Any:
        result = real(*args, **kwargs)
        calls.append((args, kwargs, result))
        return result

    with mock.patch.object(module, name, wrapper):
        yield calls


def simulate(doc: Json, sid: str | None) -> tuple[Json, dict[str, bool]]:
    """A report of ``doc`` (seeded, so the same every time on one platform) with its
    machine-dependent provenance blanked, and whether each node's explain formulas were
    pool-based (an argument of ``explain.generic`` the report does not show)."""
    with spying(report_module, "generic") as calls:
        report = archsim_engine.run_simulation(doc, scenario_id=sid, registry=registry())
    normalize_report(report)
    return report, {args[0]["id"]: bool(args[2]) for args, _, _ in calls}


def normalize_report(report: Json) -> None:
    """Blank what depends on the machine, not the model: the wall time and Python version."""
    report["provenance"]["wallSeconds"] = 0.0
    report["provenance"]["python"] = "normalized"


def run_case(g: GoldenFile, key: str, raw: Json, sid: str | None) -> tuple[Json, Json, Json]:
    """One recorded run: its compressed document, its report, and every function of a report
    on it. Returns (document, report, resolved document)."""
    doc, f = compressed(raw, sid)
    where = ("runs", key)
    report, pool_based = simulate(doc, sid)
    g.place((*where, "scenarioId"), sid)
    g.place((*where, "timeScale"), f)
    g.place((*where, "document"), doc)
    g.place((*where, "report"), report)
    loaded = g.entry(where, "load_document", "archsim_engine.scenarios.load_document", {"raw": doc})
    out = g.entry(
        where,
        "resolve_scenario_sized",
        "archsim_engine.scenarios.resolve_scenario_sized",
        {"doc": loaded, "scenarioId": sid},
    )
    resolved, sizes = out["resolved"], out["sizes"]
    same(
        f"{pointer(where)} report.input",
        report["input"],
        call("archsim_engine.scenarios.simulation_input", {"resolved": resolved}),
    )
    findings = g.entry(
        where,
        "analyze",
        "archsim_engine.analysis.analyze",
        {"report": report, "resolved": resolved},
    )
    same(f"{pointer(where)} findings", findings, report["findings"])
    resolved_doc = doc_of(resolved)
    link_ms = {e.id: e.config.latency_ms.expected() for e in resolved_doc.architecture.edges}
    confidence = g.entry(
        where,
        "report_confidence",
        "archsim_engine.confidence.report_confidence",
        {"report": report, "inputs": report["assumptions"]["userInputs"], "linkMs": link_ms},
    )
    same(f"{pointer(where)} confidence", confidence, report["confidence"])
    busy = g.entry(where, "base_busy", "archsim_engine.sizing.base_busy", {"sizes": sizes})
    snap = report["input"]
    cost = g.entry(
        where,
        "estimate_cost",
        "archsim_engine.cost.estimate_cost",
        {
            "nodes": snap["architecture"]["nodes"],
            "report": report,
            "pricing": snap.get("pricing"),
            "provenance": {k: v["kind"] for k, v in resolved["provenance"].items()},
            "baseBusy": busy,
            "document": snap,
            "estimate": report["estimate"],
        },
    )
    same(f"{pointer(where)} cost", cost, report["cost"])
    window_s = report["window"]["measuredS"]
    for node in report["nodes"]:
        if node["id"] not in pool_based:
            continue
        formulas = g.entry(
            where,
            f"generic[{node['id']}]",
            "archsim_engine.explain.generic",
            {"node": node, "windowS": window_s, "poolBased": pool_based[node["id"]]},
        )
        same(f"{pointer(where)} explain {node['id']}", formulas, node["explain"][: len(formulas)])
    g.entry(where, "stationary", "archsim_engine.engine.stationary", {"report": report})
    digest = g.entry(
        where,
        "canonical_digest[input]",
        "archsim_engine.report.canonical_digest",
        {"obj": report["input"]},
    )
    same(f"{pointer(where)} inputDigest", digest, report["provenance"]["inputDigest"])
    digest = g.entry(
        where,
        "canonical_digest[result]",
        "archsim_engine.report.canonical_digest",
        {"obj": {k: report[k] for k in ("summary", "timeseries", "nodes", "edges", "window")}},
    )
    same(f"{pointer(where)} resultDigest", digest, report["provenance"]["resultDigest"])
    objectives = resolved["objectives"]
    summary = report["summary"]
    g.entry(
        where,
        "slo_checks",
        "archsim_engine.slo.slo_checks",
        {"objectives": objectives, "summary": summary},
    )
    result = g.entry(
        where,
        "evaluate",
        "archsim_engine.slo.evaluate",
        {"objectives": objectives, "summary": summary},
    )
    g.entry(where, "verdict_text", "archsim_engine.slo.verdict_text", {"result": result})
    row = g.entry(
        where,
        "row_from",
        "archsim_engine.sweep.row_from",
        {"rps": resolved["workload"]["rps"], "report": report},
    )
    g.entry(where, "meets", "archsim_engine.sweep.meets", {"row": row, "objectives": objectives})
    g.entry(
        where,
        "measure",
        "archsim_engine.check.measure",
        {"document": doc, "scenarioId": sid, "simulation": report},
    )
    return doc, report, resolved


def runs_and_comparison(g: GoldenFile, raw: Json) -> None:
    """The base design's run and one scenario's, and the functions that compare two runs."""
    _, base, base_resolved = run_case(g, "base", raw, None)
    other = second_run(raw)
    if other is None:
        return
    _, report, resolved = run_case(g, other["id"], raw, other["id"])
    where = ("comparison",)
    g.place((*where, "a"), "base")
    g.place((*where, "b"), other["id"])
    g.entry(
        where,
        "compare_reports",
        "archsim_engine.compare.compare_reports",
        {"a": base, "b": report, "labelA": "Base design", "labelB": other["name"]},
    )
    g.entry(
        where,
        "input_diff",
        "archsim_engine.compare.input_diff",
        {"a": base["input"], "b": report["input"]},
    )
    objectives = base_resolved["objectives"]
    seed = base["provenance"]["seed"]
    g.entry(
        where,
        "summarize_seeds",
        "archsim_engine.seeds.summarize_seeds",
        {
            "runs": [
                {"seed": seed, "summary": base["summary"]},
                {"seed": seed + 1, "summary": report["summary"]},
                {"seed": seed + 2, "summary": None},
            ],
            "objectives": objectives,
        },
    )
    rows = [
        call(
            "archsim_engine.sweep.row_from",
            {"rps": r["workload"]["rps"], "report": rep},
        )
        for r, rep in ((base_resolved, base), (resolved, report))
    ]
    g.entry(
        where, "analyze", "archsim_engine.sweep.analyze", {"rows": rows, "objectives": objectives}
    )
    thresholds = dataclasses.asdict(check_module.Thresholds())
    current = g.root["runs"][other["id"]]["entries"]["measure"]["output"]
    baseline = g.root["runs"]["base"]["entries"]["measure"]["output"]
    g.entry(
        where,
        "regressions",
        "archsim_engine.check.regressions",
        {"current": current, "baseline": baseline, "thresholds": thresholds},
    )
    result = g.entry(
        where,
        "run_check",
        "archsim_engine.check.run_check",
        {
            "current": current,
            "baseline": baseline,
            "thresholds": thresholds,
            "failOn": ["objectives", "regression"],
        },
    )
    g.entry(where, "headline", "archsim_engine.check.headline", {"result": result})
    for name, url in (("to_markdown", None), ("to_markdown[url]", "https://example.com/runs/1")):
        g.entry(
            where,
            name,
            "archsim_engine.check.to_markdown",
            {"result": result, "reportUrl": url},
        )
