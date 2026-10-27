"""Confidence on every verdict, and the "documented" provenance it counts as grounded.

The starter rests on ArchSim's defaults, so it is low; the same kind of design calibrated from
telemetry on core technologies is high. In between, and around experimental technologies and
bad calibration fits, the level follows the rules in docs/simulation-model.md."""

from __future__ import annotations

import copy
from typing import Any

import pytest

from archsim_engine import run_simulation
from archsim_engine.analyze import analyze_document
from archsim_engine.components import create_default_registry
from archsim_engine.confidence import assess
from archsim_engine.provenance import KINDS
from engine_builders import load_example

REGISTRY = create_default_registry(include_plugins=False)
PROM = "Prometheus http://prom:9090"


def _path(i: dict[str, Any]) -> str:
    if i["scope"] == "workload":
        return f"workload/{i['field']}"
    return f"{i['scope']}s/{i['id']}/{i['field']}"


def calibrated(d: dict[str, Any], fit: float = 0.05, only: str | None = None) -> dict[str, Any]:
    """``d`` with every input (or every input of one scope) recorded as measured."""
    d = copy.deepcopy(d)
    inputs = analyze_document(d, REGISTRY)["inputs"]
    d["provenance"] = {
        _path(i): {"kind": "measured", "source": PROM, "fitError": fit}
        for i in inputs
        if only is None or i["scope"] == only
    }
    return d


def shortened(d: dict[str, Any], seconds: float = 12.0) -> dict[str, Any]:
    d = copy.deepcopy(d)
    d["workload"]["durationS"], d["workload"]["warmupS"] = seconds, 2
    return d


def entry(a: dict[str, Any], node_id: str, field: str) -> dict[str, Any]:
    return next(i for i in a["inputs"] if i["id"] == node_id and i["field"] == field)


# ---------------------------------------------------------------------------------------------
# documented inputs
# ---------------------------------------------------------------------------------------------


def test_a_technology_default_with_vendor_docs_is_documented_and_cites_them() -> None:
    d = load_example("queueing-lab.json")
    d["architecture"]["nodes"][1]["config"]["technology"] = "go"
    a = analyze_document(d, REGISTRY)
    gc = entry(a, "svc", "gcPauseMs")
    assert gc["provenance"]["kind"] == "documented"
    assert gc["provenance"]["source"].startswith("https://go.dev/")
    assert "go" in gc["provenance"]["note"]
    assert gc["value"] == "0.1 ms"  # the value that applies, not "None"
    # Go's GC frequency has no documentation link: illustrative
    assert entry(a, "svc", "gcEveryRequests")["provenance"]["kind"] == "illustrative"
    assert list(a["provenanceCounts"]) == list(KINDS)
    assert a["provenanceCounts"]["documented"] == 1
    assert sum(a["provenanceCounts"].values()) == len(a["inputs"])


def test_typing_the_documented_number_keeps_it_documented_and_another_is_assumed() -> None:
    d = load_example("queueing-lab.json")
    cfg = d["architecture"]["nodes"][1]["config"]
    cfg["technology"] = "go"
    cfg["gcPauseMs"] = 0.1
    assert entry(analyze_document(d, REGISTRY), "svc", "gcPauseMs")["provenance"]["kind"] == (
        "documented"
    )
    cfg["gcPauseMs"] = 2
    assert entry(analyze_document(d, REGISTRY), "svc", "gcPauseMs")["provenance"] == {
        "kind": "assumed"
    }


def test_a_calibration_record_wins_over_documented() -> None:
    d = load_example("queueing-lab.json")
    d["architecture"]["nodes"][1]["config"]["technology"] = "go"
    d["provenance"] = {"nodes/svc/gcPauseMs": {"kind": "measured", "source": PROM}}
    assert entry(analyze_document(d, REGISTRY), "svc", "gcPauseMs")["provenance"]["kind"] == (
        "measured"
    )


def test_a_preset_number_with_a_source_is_documented() -> None:
    model = REGISTRY.get("external_api")
    preset = next(p for p in model.presets if p.sources)
    key = next(iter(preset.sources))
    d = load_example("queueing-lab.json")
    d["architecture"]["nodes"].append(
        {
            "id": "s3",
            "type": "external_api",
            "label": "Object storage",
            "config": {"technology": preset.technology, **preset.config},
        }
    )
    d["architecture"]["edges"].append(
        {"id": "svc-s3", "source": "svc", "sourcePort": "out", "target": "s3", "config": {}}
    )
    got = entry(analyze_document(d, REGISTRY), "s3", key)["provenance"]
    assert got == {
        "kind": "documented",
        "source": preset.sources[key],
        "note": f"preset {preset.name}, stated in its documentation",
    }


def test_documented_is_derived_never_stored() -> None:
    """Schema 1.3 keeps three kinds: a document cannot record "documented" itself."""
    d = load_example("queueing-lab.json")
    d["provenance"] = {"nodes/svc/serviceTimeMs": {"kind": "documented"}}
    from archsim_engine.scenarios import DocumentInvalidError, load_document

    with pytest.raises(DocumentInvalidError):
        load_document(d)


