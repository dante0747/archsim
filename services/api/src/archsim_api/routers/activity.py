"""History: what changed in a project or a workspace, newest first, a page at a time. Anyone
in the workspace reads it; only owners see the addresses changes came from."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api.db import AuditEvent
from archsim_api.deps import get_principal, get_session, require_project
from archsim_api.review import ActivityOut, ActivityPage, people
from archsim_api.security import Principal, ProjectAccess, workspace_access

router = APIRouter(tags=["activity"])

DEFAULT_PAGE = 50
MAX_PAGE = 200


def _page(session: Session, where: Any, before: int | None, limit: int, role: str) -> ActivityPage:
    stmt = select(AuditEvent).where(where)
    if before is not None:
        stmt = stmt.where(AuditEvent.id < before)
    rows = list(session.execute(stmt.order_by(AuditEvent.id.desc()).limit(limit + 1)).scalars())
    more = len(rows) > limit
    rows = rows[:limit]
    who = people(session, [r.actor_user_id for r in rows])
    items = [
        ActivityOut(
            id=r.id,
            action=r.action,
            target_kind=r.target_kind,
            target_id=r.target_id,
            project_id=r.project_id,
            actor=who.get(r.actor_user_id or ""),
            actor_kind=r.actor_kind,
            meta=r.meta,
            ip=r.ip if role == "owner" else None,
            created_at=r.created_at,
        )
        for r in rows
    ]
    return ActivityPage(items=items, next_before=rows[-1].id if more and rows else None)


@router.get("/projects/{project_id}/activity", response_model=ActivityPage)
def project_activity(
    before: int | None = Query(default=None, ge=1, description="Events older than this id."),
    limit: int = Query(default=DEFAULT_PAGE, ge=1, le=MAX_PAGE),
    access: ProjectAccess = Depends(require_project("viewer")),
    session: Session = Depends(get_session),
) -> ActivityPage:
    """The project's history: edits, runs, comments and sign-offs."""
    return _page(session, AuditEvent.project_id == access.project.id, before, limit, access.role)


@router.get("/workspaces/{workspace_id}/activity", response_model=ActivityPage)
def workspace_activity(
    workspace_id: str,
    before: int | None = Query(default=None, ge=1, description="Events older than this id."),
    limit: int = Query(default=DEFAULT_PAGE, ge=1, le=MAX_PAGE),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> ActivityPage:
    """The workspace's history, including members, invitations and deleted projects."""
    access = workspace_access(session, principal, workspace_id)
    return _page(session, AuditEvent.workspace_id == workspace_id, before, limit, access.role)
