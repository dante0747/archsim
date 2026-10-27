"""Static analysis that runs before a simulation: the load estimate, auto-sizing, the cost estimate
and the design review (ported from the web app, which now asks the API for them)."""

from __future__ import annotations

from typing import Any

import pytest

from archsim_engine import default_registry, load_document
from archsim_engine.cost import (
    HOURS_PER_MONTH,
    SECONDS_PER_MONTH,
    estimate_cost,
    hourly_price,
    instances_of,
    size_price_per_hour,
)
from archsim_engine.load import estimate_load, peak_rps
from archsim_engine.review import count_by_severity, review_design
from archsim_engine.scenarios import normalize_document
from archsim_engine.sizing import apply_auto_sizes, auto_sizes

REG = default_registry()
C = lambda v: {"kind": "constant", "value": v}  # noqa: E731


def n(id_: str, type_: str, label: str | None = None, **config: Any) -> dict[str, Any]:
    return {"id": id_, "type": type_, "label": label or id_, "config": config}


def e(
    source: str, target: str, types: list[str] | None = None, port: str = "out", **config: Any
) -> dict[str, Any]:
    cfg = {
        "requestTypes": types or ["read", "write"],
        "latencyMs": C(0.5),
        "maxAttempts": 1,
        "retryBackoffMs": 50,
    }
    cfg.update(config)
    return {
        "id": f"{source}-{target}",
        "source": source,
        "sourcePort": port,
        "target": target,
        "targetPort": "in",
        "config": cfg,
    }


def norm(
    nodes: list[dict[str, Any]], edges: list[dict[str, Any]], **workload: Any
) -> dict[str, Any]:
    wl = {
        "rps": 100,
        "durationS": 60,
        "warmupS": 10,
        "seed": 42,
        "readRatio": 0.9,
        "keyCount": 100_000,
        "keySkew": 0.99,
    }
    wl.update(workload)
    raw = {
        "schemaVersion": "1.0",
        "kind": "archsim.architecture",
        "architecture": {"nodes": nodes, "edges": edges},
        "workload": wl,
    }
    return normalize_document(load_document(raw), REG)


# -- load estimate ----------------------------------------------------------------------------


def test_peak_rps_takes_the_highest_rate_of_the_pattern() -> None:
    base = {"rps": 1000}
    assert peak_rps(base) == 1000
    assert peak_rps({**base, "pattern": {"kind": "ramp", "startRps": 100, "endRps": 1500}}) == 1500
    assert (
        peak_rps(
            {**base, "pattern": {"kind": "burst", "burstRps": 4000, "startS": 10, "durationS": 5}}
        )
        == 4000
    )


def test_load_spreads_over_balancer_targets_follows_links_and_applies_cache_hits() -> None:
    nodes = [
        n("clients", "traffic_source"),
        n("lb", "load_balancer"),
        n("big", "service", replicas=3),
        n("small", "service", replicas=1),
        n("cache", "cache", hitModel="fixed_ratio", hitRatio=0.75),
        n("db", "database"),
    ]
    edges = [
        e("clients", "lb"),
        e("lb", "big"),
        e("lb", "small"),
        e("big", "cache", ["read"]),
        e("big", "db", ["write"]),
        e("cache", "db", ["read"], "miss"),
    ]
    load = estimate_load(norm(nodes, edges, rps=1000, readRatio=0.8), REG)
    expect = {
        "lb": (800, 200),
        "big": (600, 150),
        "small": (200, 50),
        "cache": (600, 0),
        "db": (150, 150),
    }
    for nid, (read, write) in expect.items():
        got = load[nid]
        assert got is not None
        assert got.read == pytest.approx(read) and got.write == pytest.approx(write), nid
    ttl = [n("cache", "cache", hitModel="ttl_keyspace") if x["id"] == "cache" else x for x in nodes]
    assert estimate_load(norm(ttl, edges, rps=1000), REG)["db"] is None
    assert estimate_load(norm(ttl, edges, rps=1000, readRatio=1), REG, ttl_miss_share=1.0)[
        "db"
    ].read == pytest.approx(750)  # type: ignore[union-attr]


def test_load_splits_by_client_share() -> None:
    two = [n("m", "mobile_app"), n("w", "web_client"), n("api", "service")]
    load = estimate_load(norm(two, [e("m", "api"), e("w", "api")], rps=1000, readRatio=1), REG)
    assert load["m"].read == pytest.approx(1000 * 50 / 85)  # type: ignore[union-attr]
    assert load["api"].read == pytest.approx(1000)  # type: ignore[union-attr]


