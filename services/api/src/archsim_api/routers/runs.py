"""Simulation runs: create (async), poll, cancel, reproduce, fetch reports, compare. A run belongs
to its project's workspace: viewers read runs, editors start, cancel and delete them. With limits
on, starting runs counts against the workspace's runs per hour and your own (429 with
Retry-After), and each run is bounded by its workspace's limits (see ``archsim_api.limits``).

``seeds: N`` starts a seed group (``GET /run-groups/{id}``), one run per seed from the
document's seed on; ``mode: "sampled"`` is a quick check. Both are stored on each run, so a rerun
reproduces it."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Response
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from archsim_api import limits
from archsim_api.config import Settings
from archsim_api.db import Database, RunGroup, SimulationRun, Workspace
from archsim_api.deps import (
    get_db,
    get_jobs,
    get_principal,
    get_registry,
    get_session,
    get_settings_dep,
    raise_issues,
    require_project,
    require_run,
)
from archsim_api.jobs.queue import ACTIVE
from archsim_api.jobs.worker import JobService
from archsim_api.schemas import BatchRunCreate, RunCreate, RunOut
from archsim_api.security import Principal, ProjectAccess, RunAccess, run_access
from archsim_engine import (
    ENGINE_VERSION,
    ComponentRegistry,
    DocumentInvalidError,
    PreparedRun,
    SimulationLimitError,
    SimulationLimits,
    compare_reports,
    prepare_run,
    quick_check,
)
from archsim_engine.engine import MAX_SEED

router = APIRouter(tags=["runs"])


def _workspace(session: Session, workspace_id: str) -> Workspace:
    ws = session.get(Workspace, workspace_id)
    assert ws is not None  # the project's, which a foreign key keeps
    return ws


def _charge(
    settings: Settings, db: Database, workspace: Workspace, principal: Principal, runs: int
) -> None:
    """Count ``runs`` new runs against the hourly limits (429 when over)."""
    per_ws = limits.runs_per_hour(settings, workspace)
    limits.enforce(
        settings,
        db,
        f"user:{principal.user_id}:runs",
        (limits.USER_RUNS_PER_HOUR, limits.HOUR),
        f"You have started {limits.USER_RUNS_PER_HOUR} runs this hour, the most one person may.",
        cost=runs,
    )
    limits.enforce(
        settings,
        db,
        f"ws:{workspace.id}:runs",
        limits.window(per_ws, limits.HOUR),
        f"{workspace.name} has started its {per_ws} runs for this hour.",
        cost=runs,
    )


def _run_out(run: SimulationRun, reproduces: str | None = None) -> RunOut:
    # progress comes from the row: the worker running it writes it at most once a second, from
    # whichever process or host it is on
    out = RunOut.model_validate(run)
    out.reproduces_run_id = reproduces
    return out


def _prepare(
    document: dict[str, Any],
    scenario_id: str | None,
    registry: ComponentRegistry,
    run_limits: SimulationLimits,
    seed: int | None = None,
) -> PreparedRun:
    try:
        return prepare_run(document, scenario_id, registry, run_limits, seed)
    except DocumentInvalidError as exc:
        raise_issues(422, f"Cannot simulate: {exc}", exc.issues)
    except SimulationLimitError as exc:
        raise_issues(422, str(exc))


def _enqueue(
    session: Session,
    jobs: JobService,
    registry: ComponentRegistry,
    run_limits: SimulationLimits,
    project_id: str,
    workspace_id: str,
    created_by: str,
    document: dict[str, Any],
    scenario_id: str | None,
    label: str | None,
    seed: int | None = None,
    mode: str = "full",
    group: RunGroup | None = None,
    seed_index: int | None = None,
) -> SimulationRun:
    prep = _prepare(document, scenario_id, registry, run_limits, seed)
    default = prep.scenario_name or "Current design"
    run = SimulationRun(
        project_id=project_id,
        workspace_id=workspace_id,
        created_by=created_by,
        label=label or (f"{default} · quick check" if mode == "sampled" else default),
        scenario_id=prep.scenario_id,
        scenario_name=prep.scenario_name,
        status="queued",
        progress=0.0,
        seed=prep.resolved.workload.seed,
        engine_version=ENGINE_VERSION,
        input_document=prep.document.to_json_dict(),
        max_requests=run_limits.max_requests,
        max_wall_seconds=run_limits.max_wall_seconds,
        mode=mode,
        group_id=group.id if group else None,
        seed_index=seed_index,
    )
    return jobs.enqueue(session, run)


def _check_quick(prep: PreparedRun) -> None:
    """422 unless sampled mode can answer this run (the reason says why not)."""
    qc = quick_check(prep.resolved.workload)
    if not qc["eligible"]:
        raise_issues(422, f"Cannot run a quick check: {qc['reason']}")


@router.post("/projects/{project_id}/runs", response_model=RunOut, status_code=202)
def create_run(
    body: RunCreate,
    access: ProjectAccess = Depends(require_project("editor")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    jobs: JobService = Depends(get_jobs),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> RunOut:
    """Validate and enqueue a simulation. Returns immediately; poll ``GET /runs/{id}``.

    With ``seeds`` above 1, enqueue a seed group and return its first run (``groupId`` names
    the group; poll ``GET /run-groups/{groupId}``)."""
    project = access.project
    workspace = _workspace(session, project.workspace_id)
    document = body.document if body.document is not None else project.document
    run_limits = limits.simulation_limits(settings, workspace)
    args = (session, jobs, registry, run_limits, project.id, project.workspace_id)
    if body.seeds == 1:
        if body.mode == "sampled":
            _check_quick(_prepare(document, body.scenario_id, registry, run_limits))
        _charge(settings, db, workspace, principal, 1)
        run = _enqueue(
            *args, principal.user_id, document, body.scenario_id, body.label, mode=body.mode
        )
        return _run_out(run)

    # a seed group: one input, seeds base ... base + N - 1
    prep = _prepare(document, body.scenario_id, registry, run_limits)
    if body.mode == "sampled":
        _check_quick(prep)
    cap = limits.max_seeds(settings, workspace)
    if body.seeds > cap:
        raise_issues(422, f"{workspace.name} can run at most {cap} seeds at once.")
    base = prep.resolved.workload.seed
    last = base + body.seeds - 1
    if last > MAX_SEED:
        raise_issues(422, f"Seeds {base:,} to {last:,} go past the largest, {MAX_SEED:,}.")
    _charge(settings, db, workspace, principal, body.seeds)
    label = body.label or prep.scenario_name or "Current design"
    group = RunGroup(
        workspace_id=project.workspace_id,
        project_id=project.id,
        kind="seeds",
        base_seed=base,
        size=body.seeds,
        mode=body.mode,
        scenario_id=prep.scenario_id,
        label=label,
        created_by=principal.user_id,
    )
    session.add(group)
    session.flush()
    runs = [
        _enqueue(
            *args,
            principal.user_id,
            document,
            body.scenario_id,
            f"{label} · seed {i + 1} of {body.seeds}"[:200],
            seed=base + i,
            mode=body.mode,
            group=group,
            seed_index=i,
        )
        for i in range(body.seeds)
    ]
    return _run_out(runs[0])


@router.post("/projects/{project_id}/runs/batch", response_model=list[RunOut], status_code=202)
def create_batch(
    body: BatchRunCreate,
    access: ProjectAccess = Depends(require_project("editor")),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    jobs: JobService = Depends(get_jobs),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> list[RunOut]:
    """Enqueue several scenarios of the same document (``null`` = base document)."""
    project = access.project
    workspace = _workspace(session, project.workspace_id)
    run_limits = limits.simulation_limits(settings, workspace)
    document = body.document if body.document is not None else project.document
    for sid in body.scenario_ids:  # validate everything before enqueueing anything
        try:
            prepare_run(document, sid, registry, run_limits)
        except DocumentInvalidError as exc:
            raise_issues(422, f"Cannot simulate scenario '{sid or 'base'}': {exc}", exc.issues)
        except SimulationLimitError as exc:
            raise_issues(422, str(exc))
    _charge(settings, db, workspace, principal, len(body.scenario_ids))
    runs = [
        _enqueue(
            session,
            jobs,
            registry,
            run_limits,
            project.id,
            project.workspace_id,
            principal.user_id,
            document,
            sid,
            None,
        )
        for sid in body.scenario_ids
    ]
    return [_run_out(r) for r in runs]


@router.get("/projects/{project_id}/runs", response_model=list[RunOut])
def list_runs(
    access: ProjectAccess = Depends(require_project("viewer")),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings_dep),
) -> list[RunOut]:
    rows = (
        session.execute(
            select(SimulationRun)
            .where(SimulationRun.project_id == access.project.id)
            .order_by(SimulationRun.created_at.desc())
            .limit(settings.run_history_limit)
        )
        .scalars()
        .all()
    )
    return [_run_out(r) for r in rows]


@router.get("/runs/{run_id}", response_model=RunOut)
def get_run(access: RunAccess = Depends(require_run("viewer"))) -> RunOut:
    return _run_out(access.run)


@router.get("/runs/{run_id}/report")
def get_report(
    access: RunAccess = Depends(require_run("viewer", with_payload=True)),
) -> JSONResponse:
    """Full simulation report: provenance, summary, time series, per-node metrics, findings."""
    run = access.run
    if run.status != "succeeded" or run.report is None:
        raise HTTPException(
            409, detail={"message": f"Run is {run.status}; no report available", "issues": []}
        )
    return JSONResponse(run.report)


@router.get("/runs/{run_id}/input")
def get_run_input(
    access: RunAccess = Depends(require_run("viewer", with_payload=True)),
) -> JSONResponse:
    """The exact document submitted for this run (re-importable)."""
    return JSONResponse(access.run.input_document)


@router.post("/runs/{run_id}/cancel", response_model=RunOut)
def cancel_run(
    access: RunAccess = Depends(require_run("editor")),
    session: Session = Depends(get_session),
    jobs: JobService = Depends(get_jobs),
) -> RunOut:
    run = access.run
    if run.status in ACTIVE:
        jobs.cancel(run.id)
        session.refresh(run)
    return _run_out(run)


@router.post("/runs/{run_id}/rerun", response_model=RunOut, status_code=202)
def rerun(
    access: RunAccess = Depends(require_run("editor", with_payload=True)),
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
    jobs: JobService = Depends(get_jobs),
    registry: ComponentRegistry = Depends(get_registry),
    settings: Settings = Depends(get_settings_dep),
    db: Database = Depends(get_db),
) -> RunOut:
    """Re-simulate the exact stored input (same document, scenario, seed and mode) to check
    reproducibility: the new run's ``resultDigest`` should equal the original's."""
    original = access.run
    workspace = _workspace(session, original.workspace_id)
    _charge(settings, db, workspace, principal, 1)
    run = _enqueue(
        session,
        jobs,
        registry,
        limits.simulation_limits(settings, workspace),
        original.project_id,
        original.workspace_id,
        principal.user_id,
        original.input_document,
        original.scenario_id,
        f"Reproduce: {original.label or original.id[:8]}"[:200],
        seed=original.seed,
        mode=original.mode or "full",
    )
    return _run_out(run, reproduces=original.id)


@router.delete("/runs/{run_id}", status_code=204)
def delete_run(
    access: RunAccess = Depends(require_run("editor")),
    session: Session = Depends(get_session),
    jobs: JobService = Depends(get_jobs),
) -> Response:
    run = access.run
    if run.status in ACTIVE:
        jobs.cancel(run.id)
    session.delete(run)
    session.commit()
    return Response(status_code=204)


@router.get("/compare")
def compare(
    a: str,
    b: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> dict[str, Any]:
    """Compare two finished runs: KPI deltas, input differences and explanations."""
    ra = run_access(session, principal, a, "viewer", with_payload=True).run
    rb = run_access(session, principal, b, "viewer", with_payload=True).run
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
