"""Workers and the queue together: cancelling from another API instance, a worker taking over a run
whose lease ran out, cancelling a run that ignores it, the hard deadline, and a killed worker
leaving no simulation process behind. Runs on SQLite and, with ARCHSIM_TEST_DATABASE_URL,
PostgreSQL (the CI job ``api-postgres``)."""

from __future__ import annotations

import contextlib
import multiprocessing
import os
import re
import signal
import subprocess
import sys
import threading
import time
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import select

import sim_targets
from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.db import Database, Project, SimulationRun
from archsim_api.jobs import child, orphans, queue
from archsim_api.jobs.worker import Worker, WorkerConfig
from archsim_api.migrations import upgrade_to_head
from archsim_engine import SimulationLimits

API = "/api/v1"


@pytest.fixture
def db(db_url: str) -> Iterator[Database]:
    upgrade_to_head(db_url)
    database = Database(db_url)
    with database.session() as s:
        s.add(Project(id="p", name="p", document={}, schema_version="1"))
        s.commit()
    yield database
    database.engine.dispose()


def _enqueue(db: Database, document: dict[str, Any]) -> str:
    with db.session() as s:
        run = queue.enqueue(
            s,
            SimulationRun(project_id="p", seed=1, engine_version="test", input_document=document),
        )
        return run.id


def _row(db: Database, run_id: str) -> SimulationRun:
    with db.session() as s:
        run = s.get(SimulationRun, run_id)
        assert run is not None
        return run


def _until(check: Callable[[], Any], timeout: float, what: str) -> Any:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(0.05)
    raise AssertionError(f"timed out after {timeout:.0f} s waiting for {what}")


def _config(**overrides: Any) -> WorkerConfig:
    values: dict[str, Any] = {
        "slots": 1,
        "limits": SimulationLimits(),
        "executor": "process",
        "lease_seconds": 30.0,  # long: only the test that wants a lease to run out shortens it
        "heartbeat_seconds": 0.2,
        "reap_seconds": 0.2,
        "poll_seconds": 0.1,
        "cancel_grace_seconds": 1.0,
        "kill_grace_seconds": 2.0,
    }
    return WorkerConfig(**{**values, **overrides})


@contextlib.contextmanager
def _running(worker: Worker) -> Iterator[Worker]:
    worker.start()
    try:
        yield worker
    finally:
        worker.stop()


def _alive(pid: int) -> bool:
    if sys.platform == "win32":
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x00100000 | 0x1000, False, pid)  # SYNCHRONIZE | QUERY
        if not handle:
            return False
        try:
            return bool(kernel32.WaitForSingleObject(handle, 0) == 0x102)  # WAIT_TIMEOUT
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:  # pragma: no cover - someone else's process reusing the id
        return True
    try:  # a zombie has exited; its new parent has not reaped it yet
        stat = Path(f"/proc/{pid}/stat").read_text()
        return stat.rsplit(")", 1)[1].split()[0] != "Z"
    except (FileNotFoundError, IndexError):
        return sys.platform == "darwin"


# -- several API instances ---------------------------------------------------------------------


def test_cancel_from_another_api_instance(db_url: str) -> None:
    # "a" simulates (embedded worker); "b" only serves the API, like a second instance behind a
    # load balancer: it shares nothing with "a" but the database
    with (
        make_client(db_url, max_concurrent_runs=1) as a,
        make_client(db_url, embedded_worker=False) as b,
    ):
        p = create_project(b, starter(duration=600, warmup=5))
        running = b.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        queued = b.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        _until(
            lambda: a.get(f"{API}/runs/{running['id']}").json()["progress"] > 0,
            60,
            "a to simulate the first run",
        )
        # a queued run is cancelled at once, by whichever instance is asked
        assert b.post(f"{API}/runs/{queued['id']}/cancel").json()["status"] == "cancelled"
        # a running one is stopped by the worker holding it, at its next heartbeat
        assert b.post(f"{API}/runs/{running['id']}/cancel").json()["status"] == "running"
        t0 = time.monotonic()
        assert wait_for(b, running["id"], timeout=30)["status"] == "cancelled"
        assert time.monotonic() - t0 < 10
        assert a.get(f"{API}/runs/{queued['id']}").json()["status"] == "cancelled"


def test_an_api_instance_without_a_worker_only_queues(db_url: str) -> None:
    with make_client(db_url, embedded_worker=False) as api:
        p = create_project(api, starter(duration=4, warmup=1))
        run = api.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        time.sleep(1.0)
        assert api.get(f"{API}/runs/{run['id']}").json()["status"] == "queued"
        with make_client(db_url) as simulating:  # a worker turns up and takes it
            assert wait_for(simulating, run["id"], timeout=60)["status"] == "succeeded"


# -- leases ------------------------------------------------------------------------------------


