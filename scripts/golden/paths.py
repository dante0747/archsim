"""Where the exporter reads its inputs and writes the golden files."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import archsim_engine

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "parity" / "golden"
EXAMPLES = ROOT / "examples"
FIXTURES = ROOT / "parity" / "fixtures"
PRICES = Path(archsim_engine.__file__).parent / "pricing"

STARTER = "cache-aside-starter"
"""The template most fixtures start from (invalid documents, calibration)."""


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))
