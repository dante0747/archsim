"""Fitting measured latencies to ArchSim distributions.

Histograms (Prometheus ``*_bucket`` series: cumulative counts per upper bound ``le``) give
quantiles by linear interpolation inside the bucket, as ``histogram_quantile`` does. Samples
(OpenTelemetry spans) give quantiles directly. Either is fitted to a lognormal (median and p95
match exactly) or kept as an empirical quantile table.

The fit error is the largest relative error between fitted and observed quantiles. For samples
it is checked at ``CHECK_QUANTILES``. For histograms it is checked where the data is exact, at
the bucket bounds (the share of observations at or below each bound is counted, not
interpolated), for the bounds between the observed p50 and p99. An empirical table built from a
histogram passes through every bucket bound by construction, so there is nothing independent to
check it against: its fit error is ``None`` and a note gives the bucket widths instead.
Observations above the highest finite bound (the +Inf bucket) are reported in the fit's notes.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass
from statistics import NormalDist
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
FULL_EPS = 1e-9
"""Relative slack when deciding that a cumulative count has reached the total (rates averaged
over a range carry float noise)."""


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

    def beyond_share(self) -> float:
        """Share of observations above the highest finite bound (only the +Inf bucket holds
        them, so how far above is unknown)."""
        if self.total <= 0 or not self.bounds:
            return 0.0
        share = (self.total - self.cumulative[-1]) / self.total
        return share if share > FULL_EPS else 0.0

    def bucket_of(self, q: float) -> tuple[float, float] | None:
        """The bounds of the bucket holding the q-th quantile (None: the +Inf bucket)."""
        if self.total <= 0:
            return None
        rank, lower = q * self.total, 0.0
        for bound, count in zip(self.bounds, self.cumulative, strict=True):
            if count >= rank:
                return lower, bound
            lower = bound
        return None


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
    error: float | None
    """Largest relative error against the data; None when there is nothing independent to
    check against (an empirical table built from histogram buckets)."""
    observed: dict[float, float]
    count: float
    notes: tuple[str, ...] = ()


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
    notes: list[str] = []
    beyond = h.beyond_share()
    if beyond > 0:
        top = h.bounds[-1]
        notes.append(
            f"{beyond:.1%} of observations were above the highest bucket bound ({top:g} ms); "
            "a histogram does not say how far above"
            + (
                f", so the table spreads them between {top:g} and {min(MAX_SAMPLE_MS, top * 2):g} ms"
                if kind == "empirical"
                else f", so quantiles that fall there are taken as {top:g} ms"
            )
        )
    if kind == "empirical":
        fit = fit_quantiles(observed, kind, _histogram_table(h), h.total)
        widths = []
        for q in (0.5, 0.95, 0.99):
            bucket = h.bucket_of(q)
            if bucket is not None:
                widths.append(f"p{round(q * 100)} between {bucket[0]:g} and {bucket[1]:g} ms")
        notes.append(
            "an empirical table from histogram buckets passes through every bucket bound, so "
            "no fit error can be measured; inside a bucket it is only as precise as the bucket"
            + (f" ({', '.join(widths)})" if widths else "")
        )
        return Fit(fit.distribution, None, observed, h.total, tuple(notes))
    fit = fit_quantiles(observed, kind, None, h.total)
    at_bounds = _error_at_bounds(h, fit.distribution["median"], fit.distribution["p95"])
    return Fit(
        fit.distribution,
        at_bounds if at_bounds is not None else fit.error,
        observed,
        h.total,
        tuple(notes),
    )


def _error_at_bounds(h: Histogram, median: float, p95: float) -> float | None:
    """Largest relative error of a lognormal at the bucket bounds whose observed share lies
    between p50 and p99: where the fit puts that share of observations, against the bound the
    histogram counted them under. None when no bound lies in that range."""
    sigma = math.log(p95 / median) / Z[0.95] if p95 > median else 0.0
    normal = NormalDist()
    errs = []
    for bound, count in zip(h.bounds, h.cumulative, strict=True):
        share = count / h.total
        if 0.5 <= share <= 0.99 and bound > 0:
            fitted = median * math.exp(sigma * normal.inv_cdf(share))
            errs.append(abs(fitted - bound) / bound)
    return round(max(errs), 4) if errs else None


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
    """The histogram as a quantile table: (share at or below each bound, bound). It ends at the
    first bound whose cumulative count reaches the total (nothing was observed above it); only
    observations in the +Inf bucket extend it, up to twice the highest finite bound."""
    pts = [(0.0, 0.0)]
    for b, c in zip(h.bounds, h.cumulative, strict=True):
        p = c / h.total
        if p >= 1.0 - FULL_EPS:
            pts.append((1.0, b))
            return _monotone(pts)
        if p > pts[-1][0]:
            pts.append((p, b))
    pts.append((1.0, min(MAX_SAMPLE_MS, h.bounds[-1] * 2)))  # the +Inf bucket's share
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
