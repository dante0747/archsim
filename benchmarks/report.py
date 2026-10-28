"""Rewrite the generated parts of docs/validation.md (and its charts) from benchmarks/results.

    uv run python benchmarks/report.py          # the page and its charts
    uv run python benchmarks/report.py --json   # also docs/validation/accuracy.json

Hand-written text in docs/validation.md is kept: only the blocks between
``<!-- BEGIN GENERATED: name -->`` and ``<!-- END GENERATED: name -->`` are replaced. The output
depends only on the files in benchmarks/results, so running it twice gives the same bytes.

``--json`` writes the same numbers as data (``accuracy``) for the web app's /accuracy page, to
docs/validation/accuracy.json and to a copy inside apps/web (the web image is built from
apps/web alone, so it cannot read docs/).
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

import run as bench

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
RESULTS = HERE / "results"
DOC = ROOT / "docs" / "validation.md"
CHARTS = ROOT / "docs" / "validation"
JSON_OUT = CHARTS / "accuracy.json"
WEB_JSON = ROOT / "apps" / "web" / "src" / "data" / "accuracy.json"
ORDER = ["pg-mixed", "pg-hot-row", "cache-aside", "kafka"]
# Plain names for the web page (the comparison's title has the details).
NAMES = {
    "pg-mixed": "PostgreSQL, mixed reads and writes",
    "pg-hot-row": "PostgreSQL, every request updating one row",
    "cache-aside": "Redis cache in front of PostgreSQL",
    "kafka": "Kafka queue feeding PostgreSQL",
}
TECHNOLOGIES = {
    "pg-mixed": ("Node.js", "PostgreSQL"),
    "pg-hot-row": ("Node.js", "PostgreSQL"),
    "cache-aside": ("Node.js", "Redis", "PostgreSQL"),
    "kafka": ("Node.js", "Kafka", "PostgreSQL"),
}
# Each measured container as the resources table names it.
PARTS = {
    "api": "Node.js service",
    "producer": "Node.js producer",
    "consumer": "Node.js consumer",
    "postgres": "PostgreSQL",
    "redis": "Redis",
    "kafka": "Kafka broker",
}
# Why a container has no predicted CPU or memory.
NOT_MODELLED = {
    "consumer": "inside the model's queue node, which has no CPU or memory",
    "kafka": "the model's queue node has no CPU or memory",
}
NO_MEMORY_MODEL = {"postgres": "the model has no database memory figure yet"}
MEASURED_BY = {
    "window": "each container's CPU and working set (memory less reclaimable page cache), read "
    f"every {bench.SAMPLE_S:g} s inside the window k6 measures",
    "scaled": "each container's CPU over the whole k6 command, scaled to the measured window by "
    "the service's own CPU counter (these runs predate reading CPU inside the window; memory was "
    "not measured)",
    "mixed": "partly inside the measured window, partly scaled from the whole k6 command",
}
CONTAINERS = {
    "api": "service",
    "producer": "producer",
    "consumer": "consumer",
    "postgres": "PostgreSQL",
    "redis": "Redis",
    "kafka": "Kafka",
    "k6": "k6",
}

# Proposed tier thresholds (docs/validation.md, "Proposed tier thresholds").
KNEE_TOLERANCE = 0.25
P95_TOLERANCE = 1.0
P95_TOLERANCE_MS = 3.0
LOW_LOAD_FRACTION = 0.5

PRED = "#e8710a"  # orange: ArchSim's prediction
MEAS = "#1a73e8"  # blue: measured
INK = "#888888"


def load(exp: str, name: str, results: Path = RESULTS) -> dict[str, Any] | None:
    p = results / exp / f"{name}.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def pct(v: float | None, signed: bool = True) -> str:
    if v is None:
        return "-"
    return f"{v * 100:+.0f}%" if signed else f"{v * 100:.0f}%"


def ms(v: float | None) -> str:
    if v is None:
        return "-"
    return f"{v:,.0f}" if v >= 100 else f"{v:.1f}" if v >= 10 else f"{v:.2f}"


def rps(v: float | None) -> str:
    return "-" if v is None else f"{v:,.0f}"


def rps_list(values: list[float]) -> str:
    """1,000, 2,000 and 3,000 req/s"""
    items = [rps(v) for v in values]
    text = items[0] if len(items) == 1 else f"{', '.join(items[:-1])} and {items[-1]}"
    return f"{text} req/s"


def knee_text(k: Mapping[str, Any]) -> str:
    if k.get("kneeRps") is not None:
        return rps(k["kneeRps"])
    if k.get("bound") == "above":
        return f"> {rps(k.get('aboveRps'))}"
    if k.get("bound") == "below":
        return f"< {rps(k.get('belowRps'))}"
    return "-"


# ---------------------------------------------------------------------------------------------
# run history and the proposed tier criteria
# ---------------------------------------------------------------------------------------------


def protocol_of(calibration: Mapping[str, Any] | None) -> str:
    """The protocol version a calibration ran under ("v4"). Run 1 predates the method field."""
    m = re.search(r"\((v\d+)\)", (calibration or {}).get("method") or "")
    return m.group(1) if m else "v1"


def history(results: Path = RESULTS) -> dict[str, list[dict[str, Any]]]:
    """Every kept run of each experiment, oldest first, the latest last."""
    out: dict[str, list[dict[str, Any]]] = {}
    for exp, d in bench.result_dirs(results):
        cmp_path = d / "comparison.json"
        if not cmp_path.exists():
            continue
        cmp_ = json.loads(cmp_path.read_text(encoding="utf-8"))
        cal_path, env_path = d / "calibration.json", d / "environment.json"
        cal = json.loads(cal_path.read_text(encoding="utf-8")) if cal_path.exists() else None
        env = json.loads(env_path.read_text(encoding="utf-8")) if env_path.exists() else {}
        out.setdefault(exp, []).append(
            {
                "dir": f"{d.name}/" if d.name != exp else "latest",
                "protocol": protocol_of(cal),
                "calibratedAt": (cal or {}).get("at"),
                "machine": f"{env.get('cpu')}; {env.get('os')}",
                "measuredKnee": cmp_["measuredKnee"],
                "predictedKnee": cmp_["predictedKnee"],
                "kneeError": cmp_["kneeError"],
            }
        )
    return out


def p95_within(measured: float | None, predicted: float | None) -> bool:
    """Criterion 2 for one step: |predicted - measured| within 100% of the measured p95 or 3 ms,
    whichever is larger."""
    if measured is None or predicted is None:
        return False
    return abs(predicted - measured) <= max(P95_TOLERANCE * measured, P95_TOLERANCE_MS)


def criteria(cmp_: Mapping[str, Any], runs: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """The proposed tier criteria for one experiment, from its comparison and its kept runs
    (``history``; the last is the one compared):

    1. knee error within +/-25%;
    2. at or below the measured knee, every step's p95 within +/-100% or 3 ms (``p95_within``);
    3. no unstable step at or below the measured knee (3a), reproduced in a second run under the
       same protocol on another machine (3b)."""
    below = bench.at_or_below_knee(cmp_["rows"], cmp_["measuredKnee"])
    knee_error = cmp_["kneeError"]
    misses = [
        r["offeredRps"]
        for r in below
        if not p95_within(r["measured"]["p95"], r["predicted"]["p95"])
    ]
    unstable = [r["offeredRps"] for r in below if r.get("unstable")]
    protocol = runs[-1]["protocol"] if runs else None
    same = [r for r in runs if r["protocol"] == protocol]
    machines = len({r["machine"] for r in same})
    out: dict[str, Any] = {
        "knee": {
            "pass": knee_error is not None and abs(knee_error) <= KNEE_TOLERANCE,
            "error": knee_error,
        },
        "p95": {"pass": bool(below) and not misses, "steps": len(below), "misses": misses},
        "stable": {"pass": bool(below) and not unstable, "unstable": unstable},
        "reproduced": {
            "pass": len(same) >= 2 and machines >= 2,
            "protocol": protocol,
            "runs": len(same),
            "machines": machines,
        },
    }
    out["all"] = all(c["pass"] for c in out.values())
    return out


def technology_status(met: Mapping[str, bool]) -> list[dict[str, Any]]:
    """Each technology, the experiments that exercise it, and whether all of them meet the
    thresholds (``met``: experiment -> all three criteria met)."""
    techs: dict[str, list[str]] = {}
    for exp in ORDER:
        if exp in met:
            for t in TECHNOLOGIES[exp]:
                techs.setdefault(t, []).append(exp)
    return [
        {"name": t, "experiments": exps, "validated": all(met[e] for e in exps)}
        for t, exps in techs.items()
    ]


def knee_spread(runs: Sequence[Mapping[str, Any]]) -> dict[str, Any] | None:
    """How far the measured knee moved between the kept runs of one experiment (``history``) on
    the latest run's machine, or None with fewer than two such runs that measured one. Every
    protocol counts, as in docs/validation.md's headline: the protocols differ in the
    calibration, and the knee is found the same way in each (run 1 measured each step once
    rather than three times). ``plusMinus`` is half the range over its middle: 1,000 to 3,000
    req/s is +/-50%."""
    machine = runs[-1]["machine"] if runs else None
    knees = [
        (r, r["measuredKnee"]["kneeRps"])
        for r in runs
        if r["machine"] == machine and r["measuredKnee"].get("kneeRps") is not None
    ]
    if len(knees) < 2:
        return None
    lo, hi = min(k for _, k in knees), max(k for _, k in knees)
    plus_minus = (hi - lo) / (hi + lo)
    return {
        "runs": len(knees),
        "minRps": lo,
        "maxRps": hi,
        "plusMinus": plus_minus,
        "text": f"{rps(lo)} to {rps(hi)} req/s",
        "plusMinusText": f"±{pct(plus_minus, False)}",
        "values": [
            {
                "run": r["dir"],
                "protocol": r["protocol"],
                "calibratedAt": r["calibratedAt"],
                "kneeRps": k,
                "text": rps(k),
            }
            for r, k in knees
        ],
    }


def low_load_rows(cmp_: Mapping[str, Any]) -> list[dict[str, Any]]:
    """The ladder's low steps: at most LOW_LOAD_FRACTION of ArchSim's own predicted knee."""
    fine = cmp_["predictedKneeFine"]
    if not fine:
        return []
    return [r for r in cmp_["rows"] if r["offeredRps"] <= round(LOW_LOAD_FRACTION * fine)]


# ---------------------------------------------------------------------------------------------
# charts (plain SVG, readable on light and dark backgrounds)
# ---------------------------------------------------------------------------------------------


def _panel(
    x0: float,
    title: str,
    xs: list[float],
    series: list[tuple[str, str, list[float | None], bool]],
    log_y: bool,
    y_label: str,
    hline: float | None = None,
) -> list[str]:
    w, h, top, left, bottom = 330.0, 210.0, 30.0, 46.0, 34.0
    pw, ph = w - left - 10, h - top - bottom
    vals = [v for _, _, ys, _ in series for v in ys if v is not None and v > 0]
    if hline:
        vals.append(hline)
    if not vals:
        return []
    if log_y:
        lo = 10 ** math.floor(math.log10(min(vals)))
        hi = 10 ** math.ceil(math.log10(max(vals)))
    else:
        lo, hi = 0.0, max(vals) * 1.1
    xmax = max(xs) * 1.05

    def px(x: float) -> float:
        return x0 + left + pw * x / xmax

    def py(y: float) -> float:
        if log_y:
            f = (math.log10(max(y, lo)) - math.log10(lo)) / (math.log10(hi) - math.log10(lo))
        else:
            f = (y - lo) / (hi - lo)
        return top + ph * (1 - f)

    out = [
        f'<text x="{x0 + left}" y="16" font-size="12" font-weight="600" fill="{INK}">{title}</text>',
        f'<line x1="{x0 + left}" y1="{top + ph}" x2="{x0 + left + pw}" y2="{top + ph}" stroke="{INK}" stroke-width="1"/>',
        f'<line x1="{x0 + left}" y1="{top}" x2="{x0 + left}" y2="{top + ph}" stroke="{INK}" stroke-width="1"/>',
    ]
    ticks = []
    if log_y:
        t = lo
        while t <= hi * 1.0001:
            ticks.append(t)
            t *= 10
    else:
        step = 10 ** math.floor(math.log10(hi / 4)) if hi > 0 else 1
        step *= 5 if hi / step > 20 else 2 if hi / step > 8 else 1
        t = 0.0
        while t <= hi:
            ticks.append(t)
            t += step
    for t in ticks:
        y = py(t)
        label = f"{t:g}" if t < 1000 else f"{t / 1000:g}k"
        out.append(
            f'<line x1="{x0 + left - 3}" y1="{y:.1f}" x2="{x0 + left + pw}" y2="{y:.1f}" stroke="{INK}" stroke-opacity="0.18"/>'
        )
        out.append(
            f'<text x="{x0 + left - 6}" y="{y + 3:.1f}" font-size="9" text-anchor="end" fill="{INK}">{label}</text>'
        )
    for x in xs:
        out.append(
            f'<text x="{px(x):.1f}" y="{top + ph + 13}" font-size="9" text-anchor="middle" fill="{INK}">{x:,.0f}</text>'
        )
    out.append(
        f'<text x="{x0 + left + pw / 2}" y="{h - 4}" font-size="10" text-anchor="middle" fill="{INK}">offered load (req/s)</text>'
    )
    out.append(
        f'<text x="{x0 + 11}" y="{top + ph / 2}" font-size="10" text-anchor="middle" fill="{INK}" '
        f'transform="rotate(-90 {x0 + 11} {top + ph / 2})">{y_label}</text>'
    )
    if hline:
        y = py(hline)
        out.append(
            f'<line x1="{x0 + left}" y1="{y:.1f}" x2="{x0 + left + pw}" y2="{y:.1f}" stroke="{INK}" stroke-dasharray="2 3"/>'
        )
        out.append(
            f'<text x="{x0 + left + pw - 2}" y="{y - 4:.1f}" font-size="9" text-anchor="end" fill="{INK}">objective {hline:g} ms</text>'
        )
    for _name, color, ys, dashed in series:
        pts = [(px(x), py(y)) for x, y in zip(xs, ys, strict=True) if y is not None and y > 0]
        if len(pts) > 1:
            d = " ".join(f"{a:.1f},{b:.1f}" for a, b in pts)
            dash = ' stroke-dasharray="5 4"' if dashed else ""
            out.append(
                f'<polyline points="{d}" fill="none" stroke="{color}" stroke-width="2"{dash}/>'
            )
        for a, b in pts:
            out.append(f'<circle cx="{a:.1f}" cy="{b:.1f}" r="2.6" fill="{color}"/>')
    return out


def chart(cmp_: Mapping[str, Any]) -> str:
    rows = cmp_["rows"]
    xs = [r["offeredRps"] for r in rows]
    p95 = _panel(
        0,
        "p95 latency (log scale)",
        xs,
        [
            ("measured", MEAS, [r["measured"]["p95"] for r in rows], False),
            ("ArchSim", PRED, [r["predicted"]["p95"] for r in rows], True),
        ],
        True,
        "ms",
        cmp_["objective"]["p95Ms"],
    )
    thr = _panel(
        340,
        "throughput (successful)",
        xs,
        [
            ("offered", INK, xs, True),
            ("measured", MEAS, [r["measured"]["throughputRps"] for r in rows], False),
            ("ArchSim", PRED, [r["predicted"]["throughputRps"] for r in rows], True),
        ],
        False,
        "req/s",
    )
    legend = [
        f'<rect x="440" y="5" width="14" height="3" fill="{MEAS}"/><text x="458" y="10" font-size="10" fill="{INK}">measured</text>',
        f'<rect x="520" y="5" width="14" height="3" fill="{PRED}"/><text x="538" y="10" font-size="10" fill="{INK}">ArchSim (predicted first)</text>',
    ]
    svg = [
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 680 214" width="680" height="214" '
        'font-family="-apple-system, Segoe UI, Helvetica, Arial, sans-serif">',
        *p95,
        *thr,
        *legend,
        "</svg>",
    ]
    return "\n".join(svg) + "\n"


# ---------------------------------------------------------------------------------------------
# markdown
# ---------------------------------------------------------------------------------------------


def summary_block(cmps: list[dict[str, Any]]) -> str:
    lines = [
        "| Experiment | Knee measured | Knee predicted | Knee error | p95 error at or below the measured knee (median / worst) |",
        "| --- | --- | --- | --- | --- |",
    ]
    for c in cmps:
        b = c["belowKnee"]
        lines.append(
            f"| [{c['experiment']}](#{c['experiment']}) | {knee_text(c['measuredKnee'])} req/s | "
            f"{knee_text(c['predictedKnee'])} req/s | {pct(c['kneeError'])} | "
            f"{pct(b['medianAbsP95Error'], False)} / {pct(b['maxAbsP95Error'], False)} ({b['steps']} steps) |"
        )
    return "\n".join(lines)


def history_block(hist: Mapping[str, Sequence[Mapping[str, Any]]]) -> str:
    lines = [
        "| Experiment | Results | Protocol | Calibrated (UTC) | Knee measured | Knee predicted | Knee error |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for exp in ORDER:
        for r in hist.get(exp, []):
            where = f"`{r['dir']}`" if r["dir"] != "latest" else f"latest (`results/{exp}/`)"
            lines.append(
                f"| {exp} | {where} | {r['protocol']} | {r['calibratedAt'] or '-'} | "
                f"{knee_text(r['measuredKnee'])} req/s | {knee_text(r['predictedKnee'])} req/s | "
                f"{pct(r['kneeError'])} |"
            )
    return "\n".join(lines)


def low_load_block(cmps: list[dict[str, Any]]) -> str:
    """p95 at the ladder's low steps (at most half the predicted knee), predicted against
    measured, in milliseconds as well as relative."""
    lines = [
        "| Experiment | Offered req/s | p95 measured (ms) | p95 predicted (ms) | Predicted - measured |",
        "| --- | --- | --- | --- | --- |",
    ]
    for c in cmps:
        fine = c["predictedKneeFine"]
        for r in low_load_rows(c):
            m, p = r["measured"]["p95"], r["predicted"]["p95"]
            diff = "-" if m is None or p is None else f"{p - m:+.2f} ms ({pct(r['error']['p95'])})"
            lines.append(
                f"| {c['experiment']} | {rps(r['offeredRps'])} ({r['offeredRps'] / fine:.2g}x the "
                f"predicted knee) | {ms(m)} | {ms(p)} | {diff} |"
            )
    return "\n".join(lines)


def cores(v: float | None) -> str:
    return "-" if v is None else f"{v:.2f}"


def mib(v: float | None) -> str:
    if v is None:
        return "-"
    return f"{v / 1024:.1f} GiB" if v >= 1024 else f"{v:,.0f} MiB"


def predicted_by_text(by: Any) -> str:
    if by == "predictions":
        return "ArchSim's predictions, saved before the measurements"
    if isinstance(by, Mapping):
        return (
            f"simulated again with engine {by.get('engineVersion')} on {str(by.get('at'))[:10]} "
            "from each run's calibration alone, because the original predictions did not record "
            "CPU or memory (the latency predictions are the original ones)"
        )
    return "none"


def top_below_knee(c: Mapping[str, Any]) -> Mapping[str, Any] | None:
    """The highest load step at or below the measured knee."""
    below = bench.at_or_below_knee(c["rows"], c["measuredKnee"])
    return max(below, key=lambda r: r["offeredRps"]) if below else None


def why_not(container: str, what: str) -> str | None:
    """Why the model has no figure of this kind (cores or memory) for the container."""
    if container in NOT_MODELLED:
        return NOT_MODELLED[container]
    if what == "memory":
        return NO_MEMORY_MODEL.get(container)
    return None


def memory_measured(c: Mapping[str, Any]) -> bool:
    """Whether any step of a comparison measured a container's memory."""
    return any(
        cell["measured"]["memoryMiB"] is not None
        for r in c["rows"]
        for cell in r.get("resources", {}).values()
    )


