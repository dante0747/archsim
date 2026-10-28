"""Static analysis of a design without simulating it: the auto sizes, the cost estimate, the
design review, each part's technology tier, where every input came from, the instant estimate
from queueing formulas and the confidence it deserves, and whether a quick (sampled) check can
answer it, exactly as a run would use them
(``POST /api/v1/analyze``, the web app's live inspector and design review, the CLI and the MCP
server all call this)."""

from __future__ import annotations

from typing import Any

from archsim_engine.confidence import analysis_confidence
from archsim_engine.cost import estimate_cost
from archsim_engine.engine import quick_check
from archsim_engine.estimate import estimate_design
from archsim_engine.load import peak_rps
from archsim_engine.provenance import input_provenance, provenance_counts
from archsim_engine.registry import ComponentRegistry
from archsim_engine.review import REVIEW_RULES, count_by_severity, review_design
from archsim_engine.scenarios import load_document, resolve_scenario_sized, simulation_input
from archsim_engine.schema import ArchitectureDocument
from archsim_engine.sizing import base_busy, sizing_summary


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
    technologies = registry.technology_tiers(snapshot["architecture"]["nodes"])
    estimate = estimate_design(resolved, registry, snapshot)
    return {
        "scenarioId": scenario.id if scenario else None,
        "peakRps": peak_rps(snapshot["workload"]),
        "sizing": sizing_summary(doc, resolved, sizes, registry, estimate),
        "cost": estimate_cost(
            snapshot["architecture"]["nodes"],
            registry,
            None,
            snapshot.get("pricing"),
            {k: v.kind for k, v in resolved.provenance.items()},
            base_busy(sizes),
        ),
        "review": review,
        "reviewCounts": count_by_severity(review),
        "reviewRules": REVIEW_RULES,
        "technologies": technologies,
        "inputs": inputs,
        "provenanceCounts": provenance_counts(inputs),
        "estimate": estimate,
        "confidence": analysis_confidence(
            estimate, inputs, technologies, registry, snapshot["architecture"]
        ),
        # can "Quick check (estimated)" (sampled mode) answer it, and if not, why
        "quickCheck": quick_check(resolved.workload),
        "calibration": doc.calibration.model_dump(mode="json", by_alias=True)
        if doc.calibration
        else None,
    }
