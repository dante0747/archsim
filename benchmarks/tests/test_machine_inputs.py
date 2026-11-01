"""W27: the machine every container shares. The contention check's fit (``host_fit``: physical
cores, the clock, a thread sharing its core, the background, k6's CPU) and each calibration
pass's own load on that machine (``machine_inputs``: the design's host and the CPU-time inputs
at full speed). Nothing here starts Docker."""

from __future__ import annotations

import copy
import json
from typing import Any

import pytest
import run

from archsim_engine.components.host import Machine

SIBLINGS = {str(c): [c - c % 2, c - c % 2 + 1] for c in range(8)}
TRUTH = Machine(cores=4, threads_per_core=2, sibling_speed=0.5, all_core_clock=0.75)


def condition(busy: float, cost_us: float, mhz: float) -> dict[str, Any]:
    return {
        "condition": f"D={busy}",
        "probe": {"cpuUsPerUnit": cost_us},
        "host": {"busyLogicalProcessors": busy, "frequencyMHz": mhz},
    }


def check(rest: float = 0.4, k6_cores: float = 0.5) -> dict[str, Any]:
    """A contention check of a machine that follows ``TRUTH`` exactly."""
    probe = [
        condition(d, 30.0 / TRUTH.speed(d), 4000.0 * (1 - 0.25 * (d - 1) / 7))
        for d in (1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.0)
    ]
    return {
        "at": "2026-11-01T03:00:00Z",
        "topology": {"siblings": SIBLINGS},
        "probeRounds": [probe],
        "serviceAtRest": {"busyLogicalProcessors": rest},
        "service": [
            {
                "condition": "as benchmarked",
                "rateRps": 2500,
                "k6Cores": k6_cores,
                "serviceCores": 0.5,
            },
            {"condition": "as benchmarked", "rateRps": 5000, "k6Cores": 2.0, "serviceCores": 1.0},
        ],
    }


def test_the_fit_recovers_the_machine_it_was_measured_on() -> None:
    fit = run.host_fit(check())
    assert fit is not None
    assert fit["physicalCores"] == 4 and fit["threadsPerCore"] == 2
    assert fit["allCoreClock"] == pytest.approx(0.75, abs=0.005)
    assert fit["siblingSpeed"] == pytest.approx(0.5, abs=0.011)
    assert fit["backgroundThreads"] == 0.4
    # k6: the step below 90% of the service's core only (0.5 cores at 2,500 req/s)
    assert fit["k6CpuMsPerRequest"] == pytest.approx(0.2)
    assert "contention check 2026-11-01T03:00:00Z" in fit["how"]


def test_a_check_taken_on_a_busy_machine_or_without_k6_is_not_used() -> None:
    assert run.host_fit(check(rest=2.5)) is None
    no_rest = check()
    del no_rest["serviceAtRest"]
    assert run.host_fit(no_rest) is None
    no_k6 = check()
    for st in no_k6["service"]:
        st["k6Cores"] = None
    assert run.host_fit(no_k6) is None


def passes(rate: float = 1600.0) -> dict[str, Any]:
    """A pg-mixed top pass: 30 s at ``rate`` with the containers' CPU."""
    wall = 33_000.0
    return {
        "high": {
            "rateRps": rate,
            "service": {"wallMs": wall},
            "containerCpuMs": {"api": 0.32 * wall, "postgres": 0.28 * wall},
        },
        "idle": {"containerCpuMsPerSecond": {"api": 4.0, "postgres": 3.0}},
    }


def inputs() -> dict[str, Any]:
    return {
        "serviceTimeMs": {
            "value": {"kind": "lognormal", "median": 0.17, "p95": 0.45},
            "how": "cpu",
        },
        "dbCpuShare": {"value": 0.9, "how": "share"},
        "clientLinkLatencyMs": {"value": 0.1, "how": "anchored"},
    }


