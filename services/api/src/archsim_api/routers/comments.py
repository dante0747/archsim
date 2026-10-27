"""Comments on a design: on a part (node or link), on the project or on a run, in threads that
anyone in the workspace can resolve. Viewers comment too; authors edit and delete their own
comments, and owners may delete any. A comment whose part was deleted stays, marked detached.
Every change is recorded in the project's history."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api import limits
from archsim_api.audit import audit, excerpt
from archsim_api.config import Settings
from archsim_api.db import Comment, Database, Project, utcnow
from archsim_api.deps import get_db, get_principal, get_session, get_settings_dep, require_project
from archsim_api.review import (
    COMMENTS_PER_USER,
    CommentCreate,
    CommentOut,
    CommentResolve,
    CommentUpdate,
    comment_access,
    comments_out,
    run_of_project,
    writable,
)
from archsim_api.security import Principal, ProjectAccess

router = APIRouter(tags=["comments"])

MAX_LISTED = 2000


def _audit(session: Session, action: str, c: Comment, **meta: object) -> None:
    audit(
        session,
        action,
        workspace_id=c.workspace_id,
        target_kind="comment",
        target_id=c.id,
        project_id=c.project_id,
        meta={"anchorKind": c.anchor_kind, "anchorId": c.anchor_id, **meta},
    )


def _one(
    session: Session, project: Project, c: Comment, principal: Principal, role: str
) -> CommentOut:
    return comments_out(session, project, [c], principal, role)[0]


@router.get("/projects/{project_id}/comments", response_model=list[CommentOut])
def list_comments(
    anchor_kind: Literal["node", "edge", "project", "run"] | None = Query(
        default=None, alias="anchorKind"
    ),
    anchor_id: str | None = Query(default=None, alias="anchorId", max_length=64),
    run_id: str | None = Query(default=None, alias="runId", max_length=32),
    access: ProjectAccess = Depends(require_project("viewer")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> list[CommentOut]:
    """The project's comments, oldest first (replies carry ``parentId``), optionally only
    those on one part, run or the project. ``detached`` marks comments whose part or run is
    gone from the saved design."""
    stmt = select(Comment).where(Comment.project_id == access.project.id)
    if anchor_kind is not None:
        stmt = stmt.where(Comment.anchor_kind == anchor_kind)
    if anchor_id is not None:
        stmt = stmt.where(Comment.anchor_id == anchor_id)
    if run_id is not None:
        stmt = stmt.where(Comment.run_id == run_id)
    rows = list(
        session.execute(stmt.order_by(Comment.created_at, Comment.id).limit(MAX_LISTED)).scalars()
    )
    return comments_out(session, access.project, rows, principal, access.role)


@router.post("/projects/{project_id}/comments", response_model=CommentOut, status_code=201)
def create_comment(
    body: CommentCreate,
    access: ProjectAccess = Depends(require_project("viewer")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> CommentOut:
    """Comment on a part (``anchorKind`` node or edge, with its id), the project or a run, or
    reply to a thread (``parentId``: the reply joins that thread's part)."""
    writable(principal)
    project = access.project
    text = body.body.strip()
    if not text:
        raise HTTPException(422, detail={"message": "A comment needs some text", "issues": []})
    limits.enforce(
        settings,
        db,
        f"user:{principal.user_id}:comments",
        COMMENTS_PER_USER,
        "You have written many comments this hour.",
    )
    anchor_kind: str = body.anchor_kind
    anchor_id = body.anchor_id
    run_id = body.run_id
    parent_id: str | None = None
    if body.parent_id is not None:
        parent = session.get(Comment, body.parent_id)
        if parent is None or parent.project_id != project.id:
            raise HTTPException(
                404, detail={"message": "The comment to reply to was not found", "issues": []}
            )
        root = session.get(Comment, parent.parent_id) if parent.parent_id else parent
        assert root is not None
        parent_id = root.id
        anchor_kind, anchor_id, run_id = root.anchor_kind, root.anchor_id, root.run_id
    elif anchor_kind == "project":
        anchor_id = None
    elif not (anchor_id or (anchor_kind == "run" and run_id)):
        raise HTTPException(
            422, detail={"message": f"Say which {anchor_kind} the comment is on", "issues": []}
        )
    elif anchor_kind == "run":
        run_id = run_of_project(session, project, anchor_id or run_id or "").id
        anchor_id = run_id
    if run_id is not None and parent_id is None and anchor_kind != "run":
        run_of_project(session, project, run_id)
    c = Comment(
        workspace_id=project.workspace_id,
        project_id=project.id,
        anchor_kind=anchor_kind,
        anchor_id=anchor_id,
        run_id=run_id,
        parent_id=parent_id,
        author_id=principal.user_id,
        body=text,
    )
    session.add(c)
    session.flush()
    _audit(session, "comment.create", c, reply=parent_id is not None, excerpt=excerpt(text))
    session.commit()
    return _one(session, project, c, principal, access.role)


@router.patch("/comments/{comment_id}", response_model=CommentOut)
def edit_comment(
    comment_id: str,
    body: CommentUpdate,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> CommentOut:
    """Change the text of your own comment."""
    writable(principal)
    c, project, role = comment_access(session, principal, comment_id)
    if c.author_id != principal.user_id:
        raise HTTPException(
            403, detail={"message": "Only its author can edit a comment.", "issues": []}
        )
    text = body.body.strip()
    if not text:
        raise HTTPException(422, detail={"message": "A comment needs some text", "issues": []})
    if text != c.body:
        c.body = text
        c.edited_at = utcnow()
        _audit(session, "comment.edit", c, excerpt=excerpt(text))
        session.commit()
    return _one(session, project, c, principal, role)


@router.delete("/comments/{comment_id}", status_code=204)
def delete_comment(
    comment_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> Response:
    """Delete your own comment (owners: any comment). Deleting the first comment of a thread
    deletes its replies."""
    writable(principal)
    c, _project, role = comment_access(session, principal, comment_id)
    if c.author_id != principal.user_id and role != "owner":
        raise HTTPException(
            403,
            detail={
                "message": "Only its author or a workspace owner can delete a comment.",
                "issues": [],
            },
        )
    _audit(
        session,
        "comment.delete",
        c,
        excerpt=excerpt(c.body),
        byAuthor=c.author_id == principal.user_id,
    )
    session.delete(c)
    session.commit()
    return Response(status_code=204)


@router.post("/comments/{comment_id}/resolve", response_model=CommentOut)
def resolve_comment(
    comment_id: str,
    body: CommentResolve | None = None,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> CommentOut:
    """Resolve the thread this comment is in (``{"resolved": false}`` reopens it). Anyone in
    the workspace may. Returns the thread's first comment."""
    writable(principal)
    c, project, role = comment_access(session, principal, comment_id)
    root = session.get(Comment, c.parent_id) if c.parent_id else c
    assert root is not None
    resolved = True if body is None else body.resolved
    if resolved != (root.resolved_at is not None):
        root.resolved_at = utcnow() if resolved else None
        root.resolved_by = principal.user_id if resolved else None
        _audit(session, "comment.resolve" if resolved else "comment.reopen", root)
        session.commit()
    return _one(session, project, root, principal, role)
