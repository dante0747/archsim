"""The committed files: reading them, and writing built files with their manifest."""

from __future__ import annotations

import gzip
import json
from pathlib import Path

from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION, SCHEMA_VERSION
from golden import FORMAT, GENERATOR
from golden.build import Built
from golden.codec import Json
from golden.paths import OUT, ROOT

MANIFEST = OUT / "manifest.json"


def manifest(rows: list[Json]) -> str:
    doc = {
        "format": FORMAT,
        "engineVersion": ENGINE_VERSION,
        "schemaVersion": SCHEMA_VERSION,
        "reportVersion": REPORT_VERSION,
        "generator": GENERATOR,
        "note": "sha256 and bytes are of each file's uncompressed canonical JSON",
        "files": sorted(rows, key=lambda r: r["path"]),
    }
    return json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def read_gz(path: Path) -> bytes | None:
    return gzip.decompress(path.read_bytes()) if path.exists() else None


def write(results: dict[str, Built], full: bool) -> None:
    """Write the built files and the manifest (``full``: every file, removing stale ones)."""
    rows: dict[str, Json] = {}
    if not full and MANIFEST.exists():
        rows = {r["path"]: r for r in json.loads(MANIFEST.read_text(encoding="utf-8"))["files"]}
    for name, (data, row, _) in sorted(results.items()):
        target = OUT / f"{name}.json.gz"
        rows[row["path"]] = row
        if read_gz(target) == data:
            continue  # unchanged: keep the file's bytes (another zlib may compress differently)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(gzip.compress(data, compresslevel=9, mtime=0))
        print(f"wrote {target.relative_to(ROOT)}")
    if full:
        expected = {OUT / f"{n}.json.gz" for n in results}
        for stale in sorted(OUT.glob("*/*.json.gz")):
            if stale not in expected:
                stale.unlink()
                print(f"removed {stale.relative_to(ROOT)}")
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(manifest(list(rows.values())), encoding="utf-8", newline="\n")
