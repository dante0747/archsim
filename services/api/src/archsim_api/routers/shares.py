"""Share links: a frozen, read-only copy of one run's results that anyone with the link can open,
without signing in, until it expires or is revoked.

A share is a snapshot, not a pointer to the run: digests are guessable (the same template gives
the same digest) and cannot be revoked, runs go when their project is deleted, and the report
format changes between versions. The snapshot holds the report without its request traces, the
objectives from the run's input document, the project name, and that input document (so "Open a
copy" can make a project from it; the public answer leaves it out). Addresses of telemetry
sources (a calibration's Prometheus URL) are hidden before anything is stored.

Editors make and revoke shares; viewers list them. ``GET /public/shares/{slug}`` needs no sign-in
and is limited per address; it answers 404 alike for links that never existed, expired or were
revoked. Revoking clears the stored payload at once.
"""

from __future__ import annotations

import copy
import re
import secrets
import string
from datetime import timedelta
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import Field
from sqlalchemy import select, update
from sqlalchemy.orm import Session, undefer

from archsim_api import limits
from archsim_api.audit import audit
from archsim_api.config import Settings
from archsim_api.db import Database, Project, SharedReport, utcnow
from archsim_api.deps import (
    get_db,
    get_principal,
    get_session,
    get_settings_dep,
    get_workspace,
    require_project,
    require_run,
)
from archsim_api.identity import as_utc
from archsim_api.routers.projects import _create, _out
from archsim_api.schemas import ApiModel, ProjectOut, UtcDateTime
from archsim_api.security import (
    Principal,
    ProjectAccess,
    RunAccess,
    WorkspaceAccess,
    at_least,
    client_ip,
    forbidden,
    not_found,
)

router = APIRouter(tags=["shares"])

PAYLOAD_VERSION = 1
"""Version of the stored snapshot's shape (``payload``), apart from the report's own."""

SLUG_ALPHABET = string.digits + string.ascii_uppercase + string.ascii_lowercase
SLUG_LENGTH = 22
"""128 random bits in base62 take at most 22 characters (62**22 > 2**128)."""
SLUG_PATTERN = re.compile(rf"^[0-9A-Za-z]{{{SLUG_LENGTH}}}$")

SHARES_PER_USER = (100, limits.DAY)
PUBLIC_VIEWS_PER_IP = (60, 60)
"""Public share answers per address: 60 a minute."""
LIST_LIMIT = 100

_ADDRESS = re.compile(r"\b[a-z][a-z0-9+.-]*://\S+", re.IGNORECASE)


def new_slug() -> str:
    """A random 128-bit link id, in base62 (22 characters)."""
    n = secrets.randbits(128)
    out = []
    for _ in range(SLUG_LENGTH):
        n, r = divmod(n, len(SLUG_ALPHABET))
        out.append(SLUG_ALPHABET[r])
    return "".join(out)


def hide_addresses(value: Any) -> Any:
    """A copy with every calibration's ``source`` stripped of addresses: "Prometheus
    https://user:pass@prom.internal:9090" becomes "Prometheus (address hidden)"."""
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for k, v in value.items():
            if k == "calibration" and isinstance(v, dict) and isinstance(v.get("source"), str):
                v = {**v, "source": _ADDRESS.sub("(address hidden)", v["source"])}
            out[k] = hide_addresses(v)
        return out
    if isinstance(value, list):
        return [hide_addresses(v) for v in value]
    return value


def public_report(report: dict[str, Any]) -> dict[str, Any]:
    """The report as shared: no request traces (they name every hop of sample requests and
    make up most of the size), no telemetry addresses."""
    out = {k: v for k, v in report.items() if k != "traces"}
    return dict(hide_addresses(copy.deepcopy(out)))


def build_payload(
    project_name: str,
    run_label: str | None,
    scenario_name: str | None,
    report: dict[str, Any],
    document: dict[str, Any],
) -> dict[str, Any]:
    objectives = document.get("objectives") if isinstance(document.get("objectives"), dict) else {}
    return {
        "projectName": project_name,
        "runLabel": run_label,
        "scenarioName": scenario_name,
        "objectives": copy.deepcopy(objectives),
        "report": public_report(report),
        "document": hide_addresses(copy.deepcopy(document)),
    }


