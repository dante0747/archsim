"""Time-varying behaviour: faults, autoscaling, serverless, async queues, rate limiting."""

from __future__ import annotations

import copy
import math
from typing import Any

import pytest

from archsim_engine import (
    REPORT_VERSION,
    ArchitectureDocument,
    DocumentInvalidError,
    default_registry,
    run_simulation,
    validate_document,
)
from archsim_engine.components.load_balancer import LoadBalancerModel
from archsim_engine.components.message_queue import MessageQueueModel
from archsim_engine.components.service import ServiceModel
from engine_builders import doc, edge, node, node_by_id, short, single_service

CONST = lambda v: {"kind": "constant", "value": v}  # noqa: E731


def bucket_range(r: dict[str, Any], t0: float, t1: float) -> range:
    """Bucket indices fully inside [t0, t1)."""
    b = r["timeseries"]["bucketS"]
    return range(math.ceil(t0 / b), math.floor(t1 / b))


def mean_of(values: list[Any], idx: range) -> float:
    vals = [values[i] for i in idx if values[i] is not None]
    assert vals, "no data in range"
    return sum(vals) / len(vals)


def issues_of(d: dict[str, Any]) -> list[dict[str, Any]]:
    parsed = ArchitectureDocument.model_validate(d)
    return [i.to_dict() for i in validate_document(parsed, default_registry()).issues]


def codes(d: dict[str, Any]) -> dict[str, str]:
    return {i["code"]: i["severity"] for i in issues_of(d)}


def with_faults(d: dict[str, Any], *faults: dict[str, Any]) -> dict[str, Any]:
    d = copy.deepcopy(d)
    d["workload"]["faults"] = list(faults)
    return d


def fault(
    id_: str, kind: str, target: str, start: float, duration: float, **kw: Any
) -> dict[str, Any]:
    return {"id": id_, "kind": kind, "target": target, "startS": start, "durationS": duration, **kw}


# -----------------------------------------------------------------------------------------
# Faults
# -----------------------------------------------------------------------------------------


def test_outage_rejects_only_inside_its_window(starter: dict[str, Any]) -> None:
    d = with_faults(short(starter, duration=40, warmup=4), fault("f1", "outage", "cache", 15, 10))
    r = run_simulation(d)
    cache = node_by_id(r, "cache")
    assert cache["summary"]["rejected"].get("outage", 0) > 0
    assert cache["accounting"]["invariantHolds"]
    err = r["timeseries"]["errorRps"]
    assert mean_of(err, bucket_range(r, 16, 24)) > 50  # reads fail while the cache is down
    assert mean_of(err, bucket_range(r, 5, 14)) == 0
    assert mean_of(err, bucket_range(r, 27, 39)) == 0
    impact = next(f for f in r["findings"] if f["kind"] == "fault_impact")
    ev = impact["evidence"]
    assert impact["severity"] == "critical" and impact["nodeId"] == "cache"
    assert ev["successDuring"] < 0.5 < ev["successBefore"]
    assert ev["recovered"] and "suggestion" in impact
    assert "outage of" in impact["title"] and "15-25 s" in impact["title"]
    assert any(
        f["reason"] == "outage" and f["nodeId"] == "cache" for f in r["summary"]["failureBreakdown"]
    )


def test_slowdown_multiplies_service_time_inside_window() -> None:
    d = single_service(rps=20, service=CONST(5), workers=4, durationS=30, warmupS=2)
    r = run_simulation(with_faults(d, fault("slow", "slowdown", "svc", 10, 10, factor=4)))
    p50 = r["timeseries"]["latencyP50Ms"]
    assert mean_of(p50, bucket_range(r, 11, 19)) == pytest.approx(20, rel=0.05)
    assert mean_of(p50, bucket_range(r, 3, 9)) == pytest.approx(5, rel=0.05)
    assert mean_of(p50, bucket_range(r, 21, 29)) == pytest.approx(5, rel=0.05)
    impact = next(f for f in r["findings"] if f["kind"] == "fault_impact")
    assert impact["evidence"]["p95DuringMs"] > 3 * impact["evidence"]["p95BeforeMs"]


def test_latency_fault_adds_to_both_directions_of_a_link() -> None:
    d = single_service(rps=20, service=CONST(5), workers=4, durationS=30, warmupS=2)
    r = run_simulation(with_faults(d, fault("lat", "latency", "c-s", 10, 10, extraMs=100)))
    p50 = r["timeseries"]["latencyP50Ms"]
    assert mean_of(p50, bucket_range(r, 11, 19)) == pytest.approx(205, rel=0.03)
    assert mean_of(p50, bucket_range(r, 3, 9)) == pytest.approx(5, rel=0.05)