def resources_block(cmps: list[dict[str, Any]]) -> str:
    """Predicted against measured CPU (and memory, once measured) of each container: at the
    highest step at or below the measured knee, and the median error over every step up to it."""
    with_res = [c for c in cmps if c.get("resources")]
    if not with_res:
        return "_No CPU or memory recorded yet._"
    memory = any(memory_measured(c) for c in with_res)
    head = "| Experiment | Part | Load step (req/s) | Busy cores measured | Busy cores predicted | Error | Median error up to the knee (steps) |"
    lines = [
        head + (" Memory measured | Memory predicted | Error |" if memory else ""),
        "| --- " * (7 + (3 if memory else 0)) + "|",
    ]
    unmodelled: dict[str, str] = {}
    for c in with_res:
        top = top_below_knee(c)
        for container, s in c["resources"]["belowKnee"].items():
            cell = (top or {}).get("resources", {}).get(container)
            if top is None or cell is None:
                continue
            m, p, e = cell["measured"], cell["predicted"], cell["error"]
            name = PARTS.get(container, container)
            median_err = s["medianAbsCoresError"]
            median_text = "-" if median_err is None else f"{pct(median_err, False)} ({s['steps']})"
            if (reason := why_not(container, "cores")) is not None:
                unmodelled[name] = reason
            line = (
                f"| [{c['experiment']}](#{c['experiment']}) | {name} | {rps(top['offeredRps'])} | "
                f"{cores(m['cores'])} | {'not modelled' if reason else cores(p['cores'])} | "
                f"{pct(e['cores'])} | {median_text} |"
            )
            if memory:
                line += f" {mib(m['memoryMiB'])} | {mib(p['memoryMiB'])} | {pct(e['memory'])} |"
            lines.append(line)
    by = sorted({c["resources"]["measuredBy"] for c in with_res})
    pred = sorted({predicted_by_text(c["resources"]["predictedBy"]) for c in with_res})
    lines += [
        "",
        "Each part at the highest load step at or below the measured knee. Busy cores are CPU "
        "seconds per second; errors are (predicted - measured) / measured.",
        "",
        f"Measured: {'; '.join(MEASURED_BY.get(b, b) for b in by)}. Predicted: {'; '.join(pred)}.",
    ]
    if unmodelled:
        lines += [
            "",
            "Not modelled: "
            + "; ".join(f"{name} ({why})" for name, why in unmodelled.items())
            + ".",
        ]
    return "\n".join(lines)