# -- auto-sizing ------------------------------------------------------------------------------


def test_service_cpu_is_sized_for_its_peak_share_and_memory_for_what_it_holds() -> None:
    d = norm(
        [
            n("clients", "traffic_source"),
            n(
                "api",
                "service",
                replicas=2,
                workersPerReplica=8,
                queueCapacity=50,
                serviceTimeMs=C(10),
            ),
        ],
        [e("clients", "api")],
        rps=300,
    )
    s = auto_sizes(d, REG)["api"]
    # 150 req/s per replica x 10 ms = 1.5 cores busy; / 0.6 = 2.5 -> 4 vCPU
    assert s.cpu_cores == 4
    # 256 MiB + (8 + 50) x 2 MiB = 372 MiB, x 1.25 = 465 MiB -> 0.5 GiB, which no machine with
    # 4 vCPU has: rounded up to the cheapest standard shape, 4 vCPU and 8 GiB (2 GiB per vCPU)
    assert "= 372 MiB" in s.why["memoryGib"]
    assert s.memory_gib == 8
    assert "rounded up to a standard shape, 4 vCPU and 8 GiB" in s.why["memoryGib"]
    assert s.storage_gib is None
    assert "150 req/s per replica" in s.why["cpuCores"]


def test_autoscaling_full_scale_worker_cap_and_floor() -> None:
    sizes = auto_sizes(
        norm(
            [
                n("clients", "traffic_source"),
                n("lb", "load_balancer"),
                n(
                    "hpa",
                    "service",
                    autoscaling=True,
                    replicas=2,
                    maxReplicas=10,
                    workersPerReplica=16,
                    serviceTimeMs=C(20),
                ),
                n("narrow", "service", replicas=1, workersPerReplica=3, serviceTimeMs=C(50)),
                n("idle", "service"),
            ],
            [e("clients", "lb"), e("lb", "hpa", ["read"]), e("lb", "narrow", ["write"])],
            rps=1000,
            readRatio=0.5,
        ),
        REG,
    )
    assert sizes["hpa"].cpu_cores == 2  # 500 req/s over 10 replicas x 20 ms = 1 core / 0.6 -> 2
    # 25 cores would be needed, but 3 workers can only use 3 (never fewer: a common size of 2
    # would make the CPU the bottleneck below the workers); the machine is then the standard
    # shape that holds them, 4 vCPU
    assert sizes["narrow"].cpu_cores == 4
    assert "capped at 3" in sizes["narrow"].why["cpuCores"]
    assert "rounded up to a standard shape, 4 vCPU" in sizes["narrow"].why["cpuCores"]
    assert (sizes["idle"].cpu_cores, sizes["idle"].memory_gib, sizes["idle"].peak_rps) == (
        1,
        2,
        0,
    )


def test_databases_are_sized_from_the_busiest_primary_working_set_and_data() -> None:
    sizes = auto_sizes(
        norm(
            [
                n("clients", "traffic_source"),
                n(
                    "db",
                    "database",
                    readReplicas=1,
                    readServiceTimeMs=C(4),
                    writeServiceTimeMs=C(10),
                    cpuShare=0.5,
                    workingSetGib=30,
                    dataSizeGib=140,
                    shards=2,
                ),
                n("plain", "database"),
                n(
                    "es",
                    "database",
                    technology="elasticsearch",
                    shards=6,
                    readServiceTimeMs=C(6),
                    cpuShare=0.5,
                ),
            ],
            [e("clients", "db"), e("clients", "plain"), e("clients", "es", ["read"])],
            rps=2000,
            readRatio=0.8,
        ),
        REG,
    )
    db = sizes["db"]
    assert (db.cpu_cores, db.memory_gib, db.storage_gib) == (4, 32, 100)
    assert sizes["plain"].storage_gib == 10
    assert "set the data size" in sizes["plain"].why["storageGib"]
    assert sizes["plain"].memory_gib == 4 * (sizes["plain"].cpu_cores or 0)
    # every search hits all 6 shards: 1600 x 6 ms x 0.5 = 4.8 cores per shard / 0.6 = 8
    assert sizes["es"].cpu_cores == 8
    assert "all 6 shards" in sizes["es"].why["cpuCores"]


