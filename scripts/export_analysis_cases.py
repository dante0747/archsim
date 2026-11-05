"""Write the analysis's and run preparation's extra test cases for the Java engine, or check them.

The golden files (parity/golden/) do not reach every branch of ``analyze.analyze_document`` and
``engine.prepare_run``: the templates' analyses are all of low confidence (no input of theirs is
measured, and none has a bad calibration fit), none is weighed by its inputs alone, and the golden
files prepare no run at all. This script asks the frozen engine (0.16.0) for the analysis of a
design, and for the run it prepares, for each such case: confidence that is high or medium, held
down by an experimental technology or a bad calibration fit, or weighed by inputs; quick checks
of every kind; a scenario and a calibration record; two parts with one id; a seed out of range, a
run over its limit and a design that cannot be simulated. It also asks for each technology's name,
as the confidence's reasons give it. It writes the designs with the results (or the errors), and
the names, to archsim-engine's test resources, where ``DesignAnalyzerTests``, ``RunPreparerTests``
and ``TechnologyNamesTests`` check the Java against them as the golden files are checked.

Usage:
    uv run python scripts/export_analysis_cases.py           # write the cases
    uv run python scripts/export_analysis_cases.py --check   # exit 1 if a fresh export differs

``--check`` compares the text exactly, so it holds on the platform that wrote the file: a CPython
on another C library may differ in the last bit of ``exp`` or ``log``. It is a manual step for
that reason, not a CI one.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

from archsim_engine.analyze import analyze_document
from archsim_engine.components import create_default_registry
from archsim_engine.engine import SimulationLimitError, SimulationLimits, prepare_run, quick_check
from archsim_engine.export.tech import TECH_NAMES, technology_label
from archsim_engine.issues import DocumentInvalidError

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-engine/src/test/resources/io/archsim/engine/analysis/cases.json"
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
        "architecture": {"nodes": nodes, "edges": edges},
        "workload": workload,
        **more,
    }


def link(source: str, target: str, **config: Any) -> Json:
    return {"id": f"{source}-{target}", "source": source, "target": target, "config": config}


def node(node_id: str, node_type: str, **config: Any) -> Json:
    return {"id": node_id, "type": node_type, "config": config}


def measured(fit: float | None, value: Any = None) -> Json:
    out: Json = {"kind": "measured", "source": "prometheus", "fittedAt": "2026-10-01T00:00:00Z"}
    if fit is not None:
        out["fitError"] = fit
    if value is not None:
        out["value"] = value
    return out


API_INPUTS = (
    "serviceTimeMs",
    "memoryPerRequestMb",
    "memoryBaselineMb",
    "gcPauseMs",
    "gcEveryRequests",
    "warmupFactor",
    "warmupS",
)
DB_INPUTS = (
    "readServiceTimeMs",
    "writeServiceTimeMs",
    "throughputLimitRps",
    "commitFlushMs",
    "replicaApplyFactor",
    "replicationDelayMs",
    "sstablesPerRead",
    "cpuShare",
    "workingSetGib",
    "diskReadMs",
    "dataSizeGib",
)
WORKLOAD_INPUTS = ("rps", "readRatio", "keyCount", "keySkew", "pattern")


def api_and_db(db: str = "postgresql", api: Json | None = None, **more: Any) -> Json:
    """Clients, an API and a database: the database is the busier part."""
    return design(
        [
            node("clients", "traffic_source"),
            node(
                "api",
                "service",
                **{"serviceTimeMs": CONST10, "replicas": 2, "cpuCores": 2, **(api or {})},
            ),
            node("db", "database", technology=db),
        ],
        [link("clients", "api", timeoutMs=2000), link("api", "db", timeoutMs=500)],
        **more,
    )


def measuring(node_id: str, keys: tuple[str, ...], fit: float | None = 0.05) -> Json:
    """Each of a part's inputs measured, with a fit error."""
    return {f"nodes/{node_id}/{key}": measured(fit) for key in keys}


