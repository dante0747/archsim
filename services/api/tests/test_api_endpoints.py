"""End-to-end API tests: projects, persistence, import/export, runs, reports, comparison."""

from __future__ import annotations

import copy
import time

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.db import Project, SimulationRun

API = "/api/v1"


def test_meta_catalog_examples_schema(client: TestClient) -> None:
    assert client.get(f"{API}/health").json() == {"status": "ok", "database": "ok"}
    meta = client.get(f"{API}/meta").json()
    assert meta["engineVersion"] and meta["schemaVersion"] == "1.0"
    assert meta["database"] in ("sqlite", "postgresql")
    cat = client.get(f"{API}/components").json()
    types = {c["type"] for c in cat["components"]}
    assert {"traffic_source", "load_balancer", "service", "cache", "database"} <= types
    assert any(f["key"] == "latencyMs" for f in cat["edgeFields"])
    examples = client.get(f"{API}/examples").json()
    ids = {e["id"] for e in examples}
    assert {"cache-aside-starter", "queueing-lab", "retry-storm-ttl-cache"} <= ids
    assert client.get(f"{API}/examples/cache-aside-starter").json()["schemaVersion"] == "1.0"
    assert client.get(f"{API}/examples/nope").status_code == 404
    assert client.get(f"{API}/schema/architecture").json()["title"] == "ArchitectureDocument"
    assert "/api/v1/projects" in client.get(f"{API}/openapi.json").json()["paths"]


def test_validate_endpoint_reports_issues(client: TestClient) -> None:
    good = client.post(f"{API}/validate", json={"document": starter()}).json()
    assert good == {"valid": True, "issues": []}
    bad = starter()
    bad["architecture"]["edges"] = [
        e for e in bad["architecture"]["edges"] if e["id"] != "cache-db"
    ]
    res = client.post(f"{API}/validate", json={"document": bad}).json()
    assert not res["valid"]
    assert res["issues"][0]["code"] == "cache.miss_unrouted"
    assert res["issues"][0]["nodeId"] == "cache"
    sc = client.post(f"{API}/validate", json={"document": starter(), "scenarioId": "nope"}).json()
    assert sc["issues"][0]["code"] == "scenario.unknown"


def test_project_crud_and_revision_conflict(client: TestClient) -> None:
    p = create_project(client)
    assert p["name"] == "Cache-aside web API (starter)"
    assert p["nodeCount"] == 5 and p["revision"] == 1
    doc = p["document"]
    next(n for n in doc["architecture"]["nodes"] if n["id"] == "api")["config"]["replicas"] = 5
    upd = client.put(
        f"{API}/projects/{p['id']}",
        json={"name": "My system", "document": doc, "expectedRevision": 1},
    )
    assert upd.status_code == 200, upd.text
    body = upd.json()
    assert body["revision"] == 2 and body["name"] == "My system"
    assert body["document"]["metadata"]["name"] == "My system"
    stale = client.put(f"{API}/projects/{p['id']}", json={"document": doc, "expectedRevision": 1})
    assert stale.status_code == 409
    listing = client.get(f"{API}/projects").json()
    assert [x["id"] for x in listing] == [p["id"]]
    assert client.post(f"{API}/projects/{p['id']}/duplicate").json()["name"] == "My system (copy)"
    assert client.delete(f"{API}/projects/{p['id']}").status_code == 204
    assert client.get(f"{API}/projects/{p['id']}").status_code == 404


def test_create_from_example_and_blank(client: TestClient) -> None:
    ex = client.post(f"{API}/projects", json={"exampleId": "queueing-lab"}).json()
    assert ex["scenarioCount"] == 4
    blank = client.post(f"{API}/projects", json={"name": "Blank"}).json()
    assert blank["nodeCount"] == 0 and blank["name"] == "Blank"
    assert client.post(f"{API}/projects", json={"exampleId": "nope"}).status_code == 404


