"""Write the report functions' extra test cases for the Java engine, or check the committed file.

The golden files (parity/golden/) record the report functions on the templates' runs, which do
not reach every branch: no template runs without objectives or with a p99 or throughput
objective, a run without the figures to judge it, a seed group of compact run summaries (the run
rows' flat ``p50``, ``p95`` and ``p99``, which the API passes), a sweep with no completed step or
a latency knee before a throughput one, a sampled run too short to judge, or a part whose
figures make the queueing formulas stop early. This script asks the frozen engine (0.16.0) for
``slo.slo_checks``, ``evaluate`` and ``verdict_text``, ``seeds.summarize_seeds``,
``sweep.row_from``, ``meets`` and ``analyze``, ``engine.stationary`` and ``explain.generic`` on
small made-up inputs that do, and writes each call as a golden entry (``name``, ``fn``,
``input``, ``output``) to archsim-engine's test resources, where ``ReportCasesTests`` replays
them as the golden files are replayed.

Usage:
    uv run python scripts/export_report_cases.py           # write the cases
    uv run python scripts/export_report_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any

from archsim_engine import engine, explain, seeds, slo, sweep

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/report/cases.json"
)
Json = dict[str, Any]

ALL_OBJECTIVES = {
    "latencyP95Ms": 300.0,
    "latencyP99Ms": 500,
    "availability": 0.999,
    "minThroughputRps": 1200.0,
}
NO_OBJECTIVES = {
    "latencyP95Ms": None,
    "latencyP99Ms": None,
    "availability": None,
    "minThroughputRps": None,
}


def summary(p95: float | None, p99: float | None, success: float | None, rps: float | None) -> Json:
    """A report's summary with what the verdicts read."""
    return {
        "latencyMs": {"mean": 50.0, "p50": 40.0, "p95": p95, "p99": p99},
        "successRate": success,
        "throughputRps": rps,
        "offeredRps": rps,
        "failureBreakdown": [],
    }


def compact(p95: float | None, success: float = 1.0, rps: float = 400.0) -> Json:
    """A run row's compact summary, as the API stores it for listings."""
    return {
        "offeredRps": rps,
        "throughputRps": rps,
        "successRate": success,
        "timeoutRate": 0.0,
        "errorRate": 0.0,
        "p50": None if p95 is None else p95 / 2,
        "p95": p95,
        "p99": None if p95 is None else p95 * 1.5,
        "bottleneck": None,
        "bottleneckNodeId": None,
    }


def slo_cases() -> list[tuple[str, str, Json]]:
    judged = [
        ("noObjectives", NO_OBJECTIVES, summary(90.0, 120.0, 1.0, 400.0)),
        ("everyObjectiveMet", ALL_OBJECTIVES, summary(300.0, 500.0, 0.999, 1200.0)),
        ("everyObjectiveMissed", ALL_OBJECTIVES, summary(1234.56, 2500.04, 0.99949, 1234.5)),
        ("aFigureMissing", ALL_OBJECTIVES, summary(None, 120.0, 1.0, 1500.0)),
        ("aFigureMissingAndOneMissed", ALL_OBJECTIVES, summary(None, 120.0, 0.5, 1500.0)),
        ("onlyThroughput", {"minThroughputRps": 10}, summary(90.0, 120.0, None, 9.5)),
    ]
    cases = []
    for name, objectives, figures in judged:
        result = slo.evaluate(objectives, figures)
        cases.append(
            (name, "archsim_engine.slo.slo_checks", {"objectives": objectives, "summary": figures})
        )
        cases.append(
            (name, "archsim_engine.slo.evaluate", {"objectives": objectives, "summary": figures})
        )
        cases.append((name, "archsim_engine.slo.verdict_text", {"result": result}))
    return cases


