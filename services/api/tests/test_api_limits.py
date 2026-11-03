"""What the workers keep of the limits: runs at once per workspace kind, and pruning the
rate-limit counters whose window ended."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import timedelta

import pytest

from archsim_api import limits
from archsim_api.config import Settings
from archsim_api.db import Database, RateLimit, utcnow
from archsim_api.migrations import upgrade_to_head


@pytest.fixture
def db(db_url: str) -> Iterator[Database]:
    upgrade_to_head(db_url)
    database = Database(db_url)
    yield database
    database.engine.dispose()


def test_without_limits_only_a_sandbox_has_a_cap() -> None:
    local = Settings(rate_limits=False)

    assert limits.kind_caps(local) == {"personal": None, "team": None, "sandbox": 1}


def test_hosted_each_kind_has_its_cap() -> None:
    hosted = Settings(rate_limits=True)

    assert limits.kind_caps(hosted) == {"personal": 2, "team": 4, "sandbox": 1}


def test_old_windows_are_pruned(db: Database) -> None:
    now = utcnow()
    ended, still_open = now - timedelta(seconds=1), now + timedelta(hours=1)
    with db.session() as s:
        s.add(RateLimit(key="ended", window_start=0, count=1, expires_at=ended))
        s.add(RateLimit(key="open", window_start=0, count=1, expires_at=still_open))
        s.commit()

    assert limits.prune(db, now=now) == 1
    assert limits.prune(db, now=now) == 0