def test_cache_memory_kafka_storage_and_managed_services() -> None:
    sizes = auto_sizes(
        norm(
            [
                n("clients", "traffic_source"),
                n("cache", "cache", itemSizeKb=1),
                n("log", "message_queue", technology="kafka", brokers=3, messageSizeKb=1),
                n("sqs", "message_queue", technology="sqs"),
            ],
            [
                e("clients", "cache", ["read"]),
                e("clients", "log", ["write"]),
                e("clients", "sqs", ["write"]),
            ],
            rps=100,
            readRatio=0.5,
            keyCount=1_000_000,
        ),
        REG,
    )
    assert sizes["cache"].memory_gib == 2  # 1M x 1 KiB / 0.8 x 1.25 = 1.49 GiB
    assert sizes["log"].storage_gib == 50  # 50 msg/s x 1 KiB x 7 days x 3 copies / 3 brokers / 0.7
    assert "managed" in (sizes["sqs"].skipped or "")
    assert sizes["sqs"].cpu_cores is None


def test_apply_fills_only_empty_specs_and_can_be_switched_off() -> None:
    d = norm(
        [n("clients", "traffic_source"), n("api", "service", cpuCores=3, serviceTimeMs=C(5))],
        [e("clients", "api")],
    )
    filled, sizes = apply_auto_sizes(d, REG)
    api = next(x for x in filled["architecture"]["nodes"] if x["id"] == "api")
    assert api["config"]["cpuCores"] == 3  # set by the user: kept
    # auto memory follows the 3 vCPU set: the smallest standard ratio, 2 GiB per vCPU
    assert api["config"]["memoryGib"] == sizes["api"].memory_gib == 6
    off = norm([n("clients", "traffic_source"), n("api", "service")], [e("clients", "api")])
    off["architecture"]["autoSize"] = False
    kept, none = apply_auto_sizes(off, REG)
    assert none == {} and kept["architecture"]["nodes"][1]["config"]["cpuCores"] is None


# -- cost -------------------------------------------------------------------------------------


def _defaults(t: str) -> dict[str, Any]:
    return REG.get(t).default_config()


def test_size_and_hourly_prices() -> None:
    assert size_price_per_hour(2, 8) == pytest.approx(0.0964)
    assert size_price_per_hour(4, 16, 730) == pytest.approx(
        4 * 0.037 + 16 * 0.0028 + 0.08, abs=1e-4
    )
    assert size_price_per_hour(None, None, 100) is None
    default = _defaults("service")["costPerHour"]
    assert hourly_price(
        n("api", "service", costPerHour=default + 0.1, cpuCores=2, memoryGib=8), REG
    ) == (default + 0.1, "set")
    assert hourly_price(n("api", "service", cpuCores=2, memoryGib=8), REG) == (0.0964, "size")
    assert hourly_price(n("api", "service"), REG) == (default, "default")
    # stored documents carry every default: a price equal to it counts as not set
    assert hourly_price(n("api", "service", costPerHour=default, cpuCores=2, memoryGib=8), REG) == (
        0.0964,
        "size",
    )


def test_instances() -> None:
    assert instances_of(n("db", "database", shards=4, readReplicas=2), REG)[0] == 12
    assert instances_of(n("db", "database", shards=3, replicationFactor=3), REG) == (
        9,
        "3 shards × 3 nodes",
    )
    assert instances_of(
        n("api", "service", replicas=2), REG, {"timeseries": {"replicas": [2, 4, None, 6]}}
    ) == (4, "avg 4 active instances")
    assert instances_of(n("c", "traffic_source"), REG) == (0, "not billed")
    assert instances_of(n("x", "external_api"), REG)[1] == "per call"


