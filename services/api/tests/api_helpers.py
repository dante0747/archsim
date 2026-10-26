"""Helpers for API tests (imported by test modules)."""

from __future__ import annotations

import copy
import json
import time
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

from archsim_api.config import Settings
from archsim_api.main import create_app

REPO_ROOT = Path(__file__).resolve().parents[3]
EXAMPLES = REPO_ROOT / "examples"


def make_client(db_url: str, **overrides: Any) -> TestClient:
    # threads by default: quicker to start; test_api_process_runner covers the process runner
    settings = Settings(
        database_url=db_url, examples_dir=str(EXAMPLES), **{"runner": "thread", **overrides}
    )
    return TestClient(create_app(settings))


def starter(duration: float = 8, warmup: float = 2) -> dict[str, Any]:
    d = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    d = copy.deepcopy(d)
    d["workload"]["durationS"] = duration
    d["workload"]["warmupS"] = warmup
    return d


def wait_for(client: TestClient, run_id: str, timeout: float = 60) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        r = client.get(f"/api/v1/runs/{run_id}").json()
        if r["status"] not in ("queued", "running"):
            return r
        time.sleep(0.05)
    raise AssertionError(f"run {run_id} did not finish in {timeout}s")


def create_project(client: TestClient, document: dict[str, Any] | None = None) -> dict[str, Any]:
    res = client.post("/api/v1/projects", json={"document": document or starter()})
    assert res.status_code == 201, res.text
    return res.json()
