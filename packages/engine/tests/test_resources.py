"""Resource specs: CPU cores, memory and storage, and their effect on the simulation."""

from __future__ import annotations

from typing import Any

import pytest
from test_engine_behaviour import assert_accounting
from test_validation import codes

from archsim_engine import run_simulation
from engine_builders import doc, edge, manual, node, node_by_id, single_service

CONST = {"kind": "constant", "value": 10}


def svc_doc(rps: float, **svc: Any) -> dict[str, Any]:
    d = single_service(rps=rps, service=CONST, workers=8, durationS=30, warmupS=5)
    d["architecture"]["nodes"][1]["config"].update(svc)
    return d


def test_without_specs_nothing_changes() -> None:
    plain = run_simulation(manual(svc_doc(300)))
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


def test_auto_sized_part_is_not_the_bottleneck_at_its_peak() -> None:
    # 300 req/s x 10 ms = 3 cores busy, / 0.6 = 5 -> 8 vCPU (= the 8 workers): same as unlimited
    auto = run_simulation(svc_doc(300))
    plain = run_simulation(manual(svc_doc(300)))
    sizing = auto["sizing"]["nodes"]["svc"]["specs"]
    assert sizing["cpuCores"] == {"value": 8.0, "source": "auto"}
    assert sizing["memoryGib"]["source"] == "auto"
    assert auto["summary"]["latencyMs"] == plain["summary"]["latencyMs"]
    assert node_by_id(auto, "svc")["summary"]["cpuCoreUtilization"] == pytest.approx(
        300 * 0.010 / 8, abs=0.03
    )


def test_capacity_figures_follow_cpu_and_memory() -> None:
    plain = node_by_id(run_simulation(manual(svc_doc(300))), "svc")["summary"]
    assert plain["capacityLimit"] == "slots" and plain["cpuCapacityRps"] is None
    assert plain["serviceOnlyCapacityRps"] == pytest.approx(800, rel=0.01)

    r = run_simulation(svc_doc(300, cpuCores=2))
    s = node_by_id(r, "svc")["summary"]
    # 2 cores / 10 ms of CPU work = 200 req/s, below 8 workers / 10 ms = 800 req/s
    assert s["capacityLimit"] == "cpu"
    assert s["cpuCapacityRps"] == pytest.approx(200, rel=0.01)
    assert s["capacityRps"] == pytest.approx(200, rel=0.01)
    assert s["serviceOnlyCapacityRps"] == pytest.approx(200, rel=0.01)
    finding = next(f for f in r["findings"] if f.get("nodeId") == "svc")
    assert "CPU cores" in finding["detail"] and "CPU cores" in finding["suggestion"]

    mem = svc_doc(2000, memoryGib=1, memoryBaselineMb=1000, memoryPerRequestMb=4)
    s = node_by_id(run_simulation(mem), "svc")["summary"]
    assert s["capacityLimit"] == "memory"  # 6 requests fit, fewer than the 8 workers


def test_database_cpu_capacity_uses_the_cpu_share() -> None:
    s = node_by_id(run_simulation(db_doc(cpuCores=1, cpuShare=0.5)), "db")["summary"]
    assert s["cpuCapacityRps"] == pytest.approx(1000 / (5 * 0.5), rel=0.01)


# -- measured CPU and memory inputs (schema 1.5) ---------------------------------------------------


def test_service_cpu_share_needs_fewer_cores_and_keeps_latency() -> None:
    # 2 cores, 10 ms of service time: 200 req/s when all of it is CPU, 400 req/s at half
    r = run_simulation(svc_doc(300, cpuCores=2, cpuShare=0.5))
    s = node_by_id(r, "svc")["summary"]
    assert r["summary"]["throughputRps"] == pytest.approx(300, rel=0.06)
    assert s["cpuCoreUtilization"] == pytest.approx(300 * 0.010 * 0.5 / 2, abs=0.03)
    assert s["cpuCapacityRps"] == pytest.approx(400, rel=0.01)
    estimated = next(p for p in r["estimate"]["parts"] if p["id"] == "svc")
    assert estimated["cpuUtilization"] == pytest.approx(0.75, abs=0.01)
    assert_accounting(r)
    # the rest of the service time still holds the worker: the same latency without contention
    whole = run_simulation(svc_doc(20, cpuCores=8))
    half = run_simulation(svc_doc(20, cpuCores=8, cpuShare=0.5))
    assert half["summary"]["latencyMs"] == whole["summary"]["latencyMs"]
    busy = node_by_id(half, "svc")["summary"]["cpuCoreUtilization"]
    assert busy == pytest.approx(
        node_by_id(whole, "svc")["summary"]["cpuCoreUtilization"] / 2, rel=0.05
    )


def test_a_fractional_core_slows_only_the_cpu_share() -> None:
    # 5 ms of CPU work at half speed (10 ms) + 5 ms that needs no core
    r = run_simulation(svc_doc(5, cpuCores=0.5, cpuShare=0.5))
    assert r["summary"]["latencyMs"]["p50"] == pytest.approx(15, abs=0.5)


def test_memory_per_rps_counts_in_the_peak_not_in_the_out_of_memory_limit() -> None:
    base = {"memoryGib": 1, "memoryBaselineMb": 100, "memoryPerRequestMb": 1}
    plain = node_by_id(run_simulation(svc_doc(400, **base)), "svc")["summary"]
    flowing = node_by_id(run_simulation(svc_doc(400, **base, memoryPerRpsMb=1)), "svc")["summary"]
    # one replica at about 400 req/s (its busiest time bucket a little more): about 400 MiB more
    extra = (flowing["memoryPeakGiB"] - plain["memoryPeakGiB"]) * 1024
    assert 380 < extra < 480
    # more than the memory: the peak is capped at it, and the collector's garbage refuses nothing
    heavy = run_simulation(svc_doc(400, **base, memoryPerRpsMb=5))
    s = node_by_id(heavy, "svc")["summary"]
    assert s["memoryPeakGiB"] == 1.0 and not s["rejected"].get("out_of_memory")


def test_sizing_counts_the_cpu_share_and_memory_per_rps() -> None:
    from archsim_engine.components import create_default_registry
    from archsim_engine.scenarios import load_document
    from archsim_engine.sizing import auto_sizes

    registry = create_default_registry(include_plugins=False)

    def need(**svc: Any) -> dict[str, float]:
        d = load_document(svc_doc(300, **svc)).to_json_dict()
        return auto_sizes(d, registry)["svc"].need

    whole, part = need(), need(cpuShare=0.5, memoryPerRpsMb=1)
    assert part["busyCores"] == pytest.approx(whole["busyCores"] / 2)
    assert part["memoryGibMax"] - whole["memoryGibMax"] == pytest.approx(300 / 1024)
