"""Write what the frozen engine's ``archsim run`` printed, for the Java CLI, or check the committed file.

The Python CLI (``archsim_engine.cli``, engine 0.16.0) runs a design and prints a summary line, a
quick check's note, how far to trust the run, its findings and its digests; it refuses an invalid
design or one over the request limit. This script runs it on small designs (a run, one without
failures, one whose every call fails, a scenario with a seed of its own, a seed given, a seed for
a design without a workload, a quick check, a report written to a file, an invalid design, an
invalid workload and a run over the limit) and records the arguments, the exit code, what went
to stdout and stderr, and the report written. ``RunCommandTests`` runs ``archsim run`` in Java and compares the text exactly, the
report as the recorded simulation cases are compared.

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

OUT = (
    Path(__file__).resolve().parent.parent
    / "archsim-cli/src/test/resources/io/archsim/cli/run-cases.json.gz"
)
GENERATOR = "scripts/export_cli_cases.py (archsim_engine 0.16.0)"
FILE = "{design}"
"""Stands for the design's file in the recorded arguments and text."""
REPORT = "{report}"
"""Stands for the report's file."""
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


def run(case: Json, folder: Path) -> Json:
    path = folder / f"{case['name']}.json"
    path.write_text(json.dumps(case["design"]), encoding="utf-8")
    report_path = folder / f"{case['name']}-report.json"
    args = [
        str(path) if a == FILE else str(report_path) if a == REPORT else a for a in case["args"]
    ]
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = cli.main(args)
    recorded: Json = {
        **case,
        "exitCode": code,
        "stdout": placeheld(out.getvalue(), path, report_path),
        "stderr": placeheld(err.getvalue(), path, report_path),
    }
    if REPORT in case["args"]:
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["provenance"]["wallSeconds"] = 0.0
        report["provenance"]["python"] = "normalized"
        report["provenance"]["simpy"] = "normalized"
        recorded["report"] = report
    return recorded


def placeheld(text: str, design_path: Path, report_path: Path) -> str:
    return text.replace(str(report_path), REPORT).replace(str(design_path), FILE)


def export() -> bytes:
    with tempfile.TemporaryDirectory() as folder:
        cases = [run(case, Path(folder)) for case in RUNS]
    text = json.dumps(
        {"generator": GENERATOR, "cases": cases}, sort_keys=True, separators=(",", ":")
    )
    return gzip.compress(text.encode(), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--check", action="store_true", help="exit 1 if a fresh export differs")
    args = parser.parse_args(argv)
    data = export()
    if args.check:
        if not OUT.exists() or gzip.decompress(OUT.read_bytes()) != gzip.decompress(data):
            print(f"{OUT.name} differs from a fresh export: run scripts/export_cli_cases.py")
            return 1
        print(f"{OUT.name} matches the frozen engine's CLI")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(data)
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
