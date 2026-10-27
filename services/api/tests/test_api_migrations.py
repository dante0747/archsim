"""The Alembic migrations produce exactly the schema the ORM expects."""

from __future__ import annotations

import os
import threading
import time

import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect, text

from archsim_api.db import Base
from archsim_api.migrations import MIGRATION_LOCK_KEY, upgrade_to_head


def test_migrations_match_models(db_url: str) -> None:
    upgrade_to_head(db_url)
    engine = create_engine(db_url)
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn), Base.metadata)
    engine.dispose()
    assert diff == []


def test_upgrade_is_idempotent(db_url: str) -> None:
    upgrade_to_head(db_url)
    upgrade_to_head(db_url)
    engine = create_engine(db_url)
    tables = set(inspect(engine).get_table_names())
    engine.dispose()
    assert {"projects", "simulation_runs", "alembic_version"} <= tables


def test_migrate_command_brings_the_database_to_head(db_url: str) -> None:
    from archsim_api.__main__ import main
    from archsim_api.config import get_settings

    get_settings.cache_clear()
    old = os.environ.get("ARCHSIM_DATABASE_URL")
    os.environ["ARCHSIM_DATABASE_URL"] = db_url
    try:
        assert main(["migrate"]) == 0
    finally:
        if old is None:
            os.environ.pop("ARCHSIM_DATABASE_URL", None)
        else:
            os.environ["ARCHSIM_DATABASE_URL"] = old
        get_settings.cache_clear()
    engine = create_engine(db_url)
    with engine.connect() as conn:
        assert MigrationContext.configure(conn).get_current_revision() is not None
        assert compare_metadata(MigrationContext.configure(conn), Base.metadata) == []
    engine.dispose()


def test_migrations_wait_for_the_lock_and_run_once(db_url: str) -> None:
    if not db_url.startswith("postgresql"):
        pytest.skip("the migration lock is a PostgreSQL advisory lock")
    # someone else is migrating: upgrade_to_head waits for the lock instead of racing them
    engine = create_engine(db_url)
    holder = engine.connect()
    holder.execute(text("SELECT pg_advisory_lock(:k)"), {"k": MIGRATION_LOCK_KEY})
    errors: list[BaseException] = []

    def upgrade() -> None:
        try:
            upgrade_to_head(db_url)
        except BaseException as exc:  # pragma: no cover - reported below
            errors.append(exc)

    waiting = [threading.Thread(target=upgrade) for _ in range(3)]
    for t in waiting:
        t.start()
    time.sleep(1.0)
    assert all(t.is_alive() for t in waiting)  # blocked on the lock
    holder.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": MIGRATION_LOCK_KEY})
    holder.close()
    for t in waiting:
        t.join(timeout=60)
    assert not errors, errors
    assert not any(t.is_alive() for t in waiting)
    with engine.connect() as conn:
        versions = conn.execute(text("SELECT version_num FROM alembic_version")).scalars().all()
    engine.dispose()
    assert len(versions) == 1  # three migrators, one schema
