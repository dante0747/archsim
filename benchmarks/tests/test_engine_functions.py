"""The harness's calls of the engine's functions, answered by the Java engine's ``archsim
functions`` (ADR-0061): each gives the Python engine's result for the same call, in the shape
the harness used, and an input refused is a ValueError in the engine's words. The values are
the frozen Python engine's, which the golden files hold the Java engine to."""

from __future__ import annotations

import math
from collections.abc import Callable
from pathlib import Path
from typing import Any

import engine_functions
import pytest


def test_expected_ms_is_the_distributions_mean() -> None:
    mean = engine_functions.expected_ms({"kind": "lognormal", "median": 8, "p95": 25})

    assert mean == pytest.approx(10.169334708591919, rel=1e-12)


def test_machine_speeds_are_the_speed_at_each_busy_count() -> None:
    machine = {"cores": 4, "threadsPerCore": 2, "siblingSpeed": 0.5, "allCoreClock": 0.75}

    speeds = engine_functions.machine_speeds(machine, [1, 3, 6, 8])

    assert speeds == [1.0, 0.9285714285714286, 0.5476190476190477, 0.375]


def test_fit_load_curve_is_the_curve_with_its_table() -> None:
    points = [(50.0, 0.05), (100.0, 0.095), (200.0, 0.18), (300.0, 0.26), (400.0, 0.33)]

    curve = engine_functions.fit_load_curve(points, reference_ms=0.5, max_bins=len(points))

    assert curve is not None
    assert curve.reference_ms == 0.5
    assert curve.points[0] == pytest.approx((50.0, 2.0))
    assert curve.measured[4] == pytest.approx((400.0, 0.825))
    assert curve.table()[3] == {"rps": 300.0, "factor": 1.7333}


def test_fit_load_curve_that_finds_nothing_is_none() -> None:
    assert engine_functions.fit_load_curve([(50.0, 0.05)], max_bins=1) is None


def test_extend_load_curve_without_passes_is_none() -> None:
    assert engine_functions.extend_load_curve([], cores=1.0) is None


def test_extend_load_curve_is_the_extension_and_its_factors() -> None:
    passes = [(100.0, 2.0), (200.0, 1.8), (300.0, 1.6), (400.0, 1.45), (500.0, 1.3)]

    extension = engine_functions.extend_load_curve(passes, cores=1.0)

    assert extension is not None
    assert (extension.top_rps, extension.top_ms, extension.busy) == (500.0, 1.3, 0.65)
    assert extension.slope == pytest.approx(-0.5387345477052042, rel=1e-12)
    ((rate, factor),) = extension.factors()
    assert (rate, factor) == pytest.approx((1220.9668224507088, 0.8190230738561863 / 1.3))
    assert extension.reason.startswith("a fall of 0.539 ms per unit of ln(rate)")


def test_fit_memory_is_the_fit_against_what_it_was_given() -> None:
    fit = engine_functions.fit_memory([300.0, 320.0, 345.0, 370.0], rate=[0.0, 100.0, 200.0, 300.0])

    assert fit is not None
    assert (fit.baseline_mb, fit.per_rps_mb, fit.per_request_mb) == (298.5, 0.235, 0.01)
    assert fit.against == ("rate",)
    assert fit.predict(rate=100.0, held=10.0) == pytest.approx(298.5 + 23.5 + 0.1)


def test_a_point_that_is_not_a_number_is_left_out_of_the_fit() -> None:
    fit = engine_functions.fit_memory(
        [300.0, math.nan, 345.0, 370.0], rate=[0.0, 100.0, 200.0, 300.0]
    )

    assert fit is not None
    assert (fit.points, fit.baseline_mb, fit.per_rps_mb) == (3, 299.643, 0.232143)


def test_fit_memory_without_a_finite_point_is_none() -> None:
    assert engine_functions.fit_memory([math.nan, math.inf], rate=[0.0, 1.0]) is None


