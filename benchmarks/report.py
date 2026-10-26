"""Rewrite the generated parts of docs/validation.md (and its charts) from benchmarks/results.

    uv run python benchmarks/report.py

Hand-written text in docs/validation.md is kept: only the blocks between
``<!-- BEGIN GENERATED: name -->`` and ``<!-- END GENERATED: name -->`` are replaced.
"""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
RESULTS = HERE / "results"
DOC = ROOT / "docs" / "validation.md"
CHARTS = ROOT / "docs" / "validation"
ORDER = ["pg-mixed", "pg-hot-row", "cache-aside", "kafka"]

PRED = "#e8710a"  # orange: ArchSim's prediction
MEAS = "#1a73e8"  # blue: measured
INK = "#888888"


def load(exp: str, name: str) -> dict[str, Any] | None:
    p = RESULTS / exp / f"{name}.json"
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


def knee_text(k: dict[str, Any]) -> str:
    if k.get("kneeRps") is not None:
        return rps(k["kneeRps"])
    if k.get("bound") == "above":
        return f"> {rps(k.get('aboveRps'))}"
    if k.get("bound") == "below":
        return f"< {rps(k.get('belowRps'))}"
    return "-"


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


def chart(cmp: dict[str, Any], path: Path) -> None:
    rows = cmp["rows"]
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
        cmp["objective"]["p95Ms"],
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
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(svg) + "\n", encoding="utf-8", newline="\n")


# ---------------------------------------------------------------------------------------------
# markdown
# ---------------------------------------------------------------------------------------------


def summary_block(cmps: list[dict[str, Any]]) -> str:
    lines = [
        "| Experiment | Knee measured | Knee predicted | Knee error | p95 error below the knee (median / worst) |",
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


def environment_block(env: dict[str, Any] | None) -> str:
    if not env:
        return "_No results yet._"
    imgs = env.get("images", {})
    return "\n".join(
        [
            f"- CPU: {env.get('cpu')}; Docker {env.get('docker')} with {env.get('dockerCpus')} CPUs and "
            f"{int(env.get('dockerMemory') or 0) / 2**30:.1f} GiB; {env.get('os')}.",
            f"- Images: {', '.join(f'{k} `{v}`' for k, v in imgs.items())}.",
            "- CPUs pinned per container (cpuset): service 1, PostgreSQL 2, Redis 1, Kafka 2, k6 2.",
        ]
    )


def experiment_block(exp: str) -> str:
    cal = load(exp, "calibration")
    pred = load(exp, "predictions")
    cmp_ = load(exp, "comparison")
    if not (cal and pred and cmp_):
        return f"_No results for {exp} yet: run `uv run python benchmarks/run.py`._"
    out = [
        f"**{cmp_['title']}.** Calibrated {cal['at']}, predicted {pred['at']} (engine {pred['engineVersion']}), "
        f"measured afterwards. Objective: p95 <= {cmp_['objective']['p95Ms']:g} ms with >= "
        f"{cmp_['objective']['minSuccess'] * 100:g}% success.",
        "",
        f"![{exp}: predicted and measured p95 and throughput](validation/{exp}.svg)",
        "",
        "Model inputs from the calibration run (50 req/s):",
        "",
        "| Input | Value | How it was measured |",
        "| --- | --- | --- |",
    ]
    for name, item in cal["inputs"].items():
        if not isinstance(item, dict) or "value" not in item:
            continue
        v = item["value"]
        if isinstance(v, dict) and v.get("kind") == "lognormal":
            val = f"lognormal, median {v['median']:g} ms, p95 {v['p95']:g} ms"
        elif isinstance(v, dict) and v.get("kind") == "exponential":
            val = f"exponential, mean {v['mean']:g} ms"
        else:
            val = f"{v:g}" + (" ms" if name.endswith("Ms") else "")
        out.append(f"| `{name}` | {val} | {item['how']} |")
    low = cal["inputs"].get("measuredLowLoad", {})
    out += [
        "",
        f"Measured at 50 req/s: p50 {ms(low.get('p50'))} ms, p95 {ms(low.get('p95'))} ms.",
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
        line = (
            f"| {rps(r['offeredRps'])} | {ms(m['p50'])} / {ms(p['p50'])} | {ms(m['p95'])} / {ms(p['p95'])} | "
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
    mk, pk = cmp_["measuredKnee"], cmp_["predictedKnee"]
    out += [
        "",
        f"Knee: measured {knee_text(mk)} req/s, predicted {knee_text(pk)} req/s on the same steps "
        f"({pct(cmp_['kneeError'])}); ArchSim's own search before measuring put it at "
        f"{rps(cmp_['predictedKneeFine'])} req/s. Raw data: `benchmarks/results/{exp}/`.",
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


def main() -> int:
    cmps = [c for c in (load(e, "comparison") for e in ORDER) if c]
    text = DOC.read_text(encoding="utf-8")
    text = replace_block(text, "summary", summary_block(cmps) if cmps else "_No results yet._")
    env = next((load(e, "environment") for e in ORDER if load(e, "environment")), None)
    text = replace_block(text, "environment", environment_block(env))
    for exp in ORDER:
        text = replace_block(text, exp, experiment_block(exp))
        c = load(exp, "comparison")
        if c:
            chart(c, CHARTS / f"{exp}.svg")
    DOC.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {DOC.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
