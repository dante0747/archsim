"""Helpers for tests with several people: users and memberships written straight to the
database, and requests made as one of them (imported by test modules)."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import httpx2
from fastapi import Depends
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from api_helpers import EXAMPLES
from archsim_api.audit import PRINCIPAL_KEY
from archsim_api.config import Settings
from archsim_api.db import Database, Membership, User, Workspace
from archsim_api.deps import get_principal, get_session
from archsim_api.main import create_app
from archsim_api.security import Principal
from fake_oauth import FakeProviders

PUBLIC_URL = "https://archsim.test"
SECRET = "test-secret-key-0123456789-abcdefghijklmnop"


def oauth_settings(db_url: str, **overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "database_url": db_url,
        "examples_dir": str(EXAMPLES),
        "runner": "thread",
        "embedded_worker": False,
        "auth_mode": "oauth",
        "secret_key": SECRET,
        "public_url": PUBLIC_URL,
        "github_client_id": "test-client",
        "github_client_secret": "test-secret",
        "github_url": "https://github.test",
        "github_api_url": "https://api.github.test",
        "google_client_id": "test-client",
        "google_client_secret": "test-secret",
        "admin_emails": ["admin@example.com"],
    }
    values.update(overrides)
    return Settings(**values)


def oauth_client(db_url: str, fake: FakeProviders, **overrides: Any) -> TestClient:
    """An API with sign-in on, talking to ``fake`` instead of GitHub and Google. Use it in a
    ``with`` block; its base URL is https, so the Secure cookies come back."""
    app = create_app(oauth_settings(db_url, **overrides))
    app.state.oauth_transport = httpx2.MockTransport(fake.handle)
    return TestClient(app, base_url=PUBLIC_URL)


def sign_in(client: TestClient, fake: FakeProviders, login: str, provider: str = "github") -> str:
    """The whole browser round trip: login, the provider's page, the callback. Returns where
    the callback sent the browser."""
    fake.signing_in = login
    start = client.get(f"/api/v1/auth/{provider}/login", follow_redirects=False)
    assert start.status_code == 302, start.text
    back = fake.authorize(start.headers["location"])
    done = client.get(back.removeprefix(PUBLIC_URL), follow_redirects=False)
    assert done.status_code == 302, done.text
    csrf = client.cookies.get("__Host-archsim_csrf")
    if csrf:  # what the web app does: send the CSRF token back with every change
        client.headers["X-ArchSim-CSRF"] = csrf
    return str(done.headers["location"])


def database(client: TestClient) -> Database:
    db: Database = client.app.state.db  # type: ignore[attr-defined]
    return db


def add_user(client: TestClient, user_id: str, **roles: str) -> str:
    """A user with a role in each named workspace (``default="viewer"``)."""
    with database(client).session() as s:
        s.add(User(id=user_id, name=user_id.title(), email=f"{user_id}@example.com"))
        s.flush()
        for workspace_id, role in roles.items():
            s.add(Membership(workspace_id=workspace_id, user_id=user_id, role=role))
        s.commit()
    return user_id


def add_workspace(client: TestClient, workspace_id: str, kind: str = "team", **values: Any) -> str:
    with database(client).session() as s:
        s.add(Workspace(id=workspace_id, name=workspace_id.title(), kind=kind, **values))
        s.commit()
    return workspace_id


def set_role(client: TestClient, workspace_id: str, user_id: str, role: str) -> None:
    with database(client).session() as s:
        m = s.get(Membership, {"workspace_id": workspace_id, "user_id": user_id})
        if m is None:
            s.add(Membership(workspace_id=workspace_id, user_id=user_id, role=role))
        else:
            m.role = role
        s.commit()


@contextmanager
def acting_as(client: TestClient, user_id: str, **principal: Any) -> Iterator[None]:
    """Requests inside the block are made by ``user_id`` (a signed-in session by default)."""
    overrides = client.app.dependency_overrides  # type: ignore[attr-defined]
    kind = principal.pop("kind", "session")

    def acting(session: Session = Depends(get_session)) -> Principal:
        p = Principal(user_id, kind, **principal)
        session.info[PRINCIPAL_KEY] = p  # what get_principal does, for the history's actor
        return p

    overrides[get_principal] = acting
    try:
        yield
    finally:
        overrides.pop(get_principal, None)
