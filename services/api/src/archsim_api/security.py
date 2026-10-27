"""Who is asking, and what they may do: principals, roles, secrets kept as hashes.

Every request has a *principal*. Without sign-in (``ARCHSIM_AUTH_MODE=none``) it is the local
user, owner of the ``default`` workspace. With sign-in it is a browser session (cookie) or a
personal API token (``Authorization: Bearer asim_pat_...``).

Access is per workspace: a principal's role there is its membership's (``viewer`` < ``editor``
< ``owner``), lowered to ``viewer`` for a read-only token and absent outside a token's one
workspace. Someone with no role in a workspace learns nothing about it: its projects and runs
answer 404 exactly like ones that do not exist. A role too weak for an action answers 403.
"""

from __future__ import annotations

import hashlib
import ipaddress
import secrets
import socket
import time
from dataclasses import dataclass, field
from functools import lru_cache
from typing import Literal

from fastapi import HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session, undefer

from archsim_api.db import ROLES, Membership, Project, SimulationRun, Workspace

PrincipalKind = Literal["local", "session", "token"]

TOKEN_PREFIX = "asim_pat_"
"""Personal API tokens start with this, so secret scanners and people can recognise them."""
INVITE_PREFIX = "asim_inv_"


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def new_secret(prefix: str = "") -> str:
    """256 random bits, URL-safe."""
    return prefix + secrets.token_urlsafe(32)


CLIENT_IP_HEADER = "x-archsim-client-ip"
"""The visitor's address, as the web server saw it (believed only from ``trusted_proxies``)."""


@lru_cache(maxsize=64)
def _resolve(name: str, _bucket: int) -> frozenset[str]:
    try:
        return frozenset(str(info[4][0]) for info in socket.getaddrinfo(name, None))
    except OSError:
        return frozenset()


def _is_trusted(peer: str, trusted: list[str]) -> bool:
    try:
        address = ipaddress.ip_address(peer)
    except ValueError:
        address = None
    for entry in trusted:
        if entry == peer:
            return True
        if address is None:
            continue
        try:
            if address in ipaddress.ip_network(entry, strict=False):
                return True
        except ValueError:  # a host name (the web container's): resolved once a minute
            if peer in _resolve(entry, int(time.monotonic() // 60)):
                return True
    return False


def client_ip(request: Request, trusted: list[str]) -> str | None:
    """Who is asking: ``X-ArchSim-Client-IP`` when the request comes from a trusted proxy (the
    web server), else the address the request came from."""
    peer = request.client.host if request.client else None
    forwarded = request.headers.get(CLIENT_IP_HEADER)
    if forwarded and peer and _is_trusted(peer, trusted):
        try:
            return str(ipaddress.ip_address(forwarded.strip()))
        except ValueError:
            pass
    return peer


def at_least(role: str, minimum: str) -> bool:
    return ROLES.index(role) >= ROLES.index(minimum)


def not_found(what: str) -> HTTPException:
    return HTTPException(404, detail={"message": f"{what} not found", "issues": []})


def forbidden(role: str, minimum: str) -> HTTPException:
    article = "an" if role[0] in "aeiou" else "a"
    return HTTPException(
        403,
        detail={
            "message": f"You are {article} {role} in this workspace; this needs {minimum} access.",
            "issues": [],
        },
    )


@dataclass
class Principal:
    user_id: str
    kind: PrincipalKind
    ip: str | None = None
    session_id: str | None = None
    token_id: str | None = None
    token_workspace_id: str | None = None
    """A token limited to one workspace sees no other."""
    read_only: bool = False
    """A read-scoped token: viewer at most, wherever its user has a role."""
    _roles: dict[str, str | None] = field(default_factory=dict, repr=False)

    def role_in(self, session: Session, workspace_id: str) -> str | None:
        """This principal's role in a workspace, None when it has none there."""
        if workspace_id in self._roles:
            return self._roles[workspace_id]
        role: str | None = None
        if self.token_workspace_id is None or workspace_id == self.token_workspace_id:
            m = session.get(Membership, {"workspace_id": workspace_id, "user_id": self.user_id})
            if m is not None:
                role = "viewer" if self.read_only else m.role
        self._roles[workspace_id] = role
        return role


@dataclass(frozen=True)
class WorkspaceAccess:
    workspace: Workspace
    role: str

    def require(self, minimum: str) -> None:
        if not at_least(self.role, minimum):
            raise forbidden(self.role, minimum)


@dataclass(frozen=True)
class ProjectAccess:
    project: Project
    role: str


@dataclass(frozen=True)
class RunAccess:
    run: SimulationRun
    role: str


def workspace_access(
    session: Session, principal: Principal, workspace_id: str, minimum: str = "viewer"
) -> WorkspaceAccess:
    ws = session.get(Workspace, workspace_id)
    role = principal.role_in(session, workspace_id) if ws is not None else None
    if ws is None or role is None:
        raise not_found(f"Workspace '{workspace_id}'")
    if not at_least(role, minimum):
        raise forbidden(role, minimum)
    return WorkspaceAccess(ws, role)


def project_access(
    session: Session, principal: Principal, project_id: str, minimum: str = "viewer"
) -> ProjectAccess:
    project = session.get(Project, project_id)
    role = principal.role_in(session, project.workspace_id) if project is not None else None
    if project is None or role is None:
        raise not_found(f"Project '{project_id}'")
    if not at_least(role, minimum):
        raise forbidden(role, minimum)
    return ProjectAccess(project, role)


def run_access(
    session: Session,
    principal: Principal,
    run_id: str,
    minimum: str = "viewer",
    with_payload: bool = False,
) -> RunAccess:
    stmt = select(SimulationRun).where(SimulationRun.id == run_id)
    if with_payload:
        stmt = stmt.options(undefer(SimulationRun.report), undefer(SimulationRun.input_document))
    run = session.execute(stmt).scalar_one_or_none()
    role = principal.role_in(session, run.workspace_id) if run is not None else None
    if run is None or role is None:
        raise not_found(f"Run '{run_id}'")
    if not at_least(role, minimum):
        raise forbidden(role, minimum)
    return RunAccess(run, role)
