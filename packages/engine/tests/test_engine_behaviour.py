"""Behavioural tests: the simulator responds to load, capacity and caching as queueing theory says."""

from __future__ import annotations

import json
from typing import Any

import pytest

from archsim_engine import (
    SimulationCancelledError,
    SimulationLimitError,
    SimulationLimits,
    run_simulation,
)
from engine_builders import doc, edge, manual, node, node_by_id, short, single_service


def assert_accounting(report: dict[str, Any]) -> None:
    """Request conservation invariants (see docs/simulation-model.md#accounting)."""
    acc = report["summary"]["accounting"]
    assert acc["invariantHolds"], acc
    for cohort in ("warmup", "measured", "total"):
        c = acc[cohort]
        assert c["arrived"] == c["succeeded"] + c["failed"] + c["timedOut"] + c["outstanding"]
        assert min(c.values()) >= 0
    for n in report["nodes"]:
        a = n["accounting"]
        assert a["invariantHolds"], (n["id"], a)
    if acc["drained"]:
        # every attempt sent over a link arrived at its target
        arrivals = {n["id"]: n["accounting"]["arrivals"] for n in report["nodes"]}
        into: dict[str, int] = {}
        for e in report["edges"]:
            into[e["target"]] = into.get(e["target"], 0) + e["attemptsTotal"]
        for nid, attempts in into.items():
            assert arrivals[nid] == attempts, (nid, arrivals[nid], attempts)


def test_no_traffic_completes_cleanly() -> None:
    r = run_simulation(single_service(rps=0))
    s = r["summary"]
    assert s["arrived"] == 0 and s["succeeded"] == 0
    assert s["throughputRps"] == 0
    assert s["successRate"] is None
    assert s["latencyMs"]["p95"] is None
    assert any(f["kind"] == "no_traffic" for f in r["findings"])
    json.dumps(r, allow_nan=False)  # no NaN/inf anywhere
    assert_accounting(r)


def test_below_capacity_tracks_arrivals_with_little_queueing() -> None:
    # capacity: 4 workers / 10 ms = 400 req/s; offered 100 req/s (25%)
    r = run_simulation(manual(single_service(rps=100, workers=4, durationS=60, warmupS=5)))
    s = r["summary"]
    assert s["successRate"] == 1.0
    assert s["throughputRps"] == pytest.approx(s["offeredRps"], rel=0.05)
    assert s["offeredRps"] == pytest.approx(100, rel=0.1)
    svc = node_by_id(r, "svc")["summary"]
    assert svc["utilization"] == pytest.approx(0.25, abs=0.05)
    assert svc["meanQueueWaitMs"] < 2.0
    assert node_by_id(r, "svc")["status"] == "ok"
    assert_accounting(r)


def test_saturated_service_queues_and_rejects() -> None:
    # capacity 100 req/s, offered 200 req/s: queue (limit 50) fills, excess is rejected
    r = run_simulation(
        single_service(rps=200, workers=1, queue_capacity=50, durationS=30, warmupS=5)
    )
    s = r["summary"]
    svc = node_by_id(r, "svc")
    assert svc["status"] == "saturated"
    assert svc["summary"]["utilization"] > 0.97
    assert svc["summary"]["peakQueue"] == 50
    assert svc["summary"]["rejected"].get("queue_full", 0) > 0
    assert s["throughputRps"] == pytest.approx(100, rel=0.1)
    assert s["successRate"] < 0.6
    assert s["latencyMs"]["p50"] > 300  # ~50 queued x 10 ms
    assert any(f["kind"] == "bottleneck" and f["nodeId"] == "svc" for f in r["findings"])
    assert_accounting(r)


