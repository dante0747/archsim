"""The accuracy benchmarks' arithmetic (benchmarks/run.py) and report (benchmarks/report.py), on
synthetic data and on the committed results. Nothing here starts Docker."""

from __future__ import annotations

import json
import math
import shutil
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


def test_committed_comparisons_are_reproduced_by_compare() -> None:
    dirs = run.result_dirs()
    assert len(dirs) == 10
    for exp, d in dirs:
        pred = json.loads((d / "predictions.json").read_text(encoding="utf-8"))
        meas = json.loads((d / "measured.json").read_text(encoding="utf-8"))
        committed = json.loads((d / "comparison.json").read_text(encoding="utf-8"))
        assert run.compare(run.EXPERIMENTS[exp], pred, meas) == committed, d


def test_recompare_writes_every_comparison(tmp_path: Path) -> None:
    shutil.copytree(run.RESULTS / "pg-hot-row", tmp_path / "pg-hot-row")
    for f in (tmp_path / "pg-hot-row").rglob("comparison.json"):
        f.unlink()
    written = run.recompare(tmp_path)
    assert [p.parent.name for p in written] == ["run-1", "run-2", "run-3", "pg-hot-row"]
    latest = json.loads(written[-1].read_text(encoding="utf-8"))
    rows = {r["offeredRps"]: r for r in latest["rows"]}
    assert rows[1126]["measured"]["p95"] is None  # two of three repeats had no success
    assert rows[1126]["failedRepeats"] == 2


# ---------------------------------------------------------------------------------------------
# CPU pinning
# ---------------------------------------------------------------------------------------------


def test_cpu_count() -> None:
    assert run.cpu_count("0,1,3,6,7") == 5
    assert run.cpu_count("0-3") == 4
    assert run.cpu_count("2") == 1
    assert run.cpu_count("0-1,4") == 3


def test_cpusets_follow_compose_and_the_runner() -> None:
    declared = run.compose_cpusets()
    assert {s for services in run.STACK_SERVICES.values() for s in services} <= set(declared)
    assert run.cpusets("postgres", env={}) == {"api": "2", "postgres": "4,5", "k6": "0,1,3,6,7"}
    assert run.cpusets("cache", env={})["k6"] == "0,3,6,7"
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
    assert got["pg-mixed"]["p95"]["misses"] == [2062, 2268]
    assert got["cache-aside"]["p95"]["misses"] == [3450, 3795]
    assert got["kafka"]["p95"]["misses"] == [252, 503, 905]
    assert got["pg-hot-row"]["p95"]["pass"] and not got["pg-hot-row"]["knee"]["pass"]
    assert got["pg-mixed"]["stable"]["unstable"] == [2268]
    assert [exp for exp, k in got.items() if k["knee"]["pass"]] == [
        "pg-mixed",
        "cache-aside",
        "kafka",
    ]
    assert not any(k["all"] for k in got.values())
    assert all(k["reproduced"]["runs"] == 1 for k in got.values())


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
    assert "calibration passes at 50 and 265 req/s" in block
    assert "Measured in the calibration pass at 265 req/s" in block
    assert "(50 req/s):" not in block and "Measured at 50 req/s" not in block
    assert "calibration pass at 143 req/s" in report.experiment_block("kafka")


def test_experiment_block_explains_failed_repeats() -> None:
    block = report.experiment_block("pg-hot-row")
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
    assert "runner's defaults" in block


def test_environment_block_uses_recorded_cpusets() -> None:
    env = {"cpu": "x", "os": "y", "cpusets": {"api": "0", "postgres": "1-2", "k6": "3-7"}}
    block = report.environment_block({"pg-mixed": env})
    assert "pg-mixed: service 1 (`0`), PostgreSQL 2 (`1-2`), k6 5 (`3-7`)" in block
    assert "runner's defaults" not in block
