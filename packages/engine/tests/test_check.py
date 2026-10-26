"""archsim check: objectives, regressions against a baseline (a file or a git ref), exit codes,
the Markdown comment, and the sticky comment script of the GitHub Action."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from archsim_engine import create_default_registry
from archsim_engine.check import MARKER, Thresholds, run_check, to_markdown
from archsim_engine.cli import main as cli
from engine_builders import EXAMPLES

REGISTRY = create_default_registry(include_plugins=False)
ACTION = Path(__file__).resolve().parents[3] / ".github" / "actions" / "archsim-check"
SNAPSHOT = Path(__file__).parent / "snapshots" / "check-regression.md"


def starter(**objectives: float) -> dict[str, Any]:
    d = json.loads((EXAMPLES / "cache-aside-starter.json").read_text(encoding="utf-8"))
    d["workload"].update(durationS=12, warmupS=2)
    d["scenarios"] = []
    d["objectives"] = {"latencyP95Ms": 400.0, "availability": 0.99, **objectives}
    return d


def fewer_api_replicas(doc: dict[str, Any]) -> dict[str, Any]:
    d = json.loads(json.dumps(doc))
    api = next(n for n in d["architecture"]["nodes"] if n["id"] == "api")
    api["config"]["replicas"] = 1
    return d


def test_objectives_pass_and_fail() -> None:
    ok = run_check(starter(), REGISTRY)
    assert ok["pass"] and ok["exitCode"] == 0 and ok["current"]["slo"]["pass"] is True
    strict = run_check(starter(latencyP95Ms=1.0), REGISTRY)
    assert not strict["pass"] and strict["exitCode"] == 1
    assert "— fails" in strict["headline"]
    # objectives missed but only regressions fail the check
    lenient = run_check(starter(latencyP95Ms=1.0), REGISTRY, fail_on=("regression",))
    assert lenient["pass"] and lenient["objectivesMissed"]


def test_regressions_against_a_baseline_and_the_comment() -> None:
    base = starter()
    worse = fewer_api_replicas(base)
    result = run_check(worse, REGISTRY, baseline=base, fail_on=("regression",))
    p95 = next(r for r in result["regressions"] if r["metric"] == "p95")
    assert p95["current"] > p95["baseline"] and p95["regressed"]
    assert not result["pass"] and result["exitCode"] == 1
    assert result["headline"].startswith("p95 goes from ")
    cost = next(r for r in result["regressions"] if r["metric"] == "costMonthly")
    assert cost["current"] < cost["baseline"] and not cost["regressed"]
    md = to_markdown(result, "https://example.com/run/1")
    assert md.startswith(MARKER) and "ArchSim check fails" in md and "| Latency p95 |" in md
    assert "[Full report](https://example.com/run/1)" in md
    # the same inputs give the same comment (the engine is deterministic)
    if not SNAPSHOT.exists():  # pragma: no cover - regenerate by deleting the snapshot
        SNAPSHOT.parent.mkdir(exist_ok=True)
        SNAPSHOT.write_text(md, encoding="utf-8", newline="\n")
    assert md == SNAPSHOT.read_text(encoding="utf-8")
    # thresholds are the user's
    loose = run_check(
        worse,
        REGISTRY,
        baseline=base,
        fail_on=("regression",),
        thresholds=Thresholds(p95_increase=1000, success_drop=1, cost_increase=1000, knee_drop=1),
    )
    assert loose["pass"]


def test_cli_exit_codes(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    good, strict, bad = tmp_path / "good.json", tmp_path / "strict.json", tmp_path / "bad.json"
    good.write_text(json.dumps(starter()), encoding="utf-8")
    strict.write_text(json.dumps(starter(latencyP95Ms=1.0)), encoding="utf-8")
    bad.write_text("{not json", encoding="utf-8")
    assert cli(["check", str(good)]) == 0
    assert cli(["check", str(strict)]) == 1
    assert cli(["check", str(strict), "--fail-on", "regression"]) == 0
    assert cli(["check", str(bad)]) == 2
    broken = starter()
    broken["architecture"]["edges"].append({"id": "x", "source": "api", "target": "nowhere"})
    (tmp_path / "broken.json").write_text(json.dumps(broken), encoding="utf-8")
    assert cli(["check", str(tmp_path / "broken.json")]) == 2
    out = tmp_path / "out.json"
    assert cli(["check", str(good), "--format", "json", "--out", str(out)]) == 0
    assert json.loads(out.read_text(encoding="utf-8"))["pass"] is True


def test_cli_compares_with_a_git_ref(tmp_path: Path) -> None:
    def git(*args: str) -> None:
        subprocess.run(["git", *args], cwd=tmp_path, check=True, capture_output=True)

    git("init", "-q")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    design = tmp_path / "archsim" / "design.json"
    design.parent.mkdir()
    design.write_text(json.dumps(starter()), encoding="utf-8")
    git("add", ".")
    git("commit", "-q", "-m", "base")
    design.write_text(json.dumps(fewer_api_replicas(starter())), encoding="utf-8")
    md = tmp_path / "check.md"
    code = cli(
        [
            "check",
            str(design),
            "--baseline-ref",
            "HEAD",
            "--fail-on",
            "regression",
            "--out",
            str(md),
        ]
    )
    assert code == 1 and "p95 goes from" in md.read_text(encoding="utf-8")
    # a design that does not exist at the ref: no baseline, objectives only
    other = tmp_path / "archsim" / "new.json"
    other.write_text(json.dumps(starter()), encoding="utf-8")
    assert cli(["check", str(other), "--baseline-ref", "HEAD", "--fail-on", "regression"]) == 0
    with pytest.raises(SystemExit):
        cli(["check", str(design), "--baseline-ref", "--output=x"])


def test_sticky_comment_updates_one_comment() -> None:
    sys.path.insert(0, str(ACTION))
    import sticky_comment

    calls: list[tuple[str, str, Any]] = []
    existing = [{"id": i, "body": "hello"} for i in range(100)]

    def fake(found: bool):
        def request(method: str, path: str, body: Any = None) -> Any:
            calls.append((method, path, body))
            if method == "GET":
                if path.endswith("&page=1"):
                    return existing
                return [{"id": 7, "body": f"{MARKER}\nold"}] if found else []
            return {"id": 9}

        return request

    assert sticky_comment.upsert(fake(True), "o/r", 3, "new body") == ("updated", 7)
    assert calls[-1][0] == "PATCH" and calls[-1][2]["body"].startswith(MARKER)
    calls.clear()
    assert sticky_comment.upsert(fake(False), "o/r", 3, f"{MARKER}\nbody") == ("created", 9)
    assert [c[0] for c in calls] == ["GET", "GET", "POST"]