def seed_cases() -> list[tuple[str, str, Json]]:
    groups = [
        (
            "compactSummaries",
            [(42, compact(98.4)), (43, compact(131.0)), (44, compact(104.2))],
            ALL_OBJECTIVES,
        ),
        (
            "aRunWithoutFigures",
            [(1, compact(None)), (5, compact(120.0)), (9, None)],
            {"latencyP95Ms": 300.0},
        ),
        ("everyRunUndecided", [(7, compact(None)), (8, compact(None))], {"latencyP95Ms": 300.0}),
        ("missedAndUndecided", [(7, compact(None)), (8, compact(900.0))], {"latencyP95Ms": 300.0}),
        ("noObjectives", [(42, compact(98.0)), (43, compact(98.04))], NO_OBJECTIVES),
        ("objectivesNone", [(42, compact(9.95)), (43, compact(9.94))], None),
        ("oneRun", [(42, compact(5.25))], {"availability": 0.9}),
        ("anEmptySummary", [(42, {}), (43, compact(50.0))], {"latencyP95Ms": 300.0}),
        ("noRuns", [], {"latencyP95Ms": 300.0}),
        (
            "reportSummaries",
            [(3, summary(250.0, 400.0, 1.0, 900.0)), (2, summary(350.0, 600.0, 0.98, 880.0))],
            ALL_OBJECTIVES,
        ),
    ]
    return [
        (
            name,
            "archsim_engine.seeds.summarize_seeds",
            {"runs": [{"seed": s, "summary": m} for s, m in runs], "objectives": objectives},
        )
        for name, runs, objectives in groups
    ]


def report(series: Json, window: Json, nodes: list[Json], **more: Any) -> Json:
    """A report with what the sweep rows and the steady-state check read."""
    return {
        "summary": more.get("summary", summary(95.0, 130.0, 1.0, 398.0)),
        "findings": more.get("findings", []),
        "nodes": nodes,
        "provenance": {"resultDigest": "f" * 64},
        "timeseries": {"bucketS": 1.0, **series},
        "window": window,
    }


def flat(value: float, n: int = 12) -> list[float]:
    return [value] * n


def ramp(start: float, step: float, n: int = 12) -> list[float]:
    return [start + step * i for i in range(n)]


STEADY = {"throughputRps": flat(400.0), "offeredRps": flat(400.0), "latencyP95Ms": flat(90.0)}
WINDOW = {"warmupS": 2.0, "durationS": 12.0, "measuredS": 10.0}


def part(node_id: str, label: str, node_type: str, category: str, **more: Any) -> Json:
    """A report's part, with what every part has."""
    return {
        "id": node_id,
        "type": node_type,
        "category": category,
        "label": label,
        "summary": {},
        **more,
    }


def api(**timeseries: Any) -> Json:
    return part("api", "API", "service", "compute", timeseries=timeseries)


def stationary_cases() -> list[tuple[str, str, Json]]:
    reports = [
        ("steady", report(STEADY, WINDOW, [api(queueLength=flat(3.0))])),
        ("tooShortToJudge", report(STEADY, {"warmupS": 2.0, "durationS": 4.9}, [api()])),
        ("warmupPastTheSeries", report(STEADY, {"warmupS": 11.5, "durationS": 12.0}, [api()])),
        (
            "partsStillGrowing",
            report(
                STEADY,
                WINDOW,
                [
                    api(queueLength=ramp(0.0, 1.0), replicas=[2, 2, 2, 2, 2, 2, 3, 4, 4, 5, 6, 6]),
                    part("db", "DB", "database", "storage", timeseries=None),
                    part("queue", "Queue", "message_queue", "messaging"),
                    part(
                        "kafka",
                        "Kafka",
                        "message_queue",
                        "messaging",
                        timeseries={
                            "lag": ramp(10.0, 10.0),
                            "backlog": flat(1.0),
                            "nodes": flat(3),
                        },
                    ),
                ],
            ),
        ),
        (
            "fallingBehindWithGaps",
            report(
                {
                    "throughputRps": ramp(400.0, -10.0),
                    "offeredRps": flat(400.0),
                    "latencyP95Ms": [
                        90.0,
                        None,
                        92.0,
                        None,
                        95.0,
                        110.0,
                        None,
                        130.0,
                        150.0,
                        180.0,
                        None,
                        220.0,
                    ],
                },
                {"warmupS": 1.5, "durationS": 11.9},
                [api()],
            ),
        ),
        (
            "nothingOffered",
            report(
                {"throughputRps": flat(0.0), "offeredRps": flat(0.0), "latencyP95Ms": flat(0.0)},
                WINDOW,
                [api(nodes=None)],
            ),
        ),
    ]
    return [(name, "archsim_engine.engine.stationary", {"report": r}) for name, r in reports]


def finding(kind: str | None, node_id: str | None) -> Json:
    out: Json = {"title": "x", "nodeId": node_id}
    if kind is not None:
        out["kind"] = kind
    return out


