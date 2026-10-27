"""Workspaces, their members and invitations.

Everyone may see the workspaces they belong to and their members; owners rename a workspace,
change roles, remove members and invite people. A workspace always keeps at least one owner.
Invitations are links with a random token (shown once, stored as a hash) that make whoever
accepts them a member; one with an email can only be accepted by someone signed in with that
verified email. Personal and sandbox workspaces cannot be shared."""

from __future__ import annotations

from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api import limits
from archsim_api.config import Settings
from archsim_api.db import (
    DEFAULT_WORKSPACE_ID,
    Database,
    Identity,
    Invitation,
    Membership,
    User,
    Workspace,
    utcnow,
)
from archsim_api.deps import get_db, get_principal, get_session, get_settings_dep, require_session
from archsim_api.identity import (
    InvitationCreate,
    InvitationCreated,
    InvitationOut,
    InvitationPreview,
    InvitationToken,
    MemberOut,
    MemberUpdate,
    WorkspaceCreate,
    WorkspaceOut,
    WorkspaceUpdate,
    as_utc,
    conflict,
    create_workspace,
    owner_count,
    workspace_out,
    workspaces_of,
)
from archsim_api.security import (
    INVITE_PREFIX,
    Principal,
    at_least,
    new_secret,
    not_found,
    sha256_hex,
    workspace_access,
)

router = APIRouter(tags=["workspaces"])


