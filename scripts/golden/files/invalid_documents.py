"""The invalid-documents fixture: templates with one thing wrong, and what loading, validation
and scenario resolution say about each."""

from __future__ import annotations

import copy
from collections.abc import Callable
from typing import Any

from archsim_engine.validation import DEFAULT_MAX_AMPLIFICATION
from golden.codec import Json
from golden.file import GoldenFile
from golden.paths import EXAMPLES, STARTER, read_json


def _node(d: Json, nid: str) -> Json:
    return next(n for n in d["architecture"]["nodes"] if n["id"] == nid)


def _edge(d: Json, eid: str) -> Json:
    return next(e for e in d["architecture"]["edges"] if e["id"] == eid)


def _link(eid: str, source: str, target: str, **config: Any) -> Json:
    return {
        "id": eid,
        "source": source,
        "sourcePort": "out",
        "target": target,
        "targetPort": "in",
        "config": {"requestTypes": ["read", "write"], **config},
    }


def _drop_node(d: Json, nid: str) -> None:
    a = d["architecture"]
    a["nodes"] = [n for n in a["nodes"] if n["id"] != nid]
    a["edges"] = [e for e in a["edges"] if nid not in (e["source"], e["target"])]


def _service(nid: str, **config: Any) -> Json:
    return {"id": nid, "type": "service", "label": nid, "config": config}


