"""How much each workspace may use, and rate limits shared by every API instance.

**Profiles.** With limits on (``ARCHSIM_RATE_LIMITS``, on with sign-in), each workspace kind has
a profile: runs at once (enforced by ``queue.claim`` across all workers), runs per hour, the
largest run (``SimulationLimits``: expected requests and wall clock, never above the server's
own ``ARCHSIM_MAX_REQUESTS``/``ARCHSIM_MAX_WALL_SECONDS``), the calibration replay's wall clock,
how many projects it may hold and how many seeds one seed group may run (5 hosted, 10 locally). A workspace's own ``max_concurrent_runs`` /
``max_runs_per_hour`` replace its kind's. Without limits (local use) only the server's own
settings apply, except for sandboxes, which always keep theirs.

**Counters.** ``rate_limits`` holds one row per key and fixed window (``ws:<id>:runs``,
``user:<id>:tokens``, ``ip:<addr>:login`` ...). ``hit`` adds to the count with one upsert that
only succeeds while the count stays within the limit (``INSERT ... ON CONFLICT DO UPDATE ...
WHERE``), on SQLite and PostgreSQL alike, so instances sharing the database share the limits
and a refused request costs nothing. ``prune`` deletes rows whose window ended (the workers
call it while reaping).
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, cast

from fastapi import HTTPException
from sqlalchemy import Table, delete
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert

from archsim_api.config import Settings
from archsim_api.db import Database, RateLimit, Workspace, utcnow
from archsim_engine import SimulationLimits

HOUR = 3600
DAY = 86_400


@dataclass(frozen=True)
class Profile:
    max_concurrent_runs: int | None
    """Runs simulated at once (None: as many as the workers have slots)."""
    runs_per_hour: int | None
    max_requests: int | None
    """Expected requests in one run (None: the server's ARCHSIM_MAX_REQUESTS)."""
    max_wall_seconds: float | None
    calibrate_wall_seconds: float | None
    """Wall clock of the replay inside POST /calibrate (it answers synchronously)."""
    max_projects: int | None
    max_seeds: int = 5
    """Runs in one seed group (``RunCreate.seeds``)."""


UNLIMITED = Profile(None, None, None, None, None, None, max_seeds=10)

SANDBOX = Profile(
    max_concurrent_runs=1,
    runs_per_hour=20,
    max_requests=200_000,
    max_wall_seconds=20.0,
    calibrate_wall_seconds=20.0,
    max_projects=5,
)

HOSTED: dict[str, Profile] = {
    "personal": Profile(2, 60, None, None, 20.0, None),
    "team": Profile(4, 300, None, None, 20.0, None),
    "sandbox": SANDBOX,
}

# Per person and per address, whatever the workspace.
USER_RUNS_PER_HOUR = 300
LOGINS_PER_IP = (30, 600)  # 30 sign-in starts per 10 minutes
TOKENS_PER_USER = (20, DAY)
WORKSPACES_PER_USER = (10, DAY)
INVITES_PER_WORKSPACE = (50, DAY)
CALIBRATIONS_PER_USER = (30, HOUR)


def profile(settings: Settings, kind: str) -> Profile:
    if settings.limits_on:
        return HOSTED.get(kind, HOSTED["personal"])
    return SANDBOX if kind == "sandbox" else UNLIMITED


def kind_caps(settings: Settings) -> dict[str, int | None]:
    """Runs at once per workspace kind, for ``queue.claim``."""
    kinds = ("personal", "team", "sandbox")
    return {k: profile(settings, k).max_concurrent_runs for k in kinds}


def _least(server: float, cap: float | None) -> float:
    return server if cap is None else min(server, cap)


def simulation_limits(settings: Settings, workspace: Workspace) -> SimulationLimits:
    """The largest run this workspace may start."""
    p = profile(settings, workspace.kind)
    return SimulationLimits(
        max_requests=int(_least(settings.max_requests, p.max_requests)),
        max_wall_seconds=_least(settings.max_wall_seconds, p.max_wall_seconds),
    )


def calibrate_wall_seconds(settings: Settings, workspace: Workspace) -> float:
    p = profile(settings, workspace.kind)
    server = min(settings.max_wall_seconds, settings.calibrate_max_wall_seconds)
    return _least(_least(server, p.max_wall_seconds), p.calibrate_wall_seconds)


def max_seeds(settings: Settings, workspace: Workspace) -> int:
    """The most seeds one seed group of this workspace may run."""
    return profile(settings, workspace.kind).max_seeds


def runs_per_hour(settings: Settings, workspace: Workspace) -> int | None:
    if workspace.max_runs_per_hour is not None:
        return workspace.max_runs_per_hour
    return profile(settings, workspace.kind).runs_per_hour


# -- counters ----------------------------------------------------------------------------------
rate_limits = cast(Table, RateLimit.__table__)


def hit(db: Database, key: str, limit: int, window_s: int, cost: int = 1) -> int | None:
    """Count ``cost`` against ``key`` in the current window of ``window_s`` seconds. None: done,
    within ``limit``. Otherwise nothing is counted and the answer is the seconds until the
    window ends (for Retry-After)."""
    now = time.time()
    start = int(now // window_s) * window_s
    retry = max(1, math.ceil(start + window_s - now))
    if cost > limit:
        return retry
    insert: Any = pg_insert if db.dialect == "postgresql" else sqlite_insert
    stmt = (
        insert(rate_limits)
        .values(
            key=key[:200],
            window_start=start,
            count=cost,
            expires_at=datetime.fromtimestamp(start + window_s, UTC),
        )
        .on_conflict_do_update(
            index_elements=[rate_limits.c.key, rate_limits.c.window_start],
            set_={"count": rate_limits.c.count + cost},
            where=rate_limits.c.count + cost <= limit,
        )
        .returning(rate_limits.c.count)
    )
    with db.engine.begin() as conn:
        counted = conn.execute(stmt).scalar_one_or_none()
    return None if counted is not None else retry


def prune(db: Database, now: datetime | None = None) -> int:
    """Delete counters of windows that ended."""
    with db.engine.begin() as conn:
        res = conn.execute(delete(rate_limits).where(rate_limits.c.expires_at < (now or utcnow())))
    return int(res.rowcount or 0)


def _wait(seconds: int) -> str:
    if seconds < 90:
        return f"{seconds} s"
    if seconds < 5400:
        return f"{math.ceil(seconds / 60)} min"
    return f"{math.ceil(seconds / 3600)} h"


def too_many(message: str, retry_after: int) -> HTTPException:
    return HTTPException(
        429,
        detail={"message": f"{message} Try again in {_wait(retry_after)}.", "issues": []},
        headers={"Retry-After": str(retry_after)},
    )


def enforce(
    settings: Settings,
    db: Database,
    key: str,
    limit_window: tuple[int, int] | None,
    message: str,
    cost: int = 1,
) -> None:
    """Raise 429 (with Retry-After) when ``key`` is over its limit. Off without limits."""
    if not settings.limits_on or limit_window is None:
        return
    limit, window = limit_window
    retry = hit(db, key, limit, window, cost)
    if retry is not None:
        raise too_many(message, retry)


def window(limit: int | None, seconds: int) -> tuple[int, int] | None:
    return None if limit is None else (limit, seconds)
