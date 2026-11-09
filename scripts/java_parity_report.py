"""Write docs/validation/java-parity.md, stage 4's parity report (docs/proposals/java-25-port.md):
the Java simulator against the frozen engine 0.16.0, by the port plan's bar.

- Every template case, 20 seeds each: Java's median p50, p95, throughput and each part's
  utilization inside Python's seed range and within 2% of its median. From
  docs/validation/simulation-parity.json, which `./gradlew :archsim-engine:parity
  -Parchsim.parity.out=docs/validation` writes from the baselines (ADR-0052).
- Every kept calibration: the re-simulated knee within 2% of the frozen engine's, both from
  the same harness over every kept run with a two-pass calibration: the frozen engine's
  development checks in benchmarks/results/dev/0.16.0-port/ (`uv run python benchmarks/run.py
  resimulate --label 0.16.0-port`) against the Java engine's in dev/0.16.0-java/
  (`BENCH_ENGINE=java uv run python benchmarks/run.py resimulate`), paired by file over both
  folders, with whether their derived inputs agree.
- Speed, against docs/performance.md and not part of the bar: each template's median wall time
  over several runs on each engine, measured one after the other on a quiet machine, from
  benchmarks/perf/. While neither engine's measurement exists, the page says speed is not yet
  measured; with only one, the script fails and names the other with its command.

    uv run python scripts/java_parity_report.py           # write the page
    uv run python scripts/java_parity_report.py --check   # fail if the page is stale
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
PAGE = ROOT / "docs" / "validation" / "java-parity.md"
PARITY = ROOT / "docs" / "validation" / "simulation-parity.json"
DEV = ROOT / "benchmarks" / "results" / "dev"
PYTHON_ENGINE = "0.16.0"
PYTHON_KNEES = "0.16.0-port"
"""The frozen engine's development checks of every kept run from the harness the Java engine's
are made with: dev/0.16.0/ predates the spread anchor every check now derives (7 of its 38
knees differ), and dev/0.16.0-w29/ the held-out runs."""
JAVA_KNEES = "0.16.0-java"
"""The Java engine's, as `BENCH_ENGINE=java uv run python benchmarks/run.py resimulate` names
them."""
REPEATS = 5
"""How many times each engine runs each template for the speed comparison."""
PYTHON_SPEED_LABEL = f"python-{PYTHON_ENGINE}"
"""The frozen engine's speed, as `scripts/perf.py --label` names its file in benchmarks/perf/."""
JAVA_SPEED_FILE = "benchmarks/perf/java-port.json"
"""The Java engine's speed, where `./gradlew :archsim-engine:speed` is told to write it."""
PYTHON_SPEED = ROOT / "benchmarks" / "perf" / f"{PYTHON_SPEED_LABEL}.json"
JAVA_SPEED = ROOT / JAVA_SPEED_FILE
PARITY_COMMAND = "./gradlew :archsim-engine:parity -Parchsim.parity.out=docs/validation"
"""Writes simulation-parity.json from the baselines."""
PYTHON_KNEES_COMMAND = f"uv run python benchmarks/run.py resimulate --label {PYTHON_KNEES}"
"""Writes the frozen engine's knees, dev/0.16.0-port/."""
JAVA_KNEES_COMMAND = "BENCH_ENGINE=java uv run python benchmarks/run.py resimulate"
"""Writes the Java engine's knees, dev/0.16.0-java/."""
PYTHON_SPEED_COMMAND = (
    f"uv run python scripts/perf.py --label {PYTHON_SPEED_LABEL} --repeats {REPEATS}"
)
"""Writes the frozen engine's speed."""
JAVA_SPEED_COMMAND = (
    f"./gradlew :archsim-engine:speed -Parchsim.speed.out={JAVA_SPEED_FILE} "
    f"-Parchsim.speed.repeats={REPEATS}"
)
"""Writes the Java engine's speed."""
WITHIN = 0.02
"""How far a Java figure may be from the Python one, as a share of it: the port plan's 2%."""

Json = dict[str, Any]


