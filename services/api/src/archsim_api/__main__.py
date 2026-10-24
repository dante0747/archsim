"""``archsim-api`` command: serve, migrate, seed."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def cmd_serve(args: argparse.Namespace) -> int:
    import uvicorn

    uvicorn.run(
        "archsim_api.main:app_factory",
        factory=True,
        host=args.host,
        port=args.port,
        reload=args.reload,
    )
    return 0


def cmd_migrate(args: argparse.Namespace) -> int:
    from archsim_api.config import get_settings
    from archsim_api.migrations import upgrade_to_head

    url = get_settings().database_url
    upgrade_to_head(url)
    print(f"database at {url.split('@')[-1]} is at the latest schema")
    return 0


def cmd_seed(args: argparse.Namespace) -> int:
    """Create one project per example file unless a project with that name exists."""
    from sqlalchemy import select

    from archsim_api.config import get_settings
    from archsim_api.db import Database, Project
    from archsim_api.migrations import upgrade_to_head
    from archsim_engine import load_document

    settings = get_settings()
    upgrade_to_head(settings.database_url)
    db = Database(settings.database_url)
    created = 0
    with db.session() as s:
        existing = set(s.execute(select(Project.name)).scalars())
        for f in sorted(Path(settings.examples_dir).glob("*.json")):
            doc = load_document(json.loads(f.read_text(encoding="utf-8"))).to_json_dict()
            name = doc["metadata"]["name"]
            if name in existing:
                continue
            s.add(
                Project(
                    name=name,
                    description=doc["metadata"].get("description"),
                    document=doc,
                    schema_version=doc["schemaVersion"],
                )
            )
            created += 1
        s.commit()
    print(f"seeded {created} example project(s)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="archsim-api")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("serve", help="run the HTTP API")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--reload", action="store_true")
    p.set_defaults(fn=cmd_serve)
    sub.add_parser("migrate", help="apply database migrations").set_defaults(fn=cmd_migrate)
    sub.add_parser("seed", help="create example projects").set_defaults(fn=cmd_seed)
    args = parser.parse_args(argv)
    code: int = args.fn(args)
    return code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
