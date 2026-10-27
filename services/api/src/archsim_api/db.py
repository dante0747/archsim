"""Database engine, session factory and ORM models.

PostgreSQL is the primary target (JSONB columns); SQLite is supported for zero-setup local use
and tests. Reports are written once per run as a single JSON document, never per event.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    create_engine,
    event,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.engine import Connection, Engine
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


DEFAULT_WORKSPACE_ID = "default"
"""The workspace every project and run belonged to before workspaces existed. In
``ARCHSIM_AUTH_MODE=none`` every request is its owner, the local user."""
LOCAL_USER_ID = "local"
"""The user every request is in ``ARCHSIM_AUTH_MODE=none`` (no sign-in)."""

ROLES = ("viewer", "editor", "owner")
"""Membership roles, weakest first: viewers read, editors also change projects and runs,
owners also manage the workspace, its members and invitations."""
WORKSPACE_KINDS = ("personal", "team", "sandbox")


# -- identity --------------------------------------------------------------------------------
class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    email: Mapped[str | None] = mapped_column(String(320), nullable=True, index=True)
    name: Mapped[str] = mapped_column(String(200))
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    disabled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Identity(Base):
    """A sign-in account at a provider (GitHub, Google) that belongs to a user."""

    __tablename__ = "identities"
    __table_args__ = (
        UniqueConstraint("provider", "subject", name="uq_identities_provider_subject"),
    )

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    provider: Mapped[str] = mapped_column(String(32))
    subject: Mapped[str] = mapped_column(String(255))
    """The provider's stable id for the account (GitHub user id, Google ``sub``)."""
    email: Mapped[str | None] = mapped_column(String(320), nullable=True)
    email_verified: Mapped[bool] = mapped_column(Boolean, default=False)
    login: Mapped[str | None] = mapped_column(String(200), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Workspace(Base):
    """Projects and runs belong to a workspace; people reach them through a membership."""

    __tablename__ = "workspaces"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(120))
    kind: Mapped[str] = mapped_column(String(16))  # personal | team | sandbox
    max_concurrent_runs: Mapped[int | None] = mapped_column(Integer, nullable=True)
    """Runs of this workspace simulated at once, across all workers (None: its kind's limit)."""
    max_runs_per_hour: Mapped[int | None] = mapped_column(Integer, nullable=True)
    """Runs it may queue per hour (None: its kind's limit)."""
    created_by: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Membership(Base):
    __tablename__ = "memberships"

    workspace_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True, index=True
    )
    role: Mapped[str] = mapped_column(String(16))  # viewer | editor | owner
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Invitation(Base):
    """A link that makes whoever opens it (signed in, with the email if one is set) a member."""

    __tablename__ = "invitations"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    workspace_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("workspaces.id", ondelete="CASCADE"), index=True
    )
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    role: Mapped[str] = mapped_column(String(16))
    email: Mapped[str | None] = mapped_column(String(320), nullable=True)
    created_by: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    accepted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    accepted_by: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )


class UserSession(Base):
    """A signed-in browser. The cookie holds a random secret; only its SHA-256 is stored."""

    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)  # sha256 of the cookie value
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    user_agent: Mapped[str | None] = mapped_column(String(300), nullable=True)


class ApiToken(Base):
    """A personal access token (``asim_pat_...``) for the CLI, CI and MCP, sent as
    ``Authorization: Bearer``. Only its SHA-256 is stored. ``scope`` is ``read`` or ``write``;
    ``workspace_id`` limits it to one workspace (None: all of its user's)."""

    __tablename__ = "api_tokens"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    workspace_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=True
    )
    name: Mapped[str] = mapped_column(String(100))
    prefix: Mapped[str] = mapped_column(String(24))
    """The token's first characters, to recognise it in a list."""
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    scope: Mapped[str] = mapped_column(String(16))  # read | write
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


# -- projects and runs ------------------------------------------------------------------------
class Project(Base):
    __tablename__ = "projects"
    __table_args__ = (Index("ix_projects_workspace_updated", "workspace_id", "updated_at"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    # Python-side default only: routes always set it; rows made directly (tests, `seed`) land in
    # the default workspace.
    workspace_id: Mapped[str] = mapped_column(
        String(32),
        ForeignKey("workspaces.id", ondelete="CASCADE", name="fk_projects_workspace_id"),
        default=DEFAULT_WORKSPACE_ID,
    )
    created_by: Mapped[str | None] = mapped_column(
        String(32),
        ForeignKey("users.id", ondelete="SET NULL", name="fk_projects_created_by"),
        nullable=True,
    )
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
    """A simulation run, which is also its job in the queue (see ``archsim_api.jobs.queue``)."""

    __tablename__ = "simulation_runs"
    __table_args__ = (
        Index("ix_runs_project_created", "project_id", "created_at"),
        Index("ix_runs_status_created", "status", "created_at"),  # oldest queued run first
        Index("ix_runs_workspace_status", "workspace_id", "status"),  # runs at once, per workspace
    )

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    project_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("projects.id", ondelete="CASCADE"), index=True
    )
    # The project's workspace, copied so the queue counts runs per workspace without a join.
    workspace_id: Mapped[str] = mapped_column(
        String(32),
        ForeignKey("workspaces.id", ondelete="CASCADE", name="fk_runs_workspace_id"),
        default=DEFAULT_WORKSPACE_ID,
    )
    created_by: Mapped[str | None] = mapped_column(
        String(32),
        ForeignKey("users.id", ondelete="SET NULL", name="fk_runs_created_by"),
        nullable=True,
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
    # Queue bookkeeping: how many times a worker took the run, which worker holds it and until
    # when (it renews the lease while the run is alive), and when someone asked to cancel it.
    attempts: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    lease_owner: Mapped[str | None] = mapped_column(String(64), nullable=True)
    lease_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    cancel_requested_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # The largest this run may grow, from its workspace's limits when it was queued (None: the
    # worker's own).
    max_requests: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    max_wall_seconds: Mapped[float | None] = mapped_column(Float, nullable=True)
    # Large payloads are deferred so listing runs stays cheap.
    input_document: Mapped[dict[str, Any]] = deferred(mapped_column(JSONType, nullable=False))
    report: Mapped[dict[str, Any] | None] = deferred(mapped_column(JSONType, nullable=True))

    project: Mapped[Project] = relationship(back_populates="runs")


class JobWorker(Base):
    """A worker that takes runs from the queue, with its latest heartbeat."""

    __tablename__ = "job_workers"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    hostname: Mapped[str] = mapped_column(String(255))
    pid: Mapped[int] = mapped_column(Integer)
    slots: Mapped[int] = mapped_column(Integer)
    running: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(16), default="running")  # running | draining
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    heartbeat_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, index=True
    )


