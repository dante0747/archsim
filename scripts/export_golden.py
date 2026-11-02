"""Golden files for the Java port: the inputs and outputs of every deterministic engine function,
recorded from the frozen Python engine (0.16.0) so a Java test can feed each function the
recorded input and compare its output, without Python. See parity/README.md for the format.

What is recorded:

* per template (``examples/*.json``) and each of its scenarios: loading, migration, validation,
  scenario resolution and auto-sizing, the simulation input, design review, input provenance,
  technology tiers, the load and instant estimates, sizing, cost, confidence, quick check and
  the full ``analyze_document``; the exports and their import round trips;
* per template, two short fixed-seed simulations (the base design and one scenario, on a
  compressed time axis): their reports are recorded as *inputs* to the functions that read a
  report (findings, report confidence, post-run cost, explain formulas, SLOs, compare, seeds,
  check, sweep rows, digests);
* template-independent catalogs: component registry, document JSON Schema, pricing, assumptions
  and constants, formatting helpers on value grids;
* fixtures: older schema versions, invalid documents, importer inputs and calibration fits.

Every output is computed by calling the engine on the recorded JSON input (``ADAPTERS``), so an
entry's output is a function of its input alone.

Usage:
    uv run python scripts/export_golden.py                 # write parity/golden/
    uv run python scripts/export_golden.py --check         # exit 1 if a fresh export differs
    uv run python scripts/export_golden.py --only queueing-lab [--check]
"""

from __future__ import annotations

import argparse
import copy
import dataclasses
import gzip
import hashlib
import inspect
import json
import math
import os
import sys
import time
from collections.abc import Callable, Iterator, Mapping
from concurrent.futures import ProcessPoolExecutor
from contextlib import ExitStack, contextmanager
from functools import cache
from pathlib import Path
from typing import Any
from unittest import mock

from pydantic import BaseModel, ValidationError

import archsim_engine
from archsim_engine import analysis as analysis_module
from archsim_engine import check as check_module
from archsim_engine import compare as compare_module
from archsim_engine import confidence as confidence_module
from archsim_engine import cost as cost_module
from archsim_engine import engine as engine_module
from archsim_engine import estimate as estimate_module
from archsim_engine import explain as explain_module
from archsim_engine import load as load_module
from archsim_engine import migrations as migrations_module
from archsim_engine import provenance as provenance_module
from archsim_engine import report as report_module
from archsim_engine import review as review_module
from archsim_engine import scenarios as scenarios_module
from archsim_engine import seeds as seeds_module
from archsim_engine import sizing as sizing_module
from archsim_engine import slo as slo_module
from archsim_engine import sweep as sweep_module
from archsim_engine.assumptions import constants as assumption_constants
from archsim_engine.assumptions import render as render_assumptions
from archsim_engine.calibration import apply as calibration_apply
from archsim_engine.calibration import fit as calibration_fit
from archsim_engine.calibration import mapping as calibration_mapping
from archsim_engine.calibration import otlp as calibration_otlp
from archsim_engine.calibration import prometheus as calibration_prometheus
from archsim_engine.calibration import resources as calibration_resources
from archsim_engine.components import create_default_registry
from archsim_engine.distributions import Empirical
from archsim_engine.export import FORMATS, export_document
from archsim_engine.export import _auto_sized as export_auto_sized
from archsim_engine.export.compose import to_docker_compose
from archsim_engine.export.k8s import to_kubernetes
from archsim_engine.export.mermaid import to_mermaid
from archsim_engine.importers import import_design
from archsim_engine.issues import DocumentInvalidError
from archsim_engine.pricing import (
    Busy,
    Sku,
    Snapshot,
    available,
    content_sha256,
    default_pricing,
    load_snapshot,
    machine_kind,
    parse_snapshot,
    pinned,
    snapshot_for,
    unknown_families,
)
from archsim_engine.registry import ComponentRegistry
from archsim_engine.schema import ArchitectureDocument, Override, Workload
from archsim_engine.sizing import PartSize
from archsim_engine.validation import DEFAULT_MAX_AMPLIFICATION
from archsim_engine.version import ENGINE_VERSION, REPORT_VERSION, SCHEMA_VERSION

Json = dict[str, Any]

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "parity" / "golden"
EXAMPLES = ROOT / "examples"
FIXTURES = ROOT / "packages" / "engine" / "tests" / "fixtures"
PRICES = Path(archsim_engine.__file__).parent / "pricing"

FORMAT = "archsim.golden/1"
GENERATOR = "uv run python scripts/export_golden.py"
FIXED_NOW = "2026-11-02T00:00:00Z"
"""What calibration's ``now_iso`` returns while recording (``fittedAt``, ``calibration.at``)."""

MIN_REF_BYTES = 256
"""Inputs at least this large (canonical JSON, or a string's length) that already appear in the
file are written as a ``$ref`` to where they first appear."""
REF_DEPTH = 4
"""How deep inside a value the file looks for parts it already holds."""

RUN_TARGET_REQUESTS = 1500
"""A recorded run's time axis is compressed so it offers about this many requests..."""
RUN_MIN_S = 2.0
"""...but lasts at least this long (s), and never longer than the template's own run."""


# =============================================================================================
# JSON conventions
# =============================================================================================


def camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(p[:1].upper() + p[1:] for p in rest)


def to_json(value: Any) -> Any:
    """Plain JSON of an engine value: pydantic models dumped by alias (as the engine dumps them),
    dataclasses as objects with camelCase field names, tuples as lists, sets as sorted lists, a
    dict with non-string keys as a list of ``[key, value]`` pairs, non-finite floats as the
    strings ``"Infinity"``, ``"-Infinity"`` and ``"NaN"``."""
    if value is None or isinstance(value, bool | int | str):
        return value
    if isinstance(value, float):
        if math.isfinite(value):
            return value
        return "NaN" if math.isnan(value) else ("Infinity" if value > 0 else "-Infinity")
    if isinstance(value, BaseModel):
        return to_json(value.model_dump(mode="json", by_alias=True))
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {camel(f.name): to_json(getattr(value, f.name)) for f in dataclasses.fields(value)}
    if isinstance(value, Mapping):
        if all(isinstance(k, str) for k in value):
            return {k: to_json(v) for k, v in value.items()}
        return [[to_json(k), to_json(v)] for k, v in value.items()]
    if isinstance(value, list | tuple):
        return [to_json(v) for v in value]
    if isinstance(value, set | frozenset):
        return sorted(to_json(v) for v in value)
    raise TypeError(f"cannot record a {type(value).__name__} as JSON")


def number(value: Any) -> Any:
    """A recorded number back as a float (non-finite floats are recorded as strings)."""
    if value in ("Infinity", "-Infinity", "NaN"):
        return float(value.replace("Infinity", "inf").replace("NaN", "nan"))
    return value


def from_json(cls: type, data: Json) -> Any:
    """A dataclass from its recorded JSON (camelCase field names): lists become tuples where the
    field is a tuple, and recorded non-finite numbers floats where it is a float."""
    names = {camel(f.name): f for f in dataclasses.fields(cls)}
    kwargs: Json = {}
    for key, value in data.items():
        field = names[key]
        kind = str(field.type)
        if isinstance(value, list) and kind.startswith("tuple"):
            value = tuple(tuple(v) if isinstance(v, list) else v for v in value)
        kwargs[field.name] = number(value) if kind.startswith("float") else value
    return cls(**kwargs)


def canonical(value: Any) -> bytes:
    """Canonical JSON: sorted keys, no whitespace, UTF-8 (not ASCII-escaped), no NaN."""
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode("utf-8")


def pointer(path: tuple[str | int, ...]) -> str:
    """The JSON Pointer (RFC 6901) of ``path``."""
    return "".join("/" + str(p).replace("~", "~0").replace("/", "~1") for p in path)


