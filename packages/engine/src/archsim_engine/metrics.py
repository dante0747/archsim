"""Bounded-memory metric primitives.

* ``Counter``          - event counts per time bucket, plus totals inside the measurement window
* ``Gauge``            - time-weighted level (queue depth, busy workers) integrated exactly
* ``LatencyHistogram`` - log-bucketed histogram (about 1% relative precision) for percentiles

Memory is bounded by the number of time buckets and histogram bins, never by the number of
simulated requests. All times are in milliseconds of simulated time.
"""

from __future__ import annotations

import math
from collections.abc import Iterable

NICE_BUCKETS_MS = (100, 250, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000)
MAX_BUCKETS = 90  # target number of buckets across the arrival window
MAX_TOTAL_BUCKETS = 1200  # hard cap including the drain period


def choose_bucket_ms(window_ms: float, total_ms: float | None = None) -> int:
    """Smallest 'nice' bucket width giving at most ``MAX_BUCKETS`` buckets over ``window_ms``
    and at most ``MAX_TOTAL_BUCKETS`` over ``total_ms``."""
    total = total_ms if total_ms is not None else window_ms
    for b in NICE_BUCKETS_MS:
        if window_ms / b <= MAX_BUCKETS and total / b <= MAX_TOTAL_BUCKETS:
            return b
    return NICE_BUCKETS_MS[-1]


