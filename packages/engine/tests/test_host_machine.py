"""CPU threads that slow each other down (schema 1.7): the speed formula, the shared host machine
(``architecture.host``) and a part's own hyperthreads (``siblingSpeed``)."""

from __future__ import annotations

import copy
import itertools
import math
from typing import Any

import pytest

from archsim_engine import run_simulation
from archsim_engine.components.host import (
    Machine,
    full_load_threads,
    host_machine,
    machine_capacity,
    private_machine,
)
from archsim_engine.migrations import migrate_document
from archsim_engine.schema import ArchitectureDocument

LAPTOP = Machine(cores=4, threads_per_core=2, sibling_speed=0.6, all_core_clock=0.8)


def test_speed_is_one_alone_and_falls_with_the_clock_then_with_sharing() -> None:
    assert LAPTOP.speed(1) == 1.0
    assert LAPTOP.speed(0.5) == 1.0
    # the clock falls in a straight line to 0.8 with all 8 threads busy; up to one thread per
    # core it is the only slowdown
    assert LAPTOP.speed(2.5) == pytest.approx(1 - 0.2 * 1.5 / 7)
    assert LAPTOP.speed(4) == pytest.approx(1 - 0.2 * 3 / 7)
    # past it, threads share cores: at 6 busy, 4 of the 6 share (2 cores hold 2 each)
    assert LAPTOP.speed(6) == pytest.approx((1 - 0.2 * 5 / 7) * (1 - 0.4 * 4 / 6))
    assert LAPTOP.speed(8) == pytest.approx(0.8 * 0.6)
    # past every thread they take turns
    assert LAPTOP.speed(16) == pytest.approx(0.8 * 0.6 * 0.5)
    speeds = [LAPTOP.speed(b / 4) for b in range(4, 80)]
    assert all(a >= b for a, b in itertools.pairwise(speeds))


def test_without_hyperthreads_threads_take_turns_past_the_cores() -> None:
    m = Machine(cores=2, threads_per_core=1)
    assert m.speed(2) == 1.0
    assert m.speed(4) == pytest.approx(0.5)


def test_solve_counts_the_slowdown_and_the_others_on_the_machine() -> None:
    m = Machine(cores=4, sibling_speed=0.6, all_core_clock=0.8, other_busy_threads=0.5,
                client_cpu_ms=0.25)  # fmt: skip
    busy, speed = m.solve(2.0, client_rps=2000)
    assert busy == pytest.approx(2.0 / speed + 0.5 + 0.5, rel=1e-6)
    assert speed == pytest.approx(m.speed(busy))
    assert m.solve(0.0) == (0.5, 1.0)  # the background alone (0.5 threads, no clients)
    assert Machine(cores=4).solve(0.0) == (0.0, 1.0)
    # demand past what the machine can do: capped, not an endless loop
    busy, speed = Machine(cores=1, threads_per_core=1).solve(5.0)
    assert busy > 10 and speed < 0.1


def test_a_part_alone_fills_where_its_threads_do_at_their_speed() -> None:
    m = Machine(cores=4, sibling_speed=0.6, all_core_clock=0.8)
    # one part, one thread: it fills at full speed (the clock with one busy core is 1)
    assert m.scale_filling(1.0, 1.0, 1.0) == pytest.approx(1.0)
    # two parts of one thread each and the same load: two threads busy, clock 1 - 0.2/7
    k = m.scale_filling(2.0, 1.0, 1.0)
    assert k == pytest.approx(1 - 0.2 / 7, rel=1e-6)
    assert m.scale_filling(1.0, 0.0, 1.0) == math.inf


def test_private_hyperthreads_turn_two_vcpus_into_one_core_and_a_bit() -> None:
    assert private_machine(None, 0.6) is None
    assert private_machine(1, 0.6) is None  # its sibling is not its own
    assert private_machine(4, None) is None
    pair = private_machine(2, 0.6)
    assert pair is not None and full_load_threads(pair) == pytest.approx(1.2)
    four = private_machine(4, 0.65)
    assert four is not None and full_load_threads(four) == pytest.approx(2.6)
    assert four.speed(2) == 1.0  # two busy threads on two cores: no sharing


def test_host_machine_reads_the_document_shape() -> None:
    assert host_machine(None) is None
    m = host_machine({"physicalCores": 4, "siblingSpeed": 0.55, "clientCpuMs": 0.2})
    assert m == Machine(cores=4, sibling_speed=0.55, client_cpu_ms=0.2)


# -- in the simulation ----------------------------------------------------------------------


def _doc(rps: float, *, host: dict[str, Any] | None = None, db_cores: float = 2,
         sibling: float | None = None) -> dict[str, Any]:  # fmt: skip
    db: dict[str, Any] = {
        "technology": "postgresql",
        "maxConnections": 10,
        "cpuCores": db_cores,
        "cpuShare": 1.0,
        "readServiceTimeMs": {"kind": "constant", "value": 1.0},
        "writeServiceTimeMs": {"kind": "constant", "value": 1.0},
        "commitFlush": "off",
        "rowLocks": "off",
    }
    if sibling is not None:
        db["siblingSpeed"] = sibling
    doc: dict[str, Any] = {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": "host"},
        "architecture": {
            "autoSize": False,
            "nodes": [
                {"id": "c", "type": "traffic_source", "config": {"timeoutMs": 5000}},
                {
                    "id": "api",
                    "type": "service",
                    "config": {
                        "technology": "nodejs",
                        "replicas": 1,
                        "cpuCores": 1,
                        "queueCapacity": 100000,
                        "queueTimeoutMs": None,
                        "serviceTimeMs": {"kind": "constant", "value": 0.2},
                        "gcPauseMs": 0,
                    },
                },
                {"id": "db", "type": "database", "config": db},
            ],
            "edges": [
                {"id": "e1", "source": "c", "target": "api"},
                {"id": "e2", "source": "api", "target": "db"},
            ],
        },
        "workload": {
            "rps": rps,
            "durationS": 6,
            "warmupS": 1,
            "readRatio": 1.0,
            "seed": 7,
            "arrivalProcess": "deterministic",
        },
    }
    if host is not None:
        doc["architecture"]["host"] = host
    return doc