def designs() -> list[tuple[str, Json, Json]]:
    """Each case's name (its Java test's name), design, and what to ask: a scenario, a seed."""
    every = {**measuring("api", API_INPUTS), **measuring("db", DB_INPUTS)}
    busy_api = {"cpuCores": 0.5}
    return [
        ("aDesignOfIllustrativeInputs_isOfLowConfidence", api_and_db(), {}),
        ("partsWhoseInputsAreAllMeasured_areOfHighConfidence", api_and_db(provenance=every), {}),
        (
            "aBusiestPartWithMeasuredInputs_isOfMediumConfidence",
            api_and_db(provenance=measuring("db", DB_INPUTS)),
            {},
        ),
        (
            "measuredInputsOutsideTheBusiestParts_leaveConfidenceLow",
            api_and_db(
                provenance={
                    **measuring("api", API_INPUTS[:4]),
                    **measuring("db", DB_INPUTS[:3]),
                    "nodes/api/costPerHour": measured(0.01),
                    "nodes/db/costPerHour": measured(0.01),
                    "edges/clients-api/latencyMs": measured(0.02),
                    "edges/api-db/latencyMs": measured(0.02),
                    **{f"workload/{key}": measured(None) for key in WORKLOAD_INPUTS},
                }
            ),
            {},
        ),
        (
            "aBadCalibrationFit_makesConfidenceLow",
            api_and_db(
                provenance={
                    **every,
                    "nodes/api/serviceTimeMs": measured(0.45),
                    "nodes/api/gcPauseMs": measured(0.35),
                    "nodes/db/readServiceTimeMs": measured(0.31),
                    "nodes/db/diskReadMs": measured(0.5),
                }
            ),
            {},
        ),
        (
            "measuredInputsWithoutAFit_areGrounded",
            api_and_db(
                provenance={
                    **measuring("api", API_INPUTS, None),
                    **measuring("db", DB_INPUTS, None),
                }
            ),
            {},
        ),
        (
            "anExperimentalTechnologyAtTheBusiestPart_makesConfidenceLow",
            api_and_db(db="sqlserver", provenance=every),
            {},
        ),
        (
            "anExperimentalTechnologyOnThePath_makesConfidenceMedium",
            api_and_db(db="sqlserver", api=busy_api, provenance=every),
            {},
        ),
        ("aDesignWithoutLoad_isWeighedByItsInputs", api_and_db(workload={"rps": 0}), {}),
        (
            "aBurstPattern_cannotBeQuickChecked",
            api_and_db(
                workload={
                    "pattern": {"kind": "burst", "startS": 10, "durationS": 5, "burstRps": 300}
                }
            ),
            {},
        ),
        (
            "plannedFailures_cannotBeQuickChecked",
            api_and_db(
                workload={
                    "durationS": 600,
                    "faults": [
                        {
                            "id": "f1",
                            "kind": "outage",
                            "target": "db",
                            "startS": 30,
                            "durationS": 10,
                        },
                        {
                            "id": "f2",
                            "kind": "slowdown",
                            "target": "api",
                            "startS": 60,
                            "durationS": 10,
                        },
                    ],
                }
            ),
            {},
        ),
        (
            "onePlannedFailure_cannotBeQuickChecked",
            api_and_db(
                workload={
                    "durationS": 600,
                    "faults": [
                        {
                            "id": "f1",
                            "kind": "outage",
                            "target": "db",
                            "startS": 30,
                            "durationS": 10,
                        }
                    ],
                }
            ),
            {},
        ),
        ("aLongRun_isQuickChecked", api_and_db(workload={"durationS": 1200, "rps": 1500}), {}),
        (
            "aShortSample_isWrittenWithADecimal",
            api_and_db(workload={"durationS": 40, "warmupS": 2.5, "rps": 6000}),
            {},
        ),
        (
            "aScenarioWithACalibration_isAnalyzedAsTheScenario",
            api_and_db(
                scenarios=[
                    {
                        "id": "busy",
                        "name": "Busy",
                        "overrides": [
                            {"target": {"kind": "workload"}, "path": "rps", "value": 400}
                        ],
                    }
                ],
                calibration={"at": "2026-10-01T00:00:00Z", "source": "prometheus", "inputs": 2},
                provenance=every,
            ),
            {"scenarioId": "busy"},
        ),
        (
            "twoPartsWithOneId_eachListTheirInputs",
            design(
                [
                    node("clients", "traffic_source"),
                    node("api", "service", serviceTimeMs=CONST10, replicas=2),
                    node("api", "service", serviceTimeMs=CONST10, replicas=2),
                ],
                [link("clients", "api")],
            ),
            {},
        ),
        ("aDocumentThatCannotBeRead_isRefused", {"kind": "nope"}, {}),
        ("aSeedOutOfRange_isRefused", api_and_db(), {"seed": 2**32}),
        ("aNewSeed_replacesTheDesigns", api_and_db(), {"seed": 7}),
        ("aRunOverItsLimit_isRefused", api_and_db(workload={"rps": 50_000, "durationS": 3600}), {}),
        (
            "aDesignThatCannotBeSimulated_isRefused",
            design(
                [node("clients", "traffic_source"), node("api", "service", replicas=0)],
                [link("clients", "api")],
            ),
            {},
        ),
    ]


