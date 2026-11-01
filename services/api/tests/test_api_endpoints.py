"""End-to-end API tests: projects, persistence, import/export, runs, reports, comparison."""

from __future__ import annotations

import copy
import json
import os
import time
from datetime import timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.db import Project, SimulationRun, utcnow

API = "/api/v1"


def test_meta_catalog_examples_schema(client: TestClient) -> None:
    assert client.get(f"{API}/health").json() == {"status": "ok", "database": "ok"}
    meta = client.get(f"{API}/meta").json()
    assert meta["engineVersion"] and meta["schemaVersion"] == "1.7"
    assert meta["database"] in ("sqlite", "postgresql")
    cat = client.get(f"{API}/components").json()
    types = {c["type"] for c in cat["components"]}
    assert {"traffic_source", "load_balancer", "service", "cache", "database"} <= types
    assert any(f["key"] == "latencyMs" for f in cat["edgeFields"])
    examples = client.get(f"{API}/examples").json()
    ids = {e["id"] for e in examples}
    assert {"cache-aside-starter", "queueing-lab", "retry-storm-ttl-cache"} <= ids
    assert client.get(f"{API}/examples/cache-aside-starter").json()["schemaVersion"] == "1.3"
    assert client.get(f"{API}/examples/nope").status_code == 404
    assert client.get(f"{API}/schema/architecture").json()["title"] == "ArchitectureDocument"
    assert "/api/v1/projects" in client.get(f"{API}/openapi.json").json()["paths"]


def test_validate_endpoint_reports_issues(client: TestClient) -> None:
    good = client.post(f"{API}/validate", json={"document": starter()}).json()
    assert good == {"valid": True, "issues": []}
    bad = starter()  # cache misses that nothing reads: the API no longer reads the DB on a miss
    for e in bad["architecture"]["edges"]:
        if e["id"] == "api-db":
            e["config"]["readsOnMiss"] = False
    res = client.post(f"{API}/validate", json={"document": bad}).json()
    assert not res["valid"]
    assert res["issues"][0]["code"] == "cache.miss_unrouted"
    assert res["issues"][0]["nodeId"] == "cache"
    sc = client.post(f"{API}/validate", json={"document": starter(), "scenarioId": "nope"}).json()
    assert sc["issues"][0]["code"] == "scenario.unknown"


def test_analyze_returns_sizes_cost_and_review(client: TestClient) -> None:
    res = client.post(f"{API}/analyze", json={"document": starter()})
    assert res.status_code == 200
    a = res.json()
    api = a["sizing"]["nodes"]["api"]
    assert api["specs"]["cpuCores"]["source"] == "auto" and api["auto"]["cpuCores"] >= 1
    assert api["pricePerHour"] > 0 and "req/s per replica" in api["why"]["cpuCores"]
    assert a["cost"]["monthly"] > 0 and a["cost"]["byNode"]
    assert {f["rule"] for f in a["review"]} and sum(a["reviewCounts"].values()) == len(a["review"])
    assert a["peakRps"] == starter()["workload"]["rps"]
    sc = client.post(
        f"{API}/analyze", json={"document": starter(), "scenarioId": "three-api-replicas"}
    ).json()
    assert sc["scenarioId"] == "three-api-replicas"
    # the scenario runs on the design's sizes: same auto size, more replicas billed
    assert sc["sizing"]["nodes"]["api"]["auto"] == api["auto"]
    assert sc["cost"]["monthly"] > a["cost"]["monthly"]
    bad = client.post(f"{API}/analyze", json={"document": {"kind": "nope"}})
    assert bad.status_code == 422 and bad.json()["detail"]["issues"]


