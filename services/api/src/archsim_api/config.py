"""Runtime settings, read from environment variables prefixed ``ARCHSIM_`` (or a ``.env`` file)."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


def _default_examples_dir() -> str:
    # services/api/src/archsim_api/config.py -> repository root /examples
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "examples"
        if candidate.is_dir() and any(candidate.glob("*.json")):
            return str(candidate)
    return str(Path.cwd() / "examples")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ARCHSIM_", env_file=".env", extra="ignore")

    database_url: str = Field(
        default="sqlite:///./archsim.db",
        description="SQLAlchemy URL. PostgreSQL: postgresql+psycopg://user:pass@host:5432/db",
    )
    examples_dir: str = Field(default_factory=_default_examples_dir)
    cors_origins: list[str] = Field(default_factory=lambda: ["http://localhost:3000"])
    auto_migrate: bool = True
    max_concurrent_runs: int = Field(default=2, ge=1, le=32)
    runner: Literal["process", "thread"] = Field(
        default="process",
        description="process: each run in a worker process (separate cores, the API stays "
        "responsive); thread: in the API process",
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

    @field_validator("prometheus_allowed_hosts", mode="before")
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
