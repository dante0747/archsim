"""Which CI jobs a change needs: the jobs that one of its changed files can affect.

The repository is private, so every CI minute is billed. CI's `changes` job runs this on the
files a pull request or a push changes, and the jobs none of them can affect are skipped. A path
no rule names runs every job, so a new folder is tested until it has a rule. So do a change to
CI itself, a run started by hand, and a push without a usable base (a new branch, or history
that was rewritten). `Commit messages` always runs and is not decided here.

When a test starts reading a file outside its own part of the repository (the Python tests read
docs/assumptions.md, the Java tests read packages/engine/tests/fixtures and examples/), add that
file or its folder to RULES, or a change to it alone will not run the test.

    python3 scripts/ci_changes.py --base BASE_SHA --head HEAD_SHA        # what runs, and why
    python3 scripts/ci_changes.py --base BASE_SHA --head HEAD_SHA --github-output "$GITHUB_OUTPUT"
    python3 scripts/ci_changes.py --all --github-output "$GITHUB_OUTPUT"
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from collections.abc import Iterable
from fnmatch import fnmatchcase
from pathlib import Path

# The jobs of .github/workflows/ci.yml that run only when a change needs them. `engine_tests` and
# `benchmark_tests` are steps of the Python job, about 7 and 9 of its minutes on a hosted
# runner: the frozen engine's tests, and the accuracy benchmarks' (which calibrate, simulate and
# render docs/validation.md with that engine). Only a change to the engine or to a file they read
# can change their result.
JOBS = (
    "python",
    "engine_tests",
    "benchmark_tests",
    "workers",
    "java",
    "archsim_check",
    "web",
    "e2e",
)

EVERY = frozenset(JOBS)
NONE: frozenset[str] = frozenset()
PYTHON = frozenset({"python"})
ENGINE_TESTS = frozenset({"python", "engine_tests"})
BENCHMARK_TESTS = frozenset({"python", "benchmark_tests"})
JAVA = frozenset({"java", "e2e"})
# The frozen engine and the Python environment: every Python process imports them (the workers,
# the engine service the Java API and the end-to-end tests start, the archsim CLI), and the Java
# build reads the engine's pricing data and test fixtures.
PYTHON_RUNTIME = EVERY - {"web"}

# (path, jobs). A path ending in "/" is a folder and covers everything in it, a path with a "*"
# covers the paths it matches, and any other path is one file. The first rule that covers a
# changed file decides which jobs it needs.
RULES: tuple[tuple[str, frozenset[str]], ...] = (
    # CI itself
    (".github/workflows/ci.yml", EVERY),
    ("scripts/ci_changes.py", EVERY),
    # packages/engine/tests/test_check.py runs the action
    (".github/actions/", frozenset({"python", "engine_tests", "archsim_check"})),
    # benchmarks/tests/test_held_out.py reads its cpuset loop
    (".github/workflows/benchmark.yml", BENCHMARK_TESTS),
    (".github/", NONE),
    # The web app. The benchmarks' tests compare the accuracy page's copy with docs/validation/;
    # the engine's tests check the fixtures scripts/export_web_fixtures.py writes, and
    # archsim-model's DesignDocumentsTests reads the starter.
    ("apps/web/src/data/accuracy.json", BENCHMARK_TESTS | {"web", "e2e"}),
    ("apps/web/src/test/fixtures/starter.json", ENGINE_TESTS | {"web", "java", "e2e"}),
    ("apps/web/src/test/fixtures/catalog.json", ENGINE_TESTS | {"web", "e2e"}),
    ("apps/web/src/test/fixtures/analysis-starter.json", ENGINE_TESTS | {"web", "e2e"}),
    ("apps/web/", frozenset({"web", "e2e"})),
    # The Java build. The black-box API tests are not part of the app the end-to-end tests run.
    ("archsim-api-tests/", frozenset({"java"})),
    ("archsim-app/", JAVA),
    ("archsim-engine/", JAVA),
    # scripts/export_printable.py writes the table PythonText reads, and scripts/tests checks
    # it against Python in the Python job
    ("archsim-model/src/main/resources/io/archsim/model/text/nonprintable.tsv", JAVA | {"python"}),
    ("archsim-model/", JAVA),
    ("build-logic/", JAVA),
    ("config/", JAVA),
    ("gradle/", JAVA),
    ("gradle.properties", JAVA),
    ("gradlew", JAVA),
    ("gradlew.bat", JAVA),
    ("settings.gradle.kts", JAVA),
    # Python
    ("packages/engine/", PYTHON_RUNTIME),
    ("pyproject.toml", PYTHON_RUNTIME),
    ("uv.lock", PYTHON_RUNTIME),
    ("services/api/", frozenset({"python", "workers", "java", "e2e"})),
    ("services/engine-service/", frozenset({"python", "java", "e2e"})),
    ("packages/mcp/", PYTHON),
    # The exporters write the Java tests' resources (archsim-engine/src/test/resources) and the
    # golden files (scripts/golden/, which export_golden.py --check compares in the Python job);
    # the engine's tests run export_web_fixtures.py, price and audit scripts.
    ("scripts/export_web_fixtures.py", ENGINE_TESTS | {"java"}),
    ("scripts/export_*", frozenset({"python", "java"})),
    ("scripts/golden/", frozenset({"python", "java"})),
    ("scripts/prices/", ENGINE_TESTS),
    ("scripts/sizing_audit.py", ENGINE_TESTS),
    ("scripts/", PYTHON),
    ("benchmarks/", BENCHMARK_TESTS),
    # The golden files: export_golden.py --check writes them, the Java parity tests read them.
    ("parity/", frozenset({"python", "java", "e2e"})),
    # The templates: every part loads them.
    ("examples/", EVERY),
    # The documents the Python tests compare with what the scripts generate; no job reads the rest.
    ("docs/assumptions.md", ENGINE_TESTS),
    ("docs/validation.md", BENCHMARK_TESTS),
    ("docs/validation/", BENCHMARK_TESTS),
    ("docs/", NONE),
    # For people and assistants only.
    (".claude/", NONE),
    ("AGENTS.md", NONE),
    ("CLAUDE.md", NONE),
    ("CODE_OF_CONDUCT.md", NONE),
    ("CONTRIBUTING.md", NONE),
    ("IMPLEMENTATION_STATUS.md", NONE),
    ("LICENSE", NONE),
    ("NOTICE", NONE),
    ("README.md", NONE),
    ("SECURITY.md", NONE),
)


def covers(rule: str, path: str) -> bool:
    """Whether a rule's path covers a path: the same file, anything in the rule's folder, or a
    path its "*" pattern matches."""
    if "*" in rule:
        return fnmatchcase(path, rule)
    return path.startswith(rule) if rule.endswith("/") else path == rule


def needed_by(path: str) -> frozenset[str]:
    """The jobs a change to one file needs: the first covering rule's, or every job."""
    return next((jobs for rule, jobs in RULES if covers(rule, path)), EVERY)


