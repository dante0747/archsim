"""Runtime settings, read from environment variables prefixed ``ARCHSIM_`` (or a ``.env`` file)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


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


@lru_cache
def get_settings() -> Settings:
    return Settings()
