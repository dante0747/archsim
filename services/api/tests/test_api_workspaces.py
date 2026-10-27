"""Workspaces and roles: what each role may do, 404 for anyone outside a workspace, template
copies that stay with whoever made them, invitations, and the queue's per-workspace caps."""

from __future__ import annotations

from collections.abc import Iterator
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import update

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.db import (
    Database,
    Identity,
    Invitation,
    Project,
    SimulationRun,
    Workspace,
    utcnow,
)
from archsim_api.jobs import queue
from archsim_api.migrations import upgrade_to_head
from identity_helpers import acting_as, add_user, add_workspace, database, set_role

API = "/api/v1"


@pytest.fixture
def client(db_url: str) -> Iterator[TestClient]:
    with make_client(db_url, embedded_worker=False) as c:
        yield c


# -- without sign-in ----------------------------------------------------------------------------
def test_without_sign_in_everyone_is_the_local_owner_of_default(client: TestClient) -> None:
    me = client.get(f"{API}/auth/me").json()
    assert me["mode"] == "none" and me["kind"] == "local"
    assert me["user"]["id"] == "local"
    assert [(w["id"], w["role"], w["kind"]) for w in me["workspaces"]] == [
        ("default", "owner", "team")
    ]
    assert client.get(f"{API}/auth/config").json() == {"mode": "none", "providers": []}
    p = create_project(client)
    with database(client).session() as s:
        row = s.get(Project, p["id"])
        assert row is not None and (row.workspace_id, row.created_by) == ("default", "local")


