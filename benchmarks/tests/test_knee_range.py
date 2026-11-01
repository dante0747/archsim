"""The predicted range of the knee (W28): the inputs' run-to-run variation, the knee's
sensitivity to them, the model's own error, the range itself and its leave-one-out check, on
synthetic data and on the committed fit (benchmarks/knee-range.json). Nothing here starts
Docker or runs the engine: knee searches go through a stand-in for ``run.simulate``."""

from __future__ import annotations

import json
import math
import statistics
from pathlib import Path
from typing import Any

import pytest
import report
import run

PG_MIXED = run.EXPERIMENTS["pg-mixed"]


def fake_simulate(knee_of: Any) -> Any:
    """A stand-in for run.simulate: the design meets the objective up to the knee its inputs
    give (``knee_of(service time mean, flush median)``) and misses it above."""

    def sim(doc: dict[str, Any]) -> dict[str, Any]:
        nodes = {n["id"]: n["config"] for n in doc["architecture"]["nodes"]}
        st = run.mean_ms(nodes["api"]["serviceTimeMs"])
        flush = (nodes["db"].get("commitFlushMs") or {}).get("median", 1.0)
        ok = doc["workload"]["rps"] <= knee_of(st, flush)
        return {
            "offeredRps": doc["workload"]["rps"],
            "p95": 5.0 if ok else 500.0,
            "successRate": 1.0,
        }

    return sim


INPUTS: dict[str, Any] = {
    "serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.17, "p95": 0.46}, "how": ""},
    "clientLinkLatencyMs": {"value": 0.005, "how": ""},
    "commitFlushMs": {"value": {"kind": "lognormal", "median": 0.8, "p95": 1.6}, "how": ""},
    "dbCpuShare": {"value": 0.9, "how": ""},
    "readServiceTimeMs": {"value": {"kind": "lognormal", "median": 0.2, "p95": 0.5}, "how": ""},
    "anchor": {"rateRps": 1674.0},
    "measuredLowLoad": {"rateRps": 1674.0},
}


def test_inputs_scale_as_a_whole() -> None:
    lognormal = {"kind": "lognormal", "median": 0.2, "p95": 0.5}
    assert run.scaled(lognormal, 2.0) == {"kind": "lognormal", "median": 0.4, "p95": 1.0}
    assert run.scaled({"kind": "exponential", "mean": 0.04}, 0.5) == {
        "kind": "exponential",
        "mean": 0.02,
    }
    assert run.scaled({"kind": "constant", "value": 3.0}, 2.0)["value"] == 6.0
    emp = run.scaled({"kind": "empirical", "quantiles": [[0, 1.0], [1, 2.0]]}, 3.0)
    assert emp["quantiles"] == [[0, 3.0], [1, 6.0]]
    assert run.scaled(0.25, 2.0) == 0.5 and run.scaled(True, 2.0) is True
    # the scale variation is measured on: a distribution's mean, as the engine samples it
    assert run.input_scale(lognormal) == pytest.approx(run.mean_ms(lognormal))
    assert run.input_scale(0.4) == 0.4 and run.input_scale(None) is None
    # dbCpuShare is a share of the query time: never above 1
    assert run.perturbed(INPUTS, "dbCpuShare", 1.5)["dbCpuShare"]["value"] == 1.0
    up = run.perturbed(INPUTS, "serviceTimeMs", 2.0)
    assert up["serviceTimeMs"]["value"]["median"] == pytest.approx(0.34)
    assert INPUTS["serviceTimeMs"]["value"]["median"] == 0.17  # the original is untouched
    assert run.range_keys(INPUTS) == [
        "serviceTimeMs",
        "clientLinkLatencyMs",
        "readServiceTimeMs",
        "commitFlushMs",
        "dbCpuShare",
    ]


def test_every_input_is_varied_or_fixed() -> None:
    """Each input calibration derives is either carried by the range (RANGE_INPUTS) or left as
    calibrated (RANGE_FIXED): a new input must be put in one of them. Checked on every
    development check's inputs (today's run.py, each engine)."""
    keys = set(INPUTS)
    for f in (run.RESULTS / "dev").glob("*/*/*.json"):
        keys |= set(json.loads(f.read_text(encoding="utf-8"))["inputs"])
    unlisted = keys - set(run.RANGE_INPUTS) - set(run.RANGE_FIXED)
    assert not unlisted, f"put these inputs in run.RANGE_INPUTS or run.RANGE_FIXED: {unlisted}"
    assert not set(run.RANGE_INPUTS) & set(run.RANGE_FIXED)


