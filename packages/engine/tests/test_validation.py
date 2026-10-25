"""Invalid documents produce actionable, located errors and are never simulated."""

from __future__ import annotations

import copy
from typing import Any

import pytest

from archsim_engine import (
    DocumentInvalidError,
    default_registry,
    load_document,
    run_simulation,
    validate_document,
)
from engine_builders import doc, edge, node, single_service


def issues_for(d: dict[str, Any]) -> list[dict[str, Any]]:
    try:
        parsed = load_document(d)
    except DocumentInvalidError as exc:
        return [i.to_dict() for i in exc.issues]
    return [i.to_dict() for i in validate_document(parsed, default_registry()).issues]


def codes(d: dict[str, Any], severity: str = "error") -> set[str]:
    return {i["code"] for i in issues_for(d) if i["severity"] == severity}


def test_starter_example_is_valid(starter: dict[str, Any]) -> None:
    assert codes(starter) == set()
    assert codes(starter, "warning") == set()


def test_cycle_is_rejected_with_path() -> None:
    d = doc(
        [
            node("clients", "traffic_source"),
            node("a", "service", "Service A"),
            node("b", "service", "Service B"),
        ],
        [edge("c-a", "clients", "a"), edge("a-b", "a", "b"), edge("b-a", "b", "a")],
    )
    issues = [i for i in issues_for(d) if i["code"] == "graph.cycle"]
    assert issues
    assert "Service A → Service B → Service A" in issues[0]["message"] or (
        "Service B → Service A → Service B" in issues[0]["message"]
    )
    assert issues[0]["hint"]
    with pytest.raises(DocumentInvalidError):
        run_simulation(d)


def test_cache_miss_must_be_routed() -> None:
    d = doc(
        [
            node("clients", "traffic_source"),
            node("api", "service"),
            node("cache", "cache", "Redis", hitRatio=0.8),
        ],
        [edge("c-a", "clients", "api"), edge("a-c", "api", "cache", request_types=["read"])],
    )
    issues = [i for i in issues_for(d) if i["code"] == "cache.miss_unrouted"]
    assert issues and issues[0]["nodeId"] == "cache"
    assert "miss" in issues[0]["hint"]


def test_cache_with_full_hit_ratio_needs_no_miss_route() -> None:
    d = doc(
        [node("clients", "traffic_source"), node("cache", "cache", hitRatio=1.0)],
        [edge("c-c", "clients", "cache", request_types=["read"])],
    )
    assert codes(d) == set()


def test_database_cannot_send_requests() -> None:
    d = doc(
        [node("clients", "traffic_source"), node("db", "database"), node("api", "service")],
        [edge("c-d", "clients", "db"), edge("d-a", "db", "api")],
    )
    issues = [i for i in issues_for(d) if i["code"] == "edge.invalid_source_port"]
    assert issues and issues[0]["edgeId"] == "d-a"


def test_traffic_source_cannot_receive_requests() -> None:
    d = doc(
        [node("clients", "traffic_source"), node("api", "service")],
        [edge("c-a", "clients", "api"), edge("a-c", "api", "clients")],
    )
    assert "edge.invalid_target_port" in codes(d)


def test_source_requires_exactly_one_link() -> None:
    d = doc([node("clients", "traffic_source"), node("api", "service")], [])
    assert "port.missing_link" in codes(d)
    d2 = doc(
        [node("clients", "traffic_source"), node("a", "service"), node("b", "service")],
        [edge("c-a", "clients", "a"), edge("c-b", "clients", "b")],
    )
    assert "port.too_many_links" in codes(d2)


def test_at_least_one_client_and_several_allowed() -> None:
    assert "graph.no_source" in codes(doc([node("api", "service")], []))
    d = doc(
        [
            node("c1", "traffic_source"),
            node("mobile", "mobile_app", technology="ios"),
            node("web", "web_client"),
            node("partners", "api_consumer", trafficShare=5),
            node("api", "service"),
        ],
        [
            edge("1", "c1", "api"),
            edge("2", "mobile", "api"),
            edge("3", "web", "api"),
            edge("4", "partners", "api"),
        ],
    )
    assert codes(d) == set()
    bad = doc(
        [node("c", "mobile_app", technology="symbian"), node("a", "service")],
        [edge("1", "c", "a")],
    )
    assert "node.config_invalid" in codes(bad)


