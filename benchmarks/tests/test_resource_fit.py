"""Fitted CPU and memory inputs against what pg-mixed run 5 measured, read from the committed
results (benchmarks/results/pg-mixed/run-5); nothing starts Docker.

Calibration fits the CPU share from CPU per request and memory from the working set
(archsim_engine.calibration.resources). On the steps below the measured knee, as a calibration
over normal operation would see them:

- the CPU share predicts the API's CPU at the knee within 15%, where ArchSim predicted a full
  core before (it counted the whole service time as CPU). CPU per request falls with load (about
  0.48 ms at 560 req/s, 0.21 ms at 2,231 req/s); ArchSim keeps one cost per request, weighted to
  the busiest times, so it under-counts CPU at quiet times. That is the known limit, and the fit
  reports it;
- memory fitted against the request rate predicts every step within 30%. Fitted against requests
  held (the plan's first idea) it is off by more than 100%, and before calibration (256 MiB at
  idle + 2 MiB per request held) ArchSim predicted about 3x the memory at the knee. Over the whole
  run, overload steps included, memory does not grow with requests held at all;
- PostgreSQL's working set stays flat at every rate, so databases need no load term.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

from archsim_engine import run_simulation
from archsim_engine.calibration.resources import MIN_PER_REQUEST_MB, fit_cpu_share, fit_memory
from archsim_engine.load import mean_ms

# run 5 (protocol v4, 28 Oct 2026), kept in run-5/ since the baseline campaign of 30 Oct
RESULTS = Path(__file__).resolve().parents[1] / "results" / "pg-mixed" / "run-5"


def _load(name: str) -> Any:
    return json.loads((RESULTS / name).read_text(encoding="utf-8"))


MEASURED = _load("measured.json")
COMPARISON = _load("comparison.json")
DESIGN = _load("predictions.json")["design"]
LAST_BELOW_KNEE = COMPARISON["measuredKnee"]["between"][0]  # the knee lies past this step


def steps(below_knee: bool = True) -> list[dict[str, Any]]:
    return [s for s in MEASURED["steps"] if not below_knee or s["offeredRps"] <= LAST_BELOW_KNEE]


def used(step: dict[str, Any], container: str) -> dict[str, float]:
    """CPU cores and working set a container used in the step's load window (median repeat)."""
    return step["resources"]["containers"][container]


def before(step: dict[str, Any]) -> dict[str, float]:
    """What ArchSim predicted for the API at this step before calibration."""
    row = next(r for r in COMPARISON["rows"] if r["offeredRps"] == step["offeredRps"])
    return row["resources"]["api"]["predicted"]


def api_service_ms() -> float:
    api = next(n for n in DESIGN["architecture"]["nodes"] if n["id"] == "api")
    return mean_ms(api["config"]["serviceTimeMs"])


def held(step: dict[str, Any]) -> float:
    """Requests the API held on average: throughput times mean latency (Little's law)."""
    return step["throughputRps"] * step["mean"] / 1000.0


def rel(a: float, b: float) -> float:
    return (a - b) / b


def fitted() -> tuple[Any, Any]:
    below = steps()
    cpu = fit_cpu_share(
        [(s["throughputRps"], used(s, "api")["cores"]) for s in below], api_service_ms()
    )
    memory = fit_memory(
        [used(s, "api")["workingSetMiB"] for s in below],
        rate=[s["throughputRps"] for s in below],
    )
    assert cpu is not None and memory is not None
    return cpu, memory


def test_the_results_are_the_run_this_test_describes() -> None:
    assert MEASURED["experiment"] == "pg-mixed" and MEASURED["at"].startswith("2026-10-28")
    assert [s["offeredRps"] for s in steps()] == [560, 1119, 1678, 2014, 2238]
    assert all(used(s, "api")["cores"] > 0 for s in MEASURED["steps"])


def test_cpu_share_from_cpu_per_request_predicts_the_cpu_at_the_knee() -> None:
    cpu, _ = fitted()
    assert 0.45 < cpu.share < 0.6  # about half the 0.45 ms service time needs a core
    assert cpu.error is not None and cpu.error <= 0.15  # at and above the median rate
    top = steps()[-1]
    measured = used(top, "api")["cores"]
    assert abs(rel(cpu.predict(top["throughputRps"]), measured)) < 0.15
    assert rel(before(top)["cores"], measured) > 1.0  # a full core where 0.46 was used


