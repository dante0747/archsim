"""Documents: migration, loading, validation, normalisation, auto-sizing and scenarios."""

from __future__ import annotations

from typing import Any

import archsim_engine
from archsim_engine import migrations as migrations_module
from archsim_engine import scenarios as scenarios_module
from archsim_engine import sizing as sizing_module
from archsim_engine.pricing import pinned
from archsim_engine.schema import Override
from golden.adapters.support import Adapters, doc_of, dump, registry, sizes_json
from golden.codec import Json

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.migrations.migrate_document")
def _migrate(i: Json) -> Any:
    doc, notes = migrations_module.migrate_document(i["raw"])
    return {"document": doc, "notes": notes}


@ADAPTERS.add("archsim_engine.scenarios.load_document")
def _load(i: Json) -> Any:
    return dump(scenarios_module.load_document(i["raw"]))


@ADAPTERS.add("archsim_engine.validation.validate_document")
def _validate(i: Json) -> Any:
    result = archsim_engine.validate_document(
        doc_of(i["doc"]), registry(), max_amplification=i["maxAmplification"]
    )
    return {**result.to_dict(), "edgeFlows": {k: sorted(v) for k, v in result.edge_flows.items()}}


@ADAPTERS.add("archsim_engine.scenarios.normalize_document")
def _normalize(i: Json) -> Any:
    return scenarios_module.normalize_document(doc_of(i["doc"]), registry())


@ADAPTERS.add("archsim_engine.pricing.pinned")
def _pinned(i: Json) -> Any:
    return pinned(i["pricing"])


@ADAPTERS.add("archsim_engine.sizing.auto_sizes")
def _auto_sizes(i: Json) -> Any:
    return sizes_json(sizing_module.auto_sizes(i["doc"], registry()))


@ADAPTERS.add("archsim_engine.sizing.apply_auto_sizes")
def _apply_auto_sizes(i: Json) -> Any:
    doc, sizes = sizing_module.apply_auto_sizes(i["doc"], registry())
    return {"document": doc, "sizes": sizes_json(sizes)}


@ADAPTERS.add("archsim_engine.scenarios.apply_overrides")
def _apply_overrides(i: Json) -> Any:
    overrides = [Override.model_validate(o) for o in i["overrides"]]
    return scenarios_module.apply_overrides(i["docDict"], overrides, i["scenarioName"])


@ADAPTERS.add("archsim_engine.scenarios.resolve_scenario_sized")
def _resolve(i: Json) -> Any:
    resolved, scenario, sizes = scenarios_module.resolve_scenario_sized(
        doc_of(i["doc"]), i["scenarioId"], registry()
    )
    return {"resolved": dump(resolved), "scenario": scenario, "sizes": sizes_json(sizes)}


@ADAPTERS.add("archsim_engine.scenarios.simulation_input")
def _simulation_input(i: Json) -> Any:
    return scenarios_module.simulation_input(doc_of(i["resolved"]))
