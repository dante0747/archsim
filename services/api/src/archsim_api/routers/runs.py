"""Simulation runs: create (async), poll, cancel, reproduce, fetch reports, compare."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Response
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session, undefer

from archsim_api.config import Settings
from archsim_api.db import SimulationRun
from archsim_api.deps import get_jobs, get_registry, get_session, get_settings_dep, raise_issues
from archsim_api.jobs import ACTIVE, ThreadJobRunner
from archsim_api.routers.projects import get_project_or_404
from archsim_api.schemas import BatchRunCreate, RunCreate, RunOut
from archsim_engine import (
    ENGINE_VERSION,
    ComponentRegistry,
    DocumentInvalidError,
    SimulationLimitError,
    SimulationLimits,
    compare_reports,
    prepare_run,
)

router = APIRouter(tags=["runs"])


def _limits(settings: Settings) -> SimulationLimits:
    return SimulationLimits(
        max_requests=settings.max_requests, max_wall_seconds=settings.max_wall_seconds
    )


def _run_out(run: SimulationRun, reproduces: str | None = None) -> RunOut:
    # progress comes from the row: the worker running it writes it at most once a second, from
    # whichever process or host it is on
    out = RunOut.model_validate(run)
    out.reproduces_run_id = reproduces
    return out


def _enqueue(
    session: Session,
    jobs: ThreadJobRunner,
    registry: ComponentRegistry,
    settings: Settings,
    project_id: str,
    document: dict[str, Any],
    scenario_id: str | None,
    label: str | None,
) -> SimulationRun:
    try:
        prep = prepare_run(document, scenario_id, registry, _limits(settings))
    except DocumentInvalidError as exc:
        raise_issues(422, f"Cannot simulate: {exc}", exc.issues)
    except SimulationLimitError as exc:
        raise_issues(422, str(exc))
    run = SimulationRun(
        project_id=project_id,
        label=label or prep.scenario_name or "Current design",
        scenario_id=prep.scenario_id,
        scenario_name=prep.scenario_name,
        status="queued",
        progress=0.0,
        seed=prep.resolved.workload.seed,
        engine_version=ENGINE_VERSION,
        input_document=prep.document.to_json_dict(),
    )
    session.add(run)
    session.commit()
    jobs.submit(run.id)
    return run


def get_run_or_404(session: Session, run_id: str, with_payload: bool = False) -> SimulationRun:
    stmt = select(SimulationRun).where(SimulationRun.id == run_id)
    if with_payload:
        stmt = stmt.options(undefer(SimulationRun.report), undefer(SimulationRun.input_document))
    run = session.execute(stmt).scalar_one_or_none()
    if run is None:
        raise HTTPException(404, detail={"message": f"Run '{run_id}' not found", "issues": []})
    return run


@router.post("/projects/{project_id}/runs", response_model=RunOut, status_code=202)
def create_run(
    project_id: str,
    body: RunCreate,
    session: Session = Depends(get_session),
    jobs: ThreadJobRunner = Depends(get_jobs),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
) -> RunOut:
    """Validate and enqueue a simulation. Returns immediately; poll ``GET /runs/{id}``."""
    project = get_project_or_404(session, project_id)
    document = body.document if body.document is not None else project.document
    run = _enqueue(
        session, jobs, registry, settings, project_id, document, body.scenario_id, body.label
    )
    return _run_out(run)


@router.post("/projects/{project_id}/runs/batch", response_model=list[RunOut], status_code=202)
def create_batch(
    project_id: str,
    body: BatchRunCreate,
    session: Session = Depends(get_session),
    jobs: ThreadJobRunner = Depends(get_jobs),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
) -> list[RunOut]:
    """Enqueue several scenarios of the same document (``null`` = base document)."""
    project = get_project_or_404(session, project_id)
    document = body.document if body.document is not None else project.document
    for sid in body.scenario_ids:  # validate everything before enqueueing anything
        try:
            prepare_run(document, sid, registry, _limits(settings))
        except DocumentInvalidError as exc:
            raise_issues(422, f"Cannot simulate scenario '{sid or 'base'}': {exc}", exc.issues)
        except SimulationLimitError as exc:
            raise_issues(422, str(exc))
    runs = [
        _enqueue(session, jobs, registry, settings, project_id, document, sid, None)
        for sid in body.scenario_ids
    ]
    return [_run_out(r) for r in runs]


@router.get("/projects/{project_id}/runs", response_model=list[RunOut])
def list_runs(
    project_id: str,
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> list[RunOut]:
    get_project_or_404(session, project_id)
    rows = (
        session.execute(
            select(SimulationRun)
            .where(SimulationRun.project_id == project_id)
            .order_by(SimulationRun.created_at.desc())
            .limit(settings.run_history_limit)
        )
        .scalars()
        .all()
    )
    return [_run_out(r) for r in rows]


@router.get("/runs/{run_id}", response_model=RunOut)
def get_run(run_id: str, session: Session = Depends(get_session)) -> RunOut:
    return _run_out(get_run_or_404(session, run_id))


@router.get("/runs/{run_id}/report")
def get_report(run_id: str, session: Session = Depends(get_session)) -> JSONResponse:
    """Full simulation report: provenance, summary, time series, per-node metrics, findings."""
    run = get_run_or_404(session, run_id, with_payload=True)
    if run.status != "succeeded" or run.report is None:
        raise HTTPException(
            409, detail={"message": f"Run is {run.status}; no report available", "issues": []}
        )
    return JSONResponse(run.report)


@router.get("/runs/{run_id}/input")
def get_run_input(run_id: str, session: Session = Depends(get_session)) -> JSONResponse:
    """The exact document submitted for this run (re-importable)."""
    run = get_run_or_404(session, run_id, with_payload=True)
    return JSONResponse(run.input_document)


@router.post("/runs/{run_id}/cancel", response_model=RunOut)
def cancel_run(
    run_id: str, session: Session = Depends(get_session), jobs: ThreadJobRunner = Depends(get_jobs)
) -> RunOut:
    run = get_run_or_404(session, run_id)
    if run.status in ACTIVE:
        jobs.cancel(run_id)
        session.refresh(run)
    return _run_out(run)


@router.post("/runs/{run_id}/rerun", response_model=RunOut, status_code=202)
def rerun(
    run_id: str,
    session: Session = Depends(get_session),
    jobs: ThreadJobRunner = Depends(get_jobs),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
) -> RunOut:
    """Re-simulate the exact stored input (same document, scenario and seed) to check
    reproducibility: the new run's ``resultDigest`` should equal the original's."""
    original = get_run_or_404(session, run_id, with_payload=True)
    run = _enqueue(
        session,
        jobs,
        registry,
        settings,
        original.project_id,
        original.input_document,
        original.scenario_id,
        f"Reproduce: {original.label or original.id[:8]}"[:200],
    )
    return _run_out(run, reproduces=original.id)


@router.delete("/runs/{run_id}", status_code=204)
def delete_run(
    run_id: str, session: Session = Depends(get_session), jobs: ThreadJobRunner = Depends(get_jobs)
) -> Response:
    run = get_run_or_404(session, run_id)
    if run.status in ACTIVE:
        jobs.cancel(run_id)
    session.delete(run)
    session.commit()
    return Response(status_code=204)


@router.get("/compare")
def compare(a: str, b: str, session: Session = Depends(get_session)) -> dict[str, Any]:
    """Compare two finished runs: KPI deltas, input differences and explanations."""
    ra = get_run_or_404(session, a, with_payload=True)
    rb = get_run_or_404(session, b, with_payload=True)
    for r in (ra, rb):
        if r.status != "succeeded" or r.report is None:
            raise HTTPException(
                409,
                detail={"message": f"Run {r.id[:8]} is {r.status}; cannot compare", "issues": []},
            )
    assert ra.report is not None and rb.report is not None
    result = compare_reports(
        ra.report,
        rb.report,
        ra.label or ra.id[:8],
        rb.label or rb.id[:8],
    )
    result["runs"] = {"a": ra.id, "b": rb.id}
    return result
