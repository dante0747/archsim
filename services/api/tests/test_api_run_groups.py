"""Seed groups and quick checks: ``seeds`` and ``mode`` on ``POST /projects/{id}/runs``,
``GET /run-groups/{id}``, the seeds each workspace may run, workspace scoping, and reruns that
reproduce a group's run or a sampled run exactly."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api import limits
from archsim_api.config import Settings
from archsim_api.db import Workspace
from archsim_engine import engine as engine_module
from archsim_engine.seeds import DASH
from fake_oauth import FakeProviders
from identity_helpers import acting_as, add_user, add_workspace, oauth_client, sign_in

API = "/api/v1"


def wait_for_group(client: TestClient, group_id: str, timeout: float = 120) -> dict:
    group = client.get(f"{API}/run-groups/{group_id}").json()
    for run in group["runs"]:
        wait_for(client, run["id"], timeout)
    return dict(client.get(f"{API}/run-groups/{group_id}").json())


def test_a_seed_group_runs_one_input_with_consecutive_seeds(client: TestClient) -> None:
    doc = starter(4, 1)
    doc["objectives"] = {"latencyP95Ms": 10_000, "availability": 0.5}
    p = create_project(client, doc)
    res = client.post(f"{API}/projects/{p['id']}/runs", json={"seeds": 3})
    assert res.status_code == 202, res.text
    first = res.json()
    assert first["groupId"] and first["seedIndex"] == 0 and first["seed"] == 42
    assert first["mode"] == "full"
    group = wait_for_group(client, first["groupId"])
    assert (group["kind"], group["baseSeed"], group["size"], group["status"]) == (
        "seeds",
        42,
        3,
        "succeeded",
    )
    assert [r["seed"] for r in group["runs"]] == [42, 43, 44]
    assert [r["seedIndex"] for r in group["runs"]] == [0, 1, 2]
    assert group["runs"][1]["label"] == "Current design · seed 2 of 3"
    summary = group["summary"]
    assert (summary["finished"], summary["met"], summary["verdict"]) == (3, 3, "holds")
    assert summary["text"].startswith("Meets the objectives in 3 of 3 runs · p95 ")
    assert summary["text"].endswith(f"· seeds 42{DASH}44")
    p95s = [r["summary"]["p95"] for r in group["runs"]]
    assert summary["latencyMs"]["p95"]["min"] == min(p95s)
    # one stored document; each report ran (and records) its own seed
    reports = [client.get(f"{API}/runs/{r['id']}/report").json() for r in group["runs"]]
    assert [r["provenance"]["seed"] for r in reports] == [42, 43, 44]
    assert len({r["provenance"]["inputDigest"] for r in reports}) == 3
    inputs = [client.get(f"{API}/runs/{r['id']}/input").json() for r in group["runs"]]
    assert all(i == inputs[0] for i in inputs) and inputs[0]["workload"]["seed"] == 42
    # a single run of the same input is the group's first run
    single = wait_for(client, client.post(f"{API}/projects/{p['id']}/runs", json={}).json()["id"])
    assert single["resultDigest"] == group["runs"][0]["resultDigest"]
    assert single["groupId"] is None and single["seedIndex"] is None

    # rerunning a group's run reproduces it, seed included
    again = client.post(f"{API}/runs/{group['runs'][2]['id']}/rerun").json()
    assert again["seed"] == 44 and again["reproducesRunId"] == group["runs"][2]["id"]
    assert wait_for(client, again["id"])["resultDigest"] == group["runs"][2]["resultDigest"]


def test_the_group_reads_its_objectives_from_the_input(client: TestClient) -> None:
    doc = starter(4, 1)
    doc["objectives"] = {"latencyP95Ms": 0.001}  # nothing is that fast
    p = create_project(client, doc)
    first = client.post(f"{API}/projects/{p['id']}/runs", json={"seeds": 2}).json()
    group = wait_for_group(client, first["groupId"])
    assert group["summary"]["verdict"] == "fails"
    assert group["summary"]["text"].startswith("Misses the objectives in 2 of 2 runs")


def test_seeds_the_base_seed_and_the_schema_are_checked(db_url: str) -> None:
    with make_client(db_url, embedded_worker=False) as c:
        p = create_project(c, starter(4, 1))
        url = f"{API}/projects/{p['id']}/runs"
        assert c.post(url, json={"seeds": 0}).status_code == 422
        assert c.post(url, json={"seeds": 11}).status_code == 422
        ten = c.post(url, json={"seeds": 10})  # locally, up to 10
        assert ten.status_code == 202, ten.text
        assert len(c.get(f"{API}/run-groups/{ten.json()['groupId']}").json()["runs"]) == 10
        late = starter(4, 1)
        late["workload"]["seed"] = 2**32 - 2
        res = c.post(url, json={"seeds": 3, "document": late})
        assert res.status_code == 422 and "go past the largest" in res.json()["detail"]["message"]
        assert c.get(f"{API}/run-groups/nope").status_code == 404


def test_seed_limits_per_workspace_kind(db_url: str) -> None:
    local = Settings(database_url=db_url)
    hosted = Settings(database_url=db_url, rate_limits=True)
    for kind in ("personal", "team", "sandbox"):
        assert limits.max_seeds(hosted, Workspace(kind=kind)) == 5
    assert limits.max_seeds(local, Workspace(kind="team")) == 10
    assert limits.max_seeds(local, Workspace(kind="sandbox")) == 5


@pytest.fixture
def hosted(db_url: str) -> Iterator[TestClient]:
    fake = FakeProviders()
    fake.add("ada", "ada@example.com")
    with oauth_client(db_url, fake, embedded_worker=False) as c:
        sign_in(c, fake, "ada")
        yield c


def test_a_hosted_workspace_runs_at_most_five_seeds(hosted: TestClient) -> None:
    p = create_project(hosted, starter(4, 1))
    url = f"{API}/projects/{p['id']}/runs"
    res = hosted.post(url, json={"seeds": 6})
    assert res.status_code == 422
    assert "at most 5 seeds" in res.json()["detail"]["message"]
    assert hosted.post(url, json={"seeds": 5}).status_code == 202
    assert len(hosted.get(url).json()) == 5


def test_a_seed_group_counts_every_run_against_the_hourly_limit(hosted: TestClient) -> None:
    from test_api_limits import _set, _workspace

    _set(hosted, _workspace(hosted), max_runs_per_hour=4)
    p = create_project(hosted, starter(4, 1))
    res = hosted.post(f"{API}/projects/{p['id']}/runs", json={"seeds": 5})
    assert res.status_code == 429  # 5 > 4: nothing is queued
    assert hosted.get(f"{API}/projects/{p['id']}/runs").json() == []


def test_groups_belong_to_their_workspace(client: TestClient) -> None:
    p = create_project(client, starter(4, 1))
    first = client.post(f"{API}/projects/{p['id']}/runs", json={"seeds": 2}).json()
    add_workspace(client, "elsewhere")
    add_user(client, "eve", elsewhere="owner")
    add_user(client, "vic", default="viewer")
    with acting_as(client, "eve"):
        res = client.get(f"{API}/run-groups/{first['groupId']}")
        assert res.status_code == 404
        assert res.json() == {
            "detail": {"message": f"Run group '{first['groupId']}' not found", "issues": []}
        }
        assert client.post(f"{API}/projects/{p['id']}/runs", json={"seeds": 2}).status_code == 404
    with acting_as(client, "vic"):  # viewers read groups but start none
        assert client.get(f"{API}/run-groups/{first['groupId']}").status_code == 200
        assert client.post(f"{API}/projects/{p['id']}/runs", json={"seeds": 2}).status_code == 403
    wait_for_group(client, first["groupId"])


# -- quick checks -------------------------------------------------------------------------------
def test_a_design_that_does_not_qualify_gets_the_reason(client: TestClient) -> None:
    p = create_project(client, starter(8, 2))
    res = client.post(f"{API}/projects/{p['id']}/runs", json={"mode": "sampled"})
    assert res.status_code == 422
    assert "too short" in res.json()["detail"]["message"]
    analysis = client.post(f"{API}/analyze", json={"document": starter(8, 2)}).json()
    assert analysis["quickCheck"]["eligible"] is False
    assert analysis["quickCheck"]["reason"] in res.json()["detail"]["message"]


@pytest.fixture
def tiny_samples(monkeypatch: pytest.MonkeyPatch) -> None:
    """Runs execute in threads here, so the engine's sampling thresholds can be shrunk: a few
    seconds of the starter then qualify, and the sample always counts as steady."""
    monkeypatch.setattr(engine_module, "SAMPLE_MIN_S", 1.0)
    monkeypatch.setattr(engine_module, "SAMPLE_MIN_REQUESTS", 200)
    monkeypatch.setattr(engine_module, "stationary", lambda report: (True, {}))


def test_a_rerun_of_a_quick_check_is_a_quick_check(client: TestClient, tiny_samples: None) -> None:
    doc = starter(8, 1)
    doc["workload"]["rps"] = 200
    p = create_project(client, doc)
    res = client.post(f"{API}/projects/{p['id']}/runs", json={"mode": "sampled"})
    assert res.status_code == 202, res.text
    run = wait_for(client, res.json()["id"])
    assert run["status"] == "succeeded" and run["mode"] == "sampled"
    assert run["label"] == "Current design · quick check"
    report = client.get(f"{API}/runs/{run['id']}/report").json()
    assert report["simulationMode"]["kind"] == "sampled"
    assert report["simulationMode"]["requestedDurationS"] == 8
    again = client.post(f"{API}/runs/{run['id']}/rerun").json()
    assert again["mode"] == "sampled"
    again = wait_for(client, again["id"])
    assert again["inputDigest"] == run["inputDigest"]
    assert again["resultDigest"] == run["resultDigest"]
    # a full run of the same input is a different (longer) run
    full = wait_for(client, client.post(f"{API}/projects/{p['id']}/runs", json={}).json()["id"])
    assert full["mode"] == "full" and full["inputDigest"] != run["inputDigest"]


def test_quick_checks_with_seeds(client: TestClient, tiny_samples: None) -> None:
    doc = starter(8, 1)
    doc["workload"]["rps"] = 200
    p = create_project(client, doc)
    first = client.post(f"{API}/projects/{p['id']}/runs", json={"mode": "sampled", "seeds": 2})
    assert first.status_code == 202, first.text
    group = wait_for_group(client, first.json()["groupId"])
    assert group["mode"] == "sampled" and [r["mode"] for r in group["runs"]] == ["sampled"] * 2
    assert group["status"] == "succeeded"
