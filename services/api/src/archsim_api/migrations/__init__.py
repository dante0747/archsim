"""Alembic migrations for the ArchSim API database."""

from __future__ import annotations

from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text
from sqlalchemy.pool import NullPool

MIGRATIONS_DIR = Path(__file__).resolve().parent

MIGRATION_LOCK_KEY = 0x61726368_73696D01
"""PostgreSQL advisory lock key held while migrating ("archsim" plus 1), so API instances and
``archsim-api migrate`` started at the same time migrate one after the other."""


def alembic_config(url: str) -> Config:
    cfg = Config()
    cfg.set_main_option("script_location", str(MIGRATIONS_DIR))
    cfg.set_main_option("sqlalchemy.url", url.replace("%", "%%"))
    return cfg


def upgrade_to_head(url: str) -> None:
    """Bring the database to the latest schema.

    Everything runs in one transaction on one connection, which ``env.py`` receives through
    ``cfg.attributes["connection"]``. On PostgreSQL that transaction first takes an advisory
    lock, released when it commits: a second process waits for the first to finish, then finds
    nothing left to do."""
    cfg = alembic_config(url)
    engine = create_engine(url, poolclass=NullPool)
    try:
        with engine.begin() as connection:
            if connection.dialect.name == "postgresql":
                connection.execute(
                    text("SELECT pg_advisory_xact_lock(:key)"), {"key": MIGRATION_LOCK_KEY}
                )
            cfg.attributes["connection"] = connection
            command.upgrade(cfg, "head")
    finally:
        engine.dispose()
