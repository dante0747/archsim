"""Write the instant estimate's extra test cases for the Java engine, or check the committed file.

The golden files (parity/golden/) do not reach every branch of ``estimate.estimate_design``: a
design on a busy host or on hyperthreads, load curves, serverless functions, limited and metered
databases, read-through caches, rate limits, overloaded parts and queues, and more. This script
asks the frozen engine (0.16.0) for its estimate of a design for each such case and writes the
resolved designs with the estimates to archsim-engine's test resources, where
``InstantEstimatorTests`` checks the Java estimate against them as the golden files are checked.

Usage:
    uv run python scripts/export_estimate_cases.py           # write the cases
    uv run python scripts/export_estimate_cases.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library (glibc rather than the Windows CRT) may differ in the last bit of ``exp``,
``log`` or ``erfc``. It is a manual step for that reason, not a CI one; the Java tests compare
with tolerances.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine.components import create_default_registry
from archsim_engine.estimate import estimate_design
from archsim_engine.scenarios import load_document, resolve_scenario_sized, simulation_input

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/estimate/cases.json"
)
REGISTRY = create_default_registry(include_plugins=False)
CONST10 = {"kind": "constant", "value": 10}
Json = dict[str, Any]


def design(
    nodes: list[Json],
    edges: list[Json],
    workload: Json | None = None,
    host: Json | None = None,
) -> Json:
    architecture: Json = {"nodes": nodes, "edges": edges}
    if host:
        architecture["host"] = host
    return {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": "case"},
        "architecture": architecture,
        "workload": workload or {"rps": 100, "readRatio": 0.8, "durationS": 60},
        "pricing": {"provider": "generic"},
    }


def link(source: str, target: str, port: str | None = None, **config: Any) -> Json:
    edge: Json = {"id": f"{source}-{target}", "source": source, "target": target, "config": config}
    if port:
        edge["sourcePort"] = port
    return edge


def clients() -> Json:
    return {"id": "clients", "type": "traffic_source"}


def service(node_id: str = "api", **config: Any) -> Json:
    return {"id": node_id, "type": "service", "config": {"serviceTimeMs": CONST10, **config}}


def workload(rps: float, read_ratio: float = 0.8, **more: Any) -> Json:
    return {"rps": rps, "readRatio": read_ratio, "durationS": 60, **more}


def ttl_cache(node_id: str = "cache", **config: Any) -> Json:
    return {
        "id": node_id,
        "type": "cache",
        "config": {"hitModel": "ttl_keyspace", "ttlSeconds": 30, **config},
    }


def designs() -> list[tuple[str, Json]]:
    """Each case's name (its Java test's name) and design."""
    database = {"id": "db", "type": "database"}
    return [
        (
            "onAHostMachine_slowsEveryPartsCpuWork",
            design(
                [
                    clients(),
                    service(cpuCores=2, cpuShare=0.7),
                    {"id": "db", "type": "database", "config": {"cpuCores": 2}},
                    {"id": "cache", "type": "cache", "config": {"cpuCores": 1}},
                ],
                [link("clients", "api"), link("api", "cache"), link("api", "db")],
                workload(300),
                host={"physicalCores": 2, "threadsPerCore": 2, "siblingSpeed": 0.6},
            ),
        ),
        (
            "onABusyHostMachine_slowsTheOwnTimesItsRequestsWait",
            design(
                [
                    clients(),
                    service(cpuCores=2, cpuShare=0.7),
                    {"id": "db", "type": "database", "config": {"cpuCores": 2}},
                ],
                [link("clients", "api"), link("api", "db")],
                workload(30),
                host={
                    "physicalCores": 1,
                    "threadsPerCore": 2,
                    "siblingSpeed": 0.6,
                    "otherBusyThreads": 1.2,
                },
            ),
        ),
        (
            "withHyperthreads_slowsThePartOnItsOwnThreads",
            design(
                [
                    clients(),
                    service(cpuCores=4, siblingSpeed=0.6, replicas=1, workersPerReplica=64),
                ],
                [link("clients", "api")],
                workload(300),
            ),
        ),
        (
            "aServiceTimeByLoad_scalesTheOwnTime",
            design(
                [
                    clients(),
                    service(
                        serviceTimeByLoad=[{"rps": 10, "factor": 1.0}, {"rps": 100, "factor": 2.0}]
                    ),
                    service(
                        "fn",
                        platform="serverless",
                        concurrencyLimit=50,
                        cpuCores=1,
                        serviceTimeByLoad=[{"rps": 10, "factor": 1.0}, {"rps": 100, "factor": 1.5}],
                    ),
                ],
                [link("clients", "api"), link("api", "fn")],
            ),
        ),
        (
            "databasesWithLimits_shareOfTheirLimitsSetsTheLoad",
            design(
                [
                    clients(),
                    service(),
                    {
                        "id": "pg",
                        "type": "database",
                        "config": {
                            "technology": "postgresql",
                            "throughputLimitRps": 150,
                            "readReplicas": 1,
                            "cpuCores": 2,
                        },
                    },
                    {
                        "id": "ddb",
                        "type": "database",
                        "config": {
                            "technology": "dynamodb",
                            "capacityMode": "provisioned",
                            "readCapacityUnits": 50,
                            "writeCapacityUnits": 30,
                            "itemSizeKb": 6,
                        },
                    },
                ],
                [link("clients", "api"), link("api", "pg"), link("api", "ddb")],
            ),
        ),
        (
            "aReadThroughCache_writesTheValueBackAfterAMiss",
            design(
                [
                    clients(),
                    service(),
                    {
                        "id": "cache",
                        "type": "cache",
                        "config": {"populateOnMiss": True, "hitRatio": 0.6, "cpuCores": 1},
                    },
                    database,
                ],
                [link("clients", "api"), link("api", "cache"), link("cache", "db", port="miss")],
            ),
        ),
        (
            "aRateLimitNearlyUsed_isNamedInTheSummary",
            design(
                [
                    clients(),
                    service(workersPerReplica=64),
                    {
                        "id": "pay",
                        "type": "external_api",
                        "config": {"rateLimitRps": 120, "latencyMs": CONST10},
                    },
                ],
                [link("clients", "api"), link("api", "pay")],
            ),
        ),
        (
            "anOverloadedService_saysWhereTheDesignSaturates",
            design(
                [clients(), service(replicas=1, workersPerReplica=2)],
                [link("clients", "api")],
                workload(400),
            ),
        ),
        (
            "aQueueWithAnOverloadedConsumer_doesNotSlowItsProducers",
            design(
                [
                    clients(),
                    service(),
                    {
                        "id": "q",
                        "type": "message_queue",
                        "config": {"technology": "kafka", "consumers": 1, "partitions": 1},
                    },
                    service(
                        "worker",
                        replicas=1,
                        workersPerReplica=1,
                        serviceTimeMs={"kind": "constant", "value": 50},
                    ),
                ],
                [link("clients", "api"), link("api", "q"), link("q", "worker")],
                workload(200, 0.2),
            ),
        ),
        (
            "aTtlCacheWithLittleMemory_saysWhichKeysItHolds",
            design(
                [clients(), service(), ttl_cache(memoryGib=0.001), database],
                [link("clients", "api"), link("api", "cache"), link("cache", "db", port="miss")],
            ),
        ),
        (
            "aBurstPattern_isEstimatedAtItsPeak",
            design(
                [clients(), service()],
                [link("clients", "api")],
                workload(
                    100, pattern={"kind": "burst", "burstRps": 250, "startS": 10, "durationS": 5}
                ),
            ),
        ),
        (
            "noPartWithAKnownCapacity_hasNoEstimate",
            design([clients(), {"id": "k8s", "type": "kubernetes_cluster"}], []),
        ),
        (
            "partsOnACycle_areLeftOut",
            design(
                [clients(), service(), service("b"), service("c")],
                [link("clients", "api"), link("b", "c"), link("c", "b")],
            ),
        ),
        (
            "aCallIntoACycle_leavesTheLatencyUnestimated",
            design(
                [clients(), service(), service("b"), service("c"), database],
                [
                    link("clients", "api"),
                    link("api", "b"),
                    link("b", "c"),
                    link("c", "b"),
                    link("api", "db"),
                ],
            ),
        ),
        (
            "partsWhoseConfigIsNotValid_haveNoCapacityModel",
            design(
                [clients(), service(), service("bad", replicas=-1), ttl_cache(shards=0), database],
                [
                    link("clients", "api"),
                    link("api", "bad"),
                    link("api", "cache"),
                    link("cache", "db", port="miss"),
                ],
            ),
        ),
        (
            "everyKindOfDistribution_givesItsMoments",
            design(
                [
                    clients(),
                    service(
                        "slow",
                        serviceTimeMs={"kind": "exponential", "mean": 900},
                        workersPerReplica=512,
                    ),
                    service("even", serviceTimeMs={"kind": "uniform", "min": 1, "max": 3}),
                    service(
                        "measured",
                        serviceTimeMs={
                            "kind": "empirical",
                            "quantiles": [[0, 1], [0.5, 2], [0.95, 6], [1, 9]],
                        },
                    ),
                ],
                [link("clients", "slow"), link("clients", "even"), link("clients", "measured")],
            ),
        ),
        (
            "aFastPath_readsInTenthsOfAMillisecond",
            design(
                [clients(), service(serviceTimeMs={"kind": "constant", "value": 0.5})],
                [link("clients", "api", latencyMs={"kind": "constant", "value": 0.5})],
            ),
        ),
    ]


def export() -> str:
    """The cases file's text: each case's resolved design and the engine's estimate of it."""
    cases = []
    for name, raw in designs():
        resolved, _, _ = resolve_scenario_sized(load_document(raw), None, REGISTRY)
        estimate = estimate_design(resolved, REGISTRY, simulation_input(resolved))
        cases.append(
            {
                "name": name,
                "resolved": resolved.model_dump(mode="json", by_alias=True),
                "estimate": estimate,
            }
        )
    document = {
        "generator": "scripts/export_estimate_cases.py (archsim_engine 0.16.0)",
        "cases": cases,
    }
    return json.dumps(document, indent=1, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_estimate_cases.py")
            return 1
        print(f"{OUT.name} matches engine 0.16.0")
        return 0
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
