"""Which CI jobs a change needs (scripts/ci_changes.py, CI's `changes` job)."""

from __future__ import annotations

import importlib.util
import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location(
    "ci_changes", Path(__file__).parents[1] / "ci_changes.py"
)
assert _spec and _spec.loader
ci = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ci)

EVERY_JOB = {
    "python",
    "engine_tests",
    "benchmark_tests",
    "migrations",
    "java",
    "archsim_check",
    "web",
    "e2e",
}


@pytest.mark.parametrize(
    ("files", "jobs"),
    [
        (["archsim-app/src/main/java/io/archsim/app/runs/Comparisons.java"], {"java", "e2e"}),
        (["archsim-api-tests/src/main/java/io/archsim/apitests/Examples.java"], {"java"}),
        (["archsim-cli/src/main/java/io/archsim/cli/RunCommand.java"], {"java"}),
        (["gradle/libs.versions.toml", "config/checkstyle/checkstyle.xml"], {"java", "e2e"}),
        # the Python job checks the table repr reads against Python
        (
            ["archsim-model/src/main/resources/io/archsim/model/text/nonprintable.tsv"],
            {"python", "java", "e2e"},
        ),
        (["apps/web/src/app/page.tsx", "apps/web/package-lock.json"], {"web", "e2e"}),
        (["apps/web/src/data/accuracy.json"], {"python", "benchmark_tests", "web", "e2e"}),
        # the engine tests check the web fixtures export_web_fixtures.py writes; the Java model's
        # tests read the starter
        (
            ["apps/web/src/test/fixtures/starter.json"],
            {"python", "engine_tests", "web", "java", "e2e"},
        ),
        (["apps/web/src/test/fixtures/catalog.json"], {"python", "engine_tests", "web", "e2e"}),
        (["apps/web/src/test/fixtures/share.ts"], {"web", "e2e"}),
        # the end-to-end launcher starts the worker, whose command line the Java tests check
        (["apps/web/e2e/api-server.mjs"], {"web", "java", "e2e"}),
        # the Alembic migrations and seed; the end-to-end launcher makes its database through
        # the environment their project declares
        (["services/api/src/archsim_api/db.py"], {"python", "migrations"}),
        (["services/api/pyproject.toml"], {"python", "migrations", "e2e"}),
        (["packages/mcp/src/archsim_mcp/server.py"], {"python"}),
        (["scripts/check_commits.py", "packages/mcp/tests/test_server.py"], {"python"}),
        (
            ["benchmarks/report.py", "benchmarks/results/pg-mixed/run-5/measured.json"],
            {"python", "benchmark_tests"},
        ),
        (["scripts/prices/aws.py", "scripts/sizing_audit.py"], {"python", "engine_tests"}),
        # the exporters write the Java tests' resources and the golden files
        (["scripts/export_report_cases.py"], {"python", "java"}),
        (["scripts/export_golden.py", "scripts/golden/codec.py"], {"python", "java"}),
        (["scripts/export_web_fixtures.py"], {"python", "engine_tests", "java"}),
        (["parity/golden/manifest.json"], {"python", "java", "e2e"}),
        (["docs/validation.md", "docs/validation/accuracy.json"], {"python", "benchmark_tests"}),
        # stage 4's parity page and its runs: the page check in the Python job reads them, no
        # benchmark test does
        (["docs/validation/java-parity.md", "docs/validation/simulation-parity.json"], {"python"}),
        # the engines' speed, which only the page check reads, and the earlier measurements, which
        # nothing does
        (["benchmarks/perf/java-port.json", "benchmarks/perf/python-0.16.0.json"], {"python"}),
        (["benchmarks/perf/baseline.json"], {"python"}),
        (["docs/assumptions.md"], {"python", "engine_tests"}),
        (
            [".github/actions/archsim-check/action.yml"],
            {"python", "engine_tests", "archsim_check"},
        ),
        ([".github/workflows/benchmark.yml"], {"python", "benchmark_tests"}),
    ],
)
def test_a_change_runs_the_jobs_that_read_its_files(files: list[str], jobs: set[str]) -> None:
    assert ci.jobs(files) == jobs


@pytest.mark.parametrize(
    "path", ["uv.lock", "pyproject.toml", "packages/engine/src/archsim_engine/cli.py"]
)
def test_the_python_environment_or_the_engine_runs_every_job_but_web(path: str) -> None:
    assert ci.jobs([path]) == EVERY_JOB - {"web"}