class Timeline:
    """Fixed-width time buckets covering ``[0, end_ms]`` and the measurement window."""

    __slots__ = ("bucket_ms", "end_ms", "n", "window_end", "window_start")

    def __init__(self, end_ms: float, window_start: float, window_end: float) -> None:
        self.end_ms = end_ms
        self.bucket_ms = choose_bucket_ms(window_end, end_ms)
        self.n = max(1, math.ceil(end_ms / self.bucket_ms))
        self.window_start = window_start
        self.window_end = window_end

    def index(self, t: float) -> int:
        i = int(t // self.bucket_ms)
        return i if i < self.n else self.n - 1

    def in_window(self, t: float) -> bool:
        return self.window_start <= t < self.window_end

    @property
    def window_ms(self) -> float:
        return self.window_end - self.window_start


class Counter:
    __slots__ = ("buckets", "tl", "total", "window")

    def __init__(self, tl: Timeline) -> None:
        self.tl = tl
        self.buckets = [0] * tl.n
        self.total = 0
        self.window = 0

    def inc(self, t: float, n: int = 1) -> None:
        tl = self.tl
        i = int(t // tl.bucket_ms)
        if i >= tl.n:
            i = tl.n - 1
        self.buckets[i] += n
        self.total += n
        if tl.window_start <= t < tl.window_end:
            self.window += n

    def rate_series(self) -> list[float]:
        """Per-bucket rate in events/second."""
        scale = 1000.0 / self.tl.bucket_ms
        return [c * scale for c in self.buckets]

    def window_rate(self) -> float:
        w = self.tl.window_ms
        return self.window * 1000.0 / w if w > 0 else 0.0


class Gauge:
    """Piecewise-constant level integrated over time.

    ``area[i]`` is the integral of the level over bucket ``i`` (level x ms), so the bucket mean is
    ``area[i] / bucket_ms``. The measurement-window integral is tracked exactly.
    """

    __slots__ = ("area", "last_t", "maxes", "peak_window", "tl", "value", "window_area")

    def __init__(self, tl: Timeline) -> None:
        self.tl = tl
        self.value = 0
        self.last_t = 0.0
        self.area = [0.0] * tl.n
        self.maxes = [0] * tl.n
        self.window_area = 0.0
        self.peak_window = 0

    def _advance(self, t: float) -> None:
        last = self.last_t
        if t <= last:
            return
        v = self.value
        if v:
            tl = self.tl
            # exact window integral
            lo = last if last > tl.window_start else tl.window_start
            hi = t if t < tl.window_end else tl.window_end
            if hi > lo:
                self.window_area += v * (hi - lo)
                if v > self.peak_window:
                    self.peak_window = v
            # per-bucket integral (split across bucket boundaries)
            b = tl.bucket_ms
            i = tl.index(last)
            cur = last
            while cur < t:
                edge = float((i + 1) * b)
                if i >= tl.n - 1:
                    edge = t
                seg_end = t if t < edge else edge
                self.area[i] += v * (seg_end - cur)
                if v > self.maxes[i]:
                    self.maxes[i] = v
                cur = seg_end
                i += 1
        self.last_t = t

    def add(self, delta: int, t: float) -> None:
        if t > self.last_t:
            self._advance(t)
        self.value += delta
        v = self.value
        if delta > 0:
            tl = self.tl
            i = int(t // tl.bucket_ms)
            if i >= tl.n:
                i = tl.n - 1
            if v > self.maxes[i]:
                self.maxes[i] = v
            if v > self.peak_window and tl.window_start <= t < tl.window_end:
                self.peak_window = v

    def finalize(self, t_end: float) -> None:
        self._advance(t_end)

    def mean_series(self) -> list[float]:
        b = self.tl.bucket_ms
        return [a / b for a in self.area]

    def max_series(self) -> list[int]:
        return list(self.maxes)

    def window_mean(self) -> float:
        w = self.tl.window_ms
        return self.window_area / w if w > 0 else 0.0


class LatencyHistogram:
    """Log-bucketed histogram. Bin ``k`` covers ``[MIN * G^k, MIN * G^(k+1))`` with G = 1.02,
    giving roughly 1% relative error on reported percentiles."""

    MIN_MS = 0.001
    GROWTH = 1.02
    _LOG_G = math.log(GROWTH)

    __slots__ = ("bins", "count", "max", "min", "sum")

    def __init__(self) -> None:
        self.bins: dict[int, int] = {}
        self.count = 0
        self.sum = 0.0
        self.min = math.inf
        self.max = 0.0

    def record(self, v: float) -> None:
        k = -1 if v < self.MIN_MS else int(math.log(v / self.MIN_MS) / self._LOG_G)
        self.bins[k] = self.bins.get(k, 0) + 1
        self.count += 1
        self.sum += v
        if v < self.min:
            self.min = v
        if v > self.max:
            self.max = v

    def merge(self, other: LatencyHistogram) -> None:
        for k, c in other.bins.items():
            self.bins[k] = self.bins.get(k, 0) + c
        self.count += other.count
        self.sum += other.sum
        self.min = min(self.min, other.min)
        self.max = max(self.max, other.max)

    def _bin_value(self, k: int) -> float:
        if k < 0:
            return 0.0
        # geometric midpoint of the bin
        return self.MIN_MS * self.GROWTH ** (k + 0.5)

    def percentile(self, p: float) -> float | None:
        """Value at percentile ``p`` in [0, 100]; ``None`` when empty."""
        if self.count == 0:
            return None
        if p >= 100:
            return self.max
        rank = p / 100.0 * self.count
        seen = 0
        for k in sorted(self.bins):
            seen += self.bins[k]
            if seen >= rank and seen > 0:
                return min(max(self._bin_value(k), self.min), self.max)
        return self.max

    def mean(self) -> float | None:
        return self.sum / self.count if self.count else None

    def summary(self) -> dict[str, float | int | None]:
        return {
            "count": self.count,
            "mean": _round(self.mean()),
            "p50": _round(self.percentile(50)),
            "p90": _round(self.percentile(90)),
            "p95": _round(self.percentile(95)),
            "p99": _round(self.percentile(99)),
            "max": _round(self.max if self.count else None),
        }


class BucketedHistograms:
    """One latency histogram per time bucket (for percentile time series)."""

    __slots__ = ("hists", "tl")

    def __init__(self, tl: Timeline) -> None:
        self.tl = tl
        self.hists: list[LatencyHistogram | None] = [None] * tl.n

    def record(self, t: float, v: float) -> None:
        tl = self.tl
        i = int(t // tl.bucket_ms)
        if i >= tl.n:
            i = tl.n - 1
        h = self.hists[i]
        if h is None:
            h = self.hists[i] = LatencyHistogram()
        h.record(v)

    def percentile_series(self, p: float) -> list[float | None]:
        return [_round(h.percentile(p)) if h else None for h in self.hists]

    def window(self) -> LatencyHistogram:
        """Merged histogram of the buckets inside the measurement window."""
        tl = self.tl
        out = LatencyHistogram()
        i0 = math.ceil(tl.window_start / tl.bucket_ms)
        i1 = max(i0 + 1, math.floor(tl.window_end / tl.bucket_ms))
        for h in self.hists[i0:i1]:
            if h is not None:
                out.merge(h)
        return out


class MeanAccumulator:
    """Running mean per time bucket and over the measurement window."""

    __slots__ = ("counts", "sums", "tl", "window_count", "window_sum")

    def __init__(self, tl: Timeline) -> None:
        self.tl = tl
        self.sums = [0.0] * tl.n
        self.counts = [0] * tl.n
        self.window_sum = 0.0
        self.window_count = 0

    def record(self, t: float, v: float) -> None:
        i = self.tl.index(t)
        self.sums[i] += v
        self.counts[i] += 1
        if self.tl.window_start <= t < self.tl.window_end:
            self.window_sum += v
            self.window_count += 1

    def series(self) -> list[float | None]:
        return [_round(s / c) if c else None for s, c in zip(self.sums, self.counts, strict=True)]

    def window_mean(self) -> float | None:
        return self.window_sum / self.window_count if self.window_count else None


def _round(v: float | None, digits: int = 4) -> float | None:
    if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
        return None
    return round(v, digits)


def round_series(values: Iterable[float | None], digits: int = 4) -> list[float | None]:
    return [_round(v, digits) for v in values]


def safe_ratio(num: float, den: float) -> float | None:
    return num / den if den else None
