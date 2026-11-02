"""The golden-file exporter (scripts/golden): its output is deterministic, the committed files are
what the frozen engine gives, and every recorded entry replays from its own input."""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from archsim_engine.version import ENGINE_VERSION
from golden import FORMAT, GENERATOR
from golden.adapters import ADAPTERS, call
from golden.build import build
from golden.codec import error_json, lookup, resolve_refs
from golden.compare import differences
from golden.files import job_names
from golden.paths import OUT

SCRIPTS = Path(__file__).resolve().parents[1]

SMALL = "templates/queueing-lab"
"""The smallest template: two parts, four scenarios, cheap runs."""


def committed(name: str) -> bytes:
    path = OUT / f"{name}.json.gz"
    if not path.exists():
        pytest.fail(f"{path} is missing: run `{GENERATOR}`")
    return gzip.decompress(path.read_bytes())


def entries(node: Any, path: str = "") -> list[tuple[str, dict[str, Any]]]:
    """Every recorded entry in a golden file, with its JSON Pointer."""
    if not isinstance(node, dict):
        return []
    if isinstance(node.get("fn"), str) and "input" in node:
        return [(path, node)]
    return [e for k, v in node.items() for e in entries(v, f"{path}/{k}")]


def is_ref(node: Any) -> bool:
    """A golden-file reference (a JSON Schema's own ``$ref`` starts with ``#``)."""
    return (
        isinstance(node, dict)
        and len(node) == 1
        and isinstance(node.get("$ref"), str)
        and node["$ref"].startswith("/")
    )


def refs(node: Any, *, skip_inputs: bool = False) -> list[str]:
    """The references in ``node`` (with ``skip_inputs``: outside every entry's input)."""
    if is_ref(node):
        return [node["$ref"]]
    if isinstance(node, dict):
        if skip_inputs and isinstance(node.get("fn"), str) and "input" in node:
            node = {k: v for k, v in node.items() if k != "input"}
        return [r for v in node.values() for r in refs(v, skip_inputs=skip_inputs)]
    if isinstance(node, list):
        return [r for v in node for r in refs(v, skip_inputs=skip_inputs)]
    return []


@pytest.fixture(scope="module")
def small() -> tuple[str, bytes, dict[str, Any], float]:
    return build(SMALL)


def test_a_template_export_matches_the_committed_file(
    small: tuple[str, bytes, dict[str, Any], float],
) -> None:
    # as --check compares: the same content, numbers within the parity bar (a platform's math
    # library may round a float's last digit differently)
    _, data, row, _ = small
    old = committed(SMALL)
    where = differences(json.loads(old), json.loads(data))
    assert not where, f"differs at {where}: the engine is frozen (`{GENERATOR}` rewrites)"
    manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
    listed = next(r for r in manifest["files"] if r["path"] == row["path"])
    keys = row.keys() if data == old else row.keys() - {"sha256", "bytes"}
    assert {k: listed[k] for k in keys} == {k: row[k] for k in keys}


def test_the_export_is_the_same_in_another_process(
    small: tuple[str, bytes, dict[str, Any], float],
) -> None:
    # a different hash seed changes set and dict-of-set iteration order: nothing may depend on it
    script = (
        "import sys; "
        f"sys.path.insert(0, {str(SCRIPTS)!r}); "
        "from golden.build import build; "
        f"sys.stdout.buffer.write(build({SMALL!r})[1])"
    )
    env = {**os.environ, "PYTHONHASHSEED": "4242"}
    out = subprocess.run(
        [sys.executable, "-c", script], capture_output=True, check=True, env=env, timeout=120
    )
    assert out.stdout == small[1]


def test_every_entry_replays_from_its_recorded_input() -> None:
    """What a Java test does with each entry: resolve the input's references, call the function,
    compare with the recorded output (or error)."""
    root = json.loads(committed(SMALL))
    found = entries(root)
    assert len(found) > 100
    for where, entry in found:
        inputs = resolve_refs(entry["input"], root)
        try:
            output = call(entry["fn"], inputs)
        except Exception as exc:
            assert "error" in entry, f"{where}: {exc}"
            assert error_json(exc) == entry["error"], where
        else:
            assert not differences(entry["output"], output), where


def test_committed_files_are_well_formed() -> None:
    """Every file listed in the manifest with its hash; every entry names a known function and
    has an output or an error; references appear only in inputs and resolve."""
    manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["engineVersion"] == ENGINE_VERSION
    listed = {row["path"] for row in manifest["files"]}
    on_disk = {p.relative_to(OUT).as_posix() for p in OUT.glob("*/*.json.gz")}
    assert listed == on_disk
    assert listed == {f"{n}.json.gz" for n in job_names()}
    for row in manifest["files"]:
        data = committed(row["path"].removesuffix(".json.gz"))
        assert hashlib.sha256(data).hexdigest() == row["sha256"], row["path"]
        root = json.loads(data)
        assert root["format"] == FORMAT
        assert root["engineVersion"] == ENGINE_VERSION
        found = entries(root)
        assert len(found) == row["entries"], row["path"]
        for where, entry in found:
            assert entry["fn"] in ADAPTERS, where
            assert ("output" in entry) != ("error" in entry), where
            for ref in refs(entry["input"]):
                assert not is_ref(lookup(root, ref)), where  # raises if nothing is there
        assert not refs(root, skip_inputs=True), row["path"]
