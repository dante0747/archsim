"""Static analysis of a design without simulating it: the auto sizes, the cost estimate, the
design review and each part's technology tier, exactly as a run would use them
(``POST /api/v1/analyze``, the web app's live inspector and design review, the CLI and the MCP
server all call this)."""

from __future__ import annotations

from typing import Any

from archsim_engine.cost import estimate_cost
from archsim_engine.load import peak_rps
from archsim_engine.provenance import input_provenance, provenance_counts
from archsim_engine.registry import ComponentRegistry
from archsim_engine.review import REVIEW_RULES, count_by_severity, review_design
from archsim_engine.scenarios import load_document, resolve_scenario_sized, simulation_input
from archsim_engine.schema import ArchitectureDocument
from archsim_engine.sizing import sizing_summary


def analyze_document(
    document: dict[str, Any] | ArchitectureDocument,
    registry: ComponentRegistry,
    scenario_id: str | None = None,
) -> dict[str, Any]:
    """Sizes, cost and review of ``document`` (or one of its scenarios). Raises
    ``DocumentInvalidError`` when the document cannot be read."""
    doc = load_document(document)
    resolved, scenario, sizes = resolve_scenario_sized(doc, scenario_id, registry)
    snapshot = simulation_input(resolved)
    review = review_design(snapshot, registry)
    inputs = input_provenance(resolved, registry)
    return {
        "scenarioId": scenario.id if scenario else None,
        "peakRps": peak_rps(snapshot["workload"]),
        "sizing": sizing_summary(doc, resolved, sizes),
        "cost": estimate_cost(snapshot["architecture"]["nodes"], registry),
        "review": review,
        "reviewCounts": count_by_severity(review),
        "reviewRules": REVIEW_RULES,
        "technologies": registry.technology_tiers(snapshot["architecture"]["nodes"]),
        "inputs": inputs,
        "provenanceCounts": provenance_counts(inputs),
        "calibration": doc.calibration.model_dump(mode="json", by_alias=True)
        if doc.calibration
        else None,
    }
