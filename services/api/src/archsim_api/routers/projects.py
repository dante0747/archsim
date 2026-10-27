"""Projects: saved architecture documents, import/export. Each project belongs to a workspace:
lists and new projects use the request's workspace (``?workspace=`` or the
``X-ArchSim-Workspace`` header, else the caller's own), and a project asked for by id answers
404 to anyone without a role in its workspace."""

from __future__ import annotations

import copy
import re
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Response
from fastapi.responses import JSONResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from archsim_api import limits
from archsim_api.audit import audit_project
from archsim_api.config import Settings
from archsim_api.db import Project, Workspace, utcnow
from archsim_api.deps import (
    examples,
    get_principal,
    get_session,
    get_settings_dep,
    get_workspace,
    raise_issues,
    require_project,
)
from archsim_api.schemas import ProjectCreate, ProjectOut, ProjectSummaryOut, ProjectUpdate
from archsim_api.security import Principal, ProjectAccess, WorkspaceAccess
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


def _check_room(session: Session, settings: Settings, workspace_id: str) -> None:
    """A workspace whose kind caps its projects (the sandbox) refuses one more past the cap."""
    ws = session.get(Workspace, workspace_id)
    cap = limits.profile(settings, ws.kind).max_projects if ws is not None else None
    if cap is None:
        return
    held = session.execute(
        select(func.count()).select_from(Project).where(Project.workspace_id == workspace_id)
    ).scalar_one()
    if held >= cap:
        raise HTTPException(
            409,
            detail={
                "message": f"This workspace holds at most {cap} projects: delete one first.",
                "issues": [],
            },
        )


def _create(
    session: Session,
    workspace_id: str,
    created_by: str,
    document: dict[str, Any],
    name: str | None,
    description: str | None,
    source_example: str | None = None,
    settings: Settings | None = None,
) -> Project:
    doc = _normalize(document, name)
    if settings is not None:
        _check_room(session, settings, workspace_id)
    p = Project(
        workspace_id=workspace_id,
        created_by=created_by,
        name=doc["metadata"]["name"],
        description=description if description is not None else doc["metadata"].get("description"),
        document=doc,
        schema_version=doc["schemaVersion"],
        source_example=source_example,
    )
    session.add(p)
    session.flush()  # p.id
    audit_project(session, "project.create", p, {"fromExample": source_example}, created_by)
    session.commit()
    return p


@router.get("", response_model=list[ProjectSummaryOut])
def list_projects(
    access: WorkspaceAccess = Depends(get_workspace), session: Session = Depends(get_session)
) -> list[dict[str, Any]]:
    """The workspace's projects, most recently updated first."""
    rows = (
        session.execute(
            select(Project)
            .where(Project.workspace_id == access.workspace.id)
            .order_by(Project.updated_at.desc())
        )
        .scalars()
        .all()
    )
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
    access: WorkspaceAccess = Depends(get_workspace),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ProjectOut:
    """Create a project in the workspace from a document, an example (``exampleId``) or blank.

    With ``reuseUnedited`` (and an example, no name), opening the same template twice returns
    the copy *you* made in this workspace the first time, as long as it was never saved,
    instead of a duplicate. Someone else's copy is never handed back."""
    access.require("editor")
    ws = access.workspace.id
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
                .where(
                    Project.workspace_id == ws,
                    Project.created_by == principal.user_id,
                    Project.source_example == body.example_id,
                    Project.revision == 1,
                )
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
    return _out(
        _create(
            session,
            ws,
            principal.user_id,
            document,
            body.name,
            body.description,
            source,
            settings,
        )
    )


@router.post("/import", response_model=ProjectOut, status_code=201)
def import_project(
    document: dict[str, Any] = Body(..., description="A versioned ArchSim document (JSON)"),
    name: str | None = None,
    access: WorkspaceAccess = Depends(get_workspace),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ProjectOut:
    """Import a document exported earlier into the workspace (older schema versions are
    migrated)."""
    access.require("editor")
    return _out(
        _create(
            session, access.workspace.id, principal.user_id, document, name, None, None, settings
        )
    )


@router.get("/{project_id}", response_model=ProjectOut)
def get_project(access: ProjectAccess = Depends(require_project("viewer"))) -> ProjectOut:
    return _out(access.project)


@router.put("/{project_id}", response_model=ProjectOut)
def update_project(
    body: ProjectUpdate,
    access: ProjectAccess = Depends(require_project("editor")),
    session: Session = Depends(get_session),
) -> ProjectOut:
    p = access.project
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
    audit_project(session, "project.update", p, {"revision": p.revision})
    session.commit()
    return _out(p)


@router.delete("/{project_id}", status_code=204)
def delete_project(
    access: ProjectAccess = Depends(require_project("editor")),
    session: Session = Depends(get_session),
) -> Response:
    audit_project(session, "project.delete", access.project)
    session.delete(access.project)
    session.commit()
    return Response(status_code=204)


@router.post("/{project_id}/duplicate", response_model=ProjectOut, status_code=201)
def duplicate_project(
    access: ProjectAccess = Depends(require_project("editor")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> ProjectOut:
    """Copy as "Name (copy)", in the same workspace."""
    p = access.project
    return _out(
        _create(
            session,
            p.workspace_id,
            principal.user_id,
            copy.deepcopy(p.document),
            f"{p.name} (copy)"[:120],
            p.description,
            None,
            settings,
        )
    )


@router.get("/{project_id}/export")
def export_project(access: ProjectAccess = Depends(require_project("viewer"))) -> JSONResponse:
    """Download the project as a versioned JSON document."""
    p = access.project
    slug = re.sub(r"[^a-z0-9]+", "-", p.name.lower()).strip("-") or "architecture"
    return JSONResponse(
        p.document,
        headers={"Content-Disposition": f'attachment; filename="{slug}.archsim.json"'},
    )
