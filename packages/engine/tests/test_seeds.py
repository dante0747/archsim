"""Seed groups and quick checks: the seed override, ranges across seeds, sampled-mode eligibility."""

from __future__ import annotations

import json
from typing import Any

import pytest

from archsim_engine import (
    DocumentInvalidError,
    analyze_document,
    default_registry,
    load_document,
    prepare_run,
    quick_check,
    run_simulation,
    summarize_seeds,
)
from archsim_engine import engine as engine_module
from archsim_engine.seeds import DASH, cap_confidence, range_text, seeds_text
from engine_builders import EXAMPLES


def starter(**workload: Any) -> dict[str, Any]:
    d = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    d["workload"].update(workload)
    return d


def short_starter(**workload: Any) -> dict[str, Any]:
    return starter(**{"durationS": 4, "warmupS": 1, "rps": 200, **workload})


def digests(report: dict[str, Any]) -> tuple[str, str]:
    p = report["provenance"]
    return p["inputDigest"], p["resultDigest"]


# -- seed override ----------------------------------------------------------------------------
def test_a_seed_override_reproduces_the_document_with_that_seed() -> None:
    doc = short_starter()
    overridden = run_simulation(json.loads(json.dumps(doc)), seed=43)
    written = run_simulation(short_starter(seed=43))
    assert overridden["provenance"]["seed"] == 43
    assert digests(overridden) == digests(written)
    again = run_simulation(json.loads(json.dumps(doc)), seed=43)
    assert digests(again) == digests(overridden)
    base = run_simulation(json.loads(json.dumps(doc)))
    assert base["provenance"]["seed"] == 42
    assert digests(base) != digests(overridden)
    # the document's own seed, given explicitly, changes nothing
    same = run_simulation(json.loads(json.dumps(doc)), seed=42)
    assert digests(same) == digests(base)


def test_the_seed_override_applies_after_the_scenario() -> None:
    doc = short_starter()
    doc["scenarios"] = [
        {
            "id": "seeded",
            "name": "Seeded",
            "overrides": [{"target": {"kind": "workload"}, "path": "seed", "value": 7}],
        }
    ]
    reg = default_registry()
    assert prepare_run(doc, "seeded", reg).resolved.workload.seed == 7
    assert prepare_run(doc, "seeded", reg, seed=9).resolved.workload.seed == 9


@pytest.mark.parametrize("seed", [-1, 2**32])
def test_a_seed_out_of_range_is_refused(seed: int) -> None:
    with pytest.raises(DocumentInvalidError, match="Seeds go from 0"):
        prepare_run(short_starter(), seed=seed)


# -- ranges -----------------------------------------------------------------------------------
OBJECTIVES = {"latencyP95Ms": 120, "availability": 0.999}


def flat(p95: float, success: float = 1.0, p50: float = 40.0) -> dict[str, Any]:
    """The API's compact run summary."""
    return {"p50": p50, "p95": p95, "p99": p95 * 1.5, "successRate": success, "throughputRps": 400}


def test_summarize_seeds_counts_the_verdicts_and_the_ranges() -> None:
    runs = [
        {"seed": 42, "summary": flat(98.2)},
        {"seed": 43, "summary": flat(104.4)},
        {"seed": 44, "summary": flat(131.0)},  # misses p95 <= 120
        {"seed": 45, "summary": flat(101.0)},
        {"seed": 46, "summary": flat(110.3)},
    ]
    s = summarize_seeds(runs, OBJECTIVES)
    assert s["text"] == (
        f"Meets the objectives in 4 of 5 runs · p95 98{DASH}131 ms (median 104) · seeds 42{DASH}46"
    )
    assert (s["met"], s["missed"], s["undecided"], s["finished"], s["runs"]) == (4, 1, 0, 5, 5)
    assert s["verdict"] == "borderline" and s["borderline"] is True
    assert s["confidenceCap"] == "medium"
    assert s["latencyMs"]["p95"] == {"min": 98.2, "max": 131.0, "median": 104.4}
    assert s["successRate"] == {"min": 1.0, "max": 1.0, "median": 1.0}
    assert s["seeds"] == {"first": 42, "last": 46}
    assert [r["pass"] for r in s["perRun"]] == [True, True, False, True, True]


def test_summarize_seeds_reads_report_summaries_too() -> None:
    nested = {
        "latencyMs": {"p50": 30.0, "p95": 90.0, "p99": 120.0},
        "successRate": 1.0,
        "throughputRps": 400.0,
    }
    s = summarize_seeds(
        [{"seed": 7, "summary": nested}, {"seed": 8, "summary": nested}], OBJECTIVES
    )
    assert s["verdict"] == "holds" and not s["borderline"] and s["confidenceCap"] is None
    assert s["text"] == f"Meets the objectives in 2 of 2 runs · p95 90 ms · seeds 7{DASH}8"


