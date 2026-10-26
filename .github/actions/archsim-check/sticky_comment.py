"""Post or update ArchSim's one sticky comment on a pull request (GitHub REST API).

    GITHUB_TOKEN=... python sticky_comment.py --repo owner/name --pr 12 --body-file archsim-check.md \
        --key archsim/design.json

The comment is the one whose body starts with the marker ``<!-- archsim-check KEY -->`` and that
the job's own account posted (``github-actions[bot]`` by default; ``--author`` for a GitHub App
or personal token): updated when it exists, created otherwise. A comment anyone else posts with
the marker is never taken over. The key (the design file, plus the scenario) gives every design
checked on one pull request its own comment. Only api.github.com (or GITHUB_API_URL) is called,
with the token the workflow gives the job.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

MARKER = "<!-- archsim-check -->"
"""What ``archsim check --format markdown`` starts with; replaced by the keyed marker."""
DEFAULT_AUTHOR = "github-actions[bot]"
"""The login comments made with the workflow's GITHUB_TOKEN carry."""
MAX_PAGES = 50
"""Pull requests with more than 5,000 comments get a new comment rather than a long search."""
Request = Callable[[str, str, dict[str, Any] | None], Any]


def marker(key: str | None = None) -> str:
    """The hidden marker of the comment for ``key``: one line, no ``-->`` or HTML inside."""
    if not key:
        return MARKER
    safe = re.sub(r"[^A-Za-z0-9._/#=+@-]", "_", key)[:200]
    return f"<!-- archsim-check {safe} -->"


def http(token: str, api: str) -> Request:
    def request(method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        req = urllib.request.Request(
            f"{api}{path}",
            method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"null")

    return request


def upsert(
    request: Request,
    repo: str,
    pr: int,
    body: str,
    key: str | None = None,
    author: str = DEFAULT_AUTHOR,
) -> tuple[str, int]:
    """Update this job's comment for ``key``, else create one. Returns (action, comment id)."""
    mark = marker(key)
    if body.startswith(MARKER):
        body = body[len(MARKER) :].lstrip("\r\n")
    body = f"{mark}\n{body}"
    for page in range(1, MAX_PAGES + 1):
        path = f"/repos/{repo}/issues/{pr}/comments?per_page=100&page={page}"
        comments = request("GET", path, None) or []
        for c in comments:
            login = (c.get("user") or {}).get("login")
            if login == author and (c.get("body") or "").startswith(mark):
                request("PATCH", f"/repos/{repo}/issues/comments/{c['id']}", {"body": body})
                return "updated", int(c["id"])
        if len(comments) < 100:
            break
    created = request("POST", f"/repos/{repo}/issues/{pr}/comments", {"body": body})
    return "created", int(created["id"])


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--repo", required=True)
    ap.add_argument("--pr", type=int, required=True)
    ap.add_argument("--body-file", required=True)
    ap.add_argument("--key", default="", help="one comment per key (the design file, scenario)")
    ap.add_argument(
        "--author",
        default=os.environ.get("ARCHSIM_COMMENT_AUTHOR") or DEFAULT_AUTHOR,
        help=f"the login the token posts as (default {DEFAULT_AUTHOR})",
    )
    args = ap.parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("GITHUB_TOKEN is not set: no comment posted", file=sys.stderr)
        return 0
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    body = Path(args.body_file).read_text(encoding="utf-8")
    action, cid = upsert(http(token, api), args.repo, args.pr, body, args.key, args.author)
    print(f"{action} comment {cid}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
