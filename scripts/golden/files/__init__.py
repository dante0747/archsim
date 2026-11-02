"""What each golden file records: the template files (one per template in ``examples/``), the
catalogs and the fixtures, by the name a file has under ``parity/golden/`` (without
``.json.gz``)."""

from __future__ import annotations

from collections.abc import Callable

from golden.file import GoldenFile
from golden.files.calibration import calibration_file
from golden.files.catalogs import (
    assumptions_file,
    components_file,
    formatting_file,
    pricing_file,
    schema_file,
)
from golden.files.importers import importers_file
from golden.files.invalid_documents import invalid_file
from golden.files.migrations import migrations_file
from golden.files.templates import template_file, template_ids

JOBS: dict[str, Callable[[], GoldenFile]] = {
    "catalog/components": components_file,
    "catalog/schema": schema_file,
    "catalog/pricing": pricing_file,
    "catalog/assumptions": assumptions_file,
    "catalog/formatting": formatting_file,
    "fixtures/migrations": migrations_file,
    "fixtures/invalid-documents": invalid_file,
    "fixtures/importers": importers_file,
    "fixtures/calibration": calibration_file,
}
"""The files that are not templates, each with what records it."""

TEMPLATES = "templates/"


def job_names(only: str | None = None) -> list[str]:
    """Every golden file's name, or just ``only`` (a template id, or a name such as
    ``catalog/pricing``)."""
    templates = [f"{TEMPLATES}{t}" for t in template_ids()]
    if only is not None:
        name = only if "/" in only else f"{TEMPLATES}{only}"
        if name not in templates and name not in JOBS:
            raise SystemExit(
                f"unknown golden file '{only}' (a template id, or one of {list(JOBS)})"
            )
        return [name]
    return templates + list(JOBS)


def record(name: str) -> GoldenFile:
    """Record the golden file ``name``."""
    if name.startswith(TEMPLATES):
        return template_file(name.removeprefix(TEMPLATES))
    return JOBS[name]()
