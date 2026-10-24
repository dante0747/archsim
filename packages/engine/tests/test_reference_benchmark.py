"""Deterministic synthetic reference benchmark.

1. Analytic queueing checks: with Poisson arrivals and exponential service a single service is an
   M/M/c queue, whose mean response time is known exactly (Erlang C). The simulator must match
   within statistical tolerance.
2. Directional checks on the starter example with documented tolerances. These guard against
   regressions in simulation semantics; they are NOT predictions of any real system.
"""

from __future__ import annotations

import math
from typing import Any

import pytest

from archsim_engine import run_simulation
from engine_builders import node_by_id, short, single_service


def erlang_c_mean_response_ms(lam: float, mean_service_ms: float, c: int) -> float:
    """Mean time in system W for M/M/c (lam in req/s)."""
    mu = 1000.0 / mean_service_ms
    a = lam / mu
    rho = a / c
    assert rho < 1
    top = a**c / math.factorial(c) / (1 - rho)
    bottom = sum(a**k / math.factorial(k) for k in range(c)) + top
    p_wait = top / bottom
    wq = p_wait / (c * mu - lam)
    return (wq + 1 / mu) * 1000.0


@pytest.mark.parametrize(
    ("lam", "c", "rho_label"),
    [(50.0, 1, "M/M/1 rho=0.5"), (80.0, 1, "M/M/1 rho=0.8"), (320.0, 4, "M/M/4 rho=0.8")],
)
def test_mmc_mean_response_matches_erlang_c(lam: float, c: int, rho_label: str) -> None:
    mean_service = 10.0
    duration = 400.0 if c == 1 else 200.0
    r = run_simulation(
        single_service(rps=lam, workers=c, durationS=duration, warmupS=20, seed=2024)
    )
    expected = erlang_c_mean_response_ms(lam, mean_service, c)
    measured = r["summary"]["latencyMs"]["mean"]
    assert measured == pytest.approx(expected, rel=0.08), rho_label
    svc = node_by_id(r, "svc")["summary"]
    assert svc["utilization"] == pytest.approx(lam * mean_service / 1000 / c, rel=0.05)


def test_mm1_median_matches_exponential_response_time() -> None:
    # M/M/1 response time is exponential with rate (mu - lam): median = ln 2 / (mu - lam)
    r = run_simulation(single_service(rps=50, workers=1, durationS=400, warmupS=20, seed=99))
    expected_median = math.log(2) / (100 - 50) * 1000
    assert r["summary"]["latencyMs"]["p50"] == pytest.approx(expected_median, rel=0.08)


def test_littles_law_holds_per_node(starter: dict[str, Any]) -> None:
    r = run_simulation(starter)
    for nid in ("api", "db"):
        s = node_by_id(r, nid)["summary"]
        lam = s["arrivalRps"]
        w = (s["meanQueueWaitMs"] + s["meanHoldMs"]) / 1000.0
        assert s["meanInSystem"] == pytest.approx(lam * w, rel=0.1), nid


# Expected values for the starter example (seeded). Ranges are deliberately loose: they pin
# semantics, not exact numbers, so harmless refactors do not break them.
STARTER_EXPECTATIONS = {
    "baseline": {"throughput": (380, 420), "api_util": (0.55, 0.80), "p50": (35, 65)},
    "one-api-replica": {"throughput": (240, 340), "api_util": (0.95, 1.0)},
    "three-api-replicas": {"throughput": (380, 420), "api_util": (0.35, 0.55)},
}


def test_starter_reference_benchmark(starter: dict[str, Any]) -> None:
    d = short(starter, duration=40, warmup=8)
    reports = {sid: run_simulation(d, scenario_id=sid) for sid in STARTER_EXPECTATIONS}
    for sid, exp in STARTER_EXPECTATIONS.items():
        s = reports[sid]["summary"]
        api = node_by_id(reports[sid], "api")["summary"]
        lo, hi = exp["throughput"]
        assert lo <= s["throughputRps"] <= hi, (sid, s["throughputRps"])
        lo, hi = exp["api_util"]
        assert lo <= api["utilization"] <= hi, (sid, api["utilization"])
        if "p50" in exp:
            lo, hi = exp["p50"]
            assert lo <= s["latencyMs"]["p50"] <= hi, (sid, s["latencyMs"]["p50"])

    base, one, three = (reports[k] for k in ("baseline", "one-api-replica", "three-api-replicas"))
    # direction: fewer replicas -> rejections and much higher latency; more -> lower utilization
    assert node_by_id(one, "api")["summary"]["rejectedTotal"] > 0
    assert one["summary"]["latencyMs"]["p95"] > 2 * base["summary"]["latencyMs"]["p95"]
    assert three["summary"]["latencyMs"]["p95"] <= base["summary"]["latencyMs"]["p95"] * 1.05
    assert one["findings"][0]["kind"] == "bottleneck" and one["findings"][0]["nodeId"] == "api"
    assert base["findings"][0]["kind"] == "healthy"
