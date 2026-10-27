"""The run queue, kept in the database: each ``simulation_runs`` row is its own job.

A run goes ``queued`` -> ``running`` -> ``succeeded`` | ``failed`` | ``cancelled``. A worker
*claims* queued runs: it sets itself as ``lease_owner`` with a lease that runs out after
``lease_seconds`` unless the worker *renews* it (every heartbeat). Only the lease owner may
record progress or the outcome (``finish``), so a worker that lost its lease cannot overwrite
the run someone else took over. Any process can ``request_cancel``: a queued run is cancelled at
once; a running one gets ``cancel_requested_at``, which its worker sees at its next renewal.

``reap`` deals with runs whose lease ran out (their worker died or hung): requeued while
``attempts < MAX_ATTEMPTS``, failed with ``WORKER_STOPPED`` after that, cancelled if a cancel was
requested. Every function opens and commits its own transaction, so API instances and workers
share nothing but the database.

PostgreSQL claims with ``UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED LIMIT n) RETURNING``,
so any number of workers on any number of hosts take different runs without waiting on each
other. SQLite takes the write lock first (``BEGIN IMMEDIATE``): fine for
several processes on one host, not across machines.

Lease times come from each process's clock, so hosts sharing a database need synchronised clocks
(NTP); the lease length (30 s by default) is far longer than the skew that leaves.
"""

from __future__ import annotations

from collections.abc import Collection, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, cast

from sqlalchemy import (
    ColumnElement,
    Table,
    and_,
    delete,
    func,
    insert,
    or_,
    select,
    update,
)
from sqlalchemy.orm import Session

from archsim_api.db import Database, JobWorker, SimulationRun, utcnow

ACTIVE = ("queued", "running")

MAX_ATTEMPTS = 2
"""A run whose worker stops without finishing it is requeued once; the second time it fails."""

WORKER_STOPPED = (
    "The simulation worker stopped unexpectedly (for example, it ran out of memory or was "
    "killed), so this run did not finish. Run it again; if it keeps happening, shorten the run "
    "or lower the load."
)

STALE_WORKER_ROWS = timedelta(hours=1)
"""Heartbeat rows older than this are deleted by ``reap`` (the workers are long gone)."""

runs = cast(Table, SimulationRun.__table__)
workers = cast(Table, JobWorker.__table__)


@dataclass(frozen=True)
class ClaimedRun:
    id: str
    document: dict[str, Any]
    scenario_id: str | None
    attempts: int


@dataclass(frozen=True)
class Renewal:
    owned: frozenset[str]
    """The runs asked about that this worker still holds; it lost the others."""
    cancel_requested: frozenset[str]
    """The held runs someone asked to cancel."""


@dataclass(frozen=True)
class Reaped:
    requeued: int = 0
    failed: int = 0
    cancelled: int = 0

    @property
    def total(self) -> int:
        return self.requeued + self.failed + self.cancelled


def _rowcount(result: Any) -> int:
    return int(result.rowcount or 0)


def _held_by(run_id: str, worker_id: str) -> ColumnElement[bool]:
    return and_(runs.c.id == run_id, runs.c.lease_owner == worker_id, runs.c.status == "running")


def _claimable(table: Any, exclude: Collection[str]) -> ColumnElement[bool]:
    queued: ColumnElement[bool] = table.c.status == "queued"
    return and_(queued, table.c.id.not_in(list(exclude))) if exclude else queued


def enqueue(session: Session, run: SimulationRun) -> SimulationRun:
    """Store a new run as queued (committing ``session``); any worker may claim it from now on."""
    run.status = "queued"
    run.progress = 0.0
    run.attempts = 0
    session.add(run)
    session.commit()
    return run