def knees(dev: Path, python: str | None = None, java: str | None = None) -> list[Json]:
    """Each kept calibration's re-simulated knee on both engines, paired by the check's file over
    both folders, with whether each engine checked it and whether the two checks derived the
    same inputs. A calibration only one engine checked misses."""
    python_dir = dev / (python or PYTHON_KNEES)
    java_dir = dev / (java or JAVA_KNEES)
    names = {path.relative_to(python_dir) for path in python_dir.rglob("*.json")}
    names |= {path.relative_to(java_dir) for path in java_dir.rglob("*.json")}
    rows = []
    for relative in sorted(names):
        python_check = _check(python_dir / relative)
        java_check = _check(java_dir / relative)
        both = python_check is not None and java_check is not None
        python_knee = None if python_check is None else _knee(python_check)
        java_knee = None if java_check is None else _knee(java_check)
        rows.append(
            {
                "experiment": relative.parent.as_posix(),
                "calibration": relative.stem,
                "python": python_knee,
                "java": java_knee,
                "checked": python_check is not None,
                "resimulated": java_check is not None,
                "sameInputs": (python_check or {}).get("inputs") == (java_check or {}).get("inputs")
                if both
                else None,
                "withinTwoPercent": both and _within(python_knee, java_knee),
            }
        )
    return rows


def _check(path: Path) -> Json | None:
    return _load(path) if path.exists() else None


def speed(python: Mapping[str, Any], java: Mapping[str, Any]) -> list[Json]:
    """Each template's median wall time on both engines, over every run each timed, and whether
    they gave the same result; a template the Java run did not time is kept, unmeasured."""
    java_rows = {row["template"]: row for row in java["templates"]}
    rows = []
    for row in python["templates"]:
        other = java_rows.get(row["template"])
        python_median = statistics.median(row["wallSeconds"])
        java_median = None if other is None else statistics.median(other["wallSeconds"])
        rows.append(
            {
                "template": row["template"],
                "requests": row["requests"],
                "runs": [
                    len(row["wallSeconds"]),
                    0 if other is None else len(other["wallSeconds"]),
                ],
                "python": python_median,
                "java": java_median,
                "speedUp": None if not java_median else round(python_median / java_median, 1),
                "sameResult": other is not None and row["resultDigest"] == other["resultDigest"],
            }
        )
    return rows


def render(
    parity: Mapping[str, Any], knee_rows: Sequence[Json], speed_rows: Sequence[Json] | None
) -> str:
    """The page; ``speed_rows`` is None while speed is not yet measured."""
    speed_method = (
        [
            f"- The speed: `{PYTHON_SPEED_COMMAND}`, then `{JAVA_SPEED_COMMAND}`, one after the "
            "other with nothing else running."
        ]
        if speed_rows
        else []
    )
    lines = [
        "# Java parity: the simulator against the frozen engine",
        "",
        _headline(parity, knee_rows),
        "",
        "Generated by `scripts/java_parity_report.py`. The formal criterion, stage 4's bar "
        "([the port plan](../proposals/java-25-port.md)): for every template case, with 20 seeds "
        "each, the Java simulator's median p50, p95, throughput and each part's utilization fall "
        "inside the frozen engine 0.16.0's seed range and within 2% of its median; every kept "
        "calibration's re-simulated knee is within 2% of the frozen engine's.",
        "",
        *_summary(parity),
        "",
        "## Cases that miss the bar",
        "",
        *_misses(parity["results"]),
        "",
        "## Kept calibrations' knees",
        "",
        *_knees(knee_rows),
        "",
        "## Speed",
        "",
        *_speed(speed_rows),
        "",
        "## Each case",
        "",
        *_cases(parity["results"]),
        "",
        "## How it was measured",
        "",
        "- The baselines: `scripts/export_simulation_baselines.py` simulated every template case "
        "in full on the frozen engine, with the workload's seed and the 19 after it "
        "([ADR-0052](../adr/0052-record-the-frozen-engines-simulation-baselines-once.md)).",
        f"- The Java runs: `{PARITY_COMMAND}` simulated the same runs ({parity['threads']} at a "
        "time) and wrote `simulation-parity.json`; the `javaSeconds` it records is not a speed "
        "figure.",
        f"- The knees: `{JAVA_KNEES_COMMAND}` wrote `benchmarks/results/dev/{JAVA_KNEES}/`, and "
        f"the same harness on the frozen engine, `{PYTHON_KNEES_COMMAND}`, "
        f"`benchmarks/results/dev/{PYTHON_KNEES}/`, each over every kept run with a two-pass "
        "calibration, the only kind with a knee to re-simulate "
        f"(`dev/{PYTHON_ENGINE}/` predates the spread anchor every check now derives, "
        f"`dev/{PYTHON_ENGINE}-w29/` the held-out runs).",
        *speed_method,
        "",
    ]
    return "\n".join(lines)


