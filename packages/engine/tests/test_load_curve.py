"""Service time that follows load (``serviceTimeByLoad``, schema 1.6): the curve itself, the
simulation, the analytic copies (capacity, the event-loop explanation, the instant estimate,
sizing, the review) and calibration's fit from CPU per request."""

from __future__ import annotations

import copy
from itertools import pairwise
from typing import Any

import pytest

from archsim_engine import run_simulation
from archsim_engine.calibration.resources import fit_cpu_share, fit_load_curve
from archsim_engine.components.load_curve import (
    RateMeter,
    capacity_rps,
    curve_of,
    load_factor,
)
from archsim_engine.components.service import ServiceConfig
from archsim_engine.migrations import migrate_document
from engine_builders import doc, edge, load_example, node, node_by_id, short

CURVE = [{"rps": 50, "factor": 4.0}, {"rps": 500, "factor": 1.5}, {"rps": 2000, "factor": 1.0}]


# -- the curve ----------------------------------------------------------------------------------


def test_the_factor_is_interpolated_in_log_rate_and_flat_outside() -> None:
    c = curve_of(CURVE)
    assert load_factor(c, 10) == load_factor(c, 50) == 4.0  # below: the first point's factor
    assert load_factor(c, 5000) == load_factor(c, 2000) == 1.0  # above: never extrapolated
    # halfway in log(rate) between 50 and 500 req/s: halfway between their factors
    assert load_factor(c, (50 * 500) ** 0.5) == pytest.approx(2.75)
    assert load_factor(c, None) == 4.0
    assert load_factor([], 123) == 1.0


def test_points_are_sorted_and_bad_ones_skipped() -> None:
    raw = [{"rps": 500, "factor": 1.5}, {"rps": 50, "factor": 4}, {"rps": 0, "factor": 2}, {}]
    assert curve_of(raw) == [(50.0, 4.0), (500.0, 1.5)]
    assert curve_of(None) == []


def test_capacity_is_where_the_cpu_fills_at_that_rates_factor() -> None:
    c = curve_of(CURVE)
    # 1 core at 0.5 ms (factor 1): 2,000 req/s, beyond the curve's last point
    assert capacity_rps(c, 2000) == pytest.approx(2000)
    assert capacity_rps(c, 4000) == pytest.approx(4000)
    # 1,000 req/s at factor 1 does not fit at 1,000 req/s (factor about 1.2): the fixed point
    cap = capacity_rps(c, 1000)
    assert cap * load_factor(c, cap) == pytest.approx(1000, rel=1e-6)
    assert 500 < cap < 1000
    # below the first point: the first factor
    assert capacity_rps(c, 100) == pytest.approx(25)
    assert capacity_rps([], 300) == 300


def test_a_replicas_rate_is_its_arrivals_over_the_last_second() -> None:
    m = RateMeter(0.0)
    t = 0.0
    for _ in range(3000):  # 2,000 req/s for 1.5 s
        t += 0.5
        m.arrive(t)
    assert m.rps(t) == pytest.approx(2000, rel=0.01)
    # the first arrival already reads as the rate it implies, not as a near-empty second
    first = RateMeter(0.0)
    first.arrive(10.0)
    assert first.rps(10.0) == pytest.approx(100, rel=0.06)
    # then it fades: 2 s without arrivals
    assert m.rps(t + 2000) < 2000 * 0.15


# -- the document ---------------------------------------------------------------------------------


def test_an_empty_curve_is_left_out_of_the_json() -> None:
    assert "serviceTimeByLoad" not in ServiceConfig().model_dump(mode="json", by_alias=True)
    cfg = ServiceConfig.model_validate({"serviceTimeByLoad": CURVE})
    assert cfg.model_dump(mode="json", by_alias=True)["serviceTimeByLoad"][0] == {
        "rps": 50.0,
        "factor": 4.0,
    }
    with pytest.raises(ValueError):
        ServiceConfig.model_validate({"serviceTimeByLoad": [{"rps": 50, "factor": 0}]})


def test_schema_1_5_documents_open_unchanged() -> None:
    d = load_example("cache-aside-starter.json")
    d["schemaVersion"] = "1.5"
    out, notes = migrate_document(d)
    assert out["schemaVersion"] == "1.6" and notes == ["migrated schema 1.5 -> 1.6"]
    assert out["architecture"] == d["architecture"]


def test_an_empty_curve_changes_no_result() -> None:
    d = short(load_example("cache-aside-starter.json"), duration=10, warmup=2)
    with_empty = copy.deepcopy(d)
    for n in with_empty["architecture"]["nodes"]:
        if n["type"] == "service":
            n["config"]["serviceTimeByLoad"] = []
    a, b = run_simulation(d), run_simulation(with_empty)
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]


# -- the simulation and its analytic copies ---------------------------------------------------------


def loop(rps: float, curve: list[dict[str, float]] | None = None, **cfg: Any) -> dict[str, Any]:
    """Clients -> one Node.js event loop on 1 CPU, 0.5 ms of CPU per request."""
    svc = {
        "technology": "nodejs",
        "replicas": 1,
        "queueCapacity": 100_000,
        "queueTimeoutMs": None,
        "serviceTimeMs": {"kind": "constant", "value": 0.5},
        "gcPauseMs": 0,
        "cpuCores": 1,
        **cfg,
    }
    if curve is not None:
        svc["serviceTimeByLoad"] = curve
    d = doc(
        [
            node("clients", "traffic_source", timeoutMs=600_000, maxAttempts=1),
            node("svc", "service", **svc),
        ],
        [edge("e1", "clients", "svc")],
        rps=rps,
        durationS=12,
        warmupS=3,
        arrivalProcess="deterministic",
    )
    d["architecture"]["autoSize"] = False
    return d


