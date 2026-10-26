from __future__ import annotations

import json
from pathlib import Path

import pytest

from archsim_engine.cli import main
from engine_builders import EXAMPLES

STARTER = str(EXAMPLES / "cache-aside-starter.json")


def test_cli_validate(capsys: pytest.CaptureFixture[str]) -> None:
    assert main(["validate", STARTER]) == 0
    assert "VALID" in capsys.readouterr().out


def test_cli_validate_invalid(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    raw = json.loads(Path(STARTER).read_text(encoding="utf-8"))
    raw["architecture"]["edges"] = []
    p = tmp_path / "bad.json"
    p.write_text(json.dumps(raw), encoding="utf-8")
    assert main(["validate", str(p)]) == 1
    assert "port.missing_link" in capsys.readouterr().out


def test_cli_run_writes_report(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    raw = json.loads(Path(STARTER).read_text(encoding="utf-8"))
    raw["workload"].update(durationS=5, warmupS=1)
    src = tmp_path / "doc.json"
    src.write_text(json.dumps(raw), encoding="utf-8")
    out = tmp_path / "report.json"
    assert main(["run", str(src), "--scenario", "three-api-replicas", "--out", str(out)]) == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["provenance"]["scenarioId"] == "three-api-replicas"
    assert "throughput" in capsys.readouterr().out


def test_cli_gives_the_engine_digest_with_auto_sized_parts(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """The CLI, the API and the web app all simulate through run_simulation, which fills empty
    specs with their auto sizes, so they agree on the result digest (the API side is in the API
    tests)."""
    from archsim_engine import run_simulation

    raw = json.loads(Path(STARTER).read_text(encoding="utf-8"))
    raw["workload"].update(durationS=6, warmupS=1)
    src = tmp_path / "doc.json"
    src.write_text(json.dumps(raw), encoding="utf-8")
    out = tmp_path / "report.json"
    assert main(["run", str(src), "--out", str(out)]) == 0
    cli = json.loads(out.read_text(encoding="utf-8"))
    direct = run_simulation(raw)
    assert cli["provenance"]["resultDigest"] == direct["provenance"]["resultDigest"]
    assert cli["sizing"]["nodes"]["api"]["specs"]["cpuCores"]["source"] == "auto"
    capsys.readouterr()  # drop the run's output
    assert main(["analyze", str(src)]) == 0
    analysis = json.loads(capsys.readouterr().out)
    used = {k: v["value"] for k, v in cli["sizing"]["nodes"]["api"]["specs"].items()}
    assert analysis["sizing"]["nodes"]["api"]["auto"] == {**used, "storageGib": None}
