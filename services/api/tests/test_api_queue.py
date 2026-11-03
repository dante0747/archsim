"""The database run queue: claims, leases, cancel requests, reaping, refusing an outcome from a
worker that lost its lease, and the runs at once each workspace may have. Runs on SQLite and,
with ARCHSIM_TEST_DATABASE_URL, PostgreSQL."""

from __future__ import annotations

import threading
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from sqlalchemy import select, update

from archsim_api.db import Database, JobWorker, Project, SimulationRun, Workspace, utcnow
from archsim_api.jobs import queue
from archsim_api.migrations import upgrade_to_head

LEASE = 30.0


@pytest.fixture
def db(db_url: str) -> Iterator[Database]:
    upgrade_to_head(db_url)
    database = Database(db_url)
    with database.session() as s:
        s.add(Project(id="p", name="p", document={}, schema_version="1"))
        s.commit()
    yield database
    database.engine.dispose()


def _enqueue(db: Database, n: int = 1) -> list[str]:
    ids = []
    with db.session() as s:
        for i in range(n):
            run = queue.enqueue(
                s,
                SimulationRun(
                    project_id="p",
                    seed=i,
                    engine_version="test",
                    input_document={"n": i},
                    created_at=utcnow() + timedelta(milliseconds=i),  # a stable order
                ),
            )
            ids.append(run.id)
    return ids


def _row(db: Database, run_id: str) -> SimulationRun:
    with db.session() as s:
        run = s.get(SimulationRun, run_id)
        assert run is not None
        return run


def _set(db: Database, run_id: str, **values: Any) -> None:
    with db.session() as s:
        s.execute(update(SimulationRun).where(SimulationRun.id == run_id).values(**values))
        s.commit()


def _utc(value: datetime | None) -> datetime:
    assert value is not None
    return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


def _expire(db: Database, run_id: str) -> None:
    _set(db, run_id, lease_expires_at=utcnow() - timedelta(seconds=1))


def test_claim_takes_the_oldest_runs_under_a_lease(db: Database) -> None:
    first, second, third = _enqueue(db, 3)
    claimed = queue.claim(db, "a", 2, LEASE)
    assert sorted(c.id for c in claimed) == sorted([first, second])
    assert {c.attempts for c in claimed} == {1}
    assert {c.document["n"] for c in claimed} == {0, 1}
    run = _row(db, first)
    assert (run.status, run.lease_owner, run.attempts) == ("running", "a", 1)
    assert run.lease_expires_at is not None and run.started_at is not None
    assert [c.id for c in queue.claim(db, "b", 5, LEASE)] == [third]
    assert queue.claim(db, "b", 5, LEASE) == []
    assert queue.claim(db, "b", 0, LEASE) == []


def test_concurrent_claims_never_hand_out_a_run_twice(db: Database) -> None:
    ids = _enqueue(db, 24)
    taken: list[str] = []
    lock = threading.Lock()
    errors: list[BaseException] = []

    def claimer(name: str) -> None:
        try:
            while batch := queue.claim(db, name, 2, LEASE):
                with lock:
                    taken.extend(c.id for c in batch)
        except BaseException as exc:  # pragma: no cover - reported below
            errors.append(exc)

    threads = [threading.Thread(target=claimer, args=(f"w{i}",)) for i in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)
    assert not errors, errors
    assert sorted(taken) == sorted(ids)  # each exactly once


def test_claim_skips_runs_the_worker_is_still_executing(db: Database) -> None:
    (run_id,) = _enqueue(db)
    assert queue.claim(db, "a", 1, LEASE, exclude=[run_id]) == []
    assert [c.id for c in queue.claim(db, "a", 1, LEASE)] == [run_id]


def test_renew_extends_held_leases_and_reports_cancel_requests(db: Database) -> None:
    a1, a2, b1 = _enqueue(db, 3)
    queue.claim(db, "a", 2, LEASE)
    queue.claim(db, "b", 1, LEASE)
    _set(db, a1, lease_expires_at=utcnow() + timedelta(seconds=1))
    assert queue.request_cancel(db, a2) == "running"

    renewal = queue.renew(db, "a", [a1, a2, b1], LEASE)
    assert renewal.owned == {a1, a2}  # b1 is b's
    assert renewal.cancel_requested == {a2}
    assert _utc(_row(db, a1).lease_expires_at) > utcnow() + timedelta(seconds=LEASE - 5)
    assert queue.renew(db, "a", [], LEASE).owned == frozenset()


def test_request_cancel_from_any_process(db: Database) -> None:
    older, newer = _enqueue(db, 2)
    queue.claim(db, "a", 1, LEASE)  # takes the older one
    running, queued = older, newer
    # a queued run is cancelled at once; a running one is marked for its worker
    assert queue.request_cancel(db, queued) == "cancelled"
    assert _row(db, queued).status == "cancelled"
    assert _row(db, queued).finished_at is not None
    assert queue.request_cancel(db, running) == "running"
    first = _row(db, running).cancel_requested_at
    assert first is not None and _row(db, running).status == "running"
    assert queue.request_cancel(db, running) == "running"
    assert _row(db, running).cancel_requested_at == first  # the first request is kept
    assert queue.finish(db, running, "a", "cancelled")
    assert queue.request_cancel(db, running) is None  # no longer active
    assert queue.request_cancel(db, "missing") is None