def test_work_in_progress_graphs_can_be_saved_but_not_simulated(client: TestClient) -> None:
    p = create_project(client)
    doc = p["document"]
    doc["architecture"]["edges"] = []  # graph-level errors: allowed when saving
    res = client.put(f"{API}/projects/{p['id']}", json={"document": doc})
    assert res.status_code == 200
    run = client.post(f"{API}/projects/{p['id']}/runs", json={})
    assert run.status_code == 422
    detail = run.json()["detail"]
    assert detail["message"].startswith("Cannot simulate")
    assert any(i["code"] == "port.missing_link" for i in detail["issues"])
    # schema-level errors are rejected on save
    doc["architecture"]["edges"] = [
        {"id": "x", "source": "a", "target": "b", "config": {"maxAttempts": 99}}
    ]
    assert client.put(f"{API}/projects/{p['id']}", json={"document": doc}).status_code == 422


def test_import_export_round_trip(client: TestClient) -> None:
    imported = client.post(f"{API}/projects/import", json=starter())
    assert imported.status_code == 201
    pid = imported.json()["id"]
    exported = client.get(f"{API}/projects/{pid}/export")
    assert "attachment" in exported.headers["content-disposition"]
    again = client.post(f"{API}/projects/import", json=exported.json()).json()
    assert again["document"] == exported.json()
    no_version = starter()
    del no_version["schemaVersion"]
    res = client.post(f"{API}/projects/import", json=no_version)
    assert res.status_code == 422
    assert "schemaVersion" in res.json()["detail"]["message"]


def test_run_lifecycle_report_and_reproduce(client: TestClient) -> None:
    p = create_project(client)
    res = client.post(f"{API}/projects/{p['id']}/runs", json={"scenarioId": "three-api-replicas"})
    assert res.status_code == 202, res.text
    queued = res.json()
    assert queued["status"] in ("queued", "running")
    assert queued["label"] == "3 API replicas"
    done = wait_for(client, queued["id"])
    assert done["status"] == "succeeded", done
    assert done["progress"] == 1.0
    assert done["summary"]["throughputRps"] > 300
    assert done["resultDigest"] and done["inputDigest"]

    report = client.get(f"{API}/runs/{done['id']}/report").json()
    assert report["provenance"]["scenarioId"] == "three-api-replicas"
    assert report["provenance"]["resultDigest"] == done["resultDigest"]
    assert {n["id"] for n in report["nodes"]} == {"clients", "lb", "api", "cache", "db"}
    assert report["timeseries"]["throughputRps"]
    assert report["assumptions"]["modelNotes"]

    # reproduce from the stored snapshot -> identical results
    rr = client.post(f"{API}/runs/{done['id']}/rerun").json()
    assert rr["reproducesRunId"] == done["id"]
    again = wait_for(client, rr["id"])
    assert again["resultDigest"] == done["resultDigest"]
    assert again["inputDigest"] == done["inputDigest"]

    runs = client.get(f"{API}/projects/{p['id']}/runs").json()
    assert {r["id"] for r in runs} == {done["id"], again["id"]}
    assert client.get(f"{API}/runs/{done['id']}/input").json()["schemaVersion"] == "1.0"


def test_run_with_unsaved_document_snapshot(client: TestClient) -> None:
    p = create_project(client)
    edited = copy.deepcopy(p["document"])
    edited["workload"]["rps"] = 50
    run = client.post(f"{API}/projects/{p['id']}/runs", json={"document": edited}).json()
    done = wait_for(client, run["id"])
    assert done["summary"]["offeredRps"] < 80  # simulated the edited document...
    saved = client.get(f"{API}/projects/{p['id']}").json()
    assert saved["document"]["workload"]["rps"] == 400  # ...without changing the saved one
    report = client.get(f"{API}/runs/{done['id']}/report").json()
    assert report["input"]["workload"]["rps"] == 50


