"""Phase 8: sampled (hybrid) mode and parallel sweeps."""

from __future__ import annotations

import json
import pickle
from typing import Any

import pytest

from archsim_engine import (
    DocumentInvalidError,
    SimulationCancelledError,
    SimulationLimitError,
    default_registry,
    load_document,
    run_simulation,
)
from archsim_engine.engine import sample_seconds, stationary
from archsim_engine.issues import error, warning
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


def test_engine_errors_survive_the_process_boundary() -> None:
    # errors raised in a worker process are pickled back to the parent; one that does not
    # unpickle breaks the whole process pool instead of reporting the problem
    issues = [
        error("node.unknown_type", "Unknown component type 'x'.", node_id="lb", hint="Pick one."),
        warning("edge.unused", "Unused link.", edge_id="e1"),
        error("workload.rps", "rps must be positive.", path="workload.rps"),
    ]
    invalid = pickle.loads(pickle.dumps(DocumentInvalidError(issues)))
    assert isinstance(invalid, DocumentInvalidError)
    assert invalid.issues == issues
    assert str(invalid) == "Unknown component type 'x'. (+1 more)"
    for exc in (SimulationLimitError("too many requests"), SimulationCancelledError("cancelled")):
        back = pickle.loads(pickle.dumps(exc))
        assert type(back) is type(exc) and str(back) == str(exc)


def test_a_parallel_sweep_of_an_invalid_design_reports_why() -> None:
    doc = starter(durationS=20, warmupS=4)
    doc["architecture"]["nodes"][1]["type"] = "no.such.component"  # caught in each step
    with pytest.raises(DocumentInvalidError) as caught:
        breaking_point(
            doc,
            default_registry(),
            from_rps=100,
            to_rps=200,
            steps=2,
            duration_s=4,
            warmup_s=1,
            workers=2,
        )
    assert any(i.node_id == "lb" for i in caught.value.issues)
