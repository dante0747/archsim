"""Write what the frozen engine's ``archsim`` commands printed, for the Java CLI, or check the committed files.

The Python CLI (``archsim_engine.cli``, engine 0.16.0) is recorded command by command, one file
each under ``archsim-cli/src/test/resources/io/archsim/cli/`` (``<command>-cases.json.gz``): the
arguments, the files the command reads, the exit code, what went to stdout and stderr, and the
files it wrote. The Java CLI's tests run each case and compare its lines word for word, and the
JSON it prints as values, as the golden files compare them (ADR-0055).

- ``run``: small designs (a run, one without failures, one whose every call fails, a scenario
  with a seed of its own, a seed given, a seed for a design without a workload, a quick check, a
  report written to a file, an invalid design, an invalid workload and a run over the limit); a
  report written to a file is compared as the recorded simulation cases are.
- ``validate``: every template, the queueing lab stamped with every schema version and with
  the versions refused, an invalid design and workload, and files it cannot use: one that is not
  JSON, one that is missing, a JSON array, an empty object.
- ``analyze``: every template and the first scenario of each that has one, an unknown scenario, an
  invalid design, a small design, and the files it cannot use.
- ``catalog`` and ``schema``.

A command that crashed in Python (an uncaught exception, exit code 1 and a traceback) is recorded
with ``crash``, the exception's type and message, instead of its stderr: the Java CLI answers
it with a message of its own.

Usage:
    uv run python scripts/export_cli_cases.py           # write the cases
    uv run python scripts/export_cli_cases.py --check   # exit 1 if a fresh export differs
"""

from __future__ import annotations

import argparse
import contextlib
import copy
import gzip
import io
import json
import sys
import tempfile
from pathlib import Path
from typing import Any

from export_simulation_cases import CASES, busy_hour, clients, design, external, faster_api, link

from archsim_engine import cli
from archsim_engine.version import SCHEMA_VERSION

FOLDER = Path(__file__).resolve().parent.parent / "archsim-cli/src/test/resources/io/archsim/cli"
EXAMPLES = Path(__file__).resolve().parent.parent / "examples"
GENERATOR = "scripts/export_cli_cases.py (archsim_engine 0.16.0)"
FILE = "{design}"
"""Stands for the design's file in the recorded arguments and text."""
REPORT = "{report}"
"""Stands for the report's file."""
MISSING = "{missing}"
"""Stands for a file that does not exist."""
NOT_JSON = "not JSON"
"""A design file's text that is not JSON."""
Json = dict[str, Any]

QUICK = faster_api(
    design(
        [clients(), external(latencyMs={"kind": "constant", "value": 25})],
        [link("clients", "api")],
        {"rps": 5000, "arrivalProcess": "deterministic", "warmupS": 2, "durationS": 30},
    )
)
INVALID = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "nowhere")],
    {"rps": 100},
)
NO_FAILURES = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20}, errorRate=0)],
    [link("clients", "api")],
    {"rps": 100, "arrivalProcess": "deterministic"},
)
EVERY_CALL_FAILS = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20}, errorRate=1)],
    [link("clients", "api")],
    {"rps": 100, "arrivalProcess": "deterministic"},
)
INVALID_WORKLOAD = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "api")],
    {"rps": -1},
)
NO_WORKLOAD = {key: value for key, value in NO_FAILURES.items() if key != "workload"}
TOO_BIG = design(
    [clients(), external(latencyMs={"kind": "constant", "value": 20})],
    [link("clients", "api")],
    {"rps": 50000, "durationS": 60},
)

RUNS: list[Json] = [
    {"name": "aDesignRun", "design": CASES["constantRateToAnApi"], "args": ["run", FILE]},
    {"name": "aRunWithoutFailures", "design": NO_FAILURES, "args": ["run", FILE]},
    {"name": "aRunWhoseEveryCallFails", "design": EVERY_CALL_FAILS, "args": ["run", FILE]},
    {
        # the CLI's seed is the design's: a scenario that sets its own seed wins
        "name": "aScenarioWithASeedOfItsOwn",
        "design": busy_hour(copy.deepcopy(CASES["constantRateToAnApi"])),
        "args": ["run", FILE, "--scenario", "busy", "--seed", "5"],
    },
    {
        "name": "aRunWithASeed",
        "design": CASES["poissonToAnApiThatFails"],
        "args": ["run", FILE, "--seed", "5"],
    },
    # the CLI's seed makes the workload of a design without one
    {
        "name": "aSeedForADesignWithoutAWorkload",
        "design": NO_WORKLOAD,
        "args": ["run", FILE, "--seed", "5"],
    },
    {
        "name": "aQuickCheck",
        "design": QUICK,
        "args": ["run", FILE, "--sampled", "--scenario", "faster"],
    },
    {
        "name": "aReportWrittenToAFile",
        "design": CASES["twoClientGroups"],
        "args": ["run", FILE, "--out", REPORT],
    },
    {"name": "anInvalidDesign", "design": INVALID, "args": ["run", FILE]},
    {"name": "anInvalidWorkload", "design": INVALID_WORKLOAD, "args": ["run", FILE]},
    {"name": "aRunOverTheRequestLimit", "design": TOO_BIG, "args": ["run", FILE]},
]


def template(name: str) -> Json:
    data: Json = json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))
    return data


TEMPLATES = sorted(path.stem for path in EXAMPLES.glob("*.json"))
QUEUEING_LAB = template("queueing-lab")


