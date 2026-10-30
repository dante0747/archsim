"""The accuracy benchmarks' arithmetic (benchmarks/run.py) and report (benchmarks/report.py), on
synthetic data and on the committed results. Nothing here starts Docker."""

from __future__ import annotations

import json
import math
import shutil
import time
from pathlib import Path
from typing import Any

import pytest
import report
import run

PG_MIXED = run.EXPERIMENTS["pg-mixed"]


def summary(count: int, sent: int, failed: int = 0, dropped: int = 0) -> dict[str, Any]:
    """A k6 end-of-run summary (data.metrics) for the measured scenario."""
    trend = (
        {"avg": 1.5, "min": 0.2, "med": 1.2, "p(95)": 3.4, "p(99)": 5.6, "max": 9.0}
        if count
        else {"avg": 0, "min": 0, "med": 0, "p(95)": 0, "p(99)": 0, "max": 0}
    )
    return {
        "http_req_duration{scenario:measure,expected_response:true}": {
            "values": {**trend, "count": count}
        },
        "http_reqs{scenario:measure}": {"values": {"count": sent}},
        "http_req_failed{scenario:measure}": {"values": {"passes": failed, "fails": sent - failed}},
        "dropped_iterations{scenario:measure}": {"values": {"count": dropped}},
    }


def repeat(p95: float | None, sent: int = 1000, succeeded: int | None = None) -> dict[str, Any]:
    """One measured repeat as measure_once records it; ``p95=None`` is a failed repeat."""
    ok = (sent if p95 is not None else 0) if succeeded is None else succeeded
    return {
        "p50": None if p95 is None else p95 / 2,
        "p95": p95,
        "p99": None if p95 is None else p95 * 2,
        "mean": None if p95 is None else p95 / 2,
        "sent": sent,
        "dropped": 0,
        "failed": sent - ok,
        "succeeded": ok,
        "successRate": ok / sent,
        "throughputRps": ok / run.DURATION_S,
    }


# ---------------------------------------------------------------------------------------------
# k6_measured and the aggregation of repeats
# ---------------------------------------------------------------------------------------------


def test_k6_measured_reads_the_measured_window() -> None:
    m = run.k6_measured(summary(count=990, sent=995, failed=5, dropped=5), duration=20)
    assert (m["p50"], m["p95"], m["p99"], m["mean"]) == (1.2, 3.4, 5.6, 1.5)
    assert (m["sent"], m["dropped"], m["failed"], m["succeeded"]) == (995, 5, 5, 990)
    assert m["successRate"] == pytest.approx(0.99)
    assert m["throughputRps"] == pytest.approx(49.5)


def test_k6_measured_without_a_success_has_no_latency() -> None:
    # k6 reports its latency trend as zeros when nothing succeeded (run 4, hot-row, 1,126 req/s)
    m = run.k6_measured(summary(count=0, sent=18409, failed=18409, dropped=4114), duration=20)
    assert (m["p50"], m["p95"], m["p99"], m["mean"]) == (None, None, None, None)
    assert m["successRate"] == 0.0
    assert m["throughputRps"] == 0.0
    assert run.failed_repeat(m)


def test_k6_measured_when_nothing_was_sent() -> None:
    m = run.k6_measured({}, duration=20)
    assert m["sent"] == 0
    assert m["successRate"] is None
    assert m["p95"] is None
    assert not run.failed_repeat(m)  # the load generator failed, not the system


def test_median_is_the_true_median() -> None:
    assert run.median([4.0, 1.0, 3.0, 2.0]) == 2.5
    assert run.median([3.0, None, 1.0]) == 2.0
    assert run.median([]) is None
    assert run.median([1.0, math.inf, math.inf]) is None


def test_most_repeats_failed_leaves_the_step_without_latency() -> None:
    agg = run.aggregate([repeat(332.0), repeat(None), repeat(None)])
    assert agg["p50"] is None and agg["p95"] is None and agg["p99"] is None
    assert agg["successRate"] == 0.0
    assert agg["failedRepeats"] == 2 and agg["repeats"] == 3
    assert agg["unstable"]


def test_one_failed_repeat_takes_the_slower_of_the_others() -> None:
    agg = run.aggregate([repeat(2.0), repeat(None), repeat(3.0)])
    assert agg["p95"] == 3.0
    assert agg["successRate"] == 1.0
    assert agg["failedRepeats"] == 1
    assert agg["unstable"]  # a failed repeat is infinitely slower than the others


def test_repeats_recorded_as_zero_ms_count_as_failed() -> None:
    # run 4 recorded failed repeats with k6's zeros; the comparison must not read them as 0 ms
    legacy = {**repeat(None), "p50": 0, "p95": 0, "p99": 0, "mean": 0}
    agg = run.aggregate([repeat(10.0), legacy, legacy])
    assert agg["p95"] is None
    assert agg["failedRepeats"] == 2


def test_every_failed_repeat_is_not_unstable() -> None:
    agg = run.aggregate([repeat(None), repeat(None), repeat(None)])
    assert agg["p95"] is None
    assert not agg["unstable"]


def test_stable_step() -> None:
    agg = run.aggregate([repeat(2.0), repeat(2.2), repeat(4.5)])
    assert agg["p95"] == 2.2
    assert not agg["unstable"]  # more than 2x apart, but all under the objective
    assert agg["failedRepeats"] == 0


# ---------------------------------------------------------------------------------------------
# grid_knee and compare
# ---------------------------------------------------------------------------------------------


def step(rps: float, p95: float | None, success: float = 1.0) -> dict[str, Any]:
    return {"offeredRps": rps, "p95": p95, "successRate": success}


def test_grid_knee_interpolates_p95() -> None:
    k = run.grid_knee([step(300, 70.0), step(100, 10.0), step(200, 30.0)])
    assert k == {"kneeRps": 250.0, "bound": "interpolated", "between": [200, 300]}


