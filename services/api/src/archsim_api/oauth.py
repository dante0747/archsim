"""Signing in with GitHub or Google: OAuth 2 authorization code with PKCE (Authlib).

1. ``GET /auth/<provider>/login`` makes a random ``state`` and PKCE verifier, keeps them in a
   short-lived cookie signed with ``ARCHSIM_SECRET_KEY`` (``__Host-archsim_oauth``, HttpOnly,
   10 minutes) and sends the browser to the provider.
2. The provider sends it back to ``/auth/<provider>/callback`` with a code. The state must match
   the cookie's; the code is exchanged with the verifier (so a stolen code is useless), and the
   API asks the provider who signed in.
3. The account is found or created (a new person gets a personal workspace; one whose verified
   email is in ``ARCHSIM_ADMIN_EMAILS`` also becomes an owner of the default workspace), and a
   session starts: a random secret in ``__Host-archsim_session`` (HttpOnly, Secure,
   SameSite=Lax), stored only as its SHA-256, so it can be revoked at once on every instance.

Nothing the provider returns other than the profile is kept; its access token is dropped.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import time
from dataclasses import dataclass
from datetime import timedelta
from typing import Any

import httpx2
from authlib.integrations.httpx_client import OAuth2Client
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api.config import Settings
from archsim_api.db import (
    DEFAULT_WORKSPACE_ID,
    Identity,
    Membership,
    User,
    UserSession,
    Workspace,
    utcnow,
)
from archsim_api.identity import create_workspace
from archsim_api.security import new_secret, sha256_hex

log = logging.getLogger("archsim.auth")

SESSION_COOKIE = "__Host-archsim_session"
STATE_COOKIE = "__Host-archsim_oauth"
CSRF_COOKIE = "__Host-archsim_csrf"
STATE_SECONDS = 600
HTTP_TIMEOUT_S = 10.0


class SignInError(Exception):
    """Sign-in failed; ``code`` goes to the sign-in page (``/signin?error=<code>``)."""

    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(detail or code)
        self.code = code


@dataclass(frozen=True)
class Profile:
    subject: str
    name: str
    email: str | None
    email_verified: bool
    avatar_url: str | None = None
    login: str | None = None


@dataclass(frozen=True)
class Provider:
    name: str
    client_id: str
    client_secret: str
    authorize_url: str
    token_url: str
    scope: str
    extra_params: tuple[tuple[str, str], ...]
    api_url: str = ""

    def client(
        self, redirect_uri: str, transport: httpx2.BaseTransport | None = None
    ) -> OAuth2Client:
        return OAuth2Client(
            client_id=self.client_id,
            client_secret=self.client_secret,
            scope=self.scope,
            redirect_uri=redirect_uri,
            code_challenge_method="S256",
            token_endpoint_auth_method="client_secret_post",
            transport=transport,
            timeout=HTTP_TIMEOUT_S,
        )

    def profile(self, client: OAuth2Client) -> Profile:
        if self.name == "github":
            return _github_profile(client, self.api_url)
        return _google_profile(client)


def providers(settings: Settings) -> dict[str, Provider]:
    out: dict[str, Provider] = {}
    if settings.github_client_id and settings.github_client_secret:
        base = settings.github_url.rstrip("/")
        out["github"] = Provider(
            name="github",
            client_id=settings.github_client_id,
            client_secret=settings.github_client_secret.get_secret_value(),
            authorize_url=f"{base}/login/oauth/authorize",
            token_url=f"{base}/login/oauth/access_token",
            scope="read:user user:email",
            extra_params=(("allow_signup", "true"),),
            api_url=settings.github_api_url.rstrip("/"),
        )
    if settings.google_client_id and settings.google_client_secret:
        out["google"] = Provider(
            name="google",
            client_id=settings.google_client_id,
            client_secret=settings.google_client_secret.get_secret_value(),
            authorize_url="https://accounts.google.com/o/oauth2/v2/auth",
            token_url="https://oauth2.googleapis.com/token",
            scope="openid email profile",
            extra_params=(("prompt", "select_account"), ("access_type", "online")),
        )
    return out


def _get_json(client: OAuth2Client, url: str) -> Any:
    res = client.get(url, headers={"Accept": "application/json"})
    if res.status_code != 200:
        raise SignInError("provider", f"{url} answered HTTP {res.status_code}")
    return res.json()


def _github_profile(client: OAuth2Client, api: str) -> Profile:
    user = _get_json(client, f"{api}/user")
    try:
        emails = _get_json(client, f"{api}/user/emails")
    except SignInError:
        emails = []  # the user:email scope was refused: no verified email
    verified = [e for e in emails if isinstance(e, dict) and e.get("verified") and e.get("email")]
    chosen = next((e for e in verified if e.get("primary")), verified[0] if verified else None)
    login = str(user.get("login") or "")
    return Profile(
        subject=str(user["id"]),
        name=str(user.get("name") or login or "GitHub user")[:200],
        email=str(chosen["email"]).lower() if chosen else None,
        email_verified=chosen is not None,
        avatar_url=str(user["avatar_url"])[:500] if user.get("avatar_url") else None,
        login=login[:200] or None,
    )


def _google_profile(client: OAuth2Client) -> Profile:
    info = _get_json(client, "https://openidconnect.googleapis.com/v1/userinfo")
    email = info.get("email")
    return Profile(
        subject=str(info["sub"]),
        name=str(info.get("name") or email or "Google user")[:200],
        email=str(email).lower() if email else None,
        email_verified=bool(info.get("email_verified")) and bool(email),
        avatar_url=str(info["picture"])[:500] if info.get("picture") else None,
    )


# -- the signed state cookie --------------------------------------------------------------------
def _key(settings: Settings, purpose: str) -> bytes:
    assert settings.secret_key is not None
    secret = settings.secret_key.get_secret_value().encode("utf-8")
    return hmac.new(secret, f"archsim:{purpose}".encode(), hashlib.sha256).digest()


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def sign(settings: Settings, purpose: str, payload: dict[str, Any]) -> str:
    body = _b64(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    mac = hmac.new(_key(settings, purpose), body.encode("ascii"), hashlib.sha256).digest()
    return f"{body}.{_b64(mac)}"


def unsign(settings: Settings, purpose: str, value: str) -> dict[str, Any] | None:
    """The payload if the signature is right and it has not expired (``exp``), else None."""
    body, _, mac = value.partition(".")
    if not body or not mac:
        return None
    want = hmac.new(_key(settings, purpose), body.encode("ascii"), hashlib.sha256).digest()
    try:
        if not hmac.compare_digest(want, _unb64(mac)):
            return None
        payload = json.loads(_unb64(body))
    except (ValueError, TypeError):
        return None
    if not isinstance(payload, dict) or float(payload.get("exp", 0)) < time.time():
        return None
    return payload


def csrf_token(settings: Settings, session_id: str) -> str:
    """The CSRF token of a session (derived, so every instance agrees without storing it)."""
    return _b64(hmac.new(_key(settings, "csrf"), session_id.encode(), hashlib.sha256).digest())


def safe_next(value: str | None) -> str:
    """Where to land after signing in: a path on this site, never another site."""
    if not value or not value.startswith("/") or value.startswith(("//", "/\\")):
        return "/"
    if any(c in value for c in "\r\n\t") or "://" in value.split("?")[0]:
        return "/"
    return value[:500]


def start(settings: Settings, provider: Provider, next_path: str | None) -> tuple[str, str]:
    """The provider's sign-in URL and the signed state cookie value."""
    state = new_secret()
    verifier = new_secret() + new_secret()[:40]  # 83 characters: PKCE allows 43 to 128
    client = provider.client(redirect_uri(settings, provider.name))
    url, _ = client.create_authorization_url(
        provider.authorize_url, state=state, code_verifier=verifier, **dict(provider.extra_params)
    )
    cookie = sign(
        settings,
        "oauth-state",
        {
            "p": provider.name,
            "s": state,
            "v": verifier,
            "n": safe_next(next_path),
            "exp": time.time() + STATE_SECONDS,
        },
    )
    return url, cookie


