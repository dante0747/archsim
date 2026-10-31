"""Service time that follows load (the service setting ``serviceTimeByLoad``, schema 1.6).

A service's CPU time per request is often not constant: measured services get cheaper per
request as their load rises (more requests handled per wake-up of the event loop, batched socket
writes, warmer caches) and sometimes dearer (contention). ``serviceTimeByLoad`` is a table of
``{rps, factor}`` points for one replica: at that many requests per second the replica's service
time is the configured one times the factor.

* Between two points the factor is interpolated linearly in log(rps).
* Below the first point and above the last it stays at the nearest point's factor: a curve is
  never extrapolated, so a fall measured up to some rate is not assumed to continue past it.
* An empty table means factor 1 at every rate (the behaviour before schema 1.6).

The simulation reads each replica's own arrival rate over a trailing second (``RateMeter``). The
analytic copies (capacity, the instant estimate, sizing, the review) use ``load_factor`` at the
rate they reason about, and ``capacity_rps`` where the rate is the capacity itself: the rate at
which the CPU fills when every request costs what it costs at that rate (a fixed point).
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from itertools import pairwise
from typing import Any

from pydantic import Field

from archsim_engine.schema import CamelModel

TRAILING_MS = 1000.0
"""The time constant of a replica's rate: arrivals over about the last second (EWMA)."""


class LoadPoint(CamelModel):
    """One point of a service-time curve: at ``rps`` requests per second (one replica), the
    service time is ``factor`` times the configured service time."""

    rps: float = Field(gt=0, le=10_000_000, description="Requests per second one replica receives.")
    factor: float = Field(
        gt=0,
        le=100,
        description="Service time at this rate as a multiple of the configured service time.",
    )


Curve = list[tuple[float, float]]
"""Sorted ``(rps, factor)`` points, rates distinct and positive."""


def curve_of(points: Iterable[Any] | None) -> Curve:
    """The points of a ``serviceTimeByLoad`` value (``LoadPoint`` models or raw JSON dicts with
    ``rps`` and ``factor``), sorted by rate; points without a positive rate and factor are
    skipped, and of two points at the same rate the later one is kept."""
    out: dict[float, float] = {}
    for p in points or ():
        rps = p.get("rps") if isinstance(p, dict) else getattr(p, "rps", None)
        factor = p.get("factor") if isinstance(p, dict) else getattr(p, "factor", None)
        if not isinstance(rps, int | float) or not isinstance(factor, int | float):
            continue
        if isinstance(rps, bool) or isinstance(factor, bool):
            continue
        if rps > 0 and factor > 0 and math.isfinite(rps) and math.isfinite(factor):
            out[float(rps)] = float(factor)
    return sorted(out.items())


def load_factor(curve: Sequence[tuple[float, float]], rps: float | None) -> float:
    """The service-time factor at ``rps`` requests per second (one replica): interpolated in
    log(rps) between points, the nearest point's factor outside them, 1 without points."""
    if not curve:
        return 1.0
    if rps is None or rps <= curve[0][0]:
        return curve[0][1]
    if rps >= curve[-1][0]:
        return curve[-1][1]
    for (r0, f0), (r1, f1) in pairwise(curve):
        if rps <= r1:
            t = math.log(rps / r0) / math.log(r1 / r0)
            return f0 + (f1 - f0) * t
    return curve[-1][1]  # pragma: no cover - rps < the last rate is caught above


def rate_filling(
    curve: Sequence[tuple[float, float]], work_ms: float, budget_ms: float, fixed_ms: float = 0.0
) -> float:
    """The rate ``c`` (req/s) at which ``c * (work_ms * factor(c) + fixed_ms)`` reaches
    ``budget_ms`` per second: where a replica's cores (1,000 ms per second each) or its slots
    fill, when each request needs ``work_ms`` of work at factor 1 that follows the curve and
    ``fixed_ms`` that does not. Busy time is taken to grow with the rate (a calibrated CPU per
    request never falls faster than the rate rises), so bisection finds the one crossing."""
    if work_ms <= 0 and fixed_ms <= 0:
        return math.inf
    if not curve:
        return budget_ms / (work_ms + fixed_ms)
    factors = [f for _, f in curve]
    lo = budget_ms / (work_ms * max(factors) + fixed_ms)
    hi = budget_ms / (work_ms * min(factors) + fixed_ms)
    for _ in range(60):
        mid = math.sqrt(lo * hi)
        if mid * (work_ms * load_factor(curve, mid) + fixed_ms) < budget_ms:
            lo = mid
        else:
            hi = mid
    return hi


def capacity_rps(curve: Sequence[tuple[float, float]], base_rps: float) -> float:
    """The rate one replica can serve when it could serve ``base_rps`` at factor 1: the rate
    ``c`` at which ``c * factor(c)`` reaches ``base_rps`` (its CPU fills at that rate's cost).
    Without points it is ``base_rps``."""
    if not curve or base_rps <= 0:
        return max(0.0, base_rps)
    return rate_filling(curve, 1000.0 / base_rps, 1000.0)


def describe(curve: Sequence[tuple[float, float]]) -> str:
    """The table in words, such as ``x1.8 at 50 req/s to x1 at 1,674 req/s (3 points)``."""
    if not curve:
        return "no load curve"
    (r0, f0), (r1, f1) = curve[0], curve[-1]
    if len(curve) == 1:
        return f"×{f0:.3g} at every rate"
    return f"×{f0:.3g} at {r0:,.0f} req/s to ×{f1:.3g} at {r1:,.0f} req/s ({len(curve)} points)"


class RateMeter:
    """A replica's arrival rate over a trailing second: an exponentially weighted count of its
    arrivals (time constant ``TRAILING_MS``), corrected for the time it has been observed so the
    first second is not read as a low rate."""

    __slots__ = ("level", "since", "start", "tau")

    def __init__(self, start_ms: float, tau_ms: float = TRAILING_MS) -> None:
        self.tau = tau_ms
        self.start = start_ms
        self.since = start_ms
        self.level = 0.0  # weighted arrivals per ms

    def _decay(self, now: float) -> None:
        if now > self.since:
            self.level *= math.exp(-(now - self.since) / self.tau)
            self.since = now

    def arrive(self, now: float) -> None:
        self._decay(now)
        self.level += 1.0 / self.tau

    def rps(self, now: float) -> float:
        """Arrivals per second over about the last second, as of ``now``."""
        self._decay(now)
        observed = 1.0 - math.exp(-max(now - self.start, 0.0) / self.tau)
        if observed <= 0:
            return 0.0
        return self.level / observed * 1000.0
