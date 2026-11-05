"""Write a run's findings' extra test cases for the Java engine, or check the committed file.

The golden files (parity/golden/) record ``analysis.analyze`` on the templates' runs, which reach
17 kinds of finding but not every branch: no template run generates no request, wastes work on
clients that gave up, autoscales, lets replicas lag a second behind, leaves pods Pending, has a part
hot from its utilization alone or saturated from its CPU alone, is held up by memory or by a store
downstream, times out at a client, throttles a store or a balancer, fails client requests at a third
party, has a cache without a miss path, has queue consumers busy without a backlog growing, ends
with messages queued or never queues one, or has a fault in its warm-up, after its end or without
recovering. This script takes the frozen engine's
(0.16.0) reports of a few templates' runs from the golden files, changes the figures (or the
settings, in the report and in the design alike) that reach each such branch, and asks the engine
for the run's findings. It writes each as a golden entry (``name``, ``fn``, ``input``, ``output``),
the report cut to what the analysis reads, with each part's status and why (``statuses``, which the
analysis sets on the report), to archsim-engine's test resources, where ``FindingsCasesTests``
checks the Java against them.

Usage:
    uv run python scripts/export_findings_cases.py           # write the cases
    uv run python scripts/export_findings_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import copy
import gzip
import json
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

from archsim_engine import analysis
from archsim_engine.schema import ArchitectureDocument

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "parity/golden/templates"
OUT = ROOT / "archsim-engine/src/test/resources/io/archsim/engine/findings/cases.json"
Json = dict[str, Any]
Change = Callable[[Json, Json], None]

NODE_KEYS = ("id", "type", "category", "label", "summary", "config", "behaviour")
NODE_SERIES = ("queueMean", "replicas", "rejectedRps", "queueMax")
RUN_SERIES = ("bucketS", "t", "throughputRps", "errorRps", "timeoutRps", "latencyP95Ms")


def golden_run(template: str, run: str) -> tuple[Json, Json]:
    """A template's run from the golden files: its report and the design it simulated."""
    with gzip.open(GOLDEN / f"{template}.json.gz") as file:
        data = json.load(file)
    recorded = data["runs"][run]
    return recorded["report"], recorded["entries"]["resolve_scenario_sized"]["output"]["resolved"]


def cut(report: Json) -> Json:
    """The report as far as the analysis reads it, and what every report has."""
    nodes = []
    for node in report["nodes"]:
        part = {key: node[key] for key in NODE_KEYS if key in node}
        if "behaviour" in part:
            part["behaviour"] = [{"id": behaviour["id"]} for behaviour in part["behaviour"]]
        part["timeseries"] = {k: v for k, v in node["timeseries"].items() if k in NODE_SERIES}
        nodes.append(part)
    return {
        "summary": report["summary"],
        "window": report["window"],
        "timeseries": {k: v for k, v in report["timeseries"].items() if k in RUN_SERIES},
        "nodes": nodes,
        "findings": [],
        "provenance": {"resultDigest": report["provenance"]["resultDigest"]},
    }


def part(report: Json, node_id: str) -> Json:
    return next(node for node in report["nodes"] if node["id"] == node_id)


def summary_of(report: Json, node_id: str) -> Json:
    return part(report, node_id)["summary"]


def configure(report: Json, resolved: Json, node_id: str, **settings: Any) -> None:
    """Changes a part's settings in the report and in the design alike."""
    part(report, node_id)["config"].update(settings)
    next(n for n in resolved["architecture"]["nodes"] if n["id"] == node_id)["config"].update(
        settings
    )


def series(report: Json, node_id: str, name: str, values: list[Any]) -> None:
    part(report, node_id)["timeseries"][name] = values


def buckets(report: Json) -> int:
    return len(report["timeseries"]["t"])


def no_traffic(report: Json, resolved: Json) -> None:
    report["summary"]["arrived"] = 0


def wasted(share: float, other: float | None = None) -> Change:
    def change(report: Json, resolved: Json) -> None:
        api = summary_of(report, "api")
        api["counters"]["wasted"] = int(api["completed"] * share)
        if other is not None:
            db = summary_of(report, "db")
            db["counters"]["wasted"] = int(db["completed"] * other)

    return change


