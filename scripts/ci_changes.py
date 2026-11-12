"""Which CI jobs a change needs: the jobs that one of its changed files can affect.

The repository is private, so every CI minute is billed. CI's `changes` job runs this on the
files a pull request or a push changes, and the jobs none of them can affect are skipped. A path
no rule names runs every job, so a new folder is tested until it has a rule. So do a change to
CI itself, a run started by hand, and a push without a usable base (a new branch, or history
that was rewritten). `Commit messages` always runs and is not decided here.

When a test starts reading a file outside its own part of the repository (the Java tests read
docs/assumptions.md, parity/fixtures and examples/), add that file or its folder to RULES, or a
change to it alone will not run the test.

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

# The jobs of .github/workflows/ci.yml that run only when a change needs them. `benchmark_tests`
# is a step of the Python job, about 9 of its minutes on a hosted runner: the accuracy
# benchmarks' tests, which calibrate, simulate and render docs/validation.md with the Java engine
# (ADR-0062). Only a change to the engine or to a file they read can change their result.
JOBS = (
    "python",
    "benchmark_tests",
    "migrations",
    "java",
    "archsim_check",
    "web",
    "e2e",
)

EVERY = frozenset(JOBS)
NONE: frozenset[str] = frozenset()
PYTHON = frozenset({"python"})
BENCHMARK_TESTS = frozenset({"python", "benchmark_tests"})
JAVA = frozenset({"java", "e2e"})
# The archsim-check action builds the Java CLI into its image from these modules and the Gradle
# build (ADR-0060), so its job checks a change to any of them.
CHECK_ACTION = frozenset({"archsim_check"})
# The Python environment, which the jobs that run Python sync: the Python job's tests, the
# benchmarks' harness, the migrations and the end-to-end launcher's database maker.
PYTHON_RUNTIME = BENCHMARK_TESTS | {"migrations", "e2e"}
# The web app's engine fixtures: archsim-engine's GeneratedFileTests checks them against the
# engine's output, and the web app's tests read them.
WEB_FIXTURES = frozenset({"web", "java", "e2e"})

# (path, jobs). A path ending in "/" is a folder and covers everything in it, a path with a "*"
# covers the paths it matches, and any other path is one file. The first rule that covers a
# changed file decides which jobs it needs.
RULES: tuple[tuple[str, frozenset[str]], ...] = (
    # CI itself
    (".github/workflows/ci.yml", EVERY),
    ("scripts/ci_changes.py", EVERY),
    # scripts/tests/test_archsim_check_action.py runs its check step, and the archsim-check job
    # tests its comment and runs it
    (".github/actions/", frozenset({"python", "archsim_check"})),
    # benchmarks/tests/test_held_out.py reads its cpuset loop
    (".github/workflows/benchmark.yml", BENCHMARK_TESTS),
    (".github/", NONE),
    # The web app. The benchmarks' tests compare the accuracy page's copy with docs/validation/.
    # archsim-engine's GeneratedFileTests checks the fixtures `./gradlew :archsim-engine:generate`
    # writes from the starter. archsim-model's DesignDocumentsTests reads the starter too.
    ("apps/web/src/data/accuracy.json", BENCHMARK_TESTS | {"web", "e2e"}),
    ("apps/web/src/test/fixtures/starter.json", WEB_FIXTURES),
    ("apps/web/src/test/fixtures/catalog.json", WEB_FIXTURES),
    ("apps/web/src/test/fixtures/analysis-starter.json", WEB_FIXTURES),
    # the end-to-end launcher: the Java tests check the worker's command line in it
    ("apps/web/e2e/api-server.mjs", frozenset({"web", "java", "e2e"})),
    ("apps/web/", frozenset({"web", "e2e"})),
    # The Java build. The black-box API tests, the command line and the MCP server are not part
    # of the app the end-to-end tests run.
    ("archsim-api-tests/", frozenset({"java"})),
    # The benchmarks' tests simulate on the installed Java CLI and ask it for the engine's
    # functions (ADR-0061, ADR-0062): a change to what the CLI is built from (its, the engine's
    # and the model's main sources and build files, and the Gradle build) runs them; the modules'
    # tests, test fixtures and READMEs do not.
    ("archsim-cli/src/main/", frozenset({"java"}) | CHECK_ACTION | BENCHMARK_TESTS),
    ("archsim-cli/build.gradle.kts", frozenset({"java"}) | CHECK_ACTION | BENCHMARK_TESTS),
    ("archsim-cli/", frozenset({"java"}) | CHECK_ACTION),
    ("archsim-mcp/", frozenset({"java"})),
    ("archsim-app/", JAVA),
    ("archsim-engine/src/main/", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("archsim-engine/build.gradle.kts", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("archsim-engine/", JAVA | CHECK_ACTION),
    # the model's main sources include the table PythonText reads, which
    # scripts/export_printable.py writes and scripts/tests checks against Python in the Python
    # job (BENCHMARK_TESTS runs that job)
    ("archsim-model/src/main/", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("archsim-model/build.gradle.kts", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("archsim-model/", JAVA | CHECK_ACTION),
    ("build-logic/", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("config/", JAVA),
    ("gradle/", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("gradle.properties", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("gradlew", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    ("gradlew.bat", JAVA),
    ("settings.gradle.kts", JAVA | CHECK_ACTION | BENCHMARK_TESTS),
    # Python
    ("pyproject.toml", PYTHON_RUNTIME),
    ("uv.lock", PYTHON_RUNTIME),
    # the Alembic migrations, until stage 6; the end-to-end launcher makes its database
    # through the environment their project declares (psycopg)
    ("services/api/pyproject.toml", frozenset({"python", "migrations", "e2e"})),
    ("services/api/", frozenset({"python", "migrations"})),
    # The exporters write tables of Python's own answers (casefold, erfc, isprintable) that the
    # Java tests, and the model for export_printable.py, read; scripts/tests checks the printable
    # table against Python in the Python job.
    ("scripts/export_*", frozenset({"python", "java"})),
    ("scripts/", PYTHON),
    # the engines' speed (the frozen engine's, and the Java speed task's): the Python job's
    # parity page check reads two of these files, and no benchmark test reads any
    ("benchmarks/perf/", PYTHON),
    ("benchmarks/", BENCHMARK_TESTS),
    # The fixtures the golden files and recordings were made from, which the API and app tests
    # read.
    ("parity/fixtures/", frozenset({"java"})),
    # The frozen engine's golden files and simulation baselines, which the Java tests read.
    ("parity/", JAVA),
    # The templates: every part loads them.
    ("examples/", EVERY),
    # The documents the tests compare with what the engine or the scripts generate; no job reads
    # the rest.
    ("docs/assumptions.md", frozenset({"java"})),
    ("docs/validation.md", BENCHMARK_TESTS),
    # stage 4's parity page and its runs, which the Python job's page check reads
    ("docs/validation/java-parity.md", PYTHON),
    ("docs/validation/simulation-parity.json", PYTHON),
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