def test_fit_memory_without_rate_or_held_is_refused() -> None:
    with pytest.raises(ValueError, match="against the request rate, requests held, or both"):
        engine_functions.fit_memory([300.0, 310.0])


def test_fit_memory_against_a_list_of_another_length_is_refused() -> None:
    with pytest.raises(ValueError, match="fit_memory's rate is as long as workingSetMb"):
        engine_functions.fit_memory([300.0, 320.0, 345.0], rate=[0.0])


def test_fit_cpu_share_is_the_share_with_its_curve() -> None:
    fit = engine_functions.fit_cpu_share(
        [(100.0, 0.08), (200.0, 0.16), (300.0, 0.25)], service_ms=2.0
    )

    assert fit is not None
    assert (fit.share, fit.service_ms, fit.capped, fit.points) == (0.4083, 2.0, False, 3)
    assert fit.cpu_ms == pytest.approx(0.8166666666666667, rel=1e-12)
    assert fit.curve is not None
    assert fit.curve.reference_ms == pytest.approx(0.8166666666666667, rel=1e-12)
    assert fit.predict(1000.0) == pytest.approx(0.8166)


def test_a_share_whose_curve_finds_nothing_has_no_curve() -> None:
    fit = engine_functions.fit_cpu_share([(100.0, 0.08), (101.0, 0.08)], service_ms=1.0)

    assert fit is not None
    assert (fit.share, fit.curve) == (0.796, None)


def test_fit_cpu_share_without_points_is_none() -> None:
    assert engine_functions.fit_cpu_share([], service_ms=1.0) is None


def test_load_factors_are_the_tables_factor_at_each_rate() -> None:
    table = [
        {"rps": 50.0, "factor": 2.0},
        {"rps": 100.0, "factor": 1.9},
        {"rps": 400.0, "factor": 1.65},
    ]

    factors = engine_functions.load_factors(table, [10.0, 75.0, 200.0, 1000.0])

    assert factors == [2.0, 1.9415037499278844, 1.775, 1.65]


def test_load_factors_of_an_empty_table_are_one() -> None:
    assert engine_functions.load_factors([], [100.0]) == [1.0]


def test_load_factors_at_a_rate_that_is_not_a_number_are_the_last_factor() -> None:
    table = [{"rps": 50.0, "factor": 2.0}, {"rps": 400.0, "factor": 1.5}]

    assert engine_functions.load_factors(table, [math.nan]) == [1.5]


def test_load_factors_leave_out_points_without_a_positive_rate_and_factor() -> None:
    table: list[dict[str, Any]] = [
        {"rps": 0, "factor": 2.0},
        {"rps": 100, "factor": True},
        {"rps": 200, "factor": 1.5},
    ]

    assert engine_functions.load_factors(table, [100.0, 400.0]) == [1.5, 1.5]


def test_report_version_is_the_java_engines(java_engine_constant: Callable[[str], str]) -> None:
    assert engine_functions.report_version() == java_engine_constant("REPORT")


def test_constants_are_the_engines() -> None:
    assert engine_functions.min_curve_change() == 0.1
    assert engine_functions.min_per_request_mb() == 0.01


def test_a_function_it_does_not_have_is_a_lookup_error() -> None:
    with pytest.raises(LookupError, match="no function nowhere; functions: constants, "):
        engine_functions.functions().call("nowhere")


def test_a_process_started_from_another_starts_its_own(monkeypatch: pytest.MonkeyPatch) -> None:
    # a forked worker must not share its parent's pipes: it would read another call's answers
    first = engine_functions.functions()
    monkeypatch.setattr(engine_functions, "_owner", -1)

    second = engine_functions.functions()

    assert second is not first
    assert second._process.pid != first._process.pid
    assert second.call("constants")["minCurveChange"] == 0.1


def test_a_cli_not_installed_says_how_to_install_it(tmp_path: Path) -> None:
    with pytest.raises(RuntimeError, match="installDist"):
        engine_functions.Functions(tmp_path / "archsim")