def test_fault_validation_codes(starter: dict[str, Any]) -> None:
    base = short(starter, duration=40, warmup=4)
    assert (
        codes(with_faults(base, fault("a", "outage", "nope", 5, 5)))["fault_unknown_target"]
        == "error"
    )
    assert (
        codes(with_faults(base, fault("a", "latency", "nope", 5, 5)))["fault_unknown_target"]
        == "error"
    )
    assert (
        codes(with_faults(base, fault("a", "latency", "cache", 5, 5)))["fault_wrong_target"]
        == "error"
    )
    assert (
        codes(with_faults(base, fault("a", "outage", "lb-api", 5, 5)))["fault_wrong_target"]
        == "error"
    )
    assert (
        codes(with_faults(base, fault("a", "outage", "clients", 5, 5)))["fault_wrong_target"]
        == "error"
    )
    assert (
        codes(with_faults(base, fault("a", "slowdown", "db", 45, 5)))["fault_outside_run"]
        == "error"
    )
    assert (
        codes(with_faults(base, fault("a", "slowdown", "db", 35, 10)))["fault_clipped"] == "warning"
    )
    assert not issues_of(with_faults(base, fault("a", "latency", "api-cache", 5, 5)))
    with pytest.raises(DocumentInvalidError):
        run_simulation(with_faults(base, fault("a", "outage", "nope", 5, 5)))


def test_duplicate_fault_ids_are_rejected(starter: dict[str, Any]) -> None:
    d = with_faults(starter, fault("a", "outage", "db", 5, 5), fault("a", "outage", "cache", 5, 5))
    with pytest.raises(ValueError, match="unique"):
        ArchitectureDocument.model_validate(d)


# -----------------------------------------------------------------------------------------
# Autoscaling
# -----------------------------------------------------------------------------------------


def autoscaling_doc(**svc: Any) -> dict[str, Any]:
    cfg = {
        "replicas": 2,
        "workersPerReplica": 4,
        "autoscaling": True,
        "minReplicas": 2,
        "maxReplicas": 12,
        "targetUtilization": 0.6,
        "scaleIntervalS": 5,
        "startupS": 5,
        "serviceTimeMs": {"kind": "exponential", "mean": 10},
        "queueCapacity": 500,
        "queueTimeoutMs": None,
    }
    cfg.update(svc)
    return doc(
        [
            node("clients", "traffic_source", timeoutMs=10_000),
            node("lb", "load_balancer", policy="least_connections"),
            node("svc", "service", platform="kubernetes", **cfg),
        ],
        [edge("c-l", "clients", "lb"), edge("l-s", "lb", "svc")],
        rps=100,
        durationS=90,
        warmupS=5,
        pattern={"kind": "ramp", "startRps": 100, "endRps": 2500},
    )


def test_autoscaling_follows_a_ramp_and_reports_replicas() -> None:
    r = run_simulation(autoscaling_doc())
    svc = node_by_id(r, "svc")
    reps = svc["timeseries"]["replicas"]
    assert len(reps) == len(r["timeseries"]["t"])
    assert reps[0] == pytest.approx(2)
    assert max(v for v in reps if v is not None) > 6
    s = svc["summary"]
    assert s["counters"]["scaleUps"] >= 2
    assert s["replicasPeak"] > s["replicasMean"] > 2
    assert 0.3 < s["utilization"] < 0.95
    assert r["summary"]["successRate"] > 0.95
    assert svc["accounting"]["invariantHolds"]
    found = [f for f in r["findings"] if f["kind"] == "autoscaling"]
    assert found and "autoscaled from 2 to" in found[0]["title"]


def test_static_replicas_saturate_where_autoscaling_copes() -> None:
    static = run_simulation(autoscaling_doc(autoscaling=False))
    scaled = run_simulation(autoscaling_doc())
    assert "replicas" not in node_by_id(static, "svc")["timeseries"]
    assert scaled["summary"]["successRate"] > static["summary"]["successRate"]


def test_autoscaling_scales_down_after_load_drops() -> None:
    d = autoscaling_doc(scaleIntervalS=2, startupS=1)
    d["workload"]["pattern"] = {"kind": "burst", "burstRps": 2000, "startS": 10, "durationS": 20}
    d["workload"]["durationS"] = 90
    r = run_simulation(d)
    svc = node_by_id(r, "svc")
    assert svc["summary"]["counters"].get("scaleDowns", 0) >= 1
    assert svc["summary"]["replicasEnd"] < svc["summary"]["replicasPeak"]


def test_autoscaling_range_is_validated() -> None:
    bad = autoscaling_doc(minReplicas=5, maxReplicas=3)
    assert any(
        i["code"] == "node.config_invalid" and "max replicas" in i["message"]
        for i in issues_of(bad)
    )
    clamped = autoscaling_doc(replicas=1, minReplicas=3)
    assert codes(clamped)["service.replicas_outside_range"] == "warning"
    r = run_simulation(short(clamped, duration=20, warmup=2))
    assert node_by_id(r, "svc")["timeseries"]["replicas"][0] == pytest.approx(3)


