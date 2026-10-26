"""Importing docker-compose files, Kubernetes manifests and Terraform plans: the import report,
provenance of imported numbers, and the round trip of ArchSim's own exports."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from archsim_engine import create_default_registry, load_document
from archsim_engine.export import export_document
from archsim_engine.importers import import_design
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
FIXTURES = Path(__file__).parent / "fixtures" / "import"
TEMPLATES = sorted(EXAMPLES.glob("*.json"))


def shape(doc: dict[str, Any]) -> tuple[dict[str, Any], set[tuple[Any, ...]]]:
    """Parts (type, effective technology, replicas, autoscaling) and links of a design."""
    nodes = {}
    for n in doc["architecture"]["nodes"]:
        cfg = {**REGISTRY.get(n["type"]).default_config(), **(n.get("config") or {})}
        nodes[n["id"]] = (
            n["type"],
            cfg.get("technology"),
            cfg.get("replicas") if n["type"] == "service" else None,
            (cfg.get("minReplicas"), cfg.get("maxReplicas")) if cfg.get("autoscaling") else None,
        )
    edges = {
        (
            e["source"],
            e["target"],
            e.get("sourcePort") or "out",
            tuple(sorted((e.get("config") or {}).get("requestTypes") or ["read", "write"])),
        )
        for e in doc["architecture"]["edges"]
    }
    return nodes, edges


@pytest.mark.parametrize("fmt", ["compose", "k8s"])
@pytest.mark.parametrize("path", TEMPLATES, ids=lambda p: p.stem)
def test_every_template_round_trips(path: Path, fmt: str) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    text = export_document(doc, fmt, REGISTRY)
    back, report = import_design(fmt, text, REGISTRY, doc["metadata"]["name"])
    assert shape(back) == shape(doc)
    assert not [i for i in report.issues if i["severity"] == "error"]
    assert not report.guessed  # an ArchSim export states everything the import needs
    load_document(back)


def test_compose_maps_infrastructure_and_lists_what_it_guessed() -> None:
    doc, report = import_design("compose", (FIXTURES / "voting-compose.yml").read_text(), REGISTRY)
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    assert nodes["db"]["type"] == "database" and nodes["db"]["config"]["technology"] == "postgresql"
    assert nodes["redis"]["type"] == "cache" and nodes["proxy"]["type"] == "load_balancer"
    assert nodes["vote"]["type"] == "service" and nodes["vote"]["config"]["replicas"] == 3
    links = {(e["source"], e["target"]) for e in doc["architecture"]["edges"]}
    assert ("result", "db") in links and ("clients", "proxy") in links
    # inferred links and the clients part are guesses, said so; build contexts are ignored
    assert any("link result -> db (from DATABASE_URL)" in g for g in report.guessed)
    assert any(g.startswith("a Clients part") for g in report.guessed)
    assert "vote.build" in report.ignored
    # imported numbers are assumed, with where they came from
    prov = {k: v for k, v in doc["provenance"]["nodes/vote/replicas"].items() if v is not None}
    assert prov == {
        "kind": "assumed",
        "source": "imported from docker-compose",
        "note": "deploy.replicas",
    }
    assert doc["provenance"]["nodes/db/maxConnections"]["note"] == "from the container command"
    assert doc["schemaVersion"] == "1.2"


def test_kubernetes_reads_autoscalers_ingresses_and_resources() -> None:
    doc, report = import_design("k8s", (FIXTURES / "shop-k8s.yaml").read_text(), REGISTRY)
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    checkout = nodes["checkout"]["config"]
    assert checkout["autoscaling"] is True and checkout["platform"] == "kubernetes"
    assert doc["provenance"]["nodes/checkout/maxReplicas"]["note"] == "HorizontalPodAutoscaler"
    assert nodes["shop-ingress"]["type"] == "load_balancer"
    assert nodes["orders-db"]["type"] == "database"
    # nothing in the manifests describes the nodes: the cluster part is a stated guess
    assert any("Kubernetes cluster part" in g for g in report.guessed)
    assert "ConfigMap shop-config" in report.ignored


def test_terraform_sizes_from_instance_types_and_suggests_links() -> None:
    doc, report = import_design(
        "terraform", (FIXTURES / "orders-aws-plan.json").read_text(), REGISTRY
    )
    nodes = {n["id"]: n for n in doc["architecture"]["nodes"]}
    db = nodes["orders-db"]["config"]
    # db.r6g.large: 2 vCPU, memory-optimised (8 GiB per vCPU), from AWS's instance types
    assert (db["cpuCores"], db["memoryGib"]) == (2, 16)
    assert "db.r6g.large" in doc["provenance"]["nodes/orders-db/cpuCores"]["note"]
    assert db["readReplicas"] == 1
    # the only link Terraform states: the event source mapping (queue -> function)
    links = {(e["source"], e["target"]) for e in doc["architecture"]["edges"]}
    assert ("orders", "orders-fulfil") in links
    assert {"source": "orders-api", "target": "orders-db"} in [
        {k: s[k] for k in ("source", "target")} for s in report.suggestions
    ]
    # each resource is either mapped or ignored, not both
    mapped = " ".join(report.mapped)
    assert not [i for i in report.ignored if i.split(" ")[0] in mapped]
    assert any("aws_iam_role.lambda" in i for i in report.ignored)


def test_unreadable_input_says_what_is_expected() -> None:
    with pytest.raises(ValueError, match="services"):
        import_design("compose", "version: '3'\n", REGISTRY)
    with pytest.raises(ValueError, match="terraform show -json"):
        import_design("terraform", "resource aws_s3_bucket b {}", REGISTRY)
    with pytest.raises(ValueError, match="no Kubernetes objects"):
        import_design("k8s", "# nothing\n", REGISTRY)


def test_malformed_archsim_parts_are_skipped_not_trusted() -> None:
    text = (
        "services:\n  api:\n    image: your-org/api:latest\n"
        "x-archsim:\n  parts:\n    - {id: clients, type: database}\n    - not a part\n"
        "    - {id: users, type: traffic_source, label: Users, calls: api}\n"
    )
    doc, _ = import_design("compose", text, REGISTRY)
    types = {n["id"]: n["type"] for n in doc["architecture"]["nodes"]}
    assert types == {"api": "service", "users": "traffic_source"}
    assert {(e["source"], e["target"]) for e in doc["architecture"]["edges"]} == {("users", "api")}
