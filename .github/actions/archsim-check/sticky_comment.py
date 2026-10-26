"""Post or update ArchSim's one sticky comment on a pull request (GitHub REST API).

    GITHUB_TOKEN=... python sticky_comment.py --repo owner/name --pr 12 --body-file archsim-check.md

The comment is found by the marker ``<!-- archsim-check -->`` its body starts with: updated when
it exists, created otherwise. Only api.github.com (or GITHUB_API_URL) is called, with the token
the workflow gives the job.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

MARKER = "<!-- archsim-check -->"
MAX_PAGES = 50
"""Pull requests with more than 5,000 comments get a new comment rather than a long search."""
Request = Callable[[str, str, dict[str, Any] | None], Any]


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


def upsert(request: Request, repo: str, pr: int, body: str) -> tuple[str, int]:
    """Update the comment carrying the marker, else create one. Returns (action, comment id)."""
    if MARKER not in body:
        body = f"{MARKER}\n{body}"
    for page in range(1, MAX_PAGES + 1):
        comments = request("GET", f"/repos/{repo}/issues/{pr}/comments?per_page=100&page={page}", None) or []
        for c in comments:
            if MARKER in (c.get("body") or ""):
                request("PATCH", f"/repos/{repo}/issues/comments/{c['id']}", {"body": body})
                return "updated", int(c["id"])
        if len(comments) < 100:
            break
    created = request("POST", f"/repos/{repo}/issues/{pr}/comments", {"body": body})
    return "created", int(created["id"])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--pr", type=int, required=True)
    ap.add_argument("--body-file", required=True)
    args = ap.parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("GITHUB_TOKEN is not set: no comment posted", file=sys.stderr)
        return 0
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    action, cid = upsert(http(token, api), args.repo, args.pr, Path(args.body_file).read_text(encoding="utf-8"))
    print(f"{action} comment {cid}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