def claim(
    db: Database,
    worker_id: str,
    limit: int,
    lease_seconds: float,
    exclude: Collection[str] = (),
) -> list[ClaimedRun]:
    """Take up to ``limit`` of the oldest queued runs for ``worker_id``: they become running
    under a lease of ``lease_seconds``, with one more attempt counted. ``exclude``: runs the
    worker is still executing (after it lost their lease), which it must not take twice."""
    if limit <= 0:
        return []
    now = utcnow()
    values = {
        "status": "running",
        "lease_owner": worker_id,
        "lease_expires_at": now + timedelta(seconds=lease_seconds),
        "started_at": now,
        "progress": 0.0,
        "attempts": runs.c.attempts + 1,
    }
    payload = (runs.c.id, runs.c.input_document, runs.c.scenario_id, runs.c.attempts)
    rows: Sequence[Any]
    if db.dialect == "postgresql":
        # The picked rows are a CTE, which PostgreSQL evaluates exactly once (a CTE with FOR
        # UPDATE is never inlined). ``id IN (SELECT ... LIMIT n)`` may be re-run by the planner
        # and take more than ``limit`` runs.
        q = runs.alias("q")
        picked = (
            select(q.c.id)
            .where(_claimable(q, exclude))
            .order_by(q.c.created_at)
            .limit(limit)
            .with_for_update(skip_locked=True)
            .cte("picked")
        )
        with db.engine.begin() as conn:
            rows = conn.execute(
                update(runs)
                .where(runs.c.id == picked.c.id, runs.c.status == "queued")
                .values(values)
                .returning(*payload)
            ).all()
    else:
        with db.write_transaction() as conn:
            ids = (
                conn.execute(
                    select(runs.c.id)
                    .where(_claimable(runs, exclude))
                    .order_by(runs.c.created_at)
                    .limit(limit)
                )
                .scalars()
                .all()
            )
            if not ids:
                return []
            conn.execute(
                update(runs).where(runs.c.id.in_(ids), runs.c.status == "queued").values(values)
            )
            rows = conn.execute(
                select(*payload).where(runs.c.id.in_(ids), runs.c.lease_owner == worker_id)
            ).all()
    return [ClaimedRun(r.id, r.input_document, r.scenario_id, r.attempts) for r in rows]


def renew(db: Database, worker_id: str, run_ids: list[str], lease_seconds: float) -> Renewal:
    """Extend the leases ``worker_id`` holds on ``run_ids`` and report which it still holds and
    which of those someone asked to cancel. Only runs the worker names are renewed: a run it
    claimed but never started (it lost the answer) runs out and is reaped."""
    if not run_ids:
        return Renewal(frozenset(), frozenset())
    held = and_(runs.c.id.in_(run_ids), runs.c.lease_owner == worker_id, runs.c.status == "running")
    with db.engine.begin() as conn:
        conn.execute(
            update(runs)
            .where(held)
            .values(lease_expires_at=utcnow() + timedelta(seconds=lease_seconds))
        )
        rows = conn.execute(select(runs.c.id, runs.c.cancel_requested_at).where(held)).all()
    return Renewal(
        owned=frozenset(r.id for r in rows),
        cancel_requested=frozenset(r.id for r in rows if r.cancel_requested_at is not None),
    )


def heartbeat(
    db: Database,
    worker_id: str,
    *,
    hostname: str,
    pid: int,
    slots: int,
    running: int,
    status: str = "running",
) -> None:
    """Record that ``worker_id`` is alive, with its slots and runs in flight (``job_workers``,
    for operators: which workers exist and how busy they are)."""
    now = utcnow()
    with db.engine.begin() as conn:
        res = conn.execute(
            update(workers)
            .where(workers.c.id == worker_id)
            .values(heartbeat_at=now, running=running, slots=slots, status=status)
        )
        if not _rowcount(res):
            conn.execute(
                insert(workers).values(
                    id=worker_id,
                    hostname=hostname[:255],
                    pid=pid,
                    slots=slots,
                    running=running,
                    status=status,
                    started_at=now,
                    heartbeat_at=now,
                )
            )


def forget_worker(db: Database, worker_id: str) -> None:
    with db.engine.begin() as conn:
        conn.execute(delete(workers).where(workers.c.id == worker_id))


