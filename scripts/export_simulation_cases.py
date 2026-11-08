"""Write whole simulations of small designs for the Java engine, or check the committed file.

The Java simulator keeps the frozen engine's order of events and draws its random numbers, so a
Java run of a design follows the Python run of it. This script simulates small designs, each
reaching one feature of the runtime (clients with timeouts and retries, client groups, traffic
patterns, faults, delays of zero, no traffic at all, and each component as later slices add
them), with the frozen engine (0.16.0) and records each design and its report.
``SimulationCasesTests`` simulates each design in Java and compares the reports: counts and events
processed exactly, numbers to a relative 1e-9.

The report's machine-dependent provenance (``wallSeconds``, ``python``, ``simpy``) is blanked.

Usage:
    uv run python scripts/export_simulation_cases.py           # write the cases
    uv run python scripts/export_simulation_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import copy
import gzip
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine import run_simulation

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/simulator/cases.json.gz"
)
GENERATOR = "scripts/export_simulation_cases.py (archsim_engine 0.16.0)"
Json = dict[str, Any]


def lognormal(median: float, p95: float) -> Json:
    return {"kind": "lognormal", "median": median, "p95": p95}


def design(nodes: list[Json], edges: list[Json], workload: Json) -> Json:
    """A design of the current schema, with no layout, auto-sizing off."""
    return {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": "case"},
        "architecture": {"nodes": nodes, "edges": edges, "autoSize": False},
        "workload": {"seed": 7, "warmupS": 1, "durationS": 6, "readRatio": 0.8, **workload},
    }


def clients(node_id: str = "clients", **config: Any) -> Json:
    return {"id": node_id, "type": "traffic_source", "label": "Clients", "config": config}


def external(node_id: str = "api", **config: Any) -> Json:
    return {"id": node_id, "type": "external_api", "label": "API", "config": config}


def link(source: str, target: str, **config: Any) -> Json:
    return {
        "id": f"{source}-{target}",
        "source": source,
        "sourcePort": "out",
        "target": target,
        "targetPort": "in",
        "config": {"latencyMs": {"kind": "constant", "value": 1}, **config},
    }


CASES: dict[str, Json] = {
    "constantRateToAnApi": design(
        [clients(), external(latencyMs={"kind": "constant", "value": 20})],
        [link("clients", "api")],
        {"rps": 100, "arrivalProcess": "deterministic"},
    ),
    "poissonToAnApiThatFails": design(
        [clients(), external(latencyMs=lognormal(30, 120), errorRate=0.05)],
        [link("clients", "api", latencyMs={"kind": "exponential", "mean": 2})],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "twoClientGroups": design(
        [
            clients("web", trafficShare=3, timeoutMs=500),
            clients("mobile", trafficShare=1, timeoutMs=200, maxAttempts=2, retryBackoffMs=20),
            external(latencyMs=lognormal(20, 60)),
        ],
        [
            link("web", "api"),
            link("mobile", "api", latencyMs={"kind": "uniform", "min": 5, "max": 15}),
        ],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "aRampWithAnOutageAndASlowdown": design(
        [clients(timeoutMs=300), external(latencyMs={"kind": "exponential", "mean": 25})],
        [link("clients", "api")],
        {
            "rps": 150,
            "arrivalProcess": "poisson",
            "pattern": {"kind": "ramp", "startRps": 50, "endRps": 250},
            "faults": [
                {"id": "down", "kind": "outage", "target": "api", "startS": 2, "durationS": 1},
                {
                    "id": "slow",
                    "kind": "slowdown",
                    "target": "api",
                    "startS": 4,
                    "durationS": 1,
                    "factor": 3,
                },
                {
                    "id": "lag",
                    "kind": "latency",
                    "target": "clients-api",
                    "startS": 3.5,
                    "durationS": 1,
                    "extraMs": 50,
                },
            ],
        },
    ),
    "aBurst": design(
        [clients(), external(latencyMs=lognormal(10, 30))],
        [link("clients", "api")],
        {
            "rps": 100,
            "arrivalProcess": "poisson",
            "pattern": {"kind": "burst", "startS": 2, "durationS": 1, "burstRps": 600},
        },
    ),
    "aTracedPattern": design(
        [clients(), external(latencyMs={"kind": "constant", "value": 15})],
        [link("clients", "api")],
        {
            "rps": 100,
            "arrivalProcess": "deterministic",
            "pattern": {"kind": "trace", "points": [[0, 50], [2, 300], [4, 100], [6, 100]]},
        },
    ),
    "aSetDrainAndAQuietStart": design(
        [clients(timeoutMs=400), external(latencyMs=lognormal(15, 45), errorRate=0.01)],
        [link("clients", "api")],
        {
            "rps": 100,
            "arrivalProcess": "deterministic",
            "readRatio": 0.5,
            "drainS": 2,
            "pattern": {"kind": "trace", "points": [[0, 0], [1, 0], [1.5, 200], [6, 200]]},
        },
    ),
    "retriesWithoutBackoff": design(
        [
            clients(timeoutMs=30, maxAttempts=3, retryBackoffMs=0),
            external(latencyMs=lognormal(25, 80)),
        ],
        [link("clients", "api")],
        {"rps": 120, "arrivalProcess": "poisson"},
    ),
    "zeroDelays": design(
        [clients(timeoutMs=50), external(latencyMs={"kind": "constant", "value": 0})],
        [link("clients", "api", latencyMs={"kind": "constant", "value": 0})],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "noTraffic": design(
        [clients(), external()],
        [link("clients", "api")],
        {"rps": 0},
    ),
    "clientsThatRetryAndTimeOut": design(
        [
            clients(timeoutMs=60, maxAttempts=3, retryBackoffMs=10),
            external(latencyMs=lognormal(40, 200), rateLimitRps=150, concurrencyLimit=8),
        ],
        [link("clients", "api")],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
}


def cases() -> list[Json]:
    out = []
    for name, doc in CASES.items():
        report = run_simulation(copy.deepcopy(doc))
        report["provenance"]["wallSeconds"] = 0.0
        report["provenance"]["python"] = "normalized"
        report["provenance"]["simpy"] = "normalized"
        out.append({"name": name, "design": doc, "report": report})
    return out


def export() -> bytes:
    text = json.dumps(
        {"generator": GENERATOR, "cases": cases()}, sort_keys=True, separators=(",", ":")
    )
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    data = export()
    if args.check:
        if not OUT.exists() or gzip.decompress(OUT.read_bytes()) != gzip.decompress(data):
            print(f"{OUT.name} differs from a fresh export: run scripts/export_simulation_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's simulations")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