def test_inputs_at_full_speed_and_the_host_from_the_pass(monkeypatch: pytest.MonkeyPatch) -> None:
    fit = run.host_fit(check())
    assert fit is not None
    monkeypatch.setattr(run, "_HOST_FIT", fit)
    got = inputs()
    run.machine_inputs(run.EXPERIMENTS["pg-mixed"], passes(), got)
    busy = 0.32 + 0.28 + 1600 * fit["k6CpuMsPerRequest"] / 1000 + fit["backgroundThreads"]
    m = Machine(cores=4, threads_per_core=2, sibling_speed=fit["siblingSpeed"],
                all_core_clock=fit["allCoreClock"])  # fmt: skip
    speed = m.speed(busy)
    assert speed < 1
    assert got["serviceTimeMs"]["value"]["median"] == pytest.approx(0.17 * speed, rel=1e-4)
    assert got["serviceTimeMs"]["value"]["p95"] == pytest.approx(0.45 * speed, rel=1e-4)
    assert got["dbCpuShare"]["value"] == pytest.approx(0.9 * speed, abs=1e-4)
    assert "thread speed at the pass" in got["serviceTimeMs"]["how"]
    assert got["clientLinkLatencyMs"]["value"] == 0.1  # not CPU time
    host = got["host"]["value"]
    assert host["physicalCores"] == 4 and host["otherBusyThreads"] == pytest.approx(0.4)
    assert host["clientCpuMs"] == pytest.approx(fit["k6CpuMsPerRequest"])
    # and the design carries it, as a schema 1.7 document
    v = {
        **{k: x["value"] for k, x in got.items()},
        "clientLinkLatencyMs": 0.1,
        "readServiceTimeMs": {"kind": "constant", "value": 0.15},
        "writeServiceTimeMs": {"kind": "constant", "value": 0.5},
    }
    doc = run.design(run.EXPERIMENTS["pg-mixed"], {k: {"value": x} for k, x in v.items()}, 1000)
    assert doc["schemaVersion"] == "1.7" and doc["architecture"]["host"] == host


def test_containers_the_model_has_no_cpu_for_join_the_hosts_other_load(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(run, "_HOST_FIT", run.host_fit(check()))
    p = passes(600.0)
    wall = p["high"]["service"]["wallMs"]
    p["high"]["containerCpuMs"] |= {"kafka": 0.9 * wall, "consumer": 0.3 * wall}
    p["idle"]["containerCpuMsPerSecond"] |= {"kafka": 420.0, "consumer": 40.0}
    got = inputs()
    run.machine_inputs(run.EXPERIMENTS["kafka"], p, got)
    host = got["host"]["value"]
    assert host["otherBusyThreads"] == pytest.approx(0.4 + 0.42 + 0.04)
    per_request = (0.9 - 0.42 + 0.3 - 0.04) * 1000 / 600
    assert host["clientCpuMs"] == pytest.approx(0.2 + per_request, abs=1e-4)
    assert "kafka, consumer (no CPU in the model" in got["host"]["how"]


def test_without_a_quiet_check_nothing_changes(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run, "_HOST_FIT", None)
    got = inputs()
    before = copy.deepcopy(got)
    run.machine_inputs(run.EXPERIMENTS["pg-mixed"], passes(), got)
    assert got == before
    v = {k: x["value"] for k, x in got.items()}
    v |= {"readServiceTimeMs": 0.15, "writeServiceTimeMs": 0.5}
    doc = run.design(run.EXPERIMENTS["pg-mixed"], {k: {"value": x} for k, x in v.items()}, 1000)
    assert "host" not in doc["architecture"] and doc["schemaVersion"] == "1.0"


def test_the_committed_checks_are_this_machines(tmp_path: Any) -> None:
    # every committed contention check names the laptop and parses
    for path in run.host_checks():
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["environment"]["machineId"] == "i7-1165g7"
        assert data["probeRounds"]
    assert run.host_checks(tmp_path) == []


def test_the_host_is_opt_in_and_a_what_if_fit_can_be_given(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Any
) -> None:
    fit = run.host_fit(check())
    path = tmp_path / "fit.json"
    path.write_text(json.dumps(fit), encoding="utf-8")
    monkeypatch.setenv("BENCH_HOST_FIT", str(path))
    for switch, expected in (("", None), ("off", None), ("on", fit)):
        monkeypatch.setattr(run, "_HOST_FIT", False)
        monkeypatch.setenv("BENCH_HOST", switch)
        assert run.machine_fit() == expected
    monkeypatch.setattr(run, "_HOST_FIT", False)
    monkeypatch.setenv("BENCH_HOST_CLOCK", "0.9")
    got = run.machine_fit()
    assert got is not None and got["allCoreClock"] == 0.9 and "what-if" in got["how"]
    monkeypatch.setattr(run, "_HOST_FIT", False)