def test_grid_knee_interpolates_success() -> None:
    k = run.grid_knee([step(100, 5.0), step(200, 5.0, success=0.97)])
    assert k["kneeRps"] == pytest.approx(133.3)


def test_grid_knee_takes_the_lower_of_both_failures() -> None:
    k = run.grid_knee([step(100, 10.0), step(200, 90.0, success=0.98)])
    # p95 crosses 50 ms at 150 req/s, success crosses 99% at 150 req/s too: (1-0.99)/(1-0.98)
    assert k["kneeRps"] == 150.0


def test_grid_knee_failing_step_without_latency() -> None:
    k = run.grid_knee([step(100, 5.0), step(200, None, success=0.0)])
    assert k["kneeRps"] == 100.0


def test_grid_knee_bounds() -> None:
    assert run.grid_knee([step(100, 60.0), step(200, 70.0)]) == {
        "kneeRps": None,
        "bound": "below",
        "belowRps": 100,
    }
    assert run.grid_knee([step(100, 5.0), step(200, 6.0)]) == {
        "kneeRps": None,
        "bound": "above",
        "aboveRps": 200,
    }


def synthetic() -> tuple[dict[str, Any], dict[str, Any]]:
    def pred(rps: float, p95: float) -> dict[str, Any]:
        return {
            "offeredRps": rps,
            "p50": p95 / 2,
            "p95": p95,
            "p99": p95 * 2,
            "successRate": 1.0,
            "throughputRps": rps,
        }

    predictions = {
        "knee": {"kneeRps": 340},
        "steps": [pred(50, 1.2), pred(100, 4.4), pred(200, 3.3), pred(300, 21.0), pred(400, 80.0)],
    }
    measured = {
        "steps": [
            {"offeredRps": 50, "runs": [repeat(1.0), repeat(1.0), repeat(1.0)]},
            {"offeredRps": 100, "runs": [repeat(2.0), repeat(2.2), repeat(2.4)]},
            {"offeredRps": 200, "runs": [repeat(3.0), repeat(3.0), repeat(3.0)]},
            {"offeredRps": 300, "runs": [repeat(40.0), repeat(45.0), repeat(42.0)]},
            # k6's zeros for two failed repeats, as run 4 recorded them
            {
                "offeredRps": 400,
                "p95": 0,
                "runs": [repeat(100.0), {**repeat(None), "p95": 0}, {**repeat(None), "p95": 0}],
            },
        ]
    }
    return predictions, measured


def test_compare() -> None:
    predictions, measured = synthetic()
    c = run.compare(PG_MIXED, predictions, measured)
    rows = {r["offeredRps"]: r for r in c["rows"]}
    assert rows[100]["measured"]["p95"] == 2.2
    assert rows[100]["error"]["p95"] == pytest.approx(1.0)
    assert rows[300]["error"]["p95"] == pytest.approx(-0.5)
    assert rows[400]["measured"]["p95"] is None
    assert rows[400]["error"]["p95"] is None
    assert (rows[400]["failedRepeats"], rows[400]["repeats"], rows[400]["unstable"]) == (
        2,
        3,
        True,
    )
    assert c["measuredKnee"]["kneeRps"] == 300.0
    assert c["predictedKnee"]["kneeRps"] == pytest.approx(349.2)
    assert c["kneeError"] == pytest.approx(349.2 / 300 - 1)
    assert c["predictedKneeFine"] == 340
    # at or below the knee: errors 0.2, 1.0, 0.1, 0.5 -> the median of four is 0.35
    assert c["belowKnee"]["steps"] == 4
    assert c["belowKnee"]["medianAbsP95Error"] == pytest.approx(0.35)
    assert c["belowKnee"]["maxAbsP95Error"] == pytest.approx(1.0)


def test_at_or_below_knee() -> None:
    rows = [{"offeredRps": 100}, {"offeredRps": 200}]
    assert run.at_or_below_knee(rows, {"kneeRps": 150.0}) == [{"offeredRps": 100}]
    assert run.at_or_below_knee(rows, {"kneeRps": None, "bound": "above"}) == rows
    assert run.at_or_below_knee(rows, {"kneeRps": None, "bound": "below"}) == []


def test_at_or_below_both_knees() -> None:
    rows = [{"offeredRps": 100}, {"offeredRps": 200}, {"offeredRps": 300}]
    above = {"kneeRps": None, "bound": "above"}
    # the model's knee is the lower one: past it the model's queue grows without bound
    assert run.at_or_below_both_knees(rows, {"kneeRps": 250.0}, {"kneeRps": 150.0}) == rows[:1]
    assert run.at_or_below_both_knees(rows, {"kneeRps": 150.0}, {"kneeRps": 250.0}) == rows[:1]
    assert run.at_or_below_both_knees(rows, above, {"kneeRps": 250.0}) == rows[:2]
    assert run.at_or_below_both_knees(rows, above, above) == rows


def test_committed_comparisons_are_reproduced_by_compare() -> None:
    dirs = run.result_dirs()
    assert len(dirs) == 14
    for exp, d in dirs:
        committed = json.loads((d / "comparison.json").read_text(encoding="utf-8"))
        assert run.comparison_of(exp, d) == committed, d


def test_recompare_writes_every_comparison(tmp_path: Path) -> None:
    shutil.copytree(run.RESULTS / "pg-hot-row", tmp_path / "pg-hot-row")
    for f in (tmp_path / "pg-hot-row").rglob("comparison.json"):
        f.unlink()
    written = run.recompare(tmp_path)
    assert [p.parent.name for p in written] == ["run-1", "run-2", "run-3", "run-4", "pg-hot-row"]
    run4 = json.loads(written[-2].read_text(encoding="utf-8"))
    rows = {r["offeredRps"]: r for r in run4["rows"]}
    assert rows[1126]["measured"]["p95"] is None  # two of three repeats had no success
    assert rows[1126]["failedRepeats"] == 2


