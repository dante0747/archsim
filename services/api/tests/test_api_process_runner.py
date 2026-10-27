"""Runs in simulation processes (the default executor): the same result as in threads, cancellable,
the API responsive while they simulate, and a process that dies fails only its own run."""

from __future__ import annotations

import os
import signal
import time
from typing import Any

from fastapi.testclient import TestClient

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.jobs.queue import WORKER_STOPPED

API = "/api/v1"


def test_process_runner_matches_threads_cancels_and_stays_responsive(db_url: str) -> None:
    with make_client(db_url, runner="process", max_concurrent_runs=2) as client:
        p = create_project(client)
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        done = wait_for(client, run["id"], timeout=180)
        assert done["status"] == "succeeded", done
        digest = client.get(f"{API}/runs/{run['id']}/report").json()["provenance"]["resultDigest"]

        # a long run: cancel it while it runs; the API answers meanwhile
        long_doc = starter(duration=600, warmup=5)
        long_project = create_project(client, long_doc)
        long_run = client.post(f"{API}/projects/{long_project['id']}/runs", json={}).json()
        deadline = time.monotonic() + 120
        while client.get(f"{API}/runs/{long_run['id']}").json()["status"] == "queued":
            assert time.monotonic() < deadline
            time.sleep(0.1)
        t0 = time.perf_counter()
        assert client.get(f"{API}/meta").status_code == 200
        assert time.perf_counter() - t0 < 1.0  # not held behind the simulation
        client.post(f"{API}/runs/{long_run['id']}/cancel")
        assert wait_for(client, long_run["id"], timeout=60)["status"] == "cancelled"

    # the same input in a thread gives the same digest
    with make_client(db_url, runner="thread") as client:
        p = create_project(client)
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(client, run["id"], timeout=180)["status"] == "succeeded"
        report = client.get(f"{API}/runs/{run['id']}/report").json()
        assert report["provenance"]["resultDigest"] == digest


def _kill(pid: int) -> None:
    """End a process abruptly, the way an out-of-memory kill or ``taskkill /F`` does (on Windows
    any signal but Ctrl+C/Ctrl+Break is TerminateProcess)."""
    os.kill(pid, getattr(signal, "SIGKILL", signal.SIGTERM))


def _start_long_runs(client: TestClient, count: int) -> list[str]:
    """Runs that take minutes, once each is simulating in its process (it reports progress)."""
    project = create_project(client, starter(duration=900, warmup=5))
    url = f"{API}/projects/{project['id']}/runs"
    run_ids: list[str] = [client.post(url, json={}).json()["id"] for _ in range(count)]
    deadline = time.monotonic() + 120
    while not all(client.get(f"{API}/runs/{r}").json()["progress"] for r in run_ids):
        assert time.monotonic() < deadline
        time.sleep(0.1)
    return run_ids


def test_a_killed_simulation_process_fails_only_its_own_run(db_url: str) -> None:
    with make_client(db_url, runner="process", max_concurrent_runs=2) as client:
        app: Any = client.app
        worker = app.state.jobs.worker

        victim, survivor = _start_long_runs(client, 2)
        pids = worker.running
        assert pids[victim] and pids[survivor] and pids[victim] != pids[survivor]
        _kill(pids[victim])

        failed = wait_for(client, victim, timeout=60)
        assert failed["status"] == "failed", failed
        assert failed["error"]["message"] == WORKER_STOPPED
        # the other run, in a process of its own, carries on
        time.sleep(1.5)
        other = client.get(f"{API}/runs/{survivor}").json()
        assert other["status"] == "running", other
        assert worker.running.get(survivor) == pids[survivor]
        client.post(f"{API}/runs/{survivor}/cancel")
        assert wait_for(client, survivor, timeout=60)["status"] == "cancelled"

        # and later runs are unaffected
        p = create_project(client, starter(duration=4, warmup=1))
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(client, run["id"], timeout=180)["status"] == "succeeded"
