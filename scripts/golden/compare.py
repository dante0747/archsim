"""``--check``: a fresh export against the committed files, numbers within the parity bar."""

from __future__ import annotations

import json
import sys
from typing import Any

from archsim_engine.version import ENGINE_VERSION
from golden import GENERATOR
from golden.build import Built
from golden.codec import Json, pointer
from golden.paths import OUT, ROOT
from golden.store import MANIFEST, manifest, read_gz

CHECK_TOLERANCE = 1e-9
"""``--check`` compares numbers to this relative tolerance (the port's parity bar) and everything
else exactly: another platform's math library may round the last digit of a float differently."""


def close(x: Any, y: Any) -> bool:
    """Equal numbers within CHECK_TOLERANCE (booleans and everything else: equal)."""
    if isinstance(x, bool) or isinstance(y, bool):
        return x is y
    if isinstance(x, int | float) and isinstance(y, int | float):
        return x == y or abs(x - y) <= CHECK_TOLERANCE * max(abs(x), abs(y))
    return bool(x == y)


def differences(a: Any, b: Any, path: tuple[str | int, ...] = (), limit: int = 8) -> list[str]:
    """JSON Pointers where ``a`` and ``b`` differ, numbers within CHECK_TOLERANCE aside (at most
    ``limit``)."""
    out: list[str] = []

    def walk(x: Any, y: Any, p: tuple[str | int, ...]) -> None:
        if len(out) >= limit:
            return
        if isinstance(x, dict) and isinstance(y, dict):
            for k in sorted(set(x) | set(y)):
                if k not in x or k not in y:
                    out.append(f"{pointer((*p, k))} ({'added' if k not in x else 'removed'})")
                else:
                    walk(x[k], y[k], (*p, k))
        elif isinstance(x, list) and isinstance(y, list) and len(x) == len(y):
            for n, (u, v) in enumerate(zip(x, y, strict=True)):
                walk(u, v, (*p, n))
        elif isinstance(x, dict | list) or isinstance(y, dict | list) or not close(x, y):
            out.append(pointer(p) or "/")

    walk(a, b, path)
    return out


def same_manifest(old: Json, fresh: Json, close_only: set[str]) -> bool:
    """The manifests agree (a file that matched only within the tolerance has its own hash)."""

    def rows(m: Json) -> list[Json]:
        return [
            {
                k: v
                for k, v in r.items()
                if r["path"] not in close_only or k not in ("sha256", "bytes")
            }
            for r in m["files"]
        ]

    return {**old, "files": rows(old)} == {**fresh, "files": rows(fresh)}


def check(results: dict[str, Built], full: bool) -> int:
    """Compare a fresh export with the committed files: the same content, numbers within
    CHECK_TOLERANCE (files that differ only there are reported, not failed)."""
    problems: list[str] = []
    close_only: list[str] = []  # the same within the tolerance, not byte for byte
    for name, (data, _, _) in sorted(results.items()):
        path = OUT / f"{name}.json.gz"
        committed = read_gz(path)
        if committed is None:
            problems.append(f"{path.relative_to(ROOT)}: missing")
        elif committed != data:
            where = differences(json.loads(committed), json.loads(data))
            if where:
                problems.append(f"{path.relative_to(ROOT)}: differs at " + ", ".join(where))
            else:
                close_only.append(f"{name}.json.gz")
    if full:
        expected = {OUT / f"{n}.json.gz" for n in results}
        for path in sorted(OUT.glob("*/*.json.gz")):
            if path not in expected:
                problems.append(f"{path.relative_to(ROOT)}: not produced by the exporter")
        fresh = json.loads(manifest([row for _, row, _ in results.values()]))
        old = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else None
        if old is None or not same_manifest(old, fresh, set(close_only)):
            problems.append("parity/golden/manifest.json: differs")
    for name in close_only:
        print(
            f"note: {name} matches within a relative {CHECK_TOLERANCE:g}, not byte for byte "
            "(floating-point rounding of this platform?)",
            file=sys.stderr,
        )
    if problems:
        print(
            f"golden files differ from a fresh export of engine {ENGINE_VERSION}:", file=sys.stderr
        )
        for p in problems:
            print(f"  {p}", file=sys.stderr)
        print(
            "The Python engine is frozen: if the change is intended, regenerate with "
            f"`{GENERATOR}` and say why in the commit.",
            file=sys.stderr,
        )
        return 1
    print(f"golden files match engine {ENGINE_VERSION} ({len(results)} files)")
    return 0