def test_a_knee_search_from_a_nearby_knee(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(run, "simulate", fake_simulate(lambda st, fl: 1234.0))
    full = run.predicted_knee(PG_MIXED, INPUTS)["kneeRps"]
    assert full == pytest.approx(1234, rel=0.01)
    for near in (300, 1000, 1234, 1500, 5000):
        got = run.predicted_knee(PG_MIXED, INPUTS, near)
        assert got["kneeRps"] == pytest.approx(1234, rel=0.01) and got["kneeRps"] <= 1234
        assert len(got["tried"]) < len(run.predicted_knee(PG_MIXED, INPUTS)["tried"]) + 3
    # nothing meets the objective: no knee
    monkeypatch.setattr(run, "simulate", fake_simulate(lambda st, fl: 0.0))
    assert run.predicted_knee(PG_MIXED, INPUTS, 500)["kneeRps"] == 0


def test_the_knees_sensitivity_to_each_input(monkeypatch: pytest.MonkeyPatch) -> None:
    # a knee set by the service's CPU alone: it halves when the CPU per request doubles
    monkeypatch.setattr(run, "simulate", fake_simulate(lambda st, fl: 400.0 / st))
    knee = run.predicted_knee(PG_MIXED, INPUTS)["kneeRps"]
    jobs = run.sensitivity_jobs(PG_MIXED, INPUTS, knee)
    assert len(jobs) == 1 + len(run.range_keys(INPUTS))
    sens = run.sensitivity_of(INPUTS, [run.knee_near(*j) for j in jobs])
    assert sens["serviceTimeMs"] == pytest.approx(-1.0, abs=0.06)
    assert all(sens[k] == 0 for k in sens if k != "serviceTimeMs")
    # a knee set by the commit flush and the service together
    monkeypatch.setattr(run, "simulate", fake_simulate(lambda st, fl: 1000.0 / (fl + 4 * st)))
    knee = run.predicted_knee(PG_MIXED, INPUTS)["kneeRps"]
    sens = run.sensitivity_of(
        INPUTS, [run.knee_near(*j) for j in run.sensitivity_jobs(PG_MIXED, INPUTS, knee)]
    )
    flush_share = 0.8 / (0.8 + 4 * run.mean_ms(INPUTS["serviceTimeMs"]["value"]))
    assert sens["commitFlushMs"] == pytest.approx(-flush_share, abs=0.06)


FIT: dict[str, Any] = {
    "at": "2026-11-01T00:00:00Z",
    "engineVersion": "test",
    "inputs": {"serviceTimeMs": {"sigma": 0.1}, "commitFlushMs": {"sigma": 0.2}},
    "fallbackSigma": 0.05,
    "correlation": 0.0,
    "model": {"sigma": 0.2},
    "runs": 12,
    "experiments": 4,
}


def test_the_range_adds_calibration_variation_and_the_models_error() -> None:
    sens = {"serviceTimeMs": -1.0, "commitFlushMs": -0.5, "dbLinkLatencyMs": 0.2}
    # each input's variation times the knee's sensitivity, an unseen input at the fallback
    cal = math.sqrt(0.1**2 + (0.5 * 0.2) ** 2 + (0.2 * 0.05) ** 2)
    assert run.calibration_sigma(sens, FIT) == pytest.approx(cal)
    # with a common correlation, inputs that move together add up
    both = {**FIT, "correlation": 1.0}
    assert run.calibration_sigma(sens, both) == pytest.approx(abs(-0.1 - 0.1 + 0.01))
    r = run.knee_range_of(1000, sens, FIT)
    assert r is not None and r["nominalCoverage"] == run.RANGE_COVERAGE == 0.8
    sigma = math.sqrt(cal**2 + 0.2**2)
    z = statistics.NormalDist().inv_cdf(0.9)
    assert r["sigma"] == pytest.approx(sigma, abs=1e-4)
    assert (r["lowRps"], r["highRps"]) == (
        round(1000 * math.exp(-z * sigma)),
        round(1000 * math.exp(z * sigma)),
    )
    assert r["lowRps"] < 1000 < r["highRps"] and r["fit"]["engineVersion"] == "test"
    wider = run.knee_range_of(1000, sens, FIT, 0.9)
    assert wider is not None and wider["lowRps"] < r["lowRps"] and wider["highRps"] > r["highRps"]
    assert run.knee_range_of(1000, sens, None) is None and run.knee_range_of(0, sens, FIT) is None


def test_the_range_and_whether_it_holds_the_measured_knee() -> None:
    r = run.knee_range_of(1000, {"serviceTimeMs": -1.0}, FIT)
    assert r is not None
    assert run.in_range(r, {"kneeRps": 1000.0}) is True
    assert run.in_range(r, {"kneeRps": 2000.0}) is False
    assert (
        run.in_range(r, {"kneeRps": None, "bound": "above", "aboveRps": r["highRps"] - 1}) is True
    )
    assert run.in_range(None, {"kneeRps": 950.0}) is None
    # engine 0.14.0's ranges, kept in its predictions, are read the same way
    old = {"lowRps": 900, "highRps": 1100, "nominalCoverage": 0.5, "replicates": 3}
    assert run.in_range(old, {"kneeRps": 950.0}) is True


def test_variation_is_pooled_within_experiments() -> None:
    def row(exp: str, source: str, st: float, flush: float | None = None) -> dict[str, Any]:
        scales = {"serviceTimeMs": st, **({"commitFlushMs": flush} if flush else {})}
        return {"experiment": exp, "source": source, "scales": scales}

    rows = [
        row("a", "a1", 0.20, 1.0),
        row("a", "a2", 0.22, 1.2),
        row("a", "a3", 0.18, 0.8),
        row("b", "b1", 0.10),
        row("b", "b2", 0.12),
    ]
    fit = run.variation_fit(rows)
    logs_a = [math.log(x) for x in (0.20, 0.22, 0.18)]
    logs_b = [math.log(x) for x in (0.10, 0.12)]
    ss = sum((x - statistics.fmean(logs_a)) ** 2 for x in logs_a)
    ss += sum((x - statistics.fmean(logs_b)) ** 2 for x in logs_b)
    # the level differs between experiments; only the variation within each counts
    assert fit["inputs"]["serviceTimeMs"]["sigma"] == pytest.approx(math.sqrt(ss / 3), abs=1e-4)
    assert fit["inputs"]["serviceTimeMs"] | {"sigma": 0} == {
        "sigma": 0,
        "calibrations": 5,
        "experiments": 2,
    }
    assert fit["inputs"]["commitFlushMs"]["experiments"] == 1
    assert fit["fallbackSigma"] == pytest.approx(
        statistics.median(v["sigma"] for v in fit["inputs"].values()), abs=1e-4
    )
    # in experiment a both inputs moved together
    assert fit["correlation"] > 0.5
    assert run.variation_fit([])["inputs"] == {}
    # an input that never moved (a fixed default) was not seen varying: the fallback, not 0
    fixed = [{**r, "scales": {**r["scales"], "fetchOverheadMs": 0.93}} for r in rows]
    fit = run.variation_fit(fixed)
    assert "fetchOverheadMs" not in fit["inputs"]
    assert run.input_sigma(fit, "fetchOverheadMs") == fit["fallbackSigma"] > 0


def test_the_models_error_by_maximum_likelihood() -> None:
    # no calibration spread: the root mean square of the log errors
    assert run.model_sigma([0.2, -0.2, 0.2], [0.0, 0.0, 0.0]) == pytest.approx(0.2, abs=0.001)
    # errors the calibration spread already explains leave nothing to the model
    assert run.model_sigma([0.05, -0.05], [0.3, 0.3]) == 0.0
    both = run.model_sigma([0.3, -0.3], [0.1, 0.1])
    assert both == pytest.approx(math.sqrt(0.3**2 - 0.1**2), abs=0.001)
    assert run.model_sigma([], []) == 0.0


def test_leave_one_out_never_fits_a_run_on_its_own_error() -> None:
    scales = [
        {"experiment": e, "source": f"{e}{i}", "scales": {"serviceTimeMs": 0.2 * (1 + 0.05 * i)}}
        for e in ("a", "b", "c")
        for i in range(3)
    ]

    def r(exp: str, i: int, error: float) -> dict[str, Any]:
        return {
            "experiment": exp,
            "source": f"{exp}{i}",
            "knee": 1000.0,
            "measuredKnee": {"kneeRps": 1000.0 * math.exp(error)},
            "sensitivity": {"serviceTimeMs": -1.0},
            "error": error,
        }

    # one experiment's runs far off, the others close: in-sample its error widens every range
    runs = [r(e, i, 0.6 if e == "c" else 0.02 * (i - 1)) for e in ("a", "b", "c") for i in range(3)]
    counts, detail = run.range_check(scales, runs)
    assert set(counts) == set(run.RANGE_FOLDS)
    assert all(by[f"{run.RANGE_COVERAGE:g}"][1] == len(runs) for by in counts.values())
    far = detail[-1]
    assert far["leaveExperimentOut"]["modelSigma"] < far["inSample"]["modelSigma"]
    assert far["leaveExperimentOut"]["holds"] is False  # the others' errors do not cover it
    assert far["leaveRunOut"]["modelSigma"] <= far["inSample"]["modelSigma"]
    near = detail[0]
    assert near["leaveExperimentOut"]["holds"] is True


def test_disturbed_runs_are_left_out_of_the_fit() -> None:
    def row(rps: float, p95: float) -> dict[str, Any]:
        return {"offeredRps": rps, "measured": {"p95": p95, "successRate": 1.0}}

    assert run.disturbed_steps([row(100, 5), row(200, 80), row(300, 20)]) == [200]
    assert run.disturbed_steps([row(100, 5), row(200, 80), row(300, 90)]) == []
    assert report.disturbed_steps([row(100, 5), row(200, 80), row(300, 20)]) == [200]


def test_discarded_trials_are_left_out_of_the_fit() -> None:
    # W26's throttled cache-aside trial (1 Nov) carries a DISCARDED file and is not fitted on
    sources = {s["source"] for s in run.range_sources()}
    throttled = "trials/2026-11-01-cache-aside-w26-throttled"
    assert (run.RESULTS / throttled / "DISCARDED").exists()
    assert throttled not in sources
    assert any(s.startswith("trials/") for s in sources)


def test_protocol_and_conditions() -> None:
    assert run.protocol_number({"protocol": "v5"}) == 5
    assert run.protocol_number({"protocol": "v6"}) == 6
    assert run.protocol_number({"method": "two passes (v4)"}) == 4
    assert run.protocol_number(None) == 1
    env = {"cpu": "x", "os": "w", "powerMode": "Best performance", "docker": "29"}
    assert run.conditions(env) == ("x", "w", "Best performance")


FIT_FILE = Path(run.RANGE_FIT)


def test_the_committed_range_fit() -> None:
    """benchmarks/knee-range.json: fitted on the four in-sample experiments' counted v5 runs and
    their calibrations, checked in-sample and leaving out each run and each experiment."""
    fit = json.loads(FIT_FILE.read_text(encoding="utf-8"))
    assert fit["nominalCoverage"] == run.RANGE_COVERAGE
    assert (fit["runs"], fit["experiments"]) == (12, 4)
    assert {c["experiment"] for c in fit["calibrations"]} == set(report.ORDER)
    assert not any(run.EXPERIMENTS[c["experiment"]].held_out for c in fit["calibrations"])
    assert 0 < fit["model"]["sigma"] < 1 and 0 <= fit["correlation"] <= 1
    for key, v in fit["inputs"].items():
        assert key in run.RANGE_INPUTS and v["sigma"] >= 0
    counts = fit["check"]["counts"]
    level = f"{run.RANGE_COVERAGE:g}"
    for fold in run.RANGE_FOLDS:
        hits, n = counts[fold][level]
        assert n == 12 and hits == sum(1 for c in fit["checked"] if c["ranges"][fold]["holds"])
    # the range the runs were predicted with: the replicate sets' knees held 1 of 12
    assert fit["check"]["before"] == {"inRange": 1, "withRange": 12, "nominalCoverage": 0.5}
    # each checked run's in-sample range is the committed fit applied to its sensitivity
    for c in fit["checked"]:
        r = run.knee_range_of(c["knee"], c["sensitivity"], fit)
        assert r is not None
        assert (r["lowRps"], r["highRps"]) == (
            c["ranges"]["inSample"]["lowRps"],
            c["ranges"]["inSample"]["highRps"],
        )
        assert math.log(c["measuredKnee"]["kneeRps"] / c["knee"]) == pytest.approx(
            c["error"], abs=1e-4
        )


def test_the_range_page_is_generated_from_the_committed_fit() -> None:
    """docs/validation/knee-range.md is report.py --dev's rendering of benchmarks/knee-range.json."""
    page = report.RANGE_DOC.read_text(encoding="utf-8")
    assert page == report.range_doc_text(run.read_range_fit())
    assert "No fit yet" in report.range_doc_text(None)