def row_cases() -> list[tuple[str, str, Json]]:
    nodes = [api(), part("db", "Orders DB", "database", "storage", timeseries={})]
    with_failures = summary(95.0, 130.0, 0.98, 390.0) | {"latencyWithFailuresMs": {"p95": 2000.0}}
    reports = [
        ("noBottleneck", report(STEADY, WINDOW, nodes), 400.0),
        (
            "theFirstBottleneck",
            report(
                STEADY,
                WINDOW,
                nodes,
                summary=with_failures,
                findings=[
                    finding(None, "api"),
                    finding("hot_key", "api"),
                    finding("bottleneck", "db"),
                    finding("bottleneck", "api"),
                ],
            ),
            400.0,
        ),
        (
            "aBottleneckWithoutAPart",
            report(STEADY, WINDOW, nodes, findings=[finding("bottleneck", None)]),
            12.5,
        ),
        (
            "aBottleneckOfAnotherPart",
            report(STEADY, WINDOW, nodes, findings=[finding("bottleneck", "cache")]),
            400.0,
        ),
    ]
    return [
        (name, "archsim_engine.sweep.row_from", {"rps": rps, "report": r})
        for name, r, rps in reports
    ]


def row(rps: float, throughput: float | None, p95: float | None, **more: Any) -> Json:
    out: Json = {
        "rps": rps,
        "offered": more.get("offered", rps),
        "throughput": throughput,
        "p50": None if p95 is None else p95 / 2,
        "p95": p95,
        "p99": more.get("p99", None if p95 is None else p95 * 1.4),
        "p95WithFailures": p95,
        "successRate": more.get("successRate", 1.0),
        "bottleneck": more.get("bottleneck"),
        "resultDigest": "d" * 64,
    }
    return out


def meets_cases() -> list[tuple[str, str, Json]]:
    rows = [
        ("notCompleted", row(100.0, None, 50.0), ALL_OBJECTIVES),
        ("withoutAP99", row(100.0, 99.0, 50.0, p99=None), ALL_OBJECTIVES),
        ("aboveTheP99", row(100.0, 99.0, 50.0, p99=500.5), ALL_OBJECTIVES),
        ("withoutASuccessRate", row(100.0, 99.0, 50.0, successRate=None), ALL_OBJECTIVES),
        ("withoutAP95", row(100.0, 99.0, None), {"latencyP95Ms": 300.0}),
        ("noObjectives", row(100.0, 99.0, None, successRate=None), NO_OBJECTIVES),
        ("everyObjective", row(100.0, 99.0, 300.0, p99=500.0, successRate=0.999), ALL_OBJECTIVES),
    ]
    return [
        (name, "archsim_engine.sweep.meets", {"row": r, "objectives": o}) for name, r, o in rows
    ]


def analyze_cases() -> list[tuple[str, str, Json]]:
    slo300 = {"latencyP95Ms": 300.0, "availability": None}
    sweeps = [
        ("noCompletedStep", [row(100.0, None, None), row(200.0, None, None)], slo300),
        ("noSteps", [], slo300),
        (
            "aLatencyKnee",
            [
                row(300.0, 300.0, 12.0),
                row(100.0, 100.0, 10.0),
                row(500.0, 499.0, 41.0, bottleneck="DB"),
                row(700.0, 690.0, 260.0, bottleneck="DB"),
            ],
            slo300,
        ),
        (
            "aLatencyKneeTooSmallToCount",
            [row(100.0, 100.0, 1.0), row(200.0, 200.0, 4.5), row(300.0, 300.0, 9.0)],
            {"latencyP95Ms": 8.0},
        ),
        (
            "missesTheObjectivesBeforeAnyKnee",
            [
                row(1000.0, 1000.0, 100.0, bottleneck="API"),
                row(1234.5, 1234.0, 320.0, bottleneck="API"),
                row(1500.0, 1500.0, 330.0, bottleneck="API"),
            ],
            slo300,
        ),
        (
            "saturatesWhileMeetingTheObjectives",
            [row(100.0, 100.0, 50.0), row(200.0, 150.0, 60.0, offered=None, bottleneck="")],
            slo300,
        ),
        (
            "offeredZero",
            [
                row(100.0, 50.0, 50.0, offered=0.0),
                row(200.0, 120.0, 55.0, offered=0.0, bottleneck="Workers"),
            ],
            slo300,
        ),
        (
            "equalLoads",
            [
                row(100.0, 100.0, 50.0, bottleneck="first"),
                row(100.0, 100.0, 60.0, bottleneck="second"),
            ],
            slo300,
        ),
    ]
    return [
        (name, "archsim_engine.sweep.analyze", {"rows": rows, "objectives": o})
        for name, rows, o in sweeps
    ]