def _node(report: dict[str, Any], nid: str) -> dict[str, Any]:
    return next(n for n in report["nodes"] if n["id"] == nid)


def test_no_host_and_no_sibling_speed_leave_the_document_and_run_as_before() -> None:
    plain = _doc(1000)
    parsed = ArchitectureDocument.model_validate(plain)
    out = parsed.model_dump(mode="json", by_alias=True)
    assert "host" not in out["architecture"]
    assert all("siblingSpeed" not in n["config"] for n in out["architecture"]["nodes"])
    r = run_simulation(copy.deepcopy(plain))
    assert "host" not in r
    with_one = _doc(1000, sibling=1.0)  # a sibling speed of 1 changes nothing
    r1 = run_simulation(with_one)
    assert r1["summary"]["latencyMs"] == r["summary"]["latencyMs"]


def test_a_part_on_a_busy_host_uses_more_cpu_per_request() -> None:
    host = {"physicalCores": 2, "siblingSpeed": 0.6, "allCoreClock": 0.8}
    base = run_simulation(_doc(1500))
    on = run_simulation(_doc(1500, host=host))
    cores = _node(base, "db")["summary"]["cpuCoreUtilization"]
    slowed = _node(on, "db")["summary"]["cpuCoreUtilization"]
    assert slowed > cores * 1.05
    assert on["host"]["physicalCores"] == 2
    assert 0 < on["host"]["meanSpeed"] < 1
    assert on["host"]["busyThreads"] == pytest.approx(on["host"]["partsBusyThreads"])
    # the service's CPU ceiling falls with the machine's speed at that load
    cap0 = _node(base, "api")["summary"]["capacityRps"]
    cap1 = _node(on, "api")["summary"]["capacityRps"]
    assert cap1 < cap0


def test_the_clients_load_on_the_host_slows_the_parts() -> None:
    host = {"physicalCores": 2, "siblingSpeed": 0.6, "allCoreClock": 0.8}
    quiet = run_simulation(_doc(1500, host=host))
    loaded = run_simulation(_doc(1500, host={**host, "clientCpuMs": 1.0}))
    assert loaded["host"]["otherBusyThreads"] == pytest.approx(1.5)
    assert loaded["host"]["meanSpeed"] < quiet["host"]["meanSpeed"]


def test_own_hyperthreads_cap_a_two_vcpu_database_near_one_core_and_a_fifth() -> None:
    # 1 ms of CPU per query on 2 vCPU: 2,000 queries/s as full cores, 1,200 as a hyperthread pair
    r = run_simulation(_doc(1000, sibling=0.6))
    cap = _node(r, "db")["summary"]["capacityRps"]
    assert cap == pytest.approx(1200, rel=0.01)
    plain = run_simulation(_doc(1000))
    assert _node(plain, "db")["summary"]["capacityRps"] == pytest.approx(2000, rel=0.01)
    # and past one core's worth (queries 1 ms apart overlap), its busy cores rise as they share
    busy = run_simulation(_doc(1100, sibling=0.6))
    alone = run_simulation(_doc(1100))
    assert (
        _node(busy, "db")["summary"]["cpuCoreUtilization"]
        > _node(alone, "db")["summary"]["cpuCoreUtilization"] * 1.05
    )


def test_machine_capacity_without_a_machine_is_the_full_speed_ceiling() -> None:
    assert machine_capacity(None, 123.0, 1.0, 50.0) == 123.0


def test_schema_1_6_documents_migrate_unchanged() -> None:
    doc = _doc(100)
    doc["schemaVersion"] = "1.6"
    out, notes = migrate_document(doc)
    assert out["schemaVersion"] == "1.7"
    assert out["architecture"] == doc["architecture"]
    assert notes


def test_the_static_analyses_see_the_machine_too() -> None:
    from archsim_engine.analyze import analyze_document
    from archsim_engine.components import create_default_registry

    registry = create_default_registry(include_plugins=False)

    def cpu(doc: dict[str, Any]) -> dict[str, float]:
        a = analyze_document(doc, registry)
        return {r["id"]: r["cpuUtilization"] for r in a["estimate"]["parts"]}

    plain = cpu(_doc(1500))
    assert plain == {"api": pytest.approx(0.3), "db": pytest.approx(0.75)}
    host = {"physicalCores": 2, "siblingSpeed": 0.6, "allCoreClock": 0.8}
    slowed = cpu(_doc(1500, host=host))
    assert slowed["api"] > plain["api"] and slowed["db"] > plain["db"]
    # a 2-vCPU database's own hyperthreads: 1.5 busy threads of 2 share their core
    own = cpu(_doc(1500, sibling=0.6))
    assert own["db"] > plain["db"] and own["api"] == pytest.approx(plain["api"])
    # the review says why its CPU is busier
    review = analyze_document(_doc(1500, sibling=0.6), registry)["review"]
    cpu_finding = next(f for f in review if f["rule"] == "cpu-capacity")
    assert "of it on its hyperthreads" in cpu_finding["detail"]