def test_estimate_sums_a_730_hour_month_and_prices_calls_at_the_measured_rate() -> None:
    svc, dbp = _defaults("service")["costPerHour"], _defaults("database")["costPerHour"]
    nodes = [
        n("clients", "traffic_source"),
        n("api", "service", replicas=3),
        n("db", "database"),
        n("pay", "external_api", costPerMillionRequests=10),
    ]
    c = estimate_cost(nodes, REG)
    assert c["monthly"] == pytest.approx((3 * svc + dbp) * HOURS_PER_MONTH)
    assert c["perMillionRequests"] is None
    assert [x["id"] for x in c["byNode"]][-1] == "pay"
    assert any("run a simulation" in x for x in c["notes"])
    report = {
        "summary": {"throughputRps": 100},
        "nodes": [{"id": "pay", "summary": {"arrivalRps": 10}, "timeseries": {}}],
    }
    r = estimate_cost(nodes, REG, report)
    external = 10 * SECONDS_PER_MONTH * 10 / 1e6
    assert next(x for x in r["byNode"] if x["id"] == "pay")["monthly"] == pytest.approx(external)
    assert r["perMillionRequests"] == pytest.approx(r["monthly"] / (100 * SECONDS_PER_MONTH / 1e6))


def test_kubernetes_cluster_billed_per_node_and_its_services_at_nothing() -> None:
    nodes = [
        n("k8s", "kubernetes_cluster", nodes=4, costPerHour=0.5),
        n("api", "service", platform="kubernetes", replicas=6),
        n("vm", "service", replicas=1, costPerHour=0.2),
    ]
    by = {x["id"]: x for x in estimate_cost(nodes, REG)["byNode"]}
    assert by["k8s"]["monthly"] == pytest.approx(4 * 0.5 * HOURS_PER_MONTH)
    assert by["api"]["monthly"] == 0 and by["api"]["basis"] == "runs on k8s"
    assert by["vm"]["monthly"] == pytest.approx(0.2 * HOURS_PER_MONTH)
    assert instances_of(
        n("k8s", "kubernetes_cluster", nodes=2), REG, {"timeseries": {"nodes": [2, 4, 6]}}
    ) == (4, "avg 4 nodes")


# -- design review ----------------------------------------------------------------------------


def base_design(
    api: dict[str, Any] | None = None,
    edges: dict[str, dict[str, Any]] | None = None,
    **workload: Any,
) -> dict[str, Any]:
    o = edges or {}
    nodes = [
        n("clients", "traffic_source", "Clients", timeoutMs=3000, maxAttempts=1),
        n("lb", "load_balancer", "Load Balancer"),
        n("api", "service", "API", **{"replicas": 3, **(api or {})}),
        n("cache", "cache", "Redis"),
        n("db", "database", "Postgres", readReplicas=1),
    ]
    es = [
        e("clients", "lb", **o.get("clients-lb", {})),
        e("lb", "api", **{"timeoutMs": 2000, **o.get("lb-api", {})}),
        e("api", "cache", ["read"], **{"timeoutMs": 50, **o.get("api-cache", {})}),
        e("api", "db", ["write"], **{"timeoutMs": 500, **o.get("api-db", {})}),
    ]
    return norm(nodes, es, **workload)


def rule(fs: list[dict[str, Any]], r: str) -> list[dict[str, Any]]:
    return [f for f in fs if f["rule"] == r]


def test_review_single_points_of_failure() -> None:
    fs = review_design(base_design({"replicas": 1}), REG)
    spof = next(f for f in rule(fs, "spof") if f["nodeIds"][0] == "api")
    assert (spof["severity"], spof["title"]) == ("high", "API runs a single replica")
    assert not any(
        f["nodeIds"][0] == "api" for f in rule(review_design(base_design(), REG), "spof")
    )


def test_review_timeouts_retries_and_inversion() -> None:
    mt = rule(
        review_design(
            base_design(edges={"api-db": {"timeoutMs": None}, "api-cache": {"timeoutMs": None}}),
            REG,
        ),
        "missing-timeout",
    )
    assert len(mt) == 1 and mt[0]["severity"] == "medium" and "2 dependencies" in mt[0]["title"]
    amp = rule(
        review_design(
            base_design(edges={"lb-api": {"maxAttempts": 3}, "api-db": {"maxAttempts": 3}}), REG
        ),
        "retry-amplification",
    )
    assert len(amp) == 1 and amp[0]["severity"] == "high"
    assert (
        "3 × 3 = 9" in amp[0]["detail"]
        and amp[0]["title"] == "Up to 9 attempts reach Postgres per request"
    )
    inv = rule(
        review_design(
            base_design(
                edges={
                    "lb-api": {"timeoutMs": 400},
                    "api-db": {"timeoutMs": 300, "maxAttempts": 2, "retryBackoffMs": 50},
                }
            ),
            REG,
        ),
        "timeout-inversion",
    )
    assert "lb-api" in [f["edgeIds"][0] for f in inv] and "650 ms" in inv[0]["detail"]
    assert "retry-no-backoff" in [
        f["rule"]
        for f in review_design(
            base_design(edges={"api-db": {"maxAttempts": 2, "retryBackoffMs": 0}}), REG
        )
    ]