def test_a_worker_that_stops_renewing_loses_the_run_to_another(db: Database) -> None:
    # sleep-based stand-ins, not real simulations: nothing in this process competes for the
    # GIL with the healthy worker's heartbeat
    run_id = _enqueue(db, {"seconds": 3})
    # "stalled" claims the run but never renews its lease (its heartbeat is a minute away)
    stalled = Worker(
        db,
        _config(executor="thread", lease_seconds=1.0, heartbeat_seconds=60.0, reap_seconds=60),
        worker_id="stalled",
        target=sim_targets.until_cancelled,
    )
    healthy = Worker(
        db, _config(executor="thread"), worker_id="healthy", target=sim_targets.cooperative
    )
    with _running(stalled):
        _until(lambda: _row(db, run_id).lease_owner == "stalled", 30, "the stalled claim")
        with _running(healthy):
            # the lease runs out; "healthy" reaps the run and takes it over
            _until(lambda: _row(db, run_id).lease_owner == "healthy", 30, "the takeover")
            # "stalled" notices (its progress write is refused) and stops simulating
            _until(lambda: not stalled.running, 30, "the stalled worker to drop the run")
            done = _until(
                lambda: (r := _row(db, run_id)).status != "running" and r, 60, "the healthy run"
            )
    assert (done.lease_owner, done.attempts) == ("healthy", 2)
    assert done.error == {"message": sim_targets.FINISHED}  # healthy's outcome, not stalled's


def test_stopping_a_worker_requeues_its_runs(db: Database) -> None:
    run_id = _enqueue(db, {"seconds": 600})
    worker = Worker(db, _config(), target=sim_targets.cooperative)
    with _running(worker):
        _until(lambda: worker.running.get(run_id), 60, "the run to start")
        pid = worker.running[run_id]
    assert pid is not None
    run = _row(db, run_id)
    assert (run.status, run.attempts, run.lease_owner) == ("queued", 0, None)
    _until(lambda: not _alive(pid), 10, "the simulation process to exit")


# -- stopping runs -----------------------------------------------------------------------------


def test_a_run_that_ignores_cancel_is_terminated(db: Database) -> None:
    run_id = _enqueue(db, {})
    worker = Worker(db, _config(cancel_grace_seconds=1.0), target=sim_targets.stubborn)
    with _running(worker):
        pid = _until(lambda: worker.running.get(run_id), 60, "the run to start")
        _until(lambda: _row(db, run_id).progress > 0, 30, "progress")
        assert queue.request_cancel(db, run_id) == "running"
        t0 = time.monotonic()
        _until(lambda: _row(db, run_id).status == "cancelled", 30, "the cancel")
        assert time.monotonic() - t0 < 1.0 + 2.0 + 5.0  # grace, then terminate
        assert not _alive(pid)


def test_a_run_past_its_hard_deadline_is_killed_and_failed(db: Database) -> None:
    run_id = _enqueue(db, {})
    config = _config(limits=SimulationLimits(max_wall_seconds=1.0), deadline_margin_seconds=1.0)
    worker = Worker(db, config, target=sim_targets.stubborn)
    with _running(worker):
        pid = _until(lambda: worker.running.get(run_id), 60, "the run to start")
        run = _until(lambda: (r := _row(db, run_id)).status != "running" and r, 30, "the deadline")
    assert run.status == "failed"
    assert "wall-clock limit of 1 s" in run.error["message"]
    assert not _alive(pid)


# -- a worker that dies ------------------------------------------------------------------------


def _wait_for_pid(lines: list[str], run_id: str) -> int:
    line = _until(
        lambda: next((ln for ln in lines if f"run {run_id}: simulating in process" in ln), None),
        90,
        "the worker to start the run",
    )
    found = re.search(r"simulating in process (\d+)", line)
    assert found is not None
    return int(found.group(1))


