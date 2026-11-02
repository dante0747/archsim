"""The registry of engine functions an entry may name: each recorded name
(``archsim_engine.module.function``) maps to an adapter that calls the function on the recorded
JSON input. The adapters are grouped by engine area, one module each."""

from __future__ import annotations

import json
from typing import Any

from golden.adapters import (
    analysis,
    calibration,
    catalogs,
    documents,
    exports,
    formatting,
    pricing,
    reports,
)
from golden.adapters.support import Adapter, Adapters
from golden.codec import Json, canonical, to_json


def _merged(*tables: Adapters) -> dict[str, Adapter]:
    merged: dict[str, Adapter] = {}
    for table in tables:
        twice = merged.keys() & table.keys()
        if twice:
            raise ValueError(f"adapters registered twice: {sorted(twice)}")
        merged.update(table)
    return merged


ADAPTERS: dict[str, Adapter] = _merged(
    documents.ADAPTERS,
    analysis.ADAPTERS,
    exports.ADAPTERS,
    reports.ADAPTERS,
    catalogs.ADAPTERS,
    pricing.ADAPTERS,
    formatting.ADAPTERS,
    calibration.ADAPTERS,
)
"""Every engine function a golden entry may name, with its adapter."""


def call(fn: str, inputs: Json) -> Any:
    """``fn`` on a private copy of ``inputs``, its result as recorded JSON."""
    return to_json(ADAPTERS[fn](json.loads(canonical(inputs))))
