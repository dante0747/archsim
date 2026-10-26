"""Importing what teams already have: docker-compose files, Kubernetes manifests and Terraform
plans become a design plus an import report (what was mapped, what was guessed and marked
assumed, what was ignored, and links to confirm). See docs/import.md."""

from __future__ import annotations

from typing import Any, Literal

from archsim_engine.importers.common import ImportReport
from archsim_engine.importers.compose import import_compose
from archsim_engine.importers.k8s import import_k8s
from archsim_engine.importers.terraform import import_terraform
from archsim_engine.registry import ComponentRegistry

Format = Literal["compose", "k8s", "terraform"]


def import_design(
    fmt: Format, text: str, registry: ComponentRegistry, name: str | None = None
) -> tuple[dict[str, Any], ImportReport]:
    """A design from infrastructure text, validated; issues go into the report."""
    from archsim_engine import load_document, validate_document

    if fmt == "compose":
        doc, report = import_compose(text, registry, name)
    elif fmt == "k8s":
        doc, report = import_k8s(text, registry, name)
    elif fmt == "terraform":
        doc, report = import_terraform(text, registry, name)
    else:
        raise ValueError(f"unknown import format '{fmt}' (compose, k8s, terraform)")
    parsed = load_document(doc)
    result = validate_document(parsed, registry)
    report.issues = [i.to_dict() for i in result.issues]
    return parsed.to_json_dict(), report


__all__ = ["ImportReport", "import_compose", "import_design", "import_k8s", "import_terraform"]