def _headline(parity: Mapping[str, Any], knee_rows: Sequence[Json]) -> str:
    runs, cases, exact = parity["runs"], parity["cases"], parity["exactReplays"]
    meeting = parity["casesMeetingTheBar"]
    knees_within = sum(row["withinTwoPercent"] for row in knee_rows)
    bar_met = meeting == cases and bool(knee_rows) and knees_within == len(knee_rows)
    replayed = exact == runs and parity["digestsEqual"] == runs
    if bar_met and replayed and all(_same_knee(row) for row in knee_rows):
        return (
            "**Every case, run and kept knee matches the frozen engine exactly.** All "
            f"{runs:,} runs of the {cases} template cases replay it figure for figure and digest "
            f"for digest, and all {len(knee_rows)} kept calibrations' knees are its knees."
        )
    return (
        f"**Stage 4's bar is {'met' if bar_met else 'not met'}.** {meeting} of {cases} template "
        f"cases and {knees_within} of {len(knee_rows)} kept knees meet it; {exact:,} of {runs:,} "
        "runs replay the frozen engine exactly."
    )


def _same_knee(row: Json) -> bool:
    return row["checked"] and row["resimulated"] and row["java"] == row["python"]


def _summary(parity: Mapping[str, Any]) -> list[str]:
    cases = parity["cases"]
    return [
        f"- **{parity['casesMeetingTheBar']} of {cases}** template cases meet the bar: "
        f"{parity['casesInsideTheRange']} of {cases} inside Python's seed range, "
        f"{parity['casesWithinTwoPercent']} of {cases} within 2% of its median; "
        f"{parity['casesWithMismatchedFailures']} with a seed failing otherwise than Python's "
        f"(runs failed: Python {parity['pythonRunsFailed']}, Java {parity['javaRunsFailed']}).",
        f"- **{parity['exactReplays']:,} of {parity['runs']:,}** runs replay the frozen engine's "
        f"exactly, every figure kept; {parity['digestsEqual']:,} give its result digest, and "
        f"{parity['casesReplayingExactly']} of {cases} cases replay in every seed.",
        _worst(parity.get("worst")),
    ]


def _worst(worst: Mapping[str, Any] | None) -> str:
    if worst is None:
        return "- No metric was measured in any case."
    metric = worst["verdict"]
    if metric["distance"] == 0:
        return "- No metric is off: every Java median is Python's."
    return (
        f"- The metric furthest from Python's: {worst['template']} `{worst['scenario']}`, "
        f"{metric['metric']}: Java's median {_java(metric)} against Python's "
        f"{_number(metric['pythonMedian'])} ({_range(metric)}), {_off(metric)}."
    )


def _misses(results: Sequence[Mapping[str, Any]]) -> list[str]:
    missing = [result for result in results if not result["meetsTheBar"]]
    if not missing:
        return [
            "None: every metric of every case is inside Python's range and within 2% of its "
            "median, and no seed failed otherwise than Python's."
        ]
    rows = [
        f"| {result['template']} | {result['scenario']} | {_metric_cells(metric)} |"
        for result in missing
        for metric in result["metrics"]
        if metric["metric"] in result["missed"]
    ]
    failures = [
        f"- {result['template']} `{result['scenario']}`: {miss}"
        for result in missing
        for miss in result["missed"]
        if miss not in {metric["metric"] for metric in result["metrics"]}
    ]
    lines = []
    if rows:
        lines += [
            "| Template | Case | Metric | Python's range | Python's median | Java's median | "
            "Inside the range | Within 2% |",
            "| --- | --- | --- | --- | --- | --- | --- | --- |",
            *rows,
        ]
    if failures:
        lines += [
            *([""] if lines else []),
            "Runs that failed otherwise than Python's:",
            "",
            *failures,
        ]
    return lines


