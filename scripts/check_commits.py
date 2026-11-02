"""Check commit messages against Conventional Commits (https://www.conventionalcommits.org).

A header is `type(scope)!: description`: a known type, an optional lower-case scope, an optional
`!` for a breaking change, then a description that starts in lower case. It is at most 100
characters. Messages carry no AI co-author trailer: every commit is the author's own.

    python scripts/check_commits.py --file .git/COMMIT_EDITMSG   # the commit-msg hook
    python scripts/check_commits.py --range origin/main..HEAD     # CI: every commit in a range
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

TYPES = (
    "feat",
    "fix",
    "docs",
    "style",
    "refactor",
    "perf",
    "test",
    "build",
    "ci",
    "chore",
    "revert",
)
HEADER = re.compile(
    rf"^(?P<type>{'|'.join(TYPES)})(?:\((?P<scope>[a-z0-9][a-z0-9-]*)\))?(?P<breaking>!)?: "
    r"(?P<description>\S.*)$"
)
MAX_HEADER = 100
# A description may start with a name written with more than one capital (CQRS, TypeScript).
NAME = re.compile(r"[A-Z]{2,}|[A-Z][a-z0-9]*[A-Z]")
AI_TRAILER = re.compile(r"^\s*co-authored-by:.*\b(claude|anthropic|copilot|chatgpt|openai)\b", re.I)
# Work in progress that git creates and squashes away locally; never acceptable on main.
AUTOSQUASH = re.compile(r"^(fixup|squash|amend)! ")


def problems(message: str, *, allow_autosquash: bool = False) -> list[str]:
    """What is wrong with one commit message (empty when it is fine)."""
    lines = [line for line in message.splitlines() if not line.startswith("#")]
    while lines and not lines[0].strip():
        lines.pop(0)
    if not lines:
        return ["the message is empty"]
    header = lines[0].rstrip()
    if allow_autosquash and AUTOSQUASH.match(header):
        return []
    out = []
    m = HEADER.match(header)
    if not m:
        out.append(
            f"the header {header!r} is not `type(scope): description`, with type one of "
            + ", ".join(TYPES)
        )
    elif m["description"][0].isupper() and not NAME.match(m["description"]):
        out.append(f"start the description in lower case: {m['description']!r}")
    if len(header) > MAX_HEADER:
        out.append(f"the header is {len(header)} characters (at most {MAX_HEADER})")
    if len(lines) > 1 and lines[1].strip():
        out.append("leave a blank line between the header and the body")
    for line in lines:
        if AI_TRAILER.match(line):
            out.append(f"remove the AI co-author trailer: {line.strip()!r}")
    return out


def commits(rev_range: str) -> list[tuple[str, str]]:
    """(sha, message) for every commit in the range, oldest first. Decoded as UTF-8 explicitly:
    Windows' default code page cannot read every message."""
    out = subprocess.run(
        ["git", "log", "--reverse", "--format=%H%x00%B%x00", rev_range],
        capture_output=True,
        encoding="utf-8",
        check=True,
    ).stdout
    parts = out.split("\x00")
    return [(parts[i].strip(), parts[i + 1]) for i in range(0, len(parts) - 1, 2)]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--file", type=Path, help="one message, as the commit-msg hook passes it")
    src.add_argument("--range", help="every commit in a git revision range, e.g. origin/main..HEAD")
    a = ap.parse_args(argv)

    if a.file:
        found = problems(a.file.read_text(encoding="utf-8"), allow_autosquash=True)
        for p in found:
            print(f"commit message: {p}", file=sys.stderr)
        if found:
            print("example: feat(engine): model connection pool queueing", file=sys.stderr)
        return 1 if found else 0

    bad = 0
    checked = commits(a.range)
    for sha, body in checked:
        for p in problems(body):
            print(f"{sha[:10]}: {p}", file=sys.stderr)
            bad += 1
    print(f"{len(checked)} commit(s) checked, {bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
