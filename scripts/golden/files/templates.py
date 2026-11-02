"""Template files: each template in ``examples/`` and every one of its cases (the base design and
each scenario), in the order ``analyze_document`` composes the functions."""

from __future__ import annotations

from archsim_engine import check as check_module
from archsim_engine import sweep as sweep_module
from archsim_engine.export import FORMATS
from archsim_engine.validation import DEFAULT_MAX_AMPLIFICATION
from golden.codec import Json, pointer
from golden.file import GoldenFile, same
from golden.files.runs import runs_and_comparison
from golden.paths import EXAMPLES, read_json


def template_ids() -> list[str]:
    return sorted(p.stem for p in EXAMPLES.glob("*.json"))


def case_entries(
    g: GoldenFile, where: tuple[str, ...], raw: Json, doc: Json, sid: str | None
) -> None:
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


def template_file(tid: str) -> GoldenFile:
    raw = read_json(EXAMPLES / f"{tid}.json")
    scenarios = raw.get("scenarios") or []
    g = GoldenFile("template", template=tid)
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