def redirect_uri(settings: Settings, provider: str) -> str:
    return f"{settings.public_url}/api/v1/auth/{provider}/callback"


def finish(
    settings: Settings,
    provider: Provider,
    code: str,
    state: str,
    cookie: str | None,
    transport: httpx2.BaseTransport | None = None,
) -> tuple[Profile, str]:
    """Check the state, exchange the code and read the profile. Returns it with the path to
    land on."""
    saved = unsign(settings, "oauth-state", cookie or "")
    if (
        saved is None
        or saved.get("p") != provider.name
        or not hmac.compare_digest(str(saved.get("s", "")), state)
    ):
        raise SignInError("state", "the sign-in state cookie is missing, expired or different")
    client = provider.client(redirect_uri(settings, provider.name), transport)
    try:
        with client:
            client.fetch_token(provider.token_url, code=code, code_verifier=saved["v"])
            return provider.profile(client), safe_next(saved.get("n"))
    except SignInError:
        raise
    except Exception as exc:  # network errors, a refused code, a malformed answer
        log.warning("sign-in with %s failed: %s", provider.name, type(exc).__name__)
        raise SignInError("provider", type(exc).__name__) from None


def sign_in(session: Session, settings: Settings, provider: str, profile: Profile) -> User:
    """Find or create the person behind a provider account (committed).

    The account is matched by the provider's id. A new account whose verified email belongs to
    someone already here is linked to them (a provider's unverified email is never trusted);
    otherwise a new person is created with a personal workspace. A new person whose verified
    email is in ``ARCHSIM_ADMIN_EMAILS`` also becomes an owner of the default workspace."""
    now = utcnow()
    ident = session.execute(
        select(Identity).where(Identity.provider == provider, Identity.subject == profile.subject)
    ).scalar_one_or_none()
    user: User | None = None
    if ident is not None:
        user = session.get(User, ident.user_id)
    elif profile.email and profile.email_verified:
        match = session.execute(
            select(Identity.user_id)
            .where(Identity.email == profile.email, Identity.email_verified.is_(True))
            .limit(1)
        ).scalar_one_or_none()
        user = session.get(User, match) if match else None
    created = user is None
    if user is None:
        user = User(name=profile.name, email=profile.email if profile.email_verified else None)
        session.add(user)
        session.flush()
        first = profile.name.split()[0] if profile.name.split() else "My"
        create_workspace(session, f"{first}'s workspace", "personal", user.id)
    if user.disabled_at is not None:
        raise SignInError("disabled")
    if ident is None:
        ident = Identity(user_id=user.id, provider=provider, subject=profile.subject)
        session.add(ident)
    ident.email = profile.email
    ident.email_verified = profile.email_verified
    ident.login = profile.login
    ident.last_login_at = now
    user.name = profile.name or user.name
    user.avatar_url = profile.avatar_url or user.avatar_url
    if profile.email_verified and profile.email:
        user.email = profile.email
    user.last_seen_at = now
    admin = profile.email_verified and (profile.email or "") in settings.admin_emails
    if created and admin and session.get(Workspace, DEFAULT_WORKSPACE_ID) is not None:
        key = {"workspace_id": DEFAULT_WORKSPACE_ID, "user_id": user.id}
        member = session.get(Membership, key)
        if member is None:
            session.add(Membership(**key, role="owner"))
        else:
            member.role = "owner"
        log.info("%s signed in for the first time and owns the default workspace", user.id)
    session.commit()
    return user


def start_session(
    session: Session, settings: Settings, user: User, ip: str | None, user_agent: str | None
) -> tuple[str, UserSession]:
    """A new session for ``user``: the cookie value (secret) and its row (committed)."""
    secret = new_secret()
    now = utcnow()
    row = UserSession(
        id=sha256_hex(secret),
        user_id=user.id,
        created_at=now,
        last_seen_at=now,
        expires_at=now + timedelta(days=settings.session_days),
        ip=ip,
        user_agent=(user_agent or "")[:300] or None,
    )
    session.add(row)
    session.commit()
    return secret, row
