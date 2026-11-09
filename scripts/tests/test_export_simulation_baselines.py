"""The baselines' recording: what a run keeps, the order runs go in, and resuming a recording."""

from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path

import export_simulation_baselines as baselines

REPORT = {
    "provenance": {
        "eventsProcessed": 120,
        "requestsGenerated": 40,
        "inputDigest": "in",
        "resultDigest": "out",
        "wallSeconds": 1.5,
    },
    "summary": {
        "arrived": 40,
        "successRate": 1.0,
        "latencyMs": {"p50": 3.0, "p95": 9.0},
        "latencyWithFailuresMs": {"p50": 3.0},
        "clients": [{"id": "users"}],
        "accounting": {"arrived": 40},
        "capacityLimit": None,
    },
    "nodes": [
        {"id": "api", "summary": {"utilization": 0.5, "capacityLimit": "cpu", "hitRatio": None}},
        {"id": "users", "summary": {"arrivals": 40, "byClient": {"a": 1}}},
    ],
}


def test_figures_keep_the_counts_digests_and_latencies_but_not_the_wall_time() -> None:
    figures = baselines.figures(REPORT)

    assert {
        k: figures[k]
        for k in ("eventsProcessed", "requestsGenerated", "inputDigest", "resultDigest")
    } == {
        "eventsProcessed": 120,
        "requestsGenerated": 40,
        "inputDigest": "in",
        "resultDigest": "out",
    }
    assert "wallSeconds" not in figures


def test_figures_keep_the_summarys_values_and_latencies_not_its_lists_or_nones() -> None:
    summary = baselines.figures(REPORT)["summary"]

    assert summary == {
        "arrived": 40,
        "successRate": 1.0,
        "latencyMs": {"p50": 3.0, "p95": 9.0},
        "latencyWithFailuresMs": {"p50": 3.0},
    }


def test_figures_keep_each_parts_summary_values_by_its_id() -> None:
    nodes = baselines.figures(REPORT)["nodes"]

    assert nodes == {"api": {"utilization": 0.5, "capacityLimit": "cpu"}, "users": {"arrivals": 40}}


def test_runs_go_seed_by_seed_so_every_case_has_its_first_seeds_early() -> None:
    cases = [
        baselines.Case("a", "base", 42),
        baselines.Case("a", "slow", 7),
        baselines.Case("b", "base", 1),
    ]

    runs = baselines.runs(cases, seeds=2)

    assert [(r.template, r.case, r.seed) for r in runs] == [
        ("a", "base", 42),
        ("a", "slow", 7),
        ("b", "base", 1),
        ("a", "base", 43),
        ("a", "slow", 8),
        ("b", "base", 2),
    ]


def test_recorded_reads_the_runs_a_stopped_recording_finished(tmp_path: Path) -> None:
    log = tmp_path / "runs.jsonl"
    log.write_text(
        json.dumps({"template": "a", "case": "base", "seed": 42, "figures": {}})
        + "\n"
        + '{"template": "a", "case": "base", "se',  # cut off as the process stopped
        encoding="utf-8",
    )

    assert set(baselines.recorded(log)) == {baselines.Run("a", "base", 42)}


def test_template_file_lists_each_cases_runs_by_seed() -> None:
    rows = [
        {"template": "a", "case": "base", "seed": 43, "figures": {"eventsProcessed": 2}},
        {"template": "a", "case": "base", "seed": 42, "error": "SimulationLimitError: too long"},
    ]

    data = json.loads(baselines.template_file("a", rows))

    assert data["cases"] == {
        "base": [
            {"seed": 42, "error": "SimulationLimitError: too long"},
            {"seed": 43, "eventsProcessed": 2},
        ]
    }


def test_write_keeps_each_files_digest_size_and_counts_and_other_templates(tmp_path: Path) -> None:
    rows = [
        {"template": "a", "case": "base", "seed": 42, "figures": {"eventsProcessed": 2}},
        {"template": "a", "case": "slow", "seed": 42, "error": "SimulationLimitError: too long"},
    ]
    baselines.write({"b": baselines.template_file("b", [])}, tmp_path)
    data = baselines.template_file("a", rows)

    baselines.write({"a": data}, tmp_path)

    manifest = json.loads((tmp_path / "manifest.json").read_text(encoding="utf-8"))
    assert [e["path"] for e in manifest["files"]] == ["a.json.gz", "b.json.gz"]
    assert manifest["files"][0] == {
        "path": "a.json.gz",
        "sha256": hashlib.sha256(data).hexdigest(),
        "bytes": len(data),
        "runs": 2,
        "failed": 1,
    }
    assert gzip.decompress((tmp_path / "a.json.gz").read_bytes()) == data