# ---------------------------------------------------------------------------------------------
# CPU and memory
# ---------------------------------------------------------------------------------------------

CGROUP = """usage_usec 2500000
user_usec 2000000
system_usec 500000
anon 104857600
file 52428800
active_file 20971520
inactive_file 31457280
shmem 0
188743680
"""


def test_reading_takes_the_working_set_without_reclaimable_cache() -> None:
    r = run.reading(10.0, CGROUP)
    assert r.usage_usec == 2_500_000
    assert r.anon == 100 * 2**20
    assert r.working_set == 188_743_680 - 31_457_280  # memory.current - inactive_file


def test_reading_without_counters() -> None:
    assert run.reading(1.0, "") == run.Reading(1.0, None, None, None)


def test_window_resources() -> None:
    readings = [
        run.Reading(0.0, 0, 100 * 2**20, 80 * 2**20),
        run.Reading(5.0, 2_000_000, 120 * 2**20, 90 * 2**20),
        run.Reading(10.0, 6_000_000, 110 * 2**20, 85 * 2**20),
    ]
    assert run.window_resources(readings) == {
        "cores": 0.6,  # 6 s of CPU over 10 s
        "maxCores": 0.8,  # 4 s over the second 5 s
        "workingSetMiB": 120.0,
        "anonMiB": 90.0,
    }
    assert run.window_resources(readings[:1]) == {"workingSetMiB": 100.0, "anonMiB": 80.0}
    assert run.window_resources([]) is None


def test_run_resources_scales_old_repeats_by_the_services_own_cpu() -> None:
    old = {
        **repeat(2.0, sent=2000),  # 100 req/s over the 20 s window
        "serviceCpuMsPerRequest": 3.0,  # so 0.3 busy cores
        "containerCpuShare": {"api": 0.25, "postgres": 0.1},  # over a longer k6 command
    }
    res = run.run_resources(old)
    assert res is not None and res["method"] == "scaled"
    assert res["containers"]["api"]["cores"] == pytest.approx(0.3)
    assert res["containers"]["postgres"]["cores"] == pytest.approx(0.12)
    sampled = {**old, "resources": {"method": "window", "containers": {"api": {"cores": 0.31}}}}
    assert run.run_resources(sampled) == sampled["resources"]
    assert run.run_resources(repeat(2.0)) is None  # nothing recorded


def test_aggregate_takes_the_median_of_each_containers_resources() -> None:
    def sampled(cores: float, mem: float) -> dict[str, Any]:
        containers = {"api": {"cores": cores, "workingSetMiB": mem}, "redis": {"cores": cores / 10}}
        return {**repeat(2.0), "resources": {"method": "window", "containers": containers}}

    out = run.aggregate([sampled(0.5, 90), sampled(0.7, 70), sampled(0.6, 80)])
    assert out["resources"] == {
        "method": "window",
        "repeats": 3,
        "containers": {
            "api": {"cores": 0.6, "workingSetMiB": 80.0},
            "redis": {"cores": 0.06},
        },
    }
    assert "resources" not in run.aggregate([repeat(2.0)])


def test_resource_sampler_reads_inside_the_measured_window(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(run, "WARMUP_S", 0)
    monkeypatch.setattr(run, "DURATION_S", 2.0)  # the window ends a second early: 1 s long
    monkeypatch.setattr(run, "SAMPLE_S", 0.5)
    monkeypatch.setattr(run, "container", lambda service: f"id-{service}")
    monkeypatch.setattr(run, "http_json", lambda url, method="GET": {"requests": 1})
    start = time.time()

    def read(cid: str) -> run.Reading:
        t = time.time()
        return run.Reading(t, int((t - start) * 400_000), 50 * 2**20, 40 * 2**20)  # 0.4 cores

    monkeypatch.setattr(run, "read_cgroup", read)
    with run.ResourceSampler(["api", "postgres"]) as sampler:
        time.sleep(1.6)
    res = sampler.result()
    assert res is not None and res["method"] == "window"
    assert res["readings"] == 3  # at 0, 0.5 and 1 s
    assert set(res["containers"]) == {"api", "postgres"}
    assert res["containers"]["api"]["cores"] == pytest.approx(0.4, abs=0.02)
    assert res["containers"]["api"]["workingSetMiB"] == 50.0


def test_resource_sampler_without_load_records_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run, "container", lambda service: f"id-{service}")
    monkeypatch.setattr(run, "http_json", lambda url, method="GET": {"requests": 0})
    with run.ResourceSampler(["api"]) as sampler:
        pass
    assert sampler.result() is None


def test_node_resources() -> None:
    doc = {
        "architecture": {
            "nodes": [
                {"id": "api", "config": {"cpuCores": 1, "memoryGib": 1024}},
                {"id": "db", "config": {"cpuCores": 2}},
                {"id": "cache", "config": {"cpuCores": 1, "itemSizeKb": 1.0}},
                {"id": "kafka", "config": {}},
            ]
        }
    }
    result = {
        "nodes": [
            {"id": "api", "summary": {"cpuCoreUtilization": 0.5, "memoryPeakGiB": 0.25}},
            {"id": "db", "summary": {"cpuCoreUtilization": 0.1}},
            {"id": "cache", "summary": {"cpuCoreUtilization": 0.02, "keysCached": 2048}},
            {"id": "kafka", "summary": {}},
        ]
    }
    assert run.node_resources(doc, result) == {
        "api": {"cores": 0.5, "memoryMiB": 256.0},
        "db": {"cores": 0.2},
        "cache": {"cores": 0.02, "memoryMiB": 2.0},
    }


def with_resources(row: dict[str, Any], api: float, db: float) -> dict[str, Any]:
    containers = {"api": {"cores": api, "workingSetMiB": 100.0}, "postgres": {"cores": db}}
    return {**row, "resources": {"method": "window", "containers": containers}}


