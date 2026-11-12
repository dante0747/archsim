"""The archsim-check action's check step, run by bash with a stand-in for ``docker``.

The step (``.github/actions/archsim-check/action.yml``) builds ArchSim's image and runs the check
in it (ADR-0060). These tests run the step's own script as a Linux runner runs it, with ``docker``
replaced by a script that records each call and answers as the case asks: what the step asks
Docker for, and the exit code, outputs and comment it leaves. Building the image and running the
CLI for real is the CI job's (``archsim-check``).
"""

from __future__ import annotations

import os
import re
import shutil
import stat
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / ".github" / "actions" / "archsim-check"
SEPARATOR = "\x1f"
"""Between the arguments of a call the stand-in records, which no argument holds."""

pytestmark = pytest.mark.skipif(
    sys.platform == "win32" or shutil.which("bash") is None,
    reason="the step runs in bash on a Linux runner",
)

DOCKER = r"""#!/usr/bin/env bash
# a stand-in for docker: records each call, its arguments apart, and answers as the case asks
(IFS=$'\x1f'; printf '%s\n' "$*") >> "$DOCKER_CALLS"
case "$1" in
  build)
    [ "${BUILD_EXIT:-0}" = 0 ] || exit "$BUILD_EXIT" ;;
  run)
    # the CLI writes its comment to --out
    while [ $# -gt 0 ]; do
      [ "$1" = --out ] && printf '%s\n' '<!-- archsim-check -->' 'the verdict' > "$2"
      shift
    done
    exit "${CHECK_EXIT:-0}" ;;
  image) ;;
esac
"""


@dataclass
class Step:
    exit_code: int
    outputs: dict[str, str]
    docker: list[list[str]]
    summary: str
    log: str
    workspace: Path
    temp: Path

    def files(self) -> Path:
        """The action's own folder for the design, where the container writes."""
        return self.temp / f"archsim-check-{self.outputs['slug']}"

    def tag(self) -> str:
        """The tag the step gave the image it built."""
        build = self.docker[0]
        return build[build.index("--tag") + 1]

    def check(self) -> list[str]:
        """The arguments the CLI was run with, in the image the step built."""
        run = self.docker[1]
        return run[run.index(self.tag()) + 1 :]


