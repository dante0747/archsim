"""The golden files' JSON conventions (scripts/golden/codec.py) and how ``--check`` compares
numbers (scripts/golden/compare.py)."""

from __future__ import annotations

from golden.codec import canonical, pointer, resolve_refs, to_json
from golden.compare import close, differences


def test_json_pointers_escape_slashes_and_tildes() -> None:
    assert pointer(("a/b", "c~d", 3)) == "/a~1b/c~0d/3"


def test_references_resolve_recursively() -> None:
    root = {"x": {"a/b": [10, {"y": 2}]}, "z": {"$ref": "/x/a~1b/1"}}
    assert resolve_refs({"v": {"$ref": "/z"}}, root) == {"v": {"y": 2}}


def test_a_json_schema_ref_is_not_a_golden_reference() -> None:
    schema = {"$ref": "#/$defs/Node"}
    assert resolve_refs(schema, {}) == schema


def test_engine_values_become_plain_json() -> None:
    assert to_json({(1, 2): 3, "k": {4.0}}) == [[[1, 2], 3], ["k", [4.0]]]
    assert to_json([float("inf"), float("-inf"), float("nan")]) == [
        "Infinity",
        "-Infinity",
        "NaN",
    ]


def test_canonical_json_sorts_keys_and_keeps_unicode() -> None:
    assert canonical({"b": "é", "a": 1}) == '{"a":1,"b":"é"}'.encode()


def test_numbers_compare_within_the_parity_bar() -> None:
    assert close(1.0, 1.0 + 1e-12)
    assert not close(1.0, 1.0 + 1e-6)
    assert close(1, 1.0)
    assert not close(True, 1)
    assert differences({"a": [1.0, "x"]}, {"a": [1.0 + 1e-12, "x"]}) == []
    assert differences({"a": 1}, {"b": 1}) == ["/a (removed)", "/b (added)"]