def test_unbounded_queue_grows_without_limit_when_overloaded() -> None:
    r = run_simulation(single_service(rps=150, workers=1, durationS=30, warmupS=2))
    svc = node_by_id(r, "svc")
    assert "queue kept growing" in svc["statusReasons"]
    q = svc["timeseries"]["queueMean"]
    assert q[len(q) // 2] > q[2]


def test_client_timeouts_and_drain_conclude_every_request() -> None:
    r = run_simulation(
        single_service(rps=150, workers=1, client_timeout_ms=200, durationS=20, warmupS=2)
    )
    s = r["summary"]
    assert s["timedOut"] > 0
    assert s["latencyMs"]["max"] is None or s["latencyMs"]["max"] <= 200 + 1e-6
    assert s["accounting"]["total"]["outstanding"] == 0  # auto-drain lets every client finish
    assert any(f["kind"] == "failures" for f in r["findings"])
    assert_accounting(r)


def test_unbounded_queue_with_client_timeouts_wastes_work() -> None:
    # Overloaded with no queue limit: the server stays 100% busy finishing requests whose
    # clients already gave up, so goodput collapses. The analysis must say so.
    r = run_simulation(
        single_service(rps=150, workers=1, client_timeout_ms=200, durationS=20, warmupS=2)
    )
    svc = node_by_id(r, "svc")["summary"]
    assert svc["utilization"] > 0.97
    assert svc["counters"]["wasted"] > 0.5 * svc["completed"]
    assert r["summary"]["successRate"] < 0.2
    assert any(f["kind"] == "wasted_work" and f["nodeId"] == "svc" for f in r["findings"])

    # shedding load with a queue timeout below the client timeout restores goodput
    shed = run_simulation(
        single_service(
            rps=150, workers=1, client_timeout_ms=200, queue_timeout_ms=100, durationS=20, warmupS=2
        )
    )
    assert shed["summary"]["throughputRps"] > 80
    assert node_by_id(shed, "svc")["summary"]["counters"].get("wasted", 0) == 0


def test_more_replicas_increase_capacity() -> None:
    base = dict(rps=180, workers=1, queue_capacity=20, durationS=30, warmupS=5)
    one = run_simulation(single_service(replicas=1, **base))
    three = run_simulation(single_service(replicas=3, **base))
    assert three["summary"]["throughputRps"] > one["summary"]["throughputRps"] * 1.6
    assert three["summary"]["successRate"] > 0.99 > one["summary"]["successRate"]
    assert three["summary"]["latencyMs"]["p95"] < one["summary"]["latencyMs"]["p95"]
    assert (
        node_by_id(three, "svc")["summary"]["utilization"]
        < node_by_id(one, "svc")["summary"]["utilization"]
    )


def test_high_cache_hit_ratio_reduces_db_load_and_latency(starter: dict[str, Any]) -> None:
    low = run_simulation(short(starter), scenario_id="cache-hit-20")
    high = run_simulation(short(starter), scenario_id="cache-hit-95")
    db_low = node_by_id(low, "db")["summary"]
    db_high = node_by_id(high, "db")["summary"]
    assert db_high["counters"]["reads"] < db_low["counters"]["reads"] / 5
    assert node_by_id(high, "cache")["summary"]["hitRatio"] == pytest.approx(0.95, abs=0.02)
    assert node_by_id(low, "cache")["summary"]["hitRatio"] == pytest.approx(0.20, abs=0.02)
    assert high["summary"]["latencyMs"]["p50"] < low["summary"]["latencyMs"]["p50"]
    assert high["summary"]["latencyMs"]["p95"] < low["summary"]["latencyMs"]["p95"]
    assert_accounting(low)
    assert_accounting(high)


def test_writes_bypass_cache_hits_and_reach_database(starter: dict[str, Any]) -> None:
    r = run_simulation(short(starter))
    db = node_by_id(r, "db")["summary"]["counters"]
    cache = node_by_id(r, "cache")["summary"]["counters"]
    assert db["writes"] > 0
    assert cache["invalidations"] > 0  # writes invalidate after the DB write
    # every DB read is a cache miss
    assert db["reads"] <= cache["misses"] + 5  # window edges may split a miss from its read


def test_seed_determinism_and_sensitivity(starter: dict[str, Any]) -> None:
    a = run_simulation(short(starter))
    b = run_simulation(short(starter))
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]
    assert a["summary"] == b["summary"]
    other = short(starter)
    other["workload"]["seed"] = 43
    c = run_simulation(other)
    assert c["provenance"]["resultDigest"] != a["provenance"]["resultDigest"]
    assert c["provenance"]["inputDigest"] != a["provenance"]["inputDigest"]


