"""Data stores and third-party APIs: replicas, shards, consistency, throttling, external errors."""

from __future__ import annotations

from typing import Any

from archsim_engine import (
    create_default_registry,
    default_registry,
    load_document,
    run_simulation,
    validate_document,
)
from engine_builders import doc, edge, manual, node, node_by_id

EXP10 = {"kind": "exponential", "mean": 10}
CONST1 = {"kind": "constant", "value": 1}


def clients_to(target: dict[str, Any], *, rps: float, **workload: Any) -> dict[str, Any]:
    return doc(
        [node("clients", "traffic_source", timeoutMs=600_000), target],
        [edge("c-t", "clients", target["id"])],
        rps=rps,
        **workload,
    )


def finding_kinds(report: dict[str, Any], node_id: str | None = None) -> set[str]:
    return {f["kind"] for f in report["findings"] if node_id is None or f["nodeId"] == node_id}


def test_read_replicas_raise_read_capacity() -> None:
    db = dict(maxConnections=1, readServiceTimeMs=EXP10, queueCapacity=100_000, queueTimeoutMs=None)
    base = run_simulation(
        clients_to(node("db", "database", **db), rps=180, durationS=30, warmupS=5)
    )
    repl = run_simulation(
        clients_to(node("db", "database", readReplicas=1, **db), rps=180, durationS=30, warmupS=5)
    )
    assert base["summary"]["throughputRps"] < 115  # one connection at 10 ms ~ 100 req/s
    assert repl["summary"]["throughputRps"] > 1.5 * base["summary"]["throughputRps"]
    counters = node_by_id(repl, "db")["summary"]["counters"]
    assert 0.4 < counters["replicaReads"] / counters["reads"] < 0.6
    assert "read_replicas" in finding_kinds(repl, "db")
    assert node_by_id(repl, "db")["summary"]["slots"] == 2


def test_shards_raise_write_capacity_with_an_even_keyspace() -> None:
    db = dict(
        maxConnections=1, writeServiceTimeMs=EXP10, queueCapacity=100_000, queueTimeoutMs=None
    )
    wl = dict(rps=300, durationS=30, warmupS=5, readRatio=0.0, keySkew=0.0, keyCount=10_000)
    one = run_simulation(clients_to(node("db", "database", **db), **wl))
    four = run_simulation(clients_to(node("db", "database", shards=4, **db), **wl))
    assert one["summary"]["throughputRps"] < 115
    assert four["summary"]["throughputRps"] > 250
    counters = node_by_id(four, "db")["summary"]["counters"]
    assert {f"shard{i}" for i in range(4)} <= set(counters)


def test_skewed_keys_make_a_hot_shard() -> None:
    db = node("db", "database", shards=8, maxConnections=50, readServiceTimeMs=CONST1)
    report = run_simulation(clients_to(db, rps=200, durationS=20, keyCount=1000, keySkew=1.5))
    hot = [f for f in report["findings"] if f["kind"] == "hot_shard"]
    assert len(hot) == 1 and hot[0]["nodeId"] == "db"
    assert hot[0]["evidence"]["share"] > 2 * hot[0]["evidence"]["fairShare"]
    assert abs(sum(hot[0]["evidence"]["shardShares"]) - 1) < 1e-3


def test_a_cpu_bound_hot_shard_is_estimated_from_its_primary_cores() -> None:
    """MongoDB reads go to the primary: replicas add no read CPU, so the hot shard's load is its
    primary's cores (not the connections of three replica-set members)."""
    db = node(
        "db",
        "database",
        technology="mongodb",
        shards=4,
        readReplicas=2,
        maxConnections=100,
        cpuCores=1,
        readServiceTimeMs=CONST1,
    )
    report = run_simulation(clients_to(db, rps=900, durationS=15, keyCount=1000, keySkew=1.5))
    s = node_by_id(report, "db")["summary"]
    # 4 primaries x 1 core / 0.5 ms of CPU per read (cpuShare 0.5): replicas serve no reads
    assert s["cpuCapacityRps"] < 4 * 1000 / 0.5 * 1.1
    hot = next(f for f in report["findings"] if f["kind"] == "hot_shard")
    share = hot["evidence"]["share"]
    # this shard's reads x 0.5 ms of CPU on its primary's one core
    assert hot["evidence"]["estimatedHotShardUtilization"] > 0.9 * 900 * share * 0.5 / 1000