@router.get("/workspaces", response_model=list[WorkspaceOut])
def list_workspaces(
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> list[WorkspaceOut]:
    """The workspaces you belong to, with your role in each (personal first)."""
    return workspaces_of(
        session, settings, principal.user_id, principal.token_workspace_id, principal.read_only
    )


@router.post("/workspaces", response_model=WorkspaceOut, status_code=201)
def create_team_workspace(
    body: WorkspaceCreate,
    principal: Principal = Depends(require_session),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> WorkspaceOut:
    """A new team workspace, with you as its owner."""
    limits.enforce(
        settings,
        db,
        f"user:{principal.user_id}:workspaces",
        limits.WORKSPACES_PER_USER,
        "You have made many workspaces today.",
    )
    ws = create_workspace(session, body.name.strip() or "Team", "team", principal.user_id)
    session.commit()
    return workspace_out(ws, "owner", settings)


@router.get("/workspaces/{workspace_id}", response_model=WorkspaceOut)
def get_workspace_details(
    workspace_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> WorkspaceOut:
    access = workspace_access(session, principal, workspace_id)
    return workspace_out(access.workspace, access.role, settings)


@router.patch("/workspaces/{workspace_id}", response_model=WorkspaceOut)
def update_workspace(
    workspace_id: str,
    body: WorkspaceUpdate,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> WorkspaceOut:
    access = workspace_access(session, principal, workspace_id, "owner")
    if body.name:
        access.workspace.name = body.name.strip()[:120] or access.workspace.name
    session.commit()
    return workspace_out(access.workspace, access.role, settings)


@router.delete("/workspaces/{workspace_id}", status_code=204)
def delete_workspace(
    workspace_id: str,
    principal: Principal = Depends(require_session),
    session: Session = Depends(get_session),
) -> Response:
    """Delete a team workspace with all its projects and runs (owners only). The default and
    personal workspaces stay."""
    access = workspace_access(session, principal, workspace_id, "owner")
    if access.workspace.id == DEFAULT_WORKSPACE_ID or access.workspace.kind != "team":
        raise conflict("Only team workspaces you created can be deleted.")
    session.delete(access.workspace)
    session.commit()
    return Response(status_code=204)


# -- members ----------------------------------------------------------------------------------
@router.get("/workspaces/{workspace_id}/members", response_model=list[MemberOut])
def list_members(
    workspace_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> list[MemberOut]:
    workspace_access(session, principal, workspace_id)
    rows = session.execute(
        select(Membership, User)
        .join(User, User.id == Membership.user_id)
        .where(Membership.workspace_id == workspace_id)
        .order_by(Membership.created_at)
    ).all()
    return [_member_out(m, u) for m, u in rows]


@router.patch("/workspaces/{workspace_id}/members/{user_id}", response_model=MemberOut)
def change_role(
    workspace_id: str,
    user_id: str,
    body: MemberUpdate,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> MemberOut:
    """Change a member's role (owners only). The last owner stays an owner."""
    workspace_access(session, principal, workspace_id, "owner")
    m = session.get(Membership, {"workspace_id": workspace_id, "user_id": user_id})
    if m is None:
        raise not_found("Member")
    if m.role == "owner" and body.role != "owner" and owner_count(session, workspace_id) <= 1:
        raise conflict("A workspace needs an owner: make someone else an owner first.")
    m.role = body.role
    session.commit()
    user = session.get(User, user_id)
    assert user is not None
    return _member_out(m, user)


@router.delete("/workspaces/{workspace_id}/members/{user_id}", status_code=204)
def remove_member(
    workspace_id: str,
    user_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> Response:
    """Remove a member (owners), or leave (anyone, for themselves). The last owner cannot."""
    leaving = user_id == principal.user_id
    access = workspace_access(session, principal, workspace_id, "viewer" if leaving else "owner")
    m = session.get(Membership, {"workspace_id": workspace_id, "user_id": user_id})
    if m is None:
        raise not_found("Member")
    if access.workspace.kind == "personal" and m.role == "owner":
        raise conflict("A personal workspace keeps its owner.")
    if m.role == "owner" and owner_count(session, workspace_id) <= 1:
        raise conflict("A workspace needs an owner: make someone else an owner first.")
    session.delete(m)
    session.commit()
    return Response(status_code=204)


# -- invitations ------------------------------------------------------------------------------
@router.get("/workspaces/{workspace_id}/invitations", response_model=list[InvitationOut])
def list_invitations(
    workspace_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> list[InvitationOut]:
    """Invitations not yet accepted and not expired (owners only)."""
    workspace_access(session, principal, workspace_id, "owner")
    rows = (
        session.execute(
            select(Invitation)
            .where(
                Invitation.workspace_id == workspace_id,
                Invitation.accepted_at.is_(None),
                Invitation.expires_at > utcnow(),
            )
            .order_by(Invitation.created_at.desc())
        )
        .scalars()
        .all()
    )
    return [InvitationOut.model_validate(i) for i in rows]


@router.post(
    "/workspaces/{workspace_id}/invitations", response_model=InvitationCreated, status_code=201
)
def create_invitation(
    workspace_id: str,
    body: InvitationCreate,
    principal: Principal = Depends(require_session),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> InvitationCreated:
    """An invitation link (owners only). The token is in this answer only: share
    ``<web address>/invite/<token>``."""
    access = workspace_access(session, principal, workspace_id, "owner")
    if access.workspace.kind != "team":
        raise conflict("Only team workspaces can be shared: create one under Settings.")
    limits.enforce(
        settings,
        db,
        f"ws:{workspace_id}:invitations",
        limits.INVITES_PER_WORKSPACE,
        "This workspace has sent many invitations today.",
    )
    token = new_secret(INVITE_PREFIX)
    inv = Invitation(
        workspace_id=workspace_id,
        token_hash=sha256_hex(token),
        role=body.role,
        email=body.email.strip().lower() if body.email else None,
        created_by=principal.user_id,
        expires_at=utcnow() + timedelta(days=body.expires_in_days),
    )
    session.add(inv)
    session.commit()
    out = InvitationOut.model_validate(inv).model_dump()
    return InvitationCreated.model_validate({**out, "token": token})


@router.delete("/workspaces/{workspace_id}/invitations/{invitation_id}", status_code=204)
def revoke_invitation(
    workspace_id: str,
    invitation_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> Response:
    workspace_access(session, principal, workspace_id, "owner")
    inv = session.get(Invitation, invitation_id)
    if inv is None or inv.workspace_id != workspace_id:
        raise not_found("Invitation")
    session.delete(inv)
    session.commit()
    return Response(status_code=204)


def _open_invitation(session: Session, principal: Principal, token: str) -> Invitation:
    """The invitation behind ``token`` if it can still be accepted by this principal."""
    inv = session.execute(
        select(Invitation).where(Invitation.token_hash == sha256_hex(token.strip()))
    ).scalar_one_or_none()
    if inv is None or inv.accepted_at is not None or as_utc(inv.expires_at) <= utcnow():
        raise not_found("This invitation (it may have expired or been used already) was")
    if inv.email is not None:
        emails = {
            (e or "").lower()
            for e in session.execute(
                select(Identity.email).where(
                    Identity.user_id == principal.user_id, Identity.email_verified.is_(True)
                )
            ).scalars()
        }
        if inv.email not in emails:
            raise HTTPException(
                403,
                detail={
                    "message": "This invitation is for another email address. Sign in with "
                    "the account it was sent to.",
                    "issues": [],
                },
            )
    return inv


@router.post("/invitations/preview", response_model=InvitationPreview)
def preview_invitation(
    body: InvitationToken,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> InvitationPreview:
    """What accepting an invitation would do: the workspace, the role and who sent it."""
    inv = _open_invitation(session, principal, body.token)
    ws = session.get(Workspace, inv.workspace_id)
    inviter = session.get(User, inv.created_by) if inv.created_by else None
    assert ws is not None
    return InvitationPreview(
        workspace_name=ws.name,
        role=inv.role,
        email=inv.email,
        expires_at=inv.expires_at,
        invited_by=inviter.name if inviter else None,
    )


@router.post("/invitations/accept", response_model=WorkspaceOut)
def accept_invitation(
    body: InvitationToken,
    principal: Principal = Depends(require_session),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> WorkspaceOut:
    """Join the workspace an invitation is for. A member keeps the stronger of the two roles."""
    inv = _open_invitation(session, principal, body.token)
    m = session.get(Membership, {"workspace_id": inv.workspace_id, "user_id": principal.user_id})
    if m is None:
        m = Membership(workspace_id=inv.workspace_id, user_id=principal.user_id, role=inv.role)
        session.add(m)
    elif not at_least(m.role, inv.role):
        m.role = inv.role
    inv.accepted_at = utcnow()
    inv.accepted_by = principal.user_id
    session.commit()
    ws = session.get(Workspace, inv.workspace_id)
    assert ws is not None
    return workspace_out(ws, m.role, settings)


def _member_out(m: Membership, u: User) -> MemberOut:
    return MemberOut.model_validate(
        {
            "user_id": u.id,
            "name": u.name,
            "email": u.email,
            "avatar_url": u.avatar_url,
            "role": m.role,
            "joined_at": m.created_at,
        }
    )
