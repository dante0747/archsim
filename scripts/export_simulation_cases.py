"""Write whole simulations of small designs for the Java engine, or check the committed file.

The Java simulator keeps the frozen engine's order of events and draws its random numbers, so a
Java run of a design follows the Python run of it. This script simulates small designs, each
reaching one feature of the runtime (clients with timeouts and retries, client groups, traffic
patterns, faults, delays of zero, no traffic at all, a calibrated scenario of a priced API,
services, and each component as later slices add them), with the frozen engine (0.16.0) and records each design,
the scenario simulated and the report.
``SimulationCasesTests`` simulates each design in Java and compares the whole reports: digests,
counts and events processed exactly, numbers to a relative 1e-9.

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


def service(node_id: str, label: str, **config: Any) -> Json:
    return {"id": node_id, "type": "service", "label": label, "config": config}


def balancer(node_id: str = "lb", **config: Any) -> Json:
    return {"id": node_id, "type": "load_balancer", "label": "Balancer", "config": config}


def link(source: str, target: str, **config: Any) -> Json:
    return {
        "id": f"{source}-{target}",
        "source": source,
        "sourcePort": "out",
        "target": target,
        "targetPort": "in",
        "config": {"latencyMs": {"kind": "constant", "value": 1}, **config},
    }


def calibrated(doc: Json) -> Json:
    """The design auto-sized, with a measured input, a calibration and a scenario that
    overrides the workload and the API: the report's assumptions, cost and provenance
    reach their other branches."""
    doc["architecture"]["autoSize"] = True
    doc["provenance"] = {
        "nodes/api/latencyMs": {
            "kind": "measured",
            "source": "Prometheus",
            "fitError": 0.08,
            "fittedAt": "2026-10-01T12:00:00Z",
            "value": lognormal(40, 150),
        }
    }
    doc["calibration"] = {
        "at": "2026-10-01T12:00:00Z",
        "source": "Prometheus",
        "inputs": 1,
        "notes": ["The API's latency is fitted from a week of calls."],
    }
    doc["scenarios"] = [
        {
            "id": "peak",
            "name": "Peak hour",
            "overrides": [
                {"target": {"kind": "workload"}, "path": "rps", "value": 220},
                {"target": {"kind": "node", "id": "api"}, "path": "errorRate", "value": 0.02},
            ],
        }
    ]
    return doc


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
    "aBlockingServiceWithRetryingLinks": design(
        [
            clients(timeoutMs=400),
            service(
                "svc",
                "Orders",
                replicas=2,
                workersPerReplica=3,
                queueCapacity=10,
                queueTimeoutMs=80,
                serviceTimeMs=lognormal(8, 25),
            ),
            external(latencyMs=lognormal(15, 60), errorRate=0.05),
        ],
        [
            link("clients", "svc"),
            link("svc", "api", timeoutMs=60, maxAttempts=3, retryBackoffMs=5),
        ],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "anEventLoopServiceOnContendedCores": design(
        [
            clients(timeoutMs=800),
            service(
                "svc",
                "Gateway",
                technology="nodejs",
                replicas=1,
                processesPerReplica=2,
                cpuCores=1,
                cpuShare=0.7,
                queueCapacity=100,
                queueTimeoutMs=None,
                gcEveryRequests=100,
                serviceTimeMs=lognormal(3, 9),
                serviceTimeByLoad=[{"rps": 50, "factor": 1}, {"rps": 400, "factor": 1.6}],
            ),
            external(latencyMs={"kind": "exponential", "mean": 12}),
        ],
        [link("clients", "svc"), link("svc", "api")],
        {"rps": 200, "arrivalProcess": "poisson"},
    ),
    "aJavaServiceOutOfMemoryWithALoadCurve": design(
        [
            clients(timeoutMs=1500),
            service(
                "svc",
                "Catalog",
                technology="java",
                replicas=2,
                workersPerReplica=40,
                memoryGib=0.26,
                memoryPerRpsMb=0.5,
                gcEveryRequests=200,
                warmupFactor=3,
                warmupS=30,
                serviceTimeMs=lognormal(30, 90),
                serviceTimeByLoad=[{"rps": 20, "factor": 1}, {"rps": 200, "factor": 2}],
            ),
        ],
        [link("clients", "svc")],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "aGatewayCallingInOrder": design(
        [
            clients(timeoutMs=600),
            service(
                "gw",
                "Gateway",
                blockingDownstream=False,
                cpuCores=0.5,
                serviceTimeMs={"kind": "constant", "value": 2},
            ),
            service("users", "Users", replicas=1, workersPerReplica=8),
            external("orders", errorRate=0.2, latencyMs=lognormal(10, 40)),
            service("audit", "Audit", replicas=1, serviceTimeMs={"kind": "constant", "value": 4}),
        ],
        [
            link("clients", "gw"),
            link("gw", "users", order=1, requestTypes=["read"]),
            link("gw", "orders", order=2, continueOnFailure=True, timeoutMs=50),
            link("gw", "audit", requestTypes=["write"]),
        ],
        {"rps": 120, "arrivalProcess": "poisson", "readRatio": 0.6},
    ),
    "aServiceNearItsCpuCeiling": design(
        [
            clients(timeoutMs=2000),
            service(
                "svc",
                "Pricing",
                replicas=1,
                workersPerReplica=4,
                queueCapacity=50,
                cpuCores=2,
                cpuShare=0.8,
                serviceTimeMs=lognormal(10, 30),
            ),
        ],
        [link("clients", "svc")],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "aServiceWithCoresToSpare": design(
        [
            clients(),
            service(
                "svc",
                "Search",
                replicas=3,
                workersPerReplica=4,
                cpuCores=8,
                cpuShare=0.5,
                serviceTimeMs=lognormal(20, 50),
            ),
        ],
        [link("clients", "svc")],
        {"rps": 150, "arrivalProcess": "deterministic"},
    ),
    "aBalancerRetryingAnotherReplica": design(
        [
            clients(timeoutMs=1000),
            balancer(technology="nginx", policy="round_robin"),
            service(
                "svc",
                "Orders",
                replicas=3,
                workersPerReplica=2,
                queueCapacity=5,
                queueTimeoutMs=100,
                serviceTimeMs=lognormal(15, 60),
            ),
        ],
        [
            link("clients", "lb"),
            link("lb", "svc", timeoutMs=200, maxAttempts=2, retryBackoffMs=5),
        ],
        {"rps": 300, "arrivalProcess": "poisson"},
    ),
    "anEnvoyWithACircuitBreakerAndAnUpstreamTimeout": design(
        [
            clients(timeoutMs=1000),
            balancer(
                technology="envoy",
                policy="least_connections",
                circuitBreakerMaxRequests=6,
                upstreamTimeoutMs=80,
                overheadMs={"kind": "exponential", "mean": 0.5},
            ),
            service(
                "svc", "Search", replicas=2, workersPerReplica=3, serviceTimeMs=lognormal(20, 90)
            ),
            external("api", latencyMs=lognormal(30, 150)),
        ],
        [
            link("clients", "lb"),
            link("lb", "svc", maxAttempts=2),
            link("lb", "api", requestTypes=["read"], timeoutMs=120),
        ],
        {"rps": 250, "arrivalProcess": "poisson"},
    ),
    "aRandomBalancerWithARateLimit": design(
        [
            clients(timeoutMs=800),
            balancer(
                technology="api_gateway",
                policy="random",
                rateLimitRps=150,
                rateLimitBurst=20,
                maxConnections=8,
            ),
            service(
                "svc",
                "Profile",
                replicas=2,
                workersPerReplica=3,
                queueCapacity=2,
                serviceTimeMs=lognormal(25, 70),
            ),
        ],
        [link("clients", "lb"), link("lb", "svc", maxAttempts=2)],
        {"rps": 250, "arrivalProcess": "poisson", "readRatio": 0.85},
    ),
    "aDefaultBalancerOverTwoServices": design(
        [
            clients(timeoutMs=1500),
            balancer(),
            service("a", "Orders", replicas=2, workersPerReplica=2, queueCapacity=1),
            service("b", "Orders (canary)", replicas=1, workersPerReplica=2, queueCapacity=1),
        ],
        [
            link("clients", "lb"),
            link("lb", "a", maxAttempts=2, retryBackoffMs=10),
            link("lb", "b", maxAttempts=2, retryBackoffMs=10),
        ],
        {"rps": 400, "arrivalProcess": "poisson"},
    ),
    "aBalancerRateLimitedBelowOneRequestASecond": design(
        [clients(), balancer(rateLimitRps=0.5), service("svc", "Reports", replicas=1)],
        [link("clients", "lb"), link("lb", "svc")],
        {"rps": 20, "arrivalProcess": "deterministic"},
    ),
    "aCalibratedScenarioOfAPricedApi": calibrated(
        design(
            [
                clients(timeoutMs=250),
                external(
                    technology="payments",
                    latencyMs=lognormal(40, 150),
                    concurrencyLimit=20,
                    costPerMillionRequests=500,
                ),
            ],
            [link("clients", "api")],
            {"rps": 150, "arrivalProcess": "poisson"},
        )
    ),
}

# The scenario each case simulates; a case left out simulates the design itself.
SCENARIOS = {"aCalibratedScenarioOfAPricedApi": "peak"}


def cases() -> list[Json]:
    out = []
    for name, doc in CASES.items():
        scenario_id = SCENARIOS.get(name)
        report = run_simulation(copy.deepcopy(doc), scenario_id=scenario_id)
        report["provenance"]["wallSeconds"] = 0.0
        report["provenance"]["python"] = "normalized"
        report["provenance"]["simpy"] = "normalized"
        out.append({"name": name, "design": doc, "scenarioId": scenario_id, "report": report})
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
