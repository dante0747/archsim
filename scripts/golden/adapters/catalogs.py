"""Catalogs and constants: the component catalog, the document JSON Schema, the assumptions and
the engine's text constants."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import archsim_engine
from archsim_engine import cost as cost_module
from archsim_engine import estimate as estimate_module
from archsim_engine import report as report_module
from archsim_engine import review as review_module
from archsim_engine.assumptions import constants as assumption_constants
from archsim_engine.assumptions import render as render_assumptions
from archsim_engine.export import FORMATS
from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION, SCHEMA_VERSION
from golden.adapters.support import Adapter, Adapters, registry
from golden.codec import Json

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.registry.ComponentRegistry.catalog_document")
def _catalog_document(_: Json) -> Any:
    return registry().catalog_document()


@ADAPTERS.add("archsim_engine.registry.ComponentRegistry.types")
def _types(_: Json) -> Any:
    return registry().types()


@ADAPTERS.add("archsim_engine.document_json_schema")
def _json_schema(_: Json) -> Any:
    return archsim_engine.document_json_schema()


@ADAPTERS.add("archsim_engine.assumptions.constants")
def _constants(_: Json) -> Any:
    return assumption_constants()


@ADAPTERS.add("archsim_engine.assumptions.render")
def _render(_: Json) -> Any:
    return render_assumptions(registry())


CONSTANTS: dict[str, Callable[[], Any]] = {
    "archsim_engine.version.ENGINE_VERSION": lambda: ENGINE_VERSION,
    "archsim_engine.version.SCHEMA_VERSION": lambda: SCHEMA_VERSION,
    "archsim_engine.version.REPORT_VERSION": lambda: REPORT_VERSION,
    "archsim_engine.review.REVIEW_RULES": lambda: review_module.REVIEW_RULES,
    "archsim_engine.report.MODEL_NOTES": lambda: report_module.MODEL_NOTES,
    "archsim_engine.report.REASON_TEXT": lambda: report_module.REASON_TEXT,
    "archsim_engine.estimate.METHOD": lambda: estimate_module.METHOD,
    "archsim_engine.estimate.NOT_INCLUDED": lambda: estimate_module.NOT_INCLUDED,
    "archsim_engine.cost.NOT_MODELLED": lambda: cost_module.NOT_MODELLED,
    "archsim_engine.technology.TIER_NOTE": lambda: archsim_engine.technology.TIER_NOTE,
    "archsim_engine.export.FORMATS": lambda: FORMATS,
}
"""The engine's text constants, recorded as entries with an empty input."""


def _constant(get: Callable[[], Any]) -> Adapter:
    return lambda _: get()


ADAPTERS.update({name: _constant(get) for name, get in CONSTANTS.items()})
