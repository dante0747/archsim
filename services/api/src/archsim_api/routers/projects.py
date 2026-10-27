"""Projects: saved architecture documents, import/export."""

from __future__ import annotations

import copy
import re
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Response
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api.config import Settings
from archsim_api.db import Project, utcnow
from archsim_api.deps import examples, get_session, get_settings_dep, raise_issues
from archsim_api.schemas import ProjectCreate, ProjectOut, ProjectSummaryOut, ProjectUpdate
from archsim_engine import SCHEMA_VERSION, DocumentInvalidError, load_document
from archsim_engine.pricing import default_pricing

router = APIRouter(prefix="/projects", tags=["projects"])


def blank_document() -> dict[str, Any]:
    """A new, empty design. It pins the newest AWS us-east-1 price snapshot the engine ships
    (generic rates when it ships none), like the templates; documents saved before keep the
    prices they name."""
    return {
        "schemaVersion": SCHEMA_VERSION,
        "kind": "archsim.architecture",
        "metadata": {"name": "Untitled architecture"},
        "architecture": {"nodes": [], "edges": []},
        "workload": {},
        "scenarios": [],
        "layout": {"nodes": {}},
        "pricing": default_pricing(),
    }


def _normalize(raw: dict[str, Any], name: str | None = None) -> dict[str, Any]:
    """Migrate + schema-check a document. Graph-level problems (e.g. a missing link) are allowed
    so users can save work in progress; they are reported by /validate and block simulation."""
    try:
        doc = load_document(raw)
    except DocumentInvalidError as exc:
        raise_issues(422, f"The document is not a valid ArchSim document: {exc}", exc.issues)
    if name:
        doc.metadata.name = name
    return doc.to_json_dict()


def _summary(p: Project) -> dict[str, Any]:
    arch = p.document.get("architecture", {})
    return {
        "id": p.id,
        "name": p.name,
        "description": p.description,
        "revision": p.revision,
        "created_at": p.created_at,
        "updated_at": p.updated_at,
        "node_count": len(arch.get("nodes", [])),
        "scenario_count": len(p.document.get("scenarios", [])),
    }


def _out(p: Project) -> ProjectOut:
    return ProjectOut.model_validate({**_summary(p), "document": p.document})


def get_project_or_404(session: Session, project_id: str) -> Project:
    p = session.get(Project, project_id)
    if p is None:
        raise HTTPException(
            404, detail={"message": f"Project '{project_id}' not found", "issues": []}
        )
    return p


def _create(
    session: Session,
    document: dict[str, Any],
    name: str | None,
    description: str | None,
    source_example: str | None = None,
) -> Project:
    doc = _normalize(document, name)
    p = Project(
        name=doc["metadata"]["name"],
        description=description if description is not None else doc["metadata"].get("description"),
        document=doc,
        schema_version=doc["schemaVersion"],
        source_example=source_example,
    )
    session.add(p)
    session.commit()
    return p


@router.get("", response_model=list[ProjectSummaryOut])
def list_projects(session: Session = Depends(get_session)) -> list[dict[str, Any]]:
    rows = session.execute(select(Project).order_by(Project.updated_at.desc())).scalars().all()
    return [_summary(p) for p in rows]


@router.post(
    "",
    response_model=ProjectOut,
    status_code=201,
    responses={200: {"description": "An unedited copy of the example was reused."}},
)
def create_project(
    body: ProjectCreate,
    response: Response,
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ProjectOut:
    """Create a project from a document, an example (``exampleId``) or blank.

    With ``reuseUnedited`` (and an example, no name), opening the same template twice returns
    the copy made the first time as long as it was never saved, instead of a duplicate."""
    if body.document is not None and body.example_id is not None:
        raise HTTPException(
            400, detail={"message": "Pass either document or exampleId, not both", "issues": []}
        )
    if body.example_id is not None:
        ex = examples(settings).get(body.example_id)
        if ex is None:
            raise HTTPException(
                404, detail={"message": f"Unknown example '{body.example_id}'", "issues": []}
            )
        if body.reuse_unedited and body.name is None:
            existing = session.execute(
                select(Project)
                .where(Project.source_example == body.example_id, Project.revision == 1)
                .order_by(Project.updated_at.desc())
                .limit(1)
            ).scalar_one_or_none()
            # A copy of an older version of the template is not reused: open the current one.
            if existing is not None and existing.document == _normalize(copy.deepcopy(ex)):
                response.status_code = 200
                return _out(existing)
        document = copy.deepcopy(ex)
    else:
        document = copy.deepcopy(body.document) if body.document else blank_document()
    # A project the user named is theirs, not a copy that opening the template may hand back.
    source = body.example_id if body.name is None else None
    return _out(_create(session, document, body.name, body.description, source))


@router.post("/import", response_model=ProjectOut, status_code=201)
def import_project(
    document: dict[str, Any] = Body(..., description="A versioned ArchSim document (JSON)"),
    name: str | None = None,
    session: Session = Depends(get_session),
) -> ProjectOut:
    """Import a document exported earlier (older schema versions are migrated)."""
    return _out(_create(session, document, name, None))


@router.get("/{project_id}", response_model=ProjectOut)
def get_project(project_id: str, session: Session = Depends(get_session)) -> ProjectOut:
    return _out(get_project_or_404(session, project_id))


@router.put("/{project_id}", response_model=ProjectOut)
def update_project(
    project_id: str, body: ProjectUpdate, session: Session = Depends(get_session)
) -> ProjectOut:
    p = get_project_or_404(session, project_id)
    if body.expected_revision is not None and body.expected_revision != p.revision:
        raise HTTPException(
            409,
            detail={
                "message": f"Project was modified elsewhere (revision {p.revision}, you had "
                f"{body.expected_revision}). Reload before saving.",
                "issues": [],
            },
        )
    name = body.name or p.name
    if body.document is not None:
        p.document = _normalize(body.document, name)
        p.schema_version = p.document["schemaVersion"]
    elif body.name:
        doc = copy.deepcopy(p.document)
        doc.setdefault("metadata", {})["name"] = name
        p.document = doc
    p.name = name
    if body.description is not None:
        p.description = body.description
    p.revision += 1
    p.updated_at = utcnow()
    session.commit()
    return _out(p)


@router.delete("/{project_id}", status_code=204)
def delete_project(project_id: str, session: Session = Depends(get_session)) -> Response:
    p = get_project_or_404(session, project_id)
    session.delete(p)
    session.commit()
    return Response(status_code=204)


@router.post("/{project_id}/duplicate", response_model=ProjectOut, status_code=201)
def duplicate_project(project_id: str, session: Session = Depends(get_session)) -> ProjectOut:
    p = get_project_or_404(session, project_id)
    return _out(
        _create(session, copy.deepcopy(p.document), f"{p.name} (copy)"[:120], p.description)
    )


@router.get("/{project_id}/export")
def export_project(project_id: str, session: Session = Depends(get_session)) -> JSONResponse:
    """Download the project as a versioned JSON document."""
    p = get_project_or_404(session, project_id)
    slug = re.sub(r"[^a-z0-9]+", "-", p.name.lower()).strip("-") or "architecture"
    return JSONResponse(
        p.document,
        headers={"Content-Disposition": f'attachment; filename="{slug}.archsim.json"'},
    )
