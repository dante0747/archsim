"""Limits: the shared rate-limit counter, 429 with Retry-After, workspace profiles, project
quotas, calling Prometheus only where allowed, and the API's security headers."""

from __future__ import annotations

import socket
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import update

from api_helpers import create_project, make_client, starter
from archsim_api import limits
from archsim_api.config import Settings
from archsim_api.db import Database, SimulationRun, Workspace
from archsim_api.migrations import upgrade_to_head
from fake_oauth import FakeProviders
from identity_helpers import add_workspace, database, oauth_client, set_role, sign_in

API = "/api/v1"
REAL_GETADDRINFO = socket.getaddrinfo


@pytest.fixture
def fake() -> FakeProviders:
    f = FakeProviders()
    f.add("ada", "ada@example.com")
    return f


@pytest.fixture
def hosted(db_url: str, fake: FakeProviders) -> Iterator[TestClient]:
    with oauth_client(db_url, fake) as c:
        sign_in(c, fake, "ada")
        yield c


def _workspace(client: TestClient) -> str:
    return str(client.get(f"{API}/auth/me").json()["workspaces"][0]["id"])


def _set(client: TestClient, workspace_id: str, **values: Any) -> None:
    with database(client).session() as s:
        s.execute(update(Workspace).where(Workspace.id == workspace_id).values(**values))
        s.commit()


# -- the counter --------------------------------------------------------------------------------
@pytest.fixture
def db(db_url: str) -> Iterator[Database]:
    upgrade_to_head(db_url)
    database = Database(db_url)
    yield database
    database.engine.dispose()


def test_a_counter_allows_up_to_its_limit_then_says_how_long_to_wait(db: Database) -> None:
    assert [limits.hit(db, "k", 3, 3600) for _ in range(3)] == [None, None, None]
    retry = limits.hit(db, "k", 3, 3600)
    assert retry is not None and 1 <= retry <= 3600
    assert limits.hit(db, "other", 3, 3600) is None  # keys are apart
    assert limits.hit(db, "k", 3, 3600, cost=1) is not None  # refusals count nothing


def test_a_refused_batch_costs_nothing(db: Database) -> None:
    assert limits.hit(db, "b", 5, 3600, cost=4) is None
    assert limits.hit(db, "b", 5, 3600, cost=2) is not None  # 6 > 5: refused whole
    assert limits.hit(db, "b", 5, 3600, cost=1) is None  # the 4 + 1 still fit
    assert limits.hit(db, "b", 5, 3600, cost=9) is not None


