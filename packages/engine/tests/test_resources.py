"""Resource specs: CPU cores, memory and storage, and their effect on the simulation."""

from __future__ import annotations

from typing import Any

import pytest
from test_engine_behaviour import assert_accounting
from test_validation import codes

from archsim_engine import run_simulation
from engine_builders import doc, edge, node, node_by_id, single_service

CONST = {"kind": "constant", "value": 10}


def svc_doc(rps: float, **svc: Any) -> dict[str, Any]:
    d = single_service(rps=rps, service=CONST, workers=8, durationS=30, warmupS=5)
    d["architecture"]["nodes"][1]["config"].update(svc)
    return d


def test_without_specs_nothing_changes() -> None:
    plain = run_simulation(svc_doc(300))
    # as many whole cores as workers: no contention, same numbers
    roomy = run_simulation(svc_doc(300, cpuCores=8, memoryGib=64))
    assert plain["summary"]["latencyMs"] == roomy["summary"]["latencyMs"]
    assert plain["summary"]["throughputRps"] == roomy["summary"]["throughputRps"]
    assert "cpuCoreUtilization" not in node_by_id(plain, "svc")["summary"]
    assert node_by_id(roomy, "svc")["summary"]["cpuCoreUtilization"] == pytest.approx(
        300 * 0.010 / 8, abs=0.03
    )


def test_fewer_cores_than_workers_limits_cpu_throughput() -> None:
    # 8 workers could do 800 req/s of 10 ms work; 2 cores can do only 200 req/s
    r = run_simulation(svc_doc(300, cpuCores=2))
    s = node_by_id(r, "svc")["summary"]
    assert r["summary"]["throughputRps"] == pytest.approx(200, rel=0.06)
    assert s["cpuCoreUtilization"] > 0.97
    assert node_by_id(r, "svc")["status"] == "saturated"
    assert any("CPU" in reason for reason in node_by_id(r, "svc")["statusReasons"])
    assert_accounting(r)


def test_fractional_core_slows_cpu_work() -> None:
    r = run_simulation(svc_doc(5, cpuCores=0.5))
    assert r["summary"]["latencyMs"]["p50"] == pytest.approx(20, abs=0.5)


def test_memory_limit_refuses_requests() -> None:
    # 1 GiB, 1000 MiB at idle, 4 MiB per request: room for 6 requests per replica
    d = svc_doc(2000, cpuCores=None, memoryGib=1, memoryBaselineMb=1000, memoryPerRequestMb=4)
    assert "service.memory_below_workers" in codes(d, "warning")
    r = run_simulation(d)
    svc = node_by_id(r, "svc")["summary"]
    assert svc["rejected"].get("out_of_memory", 0) > 0
    assert svc["memoryPeakGiB"] <= 1.0
    assert any(f["reason"] == "out_of_memory" for f in r["summary"]["failureBreakdown"])
    assert_accounting(r)


def test_memory_too_small_is_an_error() -> None:
    d = svc_doc(10, memoryGib=0.25, memoryBaselineMb=512)
    assert "service.memory_too_small" in codes(d)


def db_doc(**db: Any) -> dict[str, Any]:
    return doc(
        [
            node("clients", "traffic_source", timeoutMs=600_000),
            node(
                "db",
                "database",
                maxConnections=10,
                readServiceTimeMs={"kind": "constant", "value": 5},
                writeServiceTimeMs={"kind": "constant", "value": 5},
                queueCapacity=100_000,
                queueTimeoutMs=None,
                **db,
            ),
        ],
        [edge("c-db", "clients", "db")],
        rps=100,
        durationS=30,
        warmupS=5,
    )


def test_database_buffer_cache_sends_misses_to_disk() -> None:
    fits = run_simulation(db_doc(memoryGib=64, workingSetGib=8))
    spills = run_simulation(
        db_doc(memoryGib=8, workingSetGib=64, diskReadMs={"kind": "constant", "value": 20})
    )
    assert node_by_id(fits, "db")["summary"]["bufferHitRatio"] == 1.0
    s = node_by_id(spills, "db")["summary"]
    assert s["bufferHitRatio"] == pytest.approx(0.75 * 8 / 64, abs=1e-4)
    reads = s["counters"]["reads"]
    assert s["counters"]["diskReads"] / reads == pytest.approx(1 - 0.75 * 8 / 64, abs=0.04)
    assert spills["summary"]["latencyMs"]["p50"] == pytest.approx(25, abs=0.5)
    assert fits["summary"]["latencyMs"]["p50"] == pytest.approx(5, abs=0.5)


def test_database_cpu_share_needs_cores() -> None:
    # 10 connections x 5 ms = 2000 q/s; one core for the whole query = 200 q/s
    d = db_doc(cpuCores=1, cpuShare=1.0)
    d["workload"]["rps"] = 300
    r = run_simulation(d)
    assert r["summary"]["throughputRps"] == pytest.approx(200, rel=0.06)
    assert node_by_id(r, "db")["summary"]["cpuCoreUtilization"] > 0.97
    # only half of each query needs the core: 400 q/s of CPU, so 300 q/s fit
    d["architecture"]["nodes"][1]["config"]["cpuShare"] = 0.5
    r = run_simulation(d)
    assert r["summary"]["throughputRps"] == pytest.approx(300, rel=0.06)


def test_database_storage_is_checked() -> None:
    assert "database.storage_full" in codes(db_doc(storageGib=100, dataSizeGib=300))
    assert "database.storage_nearly_full" in codes(
        db_doc(storageGib=100, dataSizeGib=90), "warning"
    )
    assert codes(db_doc(storageGib=100, dataSizeGib=300, shards=4)) == set()


def cache_doc(**cache: Any) -> dict[str, Any]:
    return doc(
        [
            node("clients", "traffic_source", timeoutMs=600_000),
            node(
                "cache",
                "cache",
                hitModel="ttl_keyspace",
                ttlSeconds=3600,
                serviceTimeMs={"kind": "constant", "value": 0.2},
                concurrency=4,
                **cache,
            ),
            node("db", "database", readServiceTimeMs={"kind": "constant", "value": 5}),
        ],
        [
            edge("c-cache", "clients", "cache", request_types=["read"]),
            edge("miss", "cache", "db", source_port="miss", request_types=["read"]),
        ],
        rps=500,
        durationS=40,
        warmupS=10,
        keyCount=20_000,
        keySkew=0.9,
    )


def test_cache_memory_bounds_keys_with_lru_eviction() -> None:
    unbounded = run_simulation(cache_doc())
    # 1 MiB items, 0.05 GiB: 80% of 51.2 MiB holds 40 keys
    small = run_simulation(cache_doc(memoryGib=0.05, itemSizeKb=1024))
    big = node_by_id(unbounded, "cache")["summary"]["counters"]
    tiny = node_by_id(small, "cache")["summary"]
    assert tiny["capacityKeys"] == 40
    assert tiny["keysCached"] <= 40
    assert tiny["counters"]["evictions"] > 0
    hit = lambda c: c["hits"] / (c["hits"] + c["misses"])  # noqa: E731
    assert hit(tiny["counters"]) < hit(big) - 0.2


def test_cache_fixed_ratio_warns_when_keyspace_does_not_fit() -> None:
    d = cache_doc(memoryGib=0.01, itemSizeKb=4)
    d["architecture"]["nodes"][1]["config"]["hitModel"] = "fixed_ratio"
    d["architecture"]["nodes"][1]["config"]["hitRatio"] = 0.9
    assert "cache.keyspace_exceeds_memory" in codes(d, "warning")
