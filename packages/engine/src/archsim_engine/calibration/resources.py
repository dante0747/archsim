"""Fitting CPU and memory inputs from what a part used over a range of load.

Pure functions over (rate, measurement) points, used by calibration from Prometheus and by the
benchmark tests on measured stacks.

**CPU per request → ``cpuShare``.** CPU per request is the rate of CPU seconds over the rate of
requests, summed over the range (mean cores ÷ mean request rate): the busiest times weigh most,
which is where a part is sized. ``cpuShare`` is that over the mean service time, at most 1. The
fit error is the largest relative error of the fitted CPU at and above the median rate.

**CPU per request against load → ``serviceTimeByLoad``.** On real stacks CPU per request falls as
load rises (requests share event-loop wake-ups, system calls and driver work). ``fit_load_curve``
sorts the points by the rate one replica gets into up to ``MAX_BINS`` bins of equal count
(quantiles), takes each bin's CPU per request (its cores over its rate) and fits the closest curve
that never rises with rate (pool adjacent violators): a rise is noise or contention, which a
falling cost must not be extrapolated from. Its factors are relative to the CPU per request over
the whole range, the one ``cpuShare`` is fitted from, so the table rescales the calibrated
service time around it.

**Working set → memory at idle, per req/s and per request held.** The working set per replica is
fitted by non-negative least squares against what the mapping gives: the request rate, requests
held, or both, plus a constant (memory at idle). No term may be negative. Garbage-collected
runtimes grow with the request rate (garbage made between collections), not with requests held;
a fit against requests held then extrapolates badly near saturation, where requests held climb
and the heap does not. The fit explains the whole working set, so a term it was not given is
zero (memory per request held: its floor, ``MIN_PER_REQUEST_MB``): otherwise the calibrated
model would count the memory of requests held twice. The fit error is the largest relative error
at and above the median rate (or requests held, when no rate is given).
"""

from __future__ import annotations

import itertools
import math
from collections.abc import Sequence
from dataclasses import dataclass, field

MIN_SPREAD = 1.5
"""The busiest bin's rate must be at least this multiple of the quietest's to fit a curve."""
MAX_BINS = 6
"""Most bins by rate a CPU-by-load curve has (``fit_load_curve``)."""
MIN_CURVE_CHANGE = 0.10
"""A fitted CPU-by-load curve is set as ``serviceTimeByLoad`` only when its factors span more
than this share (less is within the noise of CPU counters)."""
MIN_PER_REQUEST_MB = 0.01
"""Smallest memory per request held calibration sets (the setting must be above zero): requests
held that add no measurable memory get this (10 KiB, about a socket's buffers)."""


@dataclass(frozen=True)
class CpuFit:
    share: float
    """cpuShare: CPU per request over the mean service time, at most 1."""
    cpu_ms: float
    """CPU per request (ms), rate-weighted over the range."""
    service_ms: float
    capped: bool
    """CPU per request exceeded the service time: the share is 1 and the model under-counts CPU."""
    error: float | None
    curve: LoadCurve | None
    """CPU per request against the rate per replica (``fit_load_curve``), or None."""
    points: int
    notes: tuple[str, ...] = ()

    def predict(self, rate: float) -> float:
        """Cores the fitted model uses at ``rate`` req/s."""
        return rate * self.service_ms * self.share / 1000.0


