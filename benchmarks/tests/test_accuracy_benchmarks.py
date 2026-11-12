"""The accuracy benchmarks' arithmetic (benchmarks/run.py) and report (benchmarks/report.py), on
synthetic data and on the committed results. Nothing here starts Docker."""

from __future__ import annotations

import hashlib
import json
import math
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import pytest
import report
import run

PG_MIXED = run.EXPERIMENTS["pg-mixed"]
FROZEN_VERSION = "0.16.0"
"""The frozen Python engine's version, which labels its development checks."""


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


# ---------------------------------------------------------------------------------------------
# protocol v5: staircase, replicates and the predicted range
# ---------------------------------------------------------------------------------------------


def laptop_cpu(rate: float) -> float:
    """CPU per request that falls with load, as on the laptop (0.2 ms plus 40 ms per second)."""
    return 0.2 + 40 / rate


def fake_pass(mix: str, rate: float, idle_ms_per_s: float = 1.0) -> dict[str, Any]:
    n = int(rate * run.CALIBRATION_S)
    cpu = laptop_cpu(rate)

    def t(mean: float, share: float = 1.0) -> dict[str, float]:
        count = round(n * share)
        return {
            "count": count,
            "mean": mean,
            "median": mean * 0.9,
            "p95": mean * 2,
            "p99": mean * 3,
        }

    return {
        "mix": mix,
        "rateRps": rate,
        "k6": {"p50": 1.0, "p95": 2.0, "p99": 3.0, "mean": 1.2, "sent": n, "succeeded": n},
        "service": {
            "cpuMs": cpu * n + idle_ms_per_s * run.CALIBRATION_S,
            "cpuMsPerRequest": cpu,
            "requests": n,
            "timings": {
                "own": t(0.02),
                "handler": t(0.8),
                "db_read": t(0.5, 0.9),
                "db_write": t(2.0, 0.1),
                "db_write_async": t(0.7),
            },
        },
        "containerCpuMs": {"api": cpu * n, "postgres": 0.15 * n + 2.0 * run.CALIBRATION_S},
    }


IDLE = {
    "seconds": run.IDLE_S,
    "containerCpuMsPerSecond": {"api": 1.0, "postgres": 2.0},
    "serviceCpuMsPerSecond": 1.0,
}


def fake_calibration(monkeypatch: pytest.MonkeyPatch) -> tuple[dict[str, Any], list[str]]:
    labels: list[str] = []

    def steady(exp: Any, mix: str, rate: float, label: str, discarded: list[Any]) -> Any:
        labels.append(f"{mix.split(':')[0]} {label} @{rate:g}")
        return fake_pass(mix, rate)

    monkeypatch.setattr(run, "settle", lambda exp: {"at": "t"})
    monkeypatch.setattr(run, "idle_pass", lambda exp: IDLE)
    monkeypatch.setattr(run, "steady_pass", steady)
    monkeypatch.setattr(run, "anchor", lambda exp, inputs, high: None)
    return run.calibrate(PG_MIXED), labels


