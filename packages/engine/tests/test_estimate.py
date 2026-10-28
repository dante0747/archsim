"""The instant estimate from queueing formulas, against the formulas themselves and against full
simulations of two templates.

Tolerance: the estimate's mean and p95 latency within 15% of the simulated ones, and the busiest
part's utilization within 5 points, on the starter and the queueing lab (today they agree within
7%; the p95 is a rough lognormal approximation, so it gets no tighter promise)."""

from __future__ import annotations

import copy
import math
import time
from typing import Any

import pytest

from archsim_engine import run_simulation
from archsim_engine.analyze import analyze_document
from archsim_engine.components import create_default_registry
from archsim_engine.estimate import (
    Moments,
    estimate_design,
    lognormal_p95,
    mmc_wait,
    moments,
    times,
)
from archsim_engine.load import ttl_hit_ratio, ttl_miss_shares
from archsim_engine.scenarios import load_document, resolve_scenario_sized
from engine_builders import EXAMPLES, doc, edge, load_example, node

REGISTRY = create_default_registry(include_plugins=False)
TOLERANCE = 0.15
UTIL_POINTS = 0.05


def estimate_of(d: dict[str, Any]) -> dict[str, Any]:
    resolved, _scenario, _sizes = resolve_scenario_sized(load_document(d), None, REGISTRY)
    return estimate_design(resolved, REGISTRY)


# ---------------------------------------------------------------------------------------------
# the formulas
# ---------------------------------------------------------------------------------------------


def test_mm1_wait_is_the_textbook_one() -> None:
    # M/M/1, mean service 10 ms, 50 req/s: rho 0.5, Wq = rho / (mu - lambda) = 10 ms
    pw, w = mmc_wait(1, 50.0, 10.0)  # type: ignore[misc]
    assert pw == pytest.approx(0.5)
    assert w.mean == pytest.approx(10.0)
    # the wait is 0 with probability 1 - rho, else exponential: E[W^2] = 2 rho / (mu - lambda)^2
    assert w.var + w.mean**2 == pytest.approx(2 * 0.5 / 50.0**2 * 1e6)
    assert mmc_wait(1, 100.0, 10.0) is None  # rho = 1: unstable


def test_moments_of_each_distribution() -> None:
    from archsim_engine.distributions import Constant, Empirical, Exponential, LogNormal, Uniform

    assert moments(Constant(value=3)) == Moments(3, 0)
    assert moments(Exponential(mean=10)) == Moments(10, 100)
    u = moments(Uniform(min=0, max=12))
    assert (u.mean, u.var) == pytest.approx((6, 12))
    ln = LogNormal(median=8, p95=25)
    m = moments(ln)
    assert m.mean == pytest.approx(ln.expected())
    assert m.var == pytest.approx(m.mean**2 * math.expm1(ln.sigma**2))
    e = moments(Empirical(quantiles=[(0, 0), (1, 10)]))  # uniform 0-10 as a table
    assert (e.mean, e.var) == pytest.approx((5, 100 / 12))


def test_calls_made_a_fraction_of_the_time() -> None:
    x = Moments(10, 4)
    assert times(1, x) == x
    half = times(0.5, x)
    assert half.mean == pytest.approx(5)
    assert half.var == pytest.approx(0.5 * (4 + 100) - 25)
    assert times(1.5, x).mean == pytest.approx(15)


def test_lognormal_p95_of_one_lognormal_is_its_p95() -> None:
    from archsim_engine.distributions import LogNormal

    m = moments(LogNormal(median=20, p95=60))
    assert lognormal_p95([(1.0, m)]) == pytest.approx(60, rel=1e-3)


