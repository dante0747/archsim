"""Record the schema the Python API's Alembic migrations build, for the Java app's Flyway baseline.

Migrates a fresh database to Alembic's head, describes its tables with ``parity/schema/describe.sql``
and writes the result to ``parity/schema/alembic-<revision>.json``. archsim-app's tests describe the
database its Flyway baseline builds with the same query and expect the same document (ADR-0032).
It needs a PostgreSQL server: it connects to the database ``--url`` names as an administrator, and
drops and creates the database ``archsim_schema_snapshot`` there.

    uv run python scripts/schema_snapshot.py --url postgresql://postgres@localhost:5432/postgres
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import psycopg
from psycopg.conninfo import make_conninfo
from sqlalchemy.engine import make_url

from archsim_api.migrations import upgrade_to_head

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "parity" / "schema"
DATABASE = "archsim_schema_snapshot"


def describe(admin_url: str) -> tuple[str, object]:
    """Migrate a fresh database with Alembic and describe it: (Alembic revision, schema)."""
    with psycopg.connect(admin_url, autocommit=True) as admin:
        admin.execute(f"DROP DATABASE IF EXISTS {DATABASE} WITH (FORCE)")
        admin.execute(f"CREATE DATABASE {DATABASE}")
    target = make_url(admin_url).set(drivername="postgresql+psycopg", database=DATABASE)
    upgrade_to_head(target.render_as_string(hide_password=False))
    with psycopg.connect(make_conninfo(admin_url, dbname=DATABASE)) as conn:
        row = conn.execute("SELECT version_num FROM alembic_version").fetchone()
        schema = conn.execute((SCHEMA / "describe.sql").read_text(encoding="utf-8")).fetchone()
    if row is None or schema is None:
        raise SystemExit("the migrated database has no Alembic revision or no tables")
    return str(row[0]), schema[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--url", required=True, help="a PostgreSQL database to connect to as admin")
    args = parser.parse_args()
    revision, schema = describe(args.url)
    out = SCHEMA / f"alembic-{revision}.json"
    out.write_text(json.dumps(schema, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
