"""Document loading, normalisation and scenario resolution.

A scenario is a named list of overrides applied to the base document. Resolution first
materialises every component default (so overrides like ``serviceTimeMs.median`` have something
to modify), applies overrides, and re-validates. The resolved document is what gets simulated
and stored as the run's input snapshot.
"""

from __future__ import annotations

import contextlib
import copy
from typing import Any

from pydantic import ValidationError

from archsim_engine.issues import DocumentInvalidError, error
from archsim_engine.migrations import MigrationError, migrate_document
from archsim_engine.registry import ComponentRegistry
from archsim_engine.schema import ArchitectureDocument, Override, Scenario
from archsim_engine.sizing import PartSize, apply_auto_sizes
from archsim_engine.validation import pydantic_issues


def load_document(raw: dict[str, Any] | ArchitectureDocument) -> ArchitectureDocument:
    """Migrate and parse a raw JSON document. Raises ``DocumentInvalidError`` with issues."""
    if isinstance(raw, ArchitectureDocument):
        return raw
    try:
        migrated, _ = migrate_document(raw)
    except MigrationError as exc:
        raise DocumentInvalidError([error("document.version", str(exc))]) from None
    try:
        return ArchitectureDocument.model_validate(migrated)
    except ValidationError as exc:
        raise DocumentInvalidError(pydantic_issues(exc, migrated)) from None


def normalize_document(doc: ArchitectureDocument, registry: ComponentRegistry) -> dict[str, Any]:
    """JSON dict of ``doc`` with every parseable node config expanded to include defaults."""
    d = doc.model_dump(mode="json", by_alias=True)
    for node in d["architecture"]["nodes"]:
        if node["type"] in registry:
            model = registry.get(node["type"])
            with contextlib.suppress(ValidationError):  # invalid configs are reported later
                node["config"] = model.parse_config(node["config"]).model_dump(
                    mode="json", by_alias=True
                )
    return d


def _set_path(container: dict[str, Any], path: str, value: Any, where: str) -> None:
    parts = path.split(".")
    cur: Any = container
    for p in parts[:-1]:
        if not isinstance(cur, dict) or p not in cur or not isinstance(cur[p], dict):
            raise KeyError(f"{where}: '{path}' does not exist (no object at '{p}')")
        cur = cur[p]
    if not isinstance(cur, dict):
        raise KeyError(f"{where}: cannot set '{path}'")
    cur[parts[-1]] = copy.deepcopy(value)


def apply_overrides(
    doc_dict: dict[str, Any], overrides: list[Override], scenario_name: str = "scenario"
) -> dict[str, Any]:
    d = copy.deepcopy(doc_dict)
    problems = []
    for o in overrides:
        t = o.target
        try:
            if t.kind == "workload":
                _set_path(d["workload"], o.path, o.value, f"{scenario_name} (workload)")
                continue
            coll = d["architecture"]["nodes" if t.kind == "node" else "edges"]
            item = next((x for x in coll if x["id"] == t.id), None)
            if item is None:
                raise KeyError(f"{scenario_name}: {t.kind} '{t.id}' does not exist")
            item.setdefault("config", {})
            _set_path(item["config"], o.path, o.value, f"{scenario_name} ({t.kind} '{t.id}')")
        except KeyError as exc:
            problems.append(
                error(
                    "scenario.invalid_override",
                    str(exc).strip("'\""),
                    node_id=t.id if t.kind == "node" else None,
                    edge_id=t.id if t.kind == "edge" else None,
                    path=o.path,
                    hint="Edit the scenario so it targets an existing node/edge and field.",
                )
            )
    if problems:
        raise DocumentInvalidError(problems)
    return d


def resolve_scenario(
    doc: ArchitectureDocument, scenario_id: str | None, registry: ComponentRegistry
) -> tuple[ArchitectureDocument, Scenario | None]:
    """Return the document to simulate (defaults materialised, auto sizes filled in, overrides
    applied)."""
    resolved, scenario, _ = resolve_scenario_sized(doc, scenario_id, registry)
    return resolved, scenario


def resolve_scenario_sized(
    doc: ArchitectureDocument, scenario_id: str | None, registry: ComponentRegistry
) -> tuple[ArchitectureDocument, Scenario | None, dict[str, PartSize]]:
    """``resolve_scenario`` that also returns the auto sizes. Sizes come from the design as
    drawn: a scenario runs on the same sizes (its overrides can still set a spec)."""
    base = normalize_document(doc, registry)
    base, sizes = apply_auto_sizes(base, registry)
    scenario = None
    if scenario_id:
        scenario = doc.scenario(scenario_id)
        if scenario is None:
            available = ", ".join(s.id for s in doc.scenarios) or "none"
            raise DocumentInvalidError(
                [
                    error(
                        "scenario.unknown",
                        f"Unknown scenario '{scenario_id}' (available: {available}).",
                    )
                ]
            )
        base = apply_overrides(base, scenario.overrides, f"Scenario '{scenario.name}'")
    base["scenarios"] = []
    base["layout"] = {"nodes": {}}
    try:
        resolved = ArchitectureDocument.model_validate(base)
    except ValidationError as exc:
        raise DocumentInvalidError(pydantic_issues(exc, base)) from None
    return resolved, scenario, sizes


def simulation_input(resolved: ArchitectureDocument) -> dict[str, Any]:
    """The exact model the engine simulates (no layout/scenarios), used for provenance."""
    d = resolved.model_dump(mode="json", by_alias=True)
    return {
        "schemaVersion": d["schemaVersion"],
        "metadata": {"name": d["metadata"]["name"]},
        "architecture": d["architecture"],
        "workload": d["workload"],
    }
