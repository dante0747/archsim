"""Review sign-off: a reviewer approves a run of a design or asks for changes, with a note. A
sign-off records the project's revision and the run's digests, and goes stale once the design
is saved again. Viewers sign off too; a reviewer may withdraw their own sign-off."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api import limits
from archsim_api.audit import audit, excerpt
from archsim_api.config import Settings
from archsim_api.db import Database, Project, ReviewSignoff
from archsim_api.deps import (
    get_db,
    get_principal,
    get_session,
    get_settings_dep,
    require_project,
    require_run,
)
from archsim_api.review import SIGNOFFS_PER_USER, SignoffCreate, SignoffOut, signoffs_out, writable
from archsim_api.security import Principal, ProjectAccess, RunAccess, not_found

router = APIRouter(tags=["reviews"])

MAX_LISTED = 500


def _audit(session: Session, action: str, s: ReviewSignoff, workspace_id: str) -> None:
    audit(
        session,
        action,
        workspace_id=workspace_id,
        target_kind="signoff",
        target_id=s.id,
        project_id=s.project_id,
        meta={
            "decision": s.decision,
            "runId": s.run_id,
            "revision": s.project_revision,
            "note": excerpt(s.note) if s.note else None,
        },
    )


@router.post("/runs/{run_id}/signoffs", response_model=SignoffOut, status_code=201)
def sign_off(
    body: SignoffCreate,
    access: RunAccess = Depends(require_run("viewer")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> SignoffOut:
    """Approve a finished run of the design, or request changes. It applies to the design as
    saved now (its revision) and to this run's input and result digests."""
    writable(principal)
    run = access.run
    if run.status != "succeeded":
        raise HTTPException(
            409,
            detail={"message": f"Run is {run.status}; sign off on a finished run", "issues": []},
        )
    project = session.get(Project, run.project_id)
    assert project is not None
    limits.enforce(
        settings,
        db,
        f"user:{principal.user_id}:signoffs",
        SIGNOFFS_PER_USER,
        "You have signed off many times this hour.",
    )
    note = (body.note or "").strip() or None
    s = ReviewSignoff(
        project_id=project.id,
        run_id=run.id,
        project_revision=project.revision,
        input_digest=run.input_digest,
        result_digest=run.result_digest,
        reviewer_id=principal.user_id,
        decision=body.decision,
        note=note,
    )
    session.add(s)
    session.flush()
    _audit(session, "signoff.create", s, project.workspace_id)
    session.commit()
    return signoffs_out(session, project, [s], principal)[0]


@router.get("/runs/{run_id}/signoffs", response_model=list[SignoffOut])
def run_signoffs(
    access: RunAccess = Depends(require_run("viewer")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> list[SignoffOut]:
    """Sign-offs of one run, newest first."""
    project = session.get(Project, access.run.project_id)
    assert project is not None
    rows = list(
        session.execute(
            select(ReviewSignoff)
            .where(ReviewSignoff.run_id == access.run.id)
            .order_by(ReviewSignoff.created_at.desc())
            .limit(MAX_LISTED)
        ).scalars()
    )
    return signoffs_out(session, project, rows, principal)


@router.get("/projects/{project_id}/signoffs", response_model=list[SignoffOut])
def project_signoffs(
    access: ProjectAccess = Depends(require_project("viewer")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> list[SignoffOut]:
    """Every sign-off of the project's runs, newest first; ``stale`` ones predate the design
    as saved now."""
    rows = list(
        session.execute(
            select(ReviewSignoff)
            .where(ReviewSignoff.project_id == access.project.id)
            .order_by(ReviewSignoff.created_at.desc())
            .limit(MAX_LISTED)
        ).scalars()
    )
    return signoffs_out(session, access.project, rows, principal)


@router.delete("/signoffs/{signoff_id}", status_code=204)
def withdraw_signoff(
    signoff_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> Response:
    """Withdraw your own sign-off."""
    writable(principal)
    s = session.get(ReviewSignoff, signoff_id)
    project = session.get(Project, s.project_id) if s is not None else None
    role = principal.role_in(session, project.workspace_id) if project is not None else None
    if s is None or project is None or role is None:
        raise not_found(f"Sign-off '{signoff_id}'")
    if s.reviewer_id != principal.user_id:
        raise HTTPException(
            403, detail={"message": "Only the reviewer can withdraw a sign-off.", "issues": []}
        )
    _audit(session, "signoff.withdraw", s, project.workspace_id)
    session.delete(s)
    session.commit()
    return Response(status_code=204)
