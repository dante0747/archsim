"""An SLO gate for CI: does a design (or one of its scenarios) meet its objectives, and did it
regress against a baseline (the same design on the base branch, or another file)?

``archsim check`` exits 0 when it passes, 1 when an objective is missed or a metric regressed
past its threshold (as ``--fail-on`` asks), and 2 when the input cannot be used. Regressions
compare p95 latency, success rate, monthly cost and (with ``--knee``) the breaking point, which
is measured for both designs on one load grid (``knee_sweeps``). Results are the model's
estimates under the design's assumptions, not measurements.
"""

from __future__ import annotations

import html
import re
from dataclasses import asdict, dataclass
from itertools import pairwise
from typing import Any

from archsim_engine.registry import ComponentRegistry
from archsim_engine.slo import evaluate, verdict_text

Json = dict[str, Any]
MARKER = "<!-- archsim-check -->"
KNEE_STEPS = 6
"""Constant-load runs per sweep (one over the shared range, one between the breaking points)."""


@dataclass(frozen=True)
class Thresholds:
    """How much worse a metric may get before it counts as a regression."""

    p95_increase: float = 0.10
    """Relative: p95 up by more than 10%."""
    success_drop: float = 0.001
    """Absolute: success rate down by more than 0.1 percentage point."""
    cost_increase: float = 0.10
    """Relative: monthly cost up by more than 10%."""
    knee_drop: float = 0.10
    """Relative: the highest load within the objectives down by more than 10% (and by more
    than one step of the load grid it was measured on)."""


def measure(
    document: Json,
    registry: ComponentRegistry,
    scenario_id: str | None = None,
    knee: bool = False,
    limits: Any = None,
) -> Json:
    """Simulate once (and sweep the load with ``knee``); the numbers a check compares. To
    compare breaking points, ``run_check`` sweeps both designs together (``knee_sweeps``)."""
    from archsim_engine import load_document, run_simulation

    report = run_simulation(document, scenario_id=scenario_id, registry=registry, limits=limits)
    objectives = load_document(document).objectives.model_dump(mode="json", by_alias=True)
    s = report["summary"]
    slo = evaluate(objectives, s)
    cost = report.get("cost") or {}
    findings = report.get("findings") or []
    out: Json = {
        "name": report["input"]["metadata"]["name"],
        "scenarioId": scenario_id,
        "p95": s["latencyMs"].get("p95"),
        "p99": s["latencyMs"].get("p99"),
        "successRate": s.get("successRate"),
        "throughputRps": s.get("throughputRps"),
        "offeredRps": s.get("offeredRps"),
        "costMonthly": cost.get("monthly") if cost.get("hasCost") else None,
        "objectives": objectives,
        "slo": slo,
        "verdict": verdict_text(slo),
        "bottleneck": next(
            (f.get("title") for f in findings if f.get("kind") == "bottleneck"), None
        ),
        "resultDigest": report["provenance"]["resultDigest"],
        "engineVersion": report["provenance"]["engineVersion"],
        "confidence": {
            k: (report.get("confidence") or {}).get(k) for k in ("level", "reason", "howToRaise")
        },
        "knee": None,
    }
    if knee:
        out.update(knee_sweeps([document], registry, scenario_id, limits)[0])
    return out


def _knee(sweep: Json) -> float | None:
    rps: float | None = (sweep.get("maxWithinSlo") or {}).get("rps")
    return rps


def _grid_step(loads: list[float]) -> float:
    return max((b - a for a, b in pairwise(loads)), default=0.0)


