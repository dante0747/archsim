"""Token bucket used for rate limits (gateways, external APIs) and provisioned throughput."""

from __future__ import annotations


class TokenBucket:
    """``rate_per_s`` tokens per second, holding at most ``burst`` tokens. Time is in ms.

    The bucket starts full. ``try_take`` returns False (and takes nothing) when empty.
    """

    __slots__ = ("burst", "last_ms", "rate_per_ms", "tokens")

    def __init__(self, rate_per_s: float, burst: float | None = None) -> None:
        if rate_per_s <= 0:
            raise ValueError("rate_per_s must be > 0")
        self.rate_per_ms = rate_per_s / 1000.0
        self.burst = float(burst) if burst is not None else max(1.0, rate_per_s)
        self.tokens = self.burst
        self.last_ms = 0.0

    def try_take(self, now_ms: float, n: float = 1.0) -> bool:
        elapsed = now_ms - self.last_ms
        if elapsed > 0:
            self.tokens = min(self.burst, self.tokens + elapsed * self.rate_per_ms)
            self.last_ms = now_ms
        if self.tokens >= n:
            self.tokens -= n
            return True
        return False