def versions() -> list[Json]:
    """The queueing lab stamped with every schema version, and with the versions refused."""
    current = int(SCHEMA_VERSION.split(".")[1])
    stamped = {f"1.{minor}": f"1.{minor}" for minor in range(current + 1)} | {
        "2.0": "2.0",
        "aNumber": 1.3,
        "notAVersion": "one",
    }
    cases = [
        {
            "name": f"schemaVersion {name}",
            "design": {**QUEUEING_LAB, "schemaVersion": version},
            "args": ["validate", FILE],
        }
        for name, version in stamped.items()
    ]
    without = {key: value for key, value in QUEUEING_LAB.items() if key != "schemaVersion"}
    return [*cases, {"name": "noSchemaVersion", "design": without, "args": ["validate", FILE]}]


def unreadable(command: str) -> list[Json]:
    """Files a command cannot use: not JSON, missing, a JSON array, an empty object."""
    return [
        {"name": "aFileThatIsNotJson", "text": NOT_JSON, "args": [command, FILE]},
        {"name": "aMissingFile", "args": [command, MISSING]},
        {"name": "aJsonArray", "design": [QUEUEING_LAB], "args": [command, FILE]},
        {"name": "anEmptyObject", "design": {}, "args": [command, FILE]},
    ]


VALIDATES: list[Json] = [
    *({"name": name, "design": template(name), "args": ["validate", FILE]} for name in TEMPLATES),
    *versions(),
    {"name": "anInvalidDesign", "design": INVALID, "args": ["validate", FILE]},
    {"name": "anInvalidWorkload", "design": INVALID_WORKLOAD, "args": ["validate", FILE]},
    *unreadable("validate"),
]


def scenarios() -> list[Json]:
    """The first scenario of every template that has one."""
    return [
        {
            "name": f"{name} {doc['scenarios'][0]['id']}",
            "design": doc,
            "args": ["analyze", FILE, "--scenario", doc["scenarios"][0]["id"]],
        }
        for name in TEMPLATES
        if (doc := template(name)).get("scenarios")
    ]


ANALYZES: list[Json] = [
    *({"name": name, "design": template(name), "args": ["analyze", FILE]} for name in TEMPLATES),
    *scenarios(),
    {
        "name": "anUnknownScenario",
        "design": QUEUEING_LAB,
        "args": ["analyze", FILE, "--scenario", "nowhere"],
    },
    {"name": "anInvalidDesign", "design": INVALID, "args": ["analyze", FILE]},
    {"name": "aDesignRun", "design": CASES["constantRateToAnApi"], "args": ["analyze", FILE]},
    *unreadable("analyze"),
]

COMMANDS: dict[str, list[Json]] = {
    "run": RUNS,
    "validate": VALIDATES,
    "analyze": ANALYZES,
    "catalog": [{"name": "theCatalog", "args": ["catalog"]}],
    "schema": [{"name": "theSchema", "args": ["schema"]}],
}


def run(case: Json, folder: Path) -> Json:
    path = folder / f"{case['name']}.json"
    if "design" in case:
        path.write_text(json.dumps(case["design"]), encoding="utf-8")
    elif "text" in case:
        path.write_text(case["text"], encoding="utf-8")
    report_path = folder / f"{case['name']}-report.json"
    missing = folder / "missing.json"
    names = {FILE: str(path), REPORT: str(report_path), MISSING: str(missing)}
    args = [names.get(a, a) for a in case["args"]]
    out, err = io.StringIO(), io.StringIO()
    crash = None
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            code = cli.main(args)
        except Exception as exc:  # what Python left uncaught: a traceback, exit code 1
            code, crash = 1, f"{type(exc).__name__}: {exc}"
    recorded: Json = {**case, "exitCode": code, "stdout": out.getvalue()}
    if crash is None:
        recorded["stderr"] = err.getvalue()
    else:
        recorded["crash"] = crash
    for key in ("stdout", "stderr", "crash"):
        if key in recorded:
            recorded[key] = placeheld(recorded[key], names)
    if REPORT in case["args"]:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["provenance"]["wallSeconds"] = 0.0
        report["provenance"]["python"] = "normalized"
        report["provenance"]["simpy"] = "normalized"
        recorded["report"] = report
    return recorded


def placeheld(text: str, names: dict[str, str]) -> str:
    """``text`` with each file's path, as it is and as its repr writes it (an exception's
    message), replaced by its placeholder."""
    for placeholder, path in names.items():
        text = text.replace(repr(path)[1:-1], placeholder).replace(path, placeholder)
    return text


def export(command: str) -> bytes:
    with tempfile.TemporaryDirectory() as folder:
        cases = [run(case, Path(folder)) for case in COMMANDS[command]]
    text = json.dumps(
        {"generator": GENERATOR, "cases": cases}, sort_keys=True, separators=(",", ":")
    )
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    parser.add_argument("command", nargs="*", help="the commands to record (default: every one)")
    args = parser.parse_args(argv)
    differs = 0
    for command in args.command or COMMANDS:
        out = FOLDER / f"{command}-cases.json.gz"
        data = export(command)
        if not args.check:
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
            print(f"wrote {out}")
        elif out.exists() and gzip.decompress(out.read_bytes()) == gzip.decompress(data):
            print(f"{out.name} matches the frozen engine's CLI")
        else:
            print(f"{out.name} differs from a fresh export: run scripts/export_cli_cases.py")
            differs = 1
    return differs


if __name__ == "__main__":
    sys.exit(main())
