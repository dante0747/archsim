"""FastAPI dependencies and shared helpers."""

from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from datetime import timedelta
from functools import lru_cache
from pathlib import Path
from typing import Any, NoReturn

from fastapi import Depends, HTTPException, Query, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api.config import Settings
from archsim_api.db import (
    DEFAULT_WORKSPACE_ID,
    LOCAL_USER_ID,
    Database,
    Membership,
    User,
    UserSession,
    Workspace,
    utcnow,
)
from archsim_api.identity import as_utc
from archsim_api.jobs.worker import JobService
from archsim_api.oauth import SESSION_COOKIE
from archsim_api.security import (
    Principal,
    ProjectAccess,
    RunAccess,
    WorkspaceAccess,
    client_ip,
    not_found,
    project_access,
    run_access,
    sha256_hex,
    workspace_access,
)
from archsim_engine import ComponentRegistry, Issue


def get_settings_dep(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def get_db(request: Request) -> Database:
    db: Database = request.app.state.db
    return db


def get_session(request: Request) -> Iterator[Session]:
    db: Database = request.app.state.db
    with db.session() as s:
        yield s


def get_jobs(request: Request) -> JobService:
    jobs: JobService = request.app.state.jobs
    return jobs


def get_registry(request: Request) -> ComponentRegistry:
    registry: ComponentRegistry = request.app.state.registry
    return registry


WORKSPACE_HEADER = "x-archsim-workspace"


SESSION_TOUCH = timedelta(minutes=5)
"""How stale a session's last-seen time may get before a request updates it."""


def unauthorized(message: str = "Sign in to continue") -> HTTPException:
    return HTTPException(
        401, detail={"message": message, "issues": []}, headers={"WWW-Authenticate": "Bearer"}
    )


def get_principal(
    request: Request,
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> Principal:
    """Who is asking. Without sign-in, the local user (owner of the default workspace); with
    it, the signed-in session in the cookie, else 401."""
    cached: Principal | None = getattr(request.state, "principal", None)
    if cached is not None:
        return cached
    ip = client_ip(request, settings.trusted_proxies)
    if not settings.hosted:
        principal = Principal(LOCAL_USER_ID, "local", ip=ip)
    else:
        principal = _from_session(request, session, ip)
    request.state.principal = principal
    return principal


def _from_session(request: Request, session: Session, ip: str | None) -> Principal:
    cookie = request.cookies.get(SESSION_COOKIE)
    if not cookie:
        raise unauthorized()
    row = session.get(UserSession, sha256_hex(cookie))
    now = utcnow()
    if row is None or as_utc(row.expires_at) <= now:
        raise unauthorized("Your sign-in has expired: sign in again")
    user = session.get(User, row.user_id)
    if user is None or user.disabled_at is not None:
        raise unauthorized("This account cannot sign in")
    if now - as_utc(row.last_seen_at) > SESSION_TOUCH:
        row.last_seen_at = now
        user.last_seen_at = now
        session.commit()
    return Principal(user.id, "session", ip=ip, session_id=row.id)


def default_workspace_id(session: Session, principal: Principal) -> str:
    """The workspace a request means when it names none: a token's own workspace, else the
    principal's personal workspace, else the default one, else its oldest membership."""
    if principal.token_workspace_id is not None:
        return principal.token_workspace_id
    rows = session.execute(
        select(Membership.workspace_id, Workspace.kind)
        .join(Workspace, Workspace.id == Membership.workspace_id)
        .where(Membership.user_id == principal.user_id)
        .order_by(Membership.created_at)
    ).all()
    if not rows:
        raise not_found("A workspace you belong to was")
    for wid, kind in rows:
        if kind == "personal":
            return str(wid)
    ids = [str(wid) for wid, _ in rows]
    return DEFAULT_WORKSPACE_ID if DEFAULT_WORKSPACE_ID in ids else ids[0]


def get_workspace(
    request: Request,
    workspace: str | None = Query(
        default=None,
        max_length=32,
        description="Workspace id (or the X-ArchSim-Workspace header). Default: your personal "
        "workspace, else the default one.",
    ),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> WorkspaceAccess:
    """The workspace a list or create request is about, and the principal's role there."""
    wid = workspace or request.headers.get(WORKSPACE_HEADER) or None
    return workspace_access(session, principal, wid or default_workspace_id(session, principal))


def require_project(minimum: str = "viewer") -> Callable[..., ProjectAccess]:
    """Dependency: the path's ``project_id``, if the principal has at least ``minimum`` there
    (404 without a role in its workspace, 403 when the role is too weak)."""

    def dependency(
        project_id: str,
        principal: Principal = Depends(get_principal),
        session: Session = Depends(get_session),
    ) -> ProjectAccess:
        return project_access(session, principal, project_id, minimum)

    return dependency


def require_run(minimum: str = "viewer", with_payload: bool = False) -> Callable[..., RunAccess]:
    """Dependency: the path's ``run_id``, like ``require_project``. ``with_payload`` loads the
    report and the input document too."""

    def dependency(
        run_id: str,
        principal: Principal = Depends(get_principal),
        session: Session = Depends(get_session),
    ) -> RunAccess:
        return run_access(session, principal, run_id, minimum, with_payload)

    return dependency


def raise_issues(status: int, message: str, issues: list[Issue] | None = None) -> NoReturn:
    raise HTTPException(
        status_code=status,
        detail={"message": message, "issues": [i.to_dict() for i in issues or []]},
    )


@lru_cache(maxsize=8)
def _load_examples(directory: str) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    path = Path(directory)
    if not path.is_dir():
        return out
    for f in sorted(path.glob("*.json")):
        try:
            out[f.stem] = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):  # pragma: no cover - broken file
            continue
    return out


def examples(settings: Settings) -> dict[str, dict[str, Any]]:
    return _load_examples(settings.examples_dir)
