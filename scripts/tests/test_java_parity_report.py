"""Stage 4's parity report: the template cases by the bar, the kept calibrations' knees and the
speed, as docs/validation/java-parity.md shows them."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import java_parity_report as report
import pytest


def verdict(metric: str, low: float, high: float, median: float, java: float | None) -> dict:
    inside = java is not None and low <= java <= high
    distance = None if java is None else (0.0 if java == median else abs(java - median) / median)
    within = distance is not None and distance <= 0.02
    return {
        "metric": metric,
        "pythonLow": low,
        "pythonHigh": high,
        "pythonMedian": median,
        "javaMedian": java,
        "insideRange": inside,
        "withinTwoPercent": within,
        "distance": distance,
    }


def case(scenario: str, exact: int, *metrics: dict) -> dict:
    return {
        "template": "queueing-lab",
        "scenario": scenario,
        "runs": 20,
        "exact": exact,
        "digestsEqual": exact,
        "failures": {"python": 0, "java": 0, "mismatched": 0},
        "metrics": list(metrics),
        "meetsTheBar": all(m["insideRange"] and m["withinTwoPercent"] for m in metrics),
        "missed": [
            m["metric"] for m in metrics if not (m["insideRange"] and m["withinTwoPercent"])
        ],
    }


MET = case(
    "base", 20, verdict("p50", 9.0, 11.0, 10.0, 10.0), verdict("p95", 18.0, 22.0, 20.0, 20.0)
)
MISSED = case(
    "spike", 12, verdict("p50", 9.0, 11.0, 10.0, 10.0), verdict("p95", 18.0, 26.0, 20.0, 21.0)
)


def parity(*cases: dict) -> dict:
    metrics = [(c, m) for c in cases for m in c["metrics"]]
    worst_case, worst = max(
        metrics, key=lambda cm: float("inf") if cm[1]["distance"] is None else cm[1]["distance"]
    )
    return {
        "cases": len(cases),
        "casesMeetingTheBar": sum(
            all(m["insideRange"] and m["withinTwoPercent"] for m in c["metrics"]) for c in cases
        ),
        "casesInsideTheRange": sum(all(m["insideRange"] for m in c["metrics"]) for c in cases),
        "casesWithinTwoPercent": sum(
            all(m["withinTwoPercent"] for m in c["metrics"]) for c in cases
        ),
        "casesWithMismatchedFailures": sum(c["failures"]["mismatched"] > 0 for c in cases),
        "pythonRunsFailed": sum(c["failures"]["python"] for c in cases),
        "javaRunsFailed": sum(c["failures"]["java"] for c in cases),
        "casesReplayingExactly": sum(c["exact"] == c["runs"] for c in cases),
        "runs": sum(c["runs"] for c in cases),
        "exactReplays": sum(c["exact"] for c in cases),
        "digestsEqual": sum(c["digestsEqual"] for c in cases),
        "worst": {
            "template": worst_case["template"],
            "scenario": worst_case["scenario"],
            "verdict": worst,
        },
        "threads": 4,
        "javaSeconds": 3600.0,
        "results": list(cases),
    }


def test_totals_count_each_test_of_the_bar_and_the_exact_replays() -> None:
    text = report.render(parity(MET, MISSED), [], [])

    assert "**1 of 2** template cases meet the bar" in text
    assert (
        "2 of 2 inside Python's seed range, 1 of 2 within 2% of its median; 0 with a seed" in text
    )
    assert "**32 of 40** runs replay the frozen engine's exactly" in text


def knee_row(python: float, java: float) -> dict:
    return {
        "experiment": "pg-mixed",
        "calibration": "2026-10-26T08-23-18Z",
        "python": python,
        "java": java,
        "checked": True,
        "resimulated": True,
        "sameInputs": True,
        "withinTwoPercent": abs(java - python) <= 0.02 * python,
    }


def headline(text: str) -> str:
    return text.split("The formal criterion")[0]


def test_every_case_run_and_knee_matching_exactly_is_the_headline_above_the_formal_bar() -> None:
    text = report.render(parity(MET), [knee_row(1000.0, 1000.0)], [])

    assert (
        "**Every case, run and kept knee matches the frozen engine exactly.** All 20 runs of the "
        "1 template cases replay it figure for figure and digest for digest, and all 1 kept "
        "calibrations' knees are its knees." in headline(text)
    )


def test_knees_that_differ_within_the_bar_keep_the_headline_from_claiming_an_exact_match() -> None:
    text = report.render(parity(MET), [knee_row(1000.0, 1015.0)], [])

    assert (
        "**Stage 4's bar is met.** 1 of 1 template cases and 1 of 1 kept knees meet it; 20 of 20 "
        "runs replay the frozen engine exactly." in headline(text)
    )


def test_a_bar_not_met_is_the_headline_with_its_counts() -> None:
    text = report.render(parity(MET, MISSED), [knee_row(1000.0, 1000.0)], [])

    assert (
        "**Stage 4's bar is not met.** 1 of 2 template cases and 1 of 1 kept knees meet it; 32 of "
        "40 runs replay the frozen engine exactly." in headline(text)
    )


def test_a_knee_beyond_the_bar_is_a_bar_not_met_even_when_every_run_replays() -> None:
    text = report.render(parity(MET), [knee_row(1000.0, 1100.0)], [])

    assert "**Stage 4's bar is not met.** 1 of 1 template cases and 0 of 1 kept knees" in headline(
        text
    )


def test_no_knee_checked_is_no_bar_met() -> None:
    assert "**Stage 4's bar is not met.**" in headline(report.render(parity(MET), [], []))


def test_counts_of_runs_are_written_with_thousands_separators() -> None:
    whole = {**parity(MET), "runs": 2980, "exactReplays": 2980, "digestsEqual": 2980}

    text = report.render(whole, [knee_row(1000.0, 1000.0)], [])

    assert "All 2,980 runs" in text
    assert "**2,980 of 2,980** runs replay the frozen engine's exactly" in text
    assert "2,980 give its result digest" in text


def test_the_parity_run_gives_no_speed_figure() -> None:
    text = report.render(parity(MET, MISSED), [], [])

    assert (
        "- The Java runs: `./gradlew :archsim-engine:parity -Parchsim.parity.out=docs/validation` "
        "simulated the same runs (4 at a time) and wrote `simulation-parity.json`; the "
        "`javaSeconds` it records is not a speed figure." in text
    )


def test_a_case_that_misses_is_shown_with_each_number_it_missed_by() -> None:
    text = report.render(parity(MET, MISSED), [], [])

    section = text.split("## Cases that miss the bar")[1].split("\n## ")[0]
    assert "| queueing-lab | spike | p95 | 18 to 26 | 20 | 21 | yes | no (5.00%) |" in section


def test_no_case_missing_says_so() -> None:
    text = report.render(parity(MET), [], [])

    section = text.split("## Cases that miss the bar")[1].split("\n## ")[0]
    assert "None:" in section


def test_no_metric_off_says_so_instead_of_naming_one() -> None:
    text = report.render(parity(MET), [], [])

    assert "- No metric is off: every Java median is Python's." in text
    assert "The metric furthest" not in text


def test_a_case_with_no_metric_off_shows_none_off() -> None:
    text = report.render(parity(MET), [], [])

    assert "| queueing-lab | base | 20 | 20 | yes | none off | | | |" in text


def test_the_worst_metric_is_named_with_its_numbers() -> None:
    text = report.render(parity(MET, MISSED), [], [])

    assert (
        "queueing-lab `spike`, p95: Java's median 21 against Python's 20 (18 to 26), 5.00% off"
        in text
    )


def test_a_metric_no_java_run_measured_is_shown_as_unmeasured() -> None:
    unmeasured = case("cold", 0, verdict("utilization api", 0.4, 0.6, 0.5, None))

    text = report.render(parity(MET, unmeasured), [], [])

    assert (
        "| queueing-lab | cold | utilization api | 0.4 to 0.6 | 0.5 | not measured | no | no |"
        in text
    )


PYTHON_KNEES = report.PYTHON_KNEES
JAVA_KNEES = report.JAVA_KNEES
PYTHON_SPEED_COMMAND = "uv run python scripts/perf.py --label python-0.16.0 --repeats 5"
JAVA_SPEED_COMMAND = (
    "./gradlew :archsim-engine:speed -Parchsim.speed.out=benchmarks/perf/java-port.json "
    "-Parchsim.speed.repeats=5"
)


def check(
    dev: Path, engine: str, name: str, knee: float | None, inputs: dict | None = None
) -> None:
    path = dev / engine / "pg-mixed" / f"{name}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    inputs = {"serviceTimeMs": 2.0} if inputs is None else inputs
    path.write_text(
        json.dumps({"inputs": inputs, "comparison": {"predictedKnee": {"kneeRps": knee}}})
    )


def test_knees_pair_each_kept_calibration_by_its_file(tmp_path: Path) -> None:
    check(tmp_path, PYTHON_KNEES, "2026-10-26T08-23-18Z", 1000.0)
    check(tmp_path, JAVA_KNEES, "2026-10-26T08-23-18Z", 1015.0)

    rows = report.knees(tmp_path)

    assert rows == [
        {
            "experiment": "pg-mixed",
            "calibration": "2026-10-26T08-23-18Z",
            "python": 1000.0,
            "java": 1015.0,
            "checked": True,
            "resimulated": True,
            "sameInputs": True,
            "withinTwoPercent": True,
        }
    ]


def test_a_java_check_without_a_frozen_engine_one_is_listed_and_misses(tmp_path: Path) -> None:
    check(tmp_path, PYTHON_KNEES, "2026-10-26T08-23-18Z", 1000.0)
    check(tmp_path, JAVA_KNEES, "2026-10-26T08-23-18Z", 1000.0)
    check(tmp_path, JAVA_KNEES, "2026-11-03T10-00-00Z", 800.0)

    rows = report.knees(tmp_path)
    text = report.render(parity(MET), rows, [])

    assert [(row["calibration"], row["checked"], row["withinTwoPercent"]) for row in rows] == [
        ("2026-10-26T08-23-18Z", True, True),
        ("2026-11-03T10-00-00Z", False, False),
    ]
    assert "**1 of 2** kept calibrations" in text
    assert "| pg-mixed | 2026-11-03T10-00-00Z | no frozen-engine check | 800 |  | no |" in text


def test_a_calibration_java_did_not_resimulate_misses(tmp_path: Path) -> None:
    check(tmp_path, PYTHON_KNEES, "2026-10-26T12-22-50Z", 500.0)

    row = report.knees(tmp_path)[0]

    assert (row["java"], row["resimulated"], row["sameInputs"], row["withinTwoPercent"]) == (
        None,
        False,
        None,
        False,
    )


def test_a_calibration_java_did_not_resimulate_misses_even_without_a_python_knee(
    tmp_path: Path,
) -> None:
    check(tmp_path, PYTHON_KNEES, "2026-10-26T12-22-50Z", None)

    assert report.knees(tmp_path)[0]["withinTwoPercent"] is False


def test_two_checks_that_found_no_knee_agree(tmp_path: Path) -> None:
    check(tmp_path, PYTHON_KNEES, "2026-10-26T12-22-50Z", None)
    check(tmp_path, JAVA_KNEES, "2026-10-26T12-22-50Z", None)

    assert report.knees(tmp_path)[0]["withinTwoPercent"] is True


def test_a_pair_whose_derived_inputs_differ_is_shown(tmp_path: Path) -> None:
    check(tmp_path, PYTHON_KNEES, "2026-10-26T08-23-18Z", 1000.0, {"serviceTimeMs": 2.0})
    check(tmp_path, JAVA_KNEES, "2026-10-26T08-23-18Z", 1000.0, {"serviceTimeMs": 2.1})

    rows = report.knees(tmp_path)
    text = report.render(parity(MET), rows, [])

    assert rows[0]["sameInputs"] is False
    assert "1 with other derived inputs than Python's" in text
    assert "| pg-mixed | 2026-10-26T08-23-18Z | 1,000 | 1,000 | no | yes |" in text


def test_a_knee_two_percent_off_is_within_and_one_further_is_not(tmp_path: Path) -> None:
    for name, java in (("2026-10-26T08-23-18Z", 1020.0), ("2026-10-27T08-23-18Z", 1020.5)):
        check(tmp_path, PYTHON_KNEES, name, 1000.0)
        check(tmp_path, JAVA_KNEES, name, java)

    assert [row["withinTwoPercent"] for row in report.knees(tmp_path)] == [True, False]


def timed(walls: list[float], digest: str = "d") -> dict:
    return {
        "templates": [
            {
                "template": "queueing-lab",
                "wallSeconds": walls,
                "bestWallSeconds": min(walls),
                "requests": 24154,
                "resultDigest": digest,
            }
        ]
    }


def test_speed_compares_each_templates_median_runs() -> None:
    rows = report.speed(timed([1.6, 1.5, 1.4, 2.5, 1.5]), timed([0.16, 0.15, 0.3, 0.14, 0.15]))

    assert rows == [
        {
            "template": "queueing-lab",
            "requests": 24154,
            "runs": [5, 5],
            "python": 1.5,
            "java": 0.15,
            "speedUp": 10.0,
            "sameResult": True,
        }
    ]


def test_speed_takes_the_middle_two_of_an_even_count() -> None:
    rows = report.speed(timed([1.0, 2.0]), timed([0.1, 0.3]))

    assert (rows[0]["python"], rows[0]["java"]) == (1.5, 0.2)


def test_speed_page_says_how_many_runs_and_that_strictmath_is_included() -> None:
    text = report.render(parity(MET), [], report.speed(timed([1.5, 1.5, 1.5]), timed([0.15] * 3)))

    assert "the median of 3 runs on each engine" in text
    assert "include StrictMath's cost" in text
    assert "| queueing-lab | 24,154 | 1.50 s | 0.15 s | x10.0 | yes |" in text
    assert (
        f"- The speed: `{PYTHON_SPEED_COMMAND}`, then `{JAVA_SPEED_COMMAND}`, one after the "
        "other with no other heavy work running." in text
    )


def test_speed_page_points_to_performance_for_how_much_the_runs_spread() -> None:
    text = report.render(parity(MET), [], report.speed(timed([1.5, 1.5, 1.5]), timed([0.15] * 3)))

    assert "[docs/performance.md](../performance.md) says how much the runs spread" in text


def test_speed_measured_with_no_template_timed_says_so() -> None:
    text = report.render(parity(MET), [], [])

    assert "No template was timed." in text.split("## Speed")[1].split("\n## ")[0]
    assert "- The speed:" not in text


def test_speed_shows_a_template_java_did_not_time() -> None:
    rows = report.speed(timed([1.5]), {"templates": []})

    assert rows[0]["java"] is None
    assert "| queueing-lab | 24,154 | 1.50 s | not measured | not measured | no |" in report.render(
        parity(MET), [], rows
    )


def test_speed_not_yet_measured_says_so_with_the_commands_that_measure_it() -> None:
    text = report.render(parity(MET), [], None)

    section = text.split("## Speed")[1].split("\n## ")[0]
    assert "Not yet measured" in section
    assert "a quiet machine" in section
    assert (
        f"`{PYTHON_SPEED_COMMAND}`, then `{JAVA_SPEED_COMMAND}`, with no other heavy work"
        in section
    )
    assert "not part of the bar" in section
    assert "- The speed:" not in text


def test_a_case_whose_runs_failed_otherwise_than_pythons_is_listed_in_its_own_words() -> None:
    failing = case("cold", 0, verdict("p50", 9.0, 11.0, 10.0, 10.0))
    failing["failures"] = {"python": 0, "java": 3, "mismatched": 3}
    failing["meetsTheBar"] = False
    failing["missed"] = ["3 seeds failed otherwise than Python's (Java failed 3, Python 0)"]

    text = report.render(parity(MET, failing), [], [])

    section = text.split("## Cases that miss the bar")[1].split("\n## ")[0]
    assert (
        "- queueing-lab `cold`: 3 seeds failed otherwise than Python's (Java failed 3, Python 0)"
        in section
    )
    assert "| queueing-lab | cold |" not in section


def test_a_report_with_no_metric_measured_says_so() -> None:
    unmeasured = parity(MET)
    del unmeasured["worst"]

    assert "- No metric was measured in any case." in report.render(unmeasured, [], [])


def _inputs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    page = tmp_path / "java-parity.md"
    (tmp_path / "parity.json").write_text(json.dumps(parity(MET)), encoding="utf-8")
    (tmp_path / "speed.json").write_text(json.dumps({"templates": []}), encoding="utf-8")
    monkeypatch.setattr(report, "PAGE", page)
    monkeypatch.setattr(report, "PARITY", tmp_path / "parity.json")
    monkeypatch.setattr(report, "DEV", tmp_path / "dev")
    check(tmp_path / "dev", PYTHON_KNEES, "2026-10-26T08-23-18Z", 1000.0)
    check(tmp_path / "dev", JAVA_KNEES, "2026-10-26T08-23-18Z", 1000.0)
    monkeypatch.setattr(report, "PYTHON_SPEED", tmp_path / "speed.json")
    monkeypatch.setattr(report, "JAVA_SPEED", tmp_path / "speed.json")
    return page


def test_check_passes_on_the_page_just_written_and_fails_once_it_changes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    page = _inputs(tmp_path, monkeypatch)

    assert report.main([]) == 0
    assert report.main(["--check"]) == 0
    page.write_text(page.read_text(encoding="utf-8") + "edited\n", encoding="utf-8")
    assert report.main(["--check"]) == 1


def test_a_page_with_speed_not_yet_measured_is_written_and_passes_the_check(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    page = _inputs(tmp_path, monkeypatch)
    monkeypatch.setattr(report, "PYTHON_SPEED", tmp_path / "python-0.16.0.json")
    monkeypatch.setattr(report, "JAVA_SPEED", tmp_path / "java-port.json")

    assert report.main([]) == 0
    assert report.main(["--check"]) == 0
    assert "Not yet measured" in page.read_text(encoding="utf-8")


@pytest.mark.parametrize(
    ("engine", "name", "command"),
    [
        ("PYTHON_SPEED", "python-0.16.0.json", PYTHON_SPEED_COMMAND),
        ("JAVA_SPEED", "java-port.json", JAVA_SPEED_COMMAND),
    ],
)
def test_one_engines_speed_without_the_others_is_named_with_the_command_that_writes_it(
    engine: str,
    name: str,
    command: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    _inputs(tmp_path, monkeypatch)
    monkeypatch.setattr(report, engine, tmp_path / name)

    assert report.main([]) == 1
    assert f"{name} is missing: run {command}" in capsys.readouterr().err


def test_the_java_engines_missing_knees_are_restored_from_git(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    # the Java engine wrote them before it was 1.0.0; resimulate now writes dev/1.0.0/
    _inputs(tmp_path, monkeypatch)
    shutil.rmtree(tmp_path / "dev" / JAVA_KNEES)

    assert report.main([]) == 1
    assert (
        "0.16.0-java is missing: restore it with git checkout HEAD -- "
        "benchmarks/results/dev/0.16.0-java" in capsys.readouterr().err
    )


def test_the_frozen_engines_checks_are_named_with_the_labelled_run_that_writes_them(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _inputs(tmp_path, monkeypatch)
    shutil.rmtree(tmp_path / "dev" / PYTHON_KNEES)

    assert report.main([]) == 1
    assert (
        "0.16.0-port is missing: run BENCH_ENGINE=python uv run python benchmarks/run.py "
        "resimulate --label 0.16.0-port" in capsys.readouterr().err
    )