NAMED = [
    ("external_api", "payments"),
    ("nope", "redis"),
    ("service", "my_custom-tech"),
    ("service", "2fast"),
    ("service", "stra\u00dfe-db"),
    ("service", "\u00f1and\u00fa_db"),
    ("service", "e\u0301clair"),
    ("service", "\u217b_x"),
    ("service", "x\u00b2y"),
    ("service", ""),
]
"""Technologies named other than by the engine's table: a preset's name, a known name whatever
the type, and words capitalized, a combining mark ending a word and numbers carrying it on."""


def technology_names() -> list[Json]:
    """Each technology's name: every one the engine knows, and the cases of :data:`NAMED`."""
    asked = [("service", technology) for technology in TECH_NAMES] + NAMED
    return [
        {"type": t, "technology": technology, "name": technology_label(REGISTRY, t, technology)}
        for t, technology in asked
    ]


def outcome(call: Callable[..., Any], *args: Any) -> Json:
    """What a call answers, or the error it raises with its words and issues."""
    try:
        return {"output": call(*args)}
    except DocumentInvalidError as exc:
        issues = [issue.to_dict() for issue in exc.issues]
        return {"error": {"type": "DocumentInvalidError", "message": str(exc), "issues": issues}}
    except SimulationLimitError as exc:
        return {"error": {"type": "SimulationLimitError", "message": str(exc), "issues": []}}


def prepared(raw: Json, ask: Json) -> Json:
    """The run the engine service's /prepare answers with, as it writes it."""
    run = prepare_run(
        raw,
        ask.get("scenarioId"),
        REGISTRY,
        SimulationLimits(max_requests=2_000_000),
        ask.get("seed"),
    )
    return {
        "scenarioId": run.scenario_id,
        "scenarioName": run.scenario_name,
        "seed": run.resolved.workload.seed,
        "document": run.document.to_json_dict(),
        "quickCheck": quick_check(run.resolved.workload),
    }


def export() -> str:
    """The cases file's text: each case's design, its analysis and its prepared run."""
    cases = []
    for name, raw, ask in designs():
        cases.append(
            {
                "name": name,
                "design": raw,
                **({"scenarioId": ask["scenarioId"]} if "scenarioId" in ask else {}),
                **({"seed": ask["seed"]} if "seed" in ask else {}),
                "analysis": outcome(analyze_document, raw, REGISTRY, ask.get("scenarioId")),
                "prepared": outcome(prepared, raw, ask),
            }
        )
    document = {
        "generator": "scripts/export_analysis_cases.py (archsim_engine 0.16.0)",
        "cases": cases,
        "technologyNames": technology_names(),
    }
    return json.dumps(document, indent=1, ensure_ascii=False) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    text = export()
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.name} differs from a fresh export: run scripts/export_analysis_cases.py")
            return 1
        print(f"{OUT.name} matches engine 0.16.0")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
