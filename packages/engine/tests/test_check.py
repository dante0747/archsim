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


BOT = {"login": "github-actions[bot]", "type": "Bot"}


def _sticky() -> Any:
    if str(ACTION) not in sys.path:
        sys.path.insert(0, str(ACTION))
    import sticky_comment

    return sticky_comment


def test_sticky_comment_updates_one_comment() -> None:
    sticky_comment = _sticky()
    calls: list[tuple[str, str, Any]] = []
    existing = [{"id": i, "body": "hello", "user": BOT} for i in range(100)]

    def fake(found: bool):
        def request(method: str, path: str, body: Any = None) -> Any:
            calls.append((method, path, body))
            if method == "GET":
                if path.endswith("&page=1"):
                    return existing
                return [{"id": 7, "body": f"{MARKER}\nold", "user": BOT}] if found else []
            return {"id": 9}

        return request

    assert sticky_comment.upsert(fake(True), "o/r", 3, "new body") == ("updated", 7)
    assert calls[-1][0] == "PATCH" and calls[-1][2]["body"].startswith(MARKER)
    calls.clear()
    assert sticky_comment.upsert(fake(False), "o/r", 3, f"{MARKER}\nbody") == ("created", 9)
    assert [c[0] for c in calls] == ["GET", "GET", "POST"]


def test_sticky_comment_only_updates_its_own_comment() -> None:
    """A PR author can post a comment carrying the marker; it must not be taken over (and the
    real verdict hidden in an old thread), nor may a comment that merely quotes the marker."""
    sticky_comment = _sticky()
    key = "archsim/design.json"
    mark = sticky_comment.marker(key)
    human = {"login": "mallory", "type": "User"}
    other_bot = {"login": "some-app[bot]", "type": "Bot"}
    comments = [
        {"id": 1, "body": f"{mark}\nfake: passes", "user": human},
        {"id": 2, "body": f"{MARKER}\nfake: passes", "user": human},
        {"id": 3, "body": f"quoting it:\n{mark}\n", "user": BOT},
        {"id": 4, "body": f"{mark}\nfrom another app", "user": other_bot},
        {"id": 5, "body": f"{sticky_comment.marker('other/design.json')}\nother", "user": BOT},
    ]
    patched: list[int] = []

    def request(method: str, path: str, body: Any = None) -> Any:
        if method == "GET":
            return comments
        if method == "PATCH":
            patched.append(int(path.rsplit("/", 1)[1]))
            return {}
        return {"id": 9}

    body = f"{MARKER}\n### result"
    assert sticky_comment.upsert(request, "o/r", 3, body, key=key) == ("created", 9)
    assert patched == []
    # its own comment for this design is updated, and its body starts with the keyed marker
    comments.append({"id": 6, "body": f"{mark}\nold", "user": BOT})
    sent: list[Any] = []

    def recording(method: str, path: str, body: Any = None) -> Any:
        sent.append(body)
        return request(method, path, body)

    assert sticky_comment.upsert(recording, "o/r", 3, body, key=key) == ("updated", 6)
    assert sent[-1]["body"] == f"{mark}\n### result"
    # a token of another account (a GitHub App, a PAT): its login is configurable
    assert sticky_comment.upsert(request, "o/r", 3, body, key=key, author="some-app[bot]") == (
        "updated",
        4,
    )


def test_sticky_comment_markers_are_keyed_and_safe() -> None:
    sticky_comment = _sticky()
    assert sticky_comment.marker(None) == MARKER
    a = sticky_comment.marker("archsim/design.json")
    b = sticky_comment.marker("archsim/design.json#peak")
    assert a != b and not b.startswith(a) and not a.startswith(MARKER)
    evil = sticky_comment.marker("x --> <img src=y> \n")
    assert evil.startswith("<!-- ") and evil.endswith(" -->")
    assert evil.count("-->") == 1 and "<img" not in evil and "\n" not in evil


