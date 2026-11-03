"""What the workers need of the workspace limits: how many runs of each workspace kind may be
simulated at once, and the clean-up of rate-limit counters. The API sets and enforces the rest
(``io.archsim.app.limits`` in ``archsim-app``).

**Runs at once.** With limits on (``ARCHSIM_RATE_LIMITS``, on with sign-in), each workspace kind
has a cap (``HOSTED_RUNS_AT_ONCE``); without them (local use) only sandboxes keep theirs. A
workspace's own ``max_concurrent_runs`` replaces its kind's. ``queue.claim`` enforces them across
all workers.

**Counters.** ``rate_limits`` holds one row per key and fixed window, which the API counts
against. ``prune`` deletes the rows whose window ended (the workers call it while reaping).
"""

from __future__ import annotations

from datetime import datetime
from typing import cast

from sqlalchemy import Table, delete

from archsim_api.config import Settings
from archsim_api.db import Database, RateLimit, utcnow

HOSTED_RUNS_AT_ONCE: dict[str, int | None] = {"personal": 2, "team": 4, "sandbox": 1}
"""Runs simulated at once per workspace kind, with limits on."""

LOCAL_RUNS_AT_ONCE: dict[str, int | None] = {"personal": None, "team": None, "sandbox": 1}
"""Without limits: as many as the workers have slots, except in a sandbox."""


def kind_caps(settings: Settings) -> dict[str, int | None]:
    """Runs at once per workspace kind, for ``queue.claim``."""
    return dict(HOSTED_RUNS_AT_ONCE if settings.limits_on else LOCAL_RUNS_AT_ONCE)


rate_limits = cast(Table, RateLimit.__table__)


def prune(db: Database, now: datetime | None = None) -> int:
    """Delete counters of windows that ended."""
    with db.engine.begin() as conn:
        res = conn.execute(delete(rate_limits).where(rate_limits.c.expires_at < (now or utcnow())))
    return int(res.rowcount or 0)
