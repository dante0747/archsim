"""Run groups: the runs of one input started together (``POST /projects/{id}/runs`` with
``seeds`` above 1), read back with their ranges.

``GET /run-groups/{id}`` answers the group's runs, ordered by seed, and the engine's
``summarize_seeds`` of the finished ones against the objectives of the document they ran: the
verdict split ("Meets the objectives in 4 of 5 runs"), p50/p95/p99, success and throughput
ranges with their medians, and whether the runs disagree (borderline). A group belongs to its
project's workspace: non-members get the same 404 as an unknown id."""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session, undefer

from archsim_api.db import RunGroup, SimulationRun
from archsim_api.deps import get_principal, get_session
from archsim_api.jobs.queue import ACTIVE
from archsim_api.schemas import ApiModel, RunOut, UtcDateTime
from archsim_api.security import Principal, at_least, forbidden, not_found
from archsim_engine import summarize_seeds

router = APIRouter(tags=["runs"])


class RunGroupOut(ApiModel):
    id: str
    project_id: str
    kind: Literal["seeds", "sweep"]
    base_seed: int
    size: int
    mode: Literal["full", "sampled"]
    scenario_id: str | None
    label: str | None
    created_at: UtcDateTime
    status: Literal["running", "succeeded", "partial", "failed"]
    """``running`` while a run is queued or running; then ``succeeded`` when every run did,
    ``failed`` when none did, else ``partial``."""
    runs: list[RunOut]
    summary: dict[str, Any]
    """``summarize_seeds`` of the finished runs (see ``archsim_engine.seeds``)."""


def group_access(
    session: Session, principal: Principal, group_id: str, minimum: str = "viewer"
) -> RunGroup:
    group = session.get(RunGroup, group_id)
    role = principal.role_in(session, group.workspace_id) if group is not None else None
    if group is None or role is None:
        raise not_found(f"Run group '{group_id}'")
    if not at_least(role, minimum):
        raise forbidden(role, minimum)
    return group


def _status(runs: list[SimulationRun]) -> Literal["running", "succeeded", "partial", "failed"]:
    if any(r.status in ACTIVE for r in runs):
        return "running"
    ok = sum(1 for r in runs if r.status == "succeeded")
    if runs and ok == len(runs):
        return "succeeded"
    return "partial" if ok else "failed"


@router.get("/run-groups/{group_id}", response_model=RunGroupOut)
def get_run_group(
    group_id: str,
    principal: Principal = Depends(get_principal),
    session: Session = Depends(get_session),
) -> RunGroupOut:
    group = group_access(session, principal, group_id)
    runs = list(
        session.execute(
            select(SimulationRun)
            .where(SimulationRun.group_id == group.id)
            .order_by(SimulationRun.seed_index, SimulationRun.created_at)
        )
        .scalars()
        .all()
    )
    objectives: dict[str, Any] = {}
    if runs:
        first = session.execute(
            select(SimulationRun)
            .options(undefer(SimulationRun.input_document))
            .where(SimulationRun.id == runs[0].id)
        ).scalar_one()
        objectives = dict((first.input_document or {}).get("objectives") or {})
    summary = summarize_seeds(
        [{"seed": r.seed, "summary": r.summary if r.status == "succeeded" else None} for r in runs],
        objectives,
    )
    created: datetime = group.created_at
    return RunGroupOut(
        id=group.id,
        project_id=group.project_id,
        kind="sweep" if group.kind == "sweep" else "seeds",
        base_seed=group.base_seed,
        size=group.size,
        mode="sampled" if group.mode == "sampled" else "full",
        scenario_id=group.scenario_id,
        label=group.label,
        created_at=created,
        status=_status(runs),
        runs=[RunOut.model_validate(r) for r in runs],
        summary=summary,
    )
