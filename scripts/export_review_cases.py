"""Write the design review's extra test cases for the Java engine, or check the committed file.

The golden files (parity/golden/) do not reach every branch of ``review.review_design``: the
templates never retry without backoff, have an autoscaling range that cannot grow or scales late,
a queue without a downstream link, a third party whose errors reach users, a call to a third party
without a timeout, a data store without a connection timeout, a CPU check on shared cores, a walk
past its step budget, or titles that sort differently folded; nor do they read settings leniently
(a part of an unknown type, settings of the wrong kind, a link to a part the design does not have,
two parts with one id) or divide by zero where the engine raises. This script asks the frozen
engine (0.16.0) for the review of a design for each such case, as ``analyze_document`` works it
out, and writes the designs as simulated with the findings (or the error) to archsim-engine's test
resources, where ``DesignReviewTests`` checks the Java against them as the golden files are
checked.

Usage:
    uv run python scripts/export_review_cases.py           # write the cases
    uv run python scripts/export_review_cases.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library may differ in the last bit of ``exp`` or ``log``. It is a manual step for
that reason, not a CI one.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine.components import create_default_registry
from archsim_engine.review import review_design
from archsim_engine.scenarios import load_document, resolve_scenario_sized, simulation_input

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/review/cases.json"
)
REGISTRY = create_default_registry(include_plugins=False)
CONST10 = {"kind": "constant", "value": 10}
Json = dict[str, Any]


def design(nodes: list[Json], edges: list[Json], **more: Any) -> Json:
    workload = {"rps": 100, "readRatio": 0.8, "durationS": 60, **more.pop("workload", {})}
    return {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": "case"},
        "architecture": {"nodes": nodes, "edges": edges, **more},
        "workload": workload,
    }


def link(source: str, target: str, **config: Any) -> Json:
    return {"id": f"{source}-{target}", "source": source, "target": target, "config": config}


def node(node_id: str, node_type: str, label: str | None = None, **config: Any) -> Json:
    out: Json = {"id": node_id, "type": node_type, "config": config}
    if label is not None:
        out["label"] = label
    return out


def clients(**config: Any) -> Json:
    return node("clients", "traffic_source", **config)


def service(node_id: str = "api", label: str | None = None, **config: Any) -> Json:
    return node(node_id, "service", label, **{"serviceTimeMs": CONST10, **config})


def database(node_id: str = "db", **config: Any) -> Json:
    return node(node_id, "database", **config)


def timed(source: str, target: str, **config: Any) -> Json:
    return link(source, target, **{"timeoutMs": 1000, **config})


def layered(layers: int, width: int) -> Json:
    """Every part of a layer calls every part of the next: more paths than the walk takes."""
    nodes = [clients(timeoutMs=300_000)]
    edges = []
    previous = ["clients"]
    for layer in range(layers):
        ids = [f"s{layer}x{i}" for i in range(width)]
        nodes += [service(i, replicas=2) for i in ids]
        # timeouts that shrink downstream; retries only on the paths the walk reaches
        # last, past its budget
        timeout = 100 * 4 ** (layers - layer)
        edges += [
            link(a, b, timeoutMs=timeout, maxAttempts=2 if b.endswith(f"x{width - 1}") else 1)
            for a in previous
            for b in ids
        ]
        previous = ids
    return design(nodes, edges)


def designs() -> list[tuple[str, Json]]:
    """Each case's name (its Java test's name) and design."""
    return [
        (
            "linksAndClientsThatRetryAtOnce_retryWithoutBackoff",
            design(
                [clients(maxAttempts=2, retryBackoffMs=0), service(replicas=2), database()],
                [link("clients", "api"), timed("api", "db", maxAttempts=3, retryBackoffMs=0)],
            ),
        ),
        (
            "anAutoscalingRangeThatCannotGrow_cannotScaleOut",
            design(
                [clients(), service(autoscaling=True, minReplicas=3, maxReplicas=3)],
                [link("clients", "api")],
            ),
        ),
        (
            "anAutoscalingTargetAbove85Percent_scalesLate",
            design(
                [
                    clients(),
                    service(
                        autoscaling=True,
                        minReplicas=2,
                        maxReplicas=6,
                        targetUtilization=0.9,
                        startupS=30,
                    ),
                    service(
                        "worker",
                        autoscaling=True,
                        minReplicas=2,
                        maxReplicas=6,
                        targetUtilization=0.95,
                        startupS="slow",
                    ),
                ],
                [link("clients", "api"), timed("api", "worker")],
            ),
        ),
        (
            "aQueueWithoutADownstreamLink_isASinkWithIdleConsumers",
            design(
                [
                    clients(),
                    service(replicas=2),
                    node("queue", "message_queue", maxDepth=None, partitions=2, consumers=5),
                ],
                [link("clients", "api"), link("api", "queue")],
            ),
        ),
        (
            "aThirdPartyThatFailsOften_isCalledWithoutATimeoutOrARetry",
            design(
                [clients(), service(replicas=2), node("payments", "external_api", errorRate=0.05)],
                [link("clients", "api"), link("api", "payments")],
            ),
        ),
        (
            "aCacheLookupThatFailsTheRead_failsWithAFallback",
            design(
                [clients(), service(replicas=2), node("cache", "cache"), database()],
                [
                    link("clients", "api"),
                    timed("api", "cache", requestTypes=["read"]),
                    timed("api", "db", requestTypes=["read"], readsOnMiss=True),
                ],
            ),
        ),
        (
            "aCacheWithoutAFallback_leavesReadsNoOtherPath",
            design(
                [clients(), service(replicas=2), node("cache", "cache")],
                [link("clients", "api"), timed("api", "cache", continueOnFailure=True)],
            ),
        ),
        (
            "aDataStoreWithoutAConnectionTimeout_letsRequestsWaitForever",
            design(
                [
                    clients(),
                    service(replicas=2, workersPerReplica=8),
                    database(maxConnections=2, queueTimeoutMs=None),
                ],
                [link("clients", "api"), timed("api", "db")],
            ),
        ),
        (
            "serverlessFunctions_canOpenMoreConnectionsThanTheStoreAccepts",
            design(
                [clients(), service(platform="serverless", memoryGib=1), database()],
                [link("clients", "api"), timed("api", "db")],
            ),
        ),
        (
            "aDataStoreWhoseHotDataOutgrowsItsMemory_readsFromDisk",
            design(
                [
                    clients(),
                    service(replicas=2),
                    database(memoryGib=2, workingSetGib=10, shards=2, readReplicas=1),
                    database("orders", memoryGib=8, workingSetGib=9.5, readReplicas=1),
                ],
                [link("clients", "api"), timed("api", "db"), timed("api", "orders")],
            ),
        ),
        (
            "partsThatFillTheirCpu_needMoreCpuAtThePeak",
            design(
                [
                    clients(),
                    service(replicas=2, cpuCores=1, serviceTimeMs={"kind": "constant", "value": 9}),
                    database(cpuCores=0.25, readReplicas=1),
                    node(
                        "cache",
                        "cache",
                        cpuCores=0.01,
                        serviceTimeMs={"kind": "constant", "value": 0.1},
                    ),
                ],
                [link("clients", "api"), timed("api", "db"), timed("api", "cache")],
                workload={"rps": 400},
            ),
        ),
        (
            "aServiceOnALoadCurve_fillsItsCpuAtThatRatesCost",
            design(
                [
                    clients(),
                    service(
                        replicas=2,
                        cpuCores=2,
                        autoscaling=True,
                        minReplicas=1,
                        maxReplicas=1,
                        serviceTimeByLoad=[
                            {"rps": 50, "factor": 1.8},
                            {"rps": 1674, "factor": 1.0},
                        ],
                    ),
                ],
                [link("clients", "api")],
                workload={"rps": 300},
            ),
        ),
        (
            "partsOnAHost_areSlowedByItsSharedCores",
            design(
                [
                    clients(),
                    service(replicas=2, cpuCores=4),
                    database(cpuCores=4, readReplicas=1),
                ],
                [link("clients", "api"), timed("api", "db")],
                host={"physicalCores": 2},
                workload={"rps": 600},
            ),
        ),
        (
            "aServiceOnItsOwnHyperthreads_isSlowedByThem",
            design(
                [clients(), service(replicas=1, cpuCores=1, siblingSpeed=0.6)],
                [link("clients", "api")],
                workload={"rps": 160},
            ),
        ),
        ("aLayeredFanOut_isWalkedWithinItsStepBudget", layered(6, 6)),
        (
            "aClientThatRetriesALot_multipliesAttemptsAsWholeNumbers",
            design(
                [clients(maxAttempts=1e20, retryBackoffMs=10), service(replicas=2), database()],
                [link("clients", "api"), timed("api", "db", maxAttempts=3)],
            ),
        ),
        (
            "aClientTimeoutBelowItsCalleesBudget_isATimeoutInversion",
            design(
                [clients(timeoutMs=1000), service(replicas=2), database()],
                [link("clients", "api"), timed("api", "db", timeoutMs=800, maxAttempts=3)],
            ),
        ),
        (
            "titlesThatDifferInCase_areSortedFoldedThenByCodePoint",
            design(
                [
                    clients(),
                    service("zeta", "Zeta", replicas=1),
                    service("alpha", "alpha", replicas=1),
                    service("strasse", "Straße", replicas=1),
                    service("upper", "STRASSE", replicas=1),
                    service("emoji", "\U0001f600 api", replicas=1),
                    service("wide", "\uff01 api", replicas=1),
                ],
                [
                    timed("clients", n)
                    for n in ("zeta", "alpha", "strasse", "upper", "emoji", "wide")
                ],
            ),
        ),
        (
            "partsWithOneLabel_keepTheirOrder",
            design(
                [clients(), service("b", "Same", replicas=1), service("a", "Same", replicas=1)],
                [timed("clients", "b"), timed("clients", "a")],
            ),
        ),
        (
            "managedLoadBalancersAndQueues_areNotSinglePointsOfFailure",
            design(
                [
                    clients(),
                    node("lb", "load_balancer", technology="aws_alb", rateLimitRps=100),
                    service(replicas=2),
                    node("queue", "message_queue", technology="sqs"),
                    node("bus", "message_queue", brokers=3),
                    node("solo", "message_queue"),
                    service("consumer", replicas=2),
                ],
                [
                    link("clients", "lb"),
                    timed("lb", "api"),
                    timed("api", "queue"),
                    timed("api", "bus"),
                    timed("api", "solo"),
                    timed("queue", "consumer"),
                    timed("bus", "consumer"),
                    timed("solo", "consumer"),
                ],
            ),
        ),
        (
            "aShardedStoreWaitingOnEveryReplica_hasAHotShardAndConsistencyAll",
            design(
                [
                    clients(),
                    service(replicas=2),
                    database(
                        technology="cassandra", shards=4, replicationFactor=3, consistency="all"
                    ),
                ],
                [link("clients", "api"), timed("api", "db")],
                workload={"keySkew": 0.99},
            ),
        ),
        (
            "aPartOfAnUnknownType_isReadAsWritten",
            design(
                [
                    clients(),
                    service(replicas=2),
                    node("thing", "mystery", replicas=1, maxDepth=None),
                ],
                [link("clients", "api"), link("api", "thing")],
            ),
        ),
        (
            "settingsOfTheWrongKind_areReadAsMissing",
            design(
                [
                    clients(maxAttempts="3"),
                    node("lb", "load_balancer", technology=5),
                    service(replicas="3", autoscaling=1),
                    node("cache", "cache", hitModel="", hitRatio=0.99),
                    database(readReplicas="2", queueCapacity="many", queueTimeoutMs=None),
                ],
                [
                    link("clients", "lb"),
                    timed("lb", "api"),
                    timed("api", "cache"),
                    timed("api", "db"),
                ],
            ),
        ),
        (
            "aLinkToAPartTheDesignDoesNotHave_isNamedByItsId",
            design(
                [clients(), service(replicas=2)],
                [link("clients", "api"), link("api", "ghost", maxAttempts=2, retryBackoffMs=0)],
            ),
        ),
        (
            "twoPartsWithOneId_countTheLater",
            design(
                [clients(), service(replicas=1), database("api", readReplicas=0)],
                [link("clients", "api")],
            ),
        ),
        (
            "aPartWithoutCpu_cannotBeReviewed",
            design(
                [clients(), service(replicas=2, cpuCores=0)],
                [link("clients", "api")],
            ),
        ),
        (
            "aDataStoreWithoutHotData_cannotBeReviewed",
            design(
                [clients(), service(replicas=2), database(memoryGib=8, workingSetGib=0)],
                [link("clients", "api"), timed("api", "db")],
            ),
        ),
        (
            "aCacheOfItemsWithoutSize_cannotBeReviewed",
            design(
                [
                    clients(),
                    service(replicas=2),
                    node("cache", "cache", hitModel="ttl_keyspace", memoryGib=1, itemSizeKb=0),
                ],
                [link("clients", "api"), timed("api", "cache")],
            ),
        ),
        (
            "aCacheWhoseMemoryHoldsFewKeys_holdsPartOfTheKeyspace",
            design(
                [
                    clients(),
                    service(replicas=2),
                    node("cache", "cache", hitModel="ttl_keyspace", memoryGib=0.1, itemSizeKb=3),
                ],
                [link("clients", "api"), timed("api", "cache")],
                workload={"keyCount": 1_000_000},
            ),
        ),
    ]


def review(raw: Json) -> Json:
    """The case's design as simulated, and its review or the error the engine raises."""
    doc = load_document(raw)
    resolved, _, _ = resolve_scenario_sized(doc, None, REGISTRY)
    snapshot = simulation_input(resolved)
    try:
        return {"doc": snapshot, "findings": review_design(snapshot, REGISTRY)}
    except ArithmeticError as exc:
        return {"doc": snapshot, "error": {"type": type(exc).__name__, "message": str(exc)}}


def export() -> str:
    """The cases file's text: each case's design as simulated, and its findings or error."""
    cases = [{"name": name, **review(raw)} for name, raw in designs()]
    document = {
        "generator": "scripts/export_review_cases.py (archsim_engine 0.16.0)",
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
            print(f"{OUT.name} differs from a fresh export: run scripts/export_review_cases.py")
            return 1
        print(f"{OUT.name} matches engine 0.16.0")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
