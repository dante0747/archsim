"""W21: the Node.js service's garbage collection (``node_gc_inputs``, from server.js's /stats) and
its CPU per request as load rises (``service_time_by_load``, from the calibration staircase), and
how the development checks borrow a trial's GC for kept runs that did not report it. Nothing here
starts Docker."""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import pytest
import report
import run

PG_MIXED = run.EXPERIMENTS["pg-mixed"]


def node_stats(requests: int = 50_000, cpu_ms: float = 10_000.0, **by_kind: Any) -> dict[str, Any]:
    """server.js's /stats with ``gc.byKind``: kind -> (collections, pause ms)."""
    kinds = {
        k: {"collections": n, "pauseMs": ms, "median": ms / max(n, 1)}
        for k, (n, ms) in by_kind.items()
    }
    return {
        "requests": requests,
        "cpuMs": cpu_ms,
        "cpuMsPerRequest": cpu_ms / requests,
        "gc": {
            "collections": sum(n for n, _ in by_kind.values()),
            "pauseMs": sum(ms for _, ms in by_kind.values()),
            "byKind": kinds,
        },
    }


# -- garbage collection ---------------------------------------------------------------------------


def test_node_gc_models_the_mark_compact_cycles_and_leaves_scavenges_in_the_cpu() -> None:
    stats = node_stats(minor=(300, 240.0), major=(5, 60.0), incremental=(5, 40.0))
    gc, per_request = run.node_gc_inputs(stats)
    # 100 ms over 5 cycles: a mean of 20 ms, the model's median 20 / 1.17
    sigma = math.log(2.5) / run.Z95  # the engine's lognormal: p95 = 2.5 x the median
    assert gc["gcPauseMs"]["value"] == pytest.approx(20 / math.exp(sigma**2 / 2), abs=1e-3)
    assert gc["gcEveryRequests"]["value"] == 10_000
    assert per_request == pytest.approx(100 / 50_000)
    assert "300 scavenges, 240.0 ms, left in the CPU per request" in gc["gcPauseMs"]["how"]


def test_node_gc_without_a_mark_compact_or_a_report() -> None:
    gc, per_request = run.node_gc_inputs(node_stats(minor=(40, 30.0)))
    assert gc["gcPauseMs"]["value"] == 0 and per_request == 0
    assert "gcEveryRequests" not in gc
    # a service before W21 reported no GC: nothing is added (the designs stay as they were)
    assert run.node_gc_inputs({"requests": 10, "cpuMs": 1.0}) == ({}, 0.0)


def test_the_modelled_pauses_come_off_the_cpu_per_request() -> None:
    def passes(gc: bool) -> dict[str, Any]:
        timings = {
            n: {"count": 1, "mean": 0.3, "median": 0.25, "p95": 0.9}
            for n in ("own", "handler", "db_read", "db_write")
        }
        svc = node_stats(major=(5, 100.0)) if gc else {"requests": 50_000, "cpuMs": 10_000.0}
        svc = {**svc, "cpuMsPerRequest": 0.2, "timings": timings}
        high = {"rateRps": 1666, "k6": {"p50": 0.5, "p95": 1.5}, "service": svc}
        return {
            "low": high,
            "high": high,
            "idle": {"serviceCpuMsPerSecond": 0.0, "containerCpuMsPerSecond": {}},
        }

    with_gc = run.service_inputs(PG_MIXED, passes(True))
    without = run.service_inputs(PG_MIXED, passes(False))
    mean = run.lognormal_with_mean  # the value's mean is the CPU per request it was given
    assert with_gc["serviceTimeMs"]["value"] == mean(
        0.2 - 100 / 50_000, {"median": 0.25, "p95": 0.9}
    )
    assert without["serviceTimeMs"]["value"] == mean(0.2, {"median": 0.25, "p95": 0.9})
    assert (
        "less the 0.0020 ms per request of the pauses modelled as GC"
        in with_gc["serviceTimeMs"]["how"]
    )
    assert "gcPauseMs" not in without


