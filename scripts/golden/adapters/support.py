"""What the adapters share: their table type, the default registry, document helpers, and the
seams that replace a simulation or the clock with what was recorded."""

from __future__ import annotations

import copy
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager
from functools import cache
from typing import Any
from unittest import mock

import archsim_engine
from archsim_engine.calibration import apply as calibration_apply
from archsim_engine.calibration import otlp as calibration_otlp
from archsim_engine.components import create_default_registry
from archsim_engine.registry import ComponentRegistry
from archsim_engine.schema import ArchitectureDocument
from archsim_engine.sizing import PartSize
from golden.codec import Json, from_json, to_json

Adapter = Callable[[Json], Any]
"""Calls one engine function on its recorded JSON input and returns what it returned."""

FIXED_NOW = "2026-11-02T00:00:00Z"
"""What calibration's ``now_iso`` returns while recording (``fittedAt``, ``calibration.at``)."""


class Adapters(dict[str, Adapter]):
    """The adapters of one engine area, by the function name an entry records."""

    def add(self, fn: str) -> Callable[[Adapter], Adapter]:
        """Register the decorated adapter as the one for ``fn``."""

        def register(adapter: Adapter) -> Adapter:
            if fn in self:
                raise ValueError(f"{fn} has two adapters")
            self[fn] = adapter
            return adapter

        return register


@cache
def registry() -> ComponentRegistry:
    """The default registry without plugins: the ``registry`` argument of every entry."""
    return create_default_registry(include_plugins=False)


def doc_of(data: Json) -> ArchitectureDocument:
    return ArchitectureDocument.model_validate(data)


def dump(doc: ArchitectureDocument) -> Json:
    return doc.model_dump(mode="json", by_alias=True)


def sizes_json(sizes: Mapping[str, PartSize]) -> Json:
    return {nid: to_json(size) for nid, size in sizes.items()}


def sizes_of(data: Json) -> dict[str, PartSize]:
    return {nid: from_json(PartSize, size) for nid, size in data.items()}


@contextmanager
def injected_simulations(reports: list[Json] | None) -> Iterator[None]:
    """``archsim_engine.run_simulation`` returns these reports, in order, instead of simulating
    (a simulation is not a deterministic function; its recorded report is an input). None:
    simulate (while the exporter records what the simulations return)."""
    if reports is None:
        yield
        return
    queue = [copy.deepcopy(r) for r in reports]

    def fake(*_args: Any, **_kwargs: Any) -> Json:
        if not queue:
            raise RuntimeError("the recorded entry made more simulations than it recorded")
        return queue.pop(0)

    with mock.patch.object(archsim_engine, "run_simulation", fake):
        yield
    if queue:
        raise RuntimeError("the recorded entry made fewer simulations than it recorded")


@contextmanager
def fixed_clock() -> Iterator[None]:
    with (
        mock.patch.object(calibration_apply, "now_iso", lambda: FIXED_NOW),
        mock.patch.object(calibration_otlp, "now_iso", lambda: FIXED_NOW),
    ):
        yield
