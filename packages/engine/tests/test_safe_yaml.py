"""Untrusted YAML: aliases are allowed but bounded; bombs, recursion and deep nesting are refused."""

from __future__ import annotations

import time

import pytest
import yaml

from archsim_engine.safe_yaml import (
    MAX_DEPTH,
    UnsafeYamlError,
    load_all_yaml,
    load_yaml,
)


def bomb(levels: int) -> str:
    lines = ['a0: &a0 ["x","x","x","x","x","x","x","x","x","x"]']
    for i in range(1, levels + 1):
        lines.append(f"a{i}: &a{i} [{', '.join([f'*a{i - 1}'] * 10)}]")
    return "\n".join(lines)


def test_plain_documents_load() -> None:
    assert load_yaml("a: 1\nb: [x, y]") == {"a": 1, "b": ["x", "y"]}
    assert load_yaml("") is None
    assert list(load_all_yaml("a: 1\n---\nb: 2\n")) == [{"a": 1}, {"b": 2}]


def test_compose_merge_keys_still_work() -> None:
    text = (
        "x-defaults: &defaults\n  restart: always\n  environment: {A: '1'}\n"
        "services:\n  web:\n    <<: *defaults\n    image: nginx\n"
    )
    assert load_yaml(text)["services"]["web"] == {
        "restart": "always",
        "environment": {"A": "1"},
        "image": "nginx",
    }


def test_alias_bomb_is_refused_quickly() -> None:
    text = bomb(7)  # about 600 bytes that would expand to 10 million values
    assert len(text) < 1000
    t = time.perf_counter()
    with pytest.raises(UnsafeYamlError, match="expands to more than"):
        load_yaml(text)
    assert time.perf_counter() - t < 1.0


def test_modest_aliasing_is_allowed() -> None:
    assert len(load_yaml(bomb(3))["a3"]) == 10  # 10,000 values: fine


def test_recursive_alias_is_refused() -> None:
    with pytest.raises(UnsafeYamlError, match="recursive"):
        load_yaml("a: &a [1, *a]")


def test_deep_nesting_is_refused() -> None:
    with pytest.raises(UnsafeYamlError, match="nests deeper"):
        load_yaml("[" * (MAX_DEPTH + 5) + "]" * (MAX_DEPTH + 5))
    with pytest.raises(UnsafeYamlError):
        load_yaml("[" * 50_000 + "]" * 50_000)


def test_python_tags_are_still_refused() -> None:
    with pytest.raises(yaml.YAMLError):
        load_yaml("!!python/object/apply:os.system ['echo hi']")


def test_unsafe_yaml_error_is_a_value_error() -> None:
    # callers that map ValueError to 4xx responses keep working
    assert issubclass(UnsafeYamlError, ValueError)
