"""The Conventional Commits check used by the commit-msg hook and CI."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location(
    "check_commits", Path(__file__).parents[1] / "check_commits.py"
)
assert _spec and _spec.loader
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)


@pytest.mark.parametrize(
    "message",
    [
        "feat: add Kafka fetch cycles",
        "fix(engine): count failures in p95",
        "feat(api)!: drop the v1 share links",
        "build(deps-dev): bump eslint from 9.1 to 9.2",
        "docs(readme): retake the screenshots\n\nThe old ones showed the previous theme.\n",
        "feat(templates): add CQRS and event sourcing",
        "chore(benchmarks): add the v6 campaign\n\nBREAKING CHANGE: protocol v6 replaces v5\n",
        "refactor: move TypeScript helpers\n# a comment git strips\n",
    ],
)
def test_conventional_messages_pass(message: str) -> None:
    assert check.problems(message) == []


@pytest.mark.parametrize(
    ("message", "fragment"),
    [
        ("Add circuit breaker component", "is not `type(scope): description`"),
        ("feature: add a thing", "is not `type(scope): description`"),
        ("feat(Engine): add a thing", "is not `type(scope): description`"),
        ("feat:add a thing", "is not `type(scope): description`"),
        ("feat: Add a thing", "start the description in lower case"),
        ("feat: " + "x" * 95, "characters (at most 100)"),
        ("feat: add a thing\nno blank line", "blank line"),
        ("feat: add a thing\n\nCo-Authored-By: Claude <noreply@anthropic.com>", "AI co-author"),
        ("Merge branch 'w23' into main", "is not `type(scope): description`"),
        ("", "empty"),
    ],
)
def test_other_messages_fail(message: str, fragment: str) -> None:
    assert any(fragment in p for p in check.problems(message))


def test_human_co_authors_are_fine() -> None:
    assert check.problems("fix: a typo\n\nCo-Authored-By: Sam <sam@example.com>") == []


def test_autosquash_commits_pass_only_locally() -> None:
    assert check.problems("fixup! feat: add a thing", allow_autosquash=True) == []
    assert check.problems("fixup! feat: add a thing") != []


def test_the_hook_reads_a_message_file(tmp_path: Path) -> None:
    f = tmp_path / "COMMIT_EDITMSG"
    f.write_text("feat: add a thing\n", encoding="utf-8")
    assert check.main(["--file", str(f)]) == 0
    f.write_text("Add a thing\n", encoding="utf-8")
    assert check.main(["--file", str(f)]) == 1