def test_batch_scenarios_and_compare(client: TestClient) -> None:
    p = create_project(client)
    res = client.post(
        f"{API}/projects/{p['id']}/runs/batch",
        json={"scenarioIds": ["one-api-replica", "three-api-replicas"]},
    )
    assert res.status_code == 202
    a, b = (wait_for(client, r["id"]) for r in res.json())
    assert a["status"] == b["status"] == "succeeded"
    cmp = client.get(f"{API}/compare", params={"a": a["id"], "b": b["id"]}).json()
    assert cmp["inputDiff"] == [
        {"path": "node[api].replicas", "label": "API Servers: replicas", "a": 1, "b": 3}
    ]
    assert cmp["labels"] == {"a": "1 API replica", "b": "3 API replicas"}
    assert any("no longer saturated" in e for e in cmp["explanations"])
    bad = client.post(f"{API}/projects/{p['id']}/runs/batch", json={"scenarioIds": ["nope"]})
    assert bad.status_code == 422


def test_limits_and_cancellation(db_url: str) -> None:
    with make_client(db_url, max_requests=1000) as c:
        p = create_project(c)
        res = c.post(f"{API}/projects/{p['id']}/runs", json={})
        assert res.status_code == 422
        assert "Reduce RPS or duration" in res.json()["detail"]["message"]

    with make_client(db_url, max_concurrent_runs=1) as c:
        p = create_project(c, starter(duration=120, warmup=5))
        first = c.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        second = c.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        # cancel the queued one immediately and the running one cooperatively
        assert c.post(f"{API}/runs/{second['id']}/cancel").json()["status"] == "cancelled"
        deadline = time.monotonic() + 10
        while c.get(f"{API}/runs/{first['id']}").json()["status"] == "queued":
            assert time.monotonic() < deadline
            time.sleep(0.02)
        c.post(f"{API}/runs/{first['id']}/cancel")
        assert wait_for(c, first["id"])["status"] == "cancelled"
        assert c.get(f"{API}/runs/{first['id']}/report").status_code == 409


def test_saved_work_survives_restart_and_interrupted_runs_are_marked(db_url: str) -> None:
    with make_client(db_url) as c:
        p = create_project(c)
        run = c.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        wait_for(c, run["id"])
    # simulate a crash that left a run active
    engine = create_engine(db_url)
    with Session(engine) as s:
        stuck = SimulationRun(
            project_id=p["id"],
            status="running",
            progress=0.3,
            seed=1,
            engine_version="x",
            input_document={},
        )
        s.add(stuck)
        s.commit()
        stuck_id = stuck.id
        assert s.get(Project, p["id"]) is not None
    engine.dispose()
    with make_client(db_url) as c:
        assert c.get(f"{API}/projects/{p['id']}").json()["name"] == p["name"]
        assert c.get(f"{API}/runs/{run['id']}").json()["status"] == "succeeded"
        recovered = c.get(f"{API}/runs/{stuck_id}").json()
        assert recovered["status"] == "failed"
        assert "Interrupted" in recovered["error"]["message"]


def test_deleting_project_removes_runs(client: TestClient) -> None:
    p = create_project(client)
    run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
    wait_for(client, run["id"])
    client.delete(f"{API}/projects/{p['id']}")
    assert client.get(f"{API}/runs/{run['id']}").status_code == 404


def test_body_size_limit(db_url: str) -> None:
    with make_client(db_url, max_body_bytes=10_000) as c:
        res = c.post(f"{API}/projects/import", json=starter() | {"pad": "x" * 20_000})
        assert res.status_code == 413


def test_large_seed_round_trips(client: TestClient) -> None:
    # Seeds go up to 2**32 - 1, beyond a 32-bit INTEGER column (matters on PostgreSQL).
    doc = starter(duration=3, warmup=1)
    doc["workload"]["seed"] = 4_000_000_000
    p = create_project(client, doc)
    run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
    done = wait_for(client, run["id"])
    assert done["status"] == "succeeded"
    assert done["seed"] == 4_000_000_000