def test_review_pools_storms_queues_caches_shards_and_the_edge() -> None:
    d = base_design({"replicas": 10, "workersPerReplica": 8})
    next(x for x in d["architecture"]["nodes"] if x["id"] == "db")["config"]["maxConnections"] = 10
    pool = rule(review_design(d, REG), "pool-exhaustion")[0]
    assert (
        pool["severity"] == "medium"
        and "80 blocking workers" in pool["detail"]
        and "accepts 20 connections" in pool["detail"]
    )
    storm = rule(
        review_design(base_design({"platform": "serverless", "concurrencyLimit": 1000}), REG),
        "connection-storm",
    )[0]
    assert storm["severity"] == "high" and "1,000 connections" in storm["title"]
    fs = review_design(
        norm(
            [
                n("clients", "traffic_source", "Clients"),
                n("api", "service", "API", replicas=2),
                n(
                    "mq",
                    "message_queue",
                    "Kafka",
                    maxDepth=None,
                    partitions=3,
                    consumers=6,
                    brokers=1,
                    technology="kafka",
                ),
                n("cache", "cache", "Redis", hitRatio=0.995),
                n("db", "database", "Mongo", shards=8, readReplicas=1),
            ],
            [
                e("clients", "api"),
                e("api", "mq", ["write"], timeoutMs=100),
                e("api", "cache", ["read"], timeoutMs=20),
                e("api", "db", ["write"], timeoutMs=200),
            ],
        ),
        REG,
    )
    rules = {f["rule"] for f in fs}
    assert {
        "queue-unbounded",
        "queue-sink",
        "queue-partitions",
        "optimistic-cache",
        "hot-partition",
        "edge-rate-limit",
        "cache-dependency",
    } <= rules
    assert any(f["nodeIds"][0] == "mq" for f in rule(fs, "spof"))
    order = [f["severity"] for f in fs]
    assert order == sorted(order, key=["high", "medium", "low"].index)
    assert sum(count_by_severity(fs).values()) == len(fs) == len({f["id"] for f in fs})


def test_review_resource_specs() -> None:
    svc = {"cpuCores": 1, "serviceTimeMs": C(20)}
    over = rule(review_design(base_design(svc, rps=400), REG), "cpu-capacity")
    assert (
        len(over) == 1
        and over[0]["severity"] == "high"
        and over[0]["title"] == "API needs more CPU at peak"
    )
    assert "about 150 req/s" in over[0]["detail"]
    assert (
        rule(review_design(base_design(svc, rps=130), REG), "cpu-capacity")[0]["severity"]
        == "medium"
    )
    assert not rule(review_design(base_design(svc, rps=50), REG), "cpu-capacity")
    d = base_design()
    db = next(x for x in d["architecture"]["nodes"] if x["id"] == "db")
    db["config"].update(memoryGib=8, workingSetGib=64)
    cache = next(x for x in d["architecture"]["nodes"] if x["id"] == "cache")
    cache["config"].update(hitModel="ttl_keyspace", memoryGib=0.01, itemSizeKb=1)
    fs = review_design(d, REG)
    assert (
        rule(fs, "buffer-cache")[0]["severity"] == "medium"
        and "91% of reads go to disk" in rule(fs, "buffer-cache")[0]["detail"]
    )
    assert rule(fs, "cache-capacity")[0]["title"] == "Redis holds 8,388 of 100,000 keys"


def test_review_path_walk_is_bounded_on_layered_fan_out() -> None:
    # 11 layers of 3 services, each calling every service of the next: 3^10 paths
    nodes = [n("clients", "traffic_source"), n("gw", "load_balancer")]
    edges = [e("clients", "gw")]
    prev = ["gw"]
    for layer in range(11):
        cur = [f"s{layer}_{i}" for i in range(3)]
        nodes += [n(x, "service", replicas=2) for x in cur]
        edges += [e(a, b, maxAttempts=2) for a in prev for b in cur]
        prev = cur
    fs = review_design(norm(nodes, edges), REG)
    assert rule(fs, "retry-amplification") and rule(fs, "deep-chain")
