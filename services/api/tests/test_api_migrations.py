"""The Alembic migrations produce exactly the schema the ORM expects."""

from __future__ import annotations

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect

from archsim_api.db import Base
from archsim_api.migrations import upgrade_to_head


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
