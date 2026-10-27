"""Personal API tokens (Bearer) and the CSRF header that cookie requests need."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from api_helpers import create_project, make_client, starter
from archsim_api.db import ApiToken, utcnow
from fake_oauth import FakeProviders
from identity_helpers import database, oauth_client, sign_in

API = "/api/v1"


@pytest.fixture
def fake() -> FakeProviders:
    f = FakeProviders()
    f.add("ada", "ada@example.com")
    f.add("bob", "bob@example.com")
    return f


@pytest.fixture
def client(db_url: str, fake: FakeProviders) -> Iterator[TestClient]:
    with oauth_client(db_url, fake) as c:
        sign_in(c, fake, "ada")
        yield c


def _token(client: TestClient, **body: object) -> dict[str, object]:
    res = client.post(f"{API}/tokens", json={"name": "CI", **body})
    assert res.status_code == 201, res.text
    return dict(res.json())


def _bearer(client: TestClient, token: object) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _anonymous(client: TestClient) -> TestClient:
    """The same API without the browser's cookies (as a CLI would call it)."""
    client.cookies.clear()
    client.headers.pop("X-ArchSim-CSRF", None)
    return client


# -- tokens ------------------------------------------------------------------------------------
def test_a_token_is_shown_once_and_stored_as_a_hash(client: TestClient) -> None:
    created = _token(client, scope="write")
    secret = str(created["token"])
    assert secret.startswith("asim_pat_") and len(secret) > 40
    assert created["prefix"] == secret[:13]
    listed = client.get(f"{API}/tokens").json()
    assert [t["id"] for t in listed] == [created["id"]] and "token" not in listed[0]
    with database(client).session() as s:
        stored = s.execute(select(ApiToken.token_hash)).scalar_one()
        assert stored != secret and secret not in stored


def test_a_write_token_works_like_its_owner_without_cookies_or_csrf(client: TestClient) -> None:
    secret = _token(client, scope="write")["token"]
    mine = client.get(f"{API}/auth/me").json()
    c = _anonymous(client)
    assert c.get(f"{API}/projects").status_code == 401
    headers = _bearer(c, secret)
    me = c.get(f"{API}/auth/me", headers=headers).json()
    assert (me["kind"], me["user"]["id"], me["csrfToken"]) == ("token", mine["user"]["id"], None)
    res = c.post(f"{API}/projects", json={"document": starter()}, headers=headers)
    assert res.status_code == 201
    run = c.post(f"{API}/projects/{res.json()['id']}/runs", json={}, headers=headers)
    assert run.status_code == 202
    with database(client).session() as s:
        used = s.execute(select(ApiToken.last_used_at)).scalar_one()
        assert used is not None


def test_a_read_token_only_reads(client: TestClient) -> None:
    project = create_project(client)
    secret = _token(client, scope="read")["token"]
    c = _anonymous(client)
    headers = _bearer(c, secret)
    assert c.get(f"{API}/projects/{project['id']}", headers=headers).status_code == 200
    for res in (
        c.post(f"{API}/projects", json={"document": starter()}, headers=headers),
        c.put(f"{API}/projects/{project['id']}", json={"name": "x"}, headers=headers),
        c.post(f"{API}/projects/{project['id']}/runs", json={}, headers=headers),
    ):
        assert res.status_code == 403, res.text
    roles = {w["role"] for w in c.get(f"{API}/workspaces", headers=headers).json()}
    assert roles == {"viewer"}


