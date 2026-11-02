"""The golden-file exporter (scripts/export_golden.py): its output is deterministic, the committed
files are what the frozen engine gives, and every recorded entry replays from its own input."""

from __future__ import annotations

import gzip
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

_spec = importlib.util.spec_from_file_location(
    "export_golden", Path(__file__).parents[1] / "export_golden.py"
)
assert _spec and _spec.loader
golden = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(golden)

SMALL = "templates/queueing-lab"
"""The smallest template: two parts, four scenarios, cheap runs."""


def committed(name: str) -> bytes:
    path = golden.OUT / f"{name}.json.gz"
    if not path.exists():
        pytest.fail(f"{path} is missing: run `{golden.GENERATOR}`")
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
    return golden.build(SMALL)


def test_a_template_export_matches_the_committed_file(
    small: tuple[str, bytes, dict[str, Any], float],
) -> None:
    # as --check compares: the same content, numbers within the parity bar (a platform's math
    # library may round a float's last digit differently)
    _, data, row, _ = small
    old = committed(SMALL)
    where = golden.differences(json.loads(old), json.loads(data))
    assert not where, f"differs at {where}: the engine is frozen (`{golden.GENERATOR}` rewrites)"
    manifest = json.loads((golden.OUT / "manifest.json").read_text(encoding="utf-8"))
    listed = next(r for r in manifest["files"] if r["path"] == row["path"])
    keys = row.keys() if data == old else row.keys() - {"sha256", "bytes"}
    assert {k: listed[k] for k in keys} == {k: row[k] for k in keys}


def test_the_export_is_the_same_in_another_process(
    small: tuple[str, bytes, dict[str, Any], float],
) -> None:
    # a different hash seed changes set and dict-of-set iteration order: nothing may depend on it
    script = (
        "import importlib.util, sys; "
        f"spec = importlib.util.spec_from_file_location('g', {str(golden.__file__)!r}); "
        "g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g); "
        f"sys.stdout.buffer.write(g.build({SMALL!r})[1])"
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
        inputs = golden.resolve_refs(entry["input"], root)
        try:
            output = golden.call(entry["fn"], inputs)
        except Exception as exc:
            assert "error" in entry, f"{where}: {exc}"
            assert golden.error_json(exc) == entry["error"], where
        else:
            assert not golden.differences(entry["output"], output), where


def test_committed_files_are_well_formed() -> None:
    """Every file listed in the manifest with its hash; every entry names a known function and
    has an output or an error; references appear only in inputs and resolve."""
    manifest = json.loads((golden.OUT / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["engineVersion"] == golden.ENGINE_VERSION
    listed = {row["path"] for row in manifest["files"]}
    on_disk = {p.relative_to(golden.OUT).as_posix() for p in golden.OUT.glob("*/*.json.gz")}
    assert listed == on_disk
    assert listed == {f"{n}.json.gz" for n in golden.job_names()}
    for row in manifest["files"]:
        data = committed(row["path"].removesuffix(".json.gz"))
        assert golden.hashlib.sha256(data).hexdigest() == row["sha256"], row["path"]
        root = json.loads(data)
        assert root["format"] == golden.FORMAT
        assert root["engineVersion"] == golden.ENGINE_VERSION
        found = entries(root)
        assert len(found) == row["entries"], row["path"]
        for where, entry in found:
            assert entry["fn"] in golden.ADAPTERS, where
            assert ("output" in entry) != ("error" in entry), where
            for ref in refs(entry["input"]):
                assert not is_ref(golden.lookup(root, ref)), where  # raises if nothing is there
        assert not refs(root, skip_inputs=True), row["path"]


def test_json_conventions() -> None:
    assert golden.pointer(("a/b", "c~d", 3)) == "/a~1b/c~0d/3"
    root = {"x": {"a/b": [10, {"y": 2}]}, "z": {"$ref": "/x/a~1b/1"}}
    assert golden.resolve_refs({"v": {"$ref": "/z"}}, root) == {"v": {"y": 2}}
    assert golden.to_json({(1, 2): 3, "k": {4.0}}) == [[[1, 2], 3], ["k", [4.0]]]
    assert golden.to_json([float("inf"), float("-inf"), float("nan")]) == [
        "Infinity",
        "-Infinity",
        "NaN",
    ]
    assert golden.canonical({"b": "é", "a": 1}) == '{"a":1,"b":"é"}'.encode()
