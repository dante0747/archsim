"""Managed and per-request prices at the snapshot's rates, at the workload's steady base load
(cost.py): DynamoDB, API Gateway, Lambda, SQS, the ALB's capacity units, RDS storage and pods
without a cluster. The serverless template is checked by hand against AWS's list prices."""

from __future__ import annotations

import copy
import json
from typing import Any

import pytest

from archsim_engine import analyze_document, create_default_registry, run_simulation
from archsim_engine.cost import HOURS_PER_MONTH, NOT_MODELLED, SECONDS_PER_MONTH
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)


def _example(name: str) -> dict[str, Any]:
    return json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))


def _lines(cost: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {x["id"]: x for x in cost["byNode"]}


def test_the_serverless_template_matches_aws_list_prices_by_hand() -> None:
    doc = _example("serverless-burst")
    doc["scenarios"] = []
    report = run_simulation(copy.deepcopy(doc))
    lines = _lines(report["cost"])
    # DynamoDB provisioned, 100 RCU and 60 WCU: $0.00013 and $0.00065 per unit-hour, 730 hours
    table = lines["table"]
    capacity = 100 * 0.00013 * 730 + 60 * 0.00065 * 730
    assert capacity == pytest.approx(37.96, abs=0.01)  # ≈ $38 a month
    assert table["monthly"] == pytest.approx(capacity, abs=0.1)  # + a few cents of storage
    assert table["items"][0]["text"] == "73.0 k RCU-hours × $0.00013/RCU-hour"  # noqa: RUF001

    # Lambda at the base rate (150 invocations/s, not the burst's 1,500): $0.20 per million
    # invocations and $0.0000166667 per GB-second, at 1,769 MB (1 vCPU) for the duration one
    # takes at the base load, measured here outside the burst (Little's law: in flight ÷ rate)
    fn = next(n for n in report["nodes"] if n["id"] == "fn")
    t, offered = report["timeseries"]["t"], report["timeseries"]["offeredRps"]
    calm = [i for i, x in enumerate(t) if 10 <= x < 100 and offered[i] < 200]
    ts = fn["timeseries"]
    duration_s = sum(ts["inflightMean"][i] for i in calm) / sum(ts["arrivalRps"][i] for i in calm)
    memory_gb = 1769 / 1024
    invocations = 150 * SECONDS_PER_MONTH
    hand = invocations * 0.20 / 1e6 + invocations * duration_s * memory_gb * 0.0000166667
    line = lines["fn"]
    assert "invocations/s at the base load" in line["basis"] and "1,769 MB" in line["basis"]
    assert line["monthly"] == pytest.approx(hand, rel=0.10)
    # API Gateway (REST): $3.50 per million requests, at the base rate
    assert lines["gw"]["monthly"] == pytest.approx(150 * SECONDS_PER_MONTH * 3.5 / 1e6, rel=0.10)
    # the month is the base load's: a burst is not billed all month
    assert any("steady base load of 150.0 req/s" in n for n in report["cost"]["notes"])
    assert NOT_MODELLED in report["cost"]["notes"]


def test_before_a_run_per_request_parts_use_the_load_estimate_at_the_base_rate() -> None:
    cost = analyze_document(_example("serverless-image-pipeline"), REGISTRY)["cost"]
    lines = _lines(cost)
    # API Gateway at 150 req/s (constant traffic) and $3.50 per million
    assert lines["apigw"]["monthly"] == pytest.approx(150 * SECONDS_PER_MONTH * 3.5 / 1e6)
    # SQS: three requests a message (send, receive, delete) at $0.40 per million
    jobs = lines["jobs"]
    rate = float(jobs["basis"].split(" messages/s")[0])
    assert jobs["monthly"] == pytest.approx(rate * 3 * SECONDS_PER_MONTH * 0.4 / 1e6, rel=1e-3)
    assert "3 requests (send, receive, delete)" in jobs["basis"]
    # DynamoDB on demand: request units ($0.125 and $0.625 per million) plus storage
    meta = lines["metadata"]
    assert [i["what"] for i in meta["items"]] == [
        "read request units",
        "write request units",
        "GiB-months stored",
    ]
    assert meta["monthly"] > 0 and meta["priceSource"] == "snapshot"
    # no "run a simulation" left on lines the estimate can price
    assert all("run a simulation" not in x["basis"] for x in cost["byNode"])


def test_an_alb_pays_for_its_capacity_units() -> None:
    lines = _lines(analyze_document(_example("right-size-catalog-api"), REGISTRY)["cost"])
    lb = lines["lb"]
    hours, lcus = lb["items"]
    assert hours["monthly"] == pytest.approx(0.0225 * HOURS_PER_MONTH)
    # 10 KB processed per request (assumed), 1 LCU per GB an hour, $0.008 per LCU-hour
    rps = float(lb["basis"].split("(")[1].split(" req/s")[0])
    assert lcus["quantity"] == pytest.approx(rps * 10 * 3600 / 1e6 * HOURS_PER_MONTH, rel=1e-3)
    assert lcus["unitPrice"] == 0.008 and "assumed" in lb["basis"]


def test_managed_database_storage_has_its_own_rate() -> None:
    doc = _example("cache-aside-starter")
    db = next(n for n in doc["architecture"]["nodes"] if n["id"] == "db")
    db["config"]["storageGib"] = 100
    line = _lines(analyze_document(doc, REGISTRY)["cost"])["db"]
    from archsim_engine.pricing import load_snapshot

    snap = load_snapshot("aws", "us-east-1", "2026-10-28")
    instance = snap.find("database", "db.m6g.large", "postgresql").price("on_demand")  # type: ignore[union-attr]
    assert line["sku"] == "db.m6g.large" and instance is not None
    # $0.115 per GiB-month (RDS gp3), not EBS's $0.08
    assert line["unitPrice"] == pytest.approx(instance + 100 * 0.115 / HOURS_PER_MONTH, abs=2e-4)


def test_pods_without_a_cluster_run_on_implied_nodes_and_a_control_plane() -> None:
    cost = analyze_document(_example("notification-fanout-quotas"), REGISTRY)["cost"]
    lines = _lines(cost)
    api = lines["api"]
    assert api["priceSource"] == "snapshot" and "on implied" in api["basis"]
    assert "hpc" not in api["sku"] and "metal" not in api["sku"]
    plane = lines["kubernetes-control-plane"]
    assert plane["monthly"] == pytest.approx(0.10 * HOURS_PER_MONTH)
    # with a cluster in the design, the cluster's line carries its control plane
    k8s = _lines(analyze_document(_example("kubernetes-shared-pool"), REGISTRY)["cost"])
    assert "kubernetes-control-plane" not in k8s
    assert k8s["k8s"]["controlPlaneMonthly"] == pytest.approx(0.10 * HOURS_PER_MONTH)


def test_parts_billed_per_use_have_no_size_price() -> None:
    nodes = analyze_document(_example("serverless-burst"), REGISTRY)["sizing"]["nodes"]
    assert nodes["fn"]["pricePerHour"] is None  # a function: invocations and GB-seconds
    assert nodes["gw"]["pricePerHour"] is None and nodes["gw"]["skipped"]  # a managed gateway