def test_summarize_seeds_with_unfinished_runs_and_no_objectives() -> None:
    runs = [{"seed": 42, "summary": flat(5.25)}, {"seed": 43, "summary": None}]
    s = summarize_seeds(runs, {})
    assert (s["runs"], s["finished"], s["verdict"]) == (2, 1, None)
    assert s["text"] == f"1 runs, no objectives set · p95 5.3 ms · seeds 42{DASH}43"
    failing = summarize_seeds(
        [{"seed": 1, "summary": flat(300)}, {"seed": 2, "summary": flat(200, success=0.9)}],
        OBJECTIVES,
    )
    assert failing["verdict"] == "fails"
    assert failing["text"].startswith("Misses the objectives in 2 of 2 runs")
    empty = summarize_seeds([], OBJECTIVES)
    assert empty["finished"] == 0 and empty["latencyMs"]["p95"] is None and empty["text"] == ""


def test_text_helpers() -> None:
    assert range_text("p95", {"min": 9.94, "max": 10.4, "median": 10.0}) == (
        f"p95 9.9{DASH}10 ms (median 10)"
    )
    assert seeds_text([5]) == "seed 5"
    assert seeds_text([1, 3]) == "seeds 1, 3"
    assert cap_confidence("high", "medium") == "medium"
    assert cap_confidence("low", "medium") == "low"
    assert cap_confidence("high", None) == "high"


def test_a_real_group_of_seeds() -> None:
    doc = short_starter()
    reports = [run_simulation(json.loads(json.dumps(doc)), seed=s) for s in (42, 43, 44)]
    s = summarize_seeds(
        [{"seed": r["provenance"]["seed"], "summary": r["summary"]} for r in reports],
        {"latencyP95Ms": 1000},
    )
    assert s["finished"] == 3 and s["verdict"] == "holds"
    p95s = [r["summary"]["latencyMs"]["p95"] for r in reports]
    assert s["latencyMs"]["p95"]["min"] == min(p95s)
    assert len({r["provenance"]["resultDigest"] for r in reports}) == 3


# -- quick checks (sampled mode) --------------------------------------------------------------
def test_quick_check_says_why_a_design_does_not_qualify() -> None:
    def qc(**kw: Any) -> dict[str, Any]:
        return quick_check(load_document(starter(**kw)).workload)

    short = qc()
    assert not short["eligible"] and "too short" in short["reason"]
    assert "270 s or longer" in short["reason"]
    long = qc(durationS=300)
    assert long["eligible"] and long["simulatedS"] == 135 and long["sampleS"] == 125
    ramp = qc(durationS=600, pattern={"kind": "ramp", "startRps": 100, "endRps": 900})
    assert not ramp["eligible"] and "constant traffic" in ramp["reason"]
    faults = [{"id": "f1", "kind": "outage", "target": "cache", "startS": 100, "durationS": 20}]
    faulty = qc(durationS=600, faults=faults)
    assert not faulty["eligible"] and "1 planned failure," in faulty["reason"]
    # the analysis carries it, for the web app's Simulate menu
    analysis = analyze_document(starter(durationS=300), default_registry())
    assert analysis["quickCheck"]["eligible"] is True


@pytest.fixture
def tiny_samples(monkeypatch: pytest.MonkeyPatch) -> None:
    """Let a few seconds of the starter qualify for sampling, so the tests stay fast."""
    monkeypatch.setattr(engine_module, "SAMPLE_MIN_S", 1.0)
    monkeypatch.setattr(engine_module, "SAMPLE_MIN_REQUESTS", 200)


def test_a_sampled_run_takes_the_seed_override(
    tiny_samples: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_module, "stationary", lambda report: (True, {}))
    doc = starter(durationS=8, warmupS=1, rps=200)
    a = run_simulation(json.loads(json.dumps(doc)), mode="sampled", seed=50)
    assert a["simulationMode"]["kind"] == "sampled"
    assert a["provenance"]["seed"] == 50 and a["window"]["durationS"] < 8
    b = run_simulation(json.loads(json.dumps(doc)), mode="sampled", seed=50)
    assert digests(a) == digests(b)


def test_a_sample_that_is_not_steady_falls_back_with_a_note(
    tiny_samples: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(engine_module, "stationary", lambda report: (False, {"growing": ["x"]}))
    doc = starter(durationS=8, warmupS=1, rps=200)
    report = run_simulation(doc, mode="sampled")
    mode = report["simulationMode"]
    assert mode["kind"] == "full" and mode["requestedMode"] == "sampled"
    assert mode["simulatedDurationS"] == 8 and "not steady" in mode["note"]
    assert report["window"]["durationS"] == 8
