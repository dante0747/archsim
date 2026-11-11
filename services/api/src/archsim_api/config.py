"""Runtime settings, read from environment variables prefixed ``ARCHSIM_`` (or a ``.env`` file)."""

from __future__ import annotations

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """What ``archsim-api migrate`` reads. The API's, the workers' and the seed's settings are
    ``archsim-app``'s; these ignore them, so both can share one environment."""

    model_config = SettingsConfigDict(env_prefix="ARCHSIM_", env_file=".env", extra="ignore")

    database_url: str = Field(
        default="postgresql+psycopg://archsim:archsim@127.0.0.1:5432/archsim",
        description="SQLAlchemy URL of the API's database (the API's default, compose's "
        "PostgreSQL). The tests also use SQLite: sqlite:///./archsim.db",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
