"""Runtime settings, read from environment variables prefixed ``ARCHSIM_`` (or a ``.env`` file)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

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
    """What ``archsim-api migrate`` and ``seed`` read. The API's and the workers' settings are
    ``archsim-app``'s; these ignore them, so both can share one environment."""

    model_config = SettingsConfigDict(env_prefix="ARCHSIM_", env_file=".env", extra="ignore")

    database_url: str = Field(
        default="postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim",
        description="SQLAlchemy URL of the API's database (the API's default, compose's "
        "PostgreSQL). The tests also use SQLite: sqlite:///./archsim.db",
    )
    examples_dir: str = Field(default_factory=_default_examples_dir)


@lru_cache
def get_settings() -> Settings:
    return Settings()