def set_progress(db: Database, run_id: str, worker_id: str, progress: float) -> bool:
    with db.engine.begin() as conn:
        res = conn.execute(
            update(runs).where(_held_by(run_id, worker_id)).values(progress=progress)
        )
    return _rowcount(res) == 1


def finish(db: Database, run_id: str, worker_id: str, status: str, **values: Any) -> bool:
    """Record a run's outcome, only if ``worker_id`` still holds it. False: the lease was lost
    (the run was reaped, and maybe taken by another worker), so the outcome is dropped."""
    with db.engine.begin() as conn:
        res = conn.execute(
            update(runs)
            .where(_held_by(run_id, worker_id))
            .values(status=status, finished_at=utcnow(), lease_expires_at=None, **values)
        )
    return _rowcount(res) == 1


def release(db: Database, run_id: str, worker_id: str) -> str | None:
    """Give back a run the worker is stopping without finishing (it is shutting down): requeued
    without counting the attempt, or cancelled if a cancel was requested. Returns the new status,
    or None if the worker no longer held it."""
    now = utcnow()
    with db.engine.begin() as conn:
        res = conn.execute(
            update(runs)
            .where(_held_by(run_id, worker_id), runs.c.cancel_requested_at.is_not(None))
            .values(status="cancelled", finished_at=now, lease_expires_at=None)
        )
        if _rowcount(res):
            return "cancelled"
        res = conn.execute(
            update(runs)
            .where(_held_by(run_id, worker_id))
            .values(
                status="queued",
                lease_owner=None,
                lease_expires_at=None,
                started_at=None,
                progress=0.0,
                attempts=runs.c.attempts - 1,
            )
        )
        return "queued" if _rowcount(res) else None


def request_cancel(db: Database, run_id: str) -> str | None:
    """Ask for a run to stop, from any process. A queued run is cancelled now (returns
    ``"cancelled"``); a running one is marked, and its worker stops it after its next renewal
    (returns ``"running"``). None: the run was not active."""
    now = utcnow()
    with db.engine.begin() as conn:
        res = conn.execute(
            update(runs)
            .where(runs.c.id == run_id, runs.c.status == "queued")
            .values(status="cancelled", finished_at=now, cancel_requested_at=now)
        )
        if _rowcount(res):
            return "cancelled"
        res = conn.execute(
            update(runs)
            .where(runs.c.id == run_id, runs.c.status == "running")
            .values(cancel_requested_at=func.coalesce(runs.c.cancel_requested_at, now))
        )
        return "running" if _rowcount(res) else None


def reap(db: Database, now: datetime | None = None) -> Reaped:
    """Settle running runs whose lease ran out: cancelled if a cancel was requested, failed with
    ``WORKER_STOPPED`` after ``MAX_ATTEMPTS`` attempts, requeued otherwise. A running run with no
    lease at all was left by an API from before the queue (or a crash mid-claim) and counts as
    expired. Safe to call from every worker at once: each row is settled by one of them."""
    now = now or utcnow()
    expired = and_(
        runs.c.status == "running",
        or_(runs.c.lease_expires_at.is_(None), runs.c.lease_expires_at < now),
    )
    with db.write_transaction() as conn:
        cancelled = conn.execute(
            update(runs)
            .where(expired, runs.c.cancel_requested_at.is_not(None))
            .values(status="cancelled", finished_at=now, lease_expires_at=None)
        )
        failed = conn.execute(
            update(runs)
            .where(expired, runs.c.attempts >= MAX_ATTEMPTS)
            .values(
                status="failed",
                finished_at=now,
                lease_expires_at=None,
                error={"message": WORKER_STOPPED},
            )
        )
        requeued = conn.execute(
            update(runs)
            .where(expired)
            .values(
                status="queued",
                lease_owner=None,
                lease_expires_at=None,
                started_at=None,
                progress=0.0,
            )
        )
        conn.execute(delete(workers).where(workers.c.heartbeat_at < now - STALE_WORKER_ROWS))
    return Reaped(
        requeued=_rowcount(requeued), failed=_rowcount(failed), cancelled=_rowcount(cancelled)
    )