def _start_worker_process(
    db_url: str, own_group: bool = False
) -> tuple[subprocess.Popen[str], list[str]]:
    env = {
        **os.environ,
        "ARCHSIM_DATABASE_URL": db_url,
        "ARCHSIM_AUTO_MIGRATE": "false",
        "ARCHSIM_RUNNER": "process",
        "ARCHSIM_LEASE_SECONDS": "2",
        "ARCHSIM_HEARTBEAT_SECONDS": "0.5",
    }
    group: dict[str, Any] = {}
    if own_group:  # so the test can interrupt it and its children, as a terminal would
        if sys.platform == "win32":
            group["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            group["start_new_session"] = True
    proc = subprocess.Popen(
        [sys.executable, "-m", "archsim_api", "worker", "--slots", "1"],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        **group,
    )
    lines: list[str] = []

    def read() -> None:
        assert proc.stdout is not None
        for line in proc.stdout:
            lines.append(line)

    threading.Thread(target=read, daemon=True).start()
    return proc, lines


def test_killing_a_worker_leaves_no_simulation_process_and_its_run_is_taken_over(
    db: Database, db_url: str
) -> None:
    run_id = _enqueue(db, starter(duration=900, warmup=5))
    proc, lines = _start_worker_process(db_url)
    try:
        pid_line = _until(
            lambda: next(
                (ln for ln in lines if f"run {run_id}: simulating in process" in ln), None
            ),
            90,
            "the worker to start the run",
        )
        found = re.search(r"simulating in process (\d+)", pid_line)
        assert found is not None
        sim_pid = int(found.group(1))
        worker_id = _row(db, run_id).lease_owner
        assert worker_id and _alive(sim_pid)
        proc.kill()  # SIGKILL / TerminateProcess: no clean-up runs in the worker
        proc.wait(timeout=30)
    finally:
        if proc.poll() is None:
            proc.kill()
    # the simulation process went with it (Job Object, PR_SET_PDEATHSIG or the pipe watchdog)
    _until(lambda: not _alive(sim_pid), 15, f"simulation process {sim_pid} to exit")
    assert _row(db, run_id).status == "running"  # until its lease runs out

    # another worker reaps the expired lease and takes the run over (attempt 2)
    with _running(Worker(db, _config(executor="thread"), worker_id="next")):
        _until(lambda: _row(db, run_id).lease_owner == "next", 60, "the takeover")
        assert _row(db, run_id).attempts == 2
        assert queue.request_cancel(db, run_id) == "running"
        _until(lambda: _row(db, run_id).status == "cancelled", 30, "the cancel")
    with db.session() as s:  # the dead worker's heartbeat row is still there, stale
        assert worker_id in s.execute(select(queue.workers.c.id)).scalars().all()


def test_interrupting_a_worker_from_its_terminal_gives_its_run_back(
    db: Database, db_url: str
) -> None:
    # Ctrl+C (Ctrl+Break on Windows) reaches the worker and its simulation processes alike; the
    # simulation ignores it and the worker requeues the run, without counting the attempt
    run_id = _enqueue(db, starter(duration=900, warmup=5))
    proc, lines = _start_worker_process(db_url, own_group=True)
    try:
        sim_pid = _wait_for_pid(lines, run_id)
        _until(lambda: _row(db, run_id).progress > 0, 60, "progress")
        if sys.platform == "win32":
            os.kill(proc.pid, signal.CTRL_BREAK_EVENT)
        else:
            os.killpg(proc.pid, signal.SIGINT)
        assert proc.wait(timeout=60) == 0, "".join(lines)
    finally:
        if proc.poll() is None:
            proc.kill()
    run = _row(db, run_id)
    assert (run.status, run.attempts, run.lease_owner) == ("queued", 0, None), "".join(lines)
    _until(lambda: not _alive(sim_pid), 15, "the simulation process to exit")


# -- orphan guards -----------------------------------------------------------------------------


def test_a_spare_simulation_process_exits_when_it_is_not_needed() -> None:
    ctx = multiprocessing.get_context("spawn")
    _, up_send = ctx.Pipe(duplex=False)
    down_recv, down_send = ctx.Pipe(duplex=False)
    proc = ctx.Process(target=child.main, args=(up_send, down_recv, os.getpid()), daemon=True)
    proc.start()
    up_send.close()
    down_recv.close()
    down_send.close()  # it never gets a run
    proc.join(timeout=60)
    assert proc.exitcode == orphans.EXIT_ORPHANED


def test_a_simulation_process_exits_when_its_parent_pipe_closes() -> None:
    ctx = multiprocessing.get_context("spawn")
    up_recv, up_send = ctx.Pipe(duplex=False)
    down_recv, down_send = ctx.Pipe(duplex=False)
    proc = ctx.Process(target=child.main, args=(up_send, down_recv, os.getpid()), daemon=True)
    proc.start()  # not in a Job Object: only the pipe watchdog can end it
    up_send.close()
    down_recv.close()
    try:
        down_send.send(({}, None, SimulationLimits(), sim_targets.stubborn))
        assert up_recv.poll(60), "the child never reported progress"
        down_send.close()  # what the OS does when the worker dies
        proc.join(timeout=15)
        assert proc.exitcode == orphans.EXIT_ORPHANED
    finally:
        if proc.is_alive():
            proc.kill()
        up_recv.close()


@pytest.mark.skipif(sys.platform != "win32", reason="Windows Job Objects")
def test_a_windows_job_kills_its_processes_when_closed() -> None:
    job = orphans.KillOnCloseJob()
    # the interpreter itself: in a venv, sys.executable is a launcher that starts it as a child
    python = getattr(sys, "_base_executable", sys.executable)
    sleeper = subprocess.Popen([python, "-c", "import time; time.sleep(120)"])
    try:
        job.add(sleeper.pid)
        assert _alive(sleeper.pid)
        job.close()  # what Windows does when the worker holding it ends
        sleeper.wait(timeout=15)
        assert not _alive(sleeper.pid)
    finally:
        if sleeper.poll() is None:
            sleeper.kill()