Mutation = Callable[[Json], Any]
INVALID: list[tuple[str, str, str | None, str, Mutation]] = [
    # (case, template, scenario, what it shows, mutation)
    ("missing-architecture", STARTER, None, "no architecture",
     lambda d: d.pop("architecture")),
    ("node-without-id", STARTER, None, "a node without an id",
     lambda d: _node(d, "api").pop("id")),
    ("node-id-pattern", STARTER, None, "a node id that is not an id",
     lambda d: _node(d, "db").update(id="Postgre SQL!")),
    ("unknown-top-level-field", STARTER, None, "a field the schema does not have",
     lambda d: d.update(colour="blue")),
    ("wrong-kind", STARTER, None, "not an architecture document",
     lambda d: d.update(kind="archsim.project")),
    ("negative-rps", STARTER, None, "a negative load",
     lambda d: d["workload"].update(rps=-5)),
    ("rps-not-a-number", STARTER, None, "a load that is not a number",
     lambda d: d["workload"].update(rps="fast")),
    ("warmup-not-shorter", STARTER, None, "a warm-up as long as the run",
     lambda d: d["workload"].update(warmupS=60)),
    ("burst-after-run", STARTER, None, "a burst that starts after the run",
     lambda d: d["workload"].update(pattern={"kind": "burst", "burstRps": 900, "startS": 90, "durationS": 5})),
    ("trace-not-at-zero", STARTER, None, "a trace that does not start at t = 0",
     lambda d: d["workload"].update(pattern={"kind": "trace", "points": [[5, 100], [10, 200]]})),
    ("fault-unknown-kind", STARTER, None, "a fault of an unknown kind",
     lambda d: d["workload"].update(faults=[{"id": "f", "kind": "meltdown", "target": "db", "startS": 1, "durationS": 2}])),
    ("fault-ids-repeat", STARTER, None, "two faults with one id",
     lambda d: d["workload"].update(faults=[{"id": "f", "kind": "outage", "target": "db", "startS": 1, "durationS": 2}] * 2)),
    ("seed-too-large", STARTER, None, "a seed above 2^32 - 1",
     lambda d: d["workload"].update(seed=2**32)),
    ("key-count-zero", STARTER, None, "no keys",
     lambda d: d["workload"].update(keyCount=0)),
    ("unknown-distribution", STARTER, None, "a latency distribution of an unknown kind",
     lambda d: _edge(d, "clients-lb")["config"].update(latencyMs={"kind": "pareto", "alpha": 2})),
    ("lognormal-p95-below-median", STARTER, None, "a lognormal whose p95 is below its median",
     lambda d: _edge(d, "clients-lb")["config"].update(latencyMs={"kind": "lognormal", "median": 10, "p95": 5})),
    ("empirical-falls", STARTER, None, "an empirical distribution whose values fall",
     lambda d: _edge(d, "lb-api")["config"].update(latencyMs={"kind": "empirical", "quantiles": [[0, 5], [0.5, 3], [1, 10]]})),
    ("unknown-request-type", STARTER, None, "a link that carries an unknown request type",
     lambda d: _edge(d, "lb-api")["config"].update(requestTypes=["read", "delete"])),
    ("objective-out-of-range", STARTER, None, "an availability objective above 1",
     lambda d: d.update(objectives={"availability": 1.5})),
    ("override-target-kind", STARTER, None, "a scenario override of an unknown target kind",
     lambda d: d["scenarios"][1]["overrides"][0]["target"].update(kind="service")),
    ("duplicate-node-id", STARTER, None, "two nodes with one id",
     lambda d: d["architecture"]["nodes"].append(copy.deepcopy(_node(d, "db")))),
    ("duplicate-edge-id", STARTER, None, "two links with one id",
     lambda d: d["architecture"]["edges"].append({**copy.deepcopy(_edge(d, "api-db")), "target": "cache"})),
    ("unknown-component-type", STARTER, None, "a component type the registry does not have",
     lambda d: d["architecture"]["nodes"].append({"id": "qc", "type": "quantum_cache", "label": "QC", "config": {}})),
    ("link-to-unknown-node", STARTER, None, "a link to a node that does not exist",
     lambda d: d["architecture"]["edges"].append(_link("api-ghost", "api", "ghost"))),
    ("database-sends", STARTER, None, "a database that sends requests",
     lambda d: d["architecture"]["edges"].append(_link("db-cache", "db", "cache"))),
    ("invalid-target-port", STARTER, None, "a link into a port the target does not have",
     lambda d: _edge(d, "lb-api").update(targetPort="admin")),
    ("self-loop", STARTER, None, "a link from a part to itself",
     lambda d: d["architecture"]["edges"].append(_link("api-api", "api", "api"))),
    ("repeated-link", STARTER, None, "two links between the same parts",
     lambda d: d["architecture"]["edges"].append({**copy.deepcopy(_edge(d, "api-db")), "id": "api-db-2"})),
    ("cycle", STARTER, None, "requests that come back to where they started",
     lambda d: d["architecture"]["edges"].append(_link("api-lb", "api", "lb"))),
    ("config-out-of-range", STARTER, None, "a setting out of its range",
     lambda d: _node(d, "api")["config"].update(replicas=-1)),
    ("config-unknown-field", STARTER, None, "a setting the component does not have",
     lambda d: _node(d, "api")["config"].update(turbo=True)),
    ("config-wrong-type", STARTER, None, "a setting of the wrong type",
     lambda d: _node(d, "db")["config"].update(maxConnections="many")),
    ("unreachable-part", STARTER, None, "a part no request reaches, and its unused link",
     lambda d: (d["architecture"]["nodes"].append(_service("batch")),
                d["architecture"]["edges"].append(_link("batch-db", "batch", "db")))),
    ("no-clients", STARTER, None, "no traffic source",
     lambda d: _drop_node(d, "clients")),
    ("clients-receive", STARTER, None, "a link into a traffic source",
     lambda d: d["architecture"]["edges"].append(_link("api-clients", "api", "clients"))),
    ("miss-port-writes", "cdn-video-streaming", None, "a cache's miss port that carries writes",
     lambda d: _edge(d, "shield-packager")["config"].update(requestTypes=["read", "write"])),
    ("clients-two-links", STARTER, None, "a traffic source with two links",
     lambda d: d["architecture"]["edges"].append(_link("clients-api", "clients", "api"))),
    ("clients-no-link", STARTER, None, "a traffic source with no link",
     lambda d: d["architecture"]["edges"].remove(_edge(d, "clients-lb"))),
    ("no-database-link", STARTER, None, "reads on a miss with nowhere to go",
     lambda d: d["architecture"]["edges"].remove(_edge(d, "api-db"))),
    ("reads-on-miss-without-cache", STARTER, None, "reads on a miss where there is no cache",
     lambda d: _edge(d, "lb-api")["config"].update(readsOnMiss=True)),
    ("unroutable-writes", STARTER, None, "writes that no link carries",
     lambda d: _edge(d, "lb-api")["config"].update(requestTypes=["read"])),
    ("amplification", STARTER, None, "retries that multiply into many calls (bound: 100)",
     lambda d: [_edge(d, e)["config"].update(maxAttempts=5, timeoutMs=100) for e in ("lb-api", "api-cache", "api-db")]),
    ("no-traffic", STARTER, None, "no load",
     lambda d: d["workload"].update(rps=0)),
    ("snapshot-missing", STARTER, None, "a price snapshot this engine does not have",
     lambda d: d["pricing"].update(snapshotDate="2020-01-01")),
    ("unknown-family", STARTER, None, "an instance family no price list has",
     lambda d: d["pricing"].update(families=["zz9"])),
    ("unknown-instance-type", STARTER, None, "an instance type the price list does not have",
     lambda d: _node(d, "db")["config"].update(instanceType="db.zz9.huge")),
    ("memory-too-small", STARTER, None, "a service with less memory than its workers need",
     lambda d: _node(d, "api")["config"].update(memoryGib=0.25, workersPerReplica=400)),
    ("too-many-connections", STARTER, None, "more PostgreSQL connections than its cores serve well",
     lambda d: _node(d, "db")["config"].update(technology="postgresql", maxConnections=500, cpuCores=2)),
    ("autoscaling-range", "black-friday-kubernetes", None, "an autoscaling range upside down",
     lambda d: [n["config"].update(autoscaling=True, minReplicas=8, maxReplicas=2)
                for n in d["architecture"]["nodes"] if n["type"] == "service"][:1]),
    ("pod-too-big", "kubernetes-pods-pending", None, "a pod larger than any Kubernetes node",
     lambda d: _node(d, "api")["config"].update(cpuCores=16)),
    ("queue-without-consumers", "kafka-order-pipeline", None, "a queue nobody consumes",
     lambda d: d["architecture"].update(edges=[
         e for e in d["architecture"]["edges"]
         if _node(d, e["source"])["type"] != "message_queue"])),
    ("unknown-scenario", STARTER, "nope", "a scenario the document does not have",
     lambda d: None),
    ("override-missing-node", STARTER, "one-api-replica", "an override of a node that does not exist",
     lambda d: d["scenarios"][1]["overrides"][0]["target"].update(id="ghost")),
    ("override-missing-path", STARTER, "one-api-replica", "an override of a setting path that does not exist",
     lambda d: d["scenarios"][1]["overrides"][0].update(path="serviceTimeMs.median.deep")),
    ("override-invalid-value", STARTER, "one-api-replica", "an override that makes the design invalid",
     lambda d: d["scenarios"][1]["overrides"][0].update(value=-3)),
    ("override-invalid-workload", STARTER, "traffic-burst", "a workload override that makes the run invalid",
     lambda d: d["scenarios"][5]["overrides"][0]["value"].update(startS=600)),
]  # fmt: skip