def autoscaled(peak: int, rejecting: bool, utilization: float) -> Change:
    def change(report: Json, resolved: Json) -> None:
        n = buckets(report)
        ramp = [min(peak, 2 + i) for i in range(n)]
        ramp[-1] = peak - 1
        series(report, "checkout", "replicas", [float(v) for v in ramp])
        series(
            report,
            "checkout",
            "rejectedRps",
            [25.5 if rejecting and i < 4 else 0.0 for i in range(n)],
        )
        summary_of(report, "checkout")["utilization"] = utilization

    return change


def replication_lag(replica_reads: int, workers: int | None) -> Change:
    def change(report: Json, resolved: Json) -> None:
        db = summary_of(report, "db")
        db["maxReplicationLagMs"] = 2450.0
        db["counters"].update(staleReads=12, replicaReads=replica_reads)
        if workers is not None:
            configure(report, resolved, "db", replicaApplyWorkers=workers)

    return change


def pods(peak: int, longest: float, end: int) -> Change:
    def change(report: Json, resolved: Json) -> None:
        k8s = summary_of(report, "k8s")
        k8s.update(podsPendingPeak=peak, maxPendingS=longest, podsPendingEnd=end)
        k8s.setdefault("counters", {})["nodeAdds"] = 2

    return change


def requested(report: Json, resolved: Json) -> None:
    summary_of(report, "k8s")["cpuRequestedRatio"] = 0.9


def busy(utilization: float, cores: float | None) -> Change:
    def change(report: Json, resolved: Json) -> None:
        api = summary_of(report, "api")
        api["utilization"] = utilization
        if cores is not None:
            api["cpuCoreUtilization"] = cores

    return change


def backlog_grew(report: Json, resolved: Json) -> None:
    series(report, "orders-topic", "queueMean", [float(i * 3) for i in range(buckets(report))])


def busy_consumers(report: Json, resolved: Json) -> None:
    summary_of(report, "orders-topic")["utilization"] = 0.9
    series(report, "orders-topic", "queueMean", [1.0] * buckets(report))


def backlog_left(report: Json, resolved: Json) -> None:
    summary_of(report, "orders-topic")["backlogAtEnd"] = 37


def kept_up(report: Json, resolved: Json) -> None:
    series(report, "orders-topic", "queueMax", [0] * buckets(report))


def bottleneck(limit: str, own: float | None) -> Change:
    def change(report: Json, resolved: Json) -> None:
        api = summary_of(report, "api")
        api.update(utilization=0.97, peakUtilization=1.0, capacityLimit=limit, slots=86.545)
        api.update(meanQueue=14.2, peakQueue=120, rejectedTotal=58, cpuUtilization=own)
        api["rejected"] = {"queue_full": 40, "outage": 3, "queue_timeout": 15}
        series(report, "api", "queueMean", [float(i * 2) for i in range(buckets(report))])

    return change


def behind_balancer(report: Json, resolved: Json) -> None:
    summary_of(report, "lb")["utilization"] = 0.97
    summary_of(report, "api")["utilization"] = 0.97


def behind_store(report: Json, resolved: Json) -> None:
    summary_of(report, "api").update(utilization=0.96, cpuUtilization=0.3)
    summary_of(report, "db")["utilization"] = 0.97


def failures(reason: str, node_id: str, label: str | None) -> Change:
    def change(report: Json, resolved: Json) -> None:
        report["summary"]["failureBreakdown"] = [
            {
                "count": 1234,
                "edgeId": "api-db" if reason == "dependency_timeout" else None,
                "nodeId": node_id,
                "nodeLabel": label,
                "reason": reason,
                "reasonText": "timed out waiting for DB"
                if reason == "dependency_timeout"
                else "queue full",
                "share": 0.815,
                "status": "failed",
            }
        ]

    return change


def throttled_store(report: Json, resolved: Json) -> None:
    db = summary_of(report, "db")
    db.update(rejectedTotal=30)
    db["rejected"] = {"throttled": 30}
    configure(report, resolved, "db", throughputLimitRps=100.0, shards=2)


def throttled_third_party(report: Json, resolved: Json) -> None:
    summary_of(report, "fraud")["counters"].update(rateLimited=40, throttled=30)
    configure(report, resolved, "fraud", rateLimitRps=500.0, concurrencyLimit=64)


def throttled_balancer(report: Json, resolved: Json) -> None:
    lb = summary_of(report, "lb")
    lb["rejected"] = {"rate_limited": 100}
    lb["rejectedTotal"] = 100
    configure(report, resolved, "lb", rateLimitRps=1000.0)