def test_finish_refuses_a_worker_that_lost_its_lease(db: Database) -> None:
    (run_id,) = _enqueue(db)
    queue.claim(db, "a", 1, LEASE)
    assert queue.set_progress(db, run_id, "a", 0.4)
    _expire(db, run_id)  # a stalled past its lease
    assert queue.reap(db).requeued == 1
    (taken,) = queue.claim(db, "b", 1, LEASE)  # b takes it over
    assert taken.id == run_id and taken.attempts == 2
    assert _row(db, run_id).progress == 0.0

    assert queue.renew(db, "a", [run_id], LEASE).owned == frozenset()  # a learns it lost it
    assert not queue.set_progress(db, run_id, "a", 0.9)
    assert not queue.finish(db, run_id, "a", "succeeded", progress=1.0)
    assert _row(db, run_id).status == "running"
    assert queue.finish(db, run_id, "b", "succeeded", progress=1.0)
    run = _row(db, run_id)
    assert (run.status, run.lease_owner, run.lease_expires_at) == ("succeeded", "b", None)
    assert not queue.finish(db, run_id, "b", "failed")  # recorded once


def test_reap_requeues_once_then_fails_and_honours_cancel_requests(db: Database) -> None:
    retry, give_up, cancel, alive, legacy = _enqueue(db, 5)
    queue.claim(db, "dead", 5, LEASE)
    _set(db, give_up, attempts=queue.MAX_ATTEMPTS)
    queue.request_cancel(db, cancel)
    _set(db, legacy, lease_owner=None, lease_expires_at=None)  # running before the queue existed
    for run_id in (retry, give_up, cancel):
        _expire(db, run_id)

    reaped = queue.reap(db)
    assert (reaped.requeued, reaped.failed, reaped.cancelled) == (2, 1, 1)
    assert reaped.total == 4
    requeued = _row(db, retry)
    assert (requeued.status, requeued.lease_owner, requeued.started_at) == ("queued", None, None)
    failed = _row(db, give_up)
    assert failed.status == "failed" and failed.error == {"message": queue.WORKER_STOPPED}
    assert _row(db, cancel).status == "cancelled"
    assert _row(db, alive).status == "running"  # its lease is still good
    assert _row(db, legacy).status == "queued"
    assert queue.reap(db).total == 0  # every row is settled once


def test_release_requeues_without_counting_the_attempt(db: Database) -> None:
    keep, cancel = _enqueue(db, 2)
    queue.claim(db, "a", 2, LEASE)
    queue.request_cancel(db, cancel)
    assert queue.release(db, keep, "a") == "queued"
    assert queue.release(db, cancel, "a") == "cancelled"
    assert queue.release(db, keep, "a") is None  # no longer held
    run = _row(db, keep)
    assert (run.status, run.attempts, run.lease_owner) == ("queued", 0, None)
    (again,) = queue.claim(db, "b", 1, LEASE)
    assert again.attempts == 1


def test_worker_heartbeats(db: Database) -> None:
    queue.heartbeat(db, "w1", hostname="h", pid=1, slots=2, running=0)
    queue.heartbeat(db, "w1", hostname="h", pid=1, slots=2, running=1, status="draining")
    queue.heartbeat(db, "w2", hostname="h", pid=2, slots=1, running=0)
    with db.session() as s:
        w1 = s.get(JobWorker, "w1")
        assert w1 is not None and (w1.running, w1.status) == (1, "draining")
        s.execute(
            update(JobWorker)
            .where(JobWorker.id == "w2")
            .values(heartbeat_at=utcnow() - queue.STALE_WORKER_ROWS - timedelta(minutes=1))
        )
        s.commit()
    queue.reap(db)  # drops workers gone for an hour
    with db.session() as s:
        assert s.execute(select(JobWorker.id)).scalars().all() == ["w1"]
    queue.forget_worker(db, "w1")
    with db.session() as s:
        assert s.execute(select(JobWorker.id)).scalars().all() == []


# -- runs at once per workspace ------------------------------------------------------------------
def _queue(db: Database, workspace: str, n: int, kind: str = "team", **ws: object) -> list[str]:
    ids: list[str] = []
    with db.session() as s:
        if s.get(Workspace, workspace) is None:
            s.add(Workspace(id=workspace, name=workspace, kind=kind, **ws))
            s.flush()
            s.add(
                Project(
                    id=f"p-{workspace}",
                    workspace_id=workspace,
                    name="p",
                    document={},
                    schema_version="1",
                )
            )
            s.flush()
        for _ in range(n):
            run = queue.enqueue(
                s,
                SimulationRun(
                    project_id=f"p-{workspace}",
                    workspace_id=workspace,
                    seed=1,
                    engine_version="test",
                    input_document={"ws": workspace},
                    created_at=utcnow() + timedelta(milliseconds=len(ids)),
                ),
            )
            ids.append(run.id)
    return ids


def test_claim_keeps_each_workspace_under_its_cap(db: Database) -> None:
    capped = _queue(db, "capped", 3, max_concurrent_runs=1)
    free = _queue(db, "free", 2)
    first = queue.claim(db, "w1", 4, LEASE)
    assert {c.id for c in first} == {capped[0], *free}  # one of capped's, all of free's
    assert queue.claim(db, "w2", 4, LEASE) == []  # capped is at its cap on another worker too
    assert queue.finish(db, capped[0], "w1", "succeeded")
    assert [c.id for c in queue.claim(db, "w2", 4, LEASE)] == [capped[1]]


def test_claim_uses_the_kind_cap_for_workspaces_without_one(db: Database) -> None:
    sand = _queue(db, "sand", 3, kind="sandbox")
    taken = queue.claim(db, "w", 5, LEASE, kind_caps={"sandbox": 2})
    assert sorted(c.id for c in taken) == sorted(sand[:2])


def test_claim_is_fair_between_workspaces(db: Database) -> None:
    busy = _queue(db, "busy", 5)  # queued first
    late = _queue(db, "late", 1)
    taken = [c.id for c in queue.claim(db, "w", 2, LEASE)]
    assert sorted(taken) == sorted([busy[0], late[0]])  # not busy's two oldest