def test_the_node_design_carries_the_gc_and_the_curve() -> None:
    v = {
        "serviceTimeMs": {"kind": "constant", "value": 0.2},
        "gcPauseMs": 12.0,
        "gcEveryRequests": 9000,
        "serviceTimeByLoad": [{"rps": 50, "factor": 3.0}, {"rps": 1600, "factor": 1.0}],
    }
    cfg = run.service_node(v)["config"]
    assert (cfg["gcPauseMs"], cfg["gcEveryRequests"]) == (12.0, 9000)
    assert cfg["serviceTimeByLoad"] == v["serviceTimeByLoad"]
    # chain's API behind the gateway keeps neither (they were measured on the gateway)
    api = run.service_node(
        {**v, "apiServiceTimeMs": v["serviceTimeMs"]}, time_key="apiServiceTimeMs"
    )
    assert api["config"]["gcPauseMs"] == 0 and "serviceTimeByLoad" not in api["config"]
    # without them, the design is the one engine 0.14.0 predicted from
    plain = run.service_node({"serviceTimeMs": v["serviceTimeMs"]})["config"]
    assert plain["gcPauseMs"] == 0 and "gcEveryRequests" not in plain
    assert "serviceTimeByLoad" not in plain


# -- CPU per request by load -----------------------------------------------------------------------


def by_load(ms: dict[float, float]) -> list[dict[str, Any]]:
    return [{"rateRps": r, "passes": 2, "serviceCpuMsPerRequest": m} for r, m in ms.items()]


def test_the_curve_is_relative_to_the_top_rate_and_never_rises() -> None:
    curve = run.service_time_by_load(by_load({50: 0.74, 458: 0.31, 1129: 0.21, 1674: 0.20}))
    assert curve is not None
    factors = [p["factor"] for p in curve["value"]]
    assert factors[-1] == 1.0 and factors[0] == pytest.approx(0.74 / 0.20, abs=1e-3)
    assert [p["rps"] for p in curve["value"]] == [50, 458, 1129, 1674]
    # a top pass dearer than the one below it (pg-mixed run 7): pooled, so the top is still 1
    bumped = run.service_time_by_load(by_load({50: 0.77, 408: 0.35, 1150: 0.222, 1778: 0.266}))
    assert bumped is not None
    assert [p["factor"] for p in bumped["value"]][-2:] == [1.0, 1.0]
    # flat within 10%, or one rate only: no curve
    assert run.service_time_by_load(by_load({50: 0.21, 500: 0.2})) is None
    assert run.service_time_by_load(by_load({500: 0.2})) is None
    assert run.service_time_by_load(None) is None


# a protocol v6 staircase whose cost keeps falling near its top (cache-aside's service, W26)
V6: dict[float, list[float]] = {
    50: [1.0, 1.04],
    650: [0.30, 0.29],
    2000: [0.211, 0.206],
    3000: [0.186, 0.181, 0.19],
}


def v6_passes(ms: dict[float, list[float]]) -> list[tuple[float, float]]:
    return [(r, m) for r, v in ms.items() for m in v]