def test_staircase_climbs_to_two_thirds_of_the_core_then_down_and_up(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cal, labels = fake_calibration(monkeypatch)
    # protocol v6: 50 req/s at 1.0 ms, so the next rate is 65% of a core (650); at 0.26 ms 65%
    # of the core would be 2,486, just under half the pool's bound for the writes (2 ms each:
    # 10 connections, 2,500 req/s), so 2,485; then no rise of at least 10% is left
    assert cal["staircaseRps"] == [50, 650, 2485]
    assert cal["protocol"] == "v6" and "(v6)" in cal["method"]
    assert cal["staircaseRule"] == {
        "cpuCap": 0.65, "boundShare": 0.5, "p95CapMs": 10.0, "maxRises": 5, "minRise": 1.1
    }  # fmt: skip
    main = [x for x in labels if x.startswith("read")]
    assert main == [
        "read up 1 @50", "read up 2 @650", "read up 3 @2485",
        "read down 3 @2485", "read down 2 @650", "read down 1 @50",
        "read top 3 @2485",
    ]  # fmt: skip
    # the auxiliary mix right after each top pass, at the same rate
    assert [x for x in labels if x.startswith("write_async")] == [
        "write_async write_async 1 @2485",
        "write_async write_async 2 @2485",
        "write_async write_async 3 @2485",
    ]
    assert len(cal["replicates"]) == len(cal["inputReplicates"]) == run.REPLICATES
    assert [p["passes"] for p in cal["cpuByLoad"]] == [2, 2, 3]
    by_rate = {p["rateRps"]: p["serviceCpuMsPerRequest"] for p in cal["cpuByLoad"]}
    assert by_rate[50] == pytest.approx(laptop_cpu(50), abs=1e-4)  # net of the idle 1 ms/s
    assert by_rate[2485] == pytest.approx(laptop_cpu(2485), abs=1e-4)


def test_a_disturbed_top_pass_is_replaced_not_used(monkeypatch: pytest.MonkeyPatch) -> None:
    # the first pass at the top rate stalls even on its retry (as pg-mixed's did in the trial)
    labels: list[str] = []

    def steady(exp: Any, mix: str, rate: float, label: str, discarded: list[Any]) -> Any:
        labels.append(label)
        entry = fake_pass(mix, rate)
        if label == "up 3":
            entry["k6"] |= {"p50": 3.3, "p95": 46.0}  # 14x the median
        return entry

    monkeypatch.setattr(run, "settle", lambda exp: {"at": "t"})
    monkeypatch.setattr(run, "idle_pass", lambda exp: IDLE)
    monkeypatch.setattr(run, "steady_pass", steady)
    monkeypatch.setattr(run, "anchor", lambda exp, inputs, high: None)
    cal = run.calibrate(PG_MIXED)
    # the stalled pass also stopped the staircase from rising further
    assert [x for x in labels if x.startswith(("top", "down 3"))] == ["down 3", "top 3", "top 4"]
    assert len(cal["inputReplicates"]) == run.REPLICATES
    assert [p["k6"]["p95"] for p in cal["disturbedReplicates"]] == [46.0]
    assert "median of 3 replicates" in cal["method"]


def test_cpu_per_request_is_the_top_pass_net_of_idle(monkeypatch: pytest.MonkeyPatch) -> None:
    cal, _ = fake_calibration(monkeypatch)  # protocol v6 (v5 read it the same way)
    how = cal["inputs"]["serviceTimeMs"]["how"]
    assert how.startswith("the median of 3 replicates (")
    assert "mean = Node's CPU per request at 2485 req/s net of idle" in how
    assert f"= {laptop_cpu(2485):.4f} ms" in how
    # PostgreSQL: 0.15 ms per query once its 2 ms/s at rest is taken off, over the mean time the
    # model gives a query: the read and write times it samples (writes without their flush),
    # not the 0.65 ms the service measured (0.9 x 0.5 + 0.1 x 2.0, the flush included)
    i = cal["inputs"]
    model = 0.9 * run.mean_ms(i["readServiceTimeMs"]["value"]) + 0.1 * run.mean_ms(
        i["writeServiceTimeMs"]["value"]
    )
    assert model < 0.9 * 0.5 + 0.1 * 2.0
    assert i["dbCpuShare"]["value"] == pytest.approx(0.15 / model, abs=1e-3)
    assert "the model's mean time per query" in i["dbCpuShare"]["how"]


def test_staircase_stops_on_a_slow_pass() -> None:
    slow = fake_pass("read:0.9,write:0.1", 50)
    slow["k6"]["p95"] = run.STAIRCASE_P95_CAP_MS + 1
    assert run.next_rise(PG_MIXED, slow) is None
    flat = fake_pass("read:0.9,write:0.1", 50)
    flat["service"]["cpuMsPerRequest"] = 15.0  # 65% of a core is 43 req/s: no rise
    assert run.next_rise(PG_MIXED, flat) is None


def test_staircase_caps_the_busier_hop_of_a_chain() -> None:
    entry = fake_pass("read:0.9,write:0.1", 50)
    entry["service"]["cpuMsPerRequest"] = 1.0  # the gateway
    entry["service"]["upstream"] = {
        "cpuMsPerRequest": 2.0,  # the API behind it, the busier hop
        "timings": entry["service"]["timings"],
    }
    assert [h["cpuMsPerRequest"] for h in run.hops(entry["service"])] == [1.0, 2.0]
    assert run.next_rise(PG_MIXED, entry) == 325  # 65% of one CPU at the API's 2 ms
    assert run.capacity_bound(PG_MIXED, entry) == 500  # 1 CPU / 2 ms


def test_staircase_caps_a_share_of_every_cpu_the_service_has(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    entry = fake_pass("read:0.9,write:0.1", 50)
    entry["service"]["cpuMsPerRequest"] = 2.0  # 65% of one CPU: 325 req/s
    assert run.service_cpus(PG_MIXED) == 1
    assert run.next_rise(PG_MIXED, entry) == 325
    monkeypatch.setenv("BENCH_CPUS_SERVICE", "2,3")  # a service with two CPUs: twice as far
    assert run.service_cpus(PG_MIXED) == 2
    assert run.next_rise(PG_MIXED, entry) == 650


def test_median_inputs_takes_each_input_from_its_median_replicate() -> None:
    reps = [
        {
            "serviceTimeMs": {
                "value": {"kind": "lognormal", "median": m, "p95": p},
                "how": f"rep {i}",
            },
            "clientLinkLatencyMs": {"value": c, "how": "x"},
            "measuredLowLoad": {"rateRps": 1483, "p50": c * 2},
        }
        for i, (m, p, c) in enumerate([(0.2, 0.5, 0.1), (0.3, 0.4, 0.3), (0.25, 0.9, 0.2)])
    ]
    out = run.median_inputs(reps)
    # each input whole from the replicate whose median is the median: its p95 comes with it
    assert out["serviceTimeMs"]["value"] == {"kind": "lognormal", "median": 0.25, "p95": 0.9}
    assert out["serviceTimeMs"]["how"] == "the median of 3 replicates (third): rep 2"
    assert out["clientLinkLatencyMs"]["value"] == 0.2
    assert out["measuredLowLoad"] == {"rateRps": 1483, "p50": 0.4}  # number by number
    # a disturbed replicate (a slow outlier) never sets an input
    reps[0]["serviceTimeMs"] = {
        "value": {"kind": "lognormal", "median": 0.9, "p95": 9.0},
        "how": "x",
    }
    assert run.median_inputs(reps)["serviceTimeMs"]["value"]["median"] == 0.3
    assert (
        run.primary({"kind": "exponential", "mean": 0.005}) == 0.005 and run.primary(True) is None
    )


def test_median_inputs_keeps_an_input_the_first_replicate_lacks() -> None:
    # cache-aside's latest v6 run: no mark-compact in the first replicate's pass
    def rep(pause: float, every: int | None) -> dict[str, Any]:
        gc: dict[str, Any] = {"gcPauseMs": {"value": pause, "how": "x"}}
        if every is not None:
            gc["gcEveryRequests"] = {"value": every, "how": "x"}
        return gc

    out = run.median_inputs([rep(0, None), rep(9.9517, 48766), rep(9.7368, 32510)])
    assert out["gcPauseMs"]["value"] == 9.7368
    assert out["gcEveryRequests"]["value"] == 32510  # of the two that saw one
    assert out["gcEveryRequests"]["how"] == "the median of the 2 of 3 replicates with it (third): x"
    # no pause at the median: no spacing either
    out = run.median_inputs([rep(0, None), rep(0, None), rep(9.9166, 33500)])
    assert out["gcPauseMs"]["value"] == 0 and "gcEveryRequests" not in out


def test_knee_range_and_whether_it_holds_the_measured_knee() -> None:
    """compare() records whether the predicted range held the measured knee, for W28's ranges
    and for engine 0.14.0's (the replicate sets' knees, kept in its predictions)."""
    old = {"lowRps": 900, "highRps": 1100, "nominalCoverage": 0.5, "replicates": 3}
    assert run.in_range(old, {"kneeRps": 950.0}) is True
    assert run.in_range(old, {"kneeRps": 1200.0}) is False
    assert run.in_range(old, {"kneeRps": None, "bound": "above", "aboveRps": 1050}) is True
    assert run.in_range(old, {"kneeRps": None, "bound": "above", "aboveRps": 1200}) is False
    assert run.in_range(None, {"kneeRps": 950.0}) is None
    fit = {
        "inputs": {"serviceTimeMs": {"sigma": 0.05}},
        "fallbackSigma": 0.05,
        "model": {"sigma": 0.1},
    }
    predictions, measured = synthetic()  # the measured knee is 300 req/s
    predictions["kneeRange"] = run.knee_range_of(340, {"serviceTimeMs": -1.0}, fit)
    c = run.compare(PG_MIXED, predictions, measured)
    assert c["kneeRange"]["containsMeasured"] is True and c["kneeRange"]["nominalCoverage"] == 0.8
    narrow = {**fit, "model": {"sigma": 0.0}, "inputs": {"serviceTimeMs": {"sigma": 0.01}}}
    predictions["kneeRange"] = run.knee_range_of(340, {"serviceTimeMs": -1.0}, narrow)
    assert run.compare(PG_MIXED, predictions, measured)["kneeRange"]["containsMeasured"] is False


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
    assert len(dirs) == 67
    for exp, d in dirs:
        committed = json.loads((d / "comparison.json").read_text(encoding="utf-8"))
        assert run.comparison_of(exp, d) == committed, d


def test_recompare_writes_every_comparison(tmp_path: Path) -> None:
    shutil.copytree(run.RESULTS / "pg-hot-row", tmp_path / "pg-hot-row")
    for f in (tmp_path / "pg-hot-row").rglob("comparison.json"):
        f.unlink()
    written = run.recompare(tmp_path)
    runs = [f"run-{i}" for i in range(1, 14)]
    assert [p.parent.name for p in written] == [*runs, "pg-hot-row"]
    run4 = json.loads(written[3].read_text(encoding="utf-8"))
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
    # the services have their memory fitted from the staircase (W24)
    service = {p["container"]: p for p in by["pg-mixed"]["parts"]}["api"]
    assert service["top"]["text"]["measuredMemory"] == "148 MiB"
    assert service["top"]["text"]["predictedMemory"] == "155 MiB"
    parts = {p["container"]: p for p in by["cache-aside"]["parts"]}
    assert list(parts) == ["api", "postgres", "redis"]
    # the top step at or below both knees: the model's (5,652 req/s), not the measured (6,752)
    assert by["cache-aside"]["topRps"] == 5650
    assert parts["redis"]["top"]["text"]["coresError"] == "+23%"  # its CPU per command (W24)
    assert parts["api"]["top"]["text"]["predictedMemory"] == "158 MiB"
    kafka = {p["container"]: p for p in by["kafka"]["parts"]}
    assert not kafka["kafka"]["modelled"]["cores"]
    assert kafka["kafka"]["notModelled"]["cores"] == report.NOT_MODELLED["kafka"]
    assert kafka["postgres"]["notModelled"]["memory"] == report.NO_MEMORY_MODEL["postgres"]
    cmps = [c for e in report.ORDER if (c := report.load(e, "comparison"))]
    block = report.resources_block(cmps)
    assert (
        "| [pg-mixed](#pg-mixed) | Node.js service | 3,900 | 0.79 | 0.75 | -5% | 4% (3) | 148 MiB | 155 MiB | +5% |"
        in block
    )
    assert (
        "| [cache-aside](#cache-aside) | Redis | 5,650 | 0.19 | 0.23 | +23% | 15% (5) | 11 MiB | "
        "31 MiB | +188% |" in block
    )
    # memory is shown only where it was measured, never a prediction alone
    assert (
        "| [kafka](#kafka) | Node.js consumer | 2,088 | 0.30 | not modelled | - | - | 182 MiB | - | - |"
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


def test_vm_cpu_times_reads_steal(monkeypatch: pytest.MonkeyPatch) -> None:
    stat = "cpu  100 0 50 800 10 0 5 35 0 0\ncpu0 1 2 3 4 5 6 7 8 0 0\n"
    monkeypatch.setattr(run, "compose", lambda *a, **k: stat)
    assert run.vm_cpu_times() == (35, 1000)


DATABASE_INPUTS = {
    "readServiceTimeMs",
    "writeServiceTimeMs",
    "commitFlushMs",
    "dbCpuShare",
    "dbLinkLatencyMs",
}
"""The inputs ``database_inputs`` derives anew since engine 0.15.0 (W22)."""


def test_split_inputs_reproduce_every_latest_calibration() -> None:
    # the latest runs (the held-out campaign and the repeat of 4 Nov 2026) were calibrated under
    # protocol v6 by today's code, one function per part and then anchored: the very inputs each
    # one used. W27: with a quiet contention check of the machine, the CPU-time inputs are at
    # full speed and the design gains its host machine.
    anew = {"host", "clientLinkLatencyMs", "anchor", *run.MACHINE_SCALED}
    skip = anew if run.machine_fit() is not None else set()
    for exp in report.ORDER:
        cal = json.loads((run.RESULTS / exp / "calibration.json").read_text(encoding="utf-8"))
        assert cal["protocol"] == "v6", exp
        again = run.rederive_inputs(run.EXPERIMENTS[exp], cal)
        assert {k: v for k, v in again.items() if k not in skip} == {
            k: v for k, v in cal["inputs"].items() if k not in skip
        }, exp
        assert {"dbLinkLatencyMs", "dbCpuShare"} <= set(again), exp
    # since W29 the service time's spread is anchored at the top pass: narrowed from the own
    # time's, its mean still a replicate's CPU per request
    cal = json.loads((run.RESULTS / "cache-aside" / "calibration.json").read_text(encoding="utf-8"))
    st = cal["inputs"]["serviceTimeMs"]
    assert st["value"]["p95"] / st["value"]["median"] < 7
    assert "; spread anchored: p95/median " in st["how"]
    means = [run.mean_ms(r["serviceTimeMs"]["value"]) for r in cal["inputReplicates"]]
    assert min(abs(run.mean_ms(st["value"]) / m - 1) for m in means) < 0.002
    # the GC inputs are the median of the replicates that saw a mark-compact
    cal = json.loads((run.RESULTS / "pg-mixed" / "calibration.json").read_text(encoding="utf-8"))
    every = cal["inputs"]["gcEveryRequests"]
    assert every["value"] == 48930
    assert every["how"].startswith("the median of the 2 of 3 replicates with it (third)")


def test_database_inputs_keep_the_measured_round_trip() -> None:
    # pg-mixed's last v5 top pass: PostgreSQL's CPU per query becomes its time in the model, the
    # rest of each round trip the service measured becomes the link, and the share is the CPU
    path = run.RESULTS / "pg-mixed" / "run-10" / "calibration.json"
    cal = json.loads(path.read_text(encoding="utf-8"))
    rep = {**cal["replicates"][0], "low": cal["passes"]["low"], "idle": cal["idle"]}
    got = run.database_inputs(PG_MIXED, rep)
    read = rep["high"]["service"]["timings"]["db_read"]
    rt = run.mean_ms(run.lognormal(read["median"], read["p95"]))
    s, leg = got["readServiceTimeMs"]["value"], got["dbLinkLatencyMs"]["value"]
    assert run.mean_ms(s) + 2 * run.mean_ms(leg) == pytest.approx(rt, rel=1e-3)
    assert run.mean_ms(s) < 0.75 * rt  # most of the round trip is PostgreSQL's
    assert got["dbCpuShare"]["value"] == pytest.approx(1.0, abs=0.02)
    # the writes' flush: fitted in the model to the wait the service measured (no statistics)
    assert "fitted so that the model's writes wait" in got["commitFlushMs"]["how"]
    assert got["commitFlushMs"]["model"]["commitsPerFlush"] == pytest.approx(1.0, abs=0.05)


def pg_snap(calls: int, exec_ms: float, syncs: int, ticks: int) -> dict[str, Any]:
    """What pg_snapshot reads: the statistics (PG_STATS_SQL) and the processes' CPU ticks."""
    return {
        "stats": {
            "statements": [
                {"query": "UPDATE items SET value = value + $1 WHERE id = $2", "calls": calls,
                 "rows": calls, "meanExecMs": exec_ms, "stddevExecMs": 0.01,
                 "minExecMs": 0.01, "maxExecMs": 1.0},
                {"query": "BEGIN", "calls": calls, "rows": 0, "meanExecMs": 0.001,
                 "stddevExecMs": 0.0, "minExecMs": 0.0, "maxExecMs": 0.1},
            ],
            "wal": {"records": 10 * syncs, "bytes": 1000 * syncs, "buffersFull": 0,
                    "writes": syncs, "syncs": syncs, "writeMs": 0.1 * syncs,
                    "syncMs": 0.5 * syncs},
            "database": {"commits": calls, "rollbacks": 0, "activeMs": 0.3 * calls,
                         "sessionMs": 1000.0},
        },
        "processes": {"40": ("backends", ticks), "41": ("walWriter", ticks // 10)},
    }  # fmt: skip


def test_pg_end_counts_the_pass_alone() -> None:
    before, after = pg_snap(0, 0.0, 100, 500), pg_snap(9000, 0.05, 2100, 800)
    w = run.pg_window(before, after)
    # pg_stat_statements was cleared before the pass: its rows are the pass's own
    assert [s["kind"] for s in w["statements"]] == ["write"]
    assert w["wal"]["syncs"] == 2000 and w["database"]["commits"] == 9000
    assert w["cpuMsByProcess"] == {"backends": 3000.0, "walWriter": 300.0}
    entry = {"postgres": w}
    assert run.pg_statement(entry, "write") == {"calls": 9000, "meanExecMs": 0.05}
    assert run.pg_statement(entry, "read") is None
    flush = run.pg_flush(entry)
    assert flush is not None
    assert flush["flushMs"] == pytest.approx(0.6) and flush["syncMs"] == pytest.approx(0.5)
    assert flush["commitsPerFlush"] == pytest.approx(4.5)
    assert run.pg_flush({}) is None


def test_database_inputs_from_postgresqls_own_statistics() -> None:
    # W22's trial (development data): the top pass recorded PostgreSQL's statistics, so the
    # flush is PostgreSQL's own and its executor time is kept beside the model's
    trial = run.RESULTS / "trials" / "2026-10-31-pg-hot-row-w22" / "calibration.json"
    cal = json.loads(trial.read_text(encoding="utf-8"))
    rep = {**cal["replicates"][0], "low": cal["passes"]["low"], "idle": cal["idle"]}
    got = run.database_inputs(run.EXPERIMENTS["pg-hot-row"], rep)
    flush = got["commitFlushMs"]
    assert "pg_stat_wal" in flush["how"]
    measured = run.pg_flush(rep["high"])
    assert measured is not None
    assert run.mean_ms(flush["value"]) == pytest.approx(measured["flushMs"], rel=1e-3)
    assert flush["postgres"]["commitsPerFlush"] == pytest.approx(1.0, abs=0.05)
    assert flush["model"]["commitsPerFlush"] == pytest.approx(1.0, abs=0.01)
    # the UPDATE in the executor: a small part of PostgreSQL's time per UPDATE
    executor = got["writeServiceTimeMs"]["postgres"]["executorMs"]
    assert 0 < executor < 0.5 * run.mean_ms(got["writeServiceTimeMs"]["value"])


def test_fit_flush_reproduces_the_measured_wait_in_the_model() -> None:
    # writes to one row at 550/s over a link that jitters their arrival: the model's writes also
    # queue for the row's lock, so the flush that gives a 0.9 ms wait for lock and flush
    # together is shorter than 0.9 ms
    hot = run.EXPERIMENTS["pg-hot-row"]
    v = {
        "writeServiceTimeMs": run.lognormal(0.25, 0.6),
        "dbCpuShare": 1.0,
        "dbLinkLatencyMs": run.lognormal(0.2, 1.5),
    }
    median, db = run.fit_flush(hot, v, 550, 0.0, 0.9, 1.5)
    assert db["meanLockWaitMs"] > 0.05
    assert run.mean_ms(run.lognormal(median, median * 1.5)) < 0.9 - 0.05
    assert db["meanLockWaitMs"] + db["meanCommitWaitMs"] == pytest.approx(0.9, rel=0.005)
    # at a rate where no write waits for another, the flush is the wait (in the mean; the model
    # samples about 750 flushes, whose mean is within a few percent of the distribution's)
    median, db = run.fit_flush(hot, v, 50, 0.0, 0.9, 1.5)
    assert db["meanLockWaitMs"] == pytest.approx(0.0, abs=1e-3)
    assert db["meanCommitWaitMs"] == pytest.approx(0.9, rel=0.005)
    assert run.mean_ms(run.lognormal(median, median * 1.5)) == pytest.approx(0.9, rel=0.03)


def test_server_time_and_link_keep_the_round_trip() -> None:
    rt = {"median": 0.238, "p95": 0.593}
    s = run.server_time(0.17, rt)
    assert run.mean_ms(s) == pytest.approx(0.17, rel=1e-3)
    assert s["p95"] / s["median"] == pytest.approx(0.593 / 0.238, rel=1e-3)  # its shape
    leg = run.link_leg(rt, s)
    assert isinstance(leg, dict)
    (m_rt, v_rt), (m_s, v_s), (m_l, v_l) = (
        run.lognormal_moments(run.lognormal(0.238, 0.593)),
        run.lognormal_moments(s),
        run.lognormal_moments(leg),
    )
    assert m_s + 2 * m_l == pytest.approx(m_rt, rel=1e-3)  # the round trip's mean ...
    assert v_s + 2 * v_l == pytest.approx(v_rt, rel=1e-2)  # ... and variance
    # never more server time than the round trip; without a CPU figure, the round trip itself
    assert run.mean_ms(run.server_time(5.0, rt)) == pytest.approx(
        run.mean_ms(run.lognormal(0.238, 0.593))
    )
    assert run.server_time(None, rt) == run.lognormal(0.238, 0.593)
    assert run.link_leg(rt, run.server_time(None, rt)) == 0.0


def test_statement_kind_whatever_the_driver_wrote() -> None:
    assert run.statement_kind("SELECT value FROM items WHERE id = $1") == "read"
    assert run.statement_kind("select value from items where id=$1") == "read"
    assert run.statement_kind("UPDATE items SET value = value + $1 WHERE id = $2") == "write"
    assert run.statement_kind("INSERT INTO events (item_id, produced_at) VALUES ($1, $2)") == (
        "insert"
    )
    assert run.statement_kind("BEGIN") is None


def test_pg_process_kind() -> None:
    assert run.pg_process_kind("postgres: bench bench 172.18.0.4(43122) idle") == "backends"
    assert run.pg_process_kind("postgres: walwriter ") == "walWriter"
    assert run.pg_process_kind("postgres: checkpointer ") == "background"
    assert run.pg_process_kind("postgres -c shared_buffers=256MB") == "background"
    assert run.pg_process_kind("postgres: bench bench [local] SELECT") is None  # the probe
    assert run.pg_process_kind("sh -c for d in /proc") is None


def test_simulate_reports_what_the_database_did() -> None:
    cal = json.loads((run.RESULTS / "pg-mixed" / "calibration.json").read_text(encoding="utf-8"))
    r = run.simulate(run.design(PG_MIXED, cal["inputs"], 500, warm=2, duration=5))
    assert set(r["database"]) == {"commitsPerFlush", "meanLockWaitMs", "meanCommitWaitMs"}
    assert r["database"]["commitsPerFlush"] >= 1


def stand_in_report(doc: dict[str, Any]) -> dict[str, Any]:
    """A report of the design with what ``run.simulate`` reads: a summary, no parts, a digest."""
    return {
        "summary": {"latencyMs": {"p50": 5.0, "p95": 12.5, "p99": 20.0, "mean": 6.0},
                    "successRate": 1.0, "throughputRps": doc["workload"]["rps"]},
        "nodes": [],
        "timeseries": {"t": [], "bucketS": 1.0},
        "window": {"warmupS": doc["workload"]["warmupS"], "durationS": doc["workload"]["durationS"]},
        "provenance": {"resultDigest": "d" * 64},
    }  # fmt: skip


def test_simulate_reads_the_report_archsim_run_writes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # stands for `archsim run FILE --out REPORT --max-wall-seconds S`: a report of the design
    def fake_cli(command: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
        assert command[:2] == [str(run.JAVA_CLI), "run"]
        assert command[3:] == ["--out", command[4], "--max-wall-seconds", "3600"]
        doc = json.loads(Path(command[2]).read_text(encoding="utf-8"))
        Path(command[4]).write_text(json.dumps(stand_in_report(doc)), encoding="utf-8")
        return subprocess.CompletedProcess(command, 0, "", "")

    cal = json.loads((run.RESULTS / "pg-mixed" / "calibration.json").read_text(encoding="utf-8"))
    doc = run.design(PG_MIXED, cal["inputs"], 500, warm=2, duration=5)
    monkeypatch.setattr(run.subprocess, "run", fake_cli)

    result = run.simulate(doc)

    assert (result["offeredRps"], result["p95"], result["resultDigest"]) == (500, 12.5, "d" * 64)


def test_simulate_says_why_archsim_run_failed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def failing_cli(command: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
        return subprocess.CompletedProcess(command, 1, "", "Cannot simulate: document is invalid\n")

    monkeypatch.setattr(run.subprocess, "run", failing_cli)

    with pytest.raises(
        RuntimeError, match=r"archsim run failed \(1\): Cannot simulate: document is invalid$"
    ):
        run.simulate({"workload": {"rps": 1}})


JAVA_VERSION = "1.0.0"
JAVA_REPORT_VERSION = "1.15"
"""The stand-in Java engine's version and its reports'."""


def on_the_java_engine(monkeypatch: pytest.MonkeyPatch) -> None:
    """The Java engine installed, saying it is 1.0.0, its reports 1.15."""
    monkeypatch.delenv(run.ENGINE_VARIABLE, raising=False)
    monkeypatch.setattr(run, "engine_label", lambda: JAVA_VERSION)
    monkeypatch.setattr(run.engine_functions, "report_version", lambda: JAVA_REPORT_VERSION)


def installed_cli(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, code: int, out: str, err: str = ""
) -> None:
    """An installed ``archsim`` that exits with ``code``, printing ``out`` and ``err``."""
    cli_path = tmp_path / "bin" / "archsim"
    cli_path.parent.mkdir()
    cli_path.write_text("", encoding="utf-8")

    def cli(command: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
        assert command == [str(cli_path), "--version"]
        return subprocess.CompletedProcess(command, code, out, err)

    monkeypatch.setattr(run, "JAVA_CLI", cli_path)
    monkeypatch.setattr(run.subprocess, "run", cli)


def test_engine_label_is_what_archsim_version_prints(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    installed_cli(monkeypatch, tmp_path, 0, "archsim, engine 1.0.0\r\n")

    assert run.engine_label() == "1.0.0"


def test_engine_label_refuses_what_it_cannot_read(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    installed_cli(monkeypatch, tmp_path, 0, "archsim 1.0.0\n")

    with pytest.raises(RuntimeError, match=r"archsim --version printed 'archsim 1\.0\.0'"):
        run.engine_label()


def test_engine_label_says_why_archsim_version_failed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    installed_cli(monkeypatch, tmp_path, 1, "", "ERROR: JAVA_HOME is set to an invalid directory\n")

    with pytest.raises(
        RuntimeError,
        match=r"archsim --version failed \(1\): ERROR: JAVA_HOME is set to an invalid directory$",
    ):
        run.engine_label()


def test_engine_label_without_the_java_engine_says_how_to_install_it(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(run, "JAVA_CLI", tmp_path / "bin" / "archsim")

    with pytest.raises(
        RuntimeError, match=r"not installed .*: run \./gradlew :archsim-cli:installDist"
    ):
        run.engine_label()


def predicting_without_simulating(monkeypatch: pytest.MonkeyPatch) -> None:
    """``predict`` with a knee of 1,000 req/s, no range fit and stand-in simulations."""
    monkeypatch.setattr(run, "predicted_knee", lambda exp, inputs: {"kneeRps": 1000.0})
    monkeypatch.setattr(run, "read_range_fit", lambda: None)
    monkeypatch.setattr(run, "ProcessPoolExecutor", ThreadPoolExecutor)
    monkeypatch.setattr(run, "simulate", lambda doc: {"offeredRps": doc["workload"]["rps"]})
    monkeypatch.setattr(run, "provenance", lambda: {})


def test_predict_names_the_engines_versions(monkeypatch: pytest.MonkeyPatch) -> None:
    on_the_java_engine(monkeypatch)
    predicting_without_simulating(monkeypatch)
    cal = json.loads((run.RESULTS / "pg-mixed" / "calibration.json").read_text(encoding="utf-8"))

    prediction = run.predict(PG_MIXED, cal)

    assert (prediction["engineVersion"], prediction["reportVersion"]) == (
        JAVA_VERSION,
        JAVA_REPORT_VERSION,
    )


def test_provenance_hashes_the_java_engines_three_source_trees(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    on_the_java_engine(monkeypatch)
    trees = [f"{p} {run.git('rev-parse', f'HEAD:{p}')}" for p in run.JAVA_ENGINE_SOURCES]

    assert (
        run.provenance()["engineSources"] == hashlib.sha256("\n".join(trees).encode()).hexdigest()
    )


def test_java_build_is_a_hash_of_the_installed_jars(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    (tmp_path / "lib").mkdir()
    (tmp_path / "lib" / "b.jar").write_bytes(b"second")
    (tmp_path / "lib" / "a.jar").write_bytes(b"first")
    monkeypatch.setattr(run, "JAVA_CLI", tmp_path / "bin" / "archsim")
    expected = hashlib.sha256(
        b"a.jar\0"
        + hashlib.sha256(b"first").digest()
        + b"b.jar\0"
        + hashlib.sha256(b"second").digest()
    ).hexdigest()

    assert run.java_build() == expected


@pytest.mark.parametrize("engine", ["python", "Java"])
def test_a_bench_engine_other_than_java_is_refused_before_a_stack_starts(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str], engine: str
) -> None:
    def started(*_: Any, **__: Any) -> None:
        raise AssertionError("the stack started")

    monkeypatch.setenv(run.ENGINE_VARIABLE, engine)
    monkeypatch.setattr(run, "stack_up", started)

    with pytest.raises(SystemExit) as refused:
        run.main(["postgres"])
    assert refused.value.code == 2
    assert (
        f"BENCH_ENGINE={engine!r}: the harness runs the Java engine only" in capsys.readouterr().err
    )


@pytest.mark.parametrize("engine", [None, "", "java"])
def test_main_with_bench_engine_unset_empty_or_java_refuses_no_command(
    monkeypatch: pytest.MonkeyPatch, engine: str | None
) -> None:
    ran: list[str] = []

    def smoke(exp: run.Experiment, keep: bool = False) -> list[str]:
        ran.append(exp.id)
        return []

    on_the_java_engine(monkeypatch)
    if engine is not None:
        monkeypatch.setenv(run.ENGINE_VARIABLE, engine)
    monkeypatch.setattr(run, "smoke", smoke)

    assert run.main(["smoke", "pg-mixed"]) == 0
    assert ran == ["pg-mixed"]


def test_dev_checks_reproduce_the_committed_predictions() -> None:
    # engine 0.14.0 against every kept run: the protocols since v3 derive the same inputs
    # today, and then simulate exactly what their predictions said, step by step
    checks = report.dev_checks()["0.14.0"]
    assert sorted(checks) == sorted(report.TUNED_ON)  # never the held-out experiments
    rows = [c for runs in checks.values() for c in runs.values()]
    assert len(rows) == 26  # 28 kept runs less the two calibrated from one pass
    # every run under protocol v5 too: its replicate sets derived again, and their medians
    v5 = [c for c in rows if c["calibrationMethod"].endswith("(v5)")]
    assert len(v5) == 14 and all(c["inputsAsCalibrated"] for c in v5)
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
    row = (
        "| pg-hot-row | `pg-hot-row/run-5/` | 2026-10-30T10:32:56Z | yes | 814 | yes | 519 (-36%) |"
    )
    assert row in text
    # disturbed runs are listed but left out of the medians: 19 of the 26 count
    assert "| `pg-mixed/run-6/` | 2026-10-30T21:30:44Z | yes | 1,089 | no: disturbed |" in text
    assert (
        "Engine 0.14.0: median absolute knee error 20% over the latest run it checked of each "
        "experiment, 24% over all 19 counted runs." in text
    )
    # the v6 campaign's runs, checked with engine 0.16.0 only
    assert (
        "Engine 0.16.0: median absolute knee error 15% over the latest run it checked of each "
        "experiment, 19% over all 31 counted runs." in text
    )
    assert "_No development checks yet._" in report.dev_text(Path("nowhere"))


def test_the_java_engine_reproduces_every_kept_knee_of_the_frozen_engine() -> None:
    assert report.java_engine() == {
        "version": "1.0.0",
        "reproduces": "0.16.0",
        "sameKnees": 65,
        "keptCalibrations": 65,
        "text": "Re-simulated, in-sample: engine 1.0.0 (Java) re-simulates every kept calibration "
        "with exactly 0.16.0's knees (65 of 65); the latest predictions shown are 0.16.0's, which "
        "1.0.0 reproduces.",
    }


def test_the_latest_predictions_shown_are_the_frozen_engines_the_java_engine_reproduces() -> None:
    java_engine = report.java_engine()

    assert java_engine is not None
    assert {(report.load(e, "predictions") or {}).get("engineVersion") for e in report.ORDER} == {
        java_engine["reproduces"]
    }


def test_the_java_engine_counts_the_same_knees_as_its_parity_report() -> None:
    java_engine = report.java_engine()
    parity = (run.HERE.parent / "docs" / "validation" / "java-parity.md").read_text(
        encoding="utf-8"
    )

    assert java_engine is not None
    assert f", {java_engine['sameKnees']} the same;" in parity


def test_the_java_engine_missing_knees_says_how_many_it_reproduces(tmp_path: Path) -> None:
    frozen, java = (tmp_path / "dev" / label / "kafka" for label in report.JAVA_ENGINE_KNEES)
    for folder, knees in ((frozen, [1000.0, 2000.0, 900.0]), (java, [1000.0, 2100.0])):
        folder.mkdir(parents=True)
        for i, knee in enumerate(knees):
            check = {"comparison": {"predictedKnee": {"kneeRps": knee}}}
            (folder / f"{i}.json").write_text(json.dumps(check), encoding="utf-8")

    java_engine = report.java_engine(tmp_path)

    assert java_engine is not None
    assert [java_engine[k] for k in ("sameKnees", "keptCalibrations", "text")] == [
        1,
        3,
        "Re-simulated, in-sample: engine 1.0.0 (Java) re-simulates 1 of 3 kept calibrations with "
        "exactly 0.16.0's knees; the latest predictions shown are 0.16.0's.",
    ]


def test_the_java_engine_without_checks_says_nothing(tmp_path: Path) -> None:
    assert report.java_engine(tmp_path) is None


def test_the_validation_page_and_accuracy_json_say_what_the_java_engine_reproduces() -> None:
    data = report.accuracy()
    java_engine = report.java_engine()

    assert data is not None and java_engine is not None
    assert data["javaEngine"] == java_engine
    assert (
        f"{java_engine['text'].removesuffix('.')} ([java-parity.md](validation/java-parity.md))."
        in report.DOC.read_text(encoding="utf-8")
    )


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


def test_p95_within_a_factor_of_two_either_way() -> None:
    assert report.p95_within(10.0, 19.9)  # double
    assert not report.p95_within(10.0, 20.1)
    assert report.p95_within(10.0, 5.1)  # half
    assert not report.p95_within(10.0, 4.9)  # too low fails as surely as too high
    assert not report.p95_within(100.0, 0.5)


def test_p95_within_3_ms_where_a_factor_is_too_strict() -> None:
    assert report.p95_within(1.0, 3.9)
    assert not report.p95_within(1.0, 4.1)
    assert report.p95_within(4.0, 1.1)  # a factor of 3.6, but only 2.9 ms below
    assert report.p95_within(2.0, 0.0)


def test_p95_within_needs_both_values() -> None:
    assert not report.p95_within(None, 1.0)
    assert not report.p95_within(1.0, None)


def test_p95_factor_is_symmetric() -> None:
    assert report.p95_factor(10.0, 20.0) == report.p95_factor(10.0, 5.0) == 2.0
    assert report.p95_factor(10.0, 10.0) == report.p95_factor(0.0, 0.0) == 1.0
    assert report.p95_factor(10.0, 0.0) == report.p95_factor(0.0, 10.0) == math.inf


def cmp_row(rps: float, meas: float | None, pred: float | None, unstable: bool = False) -> dict:
    return {
        "offeredRps": rps,
        "measured": {"p95": meas},
        "predicted": {"p95": pred},
        "unstable": unstable,
    }


def passing_run(machine: str = "a", error: float = -0.2, **kw: Any) -> dict[str, Any]:
    """A kept run as history() records it, which met criteria 2 and 3a."""
    checks = {"kneeError": error, "steps": 2, "misses": [], "unstable": []}
    checks |= {"p95Pass": True, "stablePass": True} | kw
    return {"protocol": "v5", "machine": machine, "checks": checks, "kneeError": error}


def test_criteria_all_met() -> None:
    c = {
        "kneeError": -0.2,
        "measuredKnee": {"kneeRps": 250.0},
        "rows": [cmp_row(100, 1.0, 2.5), cmp_row(200, 10.0, 15.0), cmp_row(300, 80.0, 900.0, True)],
    }
    # three runs on machine a (the last is the one compared), one on machine b
    runs = [passing_run("b"), passing_run(), passing_run(), {"protocol": "v5", "machine": "a"}]
    k = report.criteria(c, runs)
    assert k["all"]
    assert k["knee"] == {
        "pass": True,
        "error": -0.2,
        "runs": 3,
        "disturbedRuns": 0,
        "thisRun": -0.2,
    }
    assert k["p95"] == {"pass": True, "steps": 2, "misses": [], "runs": 3, "runsPassing": 3}
    assert k["reproduced"] == {"pass": True, "protocol": "v5", "runs": 4, "machines": 2}


def test_criteria_judge_the_median_over_repeated_runs() -> None:
    c = {"kneeError": -0.1, "measuredKnee": {"kneeRps": 150.0}, "rows": [cmp_row(100, 1.0, 1.0)]}
    runs = [passing_run(error=-0.4), passing_run(error=-0.3), {"protocol": "v5", "machine": "a"}]
    k = report.criteria(c, runs)
    assert (k["knee"]["error"], k["knee"]["pass"]) == (-0.3, False)  # median of -0.4, -0.3, -0.1
    # criteria 2 and 3a hold in 2 of 3 runs: met
    runs = [passing_run(p95Pass=False), passing_run(), {"protocol": "v5", "machine": "a"}]
    k = report.criteria(c, runs)
    assert k["p95"]["pass"] and k["p95"]["runsPassing"] == 2 and k["knee"]["pass"]
    # in 1 of 3: not met
    runs = [
        passing_run(p95Pass=False),
        passing_run(p95Pass=False),
        {"protocol": "v5", "machine": "a"},
    ]
    assert not report.criteria(c, runs)["p95"]["pass"]
    # fewer than three runs never meet them, whatever the error
    k = report.criteria(c, [passing_run(), {"protocol": "v5", "machine": "a"}])
    assert k["knee"]["runs"] == 2 and not k["knee"]["pass"] and not k["p95"]["pass"]


def cmp_step(rps: float, p95: float | None, success: float = 1.0) -> dict[str, Any]:
    """A comparison row with what the objective reads (p95 at most 50 ms, success at least 99%)."""
    return {"offeredRps": rps, "measured": {"p95": p95, "successRate": success}}


def test_disturbed_steps_missed_below_a_step_that_met_the_objective() -> None:
    # pg-mixed, 30 October run 2: missed at 2,550 to 4,590, met at 5,100, missed above
    rows = [cmp_step(1275, 8.5), cmp_step(2550, 102.1, 0.927), cmp_step(3825, 119.6, 0.798)]
    rows += [
        cmp_step(4590, 190.3, 0.737),
        cmp_step(5100, 46.6, 0.998),
        cmp_step(5610, 136.7, 0.858),
    ]
    assert report.disturbed_steps(rows) == [2550, 3825, 4590]
    # a miss on success rate alone counts too, and the order of the rows does not matter
    assert report.disturbed_steps(
        [cmp_step(300, 9.0), cmp_step(200, 9.0, 0.9), cmp_step(100, 2.0)]
    ) == [200]
    # a stack that stops meeting the objective and never meets it again was not disturbed
    assert (
        report.disturbed_steps([cmp_step(100, 2.0), cmp_step(200, 60.0), cmp_step(300, None, 0.4)])
        == []
    )
    assert report.disturbed_steps([cmp_step(100, 90.0), cmp_step(200, 95.0)]) == []
    assert report.disturbed_steps([]) == []
    # rows without a success rate (older tests' rows) never meet it, so none is disturbed
    assert report.disturbed_steps([cmp_row(100, 99.0, 1.0), cmp_row(200, 1.0, 1.0)]) == []


def test_disturbed_runs_are_not_counted() -> None:
    c = {
        "kneeError": 0.2,
        "measuredKnee": {"kneeRps": 150.0},
        "rows": [cmp_step(100, 1.0) | {"predicted": {"p95": 1.0}}],
    }
    runs = [
        passing_run(error=2.9, disturbed=[2112]),
        passing_run(error=0.1),
        passing_run(error=2.5, disturbed=[2550, 3825]),
        passing_run(error=0.3),
        {"protocol": "v5", "machine": "a"},
    ]
    k = report.criteria(c, runs)
    # median of 0.1, 0.3 and this run's 0.2; the two disturbed runs are left out
    assert (k["knee"]["runs"], k["knee"]["disturbedRuns"], k["knee"]["error"]) == (3, 2, 0.2)
    assert k["knee"]["pass"] and k["p95"]["runs"] == 3
    # but every run under the protocol still counts as having run on this machine (3b)
    assert k["reproduced"]["runs"] == 5
    # a disturbed latest run is left out too
    worse = c | {"rows": [cmp_step(100, 80.0) | {"predicted": {"p95": 1.0}}, cmp_step(200, 2.0)]}
    k = report.criteria(worse, runs)
    assert (k["knee"]["runs"], k["knee"]["disturbedRuns"]) == (2, 3) and not k["knee"]["pass"]
    assert report.run_checks(worse)["disturbed"] == [100]


def test_repeatability_and_spread_leave_out_disturbed_runs() -> None:
    def r(error: float, knee: float, disturbed: list[float] | None = None) -> dict[str, Any]:
        return {
            "dir": "latest",
            "protocol": "v5",
            "calibratedAt": None,
            "machine": "a",
            "os": "x",
            "kneeError": error,
            "kneeRange": {"nominalCoverage": 0.5, "containsMeasured": False},
            "measuredKnee": {"kneeRps": knee, "bound": "interpolated"},
            "checks": {"disturbed": disturbed or []},
        }

    runs = [r(2.9, 1089.0, [2112]), r(2.5, 1449.0, [2550]), r(0.2, 4160.0), r(0.1, 4400.0)]
    rep = report.repeatability(runs)
    assert rep is not None
    assert (rep["runs"], rep["disturbed"], rep["withRange"]) == (2, 2, 2)
    assert rep["text"]["errors"] == "+10% to +20%"
    s = report.knee_spread(runs)
    assert s is not None and (s["runs"], s["disturbed"], s["minRps"]) == (2, 2, 4160.0)
    assert report.counted_text(runs[0]) == "no: missed at 2,112 req/s, met higher"
    assert report.counted_text(runs[2]) == "yes"
    # with every comparable run disturbed there is nothing to judge, and no error range
    only = report.repeatability(runs[:2])
    assert only is not None and only["runs"] == 0 and only["medianError"] is None


def test_comparable_runs_repeat_protocol_machine_os_and_engine() -> None:
    def r(**kw: Any) -> dict[str, Any]:
        return {"protocol": "v5", "machine": "a", "os": "w1", "engineVersion": "0.15.0"} | kw

    runs = [r(protocol="v4"), r(os="w0"), r(engineVersion="0.14.0"), r(machine="b"), r(), r()]
    assert report.comparable(runs) == [r(), r()]
    assert report.comparable([]) == []
    # the power mode too, once recorded: an unrecorded one is not the same as a recorded one
    best = r(powerMode="Best performance")
    assert report.comparable([r(), r(powerMode="Balanced"), best, best]) == [best, best]


def test_repeatability_counts_the_runs_inside_the_predicted_range() -> None:
    def r(error: float, inside: bool | None) -> dict[str, Any]:
        rng = None if inside is None else {"nominalCoverage": 0.5, "containsMeasured": inside}
        return {"protocol": "v5", "machine": "a", "kneeError": error, "kneeRange": rng}

    rep = report.repeatability([r(-0.3, True), r(-0.1, False), r(-0.2, True)])
    assert rep is not None
    assert (rep["runs"], rep["medianError"], rep["inRange"], rep["withRange"]) == (3, -0.2, 2, 3)
    assert rep["text"] == {"medianError": "-20%", "errors": "-30% to -10%", "coverage": "2 of 3"}
    assert (rep["expectedInRange"], rep["coverageVerdict"]) == (1.5, "as claimed")
    one = report.repeatability([r(-0.08, None)])
    assert one is not None and one["text"] == {
        "medianError": "-8%",
        "errors": "-8%",
        "coverage": "-",
    }
    assert report.repeatability([]) is None


def test_coverage_verdict_by_the_binomial_tails() -> None:
    # 12 runs at 50%: 2 or fewer inside happen under 5% of the time, as do 10 or more
    assert report.coverage_verdict(1, 12, 6.0) == "too narrow"
    assert report.coverage_verdict(2, 12, 6.0) == "too narrow"
    assert report.coverage_verdict(3, 12, 6.0) == "as claimed"
    assert report.coverage_verdict(10, 12, 6.0) == "too wide"
    # at 80%, 6 or fewer of 12 are too few, and no count is too many
    assert report.coverage_verdict(6, 12, 9.6) == "too narrow"
    assert report.coverage_verdict(7, 12, 9.6) == "as claimed"
    assert report.coverage_verdict(12, 12, 9.6) == "as claimed"
    assert report.coverage_verdict(0, 0, 0.0) is None
    # every experiment's ranges together, with their own nominal coverages
    reps = [
        {"withRange": 3, "inRange": 2, "expectedInRange": 2.4, "nominalCoverage": 0.8},
        {"withRange": 3, "inRange": 3, "expectedInRange": 2.4, "nominalCoverage": 0.8},
        None,
        {"withRange": 0, "inRange": 0, "expectedInRange": 0.0, "nominalCoverage": None},
    ]
    assert report.coverage_summary(reps) == {
        "withRange": 6,
        "inRange": 5,
        "expectedInRange": 4.8,
        "nominalCoverage": 0.8,
        "verdict": "as claimed",
    }
    mixed = [
        reps[0],
        {"withRange": 3, "inRange": 0, "expectedInRange": 1.5, "nominalCoverage": 0.5},
    ]
    assert report.coverage_summary(mixed)["nominalCoverage"] is None


def test_range_label_says_where_the_range_came_from() -> None:
    assert report.range_label({"replicates": 3, "nominalCoverage": 0.5}) == (
        "range, from the 3 replicate sets,"
    )
    assert report.range_label({"method": "...", "nominalCoverage": 0.8}) == "80% range"


def test_criteria_failures() -> None:
    c = {
        "kneeError": 0.3,
        "measuredKnee": {"kneeRps": 250.0},
        "rows": [cmp_row(100, 1.0, 5.0), cmp_row(200, 10.0, 15.0, True)],
    }
    runs = [{"protocol": "v5", "machine": "a"}, {"protocol": "v5", "machine": "a"}]
    k = report.criteria(c, runs)
    assert not k["knee"]["pass"] and k["knee"]["runs"] == 1  # earlier runs without their checks
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
    assert {exp: k["p95"]["misses"] for exp, k in got.items()} == {
        "java-mixed": [],
        "go-mixed": [],
        "python-mixed": [440],  # 6.1 ms predicted, 2.3 measured
        "chain": [3338],
        "cache-ttl": [],
        "pg-mixed": [3900],
        "pg-hot-row": [856, 942],
        "cache-aside": [5650, 6215],
        "kafka": [1566, 1879, 2297],
    }
    assert got["pg-hot-row"]["stable"]["unstable"] == [942]
    # the held-out campaign (3-4 Nov 2026): three runs each under v6 and the frozen engine
    # 0.16.0; the four tuned-on experiments add a repeat of 4 Nov to the v6 campaign's three, in
    # which hot-row's and Kafka's first runs were disturbed
    assert {exp: (k["knee"]["runs"], k["knee"]["disturbedRuns"]) for exp, k in got.items()} == {
        **dict.fromkeys(report.HELD_OUT, (3, 0)),
        "pg-mixed": (6, 0),
        "pg-hot-row": (5, 1),
        "cache-aside": (6, 0),
        "kafka": (5, 1),
    }
    assert {exp: k["knee"]["error"] for exp, k in got.items()} == pytest.approx(
        {
            "java-mixed": 0.7002,
            "go-mixed": 0.6666,
            "python-mixed": 0.2910,
            "chain": 0.3039,
            "cache-ttl": -0.0582,
            "pg-mixed": 0.1591,
            "pg-hot-row": -0.0953,
            "cache-aside": -0.2253,
            "kafka": -0.0303,
        },
        abs=1e-4,
    )
    assert [exp for exp, k in got.items() if k["knee"]["pass"]] == ["cache-ttl", *report.TUNED_ON]
    # criterion 2, a factor of two either way: java-mixed and go-mixed pass on the two light
    # steps below their early knees, cache-ttl on all five. 3a: all but pg-mixed and hot-row
    assert [e for e, k in got.items() if k["p95"]["pass"]] == [
        "java-mixed",
        "go-mixed",
        "cache-ttl",
    ]
    assert [e for e, k in got.items() if not k["stable"]["pass"]] == ["pg-mixed", "pg-hot-row"]
    assert not any(k["all"] for k in got.values())
    # every run under v6 was on the same laptop: a second machine is needed
    assert {exp: k["reproduced"]["runs"] for exp, k in got.items()} == {
        **dict.fromkeys(report.HELD_OUT, 3),
        **dict.fromkeys(report.TUNED_ON, 6),
    }
    assert all(k["reproduced"]["machines"] == 1 for k in got.values())
    assert not any(k["reproduced"]["pass"] for k in got.values())


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


def test_experiment_block_names_the_real_calibration_rates(tmp_path: Path) -> None:
    block = report.experiment_block("pg-mixed")
    assert "calibration passes at 50, 793, 2,610 and 3,262 req/s" in block
    assert "Measured in the calibration pass at 3,262 req/s" in block
    assert "(50 req/s):" not in block and "Measured at 50 req/s" not in block
    # Kafka's latest staircase stopped at its second pass: half the consumers' capacity that
    # pass implied was below the next step (run.next_rise)
    assert "calibration passes at 50 and 406 req/s" in report.experiment_block("kafka")
    # the load curve, an input since engine 0.15.0 (W21)
    curve = "x4.03 at 50, x1.31 at 793, x1.04 at 2,610, x1.00 at 3,262 req/s"
    assert f"| `serviceTimeByLoad` | {curve} |" in block
    # an older run under v4 (pg-mixed's run 5) still names its two passes
    shutil.copytree(run.RESULTS / "pg-mixed" / "run-5", tmp_path / "pg-mixed")
    old = report.experiment_block("pg-mixed", tmp_path)
    assert "calibration passes at 50 and 346 req/s" in old
    assert "Measured in the calibration pass at 346 req/s" in old


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
    # k6 off the service's hyperthread sibling since the baseline campaign (run.K6_CPUS)
    assert "pg-mixed and pg-hot-row: service 1 (`2`), PostgreSQL 2 (`4,5`), k6 4 (`0,1,6,7`)" in (
        block
    )
    assert "k6 3 (`0,6,7`)" in block and "k6 2 (`6,7`)" in block
    assert "runner's defaults" not in block  # every latest run recorded its cpusets
    # every latest run on one machine, OS build and power mode: one line
    assert block.count("- CPU: 11th Gen Intel(R) Core(TM) i7-1165G7") == 1
    assert "Windows-11-10.0.26300-SP0 (Best performance)." in block
    # a run on another OS build gets a line of its own
    older = envs["pg-mixed"] | {"os": "Windows-11-10.0.26200-SP0", "powerMode": None}
    two = report.environment_block(envs | {"pg-mixed": older})
    assert "- CPU for pg-mixed: 11th Gen Intel(R) Core(TM) i7-1165G7" in two
    others = (
        "java-mixed, go-mixed, python-mixed, chain, cache-ttl, pg-hot-row, cache-aside and kafka"
    )
    assert f"- CPU for {others}: 11th Gen Intel(R) Core(TM) i7-1165G7" in two
    assert two.count("Windows-11-10.0.26300-SP0 (Best performance)") == 1
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
            f"| [{x['id']}](#{x['id']}) | {report.SAMPLE_TEXT[x['sample']]} | "
            f"{k['measured']} req/s | {k['predicted']} req/s | "
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
    # the latest runs: the held-out ones of 3-4 Nov, the tuned-on ones of 4 Nov
    assert s["kneeErrorRangeText"] == "-16% to +70%"
    assert s["kneeWithinTolerance"] == ["cache-ttl", *report.TUNED_ON]
    assert s["validated"] == [] and not any(t["validated"] for t in s["technologies"])
    # every protocol after v4 was designed with all four's results in view, and none with the
    # held-out experiments'
    assert s["inSample"] == report.TUNED_ON
    assert s["heldOut"] == report.HELD_OUT
    # three or more counted runs each under the same conditions; pg-mixed's first run of 4 Nov
    # (2,875 req/s, against 4,371 and 4,483) widens its spread beyond its error
    assert s["withSpread"] == report.ORDER and s["repeated"] == report.ORDER
    assert s["spreadExceedsError"] == ["pg-mixed"]
    assert s["runs"] == 67 and len(s["machines"]) == 1
    # the 80% range (W28) held the measured knee in 29 of 37 runs: 20 of 22 tuned on, 9 of 15
    # held out, every held-out miss java-mixed's or go-mixed's
    assert s["coverage"] == {
        "withRange": 37,
        "inRange": 29,
        "expectedInRange": 29.6,
        "nominalCoverage": 0.8,
        "verdict": "as claimed",
    }
    assert s["coverageHeldOut"] == {
        "withRange": 15,
        "inRange": 9,
        "expectedInRange": 12.0,
        "nominalCoverage": 0.8,
        "verdict": "as claimed",
    }
    by = {x["id"]: x for x in data["experiments"]}
    assert [by[e]["repeatability"]["inRange"] for e in report.HELD_OUT] == [0, 0, 3, 3, 3]
    # pg-mixed's two disturbed v5 runs, six older ones, and hot-row's and Kafka's first runs of
    # 4 Nov
    assert s["disturbedRuns"] == 10
    mixed = by["pg-mixed"]
    assert mixed["knee"]["text"] == {
        "measured": "4,483",
        "predicted": "5,203",
        "error": "+16%",
        "absError": "16%",
    }
    # the measured knee's spread pools v5 and v6 (the protocol changes the calibration only)
    assert mixed["spread"]["text"] == "2,875 to 4,495 req/s"
    assert mixed["spread"]["plusMinusText"] == "±22%"
    assert mixed["spread"]["disturbed"] == 2
    assert [v["run"] for v in mixed["spread"]["values"]] == [
        *(f"run-{n}/" for n in range(8, 16)),
        "latest",
    ]
    # Kafka's spread counts only the runs after the healthcheck fix, and not the disturbed one
    assert [v["run"] for v in by["kafka"]["spread"]["values"]] == [
        "run-6/",
        "run-7/",
        "run-8/",
        "run-10/",
        "latest",
    ]
    assert by["pg-hot-row"]["runs"] == 14 and by["kafka"]["runs"] == 11
    assert mixed["repeatability"]["runs"] == 6 and mixed["repeatability"]["disturbed"] == 0
    # criterion 2 is symmetric (ADR 0018): pg-mixed's 3,900 req/s step misses
    assert mixed["p95BelowKnee"]["within"] == 2 and mixed["p95BelowKnee"]["steps"] == 3
    assert mixed["p95BelowKnee"]["worst"]["text"]["offered"] == "3,900"
    assert by["kafka"]["p95BelowKnee"]["misses"] == [1566, 1879, 2297]
    assert by["kafka"]["p95BelowKnee"]["worst"]["text"]["offered"] == "2,297"
    assert data["thresholds"]["p95Factor"] == 2.0 and "p95Error" not in data["thresholds"]
    assert data["summary"]["p95WithinTolerance"] == ["java-mixed", "go-mixed", "cache-ttl"]
    assert mixed["date"] == "2026-11-04" and mixed["engineVersion"] == "0.16.0"
    assert mixed["environment"]["powerMode"] == "Best performance"
    assert mixed["environment"]["dockerCpus"] == 8
    assert mixed["environment"]["dockerMemoryGiB"] == 7.6
    assert mixed["environment"]["cpusetsRecorded"]
    assert sum(1 for x in mixed["steps"] if x["atOrBelowKnee"]) == 3
    # the history marks the disturbed runs, which still show their own knee
    marked = [r["dir"] for r in data["history"]["pg-mixed"] if r["checks"]["disturbed"]]
    assert marked == ["run-2/", "run-6/", "run-7/"]


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
    # or under another power mode (runs before 31 Oct 2026 have none recorded)
    best = [hist_run(7000.0) | {"powerMode": "Best performance"} for _ in range(2)]
    s = report.knee_spread([hist_run(4800.0), *best])
    assert s is not None and (s["runs"], s["minRps"]) == (2, 7000.0)
    # or before a harness fix that limited it (Kafka's healthcheck)
    fixed = [hist_run(2700.0), hist_run(2800.0)]
    s = report.knee_spread([hist_run(2100.0) | {"harnessCaveat": True}, *fixed])
    assert s is not None and (s["runs"], s["minRps"]) == (2, 2700.0)


def test_harness_caveat_until_the_fix() -> None:
    # a Kafka run calibrated before the healthcheck fix of 31 Oct has the caveat, one after not
    assert "healthcheck" in (report.harness_caveat("kafka", "2026-10-31T03:26:27Z") or "")
    assert report.harness_caveat("kafka", "2026-11-01T22:00:00Z") is None
    assert report.harness_caveat("pg-mixed", "2026-10-31T03:26:27Z") is None
    assert report.harness_caveat("kafka", None) is None
    data = report.accuracy()
    # the v6 campaign measured Kafka without the healthcheck: no caveat on the latest run, but
    # the history still marks the runs before the fix
    assert data is not None and data["summary"]["caveats"] == []
    assert "**Harness caveat.**" not in report.experiment_block("kafka")
    marked = [r["dir"] for r in data["history"]["kafka"] if r["harnessCaveat"]]
    assert marked == ["run-1/", "run-2/", "run-3/", "run-4/", "run-5/"]


def test_pct_never_prints_minus_zero() -> None:
    assert (report.pct(-0.0023), report.pct(0.004), report.pct(-0.006)) == ("0%", "0%", "-1%")
    assert report.pct(-0.0023, signed=False) == "-0%"  # unsigned figures are magnitudes


def test_power_mode_is_windows_only(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run.sys, "platform", "linux")
    assert run.power_mode() is None
    assert run.POWER_MODES["ded574b5-45a0-4f42-8737-46345c09c238"] == "Best performance"


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


def test_dev_label_keeps_to_the_engines_own_folders(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    on_the_java_engine(monkeypatch)

    assert (run.dev_label(), run.dev_label(f"{JAVA_VERSION}-port")) == (
        JAVA_VERSION,
        f"{JAVA_VERSION}-port",
    )
    for other in (f"{FROZEN_VERSION}-port", f"{FROZEN_VERSION}-java"):
        with pytest.raises(ValueError, match="names this engine"):
            run.dev_label(other)


def test_resimulate_with_another_engines_label_is_refused_before_it_runs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    on_the_java_engine(monkeypatch)
    monkeypatch.setattr(run, "resimulate", lambda **_: pytest.fail("resimulate ran"))

    with pytest.raises(SystemExit) as stopped:
        run.main(["resimulate", "--label", f"{FROZEN_VERSION}-java"])

    assert stopped.value.code == 2


def stand_in_check(exp_id: str, d: Path, label: str, results: Path) -> dict[str, Any]:
    """A development check as ``run.dev_check`` makes one, without simulating."""
    return {
        "experiment": exp_id,
        "results": d.relative_to(results).as_posix(),
        "calibratedAt": "2026-10-26T12:22:50Z",
        "engineVersion": label,
        "inputsAsCalibrated": True,
        "comparison": {
            "measuredKnee": {"kneeRps": 2000.0},
            "predictedKnee": {"kneeRps": 2100.0},
            "kneeError": 0.05,
        },
    }


def test_resimulate_passes_the_label_it_writes_under_to_each_check(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    on_the_java_engine(monkeypatch)
    label = f"{JAVA_VERSION}-port"
    monkeypatch.setattr(run, "ProcessPoolExecutor", ThreadPoolExecutor)
    monkeypatch.setattr(
        run, "result_dirs", lambda results: [("kafka", results / "kafka" / "run-1")]
    )
    monkeypatch.setattr(run, "dev_check", stand_in_check)

    (written,) = run.resimulate(tmp_path, label=label)

    assert written == tmp_path / "dev" / label / "kafka" / "2026-10-26T12-22-50Z.json"
    assert json.loads(written.read_text(encoding="utf-8"))["engineVersion"] == label
