"""Personal API tokens for the CLI, CI and MCP (``Authorization: Bearer asim_pat_...``).

A token acts as the person who made it, with their role in each workspace, lowered to viewer
for a ``read`` token and limited to one workspace when it names one. The token itself is shown
once, when it is made; only its SHA-256 is stored, so a lost token is revoked and replaced, not
recovered. Tokens are managed from a signed-in browser (or without sign-in), never with a
token."""

from __future__ import annotations

from datetime import timedelta
from typing import Literal

from fastapi import APIRouter, Depends, Response
from pydantic import Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api import limits
from archsim_api.config import Settings
from archsim_api.db import ApiToken, Database, utcnow
from archsim_api.deps import get_db, get_session, get_settings_dep, require_session
from archsim_api.identity import as_utc, conflict
from archsim_api.schemas import ApiModel, UtcDateTime
from archsim_api.security import (
    TOKEN_PREFIX,
    Principal,
    new_secret,
    not_found,
    sha256_hex,
    workspace_access,
)

router = APIRouter(prefix="/tokens", tags=["tokens"])

MAX_TOKENS = 50
"""Active tokens per person."""


class TokenCreate(ApiModel):
    name: str = Field(min_length=1, max_length=100, description="What it is for, e.g. 'CI'")
    scope: Literal["read", "write"] = Field(
        default="write", description="read: viewer everywhere; write: your own role"
    )
    workspace_id: str | None = Field(
        default=None, max_length=32, description="Limit it to this workspace (default: all yours)"
    )
    expires_in_days: int | None = Field(
        default=90, ge=1, le=3650, description="Days until it stops working (null: never)"
    )


class TokenOut(ApiModel):
    id: str
    name: str
    prefix: str
    """The token's first characters, to tell tokens apart."""
    scope: Literal["read", "write"]
    workspace_id: str | None
    created_at: UtcDateTime
    last_used_at: UtcDateTime | None
    expires_at: UtcDateTime | None
    revoked_at: UtcDateTime | None


class TokenCreated(TokenOut):
    token: str
    """The secret: shown in this answer only."""


@router.get("", response_model=list[TokenOut])
def list_tokens(
    principal: Principal = Depends(require_session), session: Session = Depends(get_session)
) -> list[TokenOut]:
    """Your tokens that still work (not revoked or expired), newest first."""
    now = utcnow()
    rows = session.execute(
        select(ApiToken)
        .where(ApiToken.user_id == principal.user_id, ApiToken.revoked_at.is_(None))
        .order_by(ApiToken.created_at.desc())
    ).scalars()
    return [
        TokenOut.model_validate(t)
        for t in rows
        if t.expires_at is None or as_utc(t.expires_at) > now
    ]


@router.post("", response_model=TokenCreated, status_code=201)
def create_token(
    body: TokenCreate,
    principal: Principal = Depends(require_session),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> TokenCreated:
    """A new token. Copy ``token`` now: it is never shown again."""
    limits.enforce(
        settings,
        db,
        f"user:{principal.user_id}:tokens",
        limits.TOKENS_PER_USER,
        "You have made many tokens today.",
    )
    if body.workspace_id is not None:
        workspace_access(session, principal, body.workspace_id)
    active = len(list_tokens(principal, session))
    if active >= MAX_TOKENS:
        raise conflict(f"You have {active} tokens: revoke some first.")
    secret = new_secret(TOKEN_PREFIX)
    token = ApiToken(
        user_id=principal.user_id,
        workspace_id=body.workspace_id,
        name=body.name.strip() or "token",
        prefix=secret[: len(TOKEN_PREFIX) + 4],
        token_hash=sha256_hex(secret),
        scope=body.scope,
        expires_at=utcnow() + timedelta(days=body.expires_in_days)
        if body.expires_in_days
        else None,
    )
    session.add(token)
    session.commit()
    out = TokenOut.model_validate(token).model_dump()
    return TokenCreated.model_validate({**out, "token": secret})


@router.delete("/{token_id}", status_code=204)
def revoke_token(
    token_id: str,
    principal: Principal = Depends(require_session),
    session: Session = Depends(get_session),
) -> Response:
    """Stop a token working, at once and everywhere."""
    token = session.get(ApiToken, token_id)
    if token is None or token.user_id != principal.user_id:
        raise not_found("Token")
    if token.revoked_at is None:
        token.revoked_at = utcnow()
        session.commit()
    return Response(status_code=204)