def test_compare_resources() -> None:
    predictions, measured = synthetic()
    for i, s in enumerate(predictions["steps"]):
        s["resources"] = {"api": {"cores": 0.2 * (i + 1), "memoryMiB": 260.0}, "db": {"cores": 0.1}}
    for i, step in enumerate(measured["steps"]):
        step["runs"] = [with_resources(r, 0.1 * (i + 1), 0.1) for r in step["runs"]]
    c = run.compare(PG_MIXED, predictions, measured)
    rows = {r["offeredRps"]: r for r in c["rows"]}
    api = rows[100]["resources"]["api"]
    assert api["measured"] == {"cores": 0.2, "memoryMiB": 100.0}
    assert api["predicted"] == {"cores": 0.4, "memoryMiB": 260.0}
    assert api["error"]["cores"] == pytest.approx(1.0)
    assert api["error"]["memory"] == pytest.approx(1.6)
    assert rows[100]["resources"]["postgres"]["error"]["cores"] == pytest.approx(0.0)
    assert c["resources"]["measuredBy"] == "window"
    assert c["resources"]["predictedBy"] == "predictions"
    below = c["resources"]["belowKnee"]  # the four steps at or below the 300 req/s knee
    assert below["api"]["steps"] == 4
    assert below["api"]["medianAbsCoresError"] == pytest.approx(1.0)
    assert below["postgres"]["medianAbsCoresError"] == pytest.approx(0.0)
    assert below["postgres"]["medianAbsMemoryError"] is None


def test_compare_resources_from_a_later_simulation() -> None:
    predictions, measured = synthetic()
    for step in measured["steps"]:
        step["runs"] = [with_resources(r, 0.5, 0.1) for r in step["runs"]]
    later = {
        "engineVersion": "9.9.9",
        "at": "2026-10-28T00:00:00Z",
        "how": "simulated again",
        "steps": [
            {"offeredRps": s["offeredRps"], "resources": {"api": {"cores": 0.25}}}
            for s in predictions["steps"]
        ],
    }
    c = run.compare(PG_MIXED, predictions, measured, later)
    assert c["resources"]["predictedBy"] == {
        "engineVersion": "9.9.9",
        "at": "2026-10-28T00:00:00Z",
        "how": "simulated again",
    }
    assert c["rows"][0]["resources"]["api"]["error"]["cores"] == pytest.approx(-0.5)
    assert c["rows"][0]["resources"]["postgres"]["predicted"]["cores"] is None
    # without any prediction of CPU or memory: latency alone, as for the older kept runs
    bare = run.compare(PG_MIXED, predictions, measured)
    assert "resources" not in bare and all("resources" not in r for r in bare["rows"])


def test_resources_on_the_committed_results() -> None:
    data = report.accuracy()
    assert data is not None
    by = {x["id"]: x["resources"] for x in data["experiments"]}
    assert {k: (r["measuredBy"], r["predictedBy"], r["memoryMeasured"]) for k, r in by.items()} == {
        exp: ("window", "predictions", True) for exp in report.ORDER
    }
    service = {p["container"]: p for p in by["pg-mixed"]["parts"]}["api"]
    assert service["top"]["text"]["measuredMemory"] == "188 MiB"
    assert service["top"]["text"]["predictedMemory"] == "550 MiB"
    parts = {p["container"]: p for p in by["cache-aside"]["parts"]}
    assert list(parts) == ["api", "postgres", "redis"]
    # the top step at or below both knees: the model's (2,352 req/s), not the measured (4,848)
    assert by["cache-aside"]["topRps"] == 2350
    assert parts["redis"]["top"]["text"]["coresError"] == "-88%"
    assert parts["api"]["top"]["text"]["predictedMemory"] == "498 MiB"
    kafka = {p["container"]: p for p in by["kafka"]["parts"]}
    assert not kafka["kafka"]["modelled"]["cores"]
    assert kafka["kafka"]["notModelled"]["cores"] == report.NOT_MODELLED["kafka"]
    assert kafka["postgres"]["notModelled"]["memory"] == report.NO_MEMORY_MODEL["postgres"]
    cmps = [c for e in report.ORDER if (c := report.load(e, "comparison"))]
    block = report.resources_block(cmps)
    assert (
        "| [pg-mixed](#pg-mixed) | Node.js service | 2,238 | 0.46 | 1.00 | +116% | 116% (5) | 188 MiB | 550 MiB | +192% |"
        in block
    )
    assert (
        "| [cache-aside](#cache-aside) | Redis | 2,350 | 0.14 | 0.02 | -88% | 88% (5) | 11 MiB | "
        "17 MiB | +50% |" in block
    )
    # memory is shown only where it was measured, never a prediction alone
    assert (
        "| [kafka](#kafka) | Node.js consumer | 1,600 | 0.27 | not modelled | - | - | 170 MiB | - | - |"
        in block
    )


def test_resources_block_shows_memory_once_measured() -> None:
    predictions, measured = synthetic()
    for s in predictions["steps"]:
        s["resources"] = {"api": {"cores": 0.4, "memoryMiB": 260.0}}
    for step in measured["steps"]:
        step["runs"] = [with_resources(r, 0.2, 0.1) for r in step["runs"]]
    block = report.resources_block([run.compare(PG_MIXED, predictions, measured)])
    assert "| Memory measured | Memory predicted | Error |" in block
    assert (
        "| [pg-mixed](#pg-mixed) | Node.js service | 300 | 0.20 | 0.40 | +100% | 100% (4) "
        "| 100 MiB | 260 MiB | +160% |"
    ) in block
    assert "| PostgreSQL | 300 | 0.10 | - | - | - | - | - | - |" in block


# ---------------------------------------------------------------------------------------------
# CPU pinning
# ---------------------------------------------------------------------------------------------


