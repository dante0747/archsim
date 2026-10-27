"""Runtime settings, read from environment variables prefixed ``ARCHSIM_`` (or a ``.env`` file)."""

from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


def _default_examples_dir() -> str:
    # services/api/src/archsim_api/config.py -> repository root /examples
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "examples"
        if candidate.is_dir() and any(candidate.glob("*.json")):
            return str(candidate)
    return str(Path.cwd() / "examples")


def default_slots() -> int:
    """Simulations a worker runs at once by default: one per CPU this process may use, minus one
    left for the API and the database (at least 1, at most 32)."""
    # sched_getaffinity respects CPU sets (containers, taskset); Windows and macOS lack it
    affinity = getattr(os, "sched_getaffinity", None)
    cpus = len(affinity(0)) if affinity else (os.cpu_count() or 2)
    return max(1, min(32, cpus - 1))


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ARCHSIM_", env_file=".env", extra="ignore")

    database_url: str = Field(
        default="sqlite:///./archsim.db",
        description="SQLAlchemy URL. PostgreSQL: postgresql+psycopg://user:pass@host:5432/db",
    )
    examples_dir: str = Field(default_factory=_default_examples_dir)
    cors_origins: list[str] = Field(default_factory=lambda: ["http://localhost:3000"])
    auto_migrate: bool = True
    max_concurrent_runs: int = Field(
        default_factory=default_slots,
        ge=1,
        le=32,
        description="Runs a worker simulates at once (the embedded one, or `archsim-api worker` "
        "without --slots). Default: CPU count - 1",
    )
    runner: Literal["process", "thread"] = Field(
        default="process",
        description="process: each run in a process of its own (separate cores, killable, a "
        "crash fails only that run); thread: in the worker's threads",
    )
    embedded_worker: bool = Field(
        default=True,
        description="Simulate queued runs inside `archsim-api serve`. Turn it off on API "
        "instances when separate `archsim-api worker` processes do the simulating",
    )
    lease_seconds: float = Field(
        default=30.0,
        ge=1,
        le=3600,
        description="A run whose worker stops renewing it for this long is requeued (or failed "
        "after its second attempt)",
    )
    heartbeat_seconds: float = Field(
        default=2.0, gt=0, le=60, description="How often a worker renews its leases"
    )
    reap_seconds: float = Field(
        default=10.0, gt=0, le=600, description="How often a worker looks for expired leases"
    )
    cancel_grace_seconds: float = Field(
        default=10.0,
        ge=0,
        le=600,
        description="How long a cancelled run may take to stop before its process is killed",
    )
    max_wall_seconds: float = Field(default=300.0, gt=0, le=3600)
    max_requests: int = Field(default=2_000_000, ge=1, le=20_000_000)
    max_body_bytes: int = Field(default=5_000_000, ge=10_000)
    run_history_limit: int = Field(default=200, ge=1, description="Runs listed per project")
    prometheus_allowed_hosts: Annotated[list[str], NoDecode] = Field(
        default_factory=list,
        description="Prometheus hosts POST /calibrate may query (host or host:port, comma "
        "separated). Empty: any host, for local use. Set it before the API is reachable from "
        "anywhere but your machine.",
    )
    prometheus_timeout_s: float = Field(
        default=30.0, gt=0, le=600, description="Deadline of one Prometheus query, body included"
    )
    prometheus_max_bytes: int = Field(
        default=50 * 1024 * 1024, ge=1024, description="Largest Prometheus response accepted"
    )
    calibrate_max_wall_seconds: float = Field(
        default=60.0,
        gt=0,
        le=3600,
        description="Wall-clock limit of the replay run inside POST /calibrate (it answers "
        "synchronously); never above max_wall_seconds",
    )

    # -- sign-in and security (see SECURITY.md) ----------------------------------------------
    auth_mode: Literal["none", "oauth"] = Field(
        default="none",
        description="none: no sign-in, every request is the local owner of the default "
        "workspace (local use only). oauth: GitHub/Google sign-in, sessions and API tokens",
    )
    secret_key: SecretStr | None = Field(
        default=None,
        description="Signs the sign-in state cookie and derives CSRF tokens. Required with "
        "oauth: at least 32 random characters, the same on every API instance",
    )
    public_url: str | None = Field(
        default=None,
        description="The address people open, e.g. https://archsim.example.com. Required with "
        "oauth: sign-in callbacks go to <public_url>/api/v1/auth/<provider>/callback",
    )
    admin_emails: Annotated[list[str], NoDecode] = Field(
        default_factory=list,
        description="Verified emails (comma separated) whose first sign-in makes them an owner "
        "of the default workspace",
    )
    github_client_id: str | None = None
    github_client_secret: SecretStr | None = None
    github_url: str = Field(
        default="https://github.com", description="GitHub (Enterprise Server: its address)"
    )
    github_api_url: str = Field(
        default="https://api.github.com",
        description="GitHub's API (Enterprise Server: <address>/api/v3)",
    )
    google_client_id: str | None = None
    google_client_secret: SecretStr | None = None
    session_days: int = Field(default=30, ge=1, le=365, description="How long a sign-in lasts")
    trusted_proxies: Annotated[list[str], NoDecode] = Field(
        default_factory=lambda: ["127.0.0.1", "::1"],
        description="Addresses (IPs, CIDRs or host names, comma separated) whose "
        "X-ArchSim-Client-IP header is believed: the web server's. Others are rate-limited by "
        "their own address",
    )

    @property
    def hosted(self) -> bool:
        """Signed-in use: people other than whoever runs the server reach it."""
        return self.auth_mode == "oauth"

    def oauth_providers(self) -> list[str]:
        """Providers with a client id and secret, in the order sign-in offers them."""
        out = []
        if self.github_client_id and self.github_client_secret:
            out.append("github")
        if self.google_client_id and self.google_client_secret:
            out.append("google")
        return out

    @model_validator(mode="after")
    def _heartbeat_inside_lease(self) -> Settings:
        if self.heartbeat_seconds * 2 > self.lease_seconds:
            raise ValueError("heartbeat_seconds must be at most half of lease_seconds")
        return self

    @model_validator(mode="after")
    def _oauth_complete(self) -> Settings:
        if self.auth_mode != "oauth":
            return self
        if self.secret_key is None or len(self.secret_key.get_secret_value()) < 32:
            raise ValueError("ARCHSIM_AUTH_MODE=oauth needs ARCHSIM_SECRET_KEY (32+ characters)")
        if not self.public_url or not self.public_url.startswith(("https://", "http://")):
            raise ValueError("ARCHSIM_AUTH_MODE=oauth needs ARCHSIM_PUBLIC_URL (https://host)")
        if not self.oauth_providers():
            raise ValueError(
                "ARCHSIM_AUTH_MODE=oauth needs a GitHub or Google client id and secret "
                "(ARCHSIM_GITHUB_CLIENT_ID/SECRET, ARCHSIM_GOOGLE_CLIENT_ID/SECRET)"
            )
        self.public_url = self.public_url.rstrip("/")
        return self

    @field_validator("admin_emails", mode="before")
    @classmethod
    def _split_emails(cls, v: object) -> object:
        if isinstance(v, str):
            text = v.strip()
            if text.startswith("["):
                return [e.strip().lower() for e in json.loads(text)]
            return [e.strip().lower() for e in text.split(",") if e.strip()]
        if isinstance(v, list):
            return [str(e).strip().lower() for e in v]
        return v

    @field_validator("prometheus_allowed_hosts", "trusted_proxies", mode="before")
    @classmethod
    def _split_hosts(cls, v: object) -> object:
        if isinstance(v, str):
            text = v.strip()
            if text.startswith("["):
                return json.loads(text)
            return [h.strip() for h in text.split(",") if h.strip()]
        return v


@lru_cache
def get_settings() -> Settings:
    return Settings()
