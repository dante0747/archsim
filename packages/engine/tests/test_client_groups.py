"""Several client groups (mobile app, web browser, API consumers) share one workload."""

from __future__ import annotations

from typing import Any

import pytest
from test_engine_behaviour import assert_accounting

from archsim_engine import default_registry, run_simulation
from archsim_engine.schema import EdgeConfig
from engine_builders import doc, edge, node, node_by_id, single_service

SVC = {"kind": "constant", "value": 2}


def three_clients(**workload: Any) -> dict[str, Any]:
    return doc(
        [
            node("mobile", "mobile_app", technology="android", maxAttempts=1, timeoutMs=60_000),
            node("web", "web_client", timeoutMs=60_000),
            node("partners", "api_consumer", maxAttempts=1, timeoutMs=60_000),
            node("api", "service", replicas=4, workersPerReplica=8, serviceTimeMs=SVC),
        ],
        [
            edge("m-a", "mobile", "api"),
            edge("w-a", "web", "api"),
            edge("p-a", "partners", "api"),
        ],
        **workload,
    )


def test_workload_is_split_by_traffic_share() -> None:
    r = run_simulation(three_clients(rps=400, durationS=30, warmupS=2))
    assert_accounting(r)
    clients = {c["nodeId"]: c for c in r["summary"]["clients"]}
    assert [c["share"] for c in r["summary"]["clients"]] == [0.5, 0.35, 0.15]
    total = r["summary"]["arrived"]
    assert sum(c["arrived"] for c in clients.values()) == total
    for nid, share in (("mobile", 0.5), ("web", 0.35), ("partners", 0.15)):
        assert clients[nid]["arrived"] / total == pytest.approx(share, abs=0.02)
        assert clients[nid]["successRate"] == 1.0
        assert node_by_id(r, nid)["category"] == "traffic"
    assert r["summary"]["offeredRps"] == pytest.approx(400, rel=0.05)


def test_explicit_shares_and_per_client_latency() -> None:
    d = three_clients(rps=300, durationS=20, warmupS=2)
    d["architecture"]["nodes"][0]["config"]["trafficShare"] = 10
    d["architecture"]["nodes"][1]["config"]["trafficShare"] = 10
    d["architecture"]["nodes"][2]["config"]["trafficShare"] = 80
    # a slow mobile network on the mobile link only
    d["architecture"]["edges"][0]["config"]["latencyMs"] = {"kind": "constant", "value": 40}
    r = run_simulation(d)
    clients = {c["nodeId"]: c for c in r["summary"]["clients"]}
    assert clients["partners"]["share"] == 0.8
    assert clients["partners"]["arrived"] > 6 * clients["mobile"]["arrived"]
    # 2 x 40 ms on the wire + 2 ms of work for mobile; 2 ms for the others
    assert clients["mobile"]["latencyMs"]["p50"] == pytest.approx(82, abs=1)
    assert clients["web"]["latencyMs"]["p50"] == pytest.approx(2, abs=0.5)


def test_split_is_deterministic() -> None:
    a = run_simulation(three_clients(rps=200))
    b = run_simulation(three_clients(rps=200))
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]
    assert a["summary"]["clients"] == b["summary"]["clients"]


def test_single_client_reports_one_group() -> None:
    r = run_simulation(single_service(rps=50))
    (only,) = r["summary"]["clients"]
    assert only["nodeId"] == "clients" and only["share"] == 1.0
    assert only["arrived"] == r["summary"]["arrived"]


def test_new_client_types_in_catalog() -> None:
    reg = default_registry()
    expected = {
        "mobile_app": ("mobile", 10_000, 2, 50),
        "web_client": ("browser", 15_000, 1, 35),
        "api_consumer": ("api_client", 5000, 3, 15),
    }
    for type_, (tech, timeout, attempts, share) in expected.items():
        entry = reg.get(type_).catalog_entry()
        assert entry["isTrafficSource"] and entry["category"] == "traffic"
        cfg = entry["defaultConfig"]
        assert (cfg["technology"], cfg["timeoutMs"], cfg["maxAttempts"], cfg["trafficShare"]) == (
            tech,
            timeout,
            attempts,
            share,
        )
        # link defaults are valid link settings
        EdgeConfig.model_validate({**entry["linkDefaults"], "requestTypes": ["read"]})
    assert reg.get("traffic_source").catalog_entry()["linkDefaults"] == {}
