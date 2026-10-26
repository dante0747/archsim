"""The process job runner: runs in worker processes give the same result as in threads, can be
cancelled, and leave the API responsive while they simulate."""

from __future__ import annotations

import time

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
