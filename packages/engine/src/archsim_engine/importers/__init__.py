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
SHAPES = {
    "compose": "a docker-compose file",
    "k8s": "Kubernetes manifests",
    "terraform": "`terraform show -json` output",
}


def import_design(
    fmt: Format, text: str, registry: ComponentRegistry, name: str | None = None
) -> tuple[dict[str, Any], ImportReport]:
    """A design from infrastructure text, validated; issues go into the report. Text that is
    not the format (bad YAML or JSON, a list where a mapping belongs...) raises ValueError
    with what was expected, never another exception."""
    import yaml

    from archsim_engine import load_document, validate_document

    importers = {"compose": import_compose, "k8s": import_k8s, "terraform": import_terraform}
    if fmt not in importers:
        raise ValueError(f"unknown import format '{fmt}' (compose, k8s, terraform)")
    try:
        doc, report = importers[fmt](text, registry, name)
    except ValueError:
        raise
    except yaml.YAMLError as exc:
        raise ValueError(f"not valid YAML: {exc}") from None
    except RecursionError:
        raise ValueError(f"the text nests too deeply to read as {SHAPES[fmt]}") from None
    except (AttributeError, TypeError, KeyError, IndexError) as exc:
        # a value of the wrong kind where the format expects a mapping, list or name
        detail = f"missing {exc}" if isinstance(exc, KeyError) else str(exc)
        raise ValueError(f"this is not {SHAPES[fmt]} the import can read ({detail})") from None
    parsed = load_document(doc)
    result = validate_document(parsed, registry)
    report.issues = [i.to_dict() for i in result.issues]
    return parsed.to_json_dict(), report


__all__ = ["ImportReport", "import_compose", "import_design", "import_k8s", "import_terraform"]
