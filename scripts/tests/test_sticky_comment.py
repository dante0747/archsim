"""The archsim-check action's sticky comment script (.github/actions/archsim-check/
sticky_comment.py): it updates its own comment on a pull request, never another's."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

ACTION = Path(__file__).resolve().parents[2] / ".github" / "actions" / "archsim-check"
MARKER = "<!-- archsim-check -->"
"""The comment's marker, as the action writes it."""

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