def test_a_v6_staircase_can_carry_its_clear_fall_above_the_top() -> None:
    bins = by_load({r: sorted(v)[len(v) // 2] if len(v) % 2 else sum(v) / 2 for r, v in V6.items()})
    # off by default (CURVE_FORM): the top rate's factor above it, as engine 0.15.0
    assert run.CURVE_FORM == "flat"
    flat = run.service_time_by_load(bins, v6_passes(V6), cores=1)
    assert flat == run.service_time_by_load(bins)
    assert flat is not None and flat["value"][-1] == {"rps": 3000, "factor": 1.0}
    # the log form: one more point, as far above the top as the base (2,000 and 3,000 req/s,
    # the rates at least 30% busy) spans below it
    curve = run.service_time_by_load(bins, v6_passes(V6), cores=1, form="log")
    assert curve is not None
    pts = curve["value"]
    assert [p["rps"] for p in pts] == [50, 650, 2000, 3000, 4500] and pts[3]["factor"] == 1.0
    assert 0.8 < pts[-1]["factor"] < 1.0
    assert "carried from 3,000 to 4,500 req/s (one measured stretch on)" in curve["how"]
    assert curve["how"].endswith("then flat")


def test_staircase_cpu_is_each_pass_net_of_idle() -> None:
    idle = {"serviceCpuMsPerSecond": 2.0}
    runs = [
        {"rateRps": 50, "service": {"cpuMs": 1560.0, "requests": 1500}},
        {"rateRps": 50, "service": {"cpuMs": 1620.0, "requests": 1500}},
        {"rateRps": 900, "service": {"cpuMs": 5460.0, "requests": 27000}},
    ]
    rest = 2.0 * run.CALIBRATION_S
    assert run.staircase_cpu(runs, idle) == [
        (50.0, (1560 - rest) / 1500),
        (50.0, (1620 - rest) / 1500),
        (900.0, (5460 - rest) / 27000),
    ]
    assert run.staircase_cpu(runs, None) == []  # before protocol v5: no idle pass


def test_kept_v5_calibrations_keep_the_curve_they_had() -> None:
    # every staircase so far topped out at a third of the core: never carried on above its top
    for exp in report.ORDER:
        for d in [run.RESULTS / exp, *sorted((run.RESULTS / exp).glob("run-*"))]:
            cal = json.loads((d / "calibration.json").read_text(encoding="utf-8"))
            if cal.get("protocol") != "v5":
                continue
            passes = run.staircase_cpu(cal["staircase"], cal["idle"])
            assert passes, d
            assert run.service_time_by_load(
                cal["cpuByLoad"], passes, run.service_cpus(run.EXPERIMENTS[exp])
            ) == run.service_time_by_load(cal["cpuByLoad"]), d


def test_every_latest_calibration_gets_a_falling_curve() -> None:
    for exp in report.ORDER:
        cal = json.loads((run.RESULTS / exp / "calibration.json").read_text(encoding="utf-8"))
        curve = run.service_time_by_load(cal["cpuByLoad"])
        assert curve is not None, exp
        assert curve["value"][-1] == {"rps": cal["staircaseRps"][-1], "factor": 1.0}
        assert curve["value"][0]["factor"] > 1.4, exp  # 50 req/s costs more than the top rate


def test_a_design_with_the_curve_costs_more_at_low_load_and_the_same_at_the_top() -> None:
    cal = json.loads((run.RESULTS / "pg-mixed" / "calibration.json").read_text(encoding="utf-8"))
    inputs = {**cal["inputs"], "serviceTimeByLoad": run.service_time_by_load(cal["cpuByLoad"])}
    flat = {k: v for k, v in cal["inputs"].items() if k != "serviceTimeByLoad"}
    low = [run.simulate(run.design(PG_MIXED, x, 200, warm=2, duration=6)) for x in (flat, inputs)]
    assert low[1]["resources"]["api"]["cores"] > 1.5 * low[0]["resources"]["api"]["cores"]
    top = cal["staircaseRps"][-1]
    high = [run.simulate(run.design(PG_MIXED, x, top, warm=2, duration=6)) for x in (flat, inputs)]
    assert high[1]["resources"]["api"]["cores"] == pytest.approx(
        high[0]["resources"]["api"]["cores"], rel=0.02
    )


# -- the trials' GC in the development checks -----------------------------------------------------


def test_a_kept_run_borrows_its_trials_gc_per_request(tmp_path: Path) -> None:
    trial = tmp_path / "trials" / run.GC_TRIALS["pg-mixed"]
    trial.mkdir(parents=True)
    tops = [
        {"high": {"service": node_stats(10_000, major=(1, 30.0), minor=(60, 50.0))}},
        {"high": {"service": node_stats(30_000, major=(3, 90.0), minor=(180, 150.0))}},
    ]
    (trial / "calibration.json").write_text(
        json.dumps({"staircaseRps": [50, 1000], "replicates": tops}), encoding="utf-8"
    )
    gc = run.trial_gc("pg-mixed", tmp_path)
    assert gc is not None and gc["requests"] == 40_000 and gc["rateRps"] == 1000
    assert gc["perRequest"]["major"] == {"collections": 4 / 40_000, "pauseMs": 120 / 40_000}
    assert run.trial_gc("kafka", tmp_path) is None
    kept = {
        "passes": {"low": {}, "high": {"service": {"requests": 20_000}}},
        "replicates": [{"high": {"service": {"requests": 20_000}}}],
    }
    given = run.with_trial_gc(kept, gc)
    svc = given["replicates"][0]["high"]["service"]
    assert svc["gc"]["byKind"]["major"]["collections"] == pytest.approx(2)
    assert svc["gc"]["byKind"]["major"]["pauseMs"] == pytest.approx(60)
    assert "trial 2026-10-31-pg-mixed-w21" in svc["gc"]["source"]
    inputs, _ = run.node_gc_inputs(svc)
    assert inputs["gcEveryRequests"]["value"] == 10_000
    assert "(per request as trial" in inputs["gcPauseMs"]["how"]
    # a service that reported its own GC keeps it
    own = {"service": node_stats(5_000, major=(1, 5.0))}
    assert run.with_trial_gc({"passes": {"high": own}}, gc)["passes"]["high"] == own
