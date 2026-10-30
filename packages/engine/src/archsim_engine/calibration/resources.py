"""Fitting CPU and memory inputs from what a part used over a range of load.

Pure functions over (rate, measurement) points, used by calibration from Prometheus and by the
benchmark tests on measured stacks.

**CPU per request → ``cpuShare``.** CPU per request is the rate of CPU seconds over the rate of
requests, summed over the range (mean cores ÷ mean request rate): the busiest times weigh most,
which is where a part is sized. ``cpuShare`` is that over the mean service time, at most 1. The
model keeps one CPU cost per request; on real stacks it falls as load rises (requests share event
loop wake-ups, system calls and driver work), so ``load_dependence`` measures CPU per request at
the quietest and busiest quarters of the range and calibration says when they differ. The fit
error is the largest relative error of the fitted CPU at and above the median rate.

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

LOAD_DEPENDENCE = 0.25
"""CPU per request at the quietest and busiest quarters of the range differing by more than
this share is reported."""
MIN_SPREAD = 1.5
"""The busiest quarter's rate must be at least this multiple of the quietest's to compare them."""
MIN_PER_REQUEST_MB = 0.01
"""Smallest memory per request held calibration sets (the setting must be above zero): requests
held that add no measurable memory get this (10 KiB, about a socket's buffers)."""


@dataclass(frozen=True)
class LoadDependence:
    """CPU per request (ms) at the quietest and busiest quarters of the range."""

    quiet_rps: float
    quiet_ms: float
    busy_rps: float
    busy_ms: float

    @property
    def change(self) -> float:
        """Relative change from quiet to busy (negative: it falls as load rises)."""
        return (self.busy_ms - self.quiet_ms) / self.quiet_ms if self.quiet_ms > 0 else 0.0

    def text(self) -> str:
        way = "falls" if self.busy_ms < self.quiet_ms else "rises"
        return (
            f"CPU per request {way} with load: {self.quiet_ms:.3g} ms at about "
            f"{self.quiet_rps:,.0f} req/s, {self.busy_ms:.3g} ms at about {self.busy_rps:,.0f} req/s"
        )


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
    load: LoadDependence | None
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


def load_dependence(points: Sequence[tuple[float, float]]) -> LoadDependence | None:
    """CPU per request at the quietest and busiest quarters of ``points`` ((req/s, cores)), or
    None with fewer than 4 points or too little spread in the rate."""
    pts = sorted((r, c) for r, c in points if r > 0 and c >= 0)
    if len(pts) < 4:
        return None
    k = max(1, len(pts) // 4)
    quiet, busy = pts[:k], pts[-k:]
    q_rate, b_rate = sum(r for r, _ in quiet), sum(r for r, _ in busy)
    if b_rate / len(busy) < MIN_SPREAD * q_rate / len(quiet):
        return None
    return LoadDependence(
        q_rate / len(quiet),
        1000.0 * sum(c for _, c in quiet) / q_rate,
        b_rate / len(busy),
        1000.0 * sum(c for _, c in busy) / b_rate,
    )


def fit_cpu_share(points: Sequence[tuple[float, float]], service_ms: float) -> CpuFit | None:
    """``cpuShare`` from (req/s, cores) points and the mean service time (ms); None when there is
    no traffic or no service time."""
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
    load = load_dependence(pts)
    if load is not None and abs(load.change) > LOAD_DEPENDENCE:
        notes.append(
            f"{load.text()}. ArchSim keeps one CPU cost per request, weighted to the busiest "
            "times, so it "
            + ("under-counts" if load.change < 0 else "over-counts")
            + " CPU at quiet times"
        )
    busy = _busy_half([r for r, _ in pts])
    per_core = service_ms * share / 1000.0
    error = _max_rel_error([(pts[i][0] * per_core, pts[i][1]) for i in busy])
    return CpuFit(share, cpu_ms, service_ms, capped, error, load, len(pts), tuple(notes))


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
