"""Helpers for tests with several people: users and memberships written straight to the
database, and requests made as one of them (imported by test modules)."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from fastapi.testclient import TestClient

from archsim_api.db import Database, Membership, User, Workspace
from archsim_api.deps import get_principal
from archsim_api.security import Principal


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
    overrides[get_principal] = lambda: Principal(user_id, kind, **principal)
    try:
        yield
    finally:
        overrides.pop(get_principal, None)