def knee_sweeps(
    documents: list[Json],
    registry: ComponentRegistry,
    scenario_id: str | None = None,
    limits: Any = None,
) -> list[Json]:
    """The breaking point of each document (a design and its baseline) on ONE load grid, so a
    difference comes from the designs, not from two grids. The grid spans every document's
    suggested range (half to three times its load, within what the engine can run); a second
    sweep on a finer shared grid, from the lower breaking point to the first load above the
    higher one, narrows them down. ``knee`` is the highest load within the objectives (None:
    missed already at the lightest load), ``kneeRange`` the first grid's range and
    ``kneeResolution`` the step of the grid ``knee`` is read from."""
    import os

    from archsim_engine.sweep import breaking_point, sweep_range

    workers = min(KNEE_STEPS, os.cpu_count() or 1)

    def sweep(lo: float, hi: float) -> list[Json]:
        return [
            breaking_point(
                d,
                registry,
                scenario_id=scenario_id,
                from_rps=lo,
                to_rps=hi,
                steps=KNEE_STEPS,
                limits=limits,
                workers=workers,
            )
            for d in documents
        ]

    ranges = [sweep_range(d, registry, scenario_id, limits) for d in documents]
    lo, hi = min(r[0] for r in ranges), max(r[1] for r in ranges)
    final = sweep(lo, hi)
    knees = [k for k in map(_knee, final) if k is not None]
    if len(knees) == len(final):
        grid: list[float] = final[0]["levels"]
        a, top = min(knees), max(knees)
        b = next((g for g in grid if g > top), top)
        if b > a:  # every step is seeded: a load both grids share gives the same result
            fine = sweep(a, b)
            if all(_knee(f) is not None for f in fine):
                final = fine
    step = _grid_step(final[0]["levels"])
    return [
        {
            "knee": _knee(f),
            "kneeVerdict": f.get("verdict"),
            "kneeRange": [lo, hi],
            "kneeResolution": step,
        }
        for f in final
    ]


def regressions(current: Json, baseline: Json, th: Thresholds) -> list[Json]:
    out: list[Json] = []

    def rel(key: str) -> float | None:
        c, b = current.get(key), baseline.get(key)
        return (c - b) / b if c is not None and b not in (None, 0) else None

    change = rel("p95")
    if change is not None:
        out.append(
            _reg(
                "p95",
                "Latency p95",
                baseline["p95"],
                current["p95"],
                change,
                th.p95_increase,
                change > th.p95_increase,
            )
        )
    c, b = current.get("successRate"), baseline.get("successRate")
    if c is not None and b is not None:
        out.append(
            _reg(
                "successRate",
                "Success rate",
                b,
                c,
                c - b,
                th.success_drop,
                c - b < -th.success_drop,
            )
        )
    change = rel("costMonthly")
    if change is not None:
        out.append(
            _reg(
                "costMonthly",
                "Monthly cost",
                baseline["costMonthly"],
                current["costMonthly"],
                change,
                th.cost_increase,
                change > th.cost_increase,
            )
        )
    if current.get("kneeRange") is not None and baseline.get("kneeRange") is not None:
        out.append(_knee_change(current, baseline, th))
    return out


def _knee_change(current: Json, baseline: Json, th: Thresholds) -> Json:
    """Breaking points come from a load grid: each is known to one step, so a drop of one step
    may be a far smaller change, and only a larger drop past the threshold is a regression. A
    design that now misses its objectives even at the lightest load has regressed."""
    c, b = current.get("knee"), baseline.get("knee")
    step = max(current.get("kneeResolution") or 0.0, baseline.get("kneeResolution") or 0.0)
    change: float | None = None
    if c is not None and b is not None:
        change = (c - b) / b
        regressed = change < -th.knee_drop and b - c > step * (1 + 1e-9)
    else:
        regressed = b is not None  # and c is None: below the lightest load now
    row = _reg("knee", "Breaking point", b, c, change, th.knee_drop, regressed)
    row.update(resolution=step, lightest=current["kneeRange"][0])
    return row


def _reg(
    metric: str,
    label: str,
    base: float | None,
    cur: float | None,
    change: float | None,
    threshold: float,
    regressed: bool,
) -> Json:
    return {
        "metric": metric,
        "label": label,
        "baseline": base,
        "current": cur,
        "change": None if change is None else round(change, 6),
        "threshold": threshold,
        "regressed": regressed,
    }