def test_unknown_type_and_dangling_edge() -> None:
    d = doc(
        [node("clients", "traffic_source"), node("x", "quantum_router")],
        [edge("c-x", "clients", "x"), edge("x-y", "x", "ghost")],
    )
    c = codes(d)
    assert "node.unknown_type" in c
    assert "edge.unknown_node" in c


def test_out_of_range_config_reports_field_path() -> None:
    d = single_service(rps=10)
    d["architecture"]["nodes"][1]["config"]["replicas"] = 0
    d["architecture"]["nodes"][1]["config"]["serviceTimeMs"] = {
        "kind": "lognormal",
        "median": 10,
        "p95": 5,
    }
    issues = [i for i in issues_for(d) if i["code"] == "node.config_invalid"]
    paths = {i["path"] for i in issues}
    assert "replicas" in paths
    assert any(p.startswith("serviceTimeMs") for p in paths)
    assert all(i["nodeId"] == "svc" for i in issues)


def test_unknown_config_field_rejected() -> None:
    d = single_service(rps=10)
    d["architecture"]["nodes"][1]["config"]["replcias"] = 3
    assert "node.config_invalid" in codes(d)


def test_request_types_must_be_routable() -> None:
    # workload has writes, but the source link only carries reads
    d = doc(
        [node("clients", "traffic_source"), node("api", "service")],
        [edge("c-a", "clients", "api", request_types=["read"])],
        readRatio=0.5,
    )
    assert "source.unrouted_types" in codes(d)

    # load balancer receives writes but no outgoing link carries writes
    d2 = doc(
        [node("clients", "traffic_source"), node("lb", "load_balancer"), node("api", "service")],
        [edge("c-l", "clients", "lb"), edge("l-a", "lb", "api", request_types=["read"])],
        readRatio=0.5,
    )
    issues = [i for i in issues_for(d2) if i["code"] == "lb.unroutable_type"]
    assert issues and "write" in issues[0]["message"]


def test_cache_miss_port_only_emits_reads() -> None:
    d = doc(
        [node("clients", "traffic_source"), node("cache", "cache"), node("db", "database")],
        [
            edge("c-c", "clients", "cache"),
            edge("c-d", "cache", "db", source_port="miss", request_types=["read", "write"]),
        ],
    )
    assert "edge.request_types" in codes(d)


def test_self_loop_and_duplicate_links() -> None:
    d = doc(
        [node("clients", "traffic_source"), node("api", "service"), node("db", "database")],
        [
            edge("c-a", "clients", "api"),
            edge("a-a", "api", "api"),
            edge("a-d", "api", "db"),
            edge("a-d2", "api", "db"),
        ],
    )
    c = codes(d)
    assert "edge.self_loop" in c
    assert "edge.duplicate" in c


def test_unreachable_node_and_unused_link_are_warnings() -> None:
    d = doc(
        [
            node("clients", "traffic_source"),
            node("api", "service"),
            node("orphan", "database"),
            node("db", "database"),
        ],
        [edge("c-a", "clients", "api"), edge("a-d", "api", "db", request_types=["write"])],
        readRatio=1.0,
    )
    warnings = codes(d, "warning")
    assert "node.unreachable" in warnings
    assert "edge.unused" in warnings
    assert codes(d) == set()  # warnings do not block simulation


def test_schema_errors_are_located() -> None:
    d = single_service(rps=10)
    d["architecture"]["edges"][0]["config"]["maxAttempts"] = 50
    d["workload"]["warmupS"] = 100
    issues = issues_for(d)
    assert any(i["edgeId"] == "c-s" and "maxAttempts" in i["path"] for i in issues)
    assert any("warmupS" in i["message"] for i in issues)


def test_newer_schema_version_rejected() -> None:
    d = single_service(rps=10)
    d["schemaVersion"] = "9.0"
    issues = issues_for(d)
    assert issues[0]["code"] == "document.version"
    assert "newer" in issues[0]["message"]


def test_issue_messages_name_components(starter: dict[str, Any]) -> None:
    d = copy.deepcopy(starter)
    d["architecture"]["edges"] = [e for e in d["architecture"]["edges"] if e["id"] != "cache-db"]
    issues = [i for i in issues_for(d) if i["code"] == "cache.miss_unrouted"]
    assert "Redis Cache" in issues[0]["message"]
