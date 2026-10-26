"""Schema migrations for architecture documents.

Each migration upgrades a raw JSON document from one ``schemaVersion`` to the next. Documents
from a newer schema than this build supports are rejected rather than silently truncated.

To add a migration when bumping the schema (e.g. 1.0 -> 1.1)::

    @migration("1.0", "1.1")
    def _rename_field(doc: dict) -> dict:
        ...
        return doc
"""

from __future__ import annotations

import copy
from collections.abc import Callable
from typing import Any

from archsim_engine.version import SCHEMA_VERSION

Migration = Callable[[dict[str, Any]], dict[str, Any]]

_MIGRATIONS: dict[str, tuple[str, Migration]] = {}


class MigrationError(ValueError):
    pass


def migration(from_version: str, to_version: str) -> Callable[[Migration], Migration]:
    def register(fn: Migration) -> Migration:
        if from_version in _MIGRATIONS:
            raise ValueError(f"a migration from {from_version} is already registered")
        _MIGRATIONS[from_version] = (to_version, fn)
        return fn

    return register


def _parse(v: str) -> tuple[int, ...]:
    try:
        return tuple(int(p) for p in v.split("."))
    except ValueError:
        raise MigrationError(f"'{v}' is not a valid schemaVersion") from None


def migrate_document(
    raw: dict[str, Any],
    target: str = SCHEMA_VERSION,
    migrations: dict[str, tuple[str, Migration]] | None = None,
) -> tuple[dict[str, Any], list[str]]:
    """Return ``(document at target version, notes describing applied migrations)``."""
    if not isinstance(raw, dict):
        raise MigrationError("an architecture document must be a JSON object")
    version = raw.get("schemaVersion")
    if version is None:
        raise MigrationError("missing 'schemaVersion'; is this an ArchSim architecture document?")
    version = str(version)
    if _parse(version) > _parse(target):
        raise MigrationError(
            f"this document uses schema {version}, newer than the supported {target}. "
            "Upgrade ArchSim to open it."
        )
    table = _MIGRATIONS if migrations is None else migrations
    doc = copy.deepcopy(raw)
    notes: list[str] = []
    guard = 0
    while version != target:
        step = table.get(version)
        if step is None:
            raise MigrationError(f"no migration path from schema {version} to {target}")
        nxt, fn = step
        doc = fn(doc)
        doc["schemaVersion"] = nxt
        notes.append(f"migrated schema {version} -> {nxt}")
        version = nxt
        guard += 1
        if guard > 100:  # pragma: no cover - defensive
            raise MigrationError("migration chain does not terminate")
    return doc, notes


@migration("1.0", "1.1")
def _provenance_and_calibration(doc: dict[str, Any]) -> dict[str, Any]:
    """1.1 adds optional ``provenance`` and ``calibration``, the ``empirical`` distribution and
    the ``trace`` traffic pattern; a 1.0 document needs no change."""
    return doc


@migration("1.1", "1.2")
def _pricing(doc: dict[str, Any]) -> dict[str, Any]:
    """1.2 adds optional ``pricing`` (generic by default, as before); a 1.1 document needs no
    change."""
    return doc
