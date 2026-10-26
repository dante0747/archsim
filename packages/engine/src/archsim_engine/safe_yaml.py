"""YAML from sources ArchSim does not control (imports, calibration mappings, MCP inputs).

PyYAML's ``safe_load`` refuses Python tags, but it still expands aliases: a few hundred bytes of
nested ``&a``/``*a`` references ("billion laughs") turn into millions of values as soon as
anything walks the result, freezing whichever process holds it. Real files do use aliases
(Compose's ``<<: *defaults``), so they are allowed, but the size they expand to is measured on
the parsed node graph, before any Python object is built, and refused past a limit. Recursive
aliases and very deep nesting are refused too.
"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import yaml
from yaml.nodes import MappingNode, Node, SequenceNode

MAX_YAML_BYTES = 5 * 1024 * 1024
"""Largest text accepted (the API's request body limit)."""
MAX_EXPANDED_NODES = 200_000
"""Most values a document may expand to once aliases are followed."""
MAX_DEPTH = 100
"""Deepest nesting accepted."""


class UnsafeYamlError(ValueError):
    """The YAML is too large, expands too far through aliases, or nests too deeply."""


def _expanded_size(root: Node) -> int:
    """Number of nodes after following every alias, without building anything. Shared
    sub-graphs are measured once (memoised), so this is linear in the parsed graph."""
    sizes: dict[int, int] = {}
    in_progress: set[int] = set()

    def size(node: Node, depth: int) -> int:
        if depth > MAX_DEPTH:
            raise UnsafeYamlError(f"the YAML nests deeper than {MAX_DEPTH} levels")
        key = id(node)
        if key in sizes:
            return sizes[key]
        if key in in_progress:
            raise UnsafeYamlError("the YAML contains a recursive alias")
        in_progress.add(key)
        total = 1
        if isinstance(node, SequenceNode):
            for child in node.value:
                total += size(child, depth + 1)
                if total > MAX_EXPANDED_NODES:
                    break
        elif isinstance(node, MappingNode):
            for k, v in node.value:
                total += size(k, depth + 1) + size(v, depth + 1)
                if total > MAX_EXPANDED_NODES:
                    break
        in_progress.discard(key)
        if total > MAX_EXPANDED_NODES:
            raise UnsafeYamlError(
                f"the YAML expands to more than {MAX_EXPANDED_NODES:,} values through aliases"
            )
        sizes[key] = total
        return total

    return size(root, 0)


def _check_text(text: str) -> None:
    if len(text.encode("utf-8", errors="replace")) > MAX_YAML_BYTES:
        raise UnsafeYamlError(f"the YAML is larger than {MAX_YAML_BYTES // (1024 * 1024)} MB")


def load_all_yaml(text: str) -> Iterator[Any]:
    """Every document in ``text``, parsed safely (see the module docstring)."""
    _check_text(text)
    loader = yaml.SafeLoader(text)
    try:
        while loader.check_node():
            try:
                node = loader.get_node()
            except RecursionError as exc:
                raise UnsafeYamlError(f"the YAML nests deeper than {MAX_DEPTH} levels") from exc
            if node is None:
                continue
            _expanded_size(node)
            yield loader.construct_document(node)
    finally:
        loader.dispose()


def load_yaml(text: str) -> Any:
    """The single document in ``text`` (``None`` when empty), parsed safely."""
    docs = list(load_all_yaml(text))
    if len(docs) > 1:
        raise yaml.YAMLError("expected one YAML document, found several")
    return docs[0] if docs else None