# -- schemas ------------------------------------------------------------------------------------
class ShareCreate(ApiModel):
    title: str | None = Field(
        default=None,
        max_length=200,
        description="Shown on the page and in link previews (default: the project's name)",
    )
    expires_in_days: int | None = Field(
        default=30, ge=1, le=365, description="Days until the link stops working (null: never)"
    )


class ShareOut(ApiModel):
    id: str
    slug: str
    path: str
    """Where the page is on the web app: ``/r/<slug>``."""
    url: str | None
    """The full link, when the server knows its public address (``ARCHSIM_PUBLIC_URL``)."""
    title: str
    project_id: str | None
    run_id: str | None
    created_by: str | None
    created_at: UtcDateTime
    expires_at: UtcDateTime | None
    revoked_at: UtcDateTime | None
    view_count: int
    result_digest: str | None
    report_version: str
    status: Literal["active", "expired", "revoked"]


class PublicShareOut(ApiModel):
    slug: str
    title: str
    project_name: str
    run_label: str | None
    scenario_name: str | None
    created_at: UtcDateTime
    expires_at: UtcDateTime | None
    payload_version: int
    report_version: str
    result_digest: str | None
    objectives: dict[str, Any]
    report: dict[str, Any]
    can_copy: bool
    """Whether "Open a copy" can make a project from it (it holds the design)."""


class CopyFromShare(ApiModel):
    slug: str = Field(min_length=1, max_length=64)
    name: str | None = Field(default=None, min_length=1, max_length=120)


# -- helpers -----------------------------------------------------------------------------------
def _status(share: SharedReport) -> Literal["active", "expired", "revoked"]:
    if share.revoked_at is not None:
        return "revoked"
    if share.expires_at is not None and as_utc(share.expires_at) <= utcnow():
        return "expired"
    return "active"


def _share_out(share: SharedReport, settings: Settings) -> ShareOut:
    path = f"/r/{share.slug}"
    return ShareOut(
        id=share.id,
        slug=share.slug,
        path=path,
        url=f"{settings.public_url.rstrip('/')}{path}" if settings.public_url else None,
        title=share.title,
        project_id=share.project_id,
        run_id=share.run_id,
        created_by=share.created_by,
        created_at=share.created_at,
        expires_at=share.expires_at,
        revoked_at=share.revoked_at,
        view_count=share.view_count,
        result_digest=share.result_digest,
        report_version=share.report_version,
        status=_status(share),
    )


def _active_by_slug(session: Session, slug: str) -> SharedReport:
    """The share behind a link, with its payload, or 404 (unknown, expired or revoked alike)."""
    gone = not_found("Share link")
    if not SLUG_PATTERN.match(slug):
        raise gone
    share = session.execute(
        select(SharedReport).where(SharedReport.slug == slug).options(undefer(SharedReport.payload))
    ).scalar_one_or_none()
    if share is None or share.payload is None or _status(share) != "active":
        raise gone
    return share


def _list(session: Session, settings: Settings, *where: Any) -> list[ShareOut]:
    rows = session.execute(
        select(SharedReport)
        .where(*where)
        .order_by(SharedReport.created_at.desc())
        .limit(LIST_LIMIT)
    ).scalars()
    return [_share_out(s, settings) for s in rows]


