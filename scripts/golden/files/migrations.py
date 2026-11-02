"""The migrations fixture: the smallest template stamped with every schema version, and the
version errors."""

from __future__ import annotations

from typing import Any

from archsim_engine.version import SCHEMA_VERSION
from golden.file import GoldenFile
from golden.paths import EXAMPLES, read_json


def migrations_file() -> GoldenFile:
    """Documents of every schema version (the smallest template re-stamped), and the version
    errors."""
    g = GoldenFile("fixtures", name="migrations")
    base = read_json(EXAMPLES / "queueing-lab.json")
    cases: list[tuple[str, Any]] = []
    for minor in range(int(SCHEMA_VERSION.split(".")[1]) + 1):
        cases.append((f"1.{minor}", {**base, "schemaVersion": f"1.{minor}"}))
    cases += [
        ("number-1.3", {**base, "schemaVersion": 1.3}),
        ("1.3.0", {**base, "schemaVersion": "1.3.0"}),
        ("1.10", {**base, "schemaVersion": "1.10"}),
        ("2.0", {**base, "schemaVersion": "2.0"}),
        ("0.9", {**base, "schemaVersion": "0.9"}),
        ("not-a-version", {**base, "schemaVersion": "one"}),
        ("empty-version", {**base, "schemaVersion": ""}),
        ("missing-version", {k: v for k, v in base.items() if k != "schemaVersion"}),
        ("not-an-object", [base]),
        ("empty-object", {}),
    ]
    for name, raw in cases:
        where = ("cases", name)
        g.entry(where, "migrate_document", "archsim_engine.migrations.migrate_document",
                {"raw": raw}, errors=True)  # fmt: skip
        g.entry(where, "load_document", "archsim_engine.scenarios.load_document",
                {"raw": raw}, errors=True)  # fmt: skip
    g.cases = len(cases)
    return g
