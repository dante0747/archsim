"""FastAPI dependencies and shared helpers."""

from __future__ import annotations

import json
from collections.abc import Iterator
from functools import lru_cache
from pathlib import Path
from typing import Any, NoReturn

from fastapi import HTTPException, Request
from sqlalchemy.orm import Session

from archsim_api.config import Settings
from archsim_api.db import Database
from archsim_api.jobs.worker import JobService
from archsim_engine import ComponentRegistry, Issue


def get_settings_dep(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def get_db(request: Request) -> Database:
    db: Database = request.app.state.db
    return db


def get_session(request: Request) -> Iterator[Session]:
    db: Database = request.app.state.db
    with db.session() as s:
        yield s


def get_jobs(request: Request) -> JobService:
    jobs: JobService = request.app.state.jobs
    return jobs


def get_registry(request: Request) -> ComponentRegistry:
    registry: ComponentRegistry = request.app.state.registry
    return registry


def raise_issues(status: int, message: str, issues: list[Issue] | None = None) -> NoReturn:
    raise HTTPException(
        status_code=status,
        detail={"message": message, "issues": [i.to_dict() for i in issues or []]},
    )


@lru_cache(maxsize=8)
def _load_examples(directory: str) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    path = Path(directory)
    if not path.is_dir():
        return out
    for f in sorted(path.glob("*.json")):
        try:
            out[f.stem] = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):  # pragma: no cover - broken file
            continue
    return out


def examples(settings: Settings) -> dict[str, dict[str, Any]]:
    return _load_examples(settings.examples_dir)