def test_cpu_count() -> None:
    assert run.cpu_count("0,1,3,6,7") == 5
    assert run.cpu_count("0-3") == 4
    assert run.cpu_count("2") == 1
    assert run.cpu_count("0-1,4") == 3
    assert run.cpu_list("0-1,4") == [0, 1, 4]


# the laptop's Docker VM: four physical cores, two hyperthreads each
LAPTOP_SIBLINGS = {c: [c - c % 2, c - c % 2 + 1] for c in range(8)}


def test_topology_finds_containers_sharing_a_physical_core() -> None:
    # the layout of runs up to 6: k6's five CPUs span three cores, one of them the service's
    old = {"api": "2", "postgres": "4,5", "k6": "0,1,3,6,7"}
    t = run.topology(old, LAPTOP_SIBLINGS)
    assert t["physicalCores"] == {"api": 1, "postgres": 1, "k6": 3}  # PostgreSQL's 2 CPUs: 1 core
    assert t["sharedCores"] == [{"containers": ["api", "k6"], "cores": [[2, 3]]}]
    # the runner's layouts now leave the service's sibling idle
    for stack in ("postgres", "cache"):
        shared = run.topology(run.cpusets(stack, env={}), LAPTOP_SIBLINGS)["sharedCores"]
        assert not any("api" in s["containers"] for s in shared), stack
    kafka = run.topology(run.cpusets("kafka", env={}), LAPTOP_SIBLINGS)
    assert {"containers": ["producer", "consumer"], "cores": [[2, 3]]} in kafka["sharedCores"]
    alone = run.topology({"api": "2", "k6": "0,1,6,7"}, LAPTOP_SIBLINGS)
    assert alone["sharedCores"] == []
    assert run.core_of(5, {}) == (5,)  # no topology known: each CPU its own core


