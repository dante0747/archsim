"""Exports: docker-compose, Kubernetes manifests and a Mermaid diagram of a design.

``export_document`` fills empty CPU, memory and storage specs with the engine's auto sizes first
(as a run would use them), so exported resources match what was simulated. Scaffolding to
review, not production configuration.
"""

from __future__ import annotations

import copy
from typing import Any, Literal

from archsim_engine.export.compose import to_docker_compose
from archsim_engine.export.k8s import to_kubernetes
from archsim_engine.export.mermaid import to_mermaid
from archsim_engine.registry import ComponentRegistry

Format = Literal["compose", "k8s", "mermaid"]
FORMATS: dict[str, tuple[str, str]] = {
    "compose": ("docker-compose.yml", "application/yaml"),
    "k8s": ("k8s.yaml", "application/yaml"),
    "mermaid": ("mmd", "text/plain"),
}
SPEC_KEYS = ("cpuCores", "memoryGib", "storageGib")


def with_auto_sizes(document: dict[str, Any], registry: ComponentRegistry) -> dict[str, Any]:
    """The document with empty specs filled with the engine's auto sizes."""
    return _auto_sized(document, registry)[0]


def _auto_sized(
    document: dict[str, Any], registry: ComponentRegistry
) -> tuple[dict[str, Any], dict[str, float]]:
    """The document with empty specs filled with the engine's auto sizes, and the CPU each part
    whose CPU was auto-sized needs (its cores at the target utilization, a fraction of a core
    for a light part): a Kubernetes CPU request, below the whole cores it is simulated with."""
    from archsim_engine.analyze import analyze_document

    doc = copy.deepcopy(document)
    sizing = analyze_document(doc, registry)["sizing"]
    needs: dict[str, float] = {}
    if not sizing.get("auto", True):
        return doc, needs
    for node in doc["architecture"]["nodes"]:
        entry = sizing["nodes"].get(node["id"])
        if not entry or entry.get("skipped"):
            continue
        cfg = node.setdefault("config", {})
        need = (entry.get("need") or {}).get("cpuCores")
        if isinstance(need, int | float) and not isinstance(cfg.get("cpuCores"), int | float):
            needs[node["id"]] = float(need)
        for key in SPEC_KEYS:
            value = (entry.get("auto") or {}).get(key)
            if value is not None and not isinstance(cfg.get(key), int | float):
                cfg[key] = value
    return doc, needs


def export_document(
    document: dict[str, Any], fmt: Format, registry: ComponentRegistry, auto_size: bool = True
) -> str:
    """The design as ``fmt`` text."""
    from archsim_engine.scenarios import load_document

    doc = load_document(document).to_json_dict()
    needs: dict[str, float] = {}
    if auto_size:
        doc, needs = _auto_sized(doc, registry)
    if fmt == "compose":
        return to_docker_compose(doc, registry)
    if fmt == "k8s":
        return to_kubernetes(doc, registry, needs)
    if fmt == "mermaid":
        return to_mermaid(doc, registry)
    raise ValueError(f"unknown export format '{fmt}' (compose, k8s, mermaid)")


__all__ = [
    "FORMATS",
    "export_document",
    "to_docker_compose",
    "to_kubernetes",
    "to_mermaid",
    "with_auto_sizes",
]
