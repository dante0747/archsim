"""Fitting measured latencies to ArchSim distributions.

Histograms (Prometheus ``*_bucket`` series: cumulative counts per upper bound ``le``) give
quantiles by linear interpolation inside the bucket, as ``histogram_quantile`` does. Samples
(OpenTelemetry spans) give quantiles directly. Either is fitted to a lognormal (median and p95
match exactly) or kept as an empirical quantile table; the fit error is the largest relative
error between the fitted and the observed quantiles at ``CHECK_QUANTILES``.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass
from typing import Any, Literal

from archsim_engine.distributions import MAX_SAMPLE_MS

Z = {
    0.5: 0.0,
    0.75: 0.6744897501960817,
    0.9: 1.2815515655446004,
    0.95: 1.6448536269514722,
    0.99: 2.3263478740408408,
}
CHECK_QUANTILES = (0.5, 0.75, 0.9, 0.95, 0.99)
FitKind = Literal["lognormal", "empirical"]


@dataclass(frozen=True)
class Histogram:
    """Cumulative counts (or rates) per finite upper bound, plus the total (the +Inf bucket)."""

    bounds: list[float]  # finite upper bounds, ascending, in ms
    cumulative: list[float]  # cumulative count at each bound
    total: float

    @classmethod
    def from_buckets(cls, buckets: dict[float, float], scale: float = 1.0) -> Histogram:
        """``buckets``: le -> cumulative count (``math.inf`` for +Inf); ``scale`` converts le to ms."""
        finite = sorted((le, c) for le, c in buckets.items() if math.isfinite(le))
        total = buckets.get(math.inf, finite[-1][1] if finite else 0.0)
        bounds, cum = [], []
        running = 0.0
        for le, c in finite:
            running = max(running, c)  # counts never fall (guards against scrape jitter)
            bounds.append(le * scale)
            cum.append(running)
        return cls(bounds, cum, max(total, running))

    def quantile(self, q: float) -> float | None:
        """histogram_quantile: linear inside the bucket holding the q-th observation."""
        if self.total <= 0 or not self.bounds:
            return None
        rank = q * self.total
        lower_bound, lower_count = 0.0, 0.0
        for bound, count in zip(self.bounds, self.cumulative, strict=True):
            if count >= rank:
                if count == lower_count:
                    return bound
                return lower_bound + (bound - lower_bound) * (rank - lower_count) / (
                    count - lower_count
                )
            lower_bound, lower_count = bound, count
        return self.bounds[-1]  # in the +Inf bucket: the highest finite bound, like Prometheus

    def mean_upper(self) -> float:
        """An estimate of the mean from bucket midpoints (the +Inf bucket at the last bound)."""
        if self.total <= 0:
            return 0.0
        total, prev_b, prev_c = 0.0, 0.0, 0.0
        for b, c in zip(self.bounds, self.cumulative, strict=True):
            total += (c - prev_c) * (prev_b + b) / 2
            prev_b, prev_c = b, c
        total += (self.total - prev_c) * prev_b
        return total / self.total


def sample_quantile(sorted_values: list[float], q: float) -> float:
    """Linear-interpolated quantile of sorted samples."""
    if not sorted_values:
        raise ValueError("no samples")
    pos = q * (len(sorted_values) - 1)
    lo = math.floor(pos)
    hi = min(lo + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (pos - lo)


def lognormal_quantile(median: float, p95: float, q: float) -> float:
    sigma = math.log(p95 / median) / Z[0.95] if p95 > median else 0.0
    return median * math.exp(sigma * Z[q])


def fit_error(observed: dict[float, float], fitted: dict[float, float]) -> float:
    errs = [abs(fitted[q] - observed[q]) / observed[q] for q in observed if observed[q] > 0]
    return round(max(errs), 4) if errs else 0.0


@dataclass(frozen=True)
class Fit:
    distribution: dict[str, Any]
    error: float
    observed: dict[float, float]
    count: float


def fit_quantiles(
    observed: dict[float, float],
    kind: FitKind = "lognormal",
    table: list[tuple[float, float]] | None = None,
    count: float = 0.0,
) -> Fit:
    """Fit observed quantiles (q -> ms; must include 0.5 and 0.95)."""
    median = max(observed[0.5], 1e-6)
    p95 = max(observed[0.95], median * 1.0001)
    if kind == "lognormal" or not table:
        dist: dict[str, Any] = {
            "kind": "lognormal",
            "median": round(median, 6),
            "p95": round(p95, 6),
        }
        fitted = {q: lognormal_quantile(median, p95, q) for q in observed}
    else:
        dist = {"kind": "empirical", "quantiles": [[round(p, 6), round(v, 6)] for p, v in table]}
        fitted = {q: _table_quantile(table, q) for q in observed}
    return Fit(dist, fit_error(observed, fitted), observed, count)


def fit_histogram(h: Histogram, kind: FitKind = "lognormal") -> Fit:
    observed = {q: v for q in CHECK_QUANTILES if (v := h.quantile(q)) is not None}
    if 0.5 not in observed or 0.95 not in observed:
        raise ValueError("the histogram has no observations")
    return fit_quantiles(
        observed, kind, _histogram_table(h) if kind == "empirical" else None, h.total
    )


def fit_samples(samples: list[float], kind: FitKind = "lognormal") -> Fit:
    if not samples:
        raise ValueError("no samples")
    s = sorted(samples)
    observed = {q: sample_quantile(s, q) for q in CHECK_QUANTILES}
    table = None
    if kind == "empirical":
        qs = [i / 50 for i in range(50)] + [0.99, 0.995, 0.999, 1.0]  # a denser tail
        table = _monotone([(q, sample_quantile(s, q)) for q in qs])
    return fit_quantiles(observed, kind, table, len(s))


def _histogram_table(h: Histogram) -> list[tuple[float, float]]:
    pts = [(0.0, 0.0)]
    for b, c in zip(h.bounds, h.cumulative, strict=True):
        p = c / h.total
        if p > pts[-1][0] and p < 1.0:
            pts.append((p, b))
    last = h.bounds[-1]
    beyond = h.total > h.cumulative[-1]
    pts.append(
        (1.0, min(MAX_SAMPLE_MS, last * 2 if beyond else last))
    )  # +Inf mass: up to 2x the last bound
    return _monotone(pts)


def _monotone(pts: list[tuple[float, float]]) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    for p, v in pts:
        if out and p <= out[-1][0]:
            continue
        out.append((p, max(v, out[-1][1]) if out else v))
    if out[0][0] != 0.0:
        out.insert(0, (0.0, 0.0))
    if out[-1][0] != 1.0:
        out.append((1.0, out[-1][1]))
    return out


def _table_quantile(table: list[tuple[float, float]], q: float) -> float:
    for (p0, v0), (p1, v1) in itertools.pairwise(table):
        if p0 <= q <= p1:
            return v0 + (v1 - v0) * (q - p0) / (p1 - p0) if p1 > p0 else v1
    return table[-1][1]