def test_machine_id_names_the_cpu_not_the_host(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("BENCH_MACHINE", raising=False)
    assert run.machine_id("11th Gen Intel(R) Core(TM) i7-1165G7 @ 2.80GHz") == "i7-1165g7"
    assert run.machine_id("AMD EPYC 7763 64-Core Processor") == "epyc-7763-64-core"
    assert run.machine_id("") == "unknown"
    monkeypatch.setenv("BENCH_MACHINE", "ci-linux-8cpu")
    assert run.machine_id("anything") == "ci-linux-8cpu"


def test_provenance_names_the_engine_and_harness_by_git_object() -> None:
    p = run.provenance()
    assert set(p) == {"commit", "engineTree", "harnessHash", "dirty", "machineId"}
    assert p["engineTree"] and len(p["engineTree"]) == 40
    assert len(p["harnessHash"]) == 64 and isinstance(p["dirty"], bool)


def test_vm_cpu_times_reads_steal(monkeypatch: pytest.MonkeyPatch) -> None:
    stat = "cpu  100 0 50 800 10 0 5 35 0 0\ncpu0 1 2 3 4 5 6 7 8 0 0\n"
    monkeypatch.setattr(run, "compose", lambda *a, **k: stat)
    assert run.vm_cpu_times() == (35, 1000)


def test_split_inputs_reproduce_every_latest_calibration() -> None:
    # one function per part, then anchored again: the very inputs each latest run used
    for exp in report.ORDER:
        cal = json.loads((run.RESULTS / exp / "calibration.json").read_text(encoding="utf-8"))
        assert run.rederive_inputs(run.EXPERIMENTS[exp], cal) == cal["inputs"], exp


def test_simulate_reports_what_the_database_did() -> None:
    cal = json.loads((run.RESULTS / "pg-mixed" / "calibration.json").read_text(encoding="utf-8"))
    r = run.simulate(run.design(PG_MIXED, cal["inputs"], 500, warm=2, duration=5))
    assert set(r["database"]) == {"commitsPerFlush", "meanLockWaitMs", "meanCommitWaitMs"}
    assert r["database"]["commitsPerFlush"] >= 1


def test_dev_checks_reproduce_the_committed_predictions() -> None:
    # engine 0.14.0 against every kept run: the protocols since v3 derive the same inputs
    # today, and then simulate exactly what their predictions said, step by step
    checks = report.dev_checks()["0.14.0"]
    assert sorted(checks) == sorted(report.ORDER)
    rows = [c for runs in checks.values() for c in runs.values()]
    assert len(rows) == 12  # 14 kept runs less the two calibrated from one pass
    for c in rows:
        assert c["kind"] == run.DEV_KIND and c["designedAfterMeasurements"]
        same = c["sameLatencyAsPredictions"]
        if c["inputsAsCalibrated"]:
            assert same["steps"] == same["of"], c["results"]
    assert sorted(c["results"] for c in rows if not c["inputsAsCalibrated"]) == [
        "pg-hot-row/run-2",
        "pg-mixed/run-2",
    ]  # v2 had no anchor


def test_dev_path_names_a_check_by_its_calibration(tmp_path: Path) -> None:
    check = {"calibratedAt": "2026-10-30T10:32:56Z", "results": "pg-hot-row"}
    path = run.dev_path(tmp_path, "0.15.0", "pg-hot-row", check)
    assert path == tmp_path / "dev" / "0.15.0" / "pg-hot-row" / "2026-10-30T10-32-56Z.json"


def test_dev_checks_page_is_generated_from_the_committed_checks() -> None:
    text = report.dev_text()
    assert report.DEV_DOC.read_text(encoding="utf-8") == text, (
        "docs/validation/dev-checks.md is stale: run benchmarks/report.py --dev"
    )
    assert "| pg-hot-row | `pg-hot-row/` | 2026-10-30T10:32:56Z | yes | 814 | 519 (-36%) |" in text
    assert "Engine 0.14.0: median absolute knee error 29% over the latest run" in text
    assert "_No development checks yet._" in report.dev_text(Path("nowhere"))


def test_cpusets_follow_compose_and_the_runner() -> None:
    declared = run.compose_cpusets()
    assert {s for services in run.STACK_SERVICES.values() for s in services} <= set(declared)
    assert run.cpusets("postgres", env={}) == {"api": "2", "postgres": "4,5", "k6": "0,1,6,7"}
    assert run.cpusets("cache", env={})["k6"] == "0,6,7"
    assert run.cpusets("kafka", env={})["k6"] == "6,7"
    custom = run.cpusets("kafka", env={"BENCH_CPUS_POSTGRES": "0-3", "BENCH_CPUS_K6": "4-7"})
    assert (custom["postgres"], custom["k6"]) == ("0-3", "4-7")


def test_default_cpusets_do_not_overlap() -> None:
    def cpus(cpuset: str) -> set[int]:
        out: set[int] = set()
        for part in cpuset.split(","):
            lo, _, hi = part.partition("-")
            out |= set(range(int(lo), int(hi or lo) + 1))
        return out

    for stack in run.STACKS:
        seen: set[int] = set()
        for cpuset in run.cpusets(stack, env={}).values():
            assert not seen & cpus(cpuset), stack
            seen |= cpus(cpuset)


def test_docstring_states_the_measurement_window() -> None:
    assert f"{run.WARMUP_S} s warm-up, {run.DURATION_S} s measured" in (run.__doc__ or "")


# ---------------------------------------------------------------------------------------------
# the proposed tier criteria
# ---------------------------------------------------------------------------------------------


def test_p95_within() -> None:
    assert report.p95_within(1.0, 3.9)  # 3 ms absolute
    assert not report.p95_within(1.0, 4.1)
    assert report.p95_within(10.0, 19.9)  # 100%
    assert not report.p95_within(10.0, 20.1)
    assert report.p95_within(10.0, 0.5)
    assert not report.p95_within(None, 1.0)
    assert not report.p95_within(1.0, None)


def cmp_row(rps: float, meas: float | None, pred: float | None, unstable: bool = False) -> dict:
    return {
        "offeredRps": rps,
        "measured": {"p95": meas},
        "predicted": {"p95": pred},
        "unstable": unstable,
    }


def test_criteria_all_met() -> None:
    c = {
        "kneeError": -0.2,
        "measuredKnee": {"kneeRps": 250.0},
        "rows": [cmp_row(100, 1.0, 2.5), cmp_row(200, 10.0, 15.0), cmp_row(300, 80.0, 900.0, True)],
    }
    runs = [{"protocol": "v4", "machine": "a"}, {"protocol": "v4", "machine": "b"}]
    k = report.criteria(c, runs)
    assert k["all"]
    assert k["p95"] == {"pass": True, "steps": 2, "misses": []}
    assert k["reproduced"] == {"pass": True, "protocol": "v4", "runs": 2, "machines": 2}


def test_criteria_failures() -> None:
    c = {
        "kneeError": 0.3,
        "measuredKnee": {"kneeRps": 250.0},
        "rows": [cmp_row(100, 1.0, 5.0), cmp_row(200, 10.0, 15.0, True)],
    }
    runs = [{"protocol": "v4", "machine": "a"}, {"protocol": "v4", "machine": "a"}]
    k = report.criteria(c, runs)
    assert not k["knee"]["pass"]
    assert k["p95"]["misses"] == [100]
    assert k["stable"]["unstable"] == [200]
    assert not k["reproduced"]["pass"]  # two runs, one machine
    assert not k["all"]


def test_criteria_count_only_runs_under_the_latest_protocol() -> None:
    c = {"kneeError": 0.0, "measuredKnee": {"kneeRps": 150.0}, "rows": [cmp_row(100, 1.0, 1.0)]}
    runs = [{"protocol": "v3", "machine": "b"}, {"protocol": "v4", "machine": "a"}]
    assert report.criteria(c, runs)["reproduced"]["runs"] == 1


def test_criteria_without_a_step_below_the_knee() -> None:
    c = {
        "kneeError": None,
        "measuredKnee": {"kneeRps": None, "bound": "below"},
        "rows": [cmp_row(100, 90.0, 1.0)],
    }
    k = report.criteria(c, [{"protocol": "v4", "machine": "a"}])
    assert not k["knee"]["pass"] and not k["p95"]["pass"] and not k["stable"]["pass"]


def test_protocol_of() -> None:
    assert report.protocol_of({"method": "two-pass slope, ... settled first (v4)"}) == "v4"
    assert report.protocol_of({"method": "two-pass slope (v2)"}) == "v2"
    assert report.protocol_of({}) == "v1"
    assert report.protocol_of(None) == "v1"


def test_criteria_on_the_committed_results() -> None:
    # recomputed by the review from comparison.json: criterion 2 fails at these steps
    hist = report.history()
    got = {}
    for exp in report.ORDER:
        c = report.load(exp, "comparison")
        assert c is not None
        got[exp] = report.criteria(c, hist[exp])
    assert got["pg-mixed"]["p95"]["misses"] == [2238]
    assert got["pg-hot-row"]["p95"]["misses"] == [518, 570, 648, 777]
    assert got["cache-aside"]["p95"]["misses"] == [2350, 2585, 2938, 3525, 4582]
    assert got["kafka"]["p95"]["misses"] == [1760, 2000]
    assert got["pg-mixed"]["stable"]["unstable"] == []
    assert got["pg-hot-row"]["stable"]["unstable"] == [518]
    assert [exp for exp, k in got.items() if k["knee"]["pass"]] == ["pg-mixed", "kafka"]
    assert not any(k["all"] for k in got.values())
    # each ran twice under v4 (runs 4 and 5 or 6) on the same laptop, before and after a Windows
    # update: an OS update is not a second machine
    assert {exp: k["reproduced"]["runs"] for exp, k in got.items()} == dict.fromkeys(
        report.ORDER, 2
    )
    assert all(k["reproduced"]["machines"] == 1 for k in got.values())
    assert not any(k["reproduced"]["pass"] for k in got.values())  # a second machine is needed


# ---------------------------------------------------------------------------------------------
# the report
# ---------------------------------------------------------------------------------------------


def test_validation_page_is_generated_from_the_committed_results() -> None:
    text = report.DOC.read_text(encoding="utf-8")
    rendered, charts = report.render(text)
    assert rendered == text, "docs/validation.md is stale: run benchmarks/report.py"
    assert set(charts) == {f"{e}.svg" for e in report.ORDER}
    for name, svg in charts.items():
        assert (report.CHARTS / name).read_text(encoding="utf-8") == svg, name
    assert report.render(rendered) == (rendered, charts)  # byte-reproducible


def test_experiment_block_names_the_real_calibration_rates() -> None:
    block = report.experiment_block("pg-mixed")
    assert "calibration passes at 50 and 346 req/s" in block
    assert "Measured in the calibration pass at 346 req/s" in block
    assert "(50 req/s):" not in block and "Measured at 50 req/s" not in block
    assert "calibration pass at 164 req/s" in report.experiment_block("kafka")


def test_experiment_block_explains_failed_repeats(tmp_path: Path) -> None:
    # run 4 of pg-hot-row, where two repeats at 1,126 req/s had no successful request
    shutil.copytree(run.RESULTS / "pg-hot-row" / "run-4", tmp_path / "pg-hot-row")
    block = report.experiment_block("pg-hot-row", tmp_path)
    assert "| 1,126 (unstable) | - / - | - / - |" in block
    assert "At 1,126 req/s, 2 of 3 repeats had no successful request" in block
    assert "0.00" not in block


def test_environment_block_shows_each_stacks_pinning() -> None:
    envs = {e: env for e in report.ORDER if (env := report.load(e, "environment"))}
    block = report.environment_block(envs)
    assert "pg-mixed and pg-hot-row: service 1 (`2`), PostgreSQL 2 (`4,5`), k6 5 (`0,1,3,6,7`)" in (
        block
    )
    assert "k6 4 (`0,3,6,7`)" in block and "k6 2 (`6,7`)" in block
    assert "runner's defaults" not in block  # every latest run recorded its cpusets
    # pg-mixed ran before a Windows update, the other three after: one line per machine
    assert "- CPU for pg-mixed: 11th Gen Intel(R) Core(TM) i7-1165G7" in block
    assert (
        "- CPU for pg-hot-row, cache-aside and kafka: 11th Gen Intel(R) Core(TM) i7-1165G7" in block
    )
    assert block.count("Windows-11-10.0.26300-SP0") == 1
    # runs that predate recording the cpusets show the runner's defaults, and say so
    old = {k: v for k, v in envs["pg-mixed"].items() if k != "cpusets"}
    assert "runner's defaults" in report.environment_block({"pg-mixed": old})


def test_environment_block_uses_recorded_cpusets() -> None:
    env = {"cpu": "x", "os": "y", "cpusets": {"api": "0", "postgres": "1-2", "k6": "3-7"}}
    block = report.environment_block({"pg-mixed": env})
    assert "pg-mixed: service 1 (`0`), PostgreSQL 2 (`1-2`), k6 5 (`3-7`)" in block
    assert "runner's defaults" not in block


# ---------------------------------------------------------------------------------------------
# accuracy.json (the web app's /accuracy page)
# ---------------------------------------------------------------------------------------------


def test_accuracy_json_is_generated_from_the_committed_results() -> None:
    text = report.accuracy_text()
    assert report.JSON_OUT.read_text(encoding="utf-8") == text, (
        "docs/validation/accuracy.json is stale: run benchmarks/report.py --json"
    )
    assert report.accuracy_text() == text  # byte-reproducible


def test_web_copy_of_accuracy_json_matches_docs() -> None:
    # the web image is built from apps/web alone, so the page reads a copy
    assert report.WEB_JSON.read_bytes() == report.JSON_OUT.read_bytes(), (
        "apps/web/src/data/accuracy.json differs from docs/validation/accuracy.json: run "
        "benchmarks/report.py --json"
    )


def test_accuracy_json_agrees_with_the_validation_page() -> None:
    doc = report.DOC.read_text(encoding="utf-8")
    data = report.accuracy()
    assert data is not None
    assert [x["id"] for x in data["experiments"]] == report.ORDER
    for x in data["experiments"]:
        k, b = x["knee"]["text"], x["p95BelowKnee"]
        row = (
            f"| [{x['id']}](#{x['id']}) | {k['measured']} req/s | {k['predicted']} req/s | "
            f"{k['error']} | {b['text']['medianAbsError']} / {b['text']['maxAbsError']} "
            f"({b['steps']} steps) |"
        )
        assert row in doc, row
        for low in x["lowLoad"]:
            assert f"| {x['id']} | {low['text']['offered']} ({low['text']['fraction']} the " in doc
            assert f"{low['text']['diff']} ({low['text']['p95Error']}) |" in doc
        assert x["environment"]["text"] in doc
        tier = "yes" if x["tier"] == "validated" else "no"
        assert any(
            ln.startswith(f"| {x['id']} |") and ln.endswith(f"| {tier} |")
            for ln in doc.splitlines()
        )
    for runs in data["history"].values():
        for r in runs:
            t = r["text"]
            assert (
                f"| {t['measuredKnee']} req/s | {t['predictedKnee']} req/s | {t['kneeError']} |"
                in doc
            )


def test_accuracy_json_on_the_committed_results() -> None:
    data = report.accuracy()
    assert data is not None
    s = data["summary"]
    assert s["kneeErrorRangeText"] == "-51% to -8%"
    assert s["kneeWithinTolerance"] == ["pg-mixed", "kafka"]
    assert s["validated"] == [] and not any(t["validated"] for t in s["technologies"])
    assert s["inSample"] == ["pg-mixed", "pg-hot-row"]
    # the other three's latest runs are the only ones on the new OS build
    assert s["withSpread"] == ["pg-mixed"]
    assert s["spreadExceedsError"] == ["pg-mixed"]  # +/-25% against -8%
    assert s["runs"] == 14 and len(s["machines"]) == 1
    by = {x["id"]: x for x in data["experiments"]}
    mixed = by["pg-mixed"]
    assert mixed["knee"]["text"] == {
        "measured": "2,430",
        "predicted": "2,238",
        "error": "-8%",
        "absError": "8%",
    }
    assert mixed["spread"]["text"] == "1,895 to 3,162 req/s"
    assert mixed["spread"]["plusMinusText"] == "±25%"
    assert [v["run"] for v in mixed["spread"]["values"]] == ["run-2/", "run-3/", "run-4/", "latest"]
    assert by["pg-hot-row"]["runs"] == 5 and by["kafka"]["runs"] == 2
    assert all(by[e]["spread"] is None for e in ("pg-hot-row", "cache-aside", "kafka"))
    assert mixed["p95BelowKnee"]["within"] == 4 and mixed["p95BelowKnee"]["steps"] == 5
    assert mixed["p95BelowKnee"]["worst"]["text"]["offered"] == "2,238"
    assert by["kafka"]["p95BelowKnee"]["misses"] == [1760, 2000]
    assert mixed["date"] == "2026-10-28" and mixed["engineVersion"] == "0.13.0"
    assert mixed["environment"]["dockerCpus"] == 8
    assert mixed["environment"]["dockerMemoryGiB"] == 7.6
    assert mixed["environment"]["cpusetsRecorded"]
    assert sum(1 for x in mixed["steps"] if x["atOrBelowKnee"]) == 5


def test_knee_spread() -> None:
    def hist_run(
        knee: float | None, machine: str = "a", bound: str = "interpolated", os: str = "x"
    ) -> dict:
        return {
            "dir": "latest",
            "protocol": "v4",
            "calibratedAt": None,
            "machine": machine,
            "os": os,
            "measuredKnee": {"kneeRps": knee, "bound": bound},
        }

    assert report.knee_spread([]) is None
    assert report.knee_spread([hist_run(1000.0)]) is None
    assert report.knee_spread([hist_run(None, bound="above"), hist_run(1000.0)]) is None
    s = report.knee_spread([hist_run(3000.0), hist_run(None, bound="above"), hist_run(1000.0)])
    assert s is not None
    assert (s["runs"], s["minRps"], s["maxRps"]) == (2, 1000.0, 3000.0)
    assert s["plusMinus"] == pytest.approx(0.5)
    assert (s["text"], s["plusMinusText"]) == ("1,000 to 3,000 req/s", "±50%")
    # only runs on the latest run's machine: another machine's knee is not run-to-run noise
    s = report.knee_spread([hist_run(9000.0, "b"), hist_run(1000.0), hist_run(1200.0)])
    assert s is not None and s["maxRps"] == 1200.0
    # nor is the same machine's knee on another OS build
    assert report.knee_spread([hist_run(900.0, os="old"), hist_run(2000.0, os="new")]) is None


def test_accuracy_without_older_runs_has_no_spread(tmp_path: Path) -> None:
    for exp in report.ORDER:
        shutil.copytree(run.RESULTS / exp, tmp_path / exp, ignore=shutil.ignore_patterns("run-*"))
    data = report.accuracy(tmp_path)
    assert data is not None
    assert all(x["spread"] is None and x["runs"] == 1 for x in data["experiments"])
    assert data["summary"]["inSample"] == []  # no run under an earlier protocol
    assert report.accuracy(tmp_path / "none") is None


def test_report_writes_json_only_when_asked(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    doc = tmp_path / "docs" / "validation.md"
    doc.parent.mkdir()
    shutil.copy(report.DOC, doc)
    monkeypatch.setattr(report, "ROOT", tmp_path)
    monkeypatch.setattr(report, "DOC", doc)
    monkeypatch.setattr(report, "CHARTS", tmp_path / "docs" / "validation")
    monkeypatch.setattr(report, "JSON_OUT", tmp_path / "docs" / "validation" / "accuracy.json")
    monkeypatch.setattr(report, "WEB_JSON", tmp_path / "web" / "accuracy.json")
    assert report.main([]) == 0
    assert not report.JSON_OUT.exists() and not report.WEB_JSON.exists()
    assert report.main(["--json"]) == 0
    assert report.JSON_OUT.read_text(encoding="utf-8") == report.accuracy_text()
    assert report.WEB_JSON.read_bytes() == report.JSON_OUT.read_bytes()


def test_archive_keeps_the_latest_run(tmp_path: Path) -> None:
    shutil.copytree(run.RESULTS / "pg-hot-row", tmp_path / "pg-hot-row")
    shutil.copytree(
        run.RESULTS / "kafka", tmp_path / "kafka", ignore=shutil.ignore_patterns("run-*")
    )
    latest = (tmp_path / "pg-hot-row" / "comparison.json").read_bytes()
    older = len(list((tmp_path / "pg-hot-row").glob("run-*")))
    kept = run.archive("pg-hot-row", tmp_path)
    assert kept == tmp_path / "pg-hot-row" / f"run-{older + 1}"
    assert (kept / "comparison.json").read_bytes() == latest
    assert not (tmp_path / "pg-hot-row" / "measured.json").exists()
    assert run.archive("pg-hot-row", tmp_path) is None  # nothing left to keep
    assert run.archive("kafka", tmp_path) == tmp_path / "kafka" / "run-1"
    assert run.archive("cache-aside", tmp_path) is None  # no results at all
