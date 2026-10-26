"""Probability distributions for service times and network latencies.

All values are in **milliseconds**. Distributions are sampled with an explicit
``random.Random`` instance so runs are reproducible from a seed.
"""

from __future__ import annotations

import itertools
import math
import random
from bisect import bisect_right
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_SAMPLE_MS = 600_000.0
"""Upper bound applied to every sample (10 minutes) so heavy tails cannot stall a run."""

_Z95 = 1.6448536269514722  # standard normal quantile for p = 0.95


class _Dist(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    def sample(self, rng: random.Random) -> float:  # pragma: no cover - abstract
        raise NotImplementedError

    def expected(self) -> float:  # pragma: no cover - abstract
        """Analytic mean in milliseconds (before the MAX_SAMPLE_MS cap)."""
        raise NotImplementedError

    def describe(self) -> str:  # pragma: no cover - abstract
        raise NotImplementedError


class Constant(_Dist):
    """Always the same value. Useful for fixed overheads and deterministic tests."""

    kind: Literal["constant"] = "constant"
    value: float = Field(ge=0, le=MAX_SAMPLE_MS, description="Value in ms")

    def sample(self, rng: random.Random) -> float:
        return self.value

    def expected(self) -> float:
        return self.value

    def describe(self) -> str:
        return f"constant {self.value:g} ms"


class Exponential(_Dist):
    """Memoryless distribution (M in queueing notation). High variance: stddev == mean."""

    kind: Literal["exponential"] = "exponential"
    mean: float = Field(gt=0, le=MAX_SAMPLE_MS, description="Mean in ms")

    def sample(self, rng: random.Random) -> float:
        return min(rng.expovariate(1.0 / self.mean), MAX_SAMPLE_MS)

    def expected(self) -> float:
        return self.mean

    def describe(self) -> str:
        return f"exponential, mean {self.mean:g} ms"


class Uniform(_Dist):
    """Uniformly distributed between ``min`` and ``max``."""

    kind: Literal["uniform"] = "uniform"
    min: float = Field(ge=0, le=MAX_SAMPLE_MS, description="Minimum in ms")
    max: float = Field(ge=0, le=MAX_SAMPLE_MS, description="Maximum in ms")

    @model_validator(mode="after")
    def _check(self) -> Uniform:
        if self.max < self.min:
            raise ValueError("uniform distribution requires max >= min")
        return self

    def sample(self, rng: random.Random) -> float:
        return rng.uniform(self.min, self.max)

    def expected(self) -> float:
        return (self.min + self.max) / 2.0

    def describe(self) -> str:
        return f"uniform {self.min:g}-{self.max:g} ms"


class LogNormal(_Dist):
    """Right-skewed distribution parameterised by its median and 95th percentile.

    This is a common, realistic shape for request handling times: most requests are
    near the median with a long tail. ``sigma = ln(p95 / median) / 1.645``.
    """

    kind: Literal["lognormal"] = "lognormal"
    median: float = Field(gt=0, le=MAX_SAMPLE_MS, description="Median (p50) in ms")
    p95: float = Field(gt=0, le=MAX_SAMPLE_MS, description="95th percentile in ms")

    @model_validator(mode="after")
    def _check(self) -> LogNormal:
        if self.p95 < self.median:
            raise ValueError("lognormal distribution requires p95 >= median")
        return self

    @property
    def mu(self) -> float:
        return math.log(self.median)

    @property
    def sigma(self) -> float:
        return math.log(self.p95 / self.median) / _Z95

    def sample(self, rng: random.Random) -> float:
        sigma = self.sigma
        if sigma == 0:
            return self.median
        return min(rng.lognormvariate(self.mu, sigma), MAX_SAMPLE_MS)

    def expected(self) -> float:
        return math.exp(self.mu + self.sigma**2 / 2.0)

    def describe(self) -> str:
        return f"lognormal, p50 {self.median:g} ms / p95 {self.p95:g} ms"


class Empirical(_Dist):
    """A measured distribution: a quantile table, linear between its points.

    ``quantiles`` holds ``[p, ms]`` pairs: ``p`` rises strictly from 0 to 1 (both ends included)
    and the values never fall, e.g. read off a latency histogram's buckets. Sampling draws ``u``
    from the seeded RNG and interpolates, so runs stay reproducible.
    """

    kind: Literal["empirical"] = "empirical"
    quantiles: list[tuple[float, float]] = Field(min_length=2, max_length=200)

    @model_validator(mode="after")
    def _check(self) -> Empirical:
        ps = [p for p, _ in self.quantiles]
        vs = [v for _, v in self.quantiles]
        if ps[0] != 0.0 or ps[-1] != 1.0:
            raise ValueError("empirical quantiles must start at p = 0 and end at p = 1")
        if any(b <= a for a, b in itertools.pairwise(ps)):
            raise ValueError("empirical quantile probabilities must rise strictly")
        if any(b < a for a, b in itertools.pairwise(vs)):
            raise ValueError("empirical quantile values must not fall")
        if vs[0] < 0 or vs[-1] > MAX_SAMPLE_MS:
            raise ValueError(f"empirical values must be between 0 and {MAX_SAMPLE_MS:g} ms")
        return self

    def quantile(self, q: float) -> float:
        ps = [p for p, _ in self.quantiles]
        i = min(max(bisect_right(ps, q) - 1, 0), len(ps) - 2)
        (p0, v0), (p1, v1) = self.quantiles[i], self.quantiles[i + 1]
        return v0 + (v1 - v0) * (q - p0) / (p1 - p0)

    def sample(self, rng: random.Random) -> float:
        return min(self.quantile(rng.random()), MAX_SAMPLE_MS)

    def expected(self) -> float:
        return sum(
            (p1 - p0) * (v0 + v1) / 2
            for (p0, v0), (p1, v1) in zip(self.quantiles, self.quantiles[1:], strict=False)
        )

    def describe(self) -> str:
        return (
            f"empirical, p50 {self.quantile(0.5):.4g} ms / p95 {self.quantile(0.95):.4g} ms "
            f"({len(self.quantiles)} points)"
        )


Distribution = Annotated[
    Constant | Exponential | Uniform | LogNormal | Empirical,
    Field(discriminator="kind"),
]
"""A service-time or latency distribution in milliseconds (tagged by ``kind``)."""
