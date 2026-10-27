"""The history of changes: who did what to which project, run, comment or membership.

``audit()`` adds an ``audit_events`` row to the session that makes the change, before its
commit, so the change and its record are stored together or not at all. The actor is the
request's principal: passed in, else the one ``deps.get_principal`` left on the session, else a
user id (a helper that only knows who created something), else the system.

Actions are ``<target>.<verb>``: ``project.create|update|delete``, ``run.create|cancel|delete``,
``comment.create|edit|delete|resolve|reopen``, ``signoff.create|withdraw``,
``member.join|role|remove``, ``invitation.create|revoke``, ``workspace.create|rename``.
"""

from __future__ import annotations

from typing import Any

from sqlalchemy.orm import Session

from archsim_api.db import AuditEvent, Project, SimulationRun
from archsim_api.security import Principal

PRINCIPAL_KEY = "archsim_principal"
"""Where ``deps.get_principal`` leaves the request's principal in ``Session.info``."""

EXCERPT_CHARS = 140


def excerpt(text: str, limit: int = EXCERPT_CHARS) -> str:
    """The start of a comment or note, on one line, for the history."""
    flat = " ".join(text.split())
    return flat if len(flat) <= limit else flat[: limit - 1].rstrip() + "…"


def _actor(session: Session, actor: Principal | str | None) -> tuple[str | None, str, str | None]:
    """(user id, actor kind, address) of whoever makes the change."""
    principal = actor if isinstance(actor, Principal) else session.info.get(PRINCIPAL_KEY)
    if isinstance(actor, str) and (principal is None or principal.user_id != actor):
        return actor, "user", None
    if isinstance(principal, Principal):
        return principal.user_id, "token" if principal.kind == "token" else "user", principal.ip
    return None, "system", None


def audit(
    session: Session,
    action: str,
    *,
    workspace_id: str,
    target_kind: str,
    target_id: str | None = None,
    project_id: str | None = None,
    meta: dict[str, Any] | None = None,
    actor: Principal | str | None = None,
) -> AuditEvent:
    """Record ``action`` in the history, in ``session``'s transaction (the caller commits)."""
    user_id, kind, ip = _actor(session, actor)
    event = AuditEvent(
        workspace_id=workspace_id,
        project_id=project_id,
        actor_user_id=user_id,
        actor_kind=kind,
        action=action,
        target_kind=target_kind,
        target_id=target_id,
        meta={k: v for k, v in meta.items() if v is not None} if meta else None,
        ip=ip,
    )
    session.add(event)
    return event


def audit_project(
    session: Session,
    action: str,
    project: Project,
    meta: dict[str, Any] | None = None,
    actor: Principal | str | None = None,
) -> AuditEvent:
    """``audit()`` for a change to a project itself."""
    return audit(
        session,
        action,
        workspace_id=project.workspace_id,
        target_kind="project",
        target_id=project.id,
        project_id=project.id,
        meta={"name": project.name, **(meta or {})},
        actor=actor,
    )


def audit_run(
    session: Session,
    action: str,
    run: SimulationRun,
    meta: dict[str, Any] | None = None,
    actor: Principal | str | None = None,
) -> AuditEvent:
    """``audit()`` for a run: started, cancelled or deleted."""
    return audit(
        session,
        action,
        workspace_id=run.workspace_id,
        target_kind="run",
        target_id=run.id,
        project_id=run.project_id,
        meta={"label": run.label, "scenario": run.scenario_name, **(meta or {})},
        actor=actor,
    )


def audit_member(
    session: Session,
    action: str,
    workspace_id: str,
    target_id: str | None,
    meta: dict[str, Any] | None = None,
    actor: Principal | str | None = None,
) -> AuditEvent:
    """``audit()`` for a workspace, its members (``target_id``: the user) and invitations."""
    kind = action.split(".", 1)[0]
    return audit(
        session,
        action,
        workspace_id=workspace_id,
        target_kind="user" if kind == "member" else kind,
        target_id=target_id if target_id is not None else workspace_id,
        meta=meta,
        actor=actor,
    )
