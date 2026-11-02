"""A golden file under construction (``GoldenFile``), and the exporter's consistency check."""

from __future__ import annotations

import hashlib
from typing import Any

from archsim_engine.version import ENGINE_VERSION
from golden import FORMAT
from golden.adapters import call
from golden.codec import Json, canonical, error_json, pointer, to_json

MIN_REF_BYTES = 256
"""Inputs at least this large (canonical JSON, or a string's length) that already appear in the
file are written as a ``$ref`` to where they first appear."""
REF_DEPTH = 4
"""How deep inside a value the file looks for parts it already holds."""


class GoldenFile:
    """One golden file: its JSON tree, the entries recorded into it, and the values it already
    holds (an input that repeats one of them is written as a ``$ref`` to it)."""

    def __init__(self, kind: str, **meta: Any) -> None:
        self.root: Json = {"format": FORMAT, "engineVersion": ENGINE_VERSION, "kind": kind, **meta}
        self.cases = 0
        self.entries = 0
        self._known: dict[str, str] = {}
        self._keys: dict[int, tuple[Any, str | None]] = {}

    # -- tree ------------------------------------------------------------------------------------

    def _set(self, path: tuple[str, ...], value: Any) -> None:
        node = self.root
        for key in path[:-1]:
            node = node.setdefault(key, {})
        if path[-1] in node:
            raise ValueError(f"{pointer(path)} is recorded twice")
        node[path[-1]] = value

    def place(self, path: tuple[str, ...], value: Any) -> None:
        """A literal value at ``path`` (later inputs that repeat it refer to it)."""
        self._set(path, value)
        self._register(path, value, 0)

    # -- references ------------------------------------------------------------------------------

    def _key(self, value: Any) -> str | None:
        """The content hash of a value large enough to refer to (None: too small). Values are
        never changed once recorded, so the hash is kept per object (with the object, so its
        id is not reused)."""
        if not isinstance(value, str | dict | list):
            return None
        known = self._keys.get(id(value))
        if known is not None and known[0] is value:
            return known[1]
        key: str | None = None
        if isinstance(value, str):
            if len(value) >= MIN_REF_BYTES:
                key = "s" + hashlib.sha256(value.encode()).hexdigest()
        else:
            data = canonical(value)
            if len(data) >= MIN_REF_BYTES:
                key = "j" + hashlib.sha256(data).hexdigest()
        self._keys[id(value)] = (value, key)
        return key

    def _register(self, path: tuple[str | int, ...], value: Any, depth: int) -> None:
        key = self._key(value)
        if key is None:
            return
        self._known.setdefault(key, pointer(path))
        if depth < REF_DEPTH and isinstance(value, dict | list):
            items = value.items() if isinstance(value, dict) else enumerate(value)
            for k, v in items:
                self._register((*path, k), v, depth + 1)

    def _dedupe(self, path: tuple[str | int, ...], value: Any, depth: int) -> Any:
        key = self._key(value)
        if key is None:
            return value
        if key in self._known:
            return {"$ref": self._known[key]}
        self._known[key] = pointer(path)
        if depth >= REF_DEPTH or isinstance(value, str):
            return value
        if isinstance(value, dict):
            return {k: self._dedupe((*path, k), v, depth + 1) for k, v in value.items()}
        return [self._dedupe((*path, n), v, depth + 1) for n, v in enumerate(value)]

    # -- entries ---------------------------------------------------------------------------------

    def entry(
        self,
        where: tuple[str, ...],
        name: str,
        fn: str,
        inputs: Json,
        *,
        errors: bool = False,
    ) -> Any:
        """Record ``fn`` on ``inputs`` as entry ``name`` under ``where`` and return its output
        (None when it raised and ``errors`` allows that: the error is recorded instead)."""
        path = (*where, "entries", name)
        try:
            output = call(fn, inputs)
        except Exception as exc:
            if not errors:
                raise RuntimeError(f"{fn} failed at {pointer(path)}: {exc}") from exc
            result: Json = {"error": error_json(exc)}
            output = None
        else:
            result = {"output": output}
        stored = {
            "fn": fn,
            "input": {k: self._dedupe((*path, "input", k), v, 0) for k, v in inputs.items()},
            **result,
        }
        self._set(path, stored)
        if "output" in result:
            self._register((*path, "output"), output, 0)
        self.entries += 1
        return output

    def data(self) -> bytes:
        return canonical(self.root)


def same(label: str, recorded: Any, expected: Any) -> None:
    """The exporter's own consistency checks: a function fed the recorded JSON gives what the
    engine computed in one piece (else the recorded input is not enough to test it)."""
    if canonical(to_json(recorded)) != canonical(to_json(expected)):
        raise AssertionError(f"inconsistent golden data: {label}")
