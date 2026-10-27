"""Share links: a frozen copy of one run's report anyone with the link can read, without traces
or telemetry addresses; 404 once revoked or expired; who may make, list and revoke them; the
per-address limit on the public page; and "Open a copy"."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import timedelta
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import update

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.db import SharedReport, SimulationRun, utcnow
from archsim_api.routers import shares
from fake_oauth import FakeProviders
from identity_helpers import acting_as, add_user, database, oauth_client, sign_in

API = "/api/v1"
PROMETHEUS = "Prometheus https://scraper:hunter2@prom.internal.example:9090"


@pytest.fixture
def client(db_url: str) -> Iterator[TestClient]:
    with make_client(db_url, embedded_worker=False) as c:
        yield c


def finished_run(client: TestClient, project: dict[str, Any], **report: Any) -> str:
    """A succeeded run written straight to the database, with a small report that has traces
    and a calibration naming a Prometheus address."""
    doc = project["document"]
    body = {
        "reportVersion": "1.13",
        "summary": {"latencyMs": {"p95": 42.0}, "throughputRps": 100.0},
        "findings": [{"kind": "healthy", "severity": "ok", "title": "Healthy"}],
        "traces": [{"id": 1, "spans": []}],
        "assumptions": {"calibration": {"source": PROMETHEUS, "inputs": 2}},
        "provenance": {"resultDigest": "r" * 64},
        **report,
    }
    with database(client).session() as s:
        run = SimulationRun(
            project_id=project["id"],
            workspace_id="default",
            created_by="local",
            label="Current design",
            status="succeeded",
            progress=1.0,
            seed=42,
            engine_version="test",
            result_digest="r" * 64,
            input_document={
                **doc,
                "calibration": {"at": "2026-10-01T00:00:00Z", "source": PROMETHEUS, "inputs": 2},
            },
            report=body,
        )
        s.add(run)
        s.commit()
        return run.id


def share(client: TestClient, run_id: str, **body: Any) -> dict[str, Any]:
    res = client.post(f"{API}/runs/{run_id}/shares", json=body)
    assert res.status_code == 201, res.text
    return dict(res.json())


def public(client: TestClient, slug: str, **params: Any) -> Any:
    return client.get(f"{API}/public/shares/{slug}", params=params)


# -- the snapshot --------------------------------------------------------------------------------
def test_a_share_is_a_frozen_copy_of_a_real_run_without_traces(db_url: str) -> None:
    with make_client(db_url) as c:
        project = create_project(c, starter(duration=4, warmup=1))
        run = c.post(f"{API}/projects/{project['id']}/runs", json={}).json()
        assert wait_for(c, run["id"])["status"] == "succeeded"
        full = c.get(f"{API}/runs/{run['id']}/report").json()
        assert full.get("traces")  # the stored report has them; the share must not
        made = share(c, run["id"])
        assert shares.SLUG_PATTERN.match(made["slug"])
        assert made["path"] == f"/r/{made['slug']}" and made["url"] is None  # no public URL
        assert made["status"] == "active" and made["viewCount"] == 0
        assert made["resultDigest"] == full["provenance"]["resultDigest"]
        assert made["reportVersion"] == full["reportVersion"]
        assert made["title"] == project["name"]
        expires = made["expiresAt"]
        assert expires is not None  # 30 days by default

        got = public(c, made["slug"])
        assert got.status_code == 200
        body = got.json()
        assert "traces" not in body["report"]
        assert "document" not in body  # the design stays behind; "Open a copy" uses it
        assert body["report"]["summary"] == full["summary"]
        assert body["objectives"] == project["document"]["objectives"]
        assert body["projectName"] == project["name"] and body["runLabel"] == "Current design"
        assert body["canCopy"] is True

        # frozen: changing the design, deleting the run and then the project changes nothing
        c.put(f"{API}/projects/{project['id']}", json={"name": "Renamed"})
        assert c.delete(f"{API}/runs/{run['id']}").status_code == 204
        assert public(c, made["slug"]).json()["report"] == body["report"]
        assert c.delete(f"{API}/projects/{project['id']}").status_code == 204
        again = public(c, made["slug"]).json()
        assert again["report"] == body["report"] and again["projectName"] == project["name"]
        listed = c.get(f"{API}/shares").json()  # still listed, in its workspace
        assert [(s["id"], s["projectId"], s["runId"]) for s in listed] == [(made["id"], None, None)]


def test_telemetry_addresses_never_reach_a_share(client: TestClient) -> None:
    project = create_project(client)
    made = share(client, finished_run(client, project))
    body = public(client, made["slug"]).json()
    source = body["report"]["assumptions"]["calibration"]["source"]
    assert source == "Prometheus (address hidden)"
    with database(client).session() as s:
        row = s.get(SharedReport, made["id"])
        assert row is not None and row.payload is not None
        assert "hunter2" not in str(row.payload) and "prom.internal" not in str(row.payload)


def test_title_and_expiry_can_be_chosen(client: TestClient) -> None:
    project = create_project(client)
    run_id = finished_run(client, project)
    never = share(client, run_id, title="  For the platform review  ", expiresInDays=None)
    assert never["title"] == "For the platform review" and never["expiresAt"] is None
    assert public(client, never["slug"]).json()["title"] == "For the platform review"
    bad = client.post(f"{API}/runs/{run_id}/shares", json={"expiresInDays": 0})
    assert bad.status_code == 422


def test_only_finished_runs_can_be_shared(client: TestClient) -> None:
    project = create_project(client)
    queued = client.post(f"{API}/projects/{project['id']}/runs", json={}).json()
    res = client.post(f"{API}/runs/{queued['id']}/shares", json={})
    assert res.status_code == 409
    assert "only finished runs" in res.json()["detail"]["message"]


# -- 404 once revoked or expired ---------------------------------------------------------------
def test_revoking_answers_404_at_once_and_deletes_the_copy(client: TestClient) -> None:
    project = create_project(client)
    made = share(client, finished_run(client, project))
    assert public(client, made["slug"]).status_code == 200
    assert client.delete(f"{API}/shares/{made['id']}").status_code == 204
    gone = public(client, made["slug"])
    assert gone.status_code == 404 and gone.json()["detail"]["message"] == "Share link not found"
    with database(client).session() as s:
        row = s.get(SharedReport, made["id"])
        assert row is not None and row.payload is None and row.revoked_at is not None
    assert client.delete(f"{API}/shares/{made['id']}").status_code == 204  # idempotent
    listed = client.get(f"{API}/projects/{project['id']}/shares").json()
    assert [s["status"] for s in listed] == ["revoked"]


def test_expired_links_answer_404(client: TestClient) -> None:
    project = create_project(client)
    made = share(client, finished_run(client, project), expiresInDays=1)
    with database(client).session() as s:
        s.execute(
            update(SharedReport)
            .where(SharedReport.id == made["id"])
            .values(expires_at=utcnow() - timedelta(seconds=1))
        )
        s.commit()
    assert public(client, made["slug"]).status_code == 404
    assert client.get(f"{API}/projects/{project['id']}/shares").json()[0]["status"] == "expired"


def test_unknown_and_malformed_links_answer_the_same_404(client: TestClient) -> None:
    for slug in ("0" * 22, "short", "x" * 23, "not-base62-but-22-chr!"):
        res = public(client, slug)
        assert res.status_code == 404, slug
        assert res.json()["detail"]["message"] == "Share link not found"


def test_public_answers_are_never_cached_or_indexed(client: TestClient) -> None:
    project = create_project(client)
    made = share(client, finished_run(client, project))
    h = public(client, made["slug"]).headers
    assert h["cache-control"] == "no-store" and h["x-robots-tag"] == "noindex"


def test_views_are_counted_except_for_previews(client: TestClient) -> None:
    project = create_project(client)
    made = share(client, finished_run(client, project))
    for _ in range(3):
        public(client, made["slug"])
    public(client, made["slug"], view="false")  # a link preview
    listed = client.get(f"{API}/projects/{project['id']}/shares").json()
    assert listed[0]["viewCount"] == 3


# -- roles -------------------------------------------------------------------------------------
def test_viewers_list_shares_but_only_editors_make_or_revoke_them(client: TestClient) -> None:
    project = create_project(client)
    run_id = finished_run(client, project)
    made = share(client, run_id)
    add_user(client, "vera", default="viewer")
    add_user(client, "ed", default="editor")
    with acting_as(client, "vera"):
        assert len(client.get(f"{API}/projects/{project['id']}/shares").json()) == 1
        assert client.post(f"{API}/runs/{run_id}/shares", json={}).status_code == 403
        assert client.delete(f"{API}/shares/{made['id']}").status_code == 403
    with acting_as(client, "ed"):
        assert client.post(f"{API}/runs/{run_id}/shares", json={}).status_code == 201
        assert client.delete(f"{API}/shares/{made['id']}").status_code == 204
    with acting_as(client, "ed", kind="token", read_only=True):  # a read token is a viewer
        assert client.post(f"{API}/runs/{run_id}/shares", json={}).status_code == 403


def test_outsiders_learn_nothing_about_a_workspaces_shares(client: TestClient) -> None:
    project = create_project(client)
    run_id = finished_run(client, project)
    made = share(client, run_id)
    add_user(client, "olga")  # no role anywhere
    with acting_as(client, "olga"):
        assert client.post(f"{API}/runs/{run_id}/shares", json={}).status_code == 404
        assert client.get(f"{API}/projects/{project['id']}/shares").status_code == 404
        res = client.delete(f"{API}/shares/{made['id']}")
        assert res.status_code == 404 and res.json()["detail"]["message"] == "Share link not found"
        assert client.delete(f"{API}/shares/nope").status_code == 404
        assert public(client, made["slug"]).status_code == 200  # the link itself is public


@pytest.fixture
def fake() -> FakeProviders:
    f = FakeProviders()
    f.add("ada", "ada@example.com")
    return f


def test_signed_in_servers_serve_shares_to_anyone_with_the_link(
    db_url: str, fake: FakeProviders
) -> None:
    with oauth_client(db_url, fake) as c:
        sign_in(c, fake, "ada")
        ws = c.get(f"{API}/auth/me").json()["workspaces"][0]["id"]
        res = c.post(f"{API}/projects", params={"workspace": ws}, json={"document": starter()})
        project = res.json()
        run_id = finished_run(c, project)
        with database(c).session() as s:  # the run belongs to ada's workspace
            s.execute(update(SimulationRun).values(workspace_id=ws, created_by=None))
            s.commit()
        made = share(c, run_id)
        anonymous = TestClient(c.app, base_url=str(c.base_url))
        assert anonymous.get(f"{API}/projects/{project['id']}/shares").status_code == 401
        assert anonymous.post(f"{API}/runs/{run_id}/shares", json={}).status_code == 401
        assert public(anonymous, made["slug"]).status_code == 200
        # copying needs a sign-in: the copy lands in your own workspace
        copy = anonymous.post(f"{API}/projects/from-share", json={"slug": made["slug"]})
        assert copy.status_code == 401


# -- the per-address limit -----------------------------------------------------------------------
def test_the_public_page_is_limited_per_address(
    db_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(shares, "PUBLIC_VIEWS_PER_IP", (3, 60))
    with make_client(db_url, embedded_worker=False, rate_limits=True) as c:
        project = create_project(c)
        made = share(c, finished_run(c, project))
        codes = [public(c, made["slug"]).status_code for _ in range(4)]
        assert codes == [200, 200, 200, 429]
        refused = public(c, made["slug"])
        assert refused.status_code == 429 and int(refused.headers["retry-after"]) >= 1
        assert public(c, "0" * 22).status_code == 429  # guessing links counts too
    with make_client(
        db_url, embedded_worker=False, rate_limits=True, trusted_proxies=["testclient"]
    ) as c:
        c.headers["X-ArchSim-Client-IP"] = "203.0.113.9"  # another visitor, behind the web
        assert public(c, made["slug"]).status_code == 200


def test_without_limits_the_public_page_is_not_counted(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(shares, "PUBLIC_VIEWS_PER_IP", (1, 60))
    project = create_project(client)
    made = share(client, finished_run(client, project))
    assert [public(client, made["slug"]).status_code for _ in range(3)] == [200] * 3


# -- open a copy ---------------------------------------------------------------------------------
def test_open_a_copy_makes_a_project_from_the_shared_design(client: TestClient) -> None:
    project = create_project(client)
    made = share(client, finished_run(client, project))
    team = client.post(f"{API}/workspaces", json={"name": "Mine"}).json()
    res = client.post(
        f"{API}/projects/from-share", params={"workspace": team["id"]}, json={"slug": made["slug"]}
    )
    assert res.status_code == 201, res.text
    copied = res.json()
    assert copied["name"] == f"{project['name']} (copy)"
    assert copied["document"]["architecture"] == project["document"]["architecture"]
    assert "hunter2" not in str(copied["document"])
    in_team = client.get(f"{API}/projects", params={"workspace": team["id"]}).json()
    assert [p["id"] for p in in_team] == [copied["id"]]
    named = client.post(f"{API}/projects/from-share", json={"slug": made["slug"], "name": "Fork"})
    assert named.status_code == 201 and named.json()["name"] == "Fork"

    add_user(client, "vera", default="viewer")
    with acting_as(client, "vera"):  # a viewer cannot add projects to the workspace
        assert (
            client.post(f"{API}/projects/from-share", json={"slug": made["slug"]}).status_code
            == 403
        )
    client.delete(f"{API}/shares/{made['id']}")
    gone = client.post(f"{API}/projects/from-share", json={"slug": made["slug"]})
    assert gone.status_code == 404


def test_new_slugs_are_random_128_bit_base62() -> None:
    slugs = {shares.new_slug() for _ in range(2000)}
    assert len(slugs) == 2000
    assert all(shares.SLUG_PATTERN.match(s) for s in slugs)
    assert set("".join(slugs)) <= set(shares.SLUG_ALPHABET)


def test_traces_and_addresses_are_left_out_of_the_public_report() -> None:
    report = {
        "traces": [1],
        "summary": {"x": 1},
        "assumptions": {"calibration": {"source": PROMETHEUS}},
        "input": {"calibration": {"source": "OpenTelemetry traces otlp.json"}},
    }
    out = shares.public_report(report)
    assert "traces" not in out and out["summary"] == {"x": 1}
    assert out["assumptions"]["calibration"]["source"] == "Prometheus (address hidden)"
    assert out["input"]["calibration"]["source"] == "OpenTelemetry traces otlp.json"
    assert report["assumptions"]["calibration"]["source"] == PROMETHEUS  # the original is kept
