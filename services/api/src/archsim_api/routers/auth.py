"""Who you are: the sign-in mode and providers (public), and the signed-in user with their
workspaces."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from archsim_api.config import Settings
from archsim_api.db import User
from archsim_api.deps import get_principal, get_session, get_settings_dep
from archsim_api.identity import UserOut, WorkspaceOut, user_out, workspaces_of
from archsim_api.schemas import ApiModel
from archsim_api.security import Principal, PrincipalKind, not_found

router = APIRouter(prefix="/auth", tags=["auth"])


class AuthConfigOut(ApiModel):
    mode: Literal["none", "oauth"]
    providers: list[str]


class MeOut(ApiModel):
    mode: Literal["none", "oauth"]
    kind: PrincipalKind
    """local (no sign-in), session (a signed-in browser) or token."""
    user: UserOut
    workspaces: list[WorkspaceOut]
    csrf_token: str | None = None
    """Send it as X-ArchSim-CSRF with every unsafe request a session cookie authenticates."""


@router.get("/config", response_model=AuthConfigOut)
def auth_config(settings: Settings = Depends(get_settings_dep)) -> AuthConfigOut:
    """How people sign in here: ``none`` (local, no sign-in) or ``oauth`` with its providers."""
    return AuthConfigOut(
        mode=settings.auth_mode, providers=settings.oauth_providers() if settings.hosted else []
    )


@router.get("/me", response_model=MeOut, responses={401: {"description": "Not signed in."}})
def me(
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> MeOut:
    user = session.get(User, principal.user_id)
    if user is None:
        raise not_found("Your account was")
    return MeOut(
        mode=settings.auth_mode,
        kind=principal.kind,
        user=user_out(user),
        workspaces=workspaces_of(
            session, user.id, principal.token_workspace_id, principal.read_only
        ),
    )