def lookup(root: Any, ref: str) -> Any:
    """What the JSON Pointer ``ref`` points to in ``root`` (KeyError, IndexError: nothing)."""
    target = root
    for token in ref[1:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        target = target[int(token)] if isinstance(target, list) else target[token]
    return target


def resolve_refs(value: Any, root: Any) -> Any:
    """``value`` with every ``{"$ref": "/..."}`` replaced by what it points to in ``root``."""
    if isinstance(value, dict):
        ref = value.get("$ref")
        if len(value) == 1 and isinstance(ref, str) and ref.startswith("/"):
            return resolve_refs(lookup(root, ref), root)
        return {k: resolve_refs(v, root) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve_refs(v, root) for v in value]
    return value


def error_json(exc: BaseException) -> Json:
    """How a raised error is recorded: its type and message (and a document's issues)."""
    message = (
        calibration_mapping.validation_message(exc)
        if isinstance(exc, ValidationError)
        else str(exc)
    )
    out: Json = {"type": type(exc).__name__, "message": message}
    if isinstance(exc, DocumentInvalidError):
        out["issues"] = [i.to_dict() for i in exc.issues]
    return out


# =============================================================================================
# adapters: engine function <- recorded JSON input -> recorded JSON output
# =============================================================================================


@cache
def registry() -> ComponentRegistry:
    """The default registry without plugins: the ``registry`` argument of every entry."""
    return create_default_registry(include_plugins=False)


Adapter = Callable[[Json], Any]
ADAPTERS: dict[str, Adapter] = {}


def adapter(fn: str) -> Callable[[Adapter], Adapter]:
    def register(f: Adapter) -> Adapter:
        ADAPTERS[fn] = f
        return f

    return register


def call(fn: str, inputs: Json) -> Any:
    """``fn`` on a private copy of ``inputs``, its result as recorded JSON."""
    return to_json(ADAPTERS[fn](json.loads(canonical(inputs))))


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


# -- documents ---------------------------------------------------------------------------------


@adapter("archsim_engine.migrations.migrate_document")
def _migrate(i: Json) -> Any:
    doc, notes = migrations_module.migrate_document(i["raw"])
    return {"document": doc, "notes": notes}


@adapter("archsim_engine.scenarios.load_document")
def _load(i: Json) -> Any:
    return dump(scenarios_module.load_document(i["raw"]))


@adapter("archsim_engine.validation.validate_document")
def _validate(i: Json) -> Any:
    result = archsim_engine.validate_document(
        doc_of(i["doc"]), registry(), max_amplification=i["maxAmplification"]
    )
    return {**result.to_dict(), "edgeFlows": {k: sorted(v) for k, v in result.edge_flows.items()}}


@adapter("archsim_engine.scenarios.normalize_document")
def _normalize(i: Json) -> Any:
    return scenarios_module.normalize_document(doc_of(i["doc"]), registry())


@adapter("archsim_engine.pricing.pinned")
def _pinned(i: Json) -> Any:
    return pinned(i["pricing"])


@adapter("archsim_engine.sizing.auto_sizes")
def _auto_sizes(i: Json) -> Any:
    return sizes_json(sizing_module.auto_sizes(i["doc"], registry()))


@adapter("archsim_engine.sizing.apply_auto_sizes")
def _apply_auto_sizes(i: Json) -> Any:
    doc, sizes = sizing_module.apply_auto_sizes(i["doc"], registry())
    return {"document": doc, "sizes": sizes_json(sizes)}


@adapter("archsim_engine.scenarios.apply_overrides")
def _apply_overrides(i: Json) -> Any:
    overrides = [Override.model_validate(o) for o in i["overrides"]]
    return scenarios_module.apply_overrides(i["docDict"], overrides, i["scenarioName"])


@adapter("archsim_engine.scenarios.resolve_scenario_sized")
def _resolve(i: Json) -> Any:
    resolved, scenario, sizes = scenarios_module.resolve_scenario_sized(
        doc_of(i["doc"]), i["scenarioId"], registry()
    )
    return {"resolved": dump(resolved), "scenario": scenario, "sizes": sizes_json(sizes)}


@adapter("archsim_engine.scenarios.simulation_input")
def _simulation_input(i: Json) -> Any:
    return scenarios_module.simulation_input(doc_of(i["resolved"]))


# -- static analysis ---------------------------------------------------------------------------


@adapter("archsim_engine.review.review_design")
def _review(i: Json) -> Any:
    return review_module.review_design(i["doc"], registry())


@adapter("archsim_engine.review.count_by_severity")
def _count_by_severity(i: Json) -> Any:
    return review_module.count_by_severity(i["findings"])


@adapter("archsim_engine.provenance.input_provenance")
def _input_provenance(i: Json) -> Any:
    return provenance_module.input_provenance(doc_of(i["doc"]), registry())


@adapter("archsim_engine.provenance.provenance_counts")
def _provenance_counts(i: Json) -> Any:
    return provenance_module.provenance_counts(i["inputs"])


@adapter("archsim_engine.registry.ComponentRegistry.technology_tiers")
def _technology_tiers(i: Json) -> Any:
    return registry().technology_tiers(i["nodes"])


@adapter("archsim_engine.load.peak_rps")
def _peak_rps(i: Json) -> Any:
    return load_module.peak_rps(i["workload"])


@adapter("archsim_engine.load.base_rps")
def _base_rps(i: Json) -> Any:
    return load_module.base_rps(i["workload"])


@adapter("archsim_engine.load.ttl_miss_shares")
def _ttl_miss_shares(i: Json) -> Any:
    return load_module.ttl_miss_shares(i["doc"], registry(), rate=i["rate"])


@adapter("archsim_engine.load.estimate_load")
def _estimate_load(i: Json) -> Any:
    edge_loads: dict[str, Any] = {}
    loads = load_module.estimate_load(
        i["doc"],
        registry(),
        i["ttlMissShare"],
        miss_shares=i["missShares"],
        edge_loads=edge_loads,
        rate=i["rate"],
    )
    return {"loads": loads, "edgeLoads": edge_loads}


@adapter("archsim_engine.estimate.estimate_design")
def _estimate_design(i: Json) -> Any:
    return estimate_module.estimate_design(doc_of(i["resolved"]), registry(), i["snapshot"])


@adapter("archsim_engine.sizing.sizing_summary")
def _sizing_summary(i: Json) -> Any:
    return sizing_module.sizing_summary(
        doc_of(i["document"]),
        doc_of(i["resolved"]),
        sizes_of(i["sizes"]),
        registry(),
        i["estimate"],
    )


@adapter("archsim_engine.sizing.base_busy")
def _base_busy(i: Json) -> Any:
    return sizing_module.base_busy(sizes_of(i["sizes"]))


@adapter("archsim_engine.cost.estimate_cost")
def _estimate_cost(i: Json) -> Any:
    return cost_module.estimate_cost(
        i["nodes"],
        registry(),
        i["report"],
        i["pricing"],
        i["provenance"],
        i["baseBusy"],
        document=i["document"],
        estimate=i["estimate"],
    )


@adapter("archsim_engine.confidence.analysis_confidence")
def _analysis_confidence(i: Json) -> Any:
    return confidence_module.analysis_confidence(
        i["estimate"], i["inputs"], i["technologies"], registry(), i["architecture"]
    )


@adapter("archsim_engine.engine.quick_check")
def _quick_check(i: Json) -> Any:
    return engine_module.quick_check(Workload.model_validate(i["workload"]))


@adapter("archsim_engine.analyze.analyze_document")
def _analyze_document(i: Json) -> Any:
    return archsim_engine.analyze_document(i["document"], registry(), i["scenarioId"])


@adapter("archsim_engine.sweep.sweep_range")
def _sweep_range(i: Json) -> Any:
    return sweep_module.sweep_range(
        i["document"], registry(), i["scenarioId"], duration_s=i["durationS"]
    )


@adapter("archsim_engine.sweep.levels")
def _levels(i: Json) -> Any:
    return sweep_module.levels(i["fromRps"], i["toRps"], i["steps"])


# -- exports and imports -----------------------------------------------------------------------


@adapter("archsim_engine.export._auto_sized")
def _export_auto_sized(i: Json) -> Any:
    doc, needs = export_auto_sized(i["document"], registry())
    return {"document": doc, "needs": needs}


@adapter("archsim_engine.export.compose.to_docker_compose")
def _to_compose(i: Json) -> Any:
    return to_docker_compose(i["doc"], registry())


@adapter("archsim_engine.export.k8s.to_kubernetes")
def _to_k8s(i: Json) -> Any:
    return to_kubernetes(i["doc"], registry(), i["needs"])


@adapter("archsim_engine.export.mermaid.to_mermaid")
def _to_mermaid(i: Json) -> Any:
    return to_mermaid(i["doc"], registry())


@adapter("archsim_engine.export.export_document")
def _export(i: Json) -> Any:
    return export_document(i["document"], i["fmt"], registry(), i["autoSize"])


@adapter("archsim_engine.importers.import_design")
def _import(i: Json) -> Any:
    doc, report = import_design(i["fmt"], i["text"], registry(), i["name"])
    return {"document": doc, "report": report.to_dict()}


# -- functions of a simulation report ----------------------------------------------------------


@adapter("archsim_engine.analysis.analyze")
def _analysis(i: Json) -> Any:
    return analysis_module.analyze(i["report"], doc_of(i["resolved"]))


@adapter("archsim_engine.confidence.report_confidence")
def _report_confidence(i: Json) -> Any:
    return confidence_module.report_confidence(i["report"], i["inputs"], i["linkMs"], registry())


@adapter("archsim_engine.explain.generic")
def _generic(i: Json) -> Any:
    return explain_module.generic(i["node"], i["windowS"], i["poolBased"])


@adapter("archsim_engine.engine.stationary")
def _stationary(i: Json) -> Any:
    return engine_module.stationary(i["report"])


@adapter("archsim_engine.report.canonical_digest")
def _canonical_digest(i: Json) -> Any:
    return report_module.canonical_digest(i["obj"])


@adapter("archsim_engine.slo.slo_checks")
def _slo_checks(i: Json) -> Any:
    return slo_module.slo_checks(i["objectives"], i["summary"])


@adapter("archsim_engine.slo.evaluate")
def _slo_evaluate(i: Json) -> Any:
    return slo_module.evaluate(i["objectives"], i["summary"])


@adapter("archsim_engine.slo.verdict_text")
def _verdict_text(i: Json) -> Any:
    return slo_module.verdict_text(i["result"])


@adapter("archsim_engine.sweep.row_from")
def _row_from(i: Json) -> Any:
    return sweep_module.row_from(i["rps"], i["report"])


@adapter("archsim_engine.sweep.meets")
def _meets(i: Json) -> Any:
    return sweep_module.meets(i["row"], i["objectives"])


@adapter("archsim_engine.sweep.analyze")
def _sweep_analyze(i: Json) -> Any:
    return sweep_module.analyze(i["rows"], i["objectives"])


@adapter("archsim_engine.compare.compare_reports")
def _compare(i: Json) -> Any:
    return compare_module.compare_reports(i["a"], i["b"], i["labelA"], i["labelB"])


@adapter("archsim_engine.compare.input_diff")
def _input_diff(i: Json) -> Any:
    return compare_module.input_diff(i["a"], i["b"])


@adapter("archsim_engine.seeds.summarize_seeds")
def _summarize_seeds(i: Json) -> Any:
    return seeds_module.summarize_seeds(i["runs"], i["objectives"])


@adapter("archsim_engine.check.measure")
def _measure(i: Json) -> Any:
    with injected_simulations([i["simulation"]]):
        return check_module.measure(i["document"], registry(), i["scenarioId"])


def _thresholds(data: Json) -> check_module.Thresholds:
    return check_module.Thresholds(**data)


@adapter("archsim_engine.check.regressions")
def _regressions(i: Json) -> Any:
    return check_module.regressions(i["current"], i["baseline"], _thresholds(i["thresholds"]))


@adapter("archsim_engine.check.run_check")
def _run_check(i: Json) -> Any:
    measured = [i["current"], i["baseline"]]

    def measure(*_args: Any, **_kwargs: Any) -> Json:
        return copy.deepcopy(measured.pop(0))

    with mock.patch.object(check_module, "measure", measure):
        return check_module.run_check(
            {},
            registry(),
            baseline={},
            thresholds=_thresholds(i["thresholds"]),
            fail_on=tuple(i["failOn"]),
        )


@adapter("archsim_engine.check.headline")
def _headline(i: Json) -> Any:
    return check_module.headline(i["result"])


@adapter("archsim_engine.check.to_markdown")
def _to_markdown(i: Json) -> Any:
    return check_module.to_markdown(i["result"], i["reportUrl"])


# -- catalogs ----------------------------------------------------------------------------------


@adapter("archsim_engine.registry.ComponentRegistry.catalog_document")
def _catalog_document(_: Json) -> Any:
    return registry().catalog_document()


@adapter("archsim_engine.registry.ComponentRegistry.types")
def _types(_: Json) -> Any:
    return registry().types()


@adapter("archsim_engine.document_json_schema")
def _json_schema(_: Json) -> Any:
    return archsim_engine.document_json_schema()


def snapshot_json(snap: Snapshot) -> Json:
    """A price snapshot in full: ``Snapshot.to_json()`` plus its instance types and rates."""
    return {
        **snap.to_json(),
        "skus": {category: [to_json(s) for s in items] for category, items in snap.skus.items()},
        "rates": dict(snap.rates),
    }


def snapshot_of(data: Json) -> Snapshot:
    return _snapshot(canonical(data))


@cache
def _snapshot(text: bytes) -> Snapshot:
    data = json.loads(text)
    return Snapshot(
        provider=data["provider"],
        region=data["region"],
        snapshot_date=data["snapshotDate"],
        sources=tuple((s["name"], s["url"]) for s in data["sources"]),
        currency=data["currency"],
        illustrative=data["illustrative"],
        note=data["note"],
        skus={c: tuple(from_json(Sku, s) for s in items) for c, items in data["skus"].items()},
        rates=data["rates"],
        sha256=data["sha256"],
    )


@adapter("archsim_engine.pricing.parse_snapshot")
def _parse_snapshot(i: Json) -> Any:
    return snapshot_json(parse_snapshot(i["data"]))


@adapter("archsim_engine.pricing.content_sha256")
def _content_sha256(i: Json) -> Any:
    return content_sha256(i["data"])


@adapter("archsim_engine.pricing.available")
def _available(i: Json) -> Any:
    return available(every_date=i["everyDate"])


@adapter("archsim_engine.pricing.default_pricing")
def _default_pricing(_: Json) -> Any:
    return default_pricing()


@adapter("archsim_engine.pricing.load_snapshot")
def _load_snapshot(i: Json) -> Any:
    return load_snapshot(i["provider"], i["region"], i["date"], i["sha256"]).to_json()


@adapter("archsim_engine.pricing.snapshot_for")
def _snapshot_for(i: Json) -> Any:
    return snapshot_for(i["pricing"]).to_json()


@adapter("archsim_engine.pricing.unknown_families")
def _unknown_families(i: Json) -> Any:
    return unknown_families(snapshot_of(i["snapshot"]), i["families"])


@adapter("archsim_engine.pricing.Snapshot.cover")
def _cover(i: Json) -> Any:
    busy = Busy(i["busy"]["peak"], i["busy"]["base"]) if i["busy"] else None
    return snapshot_of(i["snapshot"]).cover(
        i["category"],
        i["cpu"],
        i["memoryGib"],
        i["option"],
        i["engine"],
        i["families"],
        busy,
        i["burstable"],
    )


@adapter("archsim_engine.pricing.machine_kind")
def _machine_kind(i: Json) -> Any:
    return machine_kind(i["nodeType"], i["cfg"])


@adapter("archsim_engine.assumptions.constants")
def _constants(_: Json) -> Any:
    return assumption_constants()


@adapter("archsim_engine.assumptions.render")
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


def _constant(get: Callable[[], Any]) -> Adapter:
    return lambda _: get()


for _name, _get in CONSTANTS.items():
    ADAPTERS[_name] = _constant(_get)


# -- formatting and numeric helpers ------------------------------------------------------------


@adapter("archsim_engine.explain.num")
def _num(i: Json) -> Any:
    return explain_module.num(number(i["v"]), i["digits"])


@adapter("archsim_engine.explain.pct")
def _pct(i: Json) -> Any:
    return explain_module.pct(number(i["v"]))


@adapter("archsim_engine.explain.erlang_c")
def _erlang_c(i: Json) -> Any:
    return explain_module.erlang_c(i["c"], i["offered"])


@adapter("archsim_engine.explain.zipf_share")
def _zipf_share(i: Json) -> Any:
    return explain_module.zipf_share(i["n"], i["s"], i["top"])


@adapter("archsim_engine.load.js_num")
def _js_num(i: Json) -> Any:
    return load_module.js_num(number(i["x"]))


@adapter("archsim_engine.load.fmt_int")
def _fmt_int(i: Json) -> Any:
    return load_module.fmt_int(number(i["x"]))


@adapter("archsim_engine.load.fixed")
def _fixed(i: Json) -> Any:
    return load_module.fixed(number(i["x"]), i["digits"])


@adapter("archsim_engine.load.round_half_up")
def _round_half_up(i: Json) -> Any:
    return load_module.round_half_up(number(i["x"]), i["digits"])


@adapter("archsim_engine.load.mean_ms")
def _mean_ms(i: Json) -> Any:
    return load_module.mean_ms(i["d"])


@adapter("archsim_engine.load.ttl_hit_ratio")
def _ttl_hit_ratio(i: Json) -> Any:
    return load_module.ttl_hit_ratio(
        i["readRps"], i["writeRps"], i["keys"], i["skew"], i["ttlS"], i["capacityKeys"]
    )


@adapter("archsim_engine.sizing.fmt")
def _sizing_fmt(i: Json) -> Any:
    return sizing_module.fmt(number(i["v"]))


@adapter("archsim_engine.provenance.describe_value")
def _describe_value(i: Json) -> Any:
    return provenance_module.describe_value(i["v"], i["unit"])


@adapter("archsim_engine.seeds.fmt_ms")
def _seeds_fmt_ms(i: Json) -> Any:
    return seeds_module.fmt_ms(number(i["v"]))


@adapter("archsim_engine.seeds.value_range")
def _value_range(i: Json) -> Any:
    return seeds_module.value_range(i["values"])


@adapter("archsim_engine.seeds.range_text")
def _range_text(i: Json) -> Any:
    return seeds_module.range_text(i["label"], i["r"])


@adapter("archsim_engine.seeds.seeds_text")
def _seeds_text(i: Json) -> Any:
    return seeds_module.seeds_text(i["seeds"])


@adapter("archsim_engine.seeds.cap_confidence")
def _cap_confidence(i: Json) -> Any:
    return seeds_module.cap_confidence(i["level"], i["cap"])


def distribution(data: Json) -> Any:
    from pydantic import TypeAdapter

    from archsim_engine.distributions import Distribution

    return TypeAdapter(Distribution).validate_python(data)


@adapter("archsim_engine.distributions.Distribution.expected")
def _expected(i: Json) -> Any:
    return distribution(i["d"]).expected()


@adapter("archsim_engine.distributions.Distribution.describe")
def _describe(i: Json) -> Any:
    return distribution(i["d"]).describe()


@adapter("archsim_engine.distributions.Empirical.quantile")
def _empirical_quantile(i: Json) -> Any:
    d = distribution(i["d"])
    assert isinstance(d, Empirical)
    return d.quantile(i["q"])


@adapter("archsim_engine.estimate.mmc_wait")
def _mmc_wait(i: Json) -> Any:
    return estimate_module.mmc_wait(i["slots"], i["lam"], i["holdMs"])


@adapter("archsim_engine.sweep.suggest_range")
def _suggest_range(i: Json) -> Any:
    return sweep_module.suggest_range(i["currentRps"], i["cap"])


@adapter("archsim_engine.sweep.rps_cap")
def _rps_cap(i: Json) -> Any:
    return sweep_module.rps_cap(None, i["durationS"])


# -- calibration -------------------------------------------------------------------------------


@adapter("archsim_engine.calibration.mapping.load_mapping")
def _load_mapping(i: Json) -> Any:
    return calibration_mapping.load_mapping(i["source"])


@adapter("archsim_engine.calibration.otlp.load_otlp_mapping")
def _load_otlp_mapping(i: Json) -> Any:
    return calibration_otlp.load_otlp_mapping(i["source"])


@adapter("archsim_engine.calibration.apply.parse_range")
def _parse_range(i: Json) -> Any:
    return calibration_apply.parse_range(i["text"], i["step"])


@adapter("archsim_engine.calibration.apply.calibrate_prometheus")
def _calibrate_prometheus(i: Json) -> Any:
    mapping = calibration_mapping.load_mapping(i["mapping"])
    client = calibration_prometheus.RecordedPrometheus.from_dict(i["recorded"])
    step = i["step"] or (mapping.range.step if mapping.range else 60)
    rng = calibration_apply.parse_range(i["range"], step) if i["range"] else None
    with fixed_clock(), injected_simulations(i["simulations"]):
        outcome = calibration_apply.calibrate_prometheus(
            i["document"], mapping, client, registry(), rng, i["replay"]
        )
    return outcome.to_dict()


@adapter("archsim_engine.calibration.otlp.calibrate_otlp")
def _calibrate_otlp(i: Json) -> Any:
    with fixed_clock(), injected_simulations(i["simulations"]):
        outcome = calibration_otlp.calibrate_otlp(
            i["document"], i["traces"], registry(), i["mapping"], i["minSamples"]
        )
    return outcome.to_dict()


@adapter("archsim_engine.calibration.apply.replay_summary")
def _replay_summary(i: Json) -> Any:
    return calibration_apply.replay_summary(i["record"])


@adapter("archsim_engine.calibration.otlp.parse_otlp")
def _parse_otlp(i: Json) -> Any:
    return calibration_otlp.parse_otlp(i["data"])


@adapter("archsim_engine.calibration.otlp.summarize")
def _summarize(i: Json) -> Any:
    spans = [from_json(calibration_otlp.Span, s) for s in i["spans"]]
    cpu = tuple(i["cpuTime"]) if i["cpuTime"] is not None else None
    return calibration_otlp.summarize(spans, cpu)


@adapter("archsim_engine.calibration.prometheus.histogram")
def _histogram(i: Json) -> Any:
    return calibration_prometheus.histogram(i["result"], i["unit"])


@adapter("archsim_engine.calibration.prometheus.reduce")
def _reduce(i: Json) -> Any:
    return calibration_prometheus.reduce(i["result"], i["how"])


@adapter("archsim_engine.calibration.fit.fit_histogram")
def _fit_histogram(i: Json) -> Any:
    h = from_json(calibration_fit.Histogram, i["h"])
    return calibration_fit.fit_histogram(h, i["kind"])


@adapter("archsim_engine.calibration.fit.fit_samples")
def _fit_samples(i: Json) -> Any:
    return calibration_fit.fit_samples(i["samples"], i["kind"])


def _pairs(points: list[list[float]]) -> list[tuple[float, float]]:
    return [(float(a), float(b)) for a, b in points]


@adapter("archsim_engine.calibration.resources.fit_cpu_share")
def _fit_cpu_share(i: Json) -> Any:
    return calibration_resources.fit_cpu_share(_pairs(i["points"]), i["serviceMs"], i["replicas"])


@adapter("archsim_engine.calibration.resources.fit_load_curve")
def _fit_load_curve(i: Json) -> Any:
    return calibration_resources.fit_load_curve(_pairs(i["points"]), i["referenceMs"], i["maxBins"])


@adapter("archsim_engine.calibration.resources.fit_memory")
def _fit_memory(i: Json) -> Any:
    return calibration_resources.fit_memory(i["workingSetMb"], i["rate"], i["held"])


@adapter("archsim_engine.calibration.resources.extend_load_curve")
def _extend_load_curve(i: Json) -> Any:
    ext = calibration_resources.extend_load_curve(
        _pairs(i["passes"]), i["cores"], i["minBusy"], i["baseBusy"], i["t"]
    )
    return None if ext is None else {**to_json(ext), "factors": ext.factors()}


# =============================================================================================
# a golden file under construction
# =============================================================================================


class Golden:
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


# =============================================================================================
# templates
# =============================================================================================


def template_ids() -> list[str]:
    return sorted(p.stem for p in EXAMPLES.glob("*.json"))


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def case_entries(g: Golden, where: tuple[str, ...], raw: Json, doc: Json, sid: str | None) -> None:
    """Every static-analysis function of one case (the base design or a scenario), in the order
    ``analyze_document`` composes them."""
    resolved_out = g.entry(
        where,
        "resolve_scenario_sized",
        "archsim_engine.scenarios.resolve_scenario_sized",
        {"doc": doc, "scenarioId": sid},
    )
    resolved, sizes = resolved_out["resolved"], resolved_out["sizes"]
    g.entry(
        where,
        "validate_document",
        "archsim_engine.validation.validate_document",
        {"doc": resolved, "maxAmplification": DEFAULT_MAX_AMPLIFICATION},
    )
    snap = g.entry(
        where,
        "simulation_input",
        "archsim_engine.scenarios.simulation_input",
        {"resolved": resolved},
    )
    review = g.entry(where, "review_design", "archsim_engine.review.review_design", {"doc": snap})
    counts = g.entry(
        where, "count_by_severity", "archsim_engine.review.count_by_severity", {"findings": review}
    )
    inputs = g.entry(
        where, "input_provenance", "archsim_engine.provenance.input_provenance", {"doc": resolved}
    )
    provenance_counts = g.entry(
        where,
        "provenance_counts",
        "archsim_engine.provenance.provenance_counts",
        {"inputs": inputs},
    )
    technologies = g.entry(
        where,
        "technology_tiers",
        "archsim_engine.registry.ComponentRegistry.technology_tiers",
        {"nodes": snap["architecture"]["nodes"]},
    )
    peak = g.entry(
        where, "peak_rps", "archsim_engine.load.peak_rps", {"workload": snap["workload"]}
    )
    g.entry(where, "base_rps", "archsim_engine.load.base_rps", {"workload": snap["workload"]})
    miss = g.entry(
        where, "ttl_miss_shares", "archsim_engine.load.ttl_miss_shares", {"doc": snap, "rate": None}
    )
    g.entry(
        where,
        "estimate_load",
        "archsim_engine.load.estimate_load",
        {"doc": snap, "ttlMissShare": None, "missShares": miss, "rate": None},
    )
    estimate = g.entry(
        where,
        "estimate_design",
        "archsim_engine.estimate.estimate_design",
        {"resolved": resolved, "snapshot": snap},
    )
    sizing = g.entry(
        where,
        "sizing_summary",
        "archsim_engine.sizing.sizing_summary",
        {"document": doc, "resolved": resolved, "sizes": sizes, "estimate": estimate},
    )
    busy = g.entry(where, "base_busy", "archsim_engine.sizing.base_busy", {"sizes": sizes})
    cost = g.entry(
        where,
        "estimate_cost",
        "archsim_engine.cost.estimate_cost",
        {
            "nodes": snap["architecture"]["nodes"],
            "report": None,
            "pricing": snap.get("pricing"),
            "provenance": {k: v["kind"] for k, v in resolved["provenance"].items()},
            "baseBusy": busy,
            "document": snap,
            "estimate": estimate,
        },
    )
    confidence = g.entry(
        where,
        "analysis_confidence",
        "archsim_engine.confidence.analysis_confidence",
        {
            "estimate": estimate,
            "inputs": inputs,
            "technologies": technologies,
            "architecture": snap["architecture"],
        },
    )
    quick = g.entry(
        where,
        "quick_check",
        "archsim_engine.engine.quick_check",
        {"workload": resolved["workload"]},
    )
    full = g.entry(
        where,
        "analyze_document",
        "archsim_engine.analyze.analyze_document",
        {"document": raw, "scenarioId": sid},
    )
    for key, part in (
        ("peakRps", peak),
        ("sizing", sizing),
        ("cost", cost),
        ("review", review),
        ("reviewCounts", counts),
        ("technologies", technologies),
        ("inputs", inputs),
        ("provenanceCounts", provenance_counts),
        ("estimate", estimate),
        ("confidence", confidence),
        ("quickCheck", quick),
    ):
        same(f"{pointer(where)} analyze_document.{key}", part, full[key])
    lo_hi = g.entry(
        where,
        "sweep_range",
        "archsim_engine.sweep.sweep_range",
        {"document": raw, "scenarioId": sid, "durationS": sweep_module.STEP_DURATION_S},
    )
    g.entry(
        where,
        "levels",
        "archsim_engine.sweep.levels",
        {"fromRps": lo_hi[0], "toRps": lo_hi[1], "steps": check_module.KNEE_STEPS},
    )


def template_file(tid: str) -> Golden:
    raw = read_json(EXAMPLES / f"{tid}.json")
    scenarios = raw.get("scenarios") or []
    g = Golden("template", template=tid)
    g.place(("document",), raw)
    top: tuple[str, ...] = ()
    g.entry(top, "migrate_document", "archsim_engine.migrations.migrate_document", {"raw": raw})
    doc = g.entry(top, "load_document", "archsim_engine.scenarios.load_document", {"raw": raw})
    g.entry(
        top,
        "validate_document",
        "archsim_engine.validation.validate_document",
        {"doc": doc, "maxAmplification": DEFAULT_MAX_AMPLIFICATION},
    )
    normalized = g.entry(
        top, "normalize_document", "archsim_engine.scenarios.normalize_document", {"doc": doc}
    )
    priced = {
        **normalized,
        "pricing": g.entry(
            top, "pinned", "archsim_engine.pricing.pinned", {"pricing": normalized.get("pricing")}
        ),
    }
    g.entry(top, "auto_sizes", "archsim_engine.sizing.auto_sizes", {"doc": priced})
    sized = g.entry(
        top, "apply_auto_sizes", "archsim_engine.sizing.apply_auto_sizes", {"doc": priced}
    )

    # exports, and importing the exports back
    auto = g.entry(top, "_auto_sized", "archsim_engine.export._auto_sized", {"document": doc})
    texts = {
        "compose": g.entry(
            top,
            "to_docker_compose",
            "archsim_engine.export.compose.to_docker_compose",
            {"doc": auto["document"]},
        ),
        "k8s": g.entry(
            top,
            "to_kubernetes",
            "archsim_engine.export.k8s.to_kubernetes",
            {"doc": auto["document"], "needs": auto["needs"]},
        ),
        "mermaid": g.entry(
            top, "to_mermaid", "archsim_engine.export.mermaid.to_mermaid", {"doc": auto["document"]}
        ),
    }
    for fmt in FORMATS:
        text = g.entry(
            top,
            f"export_document[{fmt}]",
            "archsim_engine.export.export_document",
            {"document": raw, "fmt": fmt, "autoSize": True},
        )
        same(f"{tid} export_document[{fmt}]", text, texts[fmt])
    for fmt in ("compose", "k8s"):
        g.entry(
            top,
            f"import_design[{fmt}]",
            "archsim_engine.importers.import_design",
            {"fmt": fmt, "text": texts[fmt], "name": None},
        )

    # every case: the base design and each scenario
    ids = [s["id"] for s in scenarios]
    if "base" in ids or len(set(ids)) != len(ids):  # runs/base is the base design's run
        raise ValueError(f"{tid}: scenario ids must be unique and not 'base'")
    case_entries(g, ("base",), raw, doc, None)
    g.place(("base", "scenarioId"), None)
    for s in scenarios:
        where = ("scenarios", s["id"])
        g.place((*where, "scenarioId"), s["id"])
        g.entry(
            where,
            "apply_overrides",
            "archsim_engine.scenarios.apply_overrides",
            {
                "docDict": sized["document"],
                "overrides": s["overrides"],
                "scenarioName": f"Scenario '{s['name']}'",
            },
        )
        case_entries(g, where, raw, doc, s["id"])
    g.cases = 1 + len(scenarios)

    runs_and_comparison(g, raw)
    return g


# -- recorded runs -----------------------------------------------------------------------------


def second_run(raw: Json) -> Json | None:
    """The scenario recorded beside the base design: the first one with faults, else the first
    that changes the traffic pattern, else the last."""
    scenarios = raw.get("scenarios") or []
    for path in ("faults", "pattern"):
        for s in scenarios:
            if any(o["target"]["kind"] == "workload" and o["path"] == path for o in s["overrides"]):
                return s
    return scenarios[-1] if scenarios else None


def _scale_pattern(pattern: Json, f: float) -> Json:
    p = dict(pattern)
    if p.get("kind") == "burst":
        p["startS"] = round(p["startS"] * f, 3)
        p["durationS"] = round(p["durationS"] * f, 3)
    elif p.get("kind") == "trace":
        p["points"] = [[round(t * f, 3), r] for t, r in p["points"]]
    return p


def _scale_faults(faults: list[Json], f: float) -> list[Json]:
    return [
        {**x, "startS": round(x["startS"] * f, 3), "durationS": round(x["durationS"] * f, 3)}
        for x in faults
    ]


def _scale_workload_value(path: str, value: Any, f: float) -> Any:
    if path in ("durationS", "warmupS"):
        return round(value * f, 3)
    if path == "pattern":
        return _scale_pattern(value, f)
    if path == "faults":
        return _scale_faults(value, f)
    if path.startswith(("pattern.", "faults")):
        raise ValueError(f"cannot compress the time of an override of workload.{path}")
    return value


def compressed(raw: Json, sid: str | None) -> tuple[Json, float]:
    """The template (with only scenario ``sid``) on a time axis compressed by a factor ``f`` so
    the run offers about RUN_TARGET_REQUESTS requests (at least RUN_MIN_S long): its duration,
    warm-up, burst and faults all scale by ``f``, so they keep their places in the run."""
    resolved, _ = scenarios_module.resolve_scenario(
        scenarios_module.load_document(raw), sid, registry()
    )
    wl = resolved.workload
    mean_rps = wl.expected_arrivals() / wl.duration_s
    target = max(RUN_MIN_S, RUN_TARGET_REQUESTS / mean_rps) if mean_rps > 0 else wl.duration_s
    f = min(wl.duration_s, round(target, 1)) / wl.duration_s
    doc = copy.deepcopy(raw)
    doc["scenarios"] = [s for s in doc.get("scenarios") or [] if s["id"] == sid]
    w = doc["workload"]
    for key in ("durationS", "warmupS"):
        if key in w:
            w[key] = round(w[key] * f, 3)
    if "pattern" in w:
        w["pattern"] = _scale_pattern(w["pattern"], f)
    if "faults" in w:
        w["faults"] = _scale_faults(w["faults"], f)
    for s in doc["scenarios"]:
        for o in s["overrides"]:
            if o["target"]["kind"] == "workload":
                o["value"] = _scale_workload_value(o["path"], o["value"], f)
    return doc, f


@contextmanager
def spying(module: Any, name: str) -> Iterator[list[tuple[tuple[Any, ...], Json, Any]]]:
    """Calls of ``module.name`` while inside: (args, kwargs, result) each."""
    calls: list[tuple[tuple[Any, ...], Json, Any]] = []
    real = getattr(module, name)

    def wrapper(*args: Any, **kwargs: Any) -> Any:
        result = real(*args, **kwargs)
        calls.append((args, kwargs, result))
        return result

    with mock.patch.object(module, name, wrapper):
        yield calls


def simulate(doc: Json, sid: str | None) -> tuple[Json, dict[str, bool]]:
    """A report of ``doc`` (seeded, so the same every time on one platform) with its
    machine-dependent provenance blanked, and whether each node's explain formulas were
    pool-based (an argument of ``explain.generic`` the report does not show)."""
    with spying(report_module, "generic") as calls:
        report = archsim_engine.run_simulation(doc, scenario_id=sid, registry=registry())
    normalize_report(report)
    return report, {args[0]["id"]: bool(args[2]) for args, _, _ in calls}


def normalize_report(report: Json) -> None:
    """Blank what depends on the machine, not the model: the wall time and Python version."""
    report["provenance"]["wallSeconds"] = 0.0
    report["provenance"]["python"] = "normalized"


def run_case(g: Golden, key: str, raw: Json, sid: str | None) -> tuple[Json, Json, Json]:
    """One recorded run: its compressed document, its report, and every function of a report
    on it. Returns (document, report, resolved document)."""
    doc, f = compressed(raw, sid)
    where = ("runs", key)
    report, pool_based = simulate(doc, sid)
    g.place((*where, "scenarioId"), sid)
    g.place((*where, "timeScale"), f)
    g.place((*where, "document"), doc)
    g.place((*where, "report"), report)
    loaded = g.entry(where, "load_document", "archsim_engine.scenarios.load_document", {"raw": doc})
    out = g.entry(
        where,
        "resolve_scenario_sized",
        "archsim_engine.scenarios.resolve_scenario_sized",
        {"doc": loaded, "scenarioId": sid},
    )
    resolved, sizes = out["resolved"], out["sizes"]
    same(
        f"{pointer(where)} report.input",
        report["input"],
        call("archsim_engine.scenarios.simulation_input", {"resolved": resolved}),
    )
    findings = g.entry(
        where,
        "analyze",
        "archsim_engine.analysis.analyze",
        {"report": report, "resolved": resolved},
    )
    same(f"{pointer(where)} findings", findings, report["findings"])
    resolved_doc = doc_of(resolved)
    link_ms = {e.id: e.config.latency_ms.expected() for e in resolved_doc.architecture.edges}
    confidence = g.entry(
        where,
        "report_confidence",
        "archsim_engine.confidence.report_confidence",
        {"report": report, "inputs": report["assumptions"]["userInputs"], "linkMs": link_ms},
    )
    same(f"{pointer(where)} confidence", confidence, report["confidence"])
    busy = g.entry(where, "base_busy", "archsim_engine.sizing.base_busy", {"sizes": sizes})
    snap = report["input"]
    cost = g.entry(
        where,
        "estimate_cost",
        "archsim_engine.cost.estimate_cost",
        {
            "nodes": snap["architecture"]["nodes"],
            "report": report,
            "pricing": snap.get("pricing"),
            "provenance": {k: v["kind"] for k, v in resolved["provenance"].items()},
            "baseBusy": busy,
            "document": snap,
            "estimate": report["estimate"],
        },
    )
    same(f"{pointer(where)} cost", cost, report["cost"])
    window_s = report["window"]["measuredS"]
    for node in report["nodes"]:
        if node["id"] not in pool_based:
            continue
        formulas = g.entry(
            where,
            f"generic[{node['id']}]",
            "archsim_engine.explain.generic",
            {"node": node, "windowS": window_s, "poolBased": pool_based[node["id"]]},
        )
        same(f"{pointer(where)} explain {node['id']}", formulas, node["explain"][: len(formulas)])
    g.entry(where, "stationary", "archsim_engine.engine.stationary", {"report": report})
    digest = g.entry(
        where,
        "canonical_digest[input]",
        "archsim_engine.report.canonical_digest",
        {"obj": report["input"]},
    )
    same(f"{pointer(where)} inputDigest", digest, report["provenance"]["inputDigest"])
    digest = g.entry(
        where,
        "canonical_digest[result]",
        "archsim_engine.report.canonical_digest",
        {"obj": {k: report[k] for k in ("summary", "timeseries", "nodes", "edges", "window")}},
    )
    same(f"{pointer(where)} resultDigest", digest, report["provenance"]["resultDigest"])
    objectives = resolved["objectives"]
    summary = report["summary"]
    g.entry(
        where,
        "slo_checks",
        "archsim_engine.slo.slo_checks",
        {"objectives": objectives, "summary": summary},
    )
    result = g.entry(
        where,
        "evaluate",
        "archsim_engine.slo.evaluate",
        {"objectives": objectives, "summary": summary},
    )
    g.entry(where, "verdict_text", "archsim_engine.slo.verdict_text", {"result": result})
    row = g.entry(
        where,
        "row_from",
        "archsim_engine.sweep.row_from",
        {"rps": resolved["workload"]["rps"], "report": report},
    )
    g.entry(where, "meets", "archsim_engine.sweep.meets", {"row": row, "objectives": objectives})
    g.entry(
        where,
        "measure",
        "archsim_engine.check.measure",
        {"document": doc, "scenarioId": sid, "simulation": report},
    )
    return doc, report, resolved


def runs_and_comparison(g: Golden, raw: Json) -> None:
    """The base design's run and one scenario's, and the functions that compare two runs."""
    _, base, base_resolved = run_case(g, "base", raw, None)
    other = second_run(raw)
    if other is None:
        return
    _, report, resolved = run_case(g, other["id"], raw, other["id"])
    where = ("comparison",)
    g.place((*where, "a"), "base")
    g.place((*where, "b"), other["id"])
    g.entry(
        where,
        "compare_reports",
        "archsim_engine.compare.compare_reports",
        {"a": base, "b": report, "labelA": "Base design", "labelB": other["name"]},
    )
    g.entry(
        where,
        "input_diff",
        "archsim_engine.compare.input_diff",
        {"a": base["input"], "b": report["input"]},
    )
    objectives = base_resolved["objectives"]
    seed = base["provenance"]["seed"]
    g.entry(
        where,
        "summarize_seeds",
        "archsim_engine.seeds.summarize_seeds",
        {
            "runs": [
                {"seed": seed, "summary": base["summary"]},
                {"seed": seed + 1, "summary": report["summary"]},
                {"seed": seed + 2, "summary": None},
            ],
            "objectives": objectives,
        },
    )
    rows = [
        call(
            "archsim_engine.sweep.row_from",
            {"rps": r["workload"]["rps"], "report": rep},
        )
        for r, rep in ((base_resolved, base), (resolved, report))
    ]
    g.entry(
        where, "analyze", "archsim_engine.sweep.analyze", {"rows": rows, "objectives": objectives}
    )
    thresholds = dataclasses.asdict(check_module.Thresholds())
    current = g.root["runs"][other["id"]]["entries"]["measure"]["output"]
    baseline = g.root["runs"]["base"]["entries"]["measure"]["output"]
    g.entry(
        where,
        "regressions",
        "archsim_engine.check.regressions",
        {"current": current, "baseline": baseline, "thresholds": thresholds},
    )
    result = g.entry(
        where,
        "run_check",
        "archsim_engine.check.run_check",
        {
            "current": current,
            "baseline": baseline,
            "thresholds": thresholds,
            "failOn": ["objectives", "regression"],
        },
    )
    g.entry(where, "headline", "archsim_engine.check.headline", {"result": result})
    for name, url in (("to_markdown", None), ("to_markdown[url]", "https://example.com/runs/1")):
        g.entry(
            where,
            name,
            "archsim_engine.check.to_markdown",
            {"result": result, "reportUrl": url},
        )


# =============================================================================================
# catalogs
# =============================================================================================


def components_file() -> Golden:
    g = Golden("catalog", name="components")
    g.entry(
        (), "catalog_document", "archsim_engine.registry.ComponentRegistry.catalog_document", {}
    )
    g.entry((), "types", "archsim_engine.registry.ComponentRegistry.types", {})
    g.cases = 1
    return g


def schema_file() -> Golden:
    g = Golden("catalog", name="schema")
    g.entry((), "document_json_schema", "archsim_engine.document_json_schema", {})
    g.cases = 1
    return g


def pricing_file() -> Golden:
    g = Golden("catalog", name="pricing")
    snapshots: dict[str, Json] = {}
    for path in sorted(PRICES.glob("*.json")):
        data = read_json(path)
        g.place(("files", path.name), data)
        snapshots[path.name] = g.entry(
            (),
            f"parse_snapshot[{path.name}]",
            "archsim_engine.pricing.parse_snapshot",
            {"data": data},
        )
        g.entry(
            (),
            f"content_sha256[{path.name}]",
            "archsim_engine.pricing.content_sha256",
            {"data": data},
        )
    for every in (False, True):
        g.entry(
            (), f"available[everyDate={str(every).lower()}]", "archsim_engine.pricing.available",
            {"everyDate": every},
        )  # fmt: skip
    g.entry((), "default_pricing", "archsim_engine.pricing.default_pricing", {})
    newest_name = sorted(snapshots)[-1]
    newest = snapshots[newest_name]
    oldest = snapshots[sorted(snapshots)[0]]
    lookups: list[Json] = [
        {"provider": "generic", "region": None, "date": None, "sha256": None},
        {"provider": "aws", "region": None, "date": None, "sha256": None},
        {"provider": "aws", "region": "us-east-1", "date": None, "sha256": None},
        {"provider": "aws", "region": "us-east-1", "date": oldest["snapshotDate"], "sha256": None},
        {
            "provider": "aws",
            "region": "us-east-1",
            "date": oldest["snapshotDate"],
            "sha256": oldest["sha256"],
        },
        {"provider": "aws", "region": "us-east-1", "date": "2020-01-01", "sha256": None},
        {"provider": "aws", "region": "us-east-1", "date": None, "sha256": "0" * 64},
        {"provider": "aws", "region": "eu-west-9", "date": None, "sha256": None},
        {"provider": "gcp", "region": "us-central1", "date": None, "sha256": None},
    ]
    for n, args in enumerate(lookups):
        g.entry(
            (), f"load_snapshot[{n}]", "archsim_engine.pricing.load_snapshot", args, errors=True
        )
    pricings: list[Json | None] = [
        None,
        {"provider": "generic"},
        {"provider": "aws", "region": "us-east-1"},
        {"provider": "aws", "region": "us-east-1", "snapshotDate": oldest["snapshotDate"]},
        {"provider": "aws", "region": "us-east-1", "snapshotDate": "2020-01-01"},
        {"provider": "aws", "region": "us-east-1", "families": ["m7g", "t4g"]},
    ]
    for n, pricing in enumerate(pricings):
        g.entry((), f"pinned[{n}]", "archsim_engine.pricing.pinned", {"pricing": pricing})
        g.entry(
            (), f"snapshot_for[{n}]", "archsim_engine.pricing.snapshot_for", {"pricing": pricing},
            errors=True,
        )  # fmt: skip
    families = sorted({s["family"] for items in newest["skus"].values() for s in items})
    g.entry(
        (),
        "unknown_families",
        "archsim_engine.pricing.unknown_families",
        {"snapshot": newest, "families": [*families[:3], "zz9", "t4g", "nope"]},
    )
    sizes = [
        (None, None), (0.25, 0.5), (1, 1), (1, 4), (2, 3), (2, 16), (3, 6), (8, 64), (48, 96),
        (1000, 1), (1, 5000),
    ]  # fmt: skip
    n = 0
    for category, items in sorted(newest["skus"].items()):
        engines = sorted({e for s in items for e in s["engines"]})[:2]
        for engine in [None, *engines]:
            for cpu, mem in sizes:
                g.entry(
                    (), f"cover[{n}]", "archsim_engine.pricing.Snapshot.cover",
                    {
                        "snapshot": newest, "category": category, "cpu": cpu, "memoryGib": mem,
                        "option": "on_demand", "engine": engine, "families": [], "busy": None,
                        "burstable": "auto",
                    },
                )  # fmt: skip
                n += 1
    compute_families = sorted({s["family"] for s in newest["skus"].get("compute", [])})
    for cpu, mem in ((0.5, 1), (1, 2), (2, 4)):
        for busy in (None, {"peak": 0.1, "base": 0.05}, {"peak": 1.5, "base": 0.9}):
            for burstable in ("auto", "never"):
                for fams in ([], ["t4g"], compute_families[:1]):
                    g.entry(
                        (), f"cover[{n}]", "archsim_engine.pricing.Snapshot.cover",
                        {
                            "snapshot": newest, "category": "compute", "cpu": cpu,
                            "memoryGib": mem, "option": "on_demand", "engine": None,
                            "families": fams, "busy": busy, "burstable": burstable,
                        },
                    )  # fmt: skip
                    n += 1
    kinds: list[tuple[str, Json]] = [
        ("service", {}), ("service", {"platform": "kubernetes"}),
        ("service", {"platform": "serverless"}), ("database", {}),
        ("database", {"technology": "mysql"}), ("database", {"technology": "dynamodb"}),
        ("cache", {}), ("cache", {"technology": "memcached"}),
        ("message_queue", {"technology": "kafka"}), ("message_queue", {"technology": "sqs"}),
        ("message_queue", {"technology": "rabbitmq"}), ("load_balancer", {}),
        ("load_balancer", {"technology": "aws_alb"}), ("kubernetes_cluster", {}),
        ("traffic_source", {}), ("external_api", {}),
    ]  # fmt: skip
    for n, (node_type, cfg) in enumerate(kinds):
        g.entry(
            (), f"machine_kind[{n}]", "archsim_engine.pricing.machine_kind",
            {"nodeType": node_type, "cfg": cfg},
        )  # fmt: skip
    g.cases = len(snapshots)
    return g


def assumptions_file() -> Golden:
    g = Golden("catalog", name="assumptions")
    g.entry((), "constants", "archsim_engine.assumptions.constants", {})
    g.entry((), "render", "archsim_engine.assumptions.render", {})
    for name in CONSTANTS:
        g.entry((), name.rsplit(".", 2)[-2] + "." + name.rsplit(".", 1)[-1], name, {})
    g.cases = 1
    return g


NUMBERS: list[Any] = [
    0, 0.0001234, 0.004, 0.05, 0.125, 0.5, 0.999, 1, 1.25, 2.5, 2.675, 9.95, 9.999, 10, 12.345,
    99.4, 99.95, 100, 100.5, 999.5, 1234.5, 12345.678, 1e6, 2.5e9, 1e16, -0.5, -12.345, -1500,
]  # fmt: skip
DISTRIBUTIONS: list[Json] = [
    {"kind": "constant", "value": 0},
    {"kind": "constant", "value": 0.25},
    {"kind": "constant", "value": 5},
    {"kind": "exponential", "mean": 12.5},
    {"kind": "exponential", "mean": 0.05},
    {"kind": "uniform", "min": 1, "max": 3},
    {"kind": "lognormal", "median": 5, "p95": 20},
    {"kind": "lognormal", "median": 10, "p95": 10},
    {"kind": "lognormal", "median": 0.8, "p95": 2.25},
    {"kind": "empirical", "quantiles": [[0, 1], [0.5, 5], [0.95, 20], [1, 50]]},
    {"kind": "empirical", "quantiles": [[0, 0.123], [0.25, 1.5], [0.75, 7.25], [1, 123.456]]},
]


def formatting_file() -> Golden:
    """Helpers that turn numbers into text or small results, on grids of values: where a port
    is most likely to differ (rounding, format specifiers, thousands separators)."""
    g = Golden("catalog", name="formatting")
    special: list[Any] = [None, "Infinity", "-Infinity", "NaN"]
    n = 0

    def rec(fn: str, inputs: Json, errors: bool = False) -> None:
        nonlocal n
        g.entry((), f"{fn.rsplit('.', 1)[-1]}[{n}]", fn, inputs, errors=errors)
        n += 1

    for v in NUMBERS + special:
        for digits in (1, 2):
            rec("archsim_engine.explain.num", {"v": v, "digits": digits})
    for v in [None, 0, 0.001, 0.0125, 0.333, 0.5, 0.9995, 1, 1.5]:
        rec("archsim_engine.explain.pct", {"v": v})
    for c in (1, 2, 4, 16, 100):
        for offered in (0, 0.5, 0.9 * c, c - 0.01, c, c + 1):
            rec("archsim_engine.explain.erlang_c", {"c": c, "offered": offered})
    for keys in (1, 10, 1000, 10_000, 100_000, 1_000_000):
        for s in (0, 0.5, 0.99, 1.0, 1.2):
            for top in (1, 10):
                rec("archsim_engine.explain.zipf_share", {"n": keys, "s": s, "top": top})
    for x in NUMBERS:
        rec("archsim_engine.load.js_num", {"x": x})
        rec("archsim_engine.load.fmt_int", {"x": x})
        rec("archsim_engine.sizing.fmt", {"v": x})
        for digits in (0, 1, 2, 3):
            rec("archsim_engine.load.fixed", {"x": x, "digits": digits})
            rec("archsim_engine.load.round_half_up", {"x": x, "digits": digits})
        if x > 0:
            rec("archsim_engine.seeds.fmt_ms", {"v": x})
    for x in (0.005, 0.015, 0.025, 0.135, 1.005, 1.115, 2.5, 3.5, -2.5):
        for digits in (0, 1, 2):
            rec("archsim_engine.load.round_half_up", {"x": x, "digits": digits})
    for d in DISTRIBUTIONS:
        rec("archsim_engine.distributions.Distribution.expected", {"d": d})
        rec("archsim_engine.distributions.Distribution.describe", {"d": d})
        rec("archsim_engine.load.mean_ms", {"d": d})
        if d["kind"] == "empirical":
            for q in (0, 0.25, 0.5, 0.95, 0.99, 1):
                rec("archsim_engine.distributions.Empirical.quantile", {"d": d, "q": q})
    for v, unit in [
        (5, None), (5, "ms"), (0.25, "ms"), (12345.678, "req/s"), (True, None), ("redis", None),
        (None, None), ([1, 2], None), (DISTRIBUTIONS[6], "ms"), (DISTRIBUTIONS[9], "ms"),
        (DISTRIBUTIONS[3], None), ({"kind": "trace", "points": [[0, 10], [5, 20]]}, None),
    ]:  # fmt: skip
        rec("archsim_engine.provenance.describe_value", {"v": v, "unit": unit}, errors=True)
    for values in ([], [None], [12.5], [3, None, 1.25, 7], [100.5, 99.5]):
        r = call("archsim_engine.seeds.value_range", {"values": values})
        rec("archsim_engine.seeds.value_range", {"values": values})
        for label in ("p95", "throughput"):
            rec("archsim_engine.seeds.range_text", {"label": label, "r": r})
    for seeds in ([], [42], [42, 43, 44], [1, 5, 9], [42, 43, 45], [7, 8]):
        rec("archsim_engine.seeds.seeds_text", {"seeds": seeds})
    for level in ("low", "medium", "high", "unknown"):
        for cap in (None, "low", "medium", "high"):
            rec("archsim_engine.seeds.cap_confidence", {"level": level, "cap": cap})
    for args in [
        (100, 0, 1000, 0.99, 60, None), (1000, 10, 100_000, 0.99, 300, None),
        (1000, 10, 100_000, 0.99, 300, 5000), (50, 50, 10, 0, 1, None),
        (0, 10, 1000, 0.99, 60, None), (500, 0, 1_000_000, 1.0, 30, 20_000),
        (2000, 200, 50_000, 1.2, 5, None), (10, 1, 3000, 0.5, 600, 2500),
    ]:  # fmt: skip
        names = ("readRps", "writeRps", "keys", "skew", "ttlS", "capacityKeys")
        rec("archsim_engine.load.ttl_hit_ratio", dict(zip(names, args, strict=True)))
    for slots, lam, hold in [
        (1, 50, 10), (1, 99, 10), (4, 320, 10), (8, 100, 50), (1, 100, 10), (16, 0, 5),
        (2, 150, 12.5), (100, 9000, 10),
    ]:  # fmt: skip
        rec("archsim_engine.estimate.mmc_wait", {"slots": slots, "lam": lam, "holdMs": hold})
    for lo, hi, steps in [(50, 300, 6), (0, 10, 6), (1, 1, 3), (300, 50, 4), (12, 18, 10), (5, 200_000, 2)]:  # fmt: skip
        rec("archsim_engine.sweep.levels", {"fromRps": lo, "toRps": hi, "steps": steps})
    for current in (0, 1, 7, 40, 120, 400, 750, 1500, 2800, 60_000):
        rec("archsim_engine.sweep.suggest_range", {"currentRps": current, "cap": 100_000.0})
    rec("archsim_engine.sweep.suggest_range", {"currentRps": 40_000, "cap": 50_000.0})
    for duration in (2.0, 20.0, 60.0):
        rec("archsim_engine.sweep.rps_cap", {"durationS": duration})
    objects: list[Any] = [
        {}, [], {"b": 1, "a": [1, 2.5, "x"]}, {"u": "caf\u00e9 \u2713 \u6771\u4eac \u2013 \U0001f600"},
        {"f": [0.1, 0.2, 0.30000000000000004, 1e-05, 1e-7, 1e16, 1e22, 1.5e300, 123456789.125]},
        {"i": [0, -1, 2**53, 2**63, -(2**31)]}, {"n": None, "t": True, "f": False},
        {"neg": -0.0, "half": 0.5, "third": 1 / 3}, {"esc": "quote \" slash \\ tab \t nl \n"},
        {"keys": {"Z": 1, "a": 2, "_": 3, "\u00e9": 4, "10": 5, "9": 6}},
    ]  # fmt: skip
    for obj in objects:
        rec("archsim_engine.report.canonical_digest", {"obj": obj})
        rec("archsim_engine.pricing.content_sha256", {"data": obj})
    g.cases = 1
    return g


# =============================================================================================
# fixtures
# =============================================================================================


def migrations_file() -> Golden:
    """Documents of every schema version (the smallest template re-stamped), and the version
    errors."""
    g = Golden("fixtures", name="migrations")
    base = read_json(EXAMPLES / "queueing-lab.json")
    cases: list[tuple[str, Any]] = []
    for minor in range(int(SCHEMA_VERSION.split(".")[1]) + 1):
        cases.append((f"1.{minor}", {**base, "schemaVersion": f"1.{minor}"}))
    cases += [
        ("number-1.3", {**base, "schemaVersion": 1.3}),
        ("1.3.0", {**base, "schemaVersion": "1.3.0"}),
        ("1.10", {**base, "schemaVersion": "1.10"}),
        ("2.0", {**base, "schemaVersion": "2.0"}),
        ("0.9", {**base, "schemaVersion": "0.9"}),
        ("not-a-version", {**base, "schemaVersion": "one"}),
        ("empty-version", {**base, "schemaVersion": ""}),
        ("missing-version", {k: v for k, v in base.items() if k != "schemaVersion"}),
        ("not-an-object", [base]),
        ("empty-object", {}),
    ]
    for name, raw in cases:
        where = ("cases", name)
        g.entry(where, "migrate_document", "archsim_engine.migrations.migrate_document",
                {"raw": raw}, errors=True)  # fmt: skip
        g.entry(where, "load_document", "archsim_engine.scenarios.load_document",
                {"raw": raw}, errors=True)  # fmt: skip
    g.cases = len(cases)
    return g


def _node(d: Json, nid: str) -> Json:
    return next(n for n in d["architecture"]["nodes"] if n["id"] == nid)


def _edge(d: Json, eid: str) -> Json:
    return next(e for e in d["architecture"]["edges"] if e["id"] == eid)


def _link(eid: str, source: str, target: str, **config: Any) -> Json:
    return {
        "id": eid,
        "source": source,
        "sourcePort": "out",
        "target": target,
        "targetPort": "in",
        "config": {"requestTypes": ["read", "write"], **config},
    }


def _drop_node(d: Json, nid: str) -> None:
    a = d["architecture"]
    a["nodes"] = [n for n in a["nodes"] if n["id"] != nid]
    a["edges"] = [e for e in a["edges"] if nid not in (e["source"], e["target"])]


def _service(nid: str, **config: Any) -> Json:
    return {"id": nid, "type": "service", "label": nid, "config": config}


Mutation = Callable[[Json], Any]
STARTER = "cache-aside-starter"
INVALID: list[tuple[str, str, str | None, str, Mutation]] = [
    # (case, template, scenario, what it shows, mutation)
    ("missing-architecture", STARTER, None, "no architecture",
     lambda d: d.pop("architecture")),
    ("node-without-id", STARTER, None, "a node without an id",
     lambda d: _node(d, "api").pop("id")),
    ("node-id-pattern", STARTER, None, "a node id that is not an id",
     lambda d: _node(d, "db").update(id="Postgre SQL!")),
    ("unknown-top-level-field", STARTER, None, "a field the schema does not have",
     lambda d: d.update(colour="blue")),
    ("wrong-kind", STARTER, None, "not an architecture document",
     lambda d: d.update(kind="archsim.project")),
    ("negative-rps", STARTER, None, "a negative load",
     lambda d: d["workload"].update(rps=-5)),
    ("rps-not-a-number", STARTER, None, "a load that is not a number",
     lambda d: d["workload"].update(rps="fast")),
    ("warmup-not-shorter", STARTER, None, "a warm-up as long as the run",
     lambda d: d["workload"].update(warmupS=60)),
    ("burst-after-run", STARTER, None, "a burst that starts after the run",
     lambda d: d["workload"].update(pattern={"kind": "burst", "burstRps": 900, "startS": 90, "durationS": 5})),
    ("trace-not-at-zero", STARTER, None, "a trace that does not start at t = 0",
     lambda d: d["workload"].update(pattern={"kind": "trace", "points": [[5, 100], [10, 200]]})),
    ("fault-unknown-kind", STARTER, None, "a fault of an unknown kind",
     lambda d: d["workload"].update(faults=[{"id": "f", "kind": "meltdown", "target": "db", "startS": 1, "durationS": 2}])),
    ("fault-ids-repeat", STARTER, None, "two faults with one id",
     lambda d: d["workload"].update(faults=[{"id": "f", "kind": "outage", "target": "db", "startS": 1, "durationS": 2}] * 2)),
    ("seed-too-large", STARTER, None, "a seed above 2^32 - 1",
     lambda d: d["workload"].update(seed=2**32)),
    ("key-count-zero", STARTER, None, "no keys",
     lambda d: d["workload"].update(keyCount=0)),
    ("unknown-distribution", STARTER, None, "a latency distribution of an unknown kind",
     lambda d: _edge(d, "clients-lb")["config"].update(latencyMs={"kind": "pareto", "alpha": 2})),
    ("lognormal-p95-below-median", STARTER, None, "a lognormal whose p95 is below its median",
     lambda d: _edge(d, "clients-lb")["config"].update(latencyMs={"kind": "lognormal", "median": 10, "p95": 5})),
    ("empirical-falls", STARTER, None, "an empirical distribution whose values fall",
     lambda d: _edge(d, "lb-api")["config"].update(latencyMs={"kind": "empirical", "quantiles": [[0, 5], [0.5, 3], [1, 10]]})),
    ("unknown-request-type", STARTER, None, "a link that carries an unknown request type",
     lambda d: _edge(d, "lb-api")["config"].update(requestTypes=["read", "delete"])),
    ("objective-out-of-range", STARTER, None, "an availability objective above 1",
     lambda d: d.update(objectives={"availability": 1.5})),
    ("override-target-kind", STARTER, None, "a scenario override of an unknown target kind",
     lambda d: d["scenarios"][1]["overrides"][0]["target"].update(kind="service")),
    ("duplicate-node-id", STARTER, None, "two nodes with one id",
     lambda d: d["architecture"]["nodes"].append(copy.deepcopy(_node(d, "db")))),
    ("duplicate-edge-id", STARTER, None, "two links with one id",
     lambda d: d["architecture"]["edges"].append({**copy.deepcopy(_edge(d, "api-db")), "target": "cache"})),
    ("unknown-component-type", STARTER, None, "a component type the registry does not have",
     lambda d: d["architecture"]["nodes"].append({"id": "qc", "type": "quantum_cache", "label": "QC", "config": {}})),
    ("link-to-unknown-node", STARTER, None, "a link to a node that does not exist",
     lambda d: d["architecture"]["edges"].append(_link("api-ghost", "api", "ghost"))),
    ("database-sends", STARTER, None, "a database that sends requests",
     lambda d: d["architecture"]["edges"].append(_link("db-cache", "db", "cache"))),
    ("invalid-target-port", STARTER, None, "a link into a port the target does not have",
     lambda d: _edge(d, "lb-api").update(targetPort="admin")),
    ("self-loop", STARTER, None, "a link from a part to itself",
     lambda d: d["architecture"]["edges"].append(_link("api-api", "api", "api"))),
    ("repeated-link", STARTER, None, "two links between the same parts",
     lambda d: d["architecture"]["edges"].append({**copy.deepcopy(_edge(d, "api-db")), "id": "api-db-2"})),
    ("cycle", STARTER, None, "requests that come back to where they started",
     lambda d: d["architecture"]["edges"].append(_link("api-lb", "api", "lb"))),
    ("config-out-of-range", STARTER, None, "a setting out of its range",
     lambda d: _node(d, "api")["config"].update(replicas=-1)),
    ("config-unknown-field", STARTER, None, "a setting the component does not have",
     lambda d: _node(d, "api")["config"].update(turbo=True)),
    ("config-wrong-type", STARTER, None, "a setting of the wrong type",
     lambda d: _node(d, "db")["config"].update(maxConnections="many")),
    ("unreachable-part", STARTER, None, "a part no request reaches, and its unused link",
     lambda d: (d["architecture"]["nodes"].append(_service("batch")),
                d["architecture"]["edges"].append(_link("batch-db", "batch", "db")))),
    ("no-clients", STARTER, None, "no traffic source",
     lambda d: _drop_node(d, "clients")),
    ("clients-receive", STARTER, None, "a link into a traffic source",
     lambda d: d["architecture"]["edges"].append(_link("api-clients", "api", "clients"))),
    ("miss-port-writes", "cdn-video-streaming", None, "a cache's miss port that carries writes",
     lambda d: _edge(d, "shield-packager")["config"].update(requestTypes=["read", "write"])),
    ("clients-two-links", STARTER, None, "a traffic source with two links",
     lambda d: d["architecture"]["edges"].append(_link("clients-api", "clients", "api"))),
    ("clients-no-link", STARTER, None, "a traffic source with no link",
     lambda d: d["architecture"]["edges"].remove(_edge(d, "clients-lb"))),
    ("no-database-link", STARTER, None, "reads on a miss with nowhere to go",
     lambda d: d["architecture"]["edges"].remove(_edge(d, "api-db"))),
    ("reads-on-miss-without-cache", STARTER, None, "reads on a miss where there is no cache",
     lambda d: _edge(d, "lb-api")["config"].update(readsOnMiss=True)),
    ("unroutable-writes", STARTER, None, "writes that no link carries",
     lambda d: _edge(d, "lb-api")["config"].update(requestTypes=["read"])),
    ("amplification", STARTER, None, "retries that multiply into many calls (bound: 100)",
     lambda d: [_edge(d, e)["config"].update(maxAttempts=5, timeoutMs=100) for e in ("lb-api", "api-cache", "api-db")]),
    ("no-traffic", STARTER, None, "no load",
     lambda d: d["workload"].update(rps=0)),
    ("snapshot-missing", STARTER, None, "a price snapshot this engine does not have",
     lambda d: d["pricing"].update(snapshotDate="2020-01-01")),
    ("unknown-family", STARTER, None, "an instance family no price list has",
     lambda d: d["pricing"].update(families=["zz9"])),
    ("unknown-instance-type", STARTER, None, "an instance type the price list does not have",
     lambda d: _node(d, "db")["config"].update(instanceType="db.zz9.huge")),
    ("memory-too-small", STARTER, None, "a service with less memory than its workers need",
     lambda d: _node(d, "api")["config"].update(memoryGib=0.25, workersPerReplica=400)),
    ("too-many-connections", STARTER, None, "more PostgreSQL connections than its cores serve well",
     lambda d: _node(d, "db")["config"].update(technology="postgresql", maxConnections=500, cpuCores=2)),
    ("autoscaling-range", "black-friday-kubernetes", None, "an autoscaling range upside down",
     lambda d: [n["config"].update(autoscaling=True, minReplicas=8, maxReplicas=2)
                for n in d["architecture"]["nodes"] if n["type"] == "service"][:1]),
    ("pod-too-big", "kubernetes-pods-pending", None, "a pod larger than any Kubernetes node",
     lambda d: _node(d, "api")["config"].update(cpuCores=16)),
    ("queue-without-consumers", "kafka-order-pipeline", None, "a queue nobody consumes",
     lambda d: d["architecture"].update(edges=[
         e for e in d["architecture"]["edges"]
         if _node(d, e["source"])["type"] != "message_queue"])),
    ("unknown-scenario", STARTER, "nope", "a scenario the document does not have",
     lambda d: None),
    ("override-missing-node", STARTER, "one-api-replica", "an override of a node that does not exist",
     lambda d: d["scenarios"][1]["overrides"][0]["target"].update(id="ghost")),
    ("override-missing-path", STARTER, "one-api-replica", "an override of a setting path that does not exist",
     lambda d: d["scenarios"][1]["overrides"][0].update(path="serviceTimeMs.median.deep")),
    ("override-invalid-value", STARTER, "one-api-replica", "an override that makes the design invalid",
     lambda d: d["scenarios"][1]["overrides"][0].update(value=-3)),
    ("override-invalid-workload", STARTER, "traffic-burst", "a workload override that makes the run invalid",
     lambda d: d["scenarios"][5]["overrides"][0]["value"].update(startS=600)),
]  # fmt: skip


MAX_AMPLIFICATION = {"amplification": 100}
"""Cases validated with a tighter bound on the calls one request may cause."""


def invalid_file() -> Golden:
    """Documents with something wrong: what loading, validation and scenario resolution say."""
    g = Golden("fixtures", name="invalid-documents")
    for name, tid, sid, what, mutate in INVALID:
        raw = read_json(EXAMPLES / f"{tid}.json")
        mutate(raw)
        where = ("cases", name)
        g.place((*where, "description"), what)
        g.place((*where, "template"), tid)
        g.place((*where, "scenarioId"), sid)
        doc = g.entry(where, "load_document", "archsim_engine.scenarios.load_document",
                      {"raw": raw}, errors=True)  # fmt: skip
        if doc is None:
            continue
        bound = MAX_AMPLIFICATION.get(name, DEFAULT_MAX_AMPLIFICATION)
        issues = g.entry(
            where, "validate_document", "archsim_engine.validation.validate_document",
            {"doc": doc, "maxAmplification": bound},
        )["issues"]  # fmt: skip
        resolved = g.entry(
            where, "resolve_scenario_sized", "archsim_engine.scenarios.resolve_scenario_sized",
            {"doc": doc, "scenarioId": sid}, errors=True,
        )  # fmt: skip
        if resolved is not None and sid is not None:
            # what POST /validate checks for a scenario: the resolved document
            issues = issues + g.entry(
                where, "validate_document[resolved]",
                "archsim_engine.validation.validate_document",
                {"doc": resolved["resolved"], "maxAmplification": bound},
            )["issues"]  # fmt: skip
        if not issues and resolved is not None:
            raise AssertionError(f"invalid document '{name}' has nothing wrong with it")
    g.cases = len(INVALID)
    return g


IMPORTS: list[tuple[str, str, str | None, str | None]] = [
    # (case, format, fixture file (else the text below), name)
    ("voting-compose", "compose", "voting-compose.yml", None),
    ("voting-compose-named", "compose", "voting-compose.yml", "Voting app"),
    ("shop-k8s", "k8s", "shop-k8s.yaml", None),
    ("orders-aws-plan", "terraform", "orders-aws-plan.json", None),
]
BAD_IMPORTS: list[tuple[str, str, str]] = [
    ("compose-bad-yaml", "compose", "services: [unclosed\n"),
    ("compose-a-list", "compose", "- a\n- b\n"),
    ("compose-no-services", "compose", "version: '3'\n"),
    ("compose-service-a-string", "compose", "services:\n  web: nginx\n"),
    ("k8s-empty", "k8s", ""),
    ("k8s-not-a-manifest", "k8s", "just text\n"),
    ("terraform-not-json", "terraform", "not json"),
    ("terraform-wrong-shape", "terraform", '{"values": []}'),
    ("terraform-empty-plan", "terraform", "{}"),
    ("unknown-format", "helm", "apiVersion: v2\n"),
]


def importers_file() -> Golden:
    g = Golden("fixtures", name="importers")
    for name, fmt, fixture, title in IMPORTS:
        assert fixture is not None
        text = (FIXTURES / "import" / fixture).read_text(encoding="utf-8")
        g.entry(("cases", name), "import_design", "archsim_engine.importers.import_design",
                {"fmt": fmt, "text": text, "name": title})  # fmt: skip
    for name, fmt, text in BAD_IMPORTS:
        g.entry(("cases", name), "import_design", "archsim_engine.importers.import_design",
                {"fmt": fmt, "text": text, "name": None}, errors=True)  # fmt: skip
    g.cases = len(IMPORTS) + len(BAD_IMPORTS)
    return g


SPIED: list[tuple[Any, str, str]] = [
    # (module the caller looks it up in, name, recorded as)
    (calibration_otlp, "parse_otlp", "archsim_engine.calibration.otlp.parse_otlp"),
    (calibration_otlp, "summarize", "archsim_engine.calibration.otlp.summarize"),
    (calibration_apply, "histogram", "archsim_engine.calibration.prometheus.histogram"),
    (calibration_apply, "reduce", "archsim_engine.calibration.prometheus.reduce"),
    (calibration_apply, "fit_histogram", "archsim_engine.calibration.fit.fit_histogram"),
    (calibration_apply, "fit_cpu_share", "archsim_engine.calibration.resources.fit_cpu_share"),
    (calibration_apply, "fit_memory", "archsim_engine.calibration.resources.fit_memory"),
    (
        calibration_resources,
        "fit_load_curve",
        "archsim_engine.calibration.resources.fit_load_curve",
    ),
    (calibration_otlp, "fit_samples", "archsim_engine.calibration.fit.fit_samples"),
]


@contextmanager
def recording_fits() -> Iterator[list[tuple[str, Json]]]:
    """The calls calibration makes to its parsing and fitting functions, as (function, recorded
    input)."""
    calls: list[tuple[str, Json]] = []
    with ExitStack() as stack:
        for module, name, fn in SPIED:
            real = getattr(module, name)
            signature = inspect.signature(real)

            def wrapper(
                *args: Any, _r: Any = real, _s: Any = signature, _fn: str = fn, **kw: Any
            ) -> Any:
                bound = _s.bind(*args, **kw)
                bound.apply_defaults()
                calls.append((_fn, {camel(k): to_json(v) for k, v in bound.arguments.items()}))
                return _r(*args, **kw)

            stack.enter_context(mock.patch.object(module, name, wrapper))
        yield calls


@contextmanager
def recording_simulations() -> Iterator[list[Json]]:
    """The reports of the simulations run while inside (a calibration's replay)."""
    reports: list[Json] = []
    real = archsim_engine.run_simulation

    def wrapper(*args: Any, **kwargs: Any) -> Json:
        report = real(*args, **kwargs)
        normalize_report(report)
        reports.append(copy.deepcopy(report))
        return report

    with mock.patch.object(archsim_engine, "run_simulation", wrapper):
        yield reports


def calibration_file() -> Golden:
    g = Golden("fixtures", name="calibration")
    folder = FIXTURES / "calibration"
    starter = read_json(EXAMPLES / f"{STARTER}.json")
    mapping_text = (folder / "starter-mapping.yaml").read_text(encoding="utf-8")
    recorded = read_json(folder / "starter-prometheus.json")
    otlp_text = (folder / "otlp-mapping.yaml").read_text(encoding="utf-8")
    traces = read_json(folder / "checkout-traces.otlp.json")
    g.place(("files", "starter-mapping.yaml"), mapping_text)
    g.place(("files", "starter-prometheus.json"), recorded)
    g.place(("files", "otlp-mapping.yaml"), otlp_text)
    g.place(("files", "checkout-traces.otlp.json"), traces)

    # mappings and ranges
    where = ("cases", "mappings")
    g.entry(where, "load_mapping", "archsim_engine.calibration.mapping.load_mapping",
            {"source": mapping_text})  # fmt: skip
    for n, source in enumerate([
        "nodes: [not, a, mapping]\n", "range: {start: 1}\nnodes: {}\n", ": bad yaml : [\n",
        {"nodes": {"api": {"serviceTimeMs": {"histogram": "q", "unit": "fortnights"}}}},
        {"nodes": {"api": {"serviceTimeMs": {"gauge": "q", "histogram": "q"}}}},
    ]):  # fmt: skip
        g.entry(where, f"load_mapping[{n}]", "archsim_engine.calibration.mapping.load_mapping",
                {"source": source}, errors=True)  # fmt: skip
    g.entry(where, "load_otlp_mapping", "archsim_engine.calibration.otlp.load_otlp_mapping",
            {"source": otlp_text})  # fmt: skip
    for n, otlp_source in enumerate([None, "", "services: [a]\n", {"cpuTime": {"attribute": "x", "unit": "minutes"}}]):  # fmt: skip
        g.entry(where, f"load_otlp_mapping[{n}]",
                "archsim_engine.calibration.otlp.load_otlp_mapping", {"source": otlp_source},
                errors=True)  # fmt: skip
    for n, (text, step) in enumerate([
        ("2026-10-01T00:00:00Z/2026-10-01T00:02:00Z", 5), ("2026-10-01T00:00:00/2026-10-02T00:00:00+02:00", 60),
        ("2026-10-01T00:00:00Z", 60),
    ]):  # fmt: skip
        g.entry(where, f"parse_range[{n}]", "archsim_engine.calibration.apply.parse_range",
                {"text": text, "step": step}, errors=True)  # fmt: skip

    # Prometheus, with the replay off and on (the replay's report is recorded as an input)
    for name, replay in (("prometheus", False), ("prometheus-replay", True)):
        where = ("cases", name)
        inputs: Json = {
            "document": starter, "mapping": mapping_text, "recorded": recorded, "range": None,
            "step": None, "replay": replay, "simulations": None,
        }  # fmt: skip
        with recording_simulations() as reports, recording_fits() as fits:
            call("archsim_engine.calibration.apply.calibrate_prometheus", inputs)
        inputs["simulations"] = reports
        outcome = g.entry(where, "calibrate_prometheus",
                          "archsim_engine.calibration.apply.calibrate_prometheus", inputs)  # fmt: skip
        g.entry(where, "replay_summary", "archsim_engine.calibration.apply.replay_summary",
                {"record": outcome["calibration"]})  # fmt: skip
        if not replay:
            record_fits(g, where, fits)

    # OpenTelemetry traces, with and without the CPU-time mapping
    otlp_mapping = call("archsim_engine.calibration.otlp.load_otlp_mapping", {"source": otlp_text})
    for name, mapping in (("otlp", otlp_mapping), ("otlp-no-mapping", None)):
        where = ("cases", name)
        inputs = {
            "document": starter, "traces": traces, "mapping": mapping, "minSamples": 20,
            "simulations": None,
        }  # fmt: skip
        with recording_simulations() as reports, recording_fits() as fits:
            call("archsim_engine.calibration.otlp.calibrate_otlp", inputs)
        inputs["simulations"] = reports
        outcome = g.entry(where, "calibrate_otlp", "archsim_engine.calibration.otlp.calibrate_otlp",
                          inputs)  # fmt: skip
        g.entry(where, "replay_summary", "archsim_engine.calibration.apply.replay_summary",
                {"record": outcome["calibration"]})  # fmt: skip
        if mapping is not None:
            record_fits(g, where, fits)

    # fits on synthetic points: a CPU-by-load staircase and its extension above the top
    where = ("cases", "synthetic-fits")
    stairs = [[r, r * (0.9 - 0.05 * math.log(r / 50)) / 1000] for r in (50, 100, 200, 400, 800)]
    g.entry(where, "fit_load_curve", "archsim_engine.calibration.resources.fit_load_curve",
            {"points": stairs, "referenceMs": None, "maxBins": 5})  # fmt: skip
    g.entry(where, "fit_load_curve[flat]", "archsim_engine.calibration.resources.fit_load_curve",
            {"points": [[100, 0.05], [110, 0.055]], "referenceMs": 0.5, "maxBins": 6})  # fmt: skip
    for n, (passes, cores) in enumerate([
        ([[r, 0.9 - 0.05 * math.log(r / 50) + 0.002 * (k % 3)] for r in (100, 200, 400, 800) for k in range(3)], 1.0),
        ([[r, 0.5] for r in (100, 200, 400)], 2.0),
        ([[r, 0.9 - 0.08 * math.log(r / 50)] for r in (400, 800, 1200)], 1.0),
    ]):  # fmt: skip
        g.entry(where, f"extend_load_curve[{n}]",
                "archsim_engine.calibration.resources.extend_load_curve",
                {"passes": passes, "cores": cores, "minBusy": calibration_resources.EXTEND_MIN_BUSY,
                 "baseBusy": calibration_resources.EXTEND_BASE_BUSY, "t": calibration_resources.EXTEND_T},
                errors=True)  # fmt: skip
    g.entry(where, "fit_memory", "archsim_engine.calibration.resources.fit_memory",
            {"workingSetMb": [512 + 0.8 * r for r in (10, 50, 100, 200)],
             "rate": [10, 50, 100, 200], "held": None})  # fmt: skip
    g.entry(where, "fit_memory[both]", "archsim_engine.calibration.resources.fit_memory",
            {"workingSetMb": [300 + 0.5 * r + 2 * h for r, h in ((10, 1), (50, 8), (100, 9), (200, 30))],
             "rate": [10, 50, 100, 200], "held": [1, 8, 9, 30]})  # fmt: skip
    g.entry(where, "fit_cpu_share", "archsim_engine.calibration.resources.fit_cpu_share",
            {"points": [[r, r * 0.004] for r in (50, 100, 150)], "serviceMs": 8.0, "replicas": 2.0})  # fmt: skip
    g.entry(where, "fit_samples", "archsim_engine.calibration.fit.fit_samples",
            {"samples": [1 + (k * 37 % 101) / 10 for k in range(200)], "kind": "empirical"})  # fmt: skip
    g.cases = len(g.root["cases"])
    return g


def record_fits(g: Golden, where: tuple[str, ...], fits: list[tuple[str, Json]]) -> None:
    """The fitting functions a calibration called, each on what it was given (once each)."""
    seen: set[bytes] = set()
    counts: dict[str, int] = {}
    for fn, inputs in fits:
        key = canonical([fn, inputs])
        if key in seen:
            continue
        seen.add(key)
        short = fn.rsplit(".", 1)[-1]
        n = counts.get(short, 0)
        counts[short] = n + 1
        g.entry(where, f"{short}[{n}]", fn, inputs)


# =============================================================================================
# the files
# =============================================================================================

JOBS: dict[str, Callable[[], Golden]] = {
    "catalog/components": components_file,
    "catalog/schema": schema_file,
    "catalog/pricing": pricing_file,
    "catalog/assumptions": assumptions_file,
    "catalog/formatting": formatting_file,
    "fixtures/migrations": migrations_file,
    "fixtures/invalid-documents": invalid_file,
    "fixtures/importers": importers_file,
    "fixtures/calibration": calibration_file,
}


def job_names(only: str | None = None) -> list[str]:
    templates = [f"templates/{t}" for t in template_ids()]
    if only is not None:
        name = only if "/" in only else f"templates/{only}"
        if name not in templates and name not in JOBS:
            raise SystemExit(
                f"unknown golden file '{only}' (a template id, or one of {list(JOBS)})"
            )
        return [name]
    return templates + list(JOBS)


@cache
def _prepared() -> None:
    os.environ.pop("ARCHSIM_PRICES_DIR", None)  # only the snapshots the engine ships


def build(name: str) -> tuple[str, bytes, Json, float]:
    """One golden file: (name, canonical JSON, manifest row, seconds)."""
    _prepared()
    t0 = time.perf_counter()
    g = template_file(name.split("/", 1)[1]) if name.startswith("templates/") else JOBS[name]()
    data = g.data()
    row = {
        "path": f"{name}.json.gz",
        "kind": g.root["kind"],
        "cases": g.cases,
        "entries": g.entries,
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    return name, data, row, time.perf_counter() - t0


def build_all(names: list[str], jobs: int) -> dict[str, tuple[bytes, Json, float]]:
    out: dict[str, tuple[bytes, Json, float]] = {}
    if jobs <= 1 or len(names) == 1:
        for name in names:
            _, data, row, secs = build(name)
            out[name] = (data, row, secs)
        return out
    with ProcessPoolExecutor(max_workers=jobs) as pool:
        for name, data, row, secs in pool.map(build, names):
            out[name] = (data, row, secs)
    return out


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


def read_gz(path: Path) -> bytes | None:
    return gzip.decompress(path.read_bytes()) if path.exists() else None


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


def check(results: dict[str, tuple[bytes, Json, float]], full: bool) -> int:
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
        current = OUT / "manifest.json"
        fresh = json.loads(manifest([row for _, row, _ in results.values()]))
        old = json.loads(current.read_text(encoding="utf-8")) if current.exists() else None
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


def write(results: dict[str, tuple[bytes, Json, float]], full: bool) -> None:
    rows: dict[str, Json] = {}
    path = OUT / "manifest.json"
    if not full and path.exists():
        rows = {r["path"]: r for r in json.loads(path.read_text(encoding="utf-8"))["files"]}
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
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(manifest(list(rows.values())), encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="compare with the committed files")
    parser.add_argument("--only", help="one template id (or e.g. catalog/pricing)")
    parser.add_argument(
        "--jobs", type=int, default=min(8, os.cpu_count() or 1), help="worker processes"
    )
    args = parser.parse_args(argv)
    names = job_names(args.only)
    t0 = time.perf_counter()
    results = build_all(names, args.jobs)
    slowest = sorted(results.items(), key=lambda kv: -kv[1][2])[:3]
    print(
        f"exported {len(results)} golden files in {time.perf_counter() - t0:.1f} s "
        f"(slowest: {', '.join(f'{n} {r[2]:.1f} s' for n, r in slowest)})",
        file=sys.stderr,
    )
    if args.check:
        return check(results, full=args.only is None)
    write(results, full=args.only is None)
    return 0


if __name__ == "__main__":
    sys.exit(main())