def node(**node_summary: Any) -> Json:
    return {**part("api", "API", "service", "compute"), "summary": node_summary}


POOL = {
    "arrivals": 12_345,
    "arrivalRps": 1234.5,
    "slots": 16,
    "meanHoldMs": 10.0,
    "utilization": 0.7716,
    "meanInSystem": 12.9,
    "latencyMs": {"mean": 10.5},
    "meanServiceMs": 4.2,
    "meanQueueWaitMs": 0.48,
    "cpuCapacityRps": 2400.0,
}


def generic_cases() -> list[tuple[str, str, Json]]:
    parts = [
        ("everyFormula", node(**POOL), 10.0, True),
        ("notPoolBased", node(**POOL), 10.0, False),
        ("noArrivals", node(**{**POOL, "arrivals": 0}), 10.0, True),
        ("noWindow", node(**POOL), 0.0, True),
        ("noArrivalRate", node(**{**POOL, "arrivalRps": None}), 10.0, True),
        ("noSlots", node(**{**POOL, "slots": 0}), 10.0, True),
        ("noHoldingTime", node(**{**POOL, "meanHoldMs": None}), 10.0, True),
        ("aHoldingTimeOfZero", node(**{**POOL, "meanHoldMs": 0}), 10.0, True),
        ("anArrivalRateOfZero", node(**{**POOL, "arrivalRps": 0}), 10.0, True),
        (
            "nothingMeasured",
            node(**{**POOL, "utilization": None, "meanInSystem": None, "meanQueueWaitMs": None}),
            10.0,
            True,
        ),
        ("halfASlot", node(**{**POOL, "slots": 0.5, "arrivalRps": 10.0}), 10.0, True),
        ("twoAndAHalfSlots", node(**{**POOL, "slots": 2.5, "arrivalRps": 100.0}), 10.0, True),
        ("overloaded", node(**{**POOL, "slots": 4}), 10.0, True),
        ("noCpuCeiling", node(**{**POOL, "cpuCapacityRps": 0}), 10.0, True),
        ("noServiceTime", node(**{**POOL, "meanServiceMs": None}), 10.0, True),
        ("aSummaryWithoutFigures", node(), 10.0, True),
    ]
    return [
        (name, "archsim_engine.explain.generic", {"node": n, "windowS": w, "poolBased": p})
        for name, n, w, p in parts
    ]


ADAPTERS = {
    "archsim_engine.slo.slo_checks": lambda i: slo.slo_checks(i["objectives"], i["summary"]),
    "archsim_engine.slo.evaluate": lambda i: slo.evaluate(i["objectives"], i["summary"]),
    "archsim_engine.slo.verdict_text": lambda i: slo.verdict_text(i["result"]),
    "archsim_engine.seeds.summarize_seeds": lambda i: seeds.summarize_seeds(
        i["runs"], i["objectives"]
    ),
    "archsim_engine.sweep.row_from": lambda i: sweep.row_from(i["rps"], i["report"]),
    "archsim_engine.sweep.meets": lambda i: sweep.meets(i["row"], i["objectives"]),
    "archsim_engine.sweep.analyze": lambda i: sweep.analyze(i["rows"], i["objectives"]),
    "archsim_engine.engine.stationary": lambda i: engine.stationary(i["report"]),
    "archsim_engine.explain.generic": lambda i: explain.generic(
        i["node"], i["windowS"], i["poolBased"]
    ),
}


def cases() -> list[Json]:
    out = []
    for name, fn, given in (
        slo_cases()
        + seed_cases()
        + stationary_cases()
        + row_cases()
        + meets_cases()
        + analyze_cases()
        + generic_cases()
    ):
        output = ADAPTERS[fn](copy.deepcopy(given))
        out.append({"name": name, "fn": fn, "input": given, "output": output})
    return out


def render() -> str:
    return json.dumps(cases(), indent=1, ensure_ascii=False, allow_nan=False) + "\n"


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
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
