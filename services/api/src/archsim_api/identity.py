"""Users, workspaces and memberships: the response models and the rules they share (who may
manage a workspace, the last owner, what a new user gets)."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Literal

from fastapi import HTTPException
from pydantic import Field
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from archsim_api.db import (
    DEFAULT_WORKSPACE_ID,
    LOCAL_USER_ID,
    Database,
    Membership,
    User,
    Workspace,
)
from archsim_api.schemas import ApiModel, UtcDateTime

Role = Literal["viewer", "editor", "owner"]
WorkspaceKind = Literal["personal", "team", "sandbox"]


class UserOut(ApiModel):
    id: str
    name: str
    email: str | None
    avatar_url: str | None


class WorkspaceOut(ApiModel):
    id: str
    name: str
    kind: WorkspaceKind
    role: Role
    """Your role in it."""
    max_concurrent_runs: int | None
    max_runs_per_hour: int | None
    created_at: UtcDateTime


class MemberOut(ApiModel):
    user_id: str
    name: str
    email: str | None
    avatar_url: str | None
    role: Role
    joined_at: UtcDateTime


def conflict(message: str) -> HTTPException:
    return HTTPException(409, detail={"message": message, "issues": []})


def workspace_out(ws: Workspace, role: str) -> WorkspaceOut:
    return WorkspaceOut.model_validate(
        {
            "id": ws.id,
            "name": ws.name,
            "kind": ws.kind,
            "role": role,
            "max_concurrent_runs": ws.max_concurrent_runs,
            "max_runs_per_hour": ws.max_runs_per_hour,
            "created_at": ws.created_at,
        }
    )


def user_out(user: User) -> UserOut:
    return UserOut.model_validate(user)


def workspaces_of(
    session: Session, user_id: str, only: str | None = None, read_only: bool = False
) -> list[WorkspaceOut]:
    """The workspaces a user belongs to (``only``: just that one, for a one-workspace token),
    personal first, then by name."""
    stmt = (
        select(Workspace, Membership.role)
        .join(Membership, Membership.workspace_id == Workspace.id)
        .where(Membership.user_id == user_id)
    )
    if only is not None:
        stmt = stmt.where(Workspace.id == only)
    rows = session.execute(stmt).all()
    out = [workspace_out(ws, "viewer" if read_only else role) for ws, role in rows]
    return sorted(out, key=lambda w: (w.kind != "personal", w.name.lower(), w.id))


def owner_count(session: Session, workspace_id: str) -> int:
    return int(
        session.execute(
            select(func.count())
            .select_from(Membership)
            .where(Membership.workspace_id == workspace_id, Membership.role == "owner")
        ).scalar_one()
    )


def create_workspace(
    session: Session, name: str, kind: str, owner_id: str, workspace_id: str | None = None
) -> Workspace:
    """A workspace with ``owner_id`` as its owner (not committed)."""
    ws = Workspace(name=name[:120], kind=kind, created_by=owner_id)
    if workspace_id is not None:
        ws.id = workspace_id
    session.add(ws)
    session.flush()
    session.add(Membership(workspace_id=ws.id, user_id=owner_id, role="owner"))
    return ws


def ensure_defaults(db: Database) -> None:
    """The local user and the default workspace it owns. Migration 0004 creates them; this
    covers databases created from the models directly (``ARCHSIM_AUTO_MIGRATE=false`` on an
    empty database)."""
    with db.session() as s:
        if s.get(Workspace, DEFAULT_WORKSPACE_ID) is not None:
            return
        try:
            if s.get(User, LOCAL_USER_ID) is None:
                s.add(User(id=LOCAL_USER_ID, name="Local user"))
                s.flush()
            create_workspace(s, "Default", "team", LOCAL_USER_ID, DEFAULT_WORKSPACE_ID)
            s.commit()
        except IntegrityError:  # another instance made them first
            s.rollback()


class WorkspaceCreate(ApiModel):
    name: str = Field(min_length=1, max_length=120)


class WorkspaceUpdate(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)


class MemberUpdate(ApiModel):
    role: Role


class InvitationCreate(ApiModel):
    role: Role = "editor"
    email: str | None = Field(
        default=None,
        max_length=320,
        pattern=r"^[^@\s]+@[^@\s]+$",
        description="Only someone signed in with this verified email can accept it",
    )
    expires_in_days: int = Field(default=7, ge=1, le=30)


class InvitationOut(ApiModel):
    id: str
    workspace_id: str
    role: Role
    email: str | None
    created_at: UtcDateTime
    expires_at: UtcDateTime
    accepted_at: UtcDateTime | None


class InvitationCreated(InvitationOut):
    token: str
    """Shown once. The link to share is ``<web address>/invite/<token>``."""


class InvitationToken(ApiModel):
    token: str = Field(min_length=10, max_length=200)


class InvitationPreview(ApiModel):
    workspace_name: str
    role: Role
    email: str | None
    expires_at: UtcDateTime
    invited_by: str | None


def as_utc(value: datetime) -> datetime:
    """SQLite hands back times without a zone; they are stored in UTC."""
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value
