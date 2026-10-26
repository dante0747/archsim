"""Imports (``POST /import``): a design from a docker-compose file, Kubernetes manifests or a
Terraform plan (``terraform show -json``), with the import report. Nothing is stored: the web app
opens the result as a new, unsaved design."""

from __future__ import annotations

from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field

from archsim_api.deps import get_registry
from archsim_api.schemas import ApiModel
from archsim_engine import ComponentRegistry, DocumentInvalidError
from archsim_engine.importers import import_design

router = APIRouter(tags=["import"])


class ImportRequest(ApiModel):
    format: Literal["compose", "k8s", "terraform"]
    text: str = Field(
        description="The file's content (Terraform: the JSON of terraform show -json)"
    )
    name: str | None = Field(default=None, min_length=1, max_length=120)


@router.post(
    "/import",
    responses={422: {"description": "The text cannot be read as that format."}},
)
def import_(
    body: ImportRequest, registry: ComponentRegistry = Depends(get_registry)
) -> dict[str, Any]:
    """The design and the import report: what was mapped, what was guessed (marked assumed), what
    was ignored, links to confirm, and validation issues to fix before a run."""
    try:
        doc, report = import_design(body.format, body.text, registry, body.name)
    except DocumentInvalidError as exc:
        raise HTTPException(
            422,
            detail={
                "message": "The imported design is not valid",
                "issues": [i.to_dict() for i in exc.issues],
            },
        ) from None
    except (ValueError, TypeError, KeyError) as exc:
        raise HTTPException(
            422, detail={"message": f"Cannot import: {exc}", "issues": []}
        ) from None
    return {"document": doc, "report": report.to_dict()}