def test_ttl_hit_ratio_matches_the_renewal_formula() -> None:
    # 100 equally popular keys read 100 times a second: each key once a second, TTL 1 s
    assert ttl_hit_ratio(100, 0, 100, 0.0, 1.0) == pytest.approx(0.5, abs=1e-6)
    # writes invalidate: the window is (1 - e^-wT) / w, shorter than the TTL
    w = (1 - math.exp(-1.0)) / 1.0
    assert ttl_hit_ratio(100, 100, 100, 0.0, 1.0) == pytest.approx(w / (1 + w), abs=1e-6)
    # skewed popularity: the hot keys hit more, so the whole cache does too
    assert ttl_hit_ratio(100, 0, 100, 1.2, 1.0) > 0.5
    # large keyspaces use buckets beyond 2,000 keys and stay close to the exact sum
    exact = sum((k**-0.9) for k in range(1, 50_001))
    want = sum(
        (s := k**-0.9 / exact) * (1000 * s * 60 / (1 + 1000 * s * 60)) for k in range(1, 50_001)
    )
    assert ttl_hit_ratio(1000, 0, 50_000, 0.9, 60) == pytest.approx(want, rel=0.01)
    # memory for half the keys: the most popular half is held, reads of the rest miss
    assert ttl_hit_ratio(100, 0, 100, 0.0, 1.0, capacity_keys=50) == pytest.approx(0.25, abs=1e-6)
    assert ttl_hit_ratio(100, 0, 100, 0.0, 1.0, capacity_keys=500) == pytest.approx(0.5, abs=1e-6)
    top = ttl_hit_ratio(1000, 0, 50_000, 0.9, 60, capacity_keys=10_000)
    assert top < ttl_hit_ratio(1000, 0, 50_000, 0.9, 60)
    held = sum(
        (s := k**-0.9 / exact) * (1000 * s * 60 / (1 + 1000 * s * 60)) for k in range(1, 10_001)
    )
    assert top == pytest.approx(held, rel=0.01)


def test_the_base_rate_of_each_traffic_pattern() -> None:
    from archsim_engine.load import base_rps

    wl = {"rps": 100, "durationS": 60}
    assert base_rps(wl) == 100
    assert (
        base_rps(
            {**wl, "pattern": {"kind": "burst", "burstRps": 900, "startS": 10, "durationS": 5}}
        )
        == 100
    )
    assert base_rps({**wl, "pattern": {"kind": "ramp", "startRps": 200, "endRps": 1000}}) == 200
    trace = {"kind": "trace", "points": [[0, 100], [10, 100], [11, 1000], [20, 1000], [21, 100]]}
    assert base_rps({**wl, "pattern": trace}) == pytest.approx(100)


def test_ttl_miss_shares_follow_the_load_reaching_each_cache() -> None:
    from archsim_engine.scenarios import normalize_document

    d = load_document(load_example("retry-storm-ttl-cache.json"))
    shares = ttl_miss_shares(normalize_document(d, REGISTRY), REGISTRY)
    assert set(shares) == {"cache"} and 0 < shares["cache"] < 1
    # at a lower rate each key is read less often, so more reads find it expired
    low = ttl_miss_shares(normalize_document(d, REGISTRY), REGISTRY, rate=10)
    assert low["cache"] > shares["cache"]


# ---------------------------------------------------------------------------------------------
# against the simulation
# ---------------------------------------------------------------------------------------------


@pytest.mark.parametrize("name", ["queueing-lab.json", "cache-aside-starter.json"])
def test_estimate_agrees_with_the_simulation(name: str) -> None:
    d = load_example(name)
    report = run_simulation(d)
    est = report["estimate"]
    assert est == analyze_document(d, REGISTRY)["estimate"]  # same input, same estimate
    sim = report["summary"]["latencyMs"]
    assert est["latencyMs"]["mean"] == pytest.approx(sim["mean"], rel=TOLERANCE)
    assert est["latencyMs"]["p95"] == pytest.approx(sim["p95"], rel=TOLERANCE)
    busiest = est["busiest"]
    simulated = next(n for n in report["nodes"] if n["id"] == busiest["id"])["summary"]
    assert busiest["utilization"] == pytest.approx(simulated["utilization"], abs=UTIL_POINTS)
    # where the time goes: the network and the busiest part, as the traces measured them
    sim_rows = {r["label"]: r["meanMs"] for r in report["explain"]["latencyBreakdown"]}
    est_rows = {r["label"]: r["meanMs"] for r in est["breakdown"]}
    assert est_rows["Network"] == pytest.approx(sim_rows["Network"], rel=TOLERANCE)
    assert sum(est_rows.values()) == pytest.approx(est["latencyMs"]["mean"], rel=0.01)


