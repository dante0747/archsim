"""The process job runner: runs in worker processes give the same result as in threads, can be
cancelled, leave the API responsive while they simulate, and survive a crashed worker."""

from __future__ import annotations

import os
import time
from concurrent.futures.process import BrokenProcessPool
from typing import Any

import pytest
from fastapi.testclient import TestClient

from api_helpers import create_project, make_client, starter, wait_for

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
        assert wait_for(client, long_run["id"], timeout=120)["status"] == "cancelled"

    # the same input in a thread gives the same digest
    with make_client(db_url, runner="thread") as client:
        p = create_project(client)
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(client, run["id"], timeout=180)["status"] == "succeeded"
        report = client.get(f"{API}/runs/{run['id']}/report").json()
        assert report["provenance"]["resultDigest"] == digest


def _crash_a_worker(jobs: Any) -> None:
    """A worker process exits abruptly, the way an out-of-memory kill or ``taskkill /F`` ends it:
    the process pool is broken and every run it was simulating fails."""
    crash = jobs.pool.submit(os._exit, 1)
    with pytest.raises(BrokenProcessPool):
        crash.result(timeout=120)


def _start_long_runs(client: TestClient, count: int) -> list[str]:
    """Runs that take minutes, once each is simulating in a worker (it reports progress)."""
    project = create_project(client, starter(duration=900, warmup=5))
    url = f"{API}/projects/{project['id']}/runs"
    run_ids: list[str] = [client.post(url, json={}).json()["id"] for _ in range(count)]
    deadline = time.monotonic() + 120
    while not all(client.get(f"{API}/runs/{r}").json()["progress"] for r in run_ids):
        assert time.monotonic() < deadline
        time.sleep(0.1)
    return run_ids


def test_a_crashed_worker_fails_its_runs_and_later_runs_still_succeed(db_url: str) -> None:
    # three workers: two simulate long runs, the third takes the job that crashes it
    with make_client(db_url, runner="process", max_concurrent_runs=3) as client:
        app: Any = client.app
        jobs = app.state.jobs

        # two runs simulating when a worker dies: both fail with a clear message, and the pool
        # is replaced once, not once per run that saw it break
        running = _start_long_runs(client, 2)
        _crash_a_worker(jobs)
        for run_id in running:
            failed = wait_for(client, run_id, timeout=120)
            assert failed["status"] == "failed", failed
            assert "simulation worker stopped unexpectedly" in failed["error"]["message"]
        assert jobs.pool_generation == 1

        # later runs use a fresh pool
        p = create_project(client, starter(duration=4, warmup=1))
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(client, run["id"], timeout=180)["status"] == "succeeded"

        # a pool that broke while idle (no run saw it) is replaced when the next run starts
        _crash_a_worker(jobs)
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(client, run["id"], timeout=180)["status"] == "succeeded"
        assert jobs.pool_generation == 2
