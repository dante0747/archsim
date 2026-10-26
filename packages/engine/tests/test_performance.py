"""Phase 8: sampled (hybrid) mode and parallel sweeps."""

from __future__ import annotations

import json
from typing import Any

import pytest

from archsim_engine import default_registry, load_document, run_simulation
from archsim_engine.engine import sample_seconds, stationary
from archsim_engine.sweep import breaking_point
from engine_builders import EXAMPLES


def starter(**workload: Any) -> dict[str, Any]:
    d = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    d["scenarios"] = []
    d["workload"].update(workload)
    return d


def test_which_runs_qualify_for_sampling() -> None:
    def wl(**kw: Any) -> Any:
        return load_document(starter(**kw)).workload

    steady = wl(durationS=600, warmupS=10, pattern={"kind": "constant"})
    assert sample_seconds(steady) == pytest.approx(10 + max(10, 50_000 / steady.rps))
    assert sample_seconds(wl(durationS=60, warmupS=5, pattern={"kind": "constant"})) is None
    ramp = {"kind": "ramp", "startRps": 100, "endRps": 900}
    assert sample_seconds(wl(durationS=600, pattern=ramp)) is None
    faults = [{"id": "f1", "kind": "outage", "target": "cache", "startS": 100, "durationS": 20}]
    assert sample_seconds(wl(durationS=600, pattern={"kind": "constant"}, faults=faults)) is None


def test_a_sampled_run_answers_a_long_steady_run() -> None:
    doc = starter(durationS=300, warmupS=10, pattern={"kind": "constant"})
    full = run_simulation(json.loads(json.dumps(doc)))
    fast = run_simulation(json.loads(json.dumps(doc)), mode="sampled")
    mode = fast["simulationMode"]
    assert mode["kind"] == "sampled" and mode["requestedDurationS"] == 300
    assert fast["provenance"]["requestsGenerated"] < full["provenance"]["requestsGenerated"] / 2
    for key in ("p50", "p95"):
        assert fast["summary"]["latencyMs"][key] == pytest.approx(
            full["summary"]["latencyMs"][key], rel=0.05
        )
    assert fast["summary"]["throughputRps"] == pytest.approx(
        full["summary"]["throughputRps"], rel=0.02
    )
    again = run_simulation(json.loads(json.dumps(doc)), mode="sampled")
    assert again["provenance"]["resultDigest"] == fast["provenance"]["resultDigest"]
    # a run that does not qualify is simulated whole, with no mode note
    short = run_simulation(starter(durationS=20, warmupS=2), mode="sampled")
    assert "simulationMode" not in short


def test_a_sample_that_is_not_steady_is_rejected() -> None:
    def report(throughput: list[float], p95: list[float], queue: list[float]) -> dict[str, Any]:
        n = len(throughput)
        return {
            "window": {"warmupS": 0, "durationS": n},
            "timeseries": {
                "bucketS": 1,
                "offeredRps": [100.0] * n,
                "throughputRps": throughput,
                "latencyP95Ms": p95,
            },
            "nodes": [{"id": "api", "timeseries": {"queueLength": queue}}],
        }

    flat = report([100.0] * 20, [10.0] * 20, [2.0] * 20)
    assert stationary(flat)[0]
    growing = report([100.0] * 20, [10.0] * 20, [float(i) for i in range(20)])
    ok, checks = stationary(growing)
    assert not ok and checks["growing"] == ["api.queueLength"]
    falling_behind = report([90.0] * 20, [10.0] * 20, [2.0] * 20)
    assert not stationary(falling_behind)[0]
    slowing = report([100.0] * 20, [10.0] * 10 + [20.0] * 10, [2.0] * 20)
    assert not stationary(slowing)[0]


def test_parallel_sweeps_give_the_same_curve() -> None:
    doc = starter(durationS=20, warmupS=4)
    kw: dict[str, Any] = {
        "from_rps": 200,
        "to_rps": 1200,
        "steps": 3,
        "duration_s": 8,
        "warmup_s": 2,
    }
    serial = breaking_point(doc, default_registry(), **kw)
    parallel = breaking_point(doc, default_registry(), workers=3, **kw)
    assert serial["rows"] == parallel["rows"]