def test_counters_hold_across_instances_and_threads(db: Database, db_url: str) -> None:
    other = Database(db_url)  # a second API instance on the same database
    for d in (db, other):  # connect once first: SQLite switches the file to WAL on connect
        with d.engine.connect():
            pass
    allowed: list[bool] = []
    lock = threading.Lock()

    def take(database: Database) -> None:
        for _ in range(10):
            ok = limits.hit(database, "shared", 25, 3600) is None
            with lock:
                allowed.append(ok)

    threads = [threading.Thread(target=take, args=(d,)) for d in (db, other, db, other)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    other.engine.dispose()
    assert allowed.count(True) == 25 and len(allowed) == 40


def test_old_windows_are_pruned(db: Database) -> None:
    from datetime import timedelta

    from archsim_api.db import utcnow

    limits.hit(db, "p", 1, 60)
    assert limits.prune(db, now=utcnow() + timedelta(seconds=61)) == 1
    assert limits.prune(db) == 0


# -- 429 on enqueue ----------------------------------------------------------------------------
def test_starting_runs_past_the_workspace_hourly_limit_is_429(hosted: TestClient) -> None:
    ws = _workspace(hosted)
    _set(hosted, ws, max_runs_per_hour=2)
    p = create_project(hosted)
    url = f"{API}/projects/{p['id']}/runs"
    assert hosted.post(url, json={}).status_code == 202
    batch = hosted.post(f"{url}/batch", json={"scenarioIds": [None, None]})
    assert batch.status_code == 429  # 1 + 2 > 2: none of the batch is queued
    assert hosted.post(url, json={}).status_code == 202
    res = hosted.post(url, json={})
    assert res.status_code == 429
    assert 1 <= int(res.headers["retry-after"]) <= 3600
    assert "runs for this hour" in res.json()["detail"]["message"]
    assert len(hosted.get(url).json()) == 2


def test_without_sign_in_there_is_no_hourly_limit(db_url: str) -> None:
    with make_client(db_url, embedded_worker=False) as c:
        _set(c, "default", max_runs_per_hour=None)
        p = create_project(c)
        for _ in range(5):
            assert c.post(f"{API}/projects/{p['id']}/runs", json={}).status_code == 202


def test_sign_in_attempts_are_limited_per_address(db_url: str, fake: FakeProviders) -> None:
    with oauth_client(db_url, fake) as c:
        codes = [
            c.get(f"{API}/auth/github/login", follow_redirects=False).status_code
            for _ in range(limits.LOGINS_PER_IP[0] + 1)
        ]
        assert codes[:-1] == [302] * limits.LOGINS_PER_IP[0] and codes[-1] == 429
    with oauth_client(db_url, fake, trusted_proxies=["testclient"]) as c:
        c.headers["X-ArchSim-Client-IP"] = "203.0.113.5"  # another visitor, behind the web
        assert c.get(f"{API}/auth/github/login", follow_redirects=False).status_code == 302


# -- profiles: the largest run, runs at once, projects -----------------------------------------
def test_each_run_carries_its_workspace_limits(hosted: TestClient) -> None:
    sandbox = add_workspace(hosted, "sand", kind="sandbox")
    me = hosted.get(f"{API}/auth/me").json()["user"]["id"]
    set_role(hosted, sandbox, me, "owner")
    p = hosted.post(
        f"{API}/projects", params={"workspace": sandbox}, json={"document": starter(4, 1)}
    ).json()
    run = hosted.post(f"{API}/projects/{p['id']}/runs", json={}).json()
    with database(hosted).session() as s:
        row = s.get(SimulationRun, run["id"])
        assert row is not None
        assert (row.max_requests, row.max_wall_seconds) == (200_000, 20.0)
    big = starter(600, 1)  # 400 req/s for ten minutes: more than a sandbox run may send
    res = hosted.post(f"{API}/projects/{p['id']}/runs", json={"document": big})
    assert res.status_code == 422


def test_profiles_per_kind(db_url: str) -> None:
    local = Settings(database_url=db_url)
    hosted_settings = Settings(database_url=db_url, rate_limits=True)
    assert limits.kind_caps(local) == {"personal": None, "team": None, "sandbox": 1}
    assert limits.kind_caps(hosted_settings) == {"personal": 2, "team": 4, "sandbox": 1}
    team = Workspace(id="t", name="t", kind="team")
    assert limits.calibrate_wall_seconds(local, team) == 60.0
    assert limits.calibrate_wall_seconds(hosted_settings, team) == 20.0  # hosted replay cap
    assert limits.simulation_limits(local, Workspace(kind="sandbox")).max_wall_seconds == 20.0


def test_workspaces_report_the_limits_that_apply(hosted: TestClient, db_url: str) -> None:
    (mine,) = hosted.get(f"{API}/workspaces").json()
    assert (mine["kind"], mine["maxConcurrentRuns"], mine["maxRunsPerHour"]) == ("personal", 2, 60)
    _set(hosted, mine["id"], max_concurrent_runs=5)
    assert hosted.get(f"{API}/workspaces/{mine['id']}").json()["maxConcurrentRuns"] == 5
    with make_client(db_url, embedded_worker=False) as local:
        (default,) = local.get(f"{API}/workspaces").json()
        assert (default["maxConcurrentRuns"], default["maxRunsPerHour"]) == (None, None)


def test_a_sandbox_holds_five_projects(hosted: TestClient) -> None:
    sandbox = add_workspace(hosted, "sand", kind="sandbox")
    me = hosted.get(f"{API}/auth/me").json()["user"]["id"]
    set_role(hosted, sandbox, me, "owner")
    url = f"{API}/projects?workspace={sandbox}"
    for _ in range(5):
        assert hosted.post(url, json={"document": starter()}).status_code == 201
    res = hosted.post(url, json={"document": starter()})
    assert res.status_code == 409 and "at most 5 projects" in res.json()["detail"]["message"]


# -- calling Prometheus ------------------------------------------------------------------------
# one histogram query, so calibrating has to call the server
MAPPING = "nodes: {api: {serviceTimeMs: {histogram: 'sum by (le) (rate(x_bucket[1m]))', unit: s}}}"


@contextmanager
def resolving(names: dict[str, str]) -> Iterator[None]:
    def fake(host: Any, port: Any, *args: Any, **kwargs: Any) -> Any:
        if host in names:
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (names[host], port))]
        return REAL_GETADDRINFO(host, port, *args, **kwargs)

    with pytest.MonkeyPatch.context() as mp:
        mp.setattr(socket, "getaddrinfo", fake)
        yield