def errors_unreached(report: Json, resolved: Json) -> None:
    summary_of(report, "fraud")["counters"]["upstreamErrors"] = 2


def errors_reached(report: Json, resolved: Json) -> None:
    summary_of(report, "fraud")["counters"]["upstreamErrors"] = 20
    report["summary"]["failureBreakdown"] = [
        {
            "count": 18,
            "edgeId": None,
            "nodeId": "fraud",
            "nodeLabel": "Fraud-scoring API",
            "reason": "upstream_error",
            "reasonText": "third-party API returned an error",
            "share": 1.0,
            "status": "failed",
        }
    ]


def no_miss_path(report: Json, resolved: Json) -> None:
    for edge in resolved["architecture"]["edges"]:
        edge["config"]["readsOnMiss"] = False


def lock_waits(report: Json, resolved: Json) -> None:
    db = summary_of(report, "db")
    db.update(meanLockWaitMs=3.0, meanServiceMs=5.0)
    db["counters"].update(writes=100, lockWaits=40)


def shard_cpu(report: Json, resolved: Json) -> None:
    configure(report, resolved, "products", cpuCores=0.5)


def fault(**changes: Any) -> Change:
    def change(report: Json, resolved: Json) -> None:
        resolved["workload"]["faults"][0].update(changes)

    return change


def not_recovered(report: Json, resolved: Json) -> None:
    ts = report["timeseries"]
    outage = resolved["workload"]["faults"][0]
    end = outage["startS"] + outage["durationS"]
    for i, t in enumerate(ts["t"]):
        if t >= end:
            ts["errorRps"][i] = (ts["throughputRps"][i] or 0.0) * 0.5
            ts["throughputRps"][i] = (ts["throughputRps"][i] or 0.0) * 0.5


def uncalled(report: Json, resolved: Json) -> None:
    summary_of(report, "idp")["rejected"].pop("outage", None)
    report["summary"]["failureBreakdown"] = [
        b for b in report["summary"]["failureBreakdown"] if b["reason"] != "outage"
    ]


def more_faults(report: Json, resolved: Json) -> None:
    edge = resolved["architecture"]["edges"][0]["id"]
    resolved["workload"]["faults"] = [
        {
            "id": "slow-api",
            "kind": "slowdown",
            "target": "api",
            "startS": 1.5,
            "durationS": 1.0,
            "factor": 3.0,
        },
        {
            "id": "lag",
            "kind": "latency",
            "target": edge,
            "startS": 1.5,
            "durationS": 1.0,
            "extraMs": 150.0,
        },
        {"id": "db-down", "kind": "outage", "target": "db", "startS": 1.5, "durationS": 1.0},
        {"id": "cache-down", "kind": "outage", "target": "cache", "startS": 1.5, "durationS": 1.0},
        {"id": "lb-down", "kind": "outage", "target": "lb", "startS": 1.5, "durationS": 1.0},
    ]
    ts = report["timeseries"]
    for i, t in enumerate(ts["t"]):
        if 1.5 <= t < 2.5:
            ts["errorRps"][i] = (ts["throughputRps"][i] or 0.0) * 0.25


def unmeasured(report: Json, resolved: Json) -> None:
    summary_of(report, "svc")["utilization"] = None


def idle_and_warm(report: Json, resolved: Json) -> None:
    summary_of(report, "db")["arrivals"] = 0
    summary_of(report, "api")["utilization"] = 0.75


def write_heavy(report: Json, resolved: Json) -> None:
    db = summary_of(report, "db")
    db["counters"].update(reads=120, writes=400, replicaReads=80)