class RateLimit(Base):
    """A rate-limit counter: requests counted against ``key`` in the window starting at
    ``window_start`` (Unix seconds). See ``archsim_api.limits``."""

    __tablename__ = "rate_limits"

    key: Mapped[str] = mapped_column(String(200), primary_key=True)
    window_start: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=False)
    count: Mapped[int] = mapped_column(Integer)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)


# -- review: comments, sign-offs and history ---------------------------------------------------
COMMENT_ANCHORS = ("node", "edge", "project", "run")
REVIEW_DECISIONS = ("approved", "changes_requested")
ACTOR_KINDS = ("user", "token", "system")

# SQLite only numbers INTEGER primary keys by itself; PostgreSQL gets a BIGSERIAL.
AutoBigInt = BigInteger().with_variant(Integer(), "sqlite")


class Comment(Base):
    """A comment on a part of a design (a node or link, by its id in the document), on the
    project or on a run. Replies point at the first comment of their thread (``parent_id``);
    resolving the thread marks that first comment. A comment whose part was deleted since is
    shown as detached, never lost."""

    __tablename__ = "comments"
    __table_args__ = (Index("ix_comments_project_created", "project_id", "created_at"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    workspace_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("workspaces.id", ondelete="CASCADE")
    )
    project_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("projects.id", ondelete="CASCADE")
    )
    anchor_kind: Mapped[str] = mapped_column(String(16))  # node | edge | project | run
    anchor_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    """The node or edge id in the document, or the run id (None for the project)."""
    run_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("simulation_runs.id", ondelete="SET NULL"), nullable=True, index=True
    )
    """The run the comment is about (a run comment) or was written next to."""
    parent_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("comments.id", ondelete="CASCADE"), nullable=True, index=True
    )
    author_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    body: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    edited_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    resolved_by: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )


class ReviewSignoff(Base):
    """A reviewer's decision on a run of a design: approved, or changes requested. It records
    the project's revision and the run's digests, so it goes stale once the design changes."""

    __tablename__ = "review_signoffs"
    __table_args__ = (Index("ix_signoffs_project_created", "project_id", "created_at"),)

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    project_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("projects.id", ondelete="CASCADE")
    )
    run_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("simulation_runs.id", ondelete="SET NULL"), nullable=True, index=True
    )
    project_revision: Mapped[int] = mapped_column(Integer)
    input_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    result_digest: Mapped[str | None] = mapped_column(String(64), nullable=True)
    reviewer_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    decision: Mapped[str] = mapped_column(String(24))  # approved | changes_requested
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class AuditEvent(Base):
    """One change, written in the same transaction as the change (``archsim_api.audit``).
    ``project_id`` has no foreign key: a project's history outlives the project."""

    __tablename__ = "audit_events"
    __table_args__ = (
        Index("ix_audit_workspace_id", "workspace_id", "id"),
        Index("ix_audit_project_id", "project_id", "id"),
    )

    id: Mapped[int] = mapped_column(AutoBigInt, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("workspaces.id", ondelete="CASCADE")
    )
    project_id: Mapped[str | None] = mapped_column(String(32), nullable=True)
    actor_user_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    actor_kind: Mapped[str] = mapped_column(String(16))  # user | token | system
    action: Mapped[str] = mapped_column(String(64))
    target_kind: Mapped[str] = mapped_column(String(32))
    target_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    meta: Mapped[dict[str, Any] | None] = mapped_column(JSONType, nullable=True)
    ip: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


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

    @contextmanager
    def write_transaction(self) -> Iterator[Connection]:
        """A transaction that takes the write lock before it reads anything.

        On SQLite that is ``BEGIN IMMEDIATE``: processes sharing the file wait for each other
        (up to the busy timeout) instead of failing when a read turns into a write. Elsewhere it
        is an ordinary transaction; PostgreSQL callers lock rows themselves. Commits on success,
        rolls back on error."""
        with self.engine.connect() as conn:
            if self.dialect == "sqlite":
                conn.exec_driver_sql("BEGIN IMMEDIATE")
            try:
                yield conn
            except BaseException:
                conn.rollback()
                raise
            conn.commit()

    @property
    def dialect(self) -> str:
        return self.engine.dialect.name
