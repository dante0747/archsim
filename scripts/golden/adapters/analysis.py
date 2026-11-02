"""Static analysis of a design: review, provenance, load and instant estimates, sizing, cost,
confidence, the quick check, ``analyze_document`` and the sweep range."""

from __future__ import annotations

from typing import Any

import archsim_engine
from archsim_engine import confidence as confidence_module
from archsim_engine import cost as cost_module
from archsim_engine import engine as engine_module
from archsim_engine import estimate as estimate_module
from archsim_engine import load as load_module
from archsim_engine import provenance as provenance_module
from archsim_engine import review as review_module
from archsim_engine import sizing as sizing_module
from archsim_engine import sweep as sweep_module
from archsim_engine.schema import Workload
from golden.adapters.support import Adapters, doc_of, registry, sizes_of
from golden.codec import Json

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.review.review_design")
def _review(i: Json) -> Any:
    return review_module.review_design(i["doc"], registry())


@ADAPTERS.add("archsim_engine.review.count_by_severity")
def _count_by_severity(i: Json) -> Any:
    return review_module.count_by_severity(i["findings"])


@ADAPTERS.add("archsim_engine.provenance.input_provenance")
def _input_provenance(i: Json) -> Any:
    return provenance_module.input_provenance(doc_of(i["doc"]), registry())


@ADAPTERS.add("archsim_engine.provenance.provenance_counts")
def _provenance_counts(i: Json) -> Any:
    return provenance_module.provenance_counts(i["inputs"])


@ADAPTERS.add("archsim_engine.registry.ComponentRegistry.technology_tiers")
def _technology_tiers(i: Json) -> Any:
    return registry().technology_tiers(i["nodes"])


@ADAPTERS.add("archsim_engine.load.peak_rps")
def _peak_rps(i: Json) -> Any:
    return load_module.peak_rps(i["workload"])


@ADAPTERS.add("archsim_engine.load.base_rps")
def _base_rps(i: Json) -> Any:
    return load_module.base_rps(i["workload"])


@ADAPTERS.add("archsim_engine.load.ttl_miss_shares")
def _ttl_miss_shares(i: Json) -> Any:
    return load_module.ttl_miss_shares(i["doc"], registry(), rate=i["rate"])


@ADAPTERS.add("archsim_engine.load.estimate_load")
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


@ADAPTERS.add("archsim_engine.estimate.estimate_design")
def _estimate_design(i: Json) -> Any:
    return estimate_module.estimate_design(doc_of(i["resolved"]), registry(), i["snapshot"])


@ADAPTERS.add("archsim_engine.sizing.sizing_summary")
def _sizing_summary(i: Json) -> Any:
    return sizing_module.sizing_summary(
        doc_of(i["document"]),
        doc_of(i["resolved"]),
        sizes_of(i["sizes"]),
        registry(),
        i["estimate"],
    )


@ADAPTERS.add("archsim_engine.sizing.base_busy")
def _base_busy(i: Json) -> Any:
    return sizing_module.base_busy(sizes_of(i["sizes"]))


@ADAPTERS.add("archsim_engine.cost.estimate_cost")
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


@ADAPTERS.add("archsim_engine.confidence.analysis_confidence")
def _analysis_confidence(i: Json) -> Any:
    return confidence_module.analysis_confidence(
        i["estimate"], i["inputs"], i["technologies"], registry(), i["architecture"]
    )


@ADAPTERS.add("archsim_engine.engine.quick_check")
def _quick_check(i: Json) -> Any:
    return engine_module.quick_check(Workload.model_validate(i["workload"]))


@ADAPTERS.add("archsim_engine.analyze.analyze_document")
def _analyze_document(i: Json) -> Any:
    return archsim_engine.analyze_document(i["document"], registry(), i["scenarioId"])


@ADAPTERS.add("archsim_engine.sweep.sweep_range")
def _sweep_range(i: Json) -> Any:
    return sweep_module.sweep_range(
        i["document"], registry(), i["scenarioId"], duration_s=i["durationS"]
    )


@ADAPTERS.add("archsim_engine.sweep.levels")
def _levels(i: Json) -> Any:
    return sweep_module.levels(i["fromRps"], i["toRps"], i["steps"])