def run_check(
    document: Json,
    registry: ComponentRegistry,
    *,
    scenario_id: str | None = None,
    baseline: Json | None = None,
    thresholds: Thresholds | None = None,
    knee: bool = False,
    fail_on: tuple[str, ...] = ("objectives",),
    limits: Any = None,
) -> Json:
    """The check of ``document`` against its objectives and, with ``baseline``, against it."""
    th = thresholds or Thresholds()
    current = measure(document, registry, scenario_id, limits=limits)
    base = None
    notes: list[str] = []
    if baseline is not None:
        try:
            base = measure(baseline, registry, scenario_id, limits=limits)
        except Exception as exc:  # the baseline may be an older, unreadable design
            notes.append(f"The baseline could not be simulated ({exc}); no regression check.")
    if knee:  # both designs on one load grid
        sides = [current] + ([base] if base else [])
        docs = [document] + ([baseline] if base and baseline is not None else [])
        for side, found in zip(
            sides, knee_sweeps(docs, registry, scenario_id, limits), strict=True
        ):
            side.update(found)
    regs = regressions(current, base, th) if base else []
    missed = current["slo"]["pass"] is False
    regressed = any(r["regressed"] for r in regs)
    failed = ("objectives" in fail_on and missed) or ("regression" in fail_on and regressed)
    result: Json = {
        "pass": not failed,
        "exitCode": 1 if failed else 0,
        "failOn": list(fail_on),
        "objectivesMissed": missed,
        "regressed": regressed,
        "current": current,
        "baseline": base,
        "regressions": regs,
        "thresholds": asdict(th),
        "notes": notes,
    }
    result["headline"] = headline(result)
    return result


def _ms(v: float | None) -> str:
    return "n/a" if v is None else f"{v:,.0f} ms" if v >= 100 else f"{v:,.1f} ms"


def _rps(v: float | None) -> str:
    if v is None:
        return "n/a"
    return f"{v / 1000:.1f}k req/s" if v >= 1000 else f"{v:,.0f} req/s"


def _usd(v: float) -> str:
    return f"${abs(v):,.0f}"


def _text(value: Any) -> str:
    """Text that comes from the design (its name, a part label, an error about it) for the
    comment: one line, HTML and Markdown markup escaped, so it cannot hide or fake the verdict
    (an unclosed ``<!--``, a new heading, a link)."""
    one_line = " ".join(str(value).split())
    return re.sub(r"([\\`*_\[\]#|!~])", r"\\\1", html.escape(one_line, quote=False))


def _code(value: Any) -> str:
    """Design text as inline code (literal on GitHub: no HTML, links or mentions)."""
    return "`" + " ".join(str(value).split()).replace("`", "'") + "`"


def headline(result: Json) -> str:
    """One sentence for a PR comment, e.g. "p95 goes from 180 to 410 ms (objective 300 ms) —
    fails; breaking point drops from 2.1k to 1.3k req/s; cost +$140 a month"."""
    cur, base = result["current"], result["baseline"]
    parts: list[str] = []
    p95_check = next((c for c in cur["slo"]["checks"] if c["key"] == "p95"), None)
    if base and base.get("p95") is not None and _ms(base["p95"]) != _ms(cur["p95"]):
        text = f"p95 goes from {_ms(base['p95'])} to {_ms(cur['p95'])}"
    elif base and base.get("p95") is not None:
        text = f"p95 stays at {_ms(cur['p95'])}"
    else:
        text = f"p95 {_ms(cur['p95'])}"
    if p95_check:
        text += f" (objective {_ms(p95_check['target'])})" + (
            " — fails" if p95_check["pass"] is False else ""
        )
    parts.append(text)
    for c in cur["slo"]["checks"]:
        if c["key"] != "p95" and c["pass"] is False:
            parts.append(f"{c['label'].lower()} misses its objective")
    if (
        base
        and cur.get("knee") is not None
        and base.get("knee") is not None
        and cur["knee"] != base["knee"]
    ):
        word = "drops" if cur["knee"] < base["knee"] else "rises"
        parts.append(f"breaking point {word} from {_rps(base['knee'])} to {_rps(cur['knee'])}")
    elif base and cur.get("kneeRange") and base.get("kneeRange"):
        below = f"below {_rps(cur['kneeRange'][0])}"
        if base.get("knee") is not None and cur.get("knee") is None:
            parts.append(f"breaking point drops from {_rps(base['knee'])} to {below}")
        elif base.get("knee") is None and cur.get("knee") is not None:
            parts.append(f"breaking point rises from {below} to {_rps(cur['knee'])}")
    if base and cur.get("costMonthly") is not None and base.get("costMonthly") is not None:
        delta = cur["costMonthly"] - base["costMonthly"]
        if abs(delta) >= 1:
            parts.append(f"cost {'+' if delta > 0 else '-'}{_usd(delta)} a month")
    if base and cur.get("successRate") is not None and base.get("successRate") is not None:
        drop = base["successRate"] - cur["successRate"]
        if drop > 0.0005:
            parts.append(
                f"success rate {base['successRate'] * 100:.2f}% → {cur['successRate'] * 100:.2f}%"
            )
    return "; ".join(parts)


