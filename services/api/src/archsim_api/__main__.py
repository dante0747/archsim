"""``archsim-api`` command: migrate. The HTTP API, the simulation workers and the seed are
``archsim-app`` (Java); this command stays with the Alembic migrations until stage 6 of the port."""

from __future__ import annotations

import argparse


def cmd_migrate(args: argparse.Namespace) -> int:
    from archsim_api.config import get_settings
    from archsim_api.migrations import upgrade_to_head

    url = get_settings().database_url
    upgrade_to_head(url)
    print(f"database at {url.split('@')[-1]} is at the latest schema")
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
    args = parser.parse_args(argv)
    code: int = args.fn(args)
    return code


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
