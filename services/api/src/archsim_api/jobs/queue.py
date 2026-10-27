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

Runs belong to workspaces. ``claim`` keeps each workspace under its ``max_concurrent_runs``
(the workspace's own, else its kind's from ``kind_caps``; None: no cap), counted across every
worker, and takes the oldest run of each workspace before anyone's second, so one busy
workspace cannot hold every slot. To count running runs and pick consistently, claims take
turns: PostgreSQL holds a transaction advisory lock while claiming (a few milliseconds), SQLite
the write lock (``BEGIN IMMEDIATE``): fine for several processes on one host, not across
machines.

Lease times come from each process's clock, so hosts sharing a database need synchronised clocks
(NTP); the lease length (30 s by default) is far longer than the skew that leaves.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Collection, Iterator, Mapping, Sequence
from contextlib import contextmanager
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
from sqlalchemy.engine import Connection
from sqlalchemy.orm import Session

from archsim_api.db import Database, JobWorker, SimulationRun, Workspace, utcnow

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

CLAIM_LOCK_KEY = 0x61726368_73696D02
"""PostgreSQL advisory lock key held while claiming ("archsim" plus 2)."""

runs = cast(Table, SimulationRun.__table__)
workers = cast(Table, JobWorker.__table__)
workspaces = cast(Table, Workspace.__table__)


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


@contextmanager
def _claiming(db: Database) -> Iterator[Connection]:
    """A transaction no other claim runs alongside (see the module notes)."""
    if db.dialect == "postgresql":
        with db.engine.begin() as conn:
            conn.execute(select(func.pg_advisory_xact_lock(CLAIM_LOCK_KEY)))
            yield conn
    else:
        with db.write_transaction() as conn:
            yield conn


def _pick(
    conn: Connection,
    limit: int,
    exclude: Collection[str],
    kind_caps: Mapping[str, int | None],
) -> list[str]:
    """Up to ``limit`` queued runs to start: each workspace's oldest first, then their second
    oldest, and so on, skipping workspaces at their cap of runs at once."""
    q = runs.alias("q")
    position = (
        func.row_number()
        .over(partition_by=q.c.workspace_id, order_by=(q.c.created_at, q.c.id))
        .label("position")
    )
    ranked = (
        select(q.c.id, q.c.workspace_id, q.c.created_at, position)
        .where(_claimable(q, exclude))
        .subquery()
    )
    candidates = conn.execute(
        select(ranked.c.id, ranked.c.workspace_id)
        .where(ranked.c.position <= limit)  # nobody gets more than `limit` from one claim
        .order_by(ranked.c.position, ranked.c.created_at, ranked.c.id)
    ).all()
    if not candidates:
        return []
    ids = {c.workspace_id for c in candidates}
    caps: dict[str, int | None] = {}
    for wid, kind, cap in conn.execute(
        select(workspaces.c.id, workspaces.c.kind, workspaces.c.max_concurrent_runs).where(
            workspaces.c.id.in_(ids)
        )
    ):
        caps[wid] = cap if cap is not None else kind_caps.get(kind)
    busy: dict[str, int] = defaultdict(int)
    if any(c is not None for c in caps.values()):
        for wid, n in conn.execute(
            select(runs.c.workspace_id, func.count())
            .where(runs.c.status == "running", runs.c.workspace_id.in_(ids))
            .group_by(runs.c.workspace_id)
        ):
            busy[wid] = int(n)
    picked: list[str] = []
    for run_id, wid in candidates:
        cap = caps.get(wid)
        if cap is not None and busy[wid] >= cap:
            continue
        busy[wid] += 1
        picked.append(run_id)
        if len(picked) == limit:
            break
    return picked


def claim(
    db: Database,
    worker_id: str,
    limit: int,
    lease_seconds: float,
    exclude: Collection[str] = (),
    kind_caps: Mapping[str, int | None] | None = None,
) -> list[ClaimedRun]:
    """Take up to ``limit`` queued runs for ``worker_id``, oldest first but fair between
    workspaces and within each workspace's cap of runs at once (``kind_caps``: the cap of each
    workspace kind, for workspaces without their own). They become running under a lease of
    ``lease_seconds``, with one more attempt counted. ``exclude``: runs the worker is still
    executing (after it lost their lease), which it must not take twice."""
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
    with _claiming(db) as conn:
        ids = _pick(conn, limit, exclude, kind_caps or {})
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
