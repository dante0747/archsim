"""Scenario overrides, provenance and A/B comparison."""

from __future__ import annotations

import copy
from typing import Any

import pytest

from archsim_engine import DocumentInvalidError, compare_reports, run_simulation
from engine_builders import node_by_id, short


def test_scenario_applies_overrides_and_records_provenance(starter: dict[str, Any]) -> None:
    r = run_simulation(short(starter), scenario_id="three-api-replicas")
    p = r["provenance"]
    assert p["scenarioId"] == "three-api-replicas"
    assert p["scenarioName"] == "3 API replicas"
    assert p["overrides"] == [
        {"target": {"kind": "node", "id": "api"}, "path": "replicas", "value": 3}
    ]
    api_cfg = next(n for n in r["input"]["architecture"]["nodes"] if n["id"] == "api")["config"]
    assert api_cfg["replicas"] == 3
    assert node_by_id(r, "api")["summary"]["slots"] == 3 * 4


def test_scenario_equals_hand_edited_document(starter: dict[str, Any]) -> None:
    via_scenario = run_simulation(short(starter), scenario_id="one-api-replica")
    edited = short(starter)
    next(n for n in edited["architecture"]["nodes"] if n["id"] == "api")["config"]["replicas"] = 1
    direct = run_simulation(edited)
    assert via_scenario["provenance"]["inputDigest"] == direct["provenance"]["inputDigest"]
    assert via_scenario["provenance"]["resultDigest"] == direct["provenance"]["resultDigest"]


def test_nested_and_workload_overrides(starter: dict[str, Any]) -> None:
    d = short(starter)
    d["scenarios"].append(
        {
            "id": "slow-db",
            "name": "Slow DB at 300 rps",
            "overrides": [
                {
                    "target": {"kind": "node", "id": "db"},
                    "path": "readServiceTimeMs.median",
                    "value": 20,
                },
                {
                    "target": {"kind": "node", "id": "db"},
                    "path": "readServiceTimeMs.p95",
                    "value": 60,
                },
                {"target": {"kind": "workload"}, "path": "rps", "value": 300},
                {"target": {"kind": "edge", "id": "api-db"}, "path": "timeoutMs", "value": 900},
            ],
        }
    )
    r = run_simulation(d, scenario_id="slow-db")
    db_cfg = next(n for n in r["input"]["architecture"]["nodes"] if n["id"] == "db")["config"]
    assert db_cfg["readServiceTimeMs"] == {"kind": "lognormal", "median": 20.0, "p95": 60.0}
    assert r["input"]["workload"]["rps"] == 300
    e = next(e for e in r["input"]["architecture"]["edges"] if e["id"] == "api-db")
    assert e["config"]["timeoutMs"] == 900


def test_invalid_overrides_are_rejected(starter: dict[str, Any]) -> None:
    d = copy.deepcopy(starter)
    d["scenarios"].append(
        {
            "id": "bad",
            "name": "Bad",
            "overrides": [
                {"target": {"kind": "node", "id": "nope"}, "path": "replicas", "value": 2}
            ],
        }
    )
    with pytest.raises(DocumentInvalidError) as exc:
        run_simulation(d, scenario_id="bad")
    assert exc.value.issues[0].code == "scenario.invalid_override"

    d["scenarios"][-1]["overrides"] = [
        {"target": {"kind": "node", "id": "api"}, "path": "replicas", "value": -1}
    ]
    with pytest.raises(DocumentInvalidError) as exc:
        run_simulation(d, scenario_id="bad")
    assert exc.value.issues[0].code == "node.config_invalid"

    with pytest.raises(DocumentInvalidError) as exc:
        run_simulation(d, scenario_id="does-not-exist")
    assert exc.value.issues[0].code == "scenario.unknown"


def test_compare_explains_replica_change(starter: dict[str, Any]) -> None:
    a = run_simulation(short(starter), scenario_id="one-api-replica")
    b = run_simulation(short(starter), scenario_id="three-api-replicas")
    cmp = compare_reports(a, b, "1 replica", "3 replicas")
    assert cmp["inputDiff"] == [
        {"path": "node[api].replicas", "label": "API Servers: replicas", "a": 1, "b": 3}
    ]
    kpi = {k["key"]: k for k in cmp["kpis"]}
    assert kpi["throughputRps"]["verdict"] == "better"
    assert kpi["latencyMs.p95"]["verdict"] == "better"
    assert kpi["successRate"]["verdict"] == "better"
    text = " ".join(cmp["explanations"])
    assert "replicas 1 → 3" in text
    assert "API Servers is no longer saturated" in text
    assert cmp["warnings"] == []
    assert cmp["provenance"]["a"]["scenarioId"] == "one-api-replica"


def test_compare_identical_runs_and_seed_warning(starter: dict[str, Any]) -> None:
    a = run_simulation(short(starter))
    b = run_simulation(short(starter))
    cmp = compare_reports(a, b)
    assert cmp["inputDiff"] == []
    assert all(k["verdict"] in ("same", "n/a") for k in cmp["kpis"])
    other = short(starter)
    other["workload"]["seed"] = 1
    cmp2 = compare_reports(a, run_simulation(other))
    assert any("different random seeds" in w for w in cmp2["warnings"])