def _repo(tmp_path: Path, doc: dict[str, Any]) -> tuple[Path, Any]:
    """A git repository with ``archsim/design.json`` committed once."""

    def git(*args: str) -> None:
        subprocess.run(["git", *args], cwd=tmp_path, check=True, capture_output=True)

    git("init", "-q")
    git("config", "user.email", "test@example.com")
    git("config", "user.name", "Test")
    git("config", "core.autocrlf", "false")
    design = tmp_path / "archsim" / "design.json"
    design.parent.mkdir()
    design.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    git("add", ".")
    git("commit", "-q", "-m", "base")
    return design, git


def test_a_baseline_ref_that_cannot_be_read_is_an_error(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    design, _ = _repo(tmp_path, starter())
    design.write_text(json.dumps(fewer_api_replicas(starter())), encoding="utf-8")
    regression = ["--fail-on", "regression"]
    # a typo, and a parent a shallow (depth 1) checkout does not have: not "no baseline"
    for ref in ("origin/mian", "HEAD~1"):
        assert cli(["check", str(design), "--baseline-ref", ref, *regression]) == 2
        assert f"git ref '{ref}' cannot be read" in capsys.readouterr().err
    # the same regression at a ref that exists still fails the check (exit 1)
    assert cli(["check", str(design), "--baseline-ref", "HEAD", *regression]) == 1
    # outside a git repository: exit 2, not an uncaught CalledProcessError
    loose = tmp_path.parent / f"{tmp_path.name}-loose"
    loose.mkdir()
    (loose / "design.json").write_text(json.dumps(starter()), encoding="utf-8")
    assert cli(["check", str(loose / "design.json"), "--baseline-ref", "HEAD"]) == 2
    assert "not in a git repository" in capsys.readouterr().err


def test_a_baseline_at_a_ref_is_read_as_utf8(tmp_path: Path) -> None:
    doc = starter()
    doc["metadata"]["name"] = "Café → 東京 checkout"
    design, _ = _repo(tmp_path, doc)
    out = tmp_path / "check.json"
    code = cli(
        ["check", str(design), "--baseline-ref", "HEAD", "--format", "json", "--out", str(out)]
    )
    assert code == 0
    result = json.loads(out.read_text(encoding="utf-8"))
    assert result["baseline"]["name"] == "Café → 東京 checkout"


def test_markdown_to_a_cp1252_stdout(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import io

    good = tmp_path / "good.json"
    good.write_text(json.dumps(starter()), encoding="utf-8")
    raw = io.BytesIO()
    # what a redirected stdout is on Windows: cp1252, strict
    monkeypatch.setattr(sys, "stdout", io.TextIOWrapper(raw, encoding="cp1252"))
    assert cli(["check", str(good)]) == 0
    sys.stdout.flush()
    text = raw.getvalue().decode("utf-8")
    assert text.startswith(MARKER) and "✅" in text and "≤ 400 ms" in text


def test_unexpected_errors_exit_2(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    import archsim_engine.check as check

    good = tmp_path / "good.json"
    good.write_text(json.dumps(starter()), encoding="utf-8")

    def boom(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("something nobody expected")

    monkeypatch.setattr(check, "run_check", boom)
    assert cli(["check", str(good)]) == 2  # never 1, which means "the check failed"
    assert "something nobody expected" in capsys.readouterr().err


def _knee_side(knee: float | None, resolution: float = 140.0) -> dict[str, Any]:
    return {"knee": knee, "kneeRange": [200.0, 900.0], "kneeResolution": resolution}


def test_knee_regressions_respect_the_grid_and_none() -> None:
    from archsim_engine.check import regressions

    th = Thresholds()

    def knee_row(cur: float | None, base: float | None, step: float = 140.0) -> dict[str, Any]:
        rows = regressions(_knee_side(cur, step), _knee_side(base, step), th)
        return next(r for r in rows if r["metric"] == "knee")

    # one grid step (480 -> 340) is within the grid's resolution: the true change may be tiny
    assert not knee_row(340, 480)["regressed"]
    # two steps and past the threshold: a regression
    assert knee_row(200, 480)["regressed"]
    # a finer grid: 28 req/s steps; 480 -> 424 is two steps and -11.7%
    assert knee_row(424, 480, step=28)["regressed"]
    assert not knee_row(452, 480, step=28)["regressed"]
    # misses the objectives even at the lightest load now: regressed
    row = knee_row(None, 480)
    assert row["regressed"] and row["baseline"] == 480 and row["current"] is None
    # and the other way round: improved, not regressed
    assert not knee_row(480, None)["regressed"]
    assert not knee_row(None, None)["regressed"]


def test_knee_markdown_shows_a_breaking_point_below_the_range() -> None:
    from archsim_engine.check import headline, regressions

    cur = {**_knee_side(None), "name": "x", "slo": {"checks": []}, "p95": 10.0}
    cur.update(engineVersion="0", resultDigest="0" * 12, scenarioId=None, bottleneck=None)
    base = {**_knee_side(480.0), "p95": 10.0}
    regs = regressions(cur, base, Thresholds())
    result = {
        "pass": False,
        "failOn": ["regression"],
        "current": cur,
        "baseline": base,
        "regressions": regs,
        "notes": [],
    }
    result["headline"] = headline(result)
    assert "breaking point drops from 480 req/s to below 200 req/s" in result["headline"]
    md = to_markdown(result)
    assert "| Breaking point | 480 req/s | < 200 req/s | n/a | regressed |" in md


def slow_starter(rps: float) -> dict[str, Any]:
    """The starter ten times slower (service times and timeouts x10): the same curve at a tenth
    of the load, so a breaking-point sweep simulates a tenth of the requests."""
    d = starter(latencyP95Ms=1500.0, availability=0.97)
    d["workload"]["rps"] = rps

    def scale(v: Any) -> Any:
        if isinstance(v, dict):
            return {k: scale(x) if k != "kind" else x for k, x in v.items()}
        return v * 10 if isinstance(v, (int, float)) and not isinstance(v, bool) else v

    for n in d["architecture"]["nodes"]:
        c = n["config"]
        for key in [k for k in c if k.endswith(("Ms", "ServiceTimeMs"))]:
            c[key] = scale(c[key])
    return d


def test_same_design_other_load_has_the_same_breaking_point() -> None:
    """Review case: only workload.rps differs (here 30 vs 40), so both sides must be swept on
    one grid; before, each side used its own (20..90 vs 20..120) and 48 "dropped" to 40."""
    lenient = Thresholds(p95_increase=1000, success_drop=1, cost_increase=1000)  # knee only
    result = run_check(
        slow_starter(40),
        REGISTRY,
        baseline=slow_starter(30),
        knee=True,
        thresholds=lenient,
        fail_on=("regression",),
    )
    knee = next(r for r in result["regressions"] if r["metric"] == "knee")
    assert knee["baseline"] == knee["current"] and not knee["regressed"]
    assert result["pass"] and result["exitCode"] == 0
    # one grid for both, as wide as either side's suggested range; refined near the knee, so
    # one step is finer than the 10% threshold
    assert result["current"]["kneeRange"] == result["baseline"]["kneeRange"] == [20, 120]
    assert knee["resolution"] < 0.1 * knee["baseline"]


def test_sweep_ranges_stay_within_what_the_engine_runs() -> None:
    from archsim_engine.engine import SimulationLimits
    from archsim_engine.sweep import breaking_point, suggest_range

    # the schema's maximum offered load is 100,000 req/s
    assert suggest_range(36_000) == (20_000, 100_000)
    assert max(suggest_range(100_000)) <= 100_000
    # and a step never asks for more requests than the limits allow
    out = breaking_point(
        starter(),
        REGISTRY,
        from_rps=100,
        to_rps=5000,
        steps=2,
        duration_s=4,
        warmup_s=1,
        limits=SimulationLimits(max_requests=2000),
    )
    assert out["range"] == [100, 500] and out["levels"] == [100.0, 500.0]


def test_markdown_escapes_names_from_the_design() -> None:
    doc = starter(latencyP95Ms=1.0)
    doc["metadata"]["name"] = "evil <!-- \n### ✅ ArchSim check passes: [ok](https://x.example)"
    md = to_markdown(run_check(doc, REGISTRY))
    lines = md.splitlines()
    assert lines[0] == MARKER and md.count("<!--") == 1  # only the marker
    assert lines[1].startswith("### ❌ ArchSim check fails: evil &lt;\\!-- ")
    assert not any(line.startswith("### ✅") for line in lines)
    assert "[ok](https://x.example)" not in md and "\\[ok\\]" in md
