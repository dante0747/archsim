"""Guard rails for untrusted documents: limits hold inside the event loop, and designs whose one
request would fan out into an enormous number of calls are rejected before they run."""

from __future__ import annotations

import time
from typing import Any

import pytest

from archsim_engine import default_registry, load_document, run_simulation, validate_document
from archsim_engine.engine import SimulationCancelledError, SimulationLimitError, SimulationLimits
from engine_builders import doc, edge, node

C = {"kind": "constant", "value": 1}


def layered(layers: int = 11, width: int = 3, attempts: int = 1, **workload: Any) -> dict[str, Any]:
    """Clients -> gateway -> ``layers`` layers of ``width`` services, each calling every service of
    the next layer (35 parts with the defaults): one request fans out into ~width^layers calls."""
    nodes = [node("clients", "traffic_source", timeoutMs=60_000), node("gw", "load_balancer")]
    edges = [edge("c-gw", "clients", "gw")]
    prev = ["gw"]
    for layer in range(layers):
        cur = [f"s{layer}_{i}" for i in range(width)]
        nodes += [
            node(x, "service", replicas=2, workersPerReplica=64, serviceTimeMs=C) for x in cur
        ]
        edges += [edge(f"{a}-{b}", a, b, maxAttempts=attempts) for a in prev for b in cur]
        prev = cur
    wl = {"rps": 1, "durationS": 10, "warmupS": 0, **workload}
    return doc(nodes, edges, **wl)


def codes(d: dict[str, Any]) -> dict[str, str]:
    result = validate_document(load_document(d), default_registry())
    return {i.code: i.severity for i in result.issues}


def test_validation_rejects_explosive_fan_out_and_names_the_path() -> None:
    d = layered()
    assert len(d["architecture"]["nodes"]) == 35
    result = validate_document(load_document(d), default_registry())
    issue = next(i for i in result.issues if i.code == "design.amplification")
    assert issue.severity == "error"
    # the gateway sends a request to one of the first layer; from there every service calls 3:
    # 1 + 3 + 9 + ... = (3^11 - 1) / 2 calls below it
    assert "88,575" in issue.message and "10,000" in issue.message
    assert "clients → gw → s0_0 → s1_0" in issue.message
    with pytest.raises(Exception, match="88,575"):
        run_simulation(d)


def test_moderate_fan_out_and_retries_warn_then_pass() -> None:
    # 7 layers: (3^7 - 1) / 2 = 1,093 calls below the gateway, plus the gateway and the client
    assert codes(layered(layers=7)).get("design.amplification") == "warning"
    assert "design.amplification" not in codes(layered(layers=6))  # 366
    # retries multiply: 2 attempts on every link of 6 layers make 18,664
    assert codes(layered(layers=6, attempts=2)).get("design.amplification") == "error"


def test_wall_limit_holds_inside_one_request() -> None:
    """Before the check moved into the event loop, this design ran one request for ~7 s against
    a 1 s limit, because limits were only checked between progress chunks."""
    limits = SimulationLimits(max_amplification=None, max_wall_seconds=1.0)
    t0 = time.perf_counter()
    with pytest.raises(SimulationLimitError, match="wall-clock limit"):
        run_simulation(layered(), limits=limits)
    assert time.perf_counter() - t0 < 2.0


def test_cancel_holds_inside_one_request() -> None:
    calls = 0

    def cancel() -> bool:
        nonlocal calls
        calls += 1
        return calls > 3  # after a few checks, i.e. mid-chunk

    t0 = time.perf_counter()
    with pytest.raises(SimulationCancelledError):
        run_simulation(
            layered(), limits=SimulationLimits(max_amplification=None), should_cancel=cancel
        )
    assert time.perf_counter() - t0 < 2.0


def test_events_are_counted_and_deterministic() -> None:
    d = layered(layers=2, durationS=5)
    a = run_simulation(d)
    b = run_simulation(d)
    assert a["provenance"]["eventsProcessed"] > a["provenance"]["requestsGenerated"] > 0
    assert a["provenance"]["eventsProcessed"] == b["provenance"]["eventsProcessed"]
    assert a["provenance"]["resultDigest"] == b["provenance"]["resultDigest"]
