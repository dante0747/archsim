"""Runtime settings, read from environment variables prefixed ``ARCHSIM_`` (or a ``.env`` file)."""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


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
    """What ``archsim-api worker``, ``migrate`` and ``seed`` read. The API's own settings (sign-in,
    CORS, calibration ...) are ``archsim-app``'s; the worker ignores them, so both can share one
    environment."""

    model_config = SettingsConfigDict(env_prefix="ARCHSIM_", env_file=".env", extra="ignore")

    database_url: str = Field(
        default="postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim",
        description="SQLAlchemy URL of the API's database (the API's default, compose's "
        "PostgreSQL). The tests also use SQLite: sqlite:///./archsim.db",
    )
    examples_dir: str = Field(default_factory=_default_examples_dir)
    max_concurrent_runs: int = Field(
        default_factory=default_slots,
        ge=1,
        le=32,
        description="Runs a worker simulates at once (without --slots). Default: CPU count - 1",
    )
    runner: Literal["process", "thread"] = Field(
        default="process",
        description="process: each run in a process of its own (separate cores, killable, a "
        "crash fails only that run); thread: in the worker's threads",
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
    auth_mode: Literal["none", "oauth"] = Field(
        default="none",
        description="The API's sign-in: with oauth (hosted use) the limits per workspace kind are "
        "on unless ARCHSIM_RATE_LIMITS says otherwise",
    )
    rate_limits: bool | None = Field(
        default=None,
        description="The limits per workspace kind (runs at once). Default: on with sign-in, off "
        "without",
    )

    @property
    def hosted(self) -> bool:
        """Signed-in use: people other than whoever runs the server reach it."""
        return self.auth_mode == "oauth"

    @property
    def limits_on(self) -> bool:
        return self.hosted if self.rate_limits is None else self.rate_limits

    @model_validator(mode="after")
    def _heartbeat_inside_lease(self) -> Settings:
        if self.heartbeat_seconds * 2 > self.lease_seconds:
            raise ValueError("heartbeat_seconds must be at most half of lease_seconds")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
