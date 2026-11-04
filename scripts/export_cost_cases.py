"""Write the cost estimate's extra test cases for the Java engine, or check the committed file.

The golden files (parity/golden/) do not reach every branch of ``cost.estimate_cost`` and
``sizing.sizing_summary``: managed and per-request parts at a snapshot's rates (a load balancer
and its capacity units, an API gateway, serverless functions, managed queues, DynamoDB), the pods
of services on Kubernetes without a cluster, burstable types' surplus CPU credits, a cluster's
control plane, prices set by a person, a snapshot this machine does not have, settings read
leniently (a part of an unknown type, a technology that is not a name, a DynamoDB config that is
not valid), and more. This script asks the frozen engine (0.16.0) for the cost and the sizing
summary of a design for each such case, as ``analyze_document`` works them out before a run, and
writes the designs with the results to archsim-engine's test resources, where
``CostEstimatorTests`` and ``SizingSummariesTests`` check the Java against them as the golden
files are checked.

Usage:
    uv run python scripts/export_cost_cases.py           # write the cases
    uv run python scripts/export_cost_cases.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library may differ in the last bit of ``exp`` or ``log``. It is a manual step for
that reason, not a CI one; the Java tests compare with tolerances.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine.components import create_default_registry
from archsim_engine.cost import estimate_cost
from archsim_engine.estimate import estimate_design
from archsim_engine.scenarios import load_document, resolve_scenario_sized, simulation_input
from archsim_engine.sizing import base_busy, sizing_summary

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/cost/cases.json"
)
REGISTRY = create_default_registry(include_plugins=False)
CONST10 = {"kind": "constant", "value": 10}
Json = dict[str, Any]


def design(
    nodes: list[Json],
    edges: list[Json],
    pricing: Json | None = None,
    **more: Any,
) -> Json:
    return {
        "schemaVersion": "1.7",
        "kind": "archsim.architecture",
        "metadata": {"name": "case"},
        "architecture": {"nodes": nodes, "edges": edges},
        "workload": {"rps": 100, "readRatio": 0.8, "durationS": 60},
        "pricing": pricing or {"provider": "generic"},
        **more,
    }


def link(source: str, target: str) -> Json:
    return {"id": f"{source}-{target}", "source": source, "target": target}


def clients() -> Json:
    return {"id": "clients", "type": "traffic_source"}


def node(node_id: str, node_type: str, **config: Any) -> Json:
    return {"id": node_id, "type": node_type, "config": config}


def service(node_id: str = "api", **config: Any) -> Json:
    return node(node_id, "service", serviceTimeMs=CONST10, **config)


AWS = {"provider": "aws", "region": "us-east-1"}


def designs() -> list[tuple[str, Json]]:
    """Each case's name (its Java test's name) and design."""
    return [
        (
            "managedPartsOnAws_arePricedAtTheSnapshotsRates",
            design(
                [
                    clients(),
                    node("alb", "load_balancer", technology="aws_alb"),
                    node("gw", "load_balancer", technology="api_gateway"),
                    service(),
                    service("fn", platform="serverless", memoryGib=1.0),
                    node("q", "message_queue", technology="sqs"),
                    service("worker"),
                    node("orders", "database", technology="dynamodb"),
                    node(
                        "ledger",
                        "database",
                        technology="dynamodb",
                        capacityMode="provisioned",
                        readCapacityUnits=100,
                        writeCapacityUnits=50,
                        dataSizeGib=20,
                    ),
                    node("pay", "external_api", latencyMs=CONST10, costPerMillionRequests=2.5),
                ],
                [
                    link("clients", "alb"),
                    link("alb", "gw"),
                    link("gw", "api"),
                    link("api", "fn"),
                    link("api", "q"),
                    link("q", "worker"),
                    link("api", "orders"),
                    link("api", "ledger"),
                    link("api", "pay"),
                ],
                AWS,
            ),
        ),
        (
            "kubernetesServices_runOnTheirClusterOrOnImpliedNodes",
            design(
                [
                    clients(),
                    service(platform="kubernetes"),
                    node("k8s", "kubernetes_cluster", label="Main cluster"),
                    service("pods", platform="kubernetes", cluster="k8s"),
                ],
                [link("clients", "api"), link("api", "pods")],
                AWS,
            ),
        ),
        (
            "aServiceOnKubernetesWithoutACluster_runsOnImpliedNodes",
            design([clients(), service(platform="kubernetes")], [link("clients", "api")], AWS),
        ),
        (
            "aBurstableTypeOverItsBaseline_paysForSurplusCredits",
            design(
                [clients(), service(cpuCores=2, replicas=1, workersPerReplica=64)],
                [link("clients", "api")],
                {**AWS, "families": ["t4g"]},
                workload={"rps": 300, "readRatio": 0.8, "durationS": 60},
            ),
        ),
        (
            "pricesSetByAPersonOrAPreset_keepTheirPrices",
            design(
                [
                    clients(),
                    service(costPerHour=0.42),
                    service("tagged", costPerHour=0.2),
                    node(
                        "pg", "database", technology="postgresql", storageGib=200, costPerHour=0.35
                    ),
                ],
                [link("clients", "api"), link("api", "tagged"), link("api", "pg")],
                AWS,
                provenance={"nodes/tagged/costPerHour": {"kind": "assumed"}},
            ),
        ),
        (
            "aSnapshotThisMachineDoesNotHave_isPricedAtGenericRates",
            design(
                [clients(), service()],
                [link("clients", "api")],
                {"provider": "aws", "region": "mars-1"},
            ),
        ),
        (
            "aDesignWithOnlyClients_hasNoPrice",
            design([clients()], []),
        ),
        (
            "partsNoSnapshotTypeCovers_keepTheirPresetPriceOrArePricedFromTheirSize",
            design(
                [
                    clients(),
                    service(cpuCores=900),
                    service("jvm", technology="java", cpuCores=900, costPerHour=0.17),
                    node("orders", "database", shards=2, replicationFactor=3),
                ],
                [link("clients", "api"), link("api", "jvm"), link("api", "orders")],
                AWS,
            ),
        ),
        (
            "aPartOfAnUnknownType_keepsItsSetPrice",
            design(
                [clients(), node("box", "mystery_box", costPerHour=0.3)],
                [link("clients", "box")],
                AWS,
            ),
        ),
        (
            "aTechnologyThatIsNotAName_isPricedAsTheEngineReadsIt",
            design([clients(), service(technology=5)], [link("clients", "api")], AWS),
        ),
        (
            "aDynamoDbConfigThatIsNotValid_isPricedLikeAnyOtherPart",
            design(
                [
                    clients(),
                    service(),
                    node(
                        "orders",
                        "database",
                        technology="dynamodb",
                        capacityMode="provisioned",
                        readCapacityUnits="lots",
                    ),
                ],
                [link("clients", "api"), link("api", "orders")],
                AWS,
            ),
        ),
        (
            "aDynamoDbCapacityModeOfNull_isPricedLikeAnyOtherPart",
            design(
                [
                    clients(),
                    service(),
                    node("orders", "database", technology="dynamodb", capacityMode=None),
                ],
                [link("clients", "api"), link("api", "orders")],
                AWS,
            ),
        ),
        (
            "sizesSetOnTheDesignOrByAScenario_sayWhereTheyCameFrom",
            design(
                [
                    clients(),
                    service(cpuCores=2),
                    node("db", "database", storageGib=50),
                    node("cache", "cache"),
                    node("q", "message_queue"),
                    service("fn", platform="serverless"),
                ],
                [
                    link("clients", "api"),
                    link("api", "db"),
                    link("api", "cache"),
                    link("api", "q"),
                    link("api", "fn"),
                ],
                scenarios=[
                    {
                        "id": "bigger",
                        "name": "Bigger cache",
                        "overrides": [
                            {
                                "target": {"kind": "node", "id": "cache"},
                                "path": "cpuCores",
                                "value": 4,
                            }
                        ],
                    }
                ],
            ),
        ),
    ]


SCENARIOS = {"sizesSetOnTheDesignOrByAScenario_sayWhereTheyCameFrom": "bigger"}
"""The scenario a case is resolved with; the design itself otherwise."""


def export() -> str:
    """The cases file's text: each case's design, resolved design, cost and sizing summary."""
    cases = []
    for name, raw in designs():
        doc = load_document(raw)
        resolved, _, sizes = resolve_scenario_sized(doc, SCENARIOS.get(name), REGISTRY)
        snapshot = simulation_input(resolved)
        estimate = estimate_design(resolved, REGISTRY, snapshot)
        cost = estimate_cost(
            snapshot["architecture"]["nodes"],
            REGISTRY,
            None,
            snapshot.get("pricing"),
            {k: v.kind for k, v in resolved.provenance.items()},
            base_busy(sizes),
            document=snapshot,
            estimate=estimate,
        )
        cases.append(
            {
                "name": name,
                "scenarioId": SCENARIOS.get(name),
                "design": doc.model_dump(mode="json", by_alias=True),
                "resolved": resolved.model_dump(mode="json", by_alias=True),
                "cost": cost,
                "summary": sizing_summary(doc, resolved, sizes, REGISTRY, estimate),
            }
        )
    document = {"generator": "scripts/export_cost_cases.py (archsim_engine 0.16.0)", "cases": cases}
    return json.dumps(document, indent=1, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_cost_cases.py")
            return 1
        print(f"{OUT.name} matches engine 0.16.0")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
