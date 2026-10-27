"""Latency counting failures: successes at their latency and each failed request as a full client
timeout, so a system that fails fast never looks fast (report 1.12)."""

from __future__ import annotations

import copy
from typing import Any

import pytest

from archsim_engine import run_simulation
from archsim_engine.cli import _summary_line
from archsim_engine.metrics import LatencyWithFailures
from engine_builders import short


def test_failures_count_exactly_as_the_timeout() -> None:
    h = LatencyWithFailures()
    for v in range(1, 91):  # 90 successes, 1-90 ms
        h.success(float(v))
    for v in (2.0, 5.0, 40.0, 900.0, 2999.0, 3000.0, 3000.0, 3000.0, 3000.0):
        h.failure(v, 3000.0)  # fast errors and timeouts: all count as the 3 s timeout
    h.failure(7000.0, 3000.0)  # retries ran past the timeout: counts as long as it took
    s = h.summary()
    assert s["count"] == 100
    assert s["p95"] == 3000.0 and s["p99"] == 3000.0  # exactly the timeout, not a bin near it
    assert s["max"] == 7000.0
    assert s["p50"] == pytest.approx(50, rel=0.02)
    assert s["mean"] == pytest.approx((sum(range(1, 91)) + 9 * 3000 + 7000) / 100)


def test_empty() -> None:
    s = LatencyWithFailures().summary()
    assert s["count"] == 0 and s["p95"] is None and s["mean"] is None and s["max"] is None


def _redis_outage(starter: dict[str, Any]) -> dict[str, Any]:
    d = copy.deepcopy(short(starter, duration=30, warmup=4))
    # at generic rates PostgreSQL is auto-sized to 1 vCPU, so the reads Redis no longer answers
    # fill the API servers up; on the AWS types the starter pins it has 2 and copes better
    d.pop("pricing", None)
    d["workload"]["faults"] = [
        {"id": "f1", "kind": "outage", "target": "cache", "startS": 10, "durationS": 15}
    ]
    return d


def test_without_failures_it_equals_latency(starter: dict[str, Any]) -> None:
    r = run_simulation(short(starter, duration=20, warmup=4))
    s = r["summary"]
    assert s["successRate"] == 1.0
    assert s["latencyWithFailuresMs"] == s["latencyMs"]
    assert "counting failures" not in _summary_line(r)


def test_a_redis_outage_shows_up_as_the_client_timeout(starter: dict[str, Any]) -> None:
    clients = next(n for n in starter["architecture"]["nodes"] if n["id"] == "clients")
    timeout = clients["config"]["timeoutMs"]
    r = run_simulation(_redis_outage(starter))
    s = r["summary"]
    assert s["successRate"] < 0.95  # the API servers fill up while every read goes to PostgreSQL
    assert s["latencyMs"]["p95"] < timeout  # successes alone look tolerable
    assert s["latencyWithFailuresMs"]["p95"] == timeout  # what users waited does not
    assert s["latencyWithFailuresMs"]["count"] == s["succeeded"] + s["failed"] + s["timedOut"]
    assert f"p95 counting failures {timeout:,.1f} ms" in _summary_line(r)