# -----------------------------------------------------------------------------------------
# Serverless
# -----------------------------------------------------------------------------------------


def serverless_doc(limit: int, rps: float) -> dict[str, Any]:
    return doc(
        [
            node("clients", "traffic_source"),
            node(
                "fn",
                "service",
                platform="serverless",
                concurrencyLimit=limit,
                keepWarmS=300,
                serviceTimeMs=CONST(50),
                coldStartMs=CONST(300),
            ),
        ],
        [edge("c-f", "clients", "fn")],
        rps=rps,
        durationS=20,
        warmupS=2,
        arrivalProcess="deterministic",
    )


def test_serverless_cold_starts_then_warm_reuse() -> None:
    # 40 req/s, 50 ms each: ~2 instances in steady state, but every request arriving during the
    # first 350 ms cold start finds no warm instance and starts its own (~15 instances).
    r = run_simulation(serverless_doc(limit=1000, rps=40))
    fn = node_by_id(r, "fn")
    s = fn["summary"]
    assert 10 <= s["coldStartsTotal"] <= 16
    assert s["counters"].get("coldStarts", 0) == 0  # all during warm-up; warm reuse afterwards
    assert r["summary"]["successRate"] == 1.0
    assert r["summary"]["latencyMs"]["p50"] == pytest.approx(50, rel=0.05)
    assert r["summary"]["latencyMs"]["max"] < 100
    assert "replicas" in fn["timeseries"] and s["replicasPeak"] == s["coldStartsTotal"]
    assert any(f["kind"] == "serverless" for f in r["findings"])


def test_serverless_throttles_at_the_concurrency_limit() -> None:
    r = run_simulation(serverless_doc(limit=5, rps=300))  # needs ~15 concurrent instances
    fn = node_by_id(r, "fn")
    assert fn["summary"]["rejected"].get("throttled", 0) > 0
    assert fn["summary"]["replicasPeak"] == 5
    assert r["summary"]["throughputRps"] == pytest.approx(100, rel=0.1)  # 5 instances x 20/s
    assert fn["accounting"]["invariantHolds"]


def test_serverless_reclaims_idle_instances() -> None:
    d = serverless_doc(limit=100, rps=200)
    d["architecture"]["nodes"][1]["config"]["keepWarmS"] = 2
    d["workload"]["pattern"] = {"kind": "burst", "burstRps": 200, "startS": 2, "durationS": 3}
    d["workload"]["rps"] = 5
    r = run_simulation(d)
    fn = node_by_id(r, "fn")
    reps = [v for v in fn["timeseries"]["replicas"] if v is not None]
    assert max(reps) >= 8 and reps[-1] <= 2  # scaled out for the burst, reclaimed after


# -----------------------------------------------------------------------------------------
# Message queues
# -----------------------------------------------------------------------------------------


def queue_doc(
    consumers: int = 2, consumer_ms: float = 10, worker_cfg: dict[str, Any] | None = None, **q: Any
) -> dict[str, Any]:
    qcfg = {
        "consumers": consumers,
        "partitions": 16,
        "publishTimeMs": CONST(2),
        "consumerTimeMs": CONST(consumer_ms),
    }
    qcfg.update(q)
    return doc(
        [
            node("clients", "traffic_source"),
            node("api", "service", replicas=2, workersPerReplica=8, serviceTimeMs=CONST(2)),
            node("q", "message_queue", **qcfg),
            node(
                "worker",
                "service",
                replicas=1,
                workersPerReplica=8,
                serviceTimeMs=CONST(5),
                **(worker_cfg or {}),
            ),
        ],
        [edge("c-a", "clients", "api"), edge("a-q", "api", "q"), edge("q-w", "q", "worker")],
        rps=300,
        durationS=20,
        warmupS=2,
        arrivalProcess="deterministic",
    )


def test_queue_acks_fast_while_lag_builds_and_drains() -> None:
    r = run_simulation(queue_doc(consumers=2, consumer_ms=10))  # ~133 msg/s capacity < 300/s
    q = node_by_id(r, "q")
    s = q["summary"]
    assert r["summary"]["successRate"] == 1.0
    assert r["summary"]["latencyMs"]["p95"] < 10  # producers only wait for the publish ack
    assert max(v for v in q["timeseries"]["lag"] if v is not None) > 500
    assert s["p95MessageAgeMs"] > 2000 and s["p95QueueWaitMs"] > 2000
    assert s["backlogAtEnd"] == 0 and r["summary"]["accounting"]["drained"]
    total_published = q["accounting"]["completed"]
    worker = node_by_id(r, "worker")
    assert worker["accounting"]["arrivals"] == total_published  # every message reached the worker
    assert q["accounting"]["invariantHolds"] and worker["accounting"]["invariantHolds"]
    assert s["effectiveConsumers"] == 2 and q["summary"]["slots"] == 2
    lag = next(f for f in r["findings"] if f["kind"] == "queue_lag")
    assert "lag peaked" in lag["title"]