def to_markdown(result: Json, report_url: str | None = None) -> str:
    """A PR comment: the verdict, the headline, the comparison and the objectives."""
    cur, base = result["current"], result["baseline"]
    status = "passes" if result["pass"] else "fails"
    icon = "✅" if result["pass"] else "❌"
    what = _text(cur["name"]) + (
        f" · scenario {_code(cur['scenarioId'])}" if cur.get("scenarioId") else ""
    )
    lines = [MARKER, f"### {icon} ArchSim check {status}: {what}", "", result["headline"] + ".", ""]
    if base:
        lines += ["| Metric | Base | This change | Change | |", "| --- | --- | --- | --- | --- |"]
        knee_step = None
        for r in result["regressions"]:
            if r["metric"] == "knee":
                knee_step = r.get("resolution")
            change = (
                "n/a"
                if r["change"] is None
                else f"{r['change'] * 100:+.1f} pp"
                if r["metric"] == "successRate"
                else f"{r['change'] * 100:+.0f}%"
            )
            flag = "regressed" if r["regressed"] else ""
            base_cell, cur_cell = _cell(r, r["baseline"]), _cell(r, r["current"])
            lines.append(f"| {r['label']} | {base_cell} | {cur_cell} | {change} | {flag} |")
        lines.append("")
        if knee_step:
            lines += [
                "Breaking point: the highest load within the objectives. Both designs are swept "
                f"on one load grid in steps of {_rps(knee_step)}, so a drop of one step is within "
                "its resolution and not a regression.",
                "",
            ]
    checks = cur["slo"]["checks"]
    if checks:
        lines += ["| Objective | Target | Result | |", "| --- | --- | --- | --- |"]
        for c in checks:
            fmt2 = (
                _ms
                if c["key"] in ("p95", "p99")
                else (lambda v: "n/a" if v is None else f"{v * 100:.2f}%")
                if c["key"] == "availability"
                else _rps
            )
            mark = "met" if c["pass"] else ("missed" if c["pass"] is False else "n/a")
            sign = "≤" if c["better"] == "lower" else "≥"
            lines.append(
                f"| {c['label']} | {sign} {fmt2(c['target'])} | {fmt2(c['actual'])} | {mark} |"
            )
        lines.append("")
    else:
        lines += [
            "No objectives are set on this design, so only regressions can fail the check.",
            "",
        ]
    if cur.get("bottleneck"):
        lines.append(f"Bottleneck: {_text(cur['bottleneck'])}.")
    conf = cur.get("confidence") or {}
    if conf.get("level"):
        lines.append(
            f"**{conf['level'].capitalize()} confidence**: {_text(conf['reason'])}. "
            f"To raise it: {_text(conf['howToRaise'])}"
        )
    for n in result["notes"]:
        lines.append(_text(n))
    fails = ", ".join(result["failOn"])
    lines.append(
        f"Fails on: {fails}. These are ArchSim's estimates under the design's assumptions "
        f"(engine {cur['engineVersion']}, result `{cur['resultDigest'][:12]}`), not measurements."
        + (f" [Full report]({report_url})" if report_url else "")
    )
    return "\n".join(lines) + "\n"


def _cell(row: Json, v: float | None) -> str:
    if row["metric"] == "knee" and v is None:  # missed even at the lightest load
        return f"< {_rps(row.get('lightest'))}"
    if v is None:
        return "n/a"
    if row["metric"] == "p95":
        return _ms(v)
    if row["metric"] == "successRate":
        return f"{v * 100:.2f}%"
    if row["metric"] == "costMonthly":
        return f"${v:,.0f}"
    return _rps(v)