def _knees(rows: Sequence[Json]) -> list[str]:
    if not rows:
        return ["None re-simulated."]
    within = sum(row["withinTwoPercent"] for row in rows)
    same = sum(_same_knee(row) for row in rows)
    checked = sum(row["checked"] for row in rows)
    resimulated = sum(row["resimulated"] for row in rows)
    other_inputs = sum(row["sameInputs"] is False for row in rows)
    return [
        f"**{within} of {len(rows)}** kept calibrations' re-simulated knees are within 2% of the "
        f"frozen engine's, {same} the same; {checked} of {len(rows)} checked on the frozen "
        f"engine, {resimulated} of {len(rows)} on Java, {other_inputs} with other derived inputs "
        "than Python's.",
        "",
        "| Experiment | Calibration | Python's knee (req/s) | Java's knee (req/s) | Same inputs | "
        "Within 2% |",
        "| --- | --- | --- | --- | --- | --- |",
        *(
            f"| {row['experiment']} | {row['calibration']} | "
            f"{_number(row['python']) if row['checked'] else 'no frozen-engine check'} | "
            f"{_number(row['java']) if row['resimulated'] else 'not re-simulated'} | "
            f"{'' if row['sameInputs'] is None else _yes(row['sameInputs'])} | "
            f"{_yes(row['withinTwoPercent'])} |"
            for row in rows
        ),
    ]


def _speed(rows: Sequence[Json] | None) -> list[str]:
    if rows is None:
        return [
            "Not yet measured. Speed is compared with [docs/performance.md](../performance.md) "
            "and is not part of the bar; it waits for a quiet machine, since other work skews "
            f"wall-clock times. `{PYTHON_SPEED_COMMAND}`, then `{JAVA_SPEED_COMMAND}`, with nothing "
            "else running, write its inputs; this section then gives each template's median wall "
            "time on both engines."
        ]
    if not rows:
        return ["No template was timed."]
    return [
        f"Each template as shipped (no scenarios): the median of {_runs(rows)} on each engine, "
        "measured one engine after the other on the same machine with nothing else running. The "
        "Java runs follow a warm-up run in the same JVM and include StrictMath's cost: the "
        "simulator takes its logarithms, exponentials and powers from `StrictMath`, so it draws "
        "Python's random numbers on every platform "
        "([ADR-0047](../adr/0047-draw-python-random-streams-in-java.md)).",
        "",
        f"| Template | Requests | Python {PYTHON_ENGINE} | Java | Speed-up | Same result |",
        "| --- | --- | --- | --- | --- | --- |",
        *(
            f"| {row['template']} | {row['requests']:,} | {row['python']:.2f} s | "
            f"{_seconds(row['java'])} | {_times(row['speedUp'])} | {_yes(row['sameResult'])} |"
            for row in rows
        ),
    ]


def _runs(rows: Sequence[Json]) -> str:
    counts = sorted({count for row in rows for count in row["runs"] if count})
    if len(counts) == 1:
        return f"{counts[0]} runs"
    return f"each template's runs ({counts[0]} to {counts[-1]})"


