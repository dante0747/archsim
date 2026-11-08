"""Write load sweeps and checks of small designs for the Java engine, or check the committed file.

The frozen engine (0.16.0) finds a design's breaking point by simulating constant loads at evenly
spaced levels and reading the curve (``sweep.breaking_point``); a check measures a design
(``check.measure``), sweeps it and its baseline on one load grid (``check.knee_sweeps``) and
judges it (``check.run_check``). This script runs each on small designs (a range found from the
design's load or given, a scenario, steps the request limit caps, a design that misses its
objectives at the lightest load, a baseline that cannot be simulated) and records the inputs and
what came out. ``SweepCasesTests`` runs each in Java and compares: digests, counts and verdicts
exactly, numbers to a relative 1e-9.

Usage:
    uv run python scripts/export_sweep_cases.py           # write the cases
    uv run python scripts/export_sweep_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import copy
import gzip
import json
import sys
from pathlib import Path
from typing import Any

from export_simulation_cases import clients, design, link, service

from archsim_engine.check import knee_sweeps, measure, run_check
from archsim_engine.engine import SimulationLimits, default_registry
from archsim_engine.sweep import breaking_point

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/sweep/cases.json.gz"
)
GENERATOR = "scripts/export_sweep_cases.py (archsim_engine 0.16.0)"
Json = dict[str, Any]


def api(workers: int, service_ms: float, **workload: Any) -> Json:
    """Clients calling one service of ``workers`` handlers, each request ``service_ms`` long:
    it saturates at ``workers / service_ms`` requests a millisecond."""
    doc = design(
        [
            clients(timeoutMs=2000),
            service(
                "api",
                "API",
                replicas=1,
                workersPerReplica=workers,
                queueCapacity=50,
                serviceTimeMs={"kind": "constant", "value": service_ms},
            ),
        ],
        [link("clients", "api")],
        {"arrivalProcess": "poisson", **workload},
    )
    doc["objectives"] = {"latencyP95Ms": 60, "availability": 0.99}
    return doc


def slower(doc: Json) -> Json:
    """The design with a scenario whose service takes twice as long."""
    doc["scenarios"] = [
        {
            "id": "slower",
            "name": "Slower service",
            "overrides": [
                {
                    "target": {"kind": "node", "id": "api"},
                    "path": "serviceTimeMs",
                    "value": {"kind": "constant", "value": 20},
                }
            ],
        }
    ]
    return doc


# about 400 req/s: a knee inside the range found from 200 req/s
SATURATING = api(4, 10, rps=200)
# twice the handlers: the baseline a change halves
ROOMY = api(8, 10, rps=200)
# a 90 ms request misses a p95 of 60 ms at any load
TOO_SLOW = api(4, 90, rps=20)
# an outage of 3 s in its 6 s run, which a sweep's steps leave out
OUTAGE = api(
    4,
    10,
    rps=200,
    faults=[{"id": "down", "kind": "outage", "target": "api", "startS": 2, "durationS": 3}],
)


def limits(max_requests: int) -> Json:
    return {"maxRequests": max_requests}


CASES: list[Json] = [
    {"name": "aKneeInTheRangeFoundFromTheLoad", "fn": "breaking_point", "document": SATURATING},
    {
        "name": "aRangeAndStepsGivenWithinTheObjectives",
        "fn": "breaking_point",
        "document": SATURATING,
        "fromRps": 50,
        "toRps": 250,
        "steps": 5,
        "durationS": 10,
        "warmupS": 2,
    },
    {
        "name": "aScenarioSwept",
        "fn": "breaking_point",
        "document": slower(copy.deepcopy(SATURATING)),
        "scenarioId": "slower",
    },
    {
        "name": "stepsCappedByTheRequestLimit",
        "fn": "breaking_point",
        "document": SATURATING,
        "limits": limits(6000),
    },
    {
        # 6,050 requests in 20 s: a cap of 302.5 req/s, which the top step rounds past
        "name": "anAskedRangeCappedByTheRequestLimit",
        "fn": "breaking_point",
        "document": SATURATING,
        "fromRps": 100,
        "toRps": 900,
        "steps": 6,
        "durationS": 20,
        "warmupS": 5,
        "limits": limits(6050),
    },
    {
        # no requests at all: a cap of 0 req/s, below the lowest load a step runs, takes both
        # ends of the range to one step
        "name": "aCapBelowTheLowestLoad",
        "fn": "breaking_point",
        "document": SATURATING,
        "limits": limits(0),
    },
    {
        # a step runs at least 2 s, its warm-up at most a second short of that
        "name": "stepsTooShortForTheirWarmUp",
        "fn": "breaking_point",
        "document": SATURATING,
        "fromRps": 100,
        "toRps": 300,
        "steps": 3,
        "durationS": 1,
        "warmupS": 5,
    },
    {"name": "aDesignWithAnOutageSweptWithoutIt", "fn": "breaking_point", "document": OUTAGE},
    {
        "name": "aDesignMissingItsObjectivesAtTheLightestLoad",
        "fn": "breaking_point",
        "document": TOO_SLOW,
    },
    {
        "name": "aDesignAndItsBaselineOnOneGrid",
        "fn": "knee_sweeps",
        "documents": [SATURATING, ROOMY],
    },
    {
        "name": "aBaselineMissingItsObjectivesAtOnce",
        "fn": "knee_sweeps",
        "documents": [SATURATING, TOO_SLOW],
    },
    {
        "name": "aScenarioMeasuredWithItsKnee",
        "fn": "measure",
        "document": slower(copy.deepcopy(SATURATING)),
        "scenarioId": "slower",
        "knee": True,
    },
    {
        "name": "aCheckAgainstABaselineWithKnees",
        "fn": "run_check",
        "document": SATURATING,
        "baseline": ROOMY,
        "knee": True,
        "failOn": ["objectives", "regression"],
    },
    {
        # a change adds a scenario its baseline does not have: only the change is swept
        "name": "aCheckWhoseBaselineLacksTheScenario",
        "fn": "run_check",
        "document": slower(copy.deepcopy(SATURATING)),
        "scenarioId": "slower",
        "baseline": ROOMY,
        "knee": True,
    },
    {
        "name": "aCheckWhoseBaselineCannotBeSimulated",
        "fn": "run_check",
        "document": SATURATING,
        "baseline": api(4, 10, rps=200, durationS=3600),
        "limits": limits(100_000),
    },
]


def run(case: Json) -> Any:
    registry = default_registry()
    bounds = (
        SimulationLimits(max_requests=case["limits"]["maxRequests"]) if "limits" in case else None
    )
    scenario_id = case.get("scenarioId")
    match case["fn"]:
        case "breaking_point":
            return breaking_point(
                copy.deepcopy(case["document"]),
                registry,
                scenario_id=scenario_id,
                from_rps=case.get("fromRps"),
                to_rps=case.get("toRps"),
                steps=case.get("steps", 8),
                duration_s=case.get("durationS", 20.0),
                warmup_s=case.get("warmupS", 5.0),
                limits=bounds,
            )
        case "knee_sweeps":
            return knee_sweeps(copy.deepcopy(case["documents"]), registry, scenario_id, bounds)
        case "measure":
            return measure(
                copy.deepcopy(case["document"]), registry, scenario_id, case["knee"], bounds
            )
        case "run_check":
            return run_check(
                copy.deepcopy(case["document"]),
                registry,
                scenario_id=scenario_id,
                baseline=copy.deepcopy(case["baseline"]),
                knee=case.get("knee", False),
                fail_on=tuple(case.get("failOn", ["objectives"])),
                limits=bounds,
            )
    raise ValueError(case["fn"])


def export() -> bytes:
    cases = [{**case, "output": run(case)} for case in CASES]
    text = json.dumps(
        {"generator": GENERATOR, "cases": cases}, sort_keys=True, separators=(",", ":")
    )
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    data = export()
    if args.check:
        if not OUT.exists() or gzip.decompress(OUT.read_bytes()) != gzip.decompress(data):
            print(f"{OUT.name} differs from a fresh export: run scripts/export_sweep_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's sweeps and checks")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
