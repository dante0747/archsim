"""Health, metadata, component catalog, examples, schema and validation."""

from __future__ import annotations

import copy
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy import text

from archsim_api.config import Settings
from archsim_api.db import Database
from archsim_api.deps import examples, get_db, get_registry, get_settings_dep
from archsim_api.schemas import ExampleOut, MetaOut, ValidateRequest, ValidateResponse
from archsim_engine import (
    ENGINE_VERSION,
    REPORT_VERSION,
    SCHEMA_VERSION,
    ComponentRegistry,
    DocumentInvalidError,
    analyze_document,
    document_json_schema,
    load_document,
    resolve_scenario,
    validate_document,
)
from archsim_engine.registry import describe_fields
from archsim_engine.schema import EdgeConfig, Workload

router = APIRouter(tags=["meta"])


@router.get("/health", responses={503: {"description": "The database is unreachable."}})
def health(db: Database = Depends(get_db)) -> JSONResponse:
    """200 when the API can reach its database, 503 otherwise, so load balancers and container
    health checks take the instance out of service."""
    try:
        with db.engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except Exception:
        return JSONResponse({"status": "degraded", "database": "unavailable"}, status_code=503)
    return JSONResponse({"status": "ok", "database": "ok"})


@router.get("/meta", response_model=MetaOut)
def meta(settings: Settings = Depends(get_settings_dep), db: Database = Depends(get_db)) -> MetaOut:
    return MetaOut.model_validate(
        {
            "engine_version": ENGINE_VERSION,
            "schema_version": SCHEMA_VERSION,
            "report_version": REPORT_VERSION,
            "database": db.dialect,
            "limits": {
                "max_requests": settings.max_requests,
                "max_wall_seconds": settings.max_wall_seconds,
                "max_concurrent_runs": settings.max_concurrent_runs,
            },
        }
    )


@router.get("/components")
def components(registry: ComponentRegistry = Depends(get_registry)) -> dict[str, Any]:
    """Component catalog: ports, config field descriptors (units, ranges, assumption flags),
    defaults. The editor renders its palette and inspector from this."""
    return {
        "components": registry.catalog(),
        "edgeFields": describe_fields(EdgeConfig),
        "workloadFields": describe_fields(Workload),
    }


@router.get("/schema/architecture")
def architecture_schema() -> dict[str, Any]:
    return document_json_schema()


@router.get("/examples", response_model=list[ExampleOut])
def list_examples(settings: Settings = Depends(get_settings_dep)) -> list[ExampleOut]:
    out = []
    for ex_id, d in examples(settings).items():
        md = d.get("metadata", {})
        out.append(
            ExampleOut(
                id=ex_id,
                name=md.get("name", ex_id),
                description=md.get("description"),
                tags=md.get("tags", []),
                scenario_count=len(d.get("scenarios", [])),
            )
        )
    return out


@router.get("/examples/{example_id}")
def get_example(example_id: str, settings: Settings = Depends(get_settings_dep)) -> dict[str, Any]:
    ex = examples(settings).get(example_id)
    if ex is None:
        raise HTTPException(
            404, detail={"message": f"Unknown example '{example_id}'", "issues": []}
        )
    return copy.deepcopy(ex)


@router.post("/validate", response_model=ValidateResponse)
def validate(
    body: ValidateRequest, registry: ComponentRegistry = Depends(get_registry)
) -> dict[str, Any]:
    """Validate a document (and optionally a scenario) without saving or simulating it."""
    try:
        doc = load_document(body.document)
        resolved, _ = resolve_scenario(doc, body.scenario_id, registry)
    except DocumentInvalidError as exc:
        return {"valid": False, "issues": [i.to_dict() for i in exc.issues]}
    return validate_document(resolved if body.scenario_id else doc, registry).to_dict()


@router.post(
    "/analyze",
    responses={422: {"description": "The document cannot be read (issues explain why)."}},
)
def analyze(
    body: ValidateRequest, registry: ComponentRegistry = Depends(get_registry)
) -> dict[str, Any]:
    """Auto sizes, cost estimate and design review of a document (and optionally a scenario),
    computed by the engine exactly as a run would use them, without simulating."""
    try:
        return analyze_document(body.document, registry, body.scenario_id)
    except DocumentInvalidError as exc:
        raise HTTPException(
            422,
            detail={
                "message": "The document cannot be analyzed",
                "issues": [i.to_dict() for i in exc.issues],
            },
        ) from None
