"""Write the simulator's metric primitives on recorded inputs for the Java engine, or check them.

A run's report is built from a few metric primitives (``archsim_engine.metrics``): time buckets
(``Timeline``), counters, time-weighted gauges, log-bucketed latency histograms, histograms per
bucket and running means. This script feeds each a recorded sequence of operations, drawn from a
seeded generator over times that hit bucket edges, the measurement window's edges and the run's
end, and writes what the frozen engine (0.16.0) reports from them: every series, total and
summary. ``SeriesCasesTests`` and ``HistogramCasesTests`` replay each sequence on the Java
primitives.

Floats are written as Python's ``float.hex()``, ``None`` as ``null``.

Usage:
    uv run python scripts/export_metrics_cases.py           # write the cases
    uv run python scripts/export_metrics_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from pathlib import Path
from typing import Any

from archsim_engine.metrics import (
    BucketedHistograms,
    Counter,
    Gauge,
    LatencyHistogram,
    LatencyWithFailures,
    MeanAccumulator,
    Timeline,
    choose_bucket_ms,
    round_series,
)

GENERATOR = "scripts/export_metrics_cases.py (archsim_engine 0.16.0)"
OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/metrics/cases.json"
)
Json = dict[str, Any]

TIMELINES = [
    # (end_ms, window_start, window_end): a short run, a long one with a drain, an hour, one
    # whose drain is so long that the cap on all buckets sets their width, one whose window is
    # empty, and one with odd edges
    (2_500.0, 500.0, 2_000.0),
    (100_000.0, 10_000.0, 60_000.0),
    (7_200_000.0, 60_000.0, 3_600_000.0),
    (1_000_000.0, 0.0, 9_000.0),
    (1_000.0, 0.0, 0.0),
    (12_345.6, 1_000.3, 9_999.9),
]
WINDOWS = [
    0.0,
    1.0,
    9_000.0,
    9_001.0,
    22_500.0,
    45_000.0,
    60_000.0,
    600_000.0,
    6_000_000.0,
    108_000_000.0,
    1e9,
]


def fl(value: float | None) -> str | None:
    """A float as Python writes it in hex."""
    return None if value is None else float(value).hex()


def spelled(value: float | None) -> str | None:
    """A float as hex, a non-finite one as the golden files spell it (``NaN``, ``Infinity``)."""
    if value is None:
        return None
    if math.isnan(value):
        return "NaN"
    if math.isinf(value):
        return "Infinity" if value > 0 else "-Infinity"
    return value.hex()


def fls(values: list[Any]) -> list[Any]:
    """Floats as hex, ints and None as they are."""
    return [v if v is None or isinstance(v, int) else fl(v) for v in values]


def timeline(end: float, start: float, stop: float) -> Json:
    """A timeline's buckets, and the index of times around its edges."""
    tl = Timeline(end, start, stop)
    probes = sorted(
        {
            0.0,
            start,
            stop,
            end,
            end * 2,
            tl.bucket_ms * 1.0,
            tl.bucket_ms - 1e-9,
            (tl.n - 1) * tl.bucket_ms + 0.5,
            start - 1e-9,
            stop - 1e-9,
        }
    )
    return {
        "endMs": fl(end),
        "windowStart": fl(start),
        "windowEnd": fl(stop),
        "bucketMs": tl.bucket_ms,
        "n": tl.n,
        "windowMs": fl(tl.window_ms),
        "index": [[fl(t), tl.index(t)] for t in probes if t >= 0],
        "inWindow": [[fl(t), tl.in_window(t)] for t in probes],
    }


def times(rng: random.Random, tl: Timeline, count: int) -> list[float]:
    """Rising times over the run: random, plus each bucket edge and the window's edges, and
    one in four repeated, as a simulation changes a level twice at one moment."""
    picks = {rng.uniform(0, tl.end_ms) for _ in range(count)}
    picks |= {float(i * tl.bucket_ms) for i in range(min(tl.n, 40))}
    picks |= {tl.window_start, tl.window_end, tl.end_ms}
    rising = sorted(t for t in picks if t <= tl.end_ms)
    return sorted(rising + [t for t in rising if rng.random() < 0.25])


def counter(rng: random.Random, tl: Timeline) -> Json:
    """A counter's increments and what it reports."""
    c = Counter(tl)
    ops = [[fl(t), rng.choice([1, 1, 1, 2, 5])] for t in times(rng, tl, 150)]
    for t, n in ops:
        c.inc(float.fromhex(t), n)
    return {
        "ops": ops,
        "buckets": c.buckets,
        "total": c.total,
        "window": c.window,
        "rateSeries": fls(c.rate_series()),
        "windowRate": fl(c.window_rate()),
    }


def gauge(rng: random.Random, tl: Timeline) -> Json:
    """A gauge's changes and what it reports; it never goes below zero."""
    g = Gauge(tl)
    level = 0
    ops = []
    for t in times(rng, tl, 150):
        delta = rng.choice([1, 1, 2, -1, -1]) if level > 1 else rng.choice([1, 3])
        level += delta
        ops.append([fl(t), delta])
        g.add(delta, t)
    end = tl.end_ms
    g.finalize(end)
    return {
        "ops": ops,
        "finalizeAt": fl(end),
        "value": g.value,
        "area": fls(g.area),
        "maxes": g.maxes,
        "windowArea": fl(g.window_area),
        "peakWindow": g.peak_window,
        "meanSeries": fls(g.mean_series()),
        "maxSeries": g.max_series(),
        "windowMean": fl(g.window_mean()),
    }


