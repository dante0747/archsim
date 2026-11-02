"""Exports (Docker Compose, Kubernetes, Mermaid) and importers."""

from __future__ import annotations

from typing import Any

from archsim_engine.export import _auto_sized as export_auto_sized
from archsim_engine.export import export_document
from archsim_engine.export.compose import to_docker_compose
from archsim_engine.export.k8s import to_kubernetes
from archsim_engine.export.mermaid import to_mermaid
from archsim_engine.importers import import_design
from golden.adapters.support import Adapters, registry
from golden.codec import Json

ADAPTERS = Adapters()


@ADAPTERS.add("archsim_engine.export._auto_sized")
def _export_auto_sized(i: Json) -> Any:
    doc, needs = export_auto_sized(i["document"], registry())
    return {"document": doc, "needs": needs}


@ADAPTERS.add("archsim_engine.export.compose.to_docker_compose")
def _to_compose(i: Json) -> Any:
    return to_docker_compose(i["doc"], registry())


@ADAPTERS.add("archsim_engine.export.k8s.to_kubernetes")
def _to_k8s(i: Json) -> Any:
    return to_kubernetes(i["doc"], registry(), i["needs"])


@ADAPTERS.add("archsim_engine.export.mermaid.to_mermaid")
def _to_mermaid(i: Json) -> Any:
    return to_mermaid(i["doc"], registry())


@ADAPTERS.add("archsim_engine.export.export_document")
def _export(i: Json) -> Any:
    return export_document(i["document"], i["fmt"], registry(), i["autoSize"])


@ADAPTERS.add("archsim_engine.importers.import_design")
def _import(i: Json) -> Any:
    doc, report = import_design(i["fmt"], i["text"], registry(), i["name"])
    return {"document": doc, "report": report.to_dict()}