def test_dependency_timeout_wastes_downstream_work() -> None:
    d = doc(
        [
            node("clients", "traffic_source", timeoutMs=10_000),
            node(
                "api",
                "service",
                replicas=1,
                workersPerReplica=8,
                serviceTimeMs={"kind": "constant", "value": 1},
            ),
            node(
                "db",
                "database",
                maxConnections=2,
                queueTimeoutMs=None,
                readServiceTimeMs={"kind": "constant", "value": 50},
            ),
        ],
        [
            edge("c-a", "clients", "api"),
            edge("a-d", "api", "db", timeoutMs=20),
        ],
        rps=20,
        durationS=10,
        warmupS=1,
    )
    r = run_simulation(d)
    s = r["summary"]
    assert s["successRate"] == 0
    assert s["failureBreakdown"][0]["reason"] == "dependency_timeout"
    assert s["failureBreakdown"][0]["nodeId"] == "api"
    db = node_by_id(r, "db")["summary"]
    assert db["completed"] > 0  # the database kept working on abandoned queries
    assert next(e for e in r["edges"] if e["id"] == "a-d")["timeouts"] > 0
    assert_accounting(r)


def test_retries_are_bounded_and_amplify_load() -> None:
    d = single_service(
        rps=200, workers=1, queue_capacity=0, max_attempts=3, durationS=10, warmupS=1
    )
    r = run_simulation(d)
    s = r["summary"]
    assert 1.5 < s["retryAmplification"] <= 3.0
    assert s["attempts"] <= 3 * r["summary"]["accounting"]["measured"]["arrived"] + 3
    assert any(f["kind"] == "retries" for f in r["findings"])
    assert_accounting(r)


def test_blocking_vs_async_workers() -> None:
    def build(blocking: bool) -> dict[str, Any]:
        return doc(
            [
                node("clients", "traffic_source"),
                node(
                    "api",
                    "service",
                    replicas=1,
                    workersPerReplica=10,
                    blockingDownstream=blocking,
                    serviceTimeMs={"kind": "constant", "value": 2},
                ),
                node(
                    "db",
                    "database",
                    maxConnections=50,
                    readServiceTimeMs={"kind": "constant", "value": 30},
                ),
            ],
            [edge("c-a", "clients", "api"), edge("a-d", "api", "db")],
            rps=200,
            durationS=10,
            warmupS=2,
        )

    blocking = run_simulation(build(True))
    nonblocking = run_simulation(build(False))
    ub = node_by_id(blocking, "api")["summary"]
    un = node_by_id(nonblocking, "api")["summary"]
    # blocking: 200/s x 32 ms = 6.4 busy workers of 10; async: 200/s x 2 ms = 0.4
    assert ub["utilization"] == pytest.approx(0.64, abs=0.08)
    assert un["utilization"] == pytest.approx(0.04, abs=0.02)
    assert ub["cpuUtilization"] == pytest.approx(un["cpuUtilization"], abs=0.02)


def test_least_connections_favours_faster_target() -> None:
    def build(policy: str) -> dict[str, Any]:
        return doc(
            [
                node("clients", "traffic_source"),
                node("lb", "load_balancer", policy=policy),
                node(
                    "fast",
                    "service",
                    replicas=1,
                    workersPerReplica=4,
                    serviceTimeMs={"kind": "exponential", "mean": 5},
                ),
                node(
                    "slow",
                    "service",
                    replicas=1,
                    workersPerReplica=4,
                    serviceTimeMs={"kind": "exponential", "mean": 40},
                ),
            ],
            [edge("c-l", "clients", "lb"), edge("l-f", "lb", "fast"), edge("l-s", "lb", "slow")],
            rps=100,
            durationS=20,
            warmupS=2,
        )

    rr = run_simulation(build("round_robin"))
    lc = run_simulation(build("least_connections"))
    rr_share = node_by_id(rr, "fast")["summary"]["arrivals"] / rr["summary"]["arrived"]
    lc_share = node_by_id(lc, "fast")["summary"]["arrivals"] / lc["summary"]["arrived"]
    assert rr_share == pytest.approx(0.5, abs=0.03)
    assert lc_share > 0.6
    assert lc["summary"]["latencyMs"]["mean"] < rr["summary"]["latencyMs"]["mean"]