def summary(s: dict[str, Any]) -> Json:
    return {k: (v if v is None or isinstance(v, int) else fl(v)) for k, v in s.items()}


def latencies(rng: random.Random, count: int) -> list[float]:
    """Latencies from sub-microsecond to minutes, with exact repeats and bin edges."""
    values = [rng.lognormvariate(3.0, 1.5) for _ in range(count)]
    values += [0.0, 0.0005, 0.001, 0.00102, 0.001 * 1.02**50, 1.0, 1.0, 600_000.0]
    rng.shuffle(values)
    return values


def histogram(rng: random.Random) -> Json:
    """A latency histogram's records, percentiles and summary, and a merge."""
    h = LatencyHistogram()
    values = latencies(rng, 500)
    for v in values:
        h.record(v)
    other = LatencyHistogram()
    extra = latencies(rng, 50)
    for v in extra:
        other.record(v)
    merged = LatencyHistogram()
    merged.merge(h)
    merged.merge(other)
    empty = LatencyHistogram()
    return {
        "values": fls(values),
        "extra": fls(extra),
        "bins": [[k, c] for k, c in h.bins.items()],
        "percentiles": [
            [p, fl(h.percentile(p))] for p in (0, 0.1, 1, 25, 50, 90, 95, 99, 99.9, 100, 150)
        ],
        "mean": fl(h.mean()),
        "summary": summary(h.summary()),
        "merged": summary(merged.summary()),
        "empty": summary(empty.summary()),
    }


def with_failures(rng: random.Random) -> Json:
    """Successes and failures, failures at or past their timeout."""
    lw = LatencyWithFailures()
    ops = []
    for v in latencies(rng, 300):
        if rng.random() < 0.2:
            timeout = rng.choice([1000.0, 2000.0, 5000.0])
            failed = rng.choice([v, timeout, timeout + 1e-7, timeout * 1.5])
            ops.append(["failure", fl(failed), fl(timeout)])
            lw.failure(failed, timeout)
        else:
            ops.append(["success", fl(v), None])
            lw.success(v)
    only_failures = LatencyWithFailures()
    only_failures.failure(1000.0, 1000.0)
    only_failures.failure(3000.0, 2000.0)
    return {
        "ops": ops,
        "count": lw.count,
        "percentiles": [[p, fl(lw.percentile(p))] for p in (0, 1, 50, 90, 95, 99, 100)],
        "summary": summary(lw.summary()),
        "onlyFailures": summary(only_failures.summary()),
        "empty": summary(LatencyWithFailures().summary()),
    }


def bucketed(rng: random.Random, tl: Timeline) -> Json:
    """Histograms per time bucket, their percentile series and the window's merged one."""
    b = BucketedHistograms(tl)
    ops = [[fl(t), fl(rng.lognormvariate(2.0, 1.0))] for t in times(rng, tl, 200)]
    for t, v in ops:
        b.record(float.fromhex(t), float.fromhex(v))
    return {
        "ops": ops,
        "p50": fls(b.percentile_series(50)),
        "p95": fls(b.percentile_series(95)),
        "window": summary(b.window().summary()),
    }


def means(rng: random.Random, tl: Timeline) -> Json:
    """A running mean per bucket and over the window."""
    m = MeanAccumulator(tl)
    ops = [[fl(t), fl(rng.uniform(0, 50))] for t in times(rng, tl, 150)]
    for t, v in ops:
        m.record(float.fromhex(t), float.fromhex(v))
    return {"ops": ops, "series": fls(m.series()), "windowMean": fl(m.window_mean())}


def helpers() -> Json:
    """Bucket widths, rounding and ratios."""
    rounded = [
        1.23456789,
        2.00005,
        2.00015,
        -0.00004999,
        1e-9,
        123456.78955,
        float("nan"),
        float("inf"),
        None,
    ]
    return {
        "chooseBucketMs": [
            [fl(w), fl(t), choose_bucket_ms(w, t)]
            for w in WINDOWS
            for t in (None, w * 3, w * 50)
            if t is None or t >= w
        ],
        "roundSeries": [
            [spelled(v), fl(r)] for v, r in zip(rounded, round_series(rounded), strict=True)
        ],
    }


def export() -> str:
    rng = random.Random(20261108)
    cases: Json = {
        "generator": GENERATOR,
        "helpers": helpers(),
        "timelines": [],
        "histogram": histogram(rng),
        "withFailures": with_failures(rng),
    }
    for end, start, stop in TIMELINES:
        tl = Timeline(end, start, stop)
        cases["timelines"].append(
            {
                "timeline": timeline(end, start, stop),
                "counter": counter(rng, tl),
                "gauge": gauge(rng, tl),
                "bucketed": bucketed(rng, tl),
                "means": means(rng, tl),
            }
        )
    return json.dumps(cases, indent=1) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_metrics_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's metrics")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