def _cases(results: Sequence[Mapping[str, Any]]) -> list[str]:
    lines = [
        "| Template | Case | Runs | Exact replays | Meets the bar | Furthest metric | "
        "Python's median | Java's median | Off by |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for result in results:
        lines.append(
            f"| {result['template']} | {result['scenario']} | {result['runs']} | "
            f"{result['exact']} | {_yes(result['meetsTheBar'])} | {_furthest(result['metrics'])} |"
        )
    return lines


def _furthest(metrics: Sequence[Mapping[str, Any]]) -> str:
    """A case's metric furthest from Python's, as the last four cells of its row."""
    if not metrics:
        return "none measured | | |"
    metric = max(metrics, key=_distance)
    if _distance(metric) == 0:
        return "none off | | |"
    return (
        f"{metric['metric']} | {_number(metric['pythonMedian'])} | {_java(metric)} | "
        f"{_percent(metric['distance'])}"
    )


def _metric_cells(metric: Mapping[str, Any]) -> str:
    within = _yes(metric["withinTwoPercent"])
    if not metric["withinTwoPercent"] and metric["distance"] is not None:
        within += f" ({_percent(metric['distance'])})"
    return (
        f"{metric['metric']} | {_range(metric)} | {_number(metric['pythonMedian'])} | "
        f"{_java(metric)} | {_yes(metric['insideRange'])} | {within}"
    )


def _knee(check: Mapping[str, Any]) -> float | None:
    value = check["comparison"]["predictedKnee"].get("kneeRps")
    return None if value is None else float(value)


# two knees within 2%, or two checks that found none
def _within(python: float | None, java: float | None) -> bool:
    if python is None or java is None:
        return python is None and java is None
    return abs(java - python) <= WITHIN * abs(python)


def _distance(metric: Mapping[str, Any]) -> float:
    return float("inf") if metric["distance"] is None else float(metric["distance"])


def _range(metric: Mapping[str, Any]) -> str:
    return f"{_number(metric['pythonLow'])} to {_number(metric['pythonHigh'])}"


def _java(metric: Mapping[str, Any]) -> str:
    return "not measured" if metric["javaMedian"] is None else _number(metric["javaMedian"])


def _off(metric: Mapping[str, Any]) -> str:
    return "not measured" if metric["distance"] is None else f"{_percent(metric['distance'])} off"


def _percent(share: float | None) -> str:
    return "not measured" if share is None else f"{share * 100:.2f}%"


def _number(value: float | None) -> str:
    if value is None:
        return "none"
    return f"{value:,.4f}".rstrip("0").rstrip(".")


def _seconds(value: float | None) -> str:
    return "not measured" if value is None else f"{value:.2f} s"


def _times(value: float | None) -> str:
    return "not measured" if value is None else f"x{value}"


def _yes(value: bool) -> str:
    return "yes" if value else "no"


def _load(path: Path) -> Json:
    return json.loads(path.read_text(encoding="utf-8"))


def _missing() -> list[str]:
    """The inputs not there yet, each with the command that writes it. Speed is optional while
    neither engine is measured, never with one engine's measurement and not the other's."""
    inputs = {
        DEV / PYTHON_KNEES: PYTHON_KNEES_COMMAND,
        DEV / JAVA_KNEES: JAVA_KNEES_COMMAND,
        PARITY: PARITY_COMMAND,
    }
    if _speed_started():
        inputs |= {PYTHON_SPEED: PYTHON_SPEED_COMMAND, JAVA_SPEED: JAVA_SPEED_COMMAND}
    return [
        f"{path.name} is missing: run {command}"
        for path, command in inputs.items()
        if not path.exists()
    ]


def _speed_started() -> bool:
    """Whether either engine's speed is measured, so both must be (``_missing``)."""
    return PYTHON_SPEED.exists() or JAVA_SPEED.exists()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--check", action="store_true", help="fail if the page is stale")
    args = parser.parse_args(argv)
    missing = _missing()
    if missing:
        print("\n".join(missing), file=sys.stderr)
        return 1
    # past _missing, either engine's speed means both
    speed_rows = speed(_load(PYTHON_SPEED), _load(JAVA_SPEED)) if _speed_started() else None
    page = render(_load(PARITY), knees(DEV), speed_rows)
    if args.check:
        current = PAGE.read_text(encoding="utf-8") if PAGE.exists() else ""
        if current != page:
            print(f"{PAGE.name} is stale: run scripts/java_parity_report.py", file=sys.stderr)
            return 1
        return 0
    PAGE.write_text(page, encoding="utf-8", newline="\n")
    print(f"wrote {PAGE}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
