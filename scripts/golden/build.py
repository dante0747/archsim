"""Building golden files in memory, in one process or several."""

from __future__ import annotations

import hashlib
import os
import time
from concurrent.futures import ProcessPoolExecutor
from functools import cache

from golden.codec import Json
from golden.files import record

Built = tuple[bytes, Json, float]
"""A built file: its canonical JSON, its manifest row, and the seconds it took."""


@cache
def _prepared() -> None:
    os.environ.pop("ARCHSIM_PRICES_DIR", None)  # only the snapshots the engine ships


def build(name: str) -> tuple[str, bytes, Json, float]:
    """One golden file: (name, canonical JSON, manifest row, seconds)."""
    _prepared()
    t0 = time.perf_counter()
    g = record(name)
    data = g.data()
    row = {
        "path": f"{name}.json.gz",
        "kind": g.root["kind"],
        "cases": g.cases,
        "entries": g.entries,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    return name, data, row, time.perf_counter() - t0


def build_all(names: list[str], jobs: int) -> dict[str, Built]:
    """The files ``names``, built by ``jobs`` worker processes (one: in this process)."""
    out: dict[str, Built] = {}
    if jobs <= 1 or len(names) == 1:
        for name in names:
            _, data, row, secs = build(name)
            out[name] = (data, row, secs)
        return out
    with ProcessPoolExecutor(max_workers=jobs) as pool:
        for name, data, row, secs in pool.map(build, names):
            out[name] = (data, row, secs)
    return out
