"""Database engine, session factory and ORM models.

PostgreSQL is the primary target (JSONB columns); SQLite is supported for zero-setup local use
and tests. Reports are written once per run as a single JSON document, never per event.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    create_engine,
    event,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.engine import Engine
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    Session,
    deferred,
    mapped_column,
    relationship,
    sessionmaker,
)

JSONType = JSON().with_variant(JSONB(), "postgresql")


def utcnow() -> datetime:
    return datetime.now(UTC)


def new_id() -> str:
    return uuid.uuid4().hex


class Base(DeclarativeBase):
    pass


class Project(Base):
    __tablename__ = "projects"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(120))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    document: Mapped[dict[str, Any]] = mapped_column(JSONType)
    schema_version: Mapped[str] = mapped_column(String(16))
    revision: Mapped[int] = mapped_column(Integer, default=1)
    # The example a project was created from, so opening that template again can reuse an
    # unedited copy instead of piling up duplicates.
    source_example: Mapped[str | None] = mapped_column(String(100), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    runs: Mapped[list[SimulationRun]] = relationship(
        back_populates="project", cascade="all, delete-orphan", passive_deletes=True
    )


class SimulationRun(Base):
    __tablename__ = "simulation_runs"
    __table_args__ = (Index("ix_runs_project_created", "project_id", "created_at"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    project_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("projects.id", ondelete="CASCADE"), index=True
    )
    label: Mapped[str | None] = mapped_column(String(200), nullable=True)
    scenario_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    scenario_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    status: Mapped[str] = mapped_column(String(16), default="queued", index=True)
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    error: Mapped[dict[str, Any] | None] = mapped_column(JSONType, nullable=True)
    seed: Mapped[int] = mapped_column(BigInteger)  # seeds go up to 2**32 - 1
    engine_version: Mapped[str] = mapped_column(String(32))
    input_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    result_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    summary: Mapped[dict[str, Any] | None] = mapped_column(JSONType, nullable=True)
    wall_seconds: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # Large payloads are deferred so listing runs stays cheap.
    input_document: Mapped[dict[str, Any]] = deferred(mapped_column(JSONType, nullable=False))
    report: Mapped[dict[str, Any] | None] = deferred(mapped_column(JSONType, nullable=True))

    project: Mapped[Project] = relationship(back_populates="runs")


def make_engine(url: str) -> Engine:
    if url.startswith("sqlite"):
        engine = create_engine(url, connect_args={"check_same_thread": False, "timeout": 30})

        @event.listens_for(engine, "connect")
        def _sqlite_pragmas(dbapi_conn: Any, _: Any) -> None:  # pragma: no cover - trivial
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA foreign_keys=ON")
            cur.execute("PRAGMA journal_mode=WAL")
            cur.close()

        return engine
    connect_args: dict[str, Any] = {}
    if url.startswith("postgresql"):
        connect_args["connect_timeout"] = 10  # seconds; fail fast instead of hanging
    return create_engine(url, pool_pre_ping=True, connect_args=connect_args)


class Database:
    def __init__(self, url: str) -> None:
        self.url = url
        self.engine = make_engine(url)
        self.session_factory = sessionmaker(self.engine, expire_on_commit=False)

    def session(self) -> Session:
        return self.session_factory()

    def sessions(self) -> Iterator[Session]:
        with self.session_factory() as s:
            yield s

    @property
    def dialect(self) -> str:
        return self.engine.dialect.name
