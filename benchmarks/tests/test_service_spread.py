"""The service time's spread anchored at the top calibration pass (``anchor_spread``): narrowed,
keeping its mean, until the model's p95 - p50 there is k6's, and never widened. The simulations
are stubbed: each returns a p95 - p50 that grows with the service time's p95/median ratio."""

from __future__ import annotations

from typing import Any

import pytest
import run

CACHE_ASIDE = run.EXPERIMENTS["cache-aside"]
WIDE = {"kind": "lognormal", "median": 0.0618, "p95": 0.7035}  # cache-aside run 7: 11.4x
TOP = {"rateRps": 3350.0, "k6": {"p50": 0.416, "p95": 1.416}}  # k6's p95 - p50: 1 ms


def inputs(value: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "serviceTimeMs": {"value": dict(value or WIDE), "how": "mean = the CPU per request"},
        "clientLinkLatencyMs": {"value": 0.08, "how": "x"},
    }


def ratio(v: dict[str, Any]) -> float:
    return v["p95"] / v["median"]


def stub(monkeypatch: pytest.MonkeyPatch, spread: Any) -> list[dict[str, Any]]:
    """Each simulation's inputs, design and duration; it returns p50 0.3 ms and p95 0.3 ms plus
    ``spread(ratio)``."""
    calls: list[dict[str, Any]] = []

    def design(exp: Any, probe: dict[str, Any], rate: float, **kw: Any) -> dict[str, Any]:
        calls.append({"inputs": probe, "rate": rate, **kw})
        return probe

    def simulate(probe: dict[str, Any]) -> dict[str, Any]:
        return {"p50": 0.3, "p95": 0.3 + spread(ratio(probe["serviceTimeMs"]["value"]))}

    monkeypatch.setattr(run, "design", design)
    monkeypatch.setattr(run, "simulate", simulate)
    return calls


def test_a_spread_wider_than_k6s_narrows_and_keeps_its_mean(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = stub(monkeypatch, lambda r: 0.2 * r)  # k6's 1 ms at a ratio of 5
    got = inputs()
    run.anchor_spread(CACHE_ASIDE, got, TOP)
    v = got["serviceTimeMs"]["value"]
    assert v["kind"] == "lognormal"
    assert 0.2 * ratio(v) == pytest.approx(1.0, rel=run.SPREAD_TOLERANCE + 0.01)
    assert run.mean_ms(v) == pytest.approx(run.mean_ms(WIDE), rel=1e-3)  # the CPU per request
    assert got["serviceTimeMs"]["how"].startswith("mean = the CPU per request; spread anchored: ")
    assert (
        "instead of 11.38, so that the model's p95 - p50 at 3350 req/s is k6's"
        in got["serviceTimeMs"]["how"]
    )
    # at the pass's rate, without the client latency, briefly, and few times
    assert {c["rate"] for c in calls} == {3350.0}
    assert all(c["duration"] == run.SPREAD_S and c["warm"] == 5 for c in calls)
    assert all(c["inputs"]["clientLinkLatencyMs"]["value"] == 0.0 for c in calls)
    assert len(calls) <= 1 + run.SPREAD_STEPS
    assert got["clientLinkLatencyMs"]["value"] == 0.08  # anchor() sets it afterwards


def test_a_spread_no_wider_than_k6s_is_kept(monkeypatch: pytest.MonkeyPatch) -> None:
    # pg-mixed, pg-hot-row and Kafka in v6: the model's tail at the top pass is k6's or less,
    # and what it misses may not be the service's, so it is never widened
    calls = stub(monkeypatch, lambda r: 0.05 * r)
    got = inputs()
    run.anchor_spread(CACHE_ASIDE, got, TOP)
    assert got == inputs() and len(calls) == 1


def test_the_spread_stops_at_the_narrowest_it_may_be(monkeypatch: pytest.MonkeyPatch) -> None:
    stub(monkeypatch, lambda r: 2.0 + 0.1 * r)  # wider than k6's however narrow
    got = inputs()
    run.anchor_spread(CACHE_ASIDE, got, TOP)
    assert run.MIN_SPREAD <= ratio(got["serviceTimeMs"]["value"]) < 1.1


def test_a_tail_the_spread_does_not_move_leaves_it(monkeypatch: pytest.MonkeyPatch) -> None:
    stub(monkeypatch, lambda r: 3.0)  # the tail is something else's
    got = inputs()
    run.anchor_spread(CACHE_ASIDE, got, TOP)
    assert got == inputs()


def test_nothing_to_anchor_without_a_lognormal_or_k6s_p95(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = stub(monkeypatch, lambda r: r)
    exp_ = inputs({"kind": "exponential", "mean": 0.2})
    run.anchor_spread(CACHE_ASIDE, exp_, TOP)
    assert exp_["serviceTimeMs"]["value"] == {"kind": "exponential", "mean": 0.2}
    got = inputs()
    run.anchor_spread(CACHE_ASIDE, got, {"rateRps": 3350.0, "k6": {"p50": 0.4}})
    assert got == inputs() and calls == []