def test_ttl_cache_warms_up_and_longer_ttl_hits_more() -> None:
    def build(ttl: float) -> dict[str, Any]:
        return doc(
            [
                node("clients", "traffic_source"),
                node(
                    "api",
                    "service",
                    replicas=2,
                    workersPerReplica=16,
                    serviceTimeMs={"kind": "constant", "value": 1},
                ),
                node("cache", "cache", hitModel="ttl_keyspace", ttlSeconds=ttl, concurrency=4),
                node("db", "database", maxConnections=50),
            ],
            [
                edge("c-a", "clients", "api"),
                edge("a-c", "api", "cache", request_types=["read"]),
                edge("c-d", "cache", "db", source_port="miss", request_types=["read"]),
            ],
            rps=300,
            durationS=30,
            warmupS=5,
            keyCount=5000,
            keySkew=1.0,
        )

    short_ttl = node_by_id(run_simulation(build(1)), "cache")
    long_ttl = node_by_id(run_simulation(build(60)), "cache")
    assert long_ttl["summary"]["hitRatio"] > short_ttl["summary"]["hitRatio"] + 0.15
    series = [v for v in long_ttl["timeseries"]["hitRatio"] if v is not None]
    assert series[0] < series[-1]  # cold start: hit ratio climbs as keys fill


def test_burst_pattern_raises_offered_load_during_burst(starter: dict[str, Any]) -> None:
    r = run_simulation(starter, scenario_id="traffic-burst")
    ts = r["timeseries"]
    t, offered = ts["t"], ts["offeredRps"]
    during = [o for tt, o in zip(t, offered, strict=True) if 31 <= tt < 39]
    before = [o for tt, o in zip(t, offered, strict=True) if 15 <= tt < 29]
    assert sum(during) / len(during) > 2 * sum(before) / len(before)
    assert node_by_id(r, "api")["summary"]["peakUtilization"] >= 0.95


def test_ramp_and_deterministic_arrivals() -> None:
    d = single_service(
        rps=0,
        workers=8,
        durationS=20,
        warmupS=0.5,
        arrivalProcess="deterministic",
        pattern={"kind": "ramp", "startRps": 50, "endRps": 150},
    )
    r = run_simulation(d)
    expected = (50 + 150) / 2 * 20
    assert r["provenance"]["requestsGenerated"] == pytest.approx(expected, rel=0.03)
    ts = r["timeseries"]
    early = [o for t, o in zip(ts["t"], ts["offeredRps"], strict=True) if t < 3]
    late = [o for t, o in zip(ts["t"], ts["offeredRps"], strict=True) if 17 <= t < 20]
    assert sum(late) / len(late) > 2 * sum(early) / len(early)


def test_limits_reject_oversized_runs() -> None:
    with pytest.raises(SimulationLimitError, match="Reduce RPS or duration"):
        run_simulation(single_service(rps=50_000, durationS=3600, warmupS=10))


def test_wall_clock_limit() -> None:
    with pytest.raises(SimulationLimitError, match="wall-clock"):
        run_simulation(
            single_service(rps=2000, workers=50, durationS=120, warmupS=1),
            limits=SimulationLimits(max_wall_seconds=0.0),
        )


def test_cancellation_and_progress() -> None:
    seen: list[float] = []
    with pytest.raises(SimulationCancelledError):
        run_simulation(
            single_service(rps=100, durationS=20, warmupS=1),
            progress=seen.append,
            should_cancel=lambda: len(seen) >= 3,
        )
    assert seen == sorted(seen) and 0 < seen[-1] < 1

    progress: list[float] = []
    run_simulation(single_service(rps=50, durationS=5, warmupS=1), progress=progress.append)
    assert progress[-1] == 1.0
    assert progress == sorted(progress)