def test_the_service_time_follows_the_replicas_rate() -> None:
    quiet = node_by_id(run_simulation(loop(50, CURVE)), "svc")["summary"]
    busy = node_by_id(run_simulation(loop(1500, CURVE)), "svc")["summary"]
    assert quiet["meanServiceMs"] == pytest.approx(0.5 * 4.0, rel=0.02)
    assert busy["meanServiceMs"] == pytest.approx(
        0.5 * load_factor(curve_of(CURVE), 1500), rel=0.02
    )
    assert busy["cpuCoreUtilization"] == pytest.approx(
        1500 * busy["meanServiceMs"] / 1000, rel=0.03
    )


def test_capacity_and_the_event_loop_explanation_use_the_fixed_point() -> None:
    r = run_simulation(loop(400, CURVE))
    svc = node_by_id(r, "svc")
    # 1 core at 0.5 ms is 2,000 req/s at factor 1, and the factor there is 1
    assert svc["summary"]["capacityRps"] == pytest.approx(2000, rel=0.02)
    rows = {f["id"]: f for f in svc["explain"]}
    assert rows["event_loop"]["result"] == pytest.approx(2000, rel=0.02)
    assert rows["load_curve"]["result"] == pytest.approx(
        0.5 * load_factor(curve_of(CURVE), 400), rel=0.02
    )
    # without a curve: one cost per request, as before
    plain = node_by_id(run_simulation(loop(400)), "svc")
    assert plain["summary"]["capacityRps"] == pytest.approx(2000, rel=0.02)
    assert "load_curve" not in {f["id"] for f in plain["explain"]}


def test_a_function_pays_the_factor_of_its_rate_per_instance() -> None:
    d = loop(20, [{"rps": 1, "factor": 3.0}, {"rps": 100, "factor": 1.0}], platform="serverless")
    d["architecture"]["nodes"][1]["config"]["cpuCores"] = None
    s = node_by_id(run_simulation(d), "svc")["summary"]
    # one instance kept warm, 20 req/s through it: between the points
    f = load_factor(curve_of([{"rps": 1, "factor": 3.0}, {"rps": 100, "factor": 1.0}]), 20)
    assert s["meanServiceMs"] == pytest.approx(0.5 * f, rel=0.1)


def test_the_estimate_sizing_and_review_use_the_factor_at_their_rate() -> None:
    curve = [{"rps": 100, "factor": 2.0}, {"rps": 1000, "factor": 1.0}]
    d = loop(100, curve)
    r = run_simulation(d)
    row = next(x for x in r["estimate"]["parts"] if x["id"] == "svc")
    assert row["cpuUtilization"] == pytest.approx(100 * 0.5 * 2.0 / 1000, rel=0.01)
    # sizing: 100 req/s x 0.5 ms x 2 (the factor at 100 req/s)
    sized = copy.deepcopy(d)
    sized["architecture"]["autoSize"] = True
    sized["architecture"]["nodes"][1]["config"]["cpuCores"] = None
    need = run_simulation(sized)["sizing"]["nodes"]["svc"]["need"]
    assert need["busyCores"] == pytest.approx(0.1, rel=0.01)
    # review: 2,000 req/s where the factor is 1 (not 1,000 at the quiet rate's factor of 2)
    busy = loop(1900, curve)
    finding = next(f for f in run_simulation(busy)["review"] if f["rule"] == "cpu-capacity")
    assert "about 2,000 req/s" in finding["detail"]


# -- calibration ----------------------------------------------------------------------------------


def test_fit_load_curve_bins_by_rate_and_never_rises() -> None:
    # CPU per request 0.8 ms at 50 req/s down to 0.2 ms at 1,600, with a bump at 800
    ms = {50: 0.8, 100: 0.6, 200: 0.4, 400: 0.3, 800: 0.33, 1600: 0.2}
    pts = [(r, r * m / 1000) for r, m in ms.items()]
    fit = fit_load_curve(pts, reference_ms=0.2)
    assert fit is not None
    factors = [f for _, f in fit.points]
    assert factors[0] == pytest.approx(4.0) and factors[-1] == pytest.approx(1.0)
    assert all(a >= b for a, b in pairwise(factors))  # never rising
    assert factors[3] == factors[4]  # the bump pooled with its neighbour
    assert fit.error is not None and 0 < fit.error < 0.1
    # quantile bins: 600 points into at most 6 bins of 100
    many = [(r + i * 0.001, r * m / 1000) for r, m in ms.items() for i in range(100)]
    assert len(fit_load_curve(many).points) == 6  # type: ignore[union-attr]
    assert fit_load_curve([(100, 0.02), (120, 0.024)]) is None  # too little spread in the rate


def test_fit_cpu_share_reports_the_curve_per_replica() -> None:
    ms = {100: 0.8, 400: 0.4, 1600: 0.2, 3200: 0.2}
    pts = [(2 * r, 2 * r * m / 1000) for r, m in ms.items()]  # two replicas
    fit = fit_cpu_share(pts, service_ms=0.4, replicas=2)
    assert fit is not None and fit.curve is not None
    assert [r for r, _ in fit.curve.points] == [100, 400, 1600, 3200]
    # factors relative to CPU per request over the range (busy times weigh most)
    assert fit.curve.reference_ms == pytest.approx(fit.cpu_ms)
    assert fit.curve.table()[0]["factor"] == pytest.approx(0.8 / fit.cpu_ms, rel=1e-3)