def test_cpu_per_request_falls_with_load_and_the_fit_says_so() -> None:
    cpu, _ = fitted()
    assert cpu.load is not None and cpu.load.quiet_ms > 2 * cpu.load.busy_ms
    assert any("falls with load" in n and "under-counts" in n for n in cpu.notes)
    # the known limit: one cost per request, weighted to the busiest times, is low at quiet times
    low = steps()[0]
    assert rel(cpu.predict(low["throughputRps"]), used(low, "api")["cores"]) < -0.4


def test_memory_follows_the_request_rate_not_requests_held() -> None:
    below = steps()
    ws = [used(s, "api")["workingSetMiB"] for s in below]
    _, by_rate = fitted()
    assert 0.06 < by_rate.per_rps_mb < 0.09 and by_rate.baseline_mb < 10
    assert by_rate.per_request_mb == MIN_PER_REQUEST_MB  # not fitted against: its floor
    assert (
        max(
            abs(rel(by_rate.predict(s["throughputRps"]), w)) for s, w in zip(below, ws, strict=True)
        )
        < 0.3
    )
    by_held = fit_memory(ws, held=[held(s) for s in below])
    assert by_held is not None
    assert (
        max(abs(rel(by_held.predict(held=held(s)), w)) for s, w in zip(below, ws, strict=True))
        > 1.0
    )
    assert rel(before(below[-1])["memoryMiB"], ws[-1]) > 1.5  # 256 MiB + 2 MiB per request held


def test_over_the_whole_run_memory_does_not_grow_with_requests_held() -> None:
    every = steps(below_knee=False)
    assert max(held(s) for s in every) > 10 * max(
        held(s) for s in steps()
    )  # overload decouples them
    both = fit_memory(
        [used(s, "api")["workingSetMiB"] for s in every],
        rate=[s["throughputRps"] for s in every],
        held=[held(s) for s in every],
    )
    assert both is not None and both.against == ("rate", "held")
    assert both.per_request_mb == MIN_PER_REQUEST_MB
    assert any("did not grow with the requests held" in n for n in both.notes)
    assert 0.06 < both.per_rps_mb < 0.09


def test_postgres_working_set_stays_flat() -> None:
    ws = [used(s, "postgres")["workingSetMiB"] for s in MEASURED["steps"]]
    assert max(ws) / min(ws) < 1.05


def _simulate(design: dict[str, Any], rps: float) -> tuple[float, float]:
    """Mean CPU cores in the measurement window and the memory peak (MiB) of the API."""
    doc = copy.deepcopy(design)
    doc["workload"]["rps"] = rps
    report = run_simulation(doc)
    api = next(n for n in report["nodes"] if n["id"] == "api")
    window, ts = report["window"], report["timeseries"]
    busy = [
        v
        for v, t in zip(api["timeseries"]["cpuBusyCores"], ts["t"], strict=False)
        if v is not None and window["warmupS"] <= t + ts["bucketS"] / 2 < window["durationS"]
    ]
    return sum(busy) / len(busy), api["summary"]["memoryPeakGiB"] * 1024.0


def test_the_calibrated_design_predicts_what_was_measured() -> None:
    """The benchmark's own design with the fitted inputs, simulated at the knee and at the
    lowest step: what ArchSim now predicts against what was measured."""
    cpu, memory = fitted()
    design = copy.deepcopy(DESIGN)
    api = next(n for n in design["architecture"]["nodes"] if n["id"] == "api")
    api["config"].update(
        {
            "cpuShare": cpu.share,
            "memoryBaselineMb": memory.baseline_mb,
            "memoryPerRpsMb": memory.per_rps_mb,
            "memoryPerRequestMb": memory.per_request_mb,
        }
    )
    top, low = steps()[-1], steps()[0]
    cores, mib = _simulate(design, top["offeredRps"])
    assert abs(rel(cores, used(top, "api")["cores"])) < 0.15
    assert abs(rel(mib, used(top, "api")["workingSetMiB"])) < 0.15
    cores, mib = _simulate(design, low["offeredRps"])
    assert abs(rel(mib, used(low, "api")["workingSetMiB"])) < 0.15
    assert rel(cores, used(low, "api")["cores"]) < -0.4  # the known limit, as above
