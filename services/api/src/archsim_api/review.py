"""Reviewing designs together: request and answer models, and helpers shared by the comments,
sign-off and activity routes.

Who may do what (roles in the project's workspace):
- viewers read, comment, reply, resolve threads and sign off;
- editors also change the design (``routers.projects``);
- a comment's author edits or deletes it; an owner may delete any comment;
- a read-only API token reads only.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any, Literal

from fastapi import HTTPException
from pydantic import Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api.db import Comment, Project, ReviewSignoff, SimulationRun, User
from archsim_api.schemas import ApiModel, UtcDateTime
from archsim_api.security import Principal, at_least, forbidden, not_found

AnchorKind = Literal["node", "edge", "project", "run"]
Decision = Literal["approved", "changes_requested"]

MAX_BODY = 4000
MAX_NOTE = 2000
COMMENTS_PER_USER = (240, 3600)
SIGNOFFS_PER_USER = (60, 3600)


# -- models -----------------------------------------------------------------------------------
class PersonOut(ApiModel):
    id: str
    name: str
    avatar_url: str | None = None


class CommentCreate(ApiModel):
    anchor_kind: AnchorKind = "project"
    anchor_id: str | None = Field(
        default=None,
        max_length=64,
        description="The node or edge id in the document, or the run id (none for the project).",
    )
    run_id: str | None = Field(
        default=None,
        max_length=32,
        description="The run this comment is about or was written next to (optional).",
    )
    parent_id: str | None = Field(
        default=None, max_length=32, description="Reply to this comment's thread."
    )
    body: str = Field(min_length=1, max_length=MAX_BODY)


class CommentUpdate(ApiModel):
    body: str = Field(min_length=1, max_length=MAX_BODY)


class CommentResolve(ApiModel):
    resolved: bool = True


class CommentOut(ApiModel):
    id: str
    project_id: str
    anchor_kind: AnchorKind
    anchor_id: str | None
    run_id: str | None
    parent_id: str | None
    author: PersonOut | None
    body: str
    created_at: UtcDateTime
    edited_at: UtcDateTime | None
    resolved_at: UtcDateTime | None
    resolved_by: PersonOut | None
    detached: bool = Field(
        description="The part or run it is about is gone from the saved design (deleted since)."
    )
    can_edit: bool
    can_delete: bool


class SignoffCreate(ApiModel):
    decision: Decision
    note: str | None = Field(default=None, max_length=MAX_NOTE)


class SignoffOut(ApiModel):
    id: str
    project_id: str
    run_id: str | None
    run_label: str | None
    project_revision: int
    current_revision: int
    stale: bool = Field(description="The design was saved again since this sign-off.")
    input_digest: str | None
    result_digest: str | None
    reviewer: PersonOut | None
    decision: Decision
    note: str | None
    created_at: UtcDateTime
    can_withdraw: bool


class ActivityOut(ApiModel):
    id: int
    action: str
    target_kind: str
    target_id: str | None
    project_id: str | None
    actor: PersonOut | None
    actor_kind: Literal["user", "token", "system"]
    meta: dict[str, Any] | None
    ip: str | None = Field(default=None, description="Shown to workspace owners only.")
    created_at: UtcDateTime


class ActivityPage(ApiModel):
    items: list[ActivityOut]
    next_before: int | None = Field(
        description="Pass as ``before`` for the next (older) page; null on the last page."
    )


# -- helpers ----------------------------------------------------------------------------------
def writable(principal: Principal) -> None:
    """Comments and sign-offs are changes: a read-only API token cannot make them."""
    if principal.read_only:
        raise HTTPException(
            403,
            detail={
                "message": "This API token can only read: use a token with write scope.",
                "issues": [],
            },
        )


def people(session: Session, ids: Iterable[str | None]) -> dict[str, PersonOut]:
    wanted = {i for i in ids if i}
    if not wanted:
        return {}
    rows = session.execute(select(User).where(User.id.in_(wanted))).scalars()
    return {u.id: PersonOut(id=u.id, name=u.name, avatar_url=u.avatar_url) for u in rows}


def part_ids(document: dict[str, Any]) -> tuple[set[str], set[str]]:
    """The node ids and edge ids of a saved document."""
    arch = document.get("architecture") or {}
    nodes = {str(n.get("id")) for n in arch.get("nodes") or [] if isinstance(n, dict)}
    edges = {str(e.get("id")) for e in arch.get("edges") or [] if isinstance(e, dict)}
    return nodes, edges


def is_detached(comment: Comment, nodes: set[str], edges: set[str]) -> bool:
    if comment.anchor_kind == "node":
        return comment.anchor_id not in nodes
    if comment.anchor_kind == "edge":
        return comment.anchor_id not in edges
    if comment.anchor_kind == "run":
        return comment.run_id is None
    return False


def comments_out(
    session: Session,
    project: Project,
    comments: list[Comment],
    principal: Principal,
    role: str,
) -> list[CommentOut]:
    nodes, edges = part_ids(project.document)
    who = people(session, [c.author_id for c in comments] + [c.resolved_by for c in comments])
    can_write = not principal.read_only
    return [
        CommentOut(
            id=c.id,
            project_id=c.project_id,
            anchor_kind=c.anchor_kind,
            anchor_id=c.anchor_id,
            run_id=c.run_id,
            parent_id=c.parent_id,
            author=who.get(c.author_id or ""),
            body=c.body,
            created_at=c.created_at,
            edited_at=c.edited_at,
            resolved_at=c.resolved_at,
            resolved_by=who.get(c.resolved_by or ""),
            detached=is_detached(c, nodes, edges),
            can_edit=can_write and c.author_id == principal.user_id,
            can_delete=can_write and (c.author_id == principal.user_id or role == "owner"),
        )
        for c in comments
    ]


def signoffs_out(
    session: Session, project: Project, rows: list[ReviewSignoff], principal: Principal
) -> list[SignoffOut]:
    who = people(session, [r.reviewer_id for r in rows])
    run_ids = {r.run_id for r in rows if r.run_id}
    labels: dict[str, str | None] = {}
    if run_ids:
        labels = {
            rid: label
            for rid, label in session.execute(
                select(SimulationRun.id, SimulationRun.label).where(SimulationRun.id.in_(run_ids))
            ).all()
        }
    return [
        SignoffOut(
            id=r.id,
            project_id=r.project_id,
            run_id=r.run_id,
            run_label=labels.get(r.run_id) if r.run_id else None,
            project_revision=r.project_revision,
            current_revision=project.revision,
            stale=project.revision > r.project_revision,
            input_digest=r.input_digest,
            result_digest=r.result_digest,
            reviewer=who.get(r.reviewer_id or ""),
            decision=r.decision,
            note=r.note,
            created_at=r.created_at,
            can_withdraw=not principal.read_only and r.reviewer_id == principal.user_id,
        )
        for r in rows
    ]


def comment_access(
    session: Session, principal: Principal, comment_id: str, minimum: str = "viewer"
) -> tuple[Comment, Project, str]:
    """A comment, its project and the principal's role there (404 without a role, like
    projects; 403 when the role is too weak)."""
    comment = session.get(Comment, comment_id)
    role = principal.role_in(session, comment.workspace_id) if comment is not None else None
    if comment is None or role is None:
        raise not_found(f"Comment '{comment_id}'")
    if not at_least(role, minimum):
        raise forbidden(role, minimum)
    project = session.get(Project, comment.project_id)
    assert project is not None  # comments go with their project
    return comment, project, role


def run_of_project(session: Session, project: Project, run_id: str) -> SimulationRun:
    run = session.get(SimulationRun, run_id)
    if run is None or run.project_id != project.id:
        raise not_found(f"Run '{run_id}' of this project")
    return run