@pytest.mark.parametrize(
    "files",
    [
        [
            "docs/architecture.md",
            "docs/adr/0026-component-specs-and-price-snapshots-in-the-model.md",
        ],
        ["README.md", "AGENTS.md", "CLAUDE.md", ".claude/skills/test-first/SKILL.md"],
        [
            ".github/dependabot.yml",
            ".github/workflows/prices.yml",
            ".github/PULL_REQUEST_TEMPLATE.md",
        ],
        [],
    ],
)
def test_files_no_job_reads_run_no_job(files: list[str]) -> None:
    assert ci.jobs(files) == set()


@pytest.mark.parametrize(
    "path",
    [
        ".github/workflows/ci.yml",
        "scripts/ci_changes.py",
        "examples/cache-aside-starter.json",
        "docker-compose.yml",
        "a-folder-no-rule-names/file.txt",
    ],
)
def test_ci_itself_the_examples_or_a_path_no_rule_names_run_every_job(path: str) -> None:
    assert ci.jobs([path]) == EVERY_JOB


def test_files_from_several_parts_run_what_any_of_them_needs() -> None:
    files = ["apps/web/src/app/page.tsx", "packages/mcp/src/archsim_mcp/server.py"]

    assert ci.jobs(files) == {"web", "e2e", "python"}


def test_the_python_steps_run_only_with_the_python_job_they_are_part_of() -> None:
    steps = {"engine_tests", "benchmark_tests"}

    alone = [path for path, jobs in ci.RULES if jobs & steps and "python" not in jobs]

    assert alone == []


def test_every_path_a_rule_names_exists() -> None:
    missing = [path for path, _ in ci.RULES if not any(ROOT.glob(path.rstrip("/")))]

    assert missing == []


def test_no_rule_is_hidden_behind_an_earlier_one() -> None:
    hidden = [
        (earlier, later)
        for i, (later, _) in enumerate(ci.RULES)
        for earlier, _ in ci.RULES[:i]
        if ci.covers(earlier, later)
    ]

    assert hidden == []


def test_ci_gates_each_job_on_its_output() -> None:
    workflow = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")

    gated = set(re.findall(r"needs\.changes\.outputs\.(\w+) == 'true'", workflow))
    declared = set(
        re.findall(r"^\s+(\w+): \$\{\{ steps\.jobs\.outputs\.(\w+) \}\}", workflow, re.M)
    )

    assert gated == EVERY_JOB
    assert declared == {(job, job) for job in EVERY_JOB}


def _git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", *args],
        cwd=repo,
        capture_output=True,
        encoding="utf-8",
        check=True,
    ).stdout.strip()


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    _git(tmp_path, "init", "-q")
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "a.md").write_text("a\n", encoding="utf-8")
    (tmp_path / "old.txt").write_text("old\n", encoding="utf-8")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "docs: start")
    return tmp_path


def test_changed_files_list_both_sides_of_a_rename(repo: Path) -> None:
    base = _git(repo, "rev-parse", "HEAD")
    (repo / "docs" / "a.md").write_text("b\n", encoding="utf-8")
    _git(repo, "mv", "old.txt", "new.txt")
    _git(repo, "commit", "-q", "-am", "docs: change")
    head = _git(repo, "rev-parse", "HEAD")

    files = ci.changed_files(base, head, cwd=repo)

    assert sorted(files or []) == ["docs/a.md", "new.txt", "old.txt"]


@pytest.mark.parametrize("base", ["", "0" * 40, "1234567890abcdef1234567890abcdef12345678"])
def test_changed_files_without_a_usable_base_are_unknown(repo: Path, base: str) -> None:
    head = _git(repo, "rev-parse", "HEAD")

    assert ci.changed_files(base, head, cwd=repo) is None


def test_a_docs_only_push_writes_every_job_false(repo: Path, tmp_path_factory) -> None:
    base = _git(repo, "rev-parse", "HEAD")
    (repo / "docs" / "a.md").write_text("b\n", encoding="utf-8")
    _git(repo, "commit", "-q", "-am", "docs: change")
    out = tmp_path_factory.mktemp("out") / "github_output"

    code = ci.main(["--base", base, "--head", "HEAD", "--github-output", str(out)], cwd=repo)

    assert code == 0
    assert out.read_text(encoding="utf-8").splitlines() == [f"{job}=false" for job in ci.JOBS]


@pytest.mark.parametrize("args", [["--all"], ["--base", "", "--head", "HEAD"]])
def test_a_run_by_hand_or_without_a_base_writes_every_job_true(
    repo: Path, tmp_path_factory, args: list[str]
) -> None:
    out = tmp_path_factory.mktemp("out") / "github_output"

    code = ci.main([*args, "--github-output", str(out)], cwd=repo)

    assert code == 0
    assert out.read_text(encoding="utf-8").splitlines() == [f"{job}=true" for job in ci.JOBS]