def run_step(tmp_path: Path, **case: str) -> Step:
    """Runs the check step with the inputs and answers ``case`` names, as GitHub would."""
    step = next(
        s
        for s in yaml.safe_load((ACTION / "action.yml").read_text())["runs"]["steps"]
        if s.get("id") == "check"
    )
    workspace = tmp_path / "work"
    temp = tmp_path / "temp"
    bin_dir = tmp_path / "bin"
    for folder in (workspace, temp, bin_dir):
        folder.mkdir(parents=True)
    docker = bin_dir / "docker"
    docker.write_text(DOCKER, encoding="utf-8")
    docker.chmod(docker.stat().st_mode | stat.S_IXUSR)
    env = {
        **os.environ,
        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
        "DESIGN": "archsim/design.json",
        "SCENARIO": "",
        "BASELINE_REF": "",
        "FAIL_ON": "objectives",
        "KNEE": "false",
        "MAX_P95": "0.10",
        "MAX_COST": "0.10",
        "ARCHSIM_DIR": "",
        "REPORT_URL": "https://github.com/o/r/actions/runs/7",
        "GITHUB_WORKSPACE": str(workspace),
        "GITHUB_ACTION_PATH": str(ACTION),
        "GITHUB_OUTPUT": str(temp / "output"),
        "GITHUB_STEP_SUMMARY": str(temp / "summary"),
        "RUNNER_TEMP": str(temp),
        "RUNNER_OS": "Linux",
        "DOCKER_CALLS": str(temp / "docker-calls"),
        **case,
    }
    for name in ("output", "summary", "docker-calls"):
        (temp / name).touch()
    done = subprocess.run(
        ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", step["run"]],
        cwd=workspace,
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    outputs = dict(
        line.split("=", 1) for line in (temp / "output").read_text().splitlines() if "=" in line
    )
    calls = [line.split(SEPARATOR) for line in (temp / "docker-calls").read_text().splitlines()]
    return Step(
        done.returncode,
        outputs,
        calls,
        (temp / "summary").read_text(),
        done.stdout + done.stderr,
        workspace,
        temp,
    )


def test_the_image_is_built_from_the_actions_own_checkout(tmp_path: Path) -> None:
    step = run_step(tmp_path)

    archsim = f"{ACTION}/../../.."
    assert step.docker[0] == [
        "build",
        "--file",
        f"{archsim}/.github/actions/archsim-check/Dockerfile",
        "--tag",
        step.tag(),
        archsim,
    ]


def test_each_check_tags_its_image_with_a_name_of_its_own(tmp_path: Path) -> None:
    # two jobs on one runner's Docker may build the same image: each gets its own tag
    first = run_step(tmp_path / "first")
    second = run_step(tmp_path / "second")

    assert re.fullmatch(r"archsim-check:[0-9a-f-]{36}", first.tag())
    assert first.tag() != second.tag()


def test_the_check_runs_in_the_image_by_its_tag(tmp_path: Path) -> None:
    step = run_step(tmp_path)

    assert step.docker[1][step.docker[1].index(step.tag()) + 1] == "check"


def test_only_the_checks_own_tag_is_removed_once_it_has_run(tmp_path: Path) -> None:
    # a self-hosted runner keeps no image per job, and another job's tag keeps the image it
    # shares with this one; BuildKit's cache stays
    step = run_step(tmp_path)

    assert step.docker[2] == ["image", "rm", step.tag()]


def test_another_archsim_checkout_is_the_one_built(tmp_path: Path) -> None:
    step = run_step(tmp_path, ARCHSIM_DIR="tools/archsim")

    archsim = f"{step.workspace}/tools/archsim"
    assert (step.docker[0][2], step.docker[0][-1]) == (
        f"{archsim}/.github/actions/archsim-check/Dockerfile",
        archsim,
    )


def test_a_check_that_passes_runs_the_cli_with_the_inputs(tmp_path: Path) -> None:
    step = run_step(tmp_path, FAIL_ON="objectives, regression")

    assert (step.exit_code, step.outputs["exit-code"], step.outputs["passed"]) == (0, "0", "true")
    assert step.check() == [
        "check",
        f"{step.workspace}/archsim/design.json",
        "--format",
        "markdown",
        "--out",
        step.outputs["markdown"],
        "--json-out",
        step.outputs["json"],
        "--report-url",
        "https://github.com/o/r/actions/runs/7",
        "--max-p95-increase",
        "0.10",
        "--max-cost-increase",
        "0.10",
        "--fail-on",
        "objectives",
        "--fail-on",
        "regression",
    ]


def test_the_container_has_no_network_the_runners_user_and_only_its_files(tmp_path: Path) -> None:
    # the workspace to read, and the action's own folder to write in
    step = run_step(tmp_path)

    run = step.docker[1]
    files = step.files()
    assert run[: run.index(step.tag())] == [
        "run",
        "--rm",
        "--network",
        "none",
        "--user",
        f"{os.getuid()}:{os.getgid()}",
        "--env",
        "HOME=/tmp",
        "--volume",
        f"{step.workspace}:{step.workspace}:ro",
        "--volume",
        f"{files}:{files}",
        "--workdir",
        str(step.workspace),
    ]


def test_the_comment_and_result_are_written_in_the_actions_own_folder(tmp_path: Path) -> None:
    step = run_step(tmp_path)

    assert (step.outputs["markdown"], step.outputs["json"]) == (
        f"{step.files()}/archsim-check-archsim_design.json.md",
        f"{step.files()}/archsim-check-archsim_design.json.json",
    )


def test_a_scenario_a_baseline_and_the_knee_are_passed_on(tmp_path: Path) -> None:
    step = run_step(tmp_path, SCENARIO="busy", BASELINE_REF="origin/main", KNEE="true")

    check = step.check()
    passed_on = check[check.index("--scenario") : check.index("--knee") + 1]
    assert passed_on == ["--scenario", "busy", "--baseline-ref", "origin/main", "--knee"]


def test_a_scenario_has_its_own_comment_and_files(tmp_path: Path) -> None:
    step = run_step(tmp_path, SCENARIO="busy")

    assert (step.outputs["key"], step.outputs["slug"]) == (
        "archsim/design.json#busy",
        "archsim_design.json_busy",
    )


def test_a_check_that_fails_leaves_the_clis_comment_and_exit_code(tmp_path: Path) -> None:
    step = run_step(tmp_path, CHECK_EXIT="1")

    assert (step.exit_code, step.outputs["exit-code"], step.outputs["passed"]) == (0, "1", "false")
    assert step.summary == "<!-- archsim-check -->\nthe verdict\n"


def test_a_check_that_cannot_run_replaces_the_comment(tmp_path: Path) -> None:
    step = run_step(tmp_path, CHECK_EXIT="2")

    assert (step.outputs["exit-code"], step.outputs["passed"]) == ("2", "false")
    assert Path(step.outputs["markdown"]).read_text().splitlines()[1:] == [
        "### ⚠️ ArchSim check could not run (exit 2)",
        "",
        "The design or its baseline could not be used; the job log says why.",
    ]


def test_a_container_that_docker_cannot_run_is_a_check_that_cannot_run(tmp_path: Path) -> None:
    # 125: Docker's own failure, which is not one of the CLI's codes
    step = run_step(tmp_path, CHECK_EXIT="125")

    assert (step.outputs["exit-code"], step.outputs["passed"]) == ("2", "false")
    assert "::error::The check's container ended with exit code 125" in step.log
    assert step.summary.splitlines()[-1] == (
        "The check's container did not run to its end; the job log says why."
    )


def test_an_image_that_does_not_build_is_a_check_that_cannot_run(tmp_path: Path) -> None:
    step = run_step(tmp_path, BUILD_EXIT="1")

    assert (step.outputs["exit-code"], [call[0] for call in step.docker]) == ("2", ["build"])
    assert "::error::ArchSim's image did not build" in step.log
    assert step.summary.splitlines()[-1] == "ArchSim's image did not build; the job log says why."


def test_a_runner_that_is_not_linux_is_a_check_that_cannot_run(tmp_path: Path) -> None:
    step = run_step(tmp_path, RUNNER_OS="macOS")

    assert (step.outputs["exit-code"], step.docker) == ("2", [])
    assert "::error::The ArchSim check runs in a Linux container" in step.log
    assert step.summary.splitlines()[-1] == (
        "The check runs in a Linux container: this runner is not Linux."
    )
