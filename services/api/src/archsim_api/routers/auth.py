"""Signing in and out, and who you are.

``GET /auth/config`` (public) says how people sign in here. With ``ARCHSIM_AUTH_MODE=oauth``,
``/auth/<provider>/login`` and ``/auth/<provider>/callback`` sign in with GitHub or Google (see
``archsim_api.oauth``), ``POST /auth/logout`` ends the session, and ``GET /auth/me`` describes
the signed-in person and their workspaces."""

from __future__ import annotations

from typing import Literal
from urllib.parse import quote

import httpx2
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import RedirectResponse
from sqlalchemy import delete
from sqlalchemy.orm import Session

from archsim_api import oauth
from archsim_api.config import Settings
from archsim_api.db import User, UserSession
from archsim_api.deps import get_principal, get_session, get_settings_dep
from archsim_api.identity import UserOut, WorkspaceOut, user_out, workspaces_of
from archsim_api.schemas import ApiModel
from archsim_api.security import Principal, PrincipalKind, client_ip, not_found, sha256_hex

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


def _provider(settings: Settings, name: str) -> oauth.Provider:
    provider = oauth.providers(settings).get(name) if settings.hosted else None
    if provider is None:
        raise HTTPException(
            404, detail={"message": f"Sign-in with '{name}' is not set up here", "issues": []}
        )
    return provider


def _cookie(response: Response, name: str, value: str, max_age: int) -> None:
    # __Host- cookies: Secure, path /, no domain; the browser holds them for the web's origin
    response.set_cookie(
        name, value, max_age=max_age, path="/", secure=True, httponly=True, samesite="lax"
    )


def _clear(response: Response, name: str) -> None:
    response.delete_cookie(name, path="/", secure=True, httponly=True, samesite="lax")


@router.get(
    "/{provider}/login",
    response_class=RedirectResponse,
    status_code=302,
    responses={302: {"description": "To the provider's sign-in page."}},
)
def login(
    provider: str,
    next: str | None = Query(default=None, max_length=500, description="Path to land on after"),
    settings: Settings = Depends(get_settings_dep),
) -> RedirectResponse:
    """Start signing in with ``github`` or ``google``: redirects to the provider."""
    p = _provider(settings, provider)
    url, state_cookie = oauth.start(settings, p, next)
    response = RedirectResponse(url, status_code=302)
    _cookie(response, oauth.STATE_COOKIE, state_cookie, oauth.STATE_SECONDS)
    response.headers["Cache-Control"] = "no-store"
    return response


@router.get(
    "/{provider}/callback",
    response_class=RedirectResponse,
    status_code=302,
    responses={302: {"description": "Signed in (to `next`), or to /signin?error=..."}},
)
def callback(
    provider: str,
    request: Request,
    code: str | None = Query(default=None, max_length=2000),
    state: str | None = Query(default=None, max_length=200),
    error: str | None = Query(default=None, max_length=200),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> RedirectResponse:
    """Where the provider sends the browser back. Sets the session cookie and redirects to the
    page sign-in started from; on failure, to ``/signin?error=<reason>``."""
    p = _provider(settings, provider)
    transport: httpx2.BaseTransport | None = getattr(request.app.state, "oauth_transport", None)
    try:
        if error or not code or not state:
            raise oauth.SignInError("denied" if error else "state")
        profile, landing = oauth.finish(
            settings, p, code, state, request.cookies.get(oauth.STATE_COOKIE), transport
        )
        user = oauth.sign_in(session, settings, p.name, profile)
        secret, _row = oauth.start_session(
            session,
            settings,
            user,
            client_ip(request, settings.trusted_proxies),
            request.headers.get("user-agent"),
        )
    except oauth.SignInError as exc:
        response = RedirectResponse(f"/signin?error={quote(exc.code)}", status_code=302)
        _clear(response, oauth.STATE_COOKIE)
        return response
    response = RedirectResponse(landing, status_code=302)
    _clear(response, oauth.STATE_COOKIE)
    _cookie(response, oauth.SESSION_COOKIE, secret, settings.session_days * 86_400)
    response.headers["Cache-Control"] = "no-store"
    return response


@router.post("/logout", status_code=204)
def logout(
    request: Request,
    everywhere: bool = Query(default=False, description="End every session of this account"),
    session: Session = Depends(get_session),
) -> Response:
    """End this browser's session (``everywhere``: all of this account's), effective at once on
    every instance. Signed out already: nothing to do."""
    cookie = request.cookies.get(oauth.SESSION_COOKIE)
    if cookie:
        row = session.get(UserSession, sha256_hex(cookie))
        if row is not None:
            if everywhere:
                session.execute(delete(UserSession).where(UserSession.user_id == row.user_id))
            else:
                session.delete(row)
            session.commit()
    response = Response(status_code=204)
    _clear(response, oauth.SESSION_COOKIE)
    return response