def tiers_block(cmps: list[dict[str, Any]], hist: Mapping[str, Sequence[Mapping[str, Any]]]) -> str:
    lines = [
        "| Experiment | 1. Knee error within ±25% | 2. p95 within ±100% or 3 ms at or below the measured knee | 3a. No unstable step at or below the measured knee | 3b. Reproduced on a second machine, same protocol | All three |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    met: dict[str, bool] = {}
    for c in cmps:
        exp = c["experiment"]
        k = criteria(c, hist.get(exp, []))
        met[exp] = k["all"]
        knee = f"{'yes' if k['knee']['pass'] else 'no'} ({pct(k['knee']['error'])})"
        p = k["p95"]
        if not p["steps"]:
            p95 = "no: no step at or below the measured knee"
        elif p["pass"]:
            p95 = f"yes ({p['steps']} of {p['steps']} steps)"
        else:
            p95 = (
                f"no: {rps_list(p['misses'])} miss ({p['steps'] - len(p['misses'])} of "
                f"{p['steps']} steps within)"
            )
        s = k["stable"]
        stable = (
            "yes"
            if s["pass"]
            else f"no: {rps_list(s['unstable'])} unstable"
            if s["unstable"]
            else "no: no step at or below the measured knee"
        )
        r = k["reproduced"]
        runs = f"{r['runs']} run{'s' if r['runs'] != 1 else ''} under {r['protocol']}, on {r['machines']} machine{'s' if r['machines'] != 1 else ''}"
        reproduced = f"{'yes' if r['pass'] else 'no'}: {runs}"
        lines.append(
            f"| {exp} | {knee} | {p95} | {stable} | {reproduced} | {'yes' if k['all'] else 'no'} |"
        )
    techs = technology_status(met)
    validated = [t["name"] for t in techs if t["validated"]]
    lines += [
        "",
        "A technology meets the thresholds when every experiment that exercises it meets all "
        "three: "
        + "; ".join(f"{t['name']} ({', '.join(t['experiments'])})" for t in techs)
        + ". "
        + (
            f"Meeting them on this data: {', '.join(validated)}."
            if validated
            else "None meets them on this data."
        ),
    ]
    return "\n".join(lines)


def cpus_text(sets: Mapping[str, str]) -> str:
    return ", ".join(
        f"{CONTAINERS.get(name, name)} {bench.cpu_count(cpus)} (`{cpus}`)"
        for name, cpus in sets.items()
    )


def stack_cpusets(exp: str, env: Mapping[str, Any]) -> tuple[dict[str, str], bool]:
    """The CPUs each container of an experiment's stack was pinned to, and whether
    environment.json recorded them (runs before that used the runner's defaults)."""
    sets = env.get("cpusets")
    if sets is None:
        return bench.cpusets(bench.EXPERIMENTS[exp].stack, env={}), False
    return dict(sets), True


def machine_text(env: Mapping[str, Any]) -> str:
    """CPU; Docker version with its CPUs and memory; OS."""
    return (
        f"{env.get('cpu')}; Docker {env.get('docker')} with {env.get('dockerCpus')} CPUs and "
        f"{int(env.get('dockerMemory') or 0) / 2**30:.1f} GiB; {env.get('os')}"
    )


def environment_block(envs: Mapping[str, dict[str, Any]]) -> str:
    """The machine (from the first experiment's environment.json) and the CPU pinning of each
    stack (recorded in environment.json since it was added; before that, the runner's
    defaults)."""
    if not envs:
        return "_No results yet._"
    env = next(iter(envs.values()))
    imgs = env.get("images", {})
    stacks: dict[tuple[str, str], list[str]] = {}
    recorded = True
    for exp, e in envs.items():
        stack = bench.EXPERIMENTS[exp].stack
        sets, was_recorded = stack_cpusets(exp, e)
        recorded = recorded and was_recorded
        stacks.setdefault((stack, cpus_text(sets)), []).append(exp)
    pinning = "; ".join(f"{' and '.join(exps)}: {text}" for (_, text), exps in stacks.items())
    note = (
        ""
        if recorded
        else " These are the runner's defaults (`benchmarks/run.py`, `compose.yaml`): the runs "
        "shown predate recording the cpusets in `environment.json`."
    )
    return "\n".join(
        [
            f"- CPU: {machine_text(env)}.",
            f"- Images: {', '.join(f'{k} `{v}`' for k, v in imgs.items())}.",
            f"- CPUs pinned per container (cpuset; k6 gets the CPUs the stack leaves free): {pinning}.{note}",
        ]
    )


def input_value(name: str, v: Any) -> str:
    if isinstance(v, dict) and v.get("kind") == "lognormal":
        return f"lognormal, median {v['median']:g} ms, p95 {v['p95']:g} ms"
    if isinstance(v, dict) and v.get("kind") == "exponential":
        return f"exponential, mean {v['mean']:g} ms"
    return f"{v:g}" + (" ms" if name.endswith("Ms") else "")


def experiment_block(exp: str, results: Path = RESULTS) -> str:
    cal = load(exp, "calibration", results)
    pred = load(exp, "predictions", results)
    cmp_ = load(exp, "comparison", results)
    if not (cal and pred and cmp_):
        return f"_No results for {exp} yet: run `uv run python benchmarks/run.py`._"
    low = cal["inputs"].get("measuredLowLoad", {})
    passes = cal.get("passes", {})
    rate_low = passes.get("low", {}).get("rateRps")
    rate_high = passes.get("high", {}).get("rateRps", low.get("rateRps"))
    source = (
        f"Model inputs from the calibration passes at {rate_low:g} and {rate_high:g} req/s "
        f"(timings from the {rate_high:g} req/s pass, CPU per request from the slope between "
        "the two):"
        if rate_low is not None and rate_high is not None
        else f"Model inputs from the calibration pass at {rate_high:g} req/s:"
    )
    out = [
        f"**{cmp_['title']}.** Calibrated {cal['at']}, predicted {pred['at']} (engine {pred['engineVersion']}), "
        f"measured afterwards. Objective: p95 <= {cmp_['objective']['p95Ms']:g} ms with >= "
        f"{cmp_['objective']['minSuccess'] * 100:g}% success.",
        "",
        f"![{exp}: predicted and measured p95 and throughput](validation/{exp}.svg)",
        "",
        source,
        "",
        "| Input | Value | How it was measured |",
        "| --- | --- | --- |",
    ]
    for name, item in cal["inputs"].items():
        if not isinstance(item, dict) or "value" not in item:
            continue
        out.append(f"| `{name}` | {input_value(name, item['value'])} | {item['how']} |")
    out += [
        "",
        f"Measured in the calibration pass at {low.get('rateRps', rate_high):g} req/s: "
        f"p50 {ms(low.get('p50'))} ms, p95 {ms(low.get('p95'))} ms.",
        "",
        "| Offered req/s | p50 meas / pred (ms) | p95 meas / pred (ms) | p95 error | p99 meas / pred (ms) | Success meas / pred | Throughput meas / pred |"
        + (" Hit ratio meas / pred |" if exp == "cache-aside" else "")
        + (
            " Consumed/s meas / pred | Message age p95 meas / pred (ms) |" if exp == "kafka" else ""
        ),
        "| --- | --- | --- | --- | --- | --- | --- |"
        + (" --- |" if exp == "cache-aside" else "")
        + (" --- | --- |" if exp == "kafka" else ""),
    ]
    for r in cmp_["rows"]:
        m, p, e = r["measured"], r["predicted"], r["error"]
        offered = rps(r["offeredRps"]) + (" (unstable)" if r.get("unstable") else "")
        line = (
            f"| {offered} | {ms(m['p50'])} / {ms(p['p50'])} | {ms(m['p95'])} / {ms(p['p95'])} | "
            f"{pct(e['p95'])} | {ms(m['p99'])} / {ms(p['p99'])} | {pct(m['successRate'], False)} / "
            f"{pct(p['successRate'], False)} | {rps(m['throughputRps'])} / {rps(p['throughputRps'])} |"
        )
        if exp == "cache-aside":
            line += (
                f" {pct(m.get('cacheHitRatio'), False)} / {pct(p.get('cacheHitRatio'), False)} |"
            )
        if exp == "kafka":
            mq, pq = m.get("queue") or {}, p.get("queue") or {}
            line += (
                f" {rps(mq.get('consumedRps'))} / {rps(pq.get('consumedRps'))} |"
                f" {ms(mq.get('p95MessageAgeMs'))} / {ms(pq.get('p95MessageAgeMs'))} |"
            )
        out.append(line)
    notes = []
    if any(r.get("unstable") for r in cmp_["rows"]):
        notes.append(
            "Unstable: the step's repeats' p95s differ by more than 2x across the objective (a "
            "repeat with no successful request counts as slower than any)."
        )
    for r in cmp_["rows"]:
        if r.get("failedRepeats"):
            outcome = (
                "so this step has no measured latency"
                if r["measured"]["p95"] is None
                else "so the step's latency is the slower of the other repeats"
            )
            notes.append(
                f"At {rps(r['offeredRps'])} req/s, {r['failedRepeats']} of {r['repeats']} repeats "
                "had no successful request (every request failed or was dropped). Such a repeat "
                "counts as failed: it has no latency, and in the step's median it counts as "
                f"slower than any measured repeat, {outcome}."
            )
    if notes:
        out += ["", *notes]
    mk, pk = cmp_["measuredKnee"], cmp_["predictedKnee"]
    out += [
        "",
        f"Knee: measured {knee_text(mk)} req/s, predicted {knee_text(pk)} req/s on the same steps "
        f"({pct(cmp_['kneeError'])}); ArchSim's own search before measuring put it at "
        f"{rps(cmp_['predictedKneeFine'])} req/s. Data: `benchmarks/results/{exp}/` (each "
        "repeat's numbers as extracted from k6's summary; the summaries themselves are not kept).",
    ]
    return "\n".join(out)


def replace_block(text: str, name: str, body: str) -> str:
    pat = re.compile(
        rf"(<!-- BEGIN GENERATED: {re.escape(name)} -->\n).*?(\n<!-- END GENERATED: {re.escape(name)} -->)",
        re.S,
    )
    if not pat.search(text):
        raise SystemExit(f"docs/validation.md has no generated block '{name}'")
    return pat.sub(lambda m: m.group(1) + body + m.group(2), text)


def render(text: str, results: Path = RESULTS) -> tuple[str, dict[str, str]]:
    """The page with its generated blocks rewritten, and the charts (file name -> SVG)."""
    cmps = [c for c in (load(e, "comparison", results) for e in ORDER) if c]
    hist = history(results)
    text = replace_block(text, "summary", summary_block(cmps) if cmps else "_No results yet._")
    text = replace_block(text, "history", history_block(hist) if hist else "_No results yet._")
    text = replace_block(text, "low-load", low_load_block(cmps) if cmps else "_No results yet._")
    text = replace_block(text, "resources", resources_block(cmps))
    text = replace_block(text, "tiers", tiers_block(cmps, hist) if cmps else "_No results yet._")
    envs = {e: env for e in ORDER if (env := load(e, "environment", results))}
    text = replace_block(text, "environment", environment_block(envs))
    charts = {}
    for exp in ORDER:
        text = replace_block(text, exp, experiment_block(exp, results))
        c = load(exp, "comparison", results)
        if c:
            charts[f"{exp}.svg"] = chart(c)
    return text, charts


# ---------------------------------------------------------------------------------------------
# JSON for the web app's /accuracy page
# ---------------------------------------------------------------------------------------------


def step_json(r: Mapping[str, Any], below: bool) -> dict[str, Any]:
    """One step of the ladder: measured and predicted p95 and success, with the same text as
    the page's tables."""
    m, p, e = r["measured"], r["predicted"], r["error"]
    return {
        "offeredRps": r["offeredRps"],
        "atOrBelowKnee": below,
        "unstable": bool(r.get("unstable")),
        "measured": {"p95Ms": m["p95"], "successRate": m["successRate"]},
        "predicted": {"p95Ms": p["p95"], "successRate": p["successRate"]},
        "p95Error": e["p95"],
        "p95Within": p95_within(m["p95"], p["p95"]),
        "text": {
            "offered": rps(r["offeredRps"]),
            "measuredP95": ms(m["p95"]),
            "predictedP95": ms(p["p95"]),
            "p95Error": pct(e["p95"]),
            "measuredSuccess": pct(m["successRate"], False),
            "predictedSuccess": pct(p["successRate"], False),
        },
    }


def low_load_json(r: Mapping[str, Any], fine: float) -> dict[str, Any]:
    """One low-load step (``low_load_rows``) as the low-load table shows it."""
    m, p = r["measured"]["p95"], r["predicted"]["p95"]
    return {
        "offeredRps": r["offeredRps"],
        "fractionOfPredictedKnee": r["offeredRps"] / fine,
        "measuredP95Ms": m,
        "predictedP95Ms": p,
        "diffMs": None if m is None or p is None else p - m,
        "text": {
            "offered": rps(r["offeredRps"]),
            "fraction": f"{r['offeredRps'] / fine:.2g}x",
            "measuredP95": ms(m),
            "predictedP95": ms(p),
            "diff": "-" if m is None or p is None else f"{p - m:+.2f} ms",
            "p95Error": pct(r["error"]["p95"]),
        },
    }


def resource_cell_json(cell: Mapping[str, Any]) -> dict[str, Any]:
    """One container at one step: measured and predicted busy cores and memory, with the
    resources table's text."""
    m, p, e = cell["measured"], cell["predicted"], cell["error"]
    return {
        "measuredCores": m["cores"],
        "predictedCores": p["cores"],
        "coresError": e["cores"],
        "measuredMemoryMiB": m["memoryMiB"],
        "predictedMemoryMiB": p["memoryMiB"],
        "memoryError": e["memory"],
        "text": {
            "measuredCores": cores(m["cores"]),
            "predictedCores": cores(p["cores"]),
            "coresError": pct(e["cores"]),
            "measuredMemory": mib(m["memoryMiB"]),
            "predictedMemory": mib(p["memoryMiB"]),
            "memoryError": pct(e["memory"]),
        },
    }


def resources_json(c: Mapping[str, Any]) -> dict[str, Any] | None:
    """An experiment's CPU and memory, predicted against measured: per part, the highest step at
    or below the measured knee and the errors up to it; and every step."""
    r = c.get("resources")
    if not r:
        return None
    top = top_below_knee(c)
    below = {id(x) for x in bench.at_or_below_knee(c["rows"], c["measuredKnee"])}
    rows = [x for x in c["rows"] if "resources" in x]
    parts = []
    for container, s in r["belowKnee"].items():
        cell = (top or {}).get("resources", {}).get(container)
        predicted_cores = any(
            x["resources"].get(container, {}).get("predicted", {}).get("cores") is not None
            for x in rows
        )
        predicted_memory = any(
            x["resources"].get(container, {}).get("predicted", {}).get("memoryMiB") is not None
            for x in rows
        )
        parts.append(
            {
                "container": container,
                "name": PARTS.get(container, container),
                "modelled": {"cores": predicted_cores, "memory": predicted_memory},
                "notModelled": {
                    "cores": why_not(container, "cores"),
                    "memory": why_not(container, "memory"),
                },
                "belowKnee": s,
                "top": resource_cell_json(cell) if cell else None,
                "text": {
                    "medianAbsCoresError": pct(s["medianAbsCoresError"], False),
                    "maxAbsCoresError": pct(s["maxAbsCoresError"], False),
                    "medianAbsMemoryError": pct(s["medianAbsMemoryError"], False),
                },
            }
        )
    by = r["predictedBy"]
    return {
        "measuredBy": r["measuredBy"],
        "measuredByText": MEASURED_BY.get(r["measuredBy"], r["measuredBy"]),
        "predictedBy": "predictions" if by == "predictions" else "re-simulated",
        "predictedByText": predicted_by_text(by),
        "predictedEngineVersion": by.get("engineVersion") if isinstance(by, Mapping) else None,
        "memoryMeasured": memory_measured(c),
        "topRps": top["offeredRps"] if top else None,
        "parts": parts,
        "steps": [
            {
                "offeredRps": x["offeredRps"],
                "atOrBelowKnee": id(x) in below,
                "text": {"offered": rps(x["offeredRps"])},
                "parts": {k: resource_cell_json(v) for k, v in x["resources"].items()},
            }
            for x in rows
        ],
    }


def environment_json(exp: str, env: Mapping[str, Any]) -> dict[str, Any]:
    sets, recorded = stack_cpusets(exp, env)
    cpus = env.get("dockerCpus")
    return {
        "cpu": env.get("cpu"),
        "os": env.get("os"),
        "docker": env.get("docker"),
        "dockerCpus": int(cpus) if isinstance(cpus, str) and cpus.isdigit() else cpus,
        "dockerMemoryGiB": round(int(env.get("dockerMemory") or 0) / 2**30, 1),
        "images": dict(env.get("images", {})),
        "python": env.get("python"),
        "cpusets": [
            {"container": CONTAINERS.get(name, name), "cpus": c, "count": bench.cpu_count(c)}
            for name, c in sets.items()
        ],
        "cpusetsRecorded": recorded,
        "text": machine_text(env),
    }


def experiment_json(
    exp: str, runs: Sequence[Mapping[str, Any]], results: Path = RESULTS
) -> dict[str, Any] | None:
    """One experiment's latest run, as the summary, low-load and tier tables show it, plus how
    far its measured knee moved between kept runs (``runs``: its ``history``)."""
    cal = load(exp, "calibration", results)
    pred = load(exp, "predictions", results)
    c = load(exp, "comparison", results)
    if not (cal and pred and c):
        return None
    env = load(exp, "environment", results) or {}
    k = criteria(c, runs)
    mk, pk, e = c["measuredKnee"], c["predictedKnee"], c["kneeError"]
    below = bench.at_or_below_knee(c["rows"], mk)
    below_ids = {id(r) for r in below}
    measured = [r for r in below if r["error"]["p95"] is not None]
    worst = max(measured, key=lambda r: abs(r["error"]["p95"]), default=None)
    b = c["belowKnee"]
    spread = knee_spread(runs)
    protocol = protocol_of(cal)
    return {
        "id": exp,
        "name": NAMES.get(exp, exp),
        "title": c["title"],
        "technologies": list(TECHNOLOGIES[exp]),
        "objective": c["objective"],
        "date": cal["at"][:10],
        "calibratedAt": cal["at"],
        "predictedAt": pred["at"],
        "engineVersion": pred.get("engineVersion"),
        "protocol": protocol,
        "runs": len(runs),
        # Earlier runs under another protocol: the protocol was revised with this experiment's
        # results in view, so its latest numbers are in-sample.
        "inSample": any(r["protocol"] != protocol for r in runs),
        "tier": "validated" if k["all"] else "not-validated",
        "criteria": k,
        "knee": {
            "measured": mk,
            "predicted": pk,
            "searchedRps": c["predictedKneeFine"],
            "error": e,
            "withinTolerance": k["knee"]["pass"],
            "text": {
                "measured": knee_text(mk),
                "predicted": knee_text(pk),
                "error": pct(e),
                "absError": pct(abs(e) if e is not None else None, False),
            },
        },
        "spread": spread,
        "spreadExceedsError": bool(spread and e is not None and spread["plusMinus"] > abs(e)),
        "p95BelowKnee": {
            "steps": b["steps"],
            "within": b["steps"] - len(k["p95"]["misses"]),
            "misses": k["p95"]["misses"],
            "pass": k["p95"]["pass"],
            "medianAbsError": b["medianAbsP95Error"],
            "maxAbsError": b["maxAbsP95Error"],
            "worst": step_json(worst, True) if worst else None,
            "text": {
                "medianAbsError": pct(b["medianAbsP95Error"], False),
                "maxAbsError": pct(b["maxAbsP95Error"], False),
            },
        },
        "lowLoad": [low_load_json(r, c["predictedKneeFine"]) for r in low_load_rows(c)],
        "steps": [step_json(r, id(r) in below_ids) for r in c["rows"]],
        "resources": resources_json(c),
        "environment": environment_json(exp, env),
    }


def history_json(hist: Mapping[str, Sequence[Mapping[str, Any]]]) -> dict[str, list[Any]]:
    """``history`` in the page's order, each run with the history table's text."""
    return {
        exp: [
            {
                **r,
                "text": {
                    "measuredKnee": knee_text(r["measuredKnee"]),
                    "predictedKnee": knee_text(r["predictedKnee"]),
                    "kneeError": pct(r["kneeError"]),
                },
            }
            for r in hist[exp]
        ]
        for exp in ORDER
        if exp in hist
    }


def accuracy(results: Path = RESULTS) -> dict[str, Any] | None:
    """Every number of docs/validation.md's generated blocks, as data, or None without
    results."""
    hist = history(results)
    exps = [x for exp in ORDER if (x := experiment_json(exp, hist.get(exp, []), results))]
    if not exps:
        return None
    errors = [x["knee"]["error"] for x in exps if x["knee"]["error"] is not None]
    lo, hi = (min(errors), max(errors)) if errors else (None, None)
    all_runs = [r for exp in ORDER for r in hist.get(exp, [])]
    machines = list(dict.fromkeys(r["machine"] for r in all_runs))
    dates = sorted(r["calibratedAt"][:10] for r in all_runs if r["calibratedAt"])
    return {
        "generatedBy": "benchmarks/report.py --json, from benchmarks/results",
        "objective": exps[0]["objective"],
        "thresholds": {
            "kneeError": KNEE_TOLERANCE,
            "p95Error": P95_TOLERANCE,
            "p95Ms": P95_TOLERANCE_MS,
            "lowLoadFraction": LOW_LOAD_FRACTION,
        },
        "summary": {
            "experiments": len(exps),
            "runs": len(all_runs),
            "firstDate": dates[0] if dates else None,
            "latestDate": max(x["date"] for x in exps),
            "machines": machines,
            "kneeErrorMin": lo,
            "kneeErrorMax": hi,
            "kneeErrorRangeText": f"{pct(lo)} to {pct(hi)}",
            "kneeWithinTolerance": [x["id"] for x in exps if x["knee"]["withinTolerance"]],
            "p95WithinTolerance": [x["id"] for x in exps if x["p95BelowKnee"]["pass"]],
            "validated": [x["id"] for x in exps if x["tier"] == "validated"],
            "withSpread": [x["id"] for x in exps if x["spread"]],
            "spreadExceedsError": [x["id"] for x in exps if x["spreadExceedsError"]],
            "inSample": [x["id"] for x in exps if x["inSample"]],
            "technologies": technology_status({x["id"]: x["criteria"]["all"] for x in exps}),
        },
        "experiments": exps,
        "history": history_json(hist),
    }


def accuracy_text(results: Path = RESULTS) -> str:
    """``accuracy`` as the bytes written to accuracy.json."""
    return json.dumps(accuracy(results), indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--json",
        action="store_true",
        help=f"also write {JSON_OUT.relative_to(ROOT).as_posix()} and its copy "
        f"{WEB_JSON.relative_to(ROOT).as_posix()}",
    )
    args = ap.parse_args(argv)
    text, charts = render(DOC.read_text(encoding="utf-8"))
    DOC.write_text(text, encoding="utf-8", newline="\n")
    CHARTS.mkdir(parents=True, exist_ok=True)
    for name, svg in charts.items():
        (CHARTS / name).write_text(svg, encoding="utf-8", newline="\n")
    print(f"wrote {DOC.relative_to(ROOT).as_posix()} and {len(charts)} charts")
    if args.json:
        data = accuracy_text()
        for path in (JSON_OUT, WEB_JSON):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(data, encoding="utf-8", newline="\n")
            print(f"wrote {path.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
