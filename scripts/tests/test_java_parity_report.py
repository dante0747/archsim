"""Stage 4's parity report: the template cases by the bar, the kept calibrations' knees and the
speed, as docs/validation/java-parity.md shows them."""

from __future__ import annotations

import json
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


def test_a_case_that_misses_is_shown_with_each_number_it_missed_by() -> None:
    text = report.render(parity(MET, MISSED), [], [])

    section = text.split("## Cases that miss the bar")[1].split("\n## ")[0]
    assert "| queueing-lab | spike | p95 | 18 to 26 | 20 | 21 | yes | no (5.00%) |" in section


def test_no_case_missing_says_so() -> None:
    text = report.render(parity(MET), [], [])

    section = text.split("## Cases that miss the bar")[1].split("\n## ")[0]
    assert "None:" in section


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


def test_speed_shows_a_template_java_did_not_time() -> None:
    rows = report.speed(timed([1.5]), {"templates": []})

    assert rows[0]["java"] is None
    assert "| queueing-lab | 24,154 | 1.50 s | not measured | not measured | no |" in report.render(
        parity(MET), [], rows
    )


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


def test_a_missing_input_is_named_with_the_command_that_writes_it(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _inputs(tmp_path, monkeypatch)
    monkeypatch.setattr(report, "JAVA_SPEED", tmp_path / "java-port.json")

    assert report.main([]) == 1
    assert (
        "java-port.json is missing: run ./gradlew :archsim-engine:speed" in capsys.readouterr().err
    )


def test_a_missing_knee_folder_is_named_with_the_command_that_writes_it(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _inputs(tmp_path, monkeypatch)
    monkeypatch.setattr(report, "JAVA_KNEES", "0.16.0-java-absent")

    assert report.main([]) == 1
    assert (
        "0.16.0-java-absent is missing: run BENCH_ENGINE=java uv run python benchmarks/run.py "
        "resimulate" in capsys.readouterr().err
    )


def test_the_frozen_engines_checks_are_named_with_the_labelled_run_that_writes_them(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    _inputs(tmp_path, monkeypatch)
    monkeypatch.setattr(report, "PYTHON_KNEES", "0.16.0-absent")

    assert report.main([]) == 1
    assert (
        "0.16.0-absent is missing: run uv run python benchmarks/run.py resimulate --label "
        "0.16.0-absent" in capsys.readouterr().err
    )