def test_a_workspace_token_sees_only_its_workspace(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    in_personal = create_project(client)
    secret = _token(client, workspaceId=team["id"])["token"]
    c = _anonymous(client)
    headers = _bearer(c, secret)
    assert c.get(f"{API}/projects/{in_personal['id']}", headers=headers).status_code == 404
    res = c.post(f"{API}/projects", json={"document": starter()}, headers=headers)
    assert res.status_code == 201  # into the token's workspace, the default for it
    listed = c.get(f"{API}/projects", headers={**headers, "X-ArchSim-Workspace": team["id"]})
    assert [p["id"] for p in listed.json()] == [res.json()["id"]]
    assert [w["id"] for w in c.get(f"{API}/workspaces", headers=headers).json()] == [team["id"]]


def test_a_token_for_a_workspace_you_are_not_in_is_refused(
    client: TestClient, fake: FakeProviders, db_url: str
) -> None:
    with oauth_client(db_url, fake) as bob:
        sign_in(bob, fake, "bob")
        bobs = bob.get(f"{API}/auth/me").json()["workspaces"][0]["id"]
    res = client.post(f"{API}/tokens", json={"name": "x", "workspaceId": bobs})
    assert res.status_code == 404


def test_revoked_expired_and_mistyped_tokens_are_refused(client: TestClient) -> None:
    revoked = _token(client)
    expired = _token(client)
    assert client.delete(f"{API}/tokens/{revoked['id']}").status_code == 204
    with database(client).session() as s:
        s.execute(
            update(ApiToken)
            .where(ApiToken.id == expired["id"])
            .values(expires_at=utcnow() - timedelta(seconds=1))
        )
        s.commit()
    assert client.get(f"{API}/tokens").json() == []
    c = _anonymous(client)
    for value in (
        f"Bearer {revoked['token']}",
        f"Bearer {expired['token']}",
        "Bearer asim_pat_not-a-real-token",
        "Bearer something-else",
        f"Basic {revoked['token']}",
    ):
        res = c.get(f"{API}/projects", headers={"Authorization": value})
        assert res.status_code == 401, value
        assert res.headers["www-authenticate"] == "Bearer"


def test_tokens_cannot_manage_access(client: TestClient) -> None:
    secret = _token(client, scope="write")["token"]
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    c = _anonymous(client)
    headers = _bearer(c, secret)
    for res in (
        c.post(f"{API}/tokens", json={"name": "more"}, headers=headers),
        c.get(f"{API}/tokens", headers=headers),
        c.post(f"{API}/workspaces", json={"name": "x"}, headers=headers),
        c.post(f"{API}/workspaces/{team['id']}/invitations", json={}, headers=headers),
        c.delete(f"{API}/workspaces/{team['id']}", headers=headers),
    ):
        assert res.status_code == 403, res.text


def test_someone_elses_token_cannot_be_revoked(
    client: TestClient, fake: FakeProviders, db_url: str
) -> None:
    mine = _token(client)
    with oauth_client(db_url, fake) as bob:
        sign_in(bob, fake, "bob")
        assert bob.delete(f"{API}/tokens/{mine['id']}").status_code == 404
    assert len(client.get(f"{API}/tokens").json()) == 1


# -- CSRF --------------------------------------------------------------------------------------
def test_cookie_changes_need_the_csrf_header(client: TestClient) -> None:
    project = create_project(client)  # the helper's sign-in set the header, like the web app
    csrf = client.headers.pop("X-ArchSim-CSRF")
    assert csrf == client.get(f"{API}/auth/me").json()["csrfToken"]
    for res in (
        client.post(f"{API}/projects", json={"document": starter()}),
        client.put(f"{API}/projects/{project['id']}", json={"name": "x"}),
        client.delete(f"{API}/projects/{project['id']}"),
        client.post(f"{API}/projects/{project['id']}/runs", json={}),
        client.post(f"{API}/tokens", json={"name": "x"}),
        client.post(f"{API}/auth/logout"),
        client.post(
            f"{API}/projects", json={"document": starter()}, headers={"X-ArchSim-CSRF": "guess"}
        ),
    ):
        assert res.status_code == 403, res.text
        assert "X-ArchSim-CSRF" in res.json()["detail"]["message"]
    assert client.get(f"{API}/projects").status_code == 200  # reading needs none
    assert client.get(f"{API}/auth/me").status_code == 200  # the logout above did nothing
    res = client.post(
        f"{API}/projects", json={"document": starter()}, headers={"X-ArchSim-CSRF": csrf}
    )
    assert res.status_code == 201


def test_the_csrf_cookie_is_readable_by_the_page_but_not_sent_cross_site(
    db_url: str, fake: FakeProviders
) -> None:
    with oauth_client(db_url, fake) as c:
        fake.signing_in = "ada"
        start = c.get(f"{API}/auth/github/login", follow_redirects=False)
        back = fake.authorize(start.headers["location"])
        done = c.get(back.removeprefix("https://archsim.test"), follow_redirects=False)
        cookie = next(
            x for x in done.headers.get_list("set-cookie") if x.startswith("__Host-archsim_csrf=")
        )
        assert "HttpOnly" not in cookie
        for flag in ("Secure", "Path=/", "SameSite=strict"):
            assert flag in cookie
        out = c.post(
            f"{API}/auth/logout", headers={"X-ArchSim-CSRF": c.cookies["__Host-archsim_csrf"]}
        )
        assert out.status_code == 204
        assert any(
            x.startswith('__Host-archsim_csrf=""') for x in out.headers.get_list("set-cookie")
        )


def test_a_csrf_token_belongs_to_its_session(
    client: TestClient, fake: FakeProviders, db_url: str
) -> None:
    with oauth_client(db_url, fake) as bob:
        sign_in(bob, fake, "bob")
        bobs = bob.headers["X-ArchSim-CSRF"]
    assert bobs != client.headers["X-ArchSim-CSRF"]
    res = client.post(
        f"{API}/projects", json={"document": starter()}, headers={"X-ArchSim-CSRF": bobs}
    )
    assert res.status_code == 403


def test_without_sign_in_nothing_needs_a_csrf_header_or_token(db_url: str) -> None:
    with make_client(db_url) as c:
        assert c.post(f"{API}/projects", json={"document": starter()}).status_code == 201
        created = c.post(f"{API}/tokens", json={"name": "local"})
        assert created.status_code == 201  # possible, though only a signed-in server checks it