CASES: list[tuple[str, str, str, Change]] = [
    ("noTraffic", "queueing-lab", "base", no_traffic),
    ("wastedWorkMostly", "cache-aside-starter", "base", wasted(0.3, 0.02)),
    ("wastedWorkSome", "cache-aside-starter", "base", wasted(0.05)),
    ("autoscaledToItsMaximum", "black-friday-kubernetes", "base", autoscaled(14, True, 0.88)),
    ("autoscaledWithoutRejections", "black-friday-kubernetes", "base", autoscaled(5, False, 0.5)),
    ("replicasLagWithStaleReads", "mysql-replica-lag", "base", replication_lag(300, None)),
    ("replicasLagWithApplyWorkers", "mysql-replica-lag", "base", replication_lag(0, 8)),
    ("podsStillPending", "kubernetes-pods-pending", "base", pods(3, 45.5, 1)),
    ("podsPendingWithoutAnAutoscaler", "kubernetes-pods-pending", "no-ca", pods(2, 12.0, 0)),
    ("clusterMostlyRequested", "kubernetes-pods-pending", "base", requested),
    ("hotFromItsCpu", "cache-aside-starter", "base", busy(0.5, 0.9)),
    ("hotFromItsUtilization", "cache-aside-starter", "base", busy(0.9, None)),
    ("hotFromItsUtilizationAndCpu", "cache-aside-starter", "base", busy(0.9, 0.88)),
    ("saturatedFromItsCpu", "cache-aside-starter", "base", busy(0.5, 0.97)),
    ("queueBacklogGrew", "kafka-order-pipeline", "base", backlog_grew),
    ("queueConsumersBusy", "kafka-order-pipeline", "base", busy_consumers),
    ("queueBacklogLeftAtTheEnd", "kafka-order-pipeline", "base", backlog_left),
    ("queueKeptUp", "kafka-order-pipeline", "base", kept_up),
    ("bottleneckOfMemory", "cache-aside-starter", "base", bottleneck("memory", None)),
    ("bottleneckOfCpuBlockedDownstream", "cache-aside-starter", "base", bottleneck("cpu", 0.6)),
    ("bottleneckOfSlots", "cache-aside-starter", "base", bottleneck("slots", None)),
    ("symptomBehindABalancer", "cache-aside-starter", "base", behind_balancer),
    ("symptomOfASlowStore", "cache-aside-starter", "base", behind_store),
    (
        "failuresAtAClientTimeout",
        "cache-aside-starter",
        "base",
        failures("client_timeout", "clients", "Clients"),
    ),
    (
        "failuresAtAnUnknownClient",
        "cache-aside-starter",
        "base",
        failures("client_timeout", "ghost", None),
    ),
    (
        "failuresAtADependency",
        "cache-aside-starter",
        "base",
        failures("dependency_timeout", "api", "API"),
    ),
    ("failuresSomewhere", "cache-aside-starter", "base", failures("queue_full", "api", None)),
    ("throttledByAStore", "cache-aside-starter", "base", throttled_store),
    ("throttledByAThirdParty", "public-api-slow-dependency", "base", throttled_third_party),
    ("throttledByABalancer", "cache-aside-starter", "base", throttled_balancer),
    ("thirdPartyErrorsNotReachingClients", "public-api-slow-dependency", "base", errors_unreached),
    ("thirdPartyErrorsReachingClients", "public-api-slow-dependency", "base", errors_reached),
    ("cacheWithoutAMissPath", "cache-aside-starter", "base", no_miss_path),
    ("lockWaitsShorterThanWrites", "cache-aside-starter", "base", lock_waits),
    ("hotShardByCpu", "mongodb-hot-shard", "base", shard_cpu),
    ("faultInTheWarmup", "auth-token-introspection", "idp-down", fault(startS=0.1)),
    ("faultAfterTheRun", "auth-token-introspection", "idp-down", fault(startS=5.0)),
    ("faultNotRecovered", "auth-token-introspection", "idp-down", not_recovered),
    ("outageOfAnUncalledPart", "auth-token-introspection", "idp-down", uncalled),
    ("slowdownLatencyAndOutages", "cache-aside-starter", "base", more_faults),
    ("healthyWithoutUtilization", "queueing-lab", "base", unmeasured),
    ("idleAndWarmParts", "cache-aside-starter", "base", idle_and_warm),
    ("replicasUnderWriteLoad", "mysql-replica-lag", "base", write_heavy),
]


def case(name: str, template: str, run: str, change: Change) -> Json:
    report, resolved = golden_run(template, run)
    report, resolved = cut(report), copy.deepcopy(resolved)
    change(report, resolved)
    analyzed = copy.deepcopy(report)
    findings = analysis.analyze(analyzed, ArchitectureDocument.model_validate(resolved))
    statuses = {node["id"]: [node["status"], node["statusReasons"]] for node in analyzed["nodes"]}
    return {
        "name": name,
        "fn": "archsim_engine.analysis.analyze",
        "input": {"report": report, "resolved": resolved},
        "output": findings,
        "statuses": statuses,
    }


def render() -> str:
    cases = [case(*spec) for spec in CASES]
    return json.dumps(cases, indent=1, ensure_ascii=False, allow_nan=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args()
    text = render()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} differs from a fresh export", file=sys.stderr)
            return 1
        print(f"{OUT} is up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