def _busy_half(xs: Sequence[float]) -> list[int]:
    """Indices of the points at or above the median of ``xs``."""
    if not xs:
        return []
    ordered = sorted(xs)
    n = len(ordered)
    median = ordered[n // 2] if n % 2 else (ordered[n // 2 - 1] + ordered[n // 2]) / 2
    return [i for i, x in enumerate(xs) if x >= median]


def _max_rel_error(pairs: list[tuple[float, float]]) -> float | None:
    errs = [abs(fitted - observed) / observed for fitted, observed in pairs if observed > 0]
    return round(max(errs), 4) if errs else None


@dataclass(frozen=True)
class LoadCurve:
    """CPU per request against the rate one replica gets: ``serviceTimeByLoad`` before it is
    turned into service-time factors."""

    points: tuple[tuple[float, float], ...]
    """(req/s per replica, CPU per request as a multiple of ``reference_ms``), rising rates,
    never-rising factors."""
    reference_ms: float
    """The CPU per request a factor of 1 stands for (ms)."""
    measured: tuple[tuple[float, float], ...]
    """(req/s per replica, CPU per request in ms) of each bin, before the never-rising fit."""
    error: float | None
    """Largest relative difference between a bin's measured CPU per request and the fit."""

    @property
    def span(self) -> float:
        """How far the factors spread: the largest over the smallest, less 1."""
        fs = [f for _, f in self.points]
        return max(fs) / min(fs) - 1.0 if min(fs) > 0 else 0.0

    def table(self) -> list[dict[str, float]]:
        """``serviceTimeByLoad``. The engine scales the whole service time by the factor and
        charges the CPU share of it as CPU, so the factors are the CPU's: CPU per request then
        follows what was measured (a share below 1 also scales the time without a core)."""
        return [{"rps": round(r, 1), "factor": round(f, 4)} for r, f in self.points]

    def text(self) -> str:
        (r0, m0), (r1, m1) = self.measured[0], self.measured[-1]
        return (
            f"CPU per request {m0:.3g} ms at about {r0:,.0f} req/s per replica, {m1:.3g} ms at "
            f"about {r1:,.0f} req/s ({len(self.measured)} bins by rate, fitted never rising)"
        )


def _never_rising(values: list[float], weights: list[float]) -> list[float]:
    """The weighted least-squares fit to ``values`` that never rises (pool adjacent violators):
    where a later value is higher, the run is replaced by its weighted mean."""
    blocks: list[list[float]] = []  # [mean, weight, count]
    for v, w in zip(values, weights, strict=True):
        blocks.append([v, w, 1])
        while len(blocks) > 1 and blocks[-1][0] > blocks[-2][0]:
            v2, w2, n2 = blocks.pop()
            v1, w1, n1 = blocks.pop()
            blocks.append([(v1 * w1 + v2 * w2) / (w1 + w2), w1 + w2, n1 + n2])
    out: list[float] = []
    for mean, _, count in blocks:
        out += [mean] * int(count)
    return out


def fit_load_curve(
    points: Sequence[tuple[float, float]],
    reference_ms: float | None = None,
    max_bins: int = MAX_BINS,
) -> LoadCurve | None:
    """CPU per request as it changes with load, from (req/s, cores) points of one replica: the
    points sorted by rate into up to ``max_bins`` bins of equal count (quantiles of the rate),
    each bin's CPU per request (its cores over its rate, so busy points weigh most), then the
    closest curve that never rises with rate. Factors are relative to ``reference_ms`` (default:
    CPU per request over the whole range, as ``fit_cpu_share`` measures it). None with fewer
    than 2 points, or when the busiest bin's rate is under ``MIN_SPREAD`` times the quietest's."""
    pts = sorted(
        (r, c) for r, c in points if math.isfinite(r) and math.isfinite(c) and r > 0 and c >= 0
    )
    if len(pts) < 2:
        return None
    k = max(2, min(max_bins, len(pts)))
    bins = [pts[i * len(pts) // k : (i + 1) * len(pts) // k] for i in range(k)]
    bins = [b for b in bins if b]
    rates = [sum(r for r, _ in b) for b in bins]
    rps = [s / len(b) for s, b in zip(rates, bins, strict=True)]
    if rps[-1] < MIN_SPREAD * rps[0]:
        return None
    ms = [1000.0 * sum(c for _, c in b) / s for s, b in zip(rates, bins, strict=True)]
    fitted = _never_rising(ms, rates)
    ref = reference_ms if reference_ms and reference_ms > 0 else 0.0
    if not ref:  # CPU per request over the whole range: busy bins weigh most
        ref = sum(m * w for m, w in zip(ms, rates, strict=True)) / sum(rates)
    if ref <= 0:
        return None
    error = _max_rel_error([(f, m) for f, m in zip(fitted, ms, strict=True)])
    return LoadCurve(
        tuple((r, f / ref) for r, f in zip(rps, fitted, strict=True)),
        ref,
        tuple(zip(rps, ms, strict=True)),
        error,
    )


def fit_cpu_share(
    points: Sequence[tuple[float, float]], service_ms: float, replicas: float = 1.0
) -> CpuFit | None:
    """``cpuShare`` from (req/s, cores) points of the whole part and the mean service time (ms),
    and CPU per request against the rate per replica (the part's rate over ``replicas``); None
    when there is no traffic or no service time."""
    pts = [(r, c) for r, c in points if math.isfinite(r) and math.isfinite(c) and r >= 0 and c >= 0]
    total_rate = sum(r for r, _ in pts)
    if not pts or total_rate <= 0 or service_ms <= 0:
        return None
    cpu_ms = 1000.0 * sum(c for _, c in pts) / total_rate
    raw = cpu_ms / service_ms
    capped = raw > 1.0
    share = round(min(1.0, raw), 4)
    notes: list[str] = []
    if capped:
        notes.append(
            f"{cpu_ms:.3g} ms of CPU per request is more than the {service_ms:.3g} ms service "
            "time: the process spends CPU outside the handler time (HTTP parsing, drivers, "
            "garbage collection). The CPU share is at most 1, so the model under-counts CPU; "
            "use a service time that covers the CPU per request"
        )
    n = max(1.0, replicas)
    curve = fit_load_curve([(r / n, c / n) for r, c in pts], cpu_ms)
    busy = _busy_half([r for r, _ in pts])
    per_core = service_ms * share / 1000.0
    error = _max_rel_error([(pts[i][0] * per_core, pts[i][1]) for i in busy])
    return CpuFit(share, cpu_ms, service_ms, capped, error, curve, len(pts), tuple(notes))


@dataclass(frozen=True)
class MemoryFit:
    baseline_mb: float
    per_rps_mb: float
    """MiB per req/s (0 when the request rate was not fitted against)."""
    per_request_mb: float
    """MiB per request held (``MIN_PER_REQUEST_MB`` when requests held were not fitted against
    or added no memory)."""
    error: float | None
    points: int
    against: tuple[str, ...] = ()
    """What the working set was fitted against: "rate", "held" or both."""
    notes: tuple[str, ...] = field(default_factory=tuple)

    def predict(self, rate: float = 0.0, held: float = 0.0) -> float:
        """Working set per replica (MiB) at ``rate`` req/s and ``held`` requests per replica."""
        return self.baseline_mb + self.per_rps_mb * rate + self.per_request_mb * held


def _solve(a: list[list[float]], b: list[float]) -> list[float] | None:
    """Gaussian elimination with partial pivoting; None when the system is (near) singular."""
    n = len(b)
    m = [[*row, b[i]] for i, row in enumerate(a)]
    scale = max((abs(x) for row in a for x in row), default=0.0) or 1.0
    for col in range(n):
        pivot = max(range(col, n), key=lambda r: abs(m[r][col]))
        if abs(m[pivot][col]) <= 1e-10 * scale:
            return None
        m[col], m[pivot] = m[pivot], m[col]
        for r in range(n):
            if r != col:
                f = m[r][col] / m[col][col]
                for c in range(col, n + 1):
                    m[r][c] -= f * m[col][c]
    return [m[i][n] / m[i][i] for i in range(n)]


def _least_squares(cols: list[list[float]], y: list[float]) -> list[float] | None:
    """Ordinary least squares over the given columns (normal equations)."""
    k = len(cols)
    gram = [
        [sum(p * q for p, q in zip(cols[i], cols[j], strict=True)) for j in range(k)]
        for i in range(k)
    ]
    rhs = [sum(p * q for p, q in zip(cols[i], y, strict=True)) for i in range(k)]
    return _solve(gram, rhs)


def nonnegative_fit(
    columns: dict[str, list[float]], y: list[float]
) -> tuple[dict[str, float], float] | None:
    """Non-negative least squares by trying every subset of ``columns`` (a handful at most):
    the best fit whose coefficients are all at least zero, preferring fewer terms on a tie.
    Returns the coefficients (absent columns are 0) and the sum of squared errors."""
    names = list(columns)
    best: tuple[float, int, dict[str, float]] | None = None
    for size in range(1, len(names) + 1):
        for subset in itertools.combinations(names, size):
            coef = _least_squares([columns[n] for n in subset], y)
            if coef is None or any(c < -1e-12 for c in coef):
                continue
            fitted = dict(zip(subset, (max(0.0, c) for c in coef), strict=True))
            sse = sum(
                (yi - sum(fitted[n] * columns[n][i] for n in subset)) ** 2 for i, yi in enumerate(y)
            )
            if best is None or sse < best[0] * (1 - 1e-9) - 1e-12:
                best = (sse, size, fitted)
    if best is None:
        return None
    return {n: best[2].get(n, 0.0) for n in names}, best[0]


def fit_memory(
    working_set_mb: Sequence[float],
    rate: Sequence[float] | None = None,
    held: Sequence[float] | None = None,
) -> MemoryFit | None:
    """Memory at idle plus MiB per req/s and/or per request held, from per-replica working sets
    (MiB) and the matching per-replica request rate and/or requests held; None without points."""
    if rate is None and held is None:
        raise ValueError("fit the working set against the request rate, requests held, or both")
    n = len(working_set_mb)
    rows = [
        i
        for i in range(n)
        if math.isfinite(working_set_mb[i])
        and (rate is None or (math.isfinite(rate[i]) and rate[i] >= 0))
        and (held is None or (math.isfinite(held[i]) and held[i] >= 0))
    ]
    if not rows:
        return None
    y = [float(working_set_mb[i]) for i in rows]
    cols: dict[str, list[float]] = {"baseline": [1.0] * len(rows)}
    if rate is not None:
        cols["rate"] = [float(rate[i]) for i in rows]
    if held is not None:
        cols["held"] = [float(held[i]) for i in rows]
    solved = nonnegative_fit(cols, y)
    if solved is None:  # pragma: no cover - the constant alone always solves
        return None
    coef, _ = solved
    notes: list[str] = []
    for key, what in (("rate", "request rate"), ("held", "requests held")):
        xs = cols.get(key)
        if xs is None:
            continue
        lo, hi = min(xs), max(xs)
        if hi <= 0 or hi < 1.2 * lo:
            notes.append(
                f"the {what} hardly changed over the range ({lo:,.3g} to {hi:,.3g} per replica), "
                f"so memory that follows it cannot be told apart from memory at idle"
            )
        elif coef[key] == 0.0:
            notes.append(f"memory did not grow with the {what} over the range")
    baseline = round(coef["baseline"], 3)
    per_rps = round(coef.get("rate", 0.0), 6)
    per_request = round(max(MIN_PER_REQUEST_MB, coef.get("held", 0.0)), 6)
    zero = [0.0] * len(rows)
    xr, xh = cols.get("rate", zero), cols.get("held", zero)
    busy = _busy_half(cols.get("rate") or cols["held"])
    error = _max_rel_error(
        [(baseline + per_rps * xr[i] + coef.get("held", 0.0) * xh[i], y[i]) for i in busy]
    )
    against = tuple(k for k in ("rate", "held") if k in cols)
    return MemoryFit(baseline, per_rps, per_request, error, len(rows), against, tuple(notes))
