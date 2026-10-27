from __future__ import annotations

import os

from alembic import context
from sqlalchemy import engine_from_config, pool
from sqlalchemy.engine import Connection

from archsim_api.db import Base

config = context.config
target_metadata = Base.metadata

# When invoked from the alembic CLI (alembic.ini present), honour ARCHSIM_DATABASE_URL.
if config.config_file_name and os.environ.get("ARCHSIM_DATABASE_URL"):
    config.set_main_option("sqlalchemy.url", os.environ["ARCHSIM_DATABASE_URL"].replace("%", "%%"))


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        render_as_batch=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def _migrate(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata, render_as_batch=True)
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    # upgrade_to_head passes the connection that holds the migration lock (and its transaction,
    # which it commits); the alembic CLI does not, so open one here.
    shared: Connection | None = config.attributes.get("connection")
    if shared is not None:
        _migrate(shared)
        return
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    with connectable.connect() as connection:
        _migrate(connection)


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