def test_projects_are_listed_and_created_per_workspace(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Platform"}).json()
    assert (team["kind"], team["role"]) == ("team", "owner")
    in_default = create_project(client)
    in_team = client.post(
        f"{API}/projects", params={"workspace": team["id"]}, json={"document": starter()}
    ).json()
    assert [p["id"] for p in client.get(f"{API}/projects").json()] == [in_default["id"]]
    listed = client.get(f"{API}/projects", headers={"X-ArchSim-Workspace": team["id"]}).json()
    assert [p["id"] for p in listed] == [in_team["id"]]
    imported = client.post(
        f"{API}/projects/import", params={"workspace": team["id"]}, json=starter()
    )
    assert imported.status_code == 201
    assert client.get(f"{API}/projects", params={"workspace": "nope"}).status_code == 404


# -- roles ---------------------------------------------------------------------------------------
def test_a_viewer_reads_but_cannot_change_anything(client: TestClient) -> None:
    project = create_project(client)
    run = client.post(f"{API}/projects/{project['id']}/runs", json={}).json()
    add_user(client, "vera", default="viewer")
    with acting_as(client, "vera"):
        assert client.get(f"{API}/projects").status_code == 200
        assert client.get(f"{API}/projects/{project['id']}").status_code == 200
        assert client.get(f"{API}/projects/{project['id']}/runs").status_code == 200
        assert client.get(f"{API}/runs/{run['id']}").status_code == 200
        assert client.get(f"{API}/projects/{project['id']}/export").status_code == 200
        refused = [
            client.put(f"{API}/projects/{project['id']}", json={"name": "Mine now"}),
            client.delete(f"{API}/projects/{project['id']}"),
            client.post(f"{API}/projects/{project['id']}/duplicate"),
            client.post(f"{API}/projects/{project['id']}/runs", json={}),
            client.post(f"{API}/projects/{project['id']}/runs/batch", json={"scenarioIds": [None]}),
            client.post(f"{API}/runs/{run['id']}/cancel"),
            client.post(f"{API}/runs/{run['id']}/rerun"),
            client.delete(f"{API}/runs/{run['id']}"),
            client.post(f"{API}/projects", json={"exampleId": "cache-aside-starter"}),
            client.post(f"{API}/projects/import", json=starter()),
        ]
        for res in refused:
            assert res.status_code == 403, res.text
            assert "viewer" in res.json()["detail"]["message"]
    assert client.get(f"{API}/projects/{project['id']}").json()["name"] != "Mine now"


def test_an_editor_changes_projects_and_runs_but_not_the_workspace(client: TestClient) -> None:
    project = create_project(client)
    add_user(client, "ed", default="editor")
    with acting_as(client, "ed"):
        res = client.put(f"{API}/projects/{project['id']}", json={"name": "Edited"})
        assert res.status_code == 200
        assert client.post(f"{API}/projects/{project['id']}/runs", json={}).status_code == 202
        assert client.patch(f"{API}/workspaces/default", json={"name": "Mine"}).status_code == 403
        assert client.get(f"{API}/workspaces/default/invitations").status_code == 403
        res = client.patch(f"{API}/workspaces/default/members/local", json={"role": "viewer"})
        assert res.status_code == 403


def test_people_outside_a_workspace_get_404_for_everything_in_it(client: TestClient) -> None:
    project = create_project(client)
    run = client.post(f"{API}/projects/{project['id']}/runs", json={}).json()
    add_workspace(client, "elsewhere")
    add_user(client, "eve", elsewhere="owner")
    with acting_as(client, "eve"):
        assert client.get(f"{API}/projects").json() == []  # eve's own (empty) workspace
        for res in (
            client.get(f"{API}/projects/{project['id']}"),
            client.put(f"{API}/projects/{project['id']}", json={"name": "x"}),
            client.delete(f"{API}/projects/{project['id']}"),
            client.get(f"{API}/projects/{project['id']}/export"),
            client.get(f"{API}/projects/{project['id']}/runs"),
            client.post(f"{API}/projects/{project['id']}/runs", json={}),
            client.get(f"{API}/runs/{run['id']}"),
            client.get(f"{API}/runs/{run['id']}/input"),
            client.get(f"{API}/runs/{run['id']}/report"),
            client.post(f"{API}/runs/{run['id']}/cancel"),
            client.delete(f"{API}/runs/{run['id']}"),
            client.get(f"{API}/compare", params={"a": run["id"], "b": run["id"]}),
            client.get(f"{API}/projects", params={"workspace": "default"}),
            client.get(f"{API}/workspaces/default"),
            client.get(f"{API}/workspaces/default/members"),
        ):
            assert res.status_code == 404, res.text
        # the same answer as for something that does not exist
        assert client.get(f"{API}/projects/{project['id']}").json() == {
            "detail": {"message": f"Project '{project['id']}' not found", "issues": []}
        }
        assert [w["id"] for w in client.get(f"{API}/workspaces").json()] == ["elsewhere"]
    assert client.get(f"{API}/projects/{project['id']}").status_code == 200


def test_a_read_only_token_principal_is_a_viewer(client: TestClient) -> None:
    project = create_project(client)
    with acting_as(client, "local", kind="token", read_only=True):
        assert client.get(f"{API}/projects/{project['id']}").status_code == 200
        res = client.put(f"{API}/projects/{project['id']}", json={"name": "x"})
        assert res.status_code == 403
    add_workspace(client, "other")
    set_role(client, "other", "local", "owner")
    other = client.post(
        f"{API}/projects", params={"workspace": "other"}, json={"document": starter()}
    ).json()
    with acting_as(client, "local", kind="token", token_workspace_id="default"):
        assert client.get(f"{API}/projects/{other['id']}").status_code == 404
        assert [w["id"] for w in client.get(f"{API}/workspaces").json()] == ["default"]


# -- template copies (the reuseUnedited leak) ----------------------------------------------------
def _open_template(client: TestClient, workspace: str | None = None) -> tuple[int, str]:
    res = client.post(
        f"{API}/projects",
        params={"workspace": workspace} if workspace else None,
        json={"exampleId": "cache-aside-starter", "reuseUnedited": True},
    )
    assert res.status_code in (200, 201), res.text
    return res.status_code, res.json()["id"]


def test_opening_a_template_never_hands_back_someone_elses_copy(client: TestClient) -> None:
    status, mine = _open_template(client)
    assert status == 201
    assert _open_template(client) == (200, mine)  # my unedited copy comes back
    add_user(client, "ann", default="editor")
    with acting_as(client, "ann"):
        status, hers = _open_template(client)
        assert status == 201 and hers != mine  # not mine, though it is in her workspace too
        assert _open_template(client) == (200, hers)
    add_workspace(client, "team")
    set_role(client, "team", "local", "owner")
    status, in_team = _open_template(client, "team")
    assert status == 201 and in_team not in (mine, hers)
    assert _open_template(client) == (200, mine)


# -- members and invitations ---------------------------------------------------------------------
def test_an_invitation_makes_someone_a_member_once(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    created = client.post(
        f"{API}/workspaces/{team['id']}/invitations", json={"role": "editor"}
    ).json()
    token = created["token"]
    assert token.startswith("asim_inv_")
    pending = client.get(f"{API}/workspaces/{team['id']}/invitations").json()
    assert [i["id"] for i in pending] == [created["id"]] and "token" not in pending[0]
    with database(client).session() as s:
        inv = s.get(Invitation, created["id"])
        assert inv is not None and inv.token_hash != token and token not in inv.token_hash

    add_user(client, "joe")
    with acting_as(client, "joe"):
        preview = client.post(f"{API}/invitations/preview", json={"token": token}).json()
        assert (preview["workspaceName"], preview["role"]) == ("Team", "editor")
        joined = client.post(f"{API}/invitations/accept", json={"token": token}).json()
        assert (joined["id"], joined["role"]) == (team["id"], "editor")
        again = client.post(f"{API}/invitations/accept", json={"token": token})
        assert again.status_code == 404  # used up
    members = client.get(f"{API}/workspaces/{team['id']}/members").json()
    assert {(m["userId"], m["role"]) for m in members} == {("local", "owner"), ("joe", "editor")}


def test_invitations_for_an_email_need_that_verified_email(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    token = client.post(
        f"{API}/workspaces/{team['id']}/invitations",
        json={"role": "viewer", "email": "Kim@Example.com"},
    ).json()["token"]
    add_user(client, "kim")
    with acting_as(client, "kim"):
        assert client.post(f"{API}/invitations/accept", json={"token": token}).status_code == 403
    with database(client).session() as s:
        s.add(Identity(user_id="kim", provider="github", subject="1", email="kim@example.com"))
        s.commit()
    with acting_as(client, "kim"):  # not verified yet
        assert client.post(f"{API}/invitations/accept", json={"token": token}).status_code == 403
    with database(client).session() as s:
        s.execute(update(Identity).values(email_verified=True))
        s.commit()
    with acting_as(client, "kim"):
        assert client.post(f"{API}/invitations/accept", json={"token": token}).status_code == 200


def test_expired_and_revoked_invitations_do_nothing(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    url = f"{API}/workspaces/{team['id']}/invitations"
    expired = client.post(url, json={}).json()
    revoked = client.post(url, json={}).json()
    with database(client).session() as s:
        s.execute(
            update(Invitation)
            .where(Invitation.id == expired["id"])
            .values(expires_at=utcnow() - timedelta(seconds=1))
        )
        s.commit()
    assert client.delete(f"{url}/{revoked['id']}").status_code == 204
    add_user(client, "late")
    with acting_as(client, "late"):
        for inv in (expired, revoked):
            res = client.post(f"{API}/invitations/accept", json={"token": inv["token"]})
            assert res.status_code == 404
    assert client.get(url).json() == []


def test_personal_workspaces_cannot_be_shared(client: TestClient) -> None:
    add_workspace(client, "mine", kind="personal")
    set_role(client, "mine", "local", "owner")
    res = client.post(f"{API}/workspaces/mine/invitations", json={})
    assert res.status_code == 409
    assert client.delete(f"{API}/workspaces/mine").status_code == 409
    assert client.delete(f"{API}/workspaces/default").status_code == 409


def test_a_workspace_keeps_an_owner(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    members = f"{API}/workspaces/{team['id']}/members"
    assert client.patch(f"{members}/local", json={"role": "editor"}).status_code == 409
    assert client.delete(f"{members}/local").status_code == 409
    add_user(client, "co")
    set_role(client, team["id"], "co", "editor")
    assert client.patch(f"{members}/co", json={"role": "owner"}).status_code == 200
    assert client.delete(f"{members}/local").status_code == 204  # leaving: co is an owner
    with acting_as(client, "co"):
        assert client.delete(f"{API}/workspaces/{team['id']}").status_code == 204


def test_a_member_can_leave(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    add_user(client, "pat")
    set_role(client, team["id"], "pat", "viewer")
    with acting_as(client, "pat"):
        assert client.delete(f"{API}/workspaces/{team['id']}/members/local").status_code == 403
        assert client.delete(f"{API}/workspaces/{team['id']}/members/pat").status_code == 204
        assert client.get(f"{API}/workspaces/{team['id']}").status_code == 404


def test_deleting_a_workspace_deletes_its_projects_and_runs(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    p = client.post(
        f"{API}/projects", params={"workspace": team["id"]}, json={"document": starter()}
    ).json()
    run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
    assert client.delete(f"{API}/workspaces/{team['id']}").status_code == 204
    with database(client).session() as s:
        assert s.get(Project, p["id"]) is None
        assert s.get(SimulationRun, run["id"]) is None


def test_runs_still_simulate_with_a_worker(db_url: str) -> None:
    with make_client(db_url) as c:
        p = create_project(c)
        run = c.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        assert wait_for(c, run["id"])["status"] == "succeeded"
        with database(c).session() as s:
            row = s.get(SimulationRun, run["id"])
            assert row is not None and (row.workspace_id, row.created_by) == ("default", "local")


# -- the queue: runs at once per workspace -------------------------------------------------------
@pytest.fixture
def db(db_url: str) -> Iterator[Database]:
    upgrade_to_head(db_url)
    database = Database(db_url)
    yield database
    database.engine.dispose()


def _queue(db: Database, workspace: str, n: int, kind: str = "team", **ws: object) -> list[str]:
    ids = []
    with db.session() as s:
        if s.get(Workspace, workspace) is None:
            s.add(Workspace(id=workspace, name=workspace, kind=kind, **ws))
            s.flush()
            s.add(
                Project(
                    id=f"p-{workspace}",
                    workspace_id=workspace,
                    name="p",
                    document={},
                    schema_version="1",
                )
            )
            s.flush()
        for _ in range(n):
            run = queue.enqueue(
                s,
                SimulationRun(
                    project_id=f"p-{workspace}",
                    workspace_id=workspace,
                    seed=1,
                    engine_version="test",
                    input_document={"ws": workspace},
                    created_at=utcnow() + timedelta(milliseconds=len(ids)),
                ),
            )
            ids.append(run.id)
    return ids


def test_claim_keeps_each_workspace_under_its_cap(db: Database) -> None:
    capped = _queue(db, "capped", 3, max_concurrent_runs=1)
    free = _queue(db, "free", 2)
    first = queue.claim(db, "w1", 4, 30.0)
    assert {c.id for c in first} == {capped[0], *free}  # one of capped's, all of free's
    assert queue.claim(db, "w2", 4, 30.0) == []  # capped is at its cap on another worker too
    assert queue.finish(db, capped[0], "w1", "succeeded")
    assert [c.id for c in queue.claim(db, "w2", 4, 30.0)] == [capped[1]]


def test_claim_uses_the_kind_cap_for_workspaces_without_one(db: Database) -> None:
    sand = _queue(db, "sand", 3, kind="sandbox")
    taken = queue.claim(db, "w", 5, 30.0, kind_caps={"sandbox": 2})
    assert sorted(c.id for c in taken) == sorted(sand[:2])


def test_claim_is_fair_between_workspaces(db: Database) -> None:
    busy = _queue(db, "busy", 5)  # queued first
    late = _queue(db, "late", 1)
    taken = [c.id for c in queue.claim(db, "w", 2, 30.0)]
    assert sorted(taken) == sorted([busy[0], late[0]])  # not busy's two oldest
