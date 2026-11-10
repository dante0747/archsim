"""``archsim-api`` command: migrate and seed. The HTTP API and the simulation workers are
``archsim-app`` (Java); this command stays with the Alembic migrations until stage 6 of the port."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def cmd_migrate(args: argparse.Namespace) -> int:
    from archsim_api.config import get_settings
    from archsim_api.migrations import upgrade_to_head

    url = get_settings().database_url
    upgrade_to_head(url)
    print(f"database at {url.split('@')[-1]} is at the latest schema")
    return 0


def cmd_seed(args: argparse.Namespace) -> int:
    """Create one project per example file unless a project with that name exists. The schema
    is the API's (Flyway), so seed never migrates: start the API first."""
    from sqlalchemy import select

    from archsim_api.config import get_settings
    from archsim_api.db import Database, Project
    from archsim_engine import load_document

    settings = get_settings()
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
    sub.add_parser(
        "migrate",
        help="apply the Alembic migrations, then exit: the schema up to revision 0008, where "
        "archsim-app's Flyway takes over (safe to run from several processes at once on "
        "PostgreSQL: they take turns)",
    ).set_defaults(fn=cmd_migrate)
    sub.add_parser("seed", help="create example projects").set_defaults(fn=cmd_seed)
    args = parser.parse_args(argv)
    code: int = args.fn(args)
    return code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