def _calibrate(client: TestClient, url: str, **params: Any) -> Any:
    return client.post(
        f"{API}/calibrate",
        params=params,
        json={
            "document": starter(),
            "source": "prometheus",
            "mapping": MAPPING,
            "prometheusUrl": url,
            "range": "2026-10-01T00:00:00Z/2026-10-01T01:00:00Z",
        },
    )


def test_hosted_servers_call_no_prometheus_by_default(hosted: TestClient) -> None:
    for url in ("http://127.0.0.1:9090", "http://10.0.0.5:9090", "http://prom.example.com"):
        res = _calibrate(hosted, url)
        assert res.status_code == 422
        assert "ARCHSIM_OUTBOUND" in res.json()["detail"]["message"]


@pytest.mark.parametrize(
    "address",
    ["127.0.0.1", "10.0.0.8", "172.20.1.1", "192.168.1.9", "169.254.169.254", "100.64.0.9"],
)
def test_an_allowlisted_name_must_resolve_to_a_public_address(
    db_url: str, fake: FakeProviders, address: str
) -> None:
    with (
        oauth_client(db_url, fake, prometheus_allowed_hosts=["prom.corp.test"]) as c,
        resolving({"prom.corp.test": address}),
    ):
        sign_in(c, fake, "ada")
        res = _calibrate(c, "http://prom.corp.test:9090")
        assert res.status_code == 422
        assert "non-public" in res.json()["detail"]["message"]
        other = _calibrate(c, "http://127.0.0.1:9090")  # not on the list at all
        assert "not allowed" in other.json()["detail"]["message"]


def test_sandboxes_never_call_out_even_on_an_open_server(db_url: str) -> None:
    with make_client(db_url, embedded_worker=False) as c:
        add_workspace(c, "sand", kind="sandbox")
        set_role(c, "sand", "local", "owner")
        res = _calibrate(c, "http://127.0.0.1:9", workspace="sand")
        assert res.status_code == 422 and "ARCHSIM_OUTBOUND" in res.json()["detail"]["message"]
        # the default workspace on a local server still may (and fails to connect here)
        res = _calibrate(c, "http://127.0.0.1:9")
        assert "ARCHSIM_OUTBOUND" not in res.json()["detail"]["message"]


def test_calibrating_needs_an_editor(hosted: TestClient) -> None:
    from identity_helpers import acting_as, add_user

    ws = _workspace(hosted)
    add_user(hosted, "viv", **{ws: "viewer"})
    with acting_as(hosted, "viv"):
        res = hosted.post(
            f"{API}/calibrate",
            params={"workspace": ws},
            json={"document": starter(), "source": "otlp"},
        )
        assert res.status_code == 403


# -- security headers ----------------------------------------------------------------------------
def test_api_answers_carry_security_headers(hosted: TestClient) -> None:
    res = hosted.get(f"{API}/auth/me")
    h = res.headers
    assert h["x-content-type-options"] == "nosniff"
    assert h["x-frame-options"] == "DENY"
    assert h["referrer-policy"] == "no-referrer"
    assert h["content-security-policy"] == "default-src 'none'; frame-ancestors 'none'"
    assert h["cache-control"] == "no-store"
    assert h["strict-transport-security"].startswith("max-age=")
    docs = hosted.get(f"{API}/docs")
    assert "content-security-policy" not in docs.headers  # its script comes from a CDN


def test_a_local_server_sends_no_hsts(db_url: str) -> None:
    with make_client(db_url, embedded_worker=False) as c:
        h = c.get(f"{API}/health").headers
        assert h["x-content-type-options"] == "nosniff" and "strict-transport-security" not in h
