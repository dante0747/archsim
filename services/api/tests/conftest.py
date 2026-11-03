from __future__ import annotations

import os
from collections.abc import Iterator
from pathlib import Path

import pytest
from sqlalchemy import MetaData, create_engine

# Set ARCHSIM_TEST_DATABASE_URL (e.g. postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim_test)
# to run the worker and queue tests against a real PostgreSQL database. Every table in that
# database is dropped before each test, so never point it at a database you care about.
TEST_DATABASE_URL = os.environ.get("ARCHSIM_TEST_DATABASE_URL")


def _reset_database(url: str) -> None:
    engine = create_engine(url)
    try:
        meta = MetaData()
        meta.reflect(bind=engine)  # includes alembic_version
        meta.drop_all(bind=engine)
    finally:
        engine.dispose()


@pytest.fixture
def db_url(tmp_path: Path) -> Iterator[str]:
    if TEST_DATABASE_URL:
        _reset_database(TEST_DATABASE_URL)
        yield TEST_DATABASE_URL
        return
    yield f"sqlite:///{(tmp_path / 'test.db').as_posix()}"