def test_the_starter_estimate_reads_well(starter: dict[str, Any]) -> None:
    est = analyze_document(starter, REGISTRY)["estimate"]
    assert est["method"] == "Estimate from queueing formulas (not simulated)"
    assert est["summary"].startswith("p95 ≈ ")
    assert est["summary"].endswith("busiest API Servers 70%")
    assert est["busiest"]["id"] == "api" and est["busiest"]["limitedBy"] == "slots"
    assert not est["overloaded"]
    assert est["saturationRps"] == pytest.approx(400 / est["busiest"]["utilization"], rel=1e-3)
    assert est["p95Approximate"] is True
    assert any("rough approximation" in n for n in est["notes"])
    cache = next(p for p in est["parts"] if p["id"] == "cache")
    # cache-aside: every read looks up, and each miss (20%) writes the value back
    assert cache["arrivalRps"] == pytest.approx(400 * 0.9 * 1.2 + 400 * 0.1)


def test_an_overloaded_design_says_where_it_saturates() -> None:
    d = load_example("queueing-lab.json")
    d["workload"]["rps"] = 500  # 4 workers x 100/s = 400/s
    est = estimate_of(d)
    assert est["overloaded"]
    assert est["latencyMs"] is None
    assert est["saturationRps"] == pytest.approx(400)
    assert est["summary"] == (
        "Overloaded at 500 req/s: Service (4 workers) needs 125% of its capacity; "
        "it saturates at about 400 req/s."
    )
    assert est["parts"][0]["status"] == "overloaded"


def test_work_behind_a_queue_does_not_add_to_latency() -> None:
    d = doc(
        [
            node("c", "traffic_source"),
            node("api", "service", replicas=1, workersPerReplica=8, serviceTimeMs={"kind": "constant", "value": 5}),
            node("q", "message_queue", consumers=1, partitions=1, publishTimeMs={"kind": "constant", "value": 2}, consumerTimeMs={"kind": "constant", "value": 50}),
            node("worker", "service", replicas=1, workersPerReplica=1, serviceTimeMs={"kind": "constant", "value": 1}),
        ],
        [edge("c-api", "c", "api"), edge("api-q", "api", "q"), edge("q-w", "q", "worker")],
        rps=100,
    )  # fmt: skip
    est = estimate_of(d)
    parts = {p["id"]: p for p in est["parts"]}
    # 100 messages/s x 51 ms per message on one consumer: the backlog grows
    assert parts["q"]["status"] == "overloaded"
    assert not parts["worker"]["onPath"]
    # ... but producers only wait for the publish
    assert est["latencyMs"] is not None
    assert est["latencyMs"]["mean"] == pytest.approx(5 + 2, abs=0.5)


@pytest.mark.parametrize("path", sorted(EXAMPLES.glob("*.json")), ids=lambda p: p.stem)
def test_every_template_gets_an_estimate_in_milliseconds(path: Any) -> None:
    d = load_example(path.name)
    resolved, _scenario, _sizes = resolve_scenario_sized(load_document(d), None, REGISTRY)
    t0 = time.perf_counter()
    est = estimate_design(resolved, REGISTRY)
    assert time.perf_counter() - t0 < 0.25
    assert est["busiest"] is not None
    assert est["summary"]
    for p in est["parts"]:
        assert p["status"] in ("ok", "busy", "overloaded", "unknown")
        if p["status"] == "unknown":
            assert p.get("note")


def test_a_ttl_cache_gets_an_estimated_hit_ratio() -> None:
    est = estimate_of(load_example("retry-storm-ttl-cache.json"))
    assert any("hit ratio is estimated at" in n for n in est["notes"])
    db = next(p for p in est["parts"] if p["type"] == "database")
    assert db["arrivalRps"] is not None


def test_estimate_ignores_scenario_free_copies() -> None:
    """The estimate is a pure function of the resolved document."""
    d = load_example("cache-aside-starter.json")
    assert estimate_of(d) == estimate_of(copy.deepcopy(d))
