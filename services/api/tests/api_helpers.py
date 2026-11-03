"""Helpers for the worker tests (imported by test modules)."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
EXAMPLES = REPO_ROOT / "examples"


def starter(duration: float = 8, warmup: float = 2) -> dict[str, Any]:
    """The cache-aside starter example, simulating ``duration`` seconds after ``warmup``."""
    text = (EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8")
    d: dict[str, Any] = copy.deepcopy(json.loads(text))
    d["workload"]["durationS"] = duration
    d["workload"]["warmupS"] = warmup
    return d