def test_queue_with_enough_consumers_has_no_lag() -> None:
    r = run_simulation(queue_doc(consumers=8, consumer_ms=5))
    q = node_by_id(r, "q")
    assert q["summary"]["p95QueueWaitMs"] < 50
    assert q["summary"]["counters"]["processed"] > 0


def test_partitions_cap_consumer_parallelism() -> None:
    d = queue_doc(consumers=8, consumer_ms=10, partitions=2)
    assert codes(d)["queue.idle_consumers"] == "warning"
    r = run_simulation(d)
    assert node_by_id(r, "q")["summary"]["effectiveConsumers"] == 2


def test_queue_rejects_when_full_and_dead_letters_failures() -> None:
    full = run_simulation(queue_doc(consumers=1, consumer_ms=50, maxDepth=100))
    q = node_by_id(full, "q")
    assert q["summary"]["rejected"].get("queue_full", 0) > 0
    assert full["summary"]["successRate"] < 1.0

    d = with_faults(
        queue_doc(consumers=4, consumer_ms=1, maxDeliveries=2),
        fault("down", "outage", "worker", 8, 4),
    )
    r = run_simulation(d)
    c = node_by_id(r, "q")["summary"]["counters"]
    assert c["failedDeliveries"] > 0 and c["deadLettered"] > 0
    assert r["summary"]["successRate"] == 1.0  # producers never see consumer-side failures


def test_queue_without_consumers_warns() -> None:
    d = queue_doc()
    d["architecture"]["edges"] = [e for e in d["architecture"]["edges"] if e["id"] != "q-w"]
    d["architecture"]["nodes"] = [n for n in d["architecture"]["nodes"] if n["id"] != "worker"]
    assert codes(d)["queue.no_consumers"] == "warning"
    r = run_simulation(d)
    assert node_by_id(r, "q")["summary"]["counters"]["processed"] > 0


# -----------------------------------------------------------------------------------------
# Gateways, report additions, determinism, catalog
# -----------------------------------------------------------------------------------------


def test_rate_limit_rejects_above_the_limit() -> None:
    d = doc(
        [
            node("clients", "traffic_source"),
            node("gw", "load_balancer", rateLimitRps=100),
            node("svc", "service", replicas=4, workersPerReplica=8),
        ],
        [edge("c-g", "clients", "gw"), edge("g-s", "gw", "svc")],
        rps=300,
        durationS=20,
        warmupS=2,
    )
    r = run_simulation(d)
    gw = node_by_id(r, "gw")
    assert gw["summary"]["rejected"]["rate_limited"] > 0
    assert r["summary"]["throughputRps"] == pytest.approx(100, rel=0.05)
    assert any(f["reason"] == "rate_limited" for f in r["summary"]["failureBreakdown"])


def test_edge_timeseries_and_report_version(starter: dict[str, Any]) -> None:
    r = run_simulation(short(starter))
    assert r["reportVersion"] == REPORT_VERSION == "1.2"
    n = len(r["timeseries"]["t"])
    for e in r["edges"]:
        ts = e["timeseries"]
        assert set(ts) == {"callRps", "timeoutRps", "failureRps"}
        assert all(len(v) == n for v in ts.values())
    lb_api = next(e for e in r["edges"] if e["id"] == "lb-api")
    measured = bucket_range(r, r["window"]["warmupS"], r["window"]["durationS"])
    assert mean_of(lb_api["timeseries"]["callRps"], measured) == pytest.approx(
        lb_api["callRps"], rel=0.05
    )


def test_new_features_are_deterministic(starter: dict[str, Any]) -> None:
    d = with_faults(
        short(starter, duration=30, warmup=4), fault("f", "slowdown", "db", 10, 5, factor=3)
    )
    for d2 in (d, autoscaling_doc(), queue_doc(), serverless_doc(limit=10, rps=150)):
        a, b = run_simulation(d2), run_simulation(d2)
        assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]


@pytest.mark.parametrize("model", [ServiceModel, LoadBalancerModel, MessageQueueModel])
def test_presets_are_valid_configs(model: type) -> None:
    m = model()
    assert m.presets, "every v2 component ships technology presets"
    for p in m.presets:
        cfg = m.parse_config({**m.default_config(), **p.config})
        assert cfg.technology == p.technology  # type: ignore[attr-defined]
    entry = m.catalog_entry()
    assert entry["presets"] and {f["key"] for f in entry["fields"]} >= {"technology", "costPerHour"}