def test_consistency_level_orders_latency() -> None:
    def hold(level: str) -> float:
        db = node(
            "db",
            "database",
            replicationFactor=3,
            consistency=level,
            maxConnections=100,
            readServiceTimeMs=EXP10,
        )
        r = run_simulation(manual(clients_to(db, rps=50, durationS=40, warmupS=5)))
        return float(node_by_id(r, "db")["summary"]["meanHoldMs"])

    one, quorum, all_ = hold("one"), hold("quorum"), hold("all")
    assert one < quorum < all_
    # order statistics of 3 exponentials (mean 10): 3.3 / 8.3 / 18.3 ms
    assert 2.5 < one < 4.5 and 6.5 < quorum < 10.5 and 15 < all_ < 22


def test_provisioned_throughput_throttles_the_excess() -> None:
    db = node("db", "database", throughputLimitRps=50, maxConnections=100, readServiceTimeMs=CONST1)
    report = run_simulation(clients_to(db, rps=100, durationS=20))
    s = node_by_id(report, "db")["summary"]
    share = s["rejected"]["throttled"] / s["arrivals"]
    assert 0.4 < share < 0.6
    assert "throttling" in finding_kinds(report, "db")


def test_external_api_error_rate() -> None:
    api = node("pay", "external_api", errorRate=0.1, latencyMs={"kind": "constant", "value": 5})
    report = run_simulation(clients_to(api, rps=200, durationS=20))
    s = node_by_id(report, "pay")["summary"]
    share = s["counters"]["upstreamErrors"] / s["arrivals"]
    assert s["failedDownstream"] == s["counters"]["upstreamErrors"]
    assert 0.07 < share < 0.13
    assert 0.87 < report["summary"]["successRate"] < 0.93
    assert "external_errors" in finding_kinds(report, "pay")
    assert report["summary"]["accounting"]["invariantHolds"]


def test_external_api_rate_and_concurrency_limits() -> None:
    limited = node("llm", "external_api", rateLimitRps=50, errorRate=0, latencyMs=CONST1)
    r = run_simulation(clients_to(limited, rps=100, durationS=20))
    s = node_by_id(r, "llm")["summary"]
    assert 0.4 < s["counters"]["rateLimited"] / s["arrivals"] < 0.6
    assert "throttling" in finding_kinds(r, "llm")
    reasons = {row["reason"] for row in r["summary"]["failureBreakdown"]}
    assert "rate_limited" in reasons

    capped = node(
        "llm",
        "external_api",
        concurrencyLimit=5,
        errorRate=0,
        latencyMs={"kind": "constant", "value": 100},
    )
    r = run_simulation(clients_to(capped, rps=100, durationS=20))
    s = node_by_id(r, "llm")["summary"]
    assert s["slots"] == 5
    assert 0.35 < s["counters"]["throttled"] / s["arrivals"] < 0.65
    assert s["utilization"] > 0.8
    assert "throttling" in finding_kinds(r, "llm")


def test_every_preset_validates_against_its_component() -> None:
    registry = create_default_registry(include_plugins=False)
    seen = 0
    for type_ in registry.types():
        model = registry.get(type_)
        ids = [p.id for p in model.presets]
        assert len(ids) == len(set(ids)), f"duplicate preset ids for {type_}"
        for p in model.presets:
            cfg = model.parse_config({**model.default_config(), **p.config})
            if "technology" in type(cfg).model_fields:
                assert cfg.model_dump()["technology"] == p.technology, (type_, p.id)
            assert p.name and p.family and p.description
            seen += 1
    for type_, minimum in (("database", 10), ("cache", 5), ("external_api", 6)):
        assert len(registry.get(type_).presets) >= minimum
    assert seen >= 21
    catalog = {c["type"]: c for c in registry.catalog()}
    assert catalog["database"]["presets"][0]["config"]["technology"] == "postgresql"


def test_new_knobs_are_deterministic() -> None:
    d = doc(
        [
            node("clients", "traffic_source", timeoutMs=2_000),
            node("svc", "service", replicas=2, workersPerReplica=8),
            node(
                "db",
                "database",
                shards=4,
                replicationFactor=3,
                consistency="quorum",
                readReplicas=1,
                throughputLimitRps=120,
            ),
            node("pay", "external_api", errorRate=0.05, rateLimitRps=80),
        ],
        [
            edge("c-s", "clients", "svc"),
            edge("s-db", "svc", "db", request_types=["read", "write"]),
            edge("s-pay", "svc", "pay", request_types=["write"], order=1),
        ],
        rps=300,
        readRatio=0.7,
        durationS=15,
    )
    a = run_simulation(d)
    b = run_simulation(d)
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]
    assert a["summary"]["accounting"]["invariantHolds"]


def test_consistency_without_replication_warns() -> None:
    d = clients_to(node("db", "database", consistency="quorum"), rps=10)
    issues = validate_document(load_document(d), default_registry()).issues
    assert "database.consistency_unused" in {i.code for i in issues}