# -- routes ------------------------------------------------------------------------------------
@router.post("/runs/{run_id}/shares", response_model=ShareOut, status_code=201)
def create_share(
    body: ShareCreate,
    access: RunAccess = Depends(require_run("editor", with_payload=True)),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> ShareOut:
    """A share link to this finished run's results: a frozen copy that does not change when the
    design does, or disappear with the run."""
    run = access.run
    if run.status != "succeeded" or run.report is None:
        raise HTTPException(
            409,
            detail={
                "message": f"Run is {run.status}; only finished runs can be shared",
                "issues": [],
            },
        )
    limits.enforce(
        settings,
        db,
        f"user:{principal.user_id}:shares",
        SHARES_PER_USER,
        "You have made many share links today.",
    )
    project = session.get(Project, run.project_id)
    project_name = project.name if project is not None else "Untitled architecture"
    title = (body.title or "").strip() or project_name
    share = SharedReport(
        slug=new_slug(),
        workspace_id=run.workspace_id,
        project_id=run.project_id,
        run_id=run.id,
        created_by=principal.user_id,
        title=title[:200],
        payload=build_payload(
            project_name, run.label, run.scenario_name, run.report, run.input_document
        ),
        payload_version=PAYLOAD_VERSION,
        report_version=str(run.report.get("reportVersion") or "unknown")[:16],
        result_digest=run.result_digest,
        expires_at=utcnow() + timedelta(days=body.expires_in_days)
        if body.expires_in_days
        else None,
        view_count=0,
    )
    session.add(share)
    session.flush()
    audit(
        session,
        "share.create",
        workspace_id=share.workspace_id,
        target_kind="share",
        target_id=share.id,
        project_id=share.project_id,
        meta={"title": share.title, "runId": run.id, "label": run.label},
        actor=principal,
    )
    session.commit()
    return _share_out(share, settings)


@router.get("/projects/{project_id}/shares", response_model=list[ShareOut])
def list_project_shares(
    access: ProjectAccess = Depends(require_project("viewer")),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> list[ShareOut]:
    """The project's share links, newest first, revoked and expired ones included."""
    return _list(session, settings, SharedReport.project_id == access.project.id)


@router.get("/shares", response_model=list[ShareOut])
def list_workspace_shares(
    access: WorkspaceAccess = Depends(get_workspace),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> list[ShareOut]:
    """Every share link of the workspace, newest first, including those whose project was
    deleted (they keep working until they expire or are revoked)."""
    return _list(session, settings, SharedReport.workspace_id == access.workspace.id)


@router.delete("/shares/{share_id}", status_code=204)
def revoke_share(
    share_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> Response:
    """Stop a link working, at once: the page answers 404 and the stored copy is deleted."""
    share = session.get(SharedReport, share_id)
    role = principal.role_in(session, share.workspace_id) if share is not None else None
    if share is None or role is None:
        raise not_found("Share link")
    if not at_least(role, "editor"):
        raise forbidden(role, "editor")
    if share.revoked_at is None:
        share.revoked_at = utcnow()
        audit(
            session,
            "share.revoke",
            workspace_id=share.workspace_id,
            target_kind="share",
            target_id=share.id,
            project_id=share.project_id,
            meta={"title": share.title},
            actor=principal,
        )
    share.payload = None
    session.commit()
    return Response(status_code=204)


@router.get("/public/shares/{slug}", response_model=PublicShareOut)
def public_share(
    slug: str,
    request: Request,
    response: Response,
    view: bool = True,
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> PublicShareOut:
    """A shared report, for anyone with the link (no sign-in). ``view=false`` reads it without
    counting a view (link previews)."""
    ip = client_ip(request, settings.trusted_proxies) or "unknown"
    limits.enforce(
        settings,
        db,
        f"ip:{ip}:shares",
        PUBLIC_VIEWS_PER_IP,
        "Too many share pages opened from your address.",
    )
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Robots-Tag"] = "noindex"
    share = _active_by_slug(session, slug)
    payload = share.payload or {}
    if view:
        session.execute(
            update(SharedReport)
            .where(SharedReport.id == share.id)
            .values(view_count=SharedReport.view_count + 1)
        )
        session.commit()
    return PublicShareOut(
        slug=share.slug,
        title=share.title,
        project_name=str(payload.get("projectName") or share.title),
        run_label=payload.get("runLabel"),
        scenario_name=payload.get("scenarioName"),
        created_at=share.created_at,
        expires_at=share.expires_at,
        payload_version=share.payload_version,
        report_version=share.report_version,
        result_digest=share.result_digest,
        objectives=payload.get("objectives") or {},
        report=payload.get("report") or {},
        can_copy=isinstance(payload.get("document"), dict),
    )


@router.post("/projects/from-share", response_model=ProjectOut, status_code=201)
def copy_from_share(
    body: CopyFromShare,
    access: WorkspaceAccess = Depends(get_workspace),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ProjectOut:
    """Open a copy: a new project in your workspace from the design a share link shows (the
    exact document that was simulated)."""
    access.require("editor")
    share = _active_by_slug(session, body.slug)
    payload = share.payload or {}
    document = payload.get("document")
    if not isinstance(document, dict):
        raise not_found("A design in this share link was")
    name = body.name or f"{payload.get('projectName') or share.title} (copy)"[:120]
    return _out(
        _create(
            session,
            access.workspace.id,
            principal.user_id,
            copy.deepcopy(document),
            name,
            None,
            None,
            settings,
        )
    )