def test_pricing_lists_every_snapshot_and_a_pinned_one_is_used(
    client: TestClient, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    fixtures = Path(__file__).resolve().parents[3] / "packages/engine/tests/fixtures/pricing"
    data = json.loads((fixtures / "example-cloud-test-1-2026-10-01.json").read_text("utf-8"))
    (tmp_path / "newer.json").write_text(json.dumps({**data, "snapshotDate": "2026-11-01"}))
    monkeypatch.setenv("ARCHSIM_PRICES_DIR", os.pathsep.join([str(fixtures), str(tmp_path)]))
    listed = client.get(f"{API}/pricing").json()["sources"]
    assert listed[0]["provider"] == "generic"
    # the AWS snapshot the engine ships is listed too
    assert any(s["provider"] == "aws" and s["region"] == "us-east-1" for s in listed)
    sources = [s for s in listed if s["provider"] == "example-cloud"]
    assert [(s["date"], s["newest"]) for s in sources] == [
        ("2026-11-01", True),
        ("2026-10-01", False),
    ]
    assert all(len(s["sha256"]) == 64 for s in listed[1:])
    doc = starter()
    doc["pricing"] = {"provider": "example-cloud", "region": "test-1", "snapshotDate": "2026-10-01"}
    cost = client.post(f"{API}/analyze", json={"document": doc}).json()["cost"]
    assert cost["pricing"]["snapshotDate"] == "2026-10-01"
    assert cost["pricing"]["sha256"] == sources[1]["sha256"]


def test_api_run_matches_the_engine_digest_with_auto_sized_parts(client: TestClient) -> None:
    from archsim_engine import run_simulation

    p = create_project(client)
    doc = starter(duration=5, warmup=1)
    for scenario in (None, "three-api-replicas"):
        run = client.post(
            f"{API}/projects/{p['id']}/runs", json={"document": doc, "scenarioId": scenario}
        ).json()
        assert wait_for(client, run["id"])["status"] == "succeeded"
        report = client.get(f"{API}/runs/{run['id']}/report").json()
        direct = run_simulation(doc, scenario_id=scenario)
        assert report["provenance"]["resultDigest"] == direct["provenance"]["resultDigest"], (
            scenario
        )
        assert report["provenance"]["inputDigest"] == direct["provenance"]["inputDigest"], scenario
        assert report["sizing"]["nodes"]["api"]["specs"]["cpuCores"]["source"] == "auto"


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


def test_opening_a_template_twice_reuses_the_unedited_copy(client: TestClient) -> None:
    body = {"exampleId": "queueing-lab", "reuseUnedited": True}
    first = client.post(f"{API}/projects", json=body)
    assert first.status_code == 201
    again = client.post(f"{API}/projects", json=body)
    assert again.status_code == 200 and again.json()["id"] == first.json()["id"]
    # Without the flag it is an explicit new project.
    plain = client.post(f"{API}/projects", json={"exampleId": "queueing-lab"})
    assert plain.status_code == 201 and plain.json()["id"] != first.json()["id"]
    # A project the user named is theirs: never handed back as "the template".
    named = client.post(f"{API}/projects", json={**body, "name": "Mine"})
    assert named.status_code == 201 and named.json()["name"] == "Mine"
    # Once a copy is saved it belongs to the user too; the template opens an unedited copy.
    for p in (first.json(), plain.json()):
        saved = client.put(f"{API}/projects/{p['id']}", json={"document": p["document"]})
        assert saved.json()["revision"] == 2
    fresh = client.post(f"{API}/projects", json=body)
    assert fresh.status_code == 201
    assert fresh.json()["id"] not in {first.json()["id"], plain.json()["id"], named.json()["id"]}
    # A duplicate is the user's own project, not a copy of the template.
    dup = client.post(f"{API}/projects/{fresh.json()['id']}/duplicate").json()
    client.delete(f"{API}/projects/{fresh.json()['id']}")
    assert client.post(f"{API}/projects", json=body).json()["id"] != dup["id"]


def test_health_reports_503_when_the_database_is_down(client: TestClient, tmp_path: Path) -> None:
    db = client.app.state.db  # type: ignore[attr-defined]
    real_engine = db.engine
    db.engine = create_engine(f"sqlite:///{(tmp_path / 'missing' / 'x.db').as_posix()}")
    try:
        res = client.get(f"{API}/health")
    finally:
        db.engine.dispose()
        db.engine = real_engine
    assert res.status_code == 503
    assert res.json() == {"status": "degraded", "database": "unavailable"}
    assert client.get(f"{API}/health").status_code == 200


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
    assert client.get(f"{API}/runs/{done['id']}/input").json()["schemaVersion"] == "1.7"


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


def test_saved_work_survives_restart_and_interrupted_runs_are_requeued(db_url: str) -> None:
    with make_client(db_url) as c:
        p = create_project(c)
        run = c.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(c, run["id"])["status"] == "succeeded"
        document = c.get(f"{API}/runs/{run['id']}/input").json()

    # a crash left runs held by a worker that is gone: their leases ran out
    engine = create_engine(db_url)
    gone = utcnow() - timedelta(minutes=1)
    with Session(engine) as s:
        stuck = {
            "retried": SimulationRun(attempts=1, lease_owner="gone", lease_expires_at=gone),
            "exhausted": SimulationRun(attempts=2, lease_owner="gone", lease_expires_at=gone),
            "pre-queue": SimulationRun(attempts=0),  # left by an API from before the queue
        }
        for r in stuck.values():
            r.project_id, r.status, r.progress, r.seed = p["id"], "running", 0.3, 1
            r.engine_version, r.input_document = "x", document
            s.add(r)
        s.commit()
        ids = {name: r.id for name, r in stuck.items()}
        assert s.get(Project, p["id"]) is not None
    engine.dispose()

    with make_client(db_url) as c:
        assert c.get(f"{API}/projects/{p['id']}").json()["name"] == p["name"]
        assert c.get(f"{API}/runs/{run['id']}").json()["status"] == "succeeded"
        # requeued at start-up and simulated again
        for name in ("retried", "pre-queue"):
            assert wait_for(c, ids[name])["status"] == "succeeded", name
        # a run whose worker stopped on its second attempt too is not tried a third time
        failed = c.get(f"{API}/runs/{ids['exhausted']}").json()
        assert failed["status"] == "failed"
        assert "simulation worker stopped unexpectedly" in failed["error"]["message"]


def test_a_run_in_flight_when_the_api_stops_finishes_after_a_restart(db_url: str) -> None:
    with make_client(db_url) as c:
        p = create_project(c, starter(duration=20, warmup=2))
        run = c.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        deadline = time.monotonic() + 60
        while c.get(f"{API}/runs/{run['id']}").json()["progress"] == 0:
            assert time.monotonic() < deadline
            time.sleep(0.05)
    # stopping gave the run back without counting the attempt
    engine = create_engine(db_url)
    with Session(engine) as s:
        row = s.get(SimulationRun, run["id"])
        assert row is not None
        assert (row.status, row.attempts, row.lease_owner) == ("queued", 0, None)
    engine.dispose()
    with make_client(db_url) as c:
        done = wait_for(c, run["id"], timeout=120)
        assert done["status"] == "succeeded", done


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