def jobs(files: Iterable[str]) -> set[str]:
    """The jobs a change to these files needs."""
    return {job for path in files for job in needed_by(path)}


def changed_files(base: str, head: str, *, cwd: Path | None = None) -> list[str] | None:
    """The files changed from where `head` and `base` meet to `head`, with both the old and the
    new path of a rename; None when `base` is missing, all zeros (a new branch) or not in the
    clone (rewritten history)."""
    if not base.strip("0"):
        return None
    try:
        out = subprocess.run(
            ["git", "diff", "--name-only", "--no-renames", "-z", f"{base}...{head}"],
            cwd=cwd,
            capture_output=True,
            encoding="utf-8",
            check=True,
        ).stdout
    except subprocess.CalledProcessError:
        return None
    return [path for path in out.split("\0") if path]


def main(argv: list[str] | None = None, *, cwd: Path | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--all", action="store_true", help="every job (a run started by hand)")
    ap.add_argument("--base", help="the base: the pull request's base, or the push's `before`")
    ap.add_argument("--head", help="the head: the pull request's head, or the pushed commit")
    ap.add_argument("--github-output", type=Path, help="append job=true|false lines to this file")
    a = ap.parse_args(argv)
    if not a.all and a.head is None:
        ap.error("give --all, or --base and --head")

    files = None if a.all else changed_files(a.base or "", a.head, cwd=cwd)
    if files is None:
        print("every job: a run by hand, or no usable base to compare with")
        needed = set(EVERY)
    else:
        needed = jobs(files)
        print(f"{len(files)} changed file(s)")
        for job in JOBS:
            why = next((path for path in files if job in needed_by(path)), None)
            print(f"  {job}: " + (f"runs ({why})" if why else "skipped"))

    if a.github_output:
        with a.github_output.open("a", encoding="utf-8", newline="\n") as out:
            for job in JOBS:
                out.write(f"{job}={'true' if job in needed else 'false'}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