# ---------------------------------------------------------------------------------------------
# levels
# ---------------------------------------------------------------------------------------------


def test_the_starter_is_low_because_its_inputs_are_illustrative(starter: dict[str, Any]) -> None:
    a = analyze_document(starter, REGISTRY)["confidence"]
    assert a["level"] == "low"
    assert a["reason"] == "31 of 35 inputs are illustrative defaults"
    assert a["weightedBy"] == "utilization"
    assert a["drivers"][0]["label"] == "API Servers"  # the busiest part, its service time first
    assert a["drivers"][0]["field"] == "serviceTimeMs"
    assert "Calibrate from telemetry" in a["howToRaise"]

    r = run_simulation(shortened(starter))["confidence"]
    assert r["level"] == "low"
    assert r["reason"] == "31 of 35 inputs are illustrative defaults"
    assert r["weightedBy"] == "latency"
    # the network carries most of a starter request's time: the client link leads the drivers
    assert r["drivers"][0]["id"] == "clients-lb"


def test_a_calibrated_design_on_core_technologies_is_high() -> None:
    d = calibrated(load_example("queueing-lab.json"))
    a = analyze_document(d, REGISTRY)["confidence"]
    assert a["level"] == "high", a
    assert a["reason"] == "14 of 14 inputs are measured or documented"
    assert a["drivers"] == []
    r = run_simulation(shortened(d))["confidence"]
    assert r["level"] == "high", r
    assert r["weightedBy"] == "latency"


def test_a_part_of_the_weight_grounded_is_medium(starter: dict[str, Any]) -> None:
    """The starter with its links measured: the network is most of the latency, not 80%."""
    d = calibrated(starter, only="edge")
    r = run_simulation(shortened(d))["confidence"]
    assert r["level"] == "medium", r
    assert "of the latency rests on measured or documented inputs" in r["reason"]
    assert "high confidence needs 80%" in r["reason"]


def test_a_fit_error_above_30_percent_is_low() -> None:
    d = calibrated(load_example("queueing-lab.json"))
    d["provenance"]["nodes/svc/serviceTimeMs"]["fitError"] = 0.42
    a = analyze_document(d, REGISTRY)["confidence"]
    assert a["level"] == "low"
    assert "fits only within 42%" in a["reason"]
    assert a["drivers"][0]["field"] == "serviceTimeMs"


def test_a_fit_between_15_and_30_percent_does_not_count_as_measured() -> None:
    d = calibrated(load_example("queueing-lab.json"), fit=0.2)
    a = analyze_document(d, REGISTRY)["confidence"]
    assert a["level"] == "low"  # nothing on the busiest part is grounded
    assert "0 of 14 inputs" not in a["reason"]


def test_an_experimental_technology_at_the_bottleneck_is_low() -> None:
    d = load_example("queueing-lab.json")
    d["architecture"]["nodes"][1]["config"]["technology"] = "python"
    d = calibrated(d)
    a = analyze_document(d, REGISTRY)["confidence"]
    assert a["level"] == "low"
    assert a["reason"].startswith("The busiest part, Service (4 workers), uses an experimental")
    r = run_simulation(shortened(d))["confidence"]
    assert r["level"] == "low"
    assert r["reason"].startswith("The bottleneck, Service (4 workers), uses an experimental")


def test_an_experimental_technology_on_the_path_caps_a_grounded_design_at_medium(
    starter: dict[str, Any],
) -> None:
    starter["architecture"]["nodes"][1]["config"]["technology"] = "envoy"  # the load balancer
    a = analyze_document(calibrated(starter), REGISTRY)["confidence"]
    assert a["level"] == "medium", a
    assert (
        a["reason"] == "Load Balancer on the request path uses an experimental technology (Envoy)"
    )


def test_confidence_is_not_in_the_result_digest(starter: dict[str, Any]) -> None:
    from archsim_engine.report import canonical_digest

    r = run_simulation(shortened(starter))
    assert r["reportVersion"] == "1.13"
    digest = canonical_digest(
        {k: r[k] for k in ("summary", "timeseries", "nodes", "edges", "window")}
    )
    assert r["provenance"]["resultDigest"] == digest
    assert "confidence" in r and "estimate" in r


def test_assess_without_any_weight_counts_inputs_equally() -> None:
    inputs = [
        {"scope": "node", "id": "a", "label": "A", "field": "serviceTimeMs", "fieldLabel": "Service time", "provenance": {"kind": "measured"}},
        {"scope": "node", "id": "b", "label": "B", "field": "serviceTimeMs", "fieldLabel": "Service time", "provenance": {"kind": "assumed"}},
    ]  # fmt: skip
    c = assess(inputs, {}, weighted_by="utilization")
    assert c["weightedBy"] == "inputs"
    assert c["level"] == "medium"  # half grounded
    assert c["drivers"] == [
        {"scope": "node", "id": "b", "label": "B", "field": "serviceTimeMs", "fieldLabel": "Service time", "kind": "assumed", "weight": 0.5}
    ]  # fmt: skip
