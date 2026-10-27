"""Signing in with GitHub and Google against a stand-in provider: PKCE and state checks, the
session cookie, accounts and their workspaces, admins, signing out and expired sessions."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import timedelta
from urllib.parse import parse_qs, urlsplit

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from sqlalchemy import select, update

from api_helpers import create_project, make_client
from archsim_api.db import Identity, User, UserSession, utcnow
from fake_oauth import FakeProviders
from identity_helpers import PUBLIC_URL, database, oauth_client, oauth_settings, sign_in

API = "/api/v1"


@pytest.fixture
def fake() -> FakeProviders:
    f = FakeProviders()
    f.add("ada", "ada@example.com")
    f.add("admin", "admin@example.com")
    f.add("bob", "bob@example.com")
    return f


@pytest.fixture
def client(db_url: str, fake: FakeProviders) -> Iterator[TestClient]:
    with oauth_client(db_url, fake) as c:
        yield c


def _set_cookies(res: object) -> list[str]:
    return res.headers.get_list("set-cookie")  # type: ignore[attr-defined, no-any-return]


def test_signed_out_requests_are_refused(client: TestClient) -> None:
    assert client.get(f"{API}/auth/config").json() == {
        "mode": "oauth",
        "providers": ["github", "google"],
    }
    for res in (
        client.get(f"{API}/auth/me"),
        client.get(f"{API}/projects"),
        client.post(f"{API}/projects", json={}),
        client.post(f"{API}/validate", json={"document": {}}),
        client.get(f"{API}/workspaces"),
    ):
        assert res.status_code == 401, res.text
    # public: the landing page, templates and catalog work before signing in
    assert client.get(f"{API}/health").status_code == 200
    assert client.get(f"{API}/examples").status_code == 200
    assert client.get(f"{API}/components").status_code == 200


def test_login_redirects_to_the_provider_with_pkce_and_a_signed_state(client: TestClient) -> None:
    res = client.get(
        f"{API}/auth/github/login", params={"next": "/projects"}, follow_redirects=False
    )
    assert res.status_code == 302
    url = urlsplit(res.headers["location"])
    q = {k: v[0] for k, v in parse_qs(url.query).items()}
    assert f"{url.scheme}://{url.netloc}{url.path}" == "https://github.test/login/oauth/authorize"
    assert q["code_challenge_method"] == "S256" and len(q["code_challenge"]) == 43
    assert q["redirect_uri"] == f"{PUBLIC_URL}/api/v1/auth/github/callback"
    assert q["client_id"] == "test-client" and len(q["state"]) >= 40
    (cookie,) = _set_cookies(res)
    assert cookie.startswith("__Host-archsim_oauth=")
    for flag in ("HttpOnly", "Path=/", "SameSite=lax", "Secure", "Max-Age=600"):
        assert flag in cookie


def test_signing_in_makes_an_account_a_personal_workspace_and_a_session(
    client: TestClient, fake: FakeProviders
) -> None:
    fake.signing_in = "ada"
    start = client.get(f"{API}/auth/github/login?next=/projects/abc", follow_redirects=False)
    back = fake.authorize(start.headers["location"])
    done = client.get(back.removeprefix(PUBLIC_URL), follow_redirects=False)
    assert done.status_code == 302 and done.headers["location"] == "/projects/abc"
    cookies = _set_cookies(done)
    session_cookie = next(c for c in cookies if c.startswith("__Host-archsim_session="))
    for flag in ("HttpOnly", "Path=/", "SameSite=lax", "Secure"):
        assert flag in session_cookie
    assert any(c.startswith('__Host-archsim_oauth=""') for c in cookies)  # state cleared
    # the verifier went to the token endpoint, and the provider checked it against the challenge
    assert fake.token_requests[-1]["code_verifier"]

    me = client.get(f"{API}/auth/me").json()
    assert (me["kind"], me["user"]["name"], me["user"]["email"]) == (
        "session",
        "Ada",
        "ada@example.com",
    )
    assert [(w["kind"], w["role"], w["name"]) for w in me["workspaces"]] == [
        ("personal", "owner", "Ada's workspace")
    ]
    # projects go to the personal workspace; the default workspace is not hers
    client.headers["X-ArchSim-CSRF"] = me["csrfToken"]
    p = create_project(client)
    assert client.get(f"{API}/projects").json()[0]["id"] == p["id"]
    assert client.get(f"{API}/projects", params={"workspace": "default"}).status_code == 404
    # only a hash of the cookie is stored
    secret = session_cookie.split(";")[0].split("=", 1)[1]
    with database(client).session() as s:
        ids = list(s.execute(select(UserSession.id)).scalars())
        assert secret not in ids and len(ids) == 1


def test_signing_in_again_finds_the_same_account(client: TestClient, fake: FakeProviders) -> None:
    sign_in(client, fake, "ada")
    first = client.get(f"{API}/auth/me").json()
    client.post(f"{API}/auth/logout")
    sign_in(client, fake, "ada")
    again = client.get(f"{API}/auth/me").json()
    assert again["user"]["id"] == first["user"]["id"]
    assert len(again["workspaces"]) == 1


def test_a_verified_email_links_github_and_google(client: TestClient, fake: FakeProviders) -> None:
    sign_in(client, fake, "ada", "github")
    github = client.get(f"{API}/auth/me").json()["user"]["id"]
    client.post(f"{API}/auth/logout")
    sign_in(client, fake, "ada", "google")
    assert client.get(f"{API}/auth/me").json()["user"]["id"] == github
    with database(client).session() as s:
        providers = sorted(s.execute(select(Identity.provider)).scalars())
        assert providers == ["github", "google"]


def test_an_unverified_email_links_nothing_and_grants_nothing(
    client: TestClient, fake: FakeProviders
) -> None:
    sign_in(client, fake, "ada")
    ada = client.get(f"{API}/auth/me").json()["user"]["id"]
    client.post(f"{API}/auth/logout")
    fake.add("mallory", "ada@example.com", verified=False)
    sign_in(client, fake, "mallory", "google")
    me = client.get(f"{API}/auth/me").json()
    assert me["user"]["id"] != ada and me["user"]["email"] is None
    client.post(f"{API}/auth/logout")
    fake.add("fakeadmin", "admin@example.com", verified=False)
    sign_in(client, fake, "fakeadmin")
    assert "default" not in [w["id"] for w in client.get(f"{API}/auth/me").json()["workspaces"]]


def test_the_first_sign_in_of_an_admin_email_owns_default(
    client: TestClient, fake: FakeProviders
) -> None:
    sign_in(client, fake, "admin")
    roles = {w["id"]: w["role"] for w in client.get(f"{API}/auth/me").json()["workspaces"]}
    assert roles["default"] == "owner"
    assert client.get(f"{API}/projects", params={"workspace": "default"}).status_code == 200


def test_a_wrong_or_missing_state_signs_nobody_in(client: TestClient, fake: FakeProviders) -> None:
    start = client.get(f"{API}/auth/github/login", follow_redirects=False)
    back = urlsplit(fake.authorize(start.headers["location"]))
    q = {k: v[0] for k, v in parse_qs(back.query).items()}
    res = client.get(
        f"{API}/auth/github/callback",
        params={"code": q["code"], "state": "not-the-state"},
        follow_redirects=False,
    )
    assert res.headers["location"] == "/signin?error=state"
    client.cookies.clear()  # no state cookie at all (another browser, or it expired)
    res = client.get(f"{API}/auth/github/callback", params=q, follow_redirects=False)
    assert res.headers["location"] == "/signin?error=state"
    assert client.get(f"{API}/auth/me").status_code == 401
    assert not any(c.startswith("__Host-archsim_session=") for c in _set_cookies(res))


def test_a_tampered_state_cookie_is_refused(client: TestClient, fake: FakeProviders) -> None:
    start = client.get(f"{API}/auth/github/login", follow_redirects=False)
    back = fake.authorize(start.headers["location"])
    value = client.cookies["__Host-archsim_oauth"]
    body, mac = value.split(".")
    client.cookies.set("__Host-archsim_oauth", f"{body}x.{mac}", domain="archsim.test")
    res = client.get(back.removeprefix(PUBLIC_URL), follow_redirects=False)
    assert res.headers["location"] == "/signin?error=state"


def test_provider_refusals_end_on_the_sign_in_page(client: TestClient, fake: FakeProviders) -> None:
    res = client.get(
        f"{API}/auth/github/callback", params={"error": "access_denied"}, follow_redirects=False
    )
    assert res.headers["location"] == "/signin?error=denied"
    fake.refuse_token = True
    assert sign_in(client, fake, "ada") == "/signin?error=provider"
    assert client.get(f"{API}/auth/me").status_code == 401


@pytest.mark.parametrize(
    "next_path", ["//evil.example/x", "https://evil.example/", "/\\evil.example", "projects"]
)
def test_sign_in_never_lands_on_another_site(
    client: TestClient, fake: FakeProviders, next_path: str
) -> None:
    fake.signing_in = "ada"
    start = client.get(
        f"{API}/auth/github/login", params={"next": next_path}, follow_redirects=False
    )
    back = fake.authorize(start.headers["location"])
    done = client.get(back.removeprefix(PUBLIC_URL), follow_redirects=False)
    assert done.headers["location"] == "/"


def test_logout_ends_the_session_everywhere_at_once(
    client: TestClient, fake: FakeProviders
) -> None:
    sign_in(client, fake, "ada")
    cookie = client.cookies["__Host-archsim_session"]
    res = client.post(f"{API}/auth/logout")
    assert res.status_code == 204
    assert any(c.startswith('__Host-archsim_session=""') for c in _set_cookies(res))
    # the old cookie is dead even if a browser kept it
    client.cookies.set("__Host-archsim_session", cookie, domain="archsim.test")
    assert client.get(f"{API}/auth/me").status_code == 401


def test_logout_everywhere_ends_every_session(
    client: TestClient, fake: FakeProviders, db_url: str
) -> None:
    sign_in(client, fake, "ada")
    with oauth_client(db_url, fake) as other:
        sign_in(other, fake, "ada")
        assert other.get(f"{API}/auth/me").status_code == 200
        client.post(f"{API}/auth/logout", params={"everywhere": True})
        assert other.get(f"{API}/auth/me").status_code == 401


def test_expired_sessions_and_disabled_accounts_are_signed_out(
    client: TestClient, fake: FakeProviders
) -> None:
    sign_in(client, fake, "ada")
    with database(client).session() as s:
        s.execute(update(UserSession).values(expires_at=utcnow() - timedelta(seconds=1)))
        s.commit()
    assert client.get(f"{API}/auth/me").status_code == 401
    sign_in(client, fake, "ada")
    assert client.get(f"{API}/auth/me").status_code == 200
    with database(client).session() as s:
        s.execute(update(User).where(User.name == "Ada").values(disabled_at=utcnow()))
        s.commit()
    assert client.get(f"{API}/auth/me").status_code == 401
    assert sign_in(client, fake, "ada") == "/signin?error=disabled"


def test_sessions_record_the_client_ip_only_from_a_trusted_proxy(
    db_url: str, fake: FakeProviders
) -> None:
    with oauth_client(db_url, fake, trusted_proxies=["testclient"]) as c:
        c.headers["X-ArchSim-Client-IP"] = "203.0.113.7"
        sign_in(c, fake, "ada")
    with oauth_client(db_url, fake, trusted_proxies=["10.0.0.0/8"]) as c:
        c.headers["X-ArchSim-Client-IP"] = "203.0.113.8"
        sign_in(c, fake, "bob")
        with database(c).session() as s:
            ips = set(s.execute(select(UserSession.ip)).scalars())
    assert ips == {"203.0.113.7", "testclient"}


def test_without_sign_in_there_is_nothing_to_sign_in_to(db_url: str) -> None:
    with make_client(db_url) as c:
        assert c.get(f"{API}/auth/github/login", follow_redirects=False).status_code == 404
        assert c.post(f"{API}/auth/logout").status_code == 204


def test_oauth_mode_needs_a_secret_a_public_url_and_a_provider(db_url: str) -> None:
    for missing in (
        {"secret_key": None},
        {"secret_key": "short"},
        {"public_url": None},
        {
            "github_client_id": None,
            "google_client_id": None,
        },
    ):
        with pytest.raises(ValidationError):
            oauth_settings(db_url, **missing)