MAX_AMPLIFICATION = {"amplification": 100}
"""Cases validated with a tighter bound on the calls one request may cause."""


def invalid_file() -> GoldenFile:
    """Documents with something wrong: what loading, validation and scenario resolution say."""
    g = GoldenFile("fixtures", name="invalid-documents")
    for name, tid, sid, what, mutate in INVALID:
        raw = read_json(EXAMPLES / f"{tid}.json")
        mutate(raw)
        where = ("cases", name)
        g.place((*where, "description"), what)
        g.place((*where, "template"), tid)
        g.place((*where, "scenarioId"), sid)
        doc = g.entry(where, "load_document", "archsim_engine.scenarios.load_document",
                      {"raw": raw}, errors=True)  # fmt: skip
        if doc is None:
            continue
        bound = MAX_AMPLIFICATION.get(name, DEFAULT_MAX_AMPLIFICATION)
        issues = g.entry(
            where, "validate_document", "archsim_engine.validation.validate_document",
            {"doc": doc, "maxAmplification": bound},
        )["issues"]  # fmt: skip
        resolved = g.entry(
            where, "resolve_scenario_sized", "archsim_engine.scenarios.resolve_scenario_sized",
            {"doc": doc, "scenarioId": sid}, errors=True,
        )  # fmt: skip
        if resolved is not None and sid is not None:
            # what POST /validate checks for a scenario: the resolved document
            issues = issues + g.entry(
                where, "validate_document[resolved]",
                "archsim_engine.validation.validate_document",
                {"doc": resolved["resolved"], "maxAmplification": bound},
            )["issues"]  # fmt: skip
        if not issues and resolved is not None:
            raise AssertionError(f"invalid document '{name}' has nothing wrong with it")
    g.cases = len(INVALID)
    return g
