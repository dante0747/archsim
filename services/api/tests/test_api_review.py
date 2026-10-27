"""Reviewing together: comments on parts (threads, resolve, detached), sign-offs that go stale,
and the history every change writes in its own transaction. Roles: viewers read, comment and
sign off; authors edit and delete their own comments; owners may delete any; read-only tokens
only read; people outside the workspace get 404."""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from api_helpers import create_project, make_client, starter, wait_for
from archsim_api.db import AuditEvent, Comment, ReviewSignoff, SimulationRun
from identity_helpers import acting_as, add_user, add_workspace, database

API = "/api/v1"


@pytest.fixture
def client(db_url: str) -> Iterator[TestClient]:
    with make_client(db_url, embedded_worker=False) as c:
        yield c


def _finished_run(client: TestClient, project_id: str, digest: str = "r1") -> dict[str, Any]:
    """A run marked succeeded without simulating it (no worker in these tests)."""
    run = client.post(f"{API}/projects/{project_id}/runs", json={}).json()
    with database(client).session() as s:
        s.execute(
            update(SimulationRun)
            .where(SimulationRun.id == run["id"])
            .values(status="succeeded", input_digest="in-" + digest, result_digest=digest)
        )
        s.commit()
    return dict(client.get(f"{API}/runs/{run['id']}").json())


def _events(client: TestClient, **where: Any) -> list[AuditEvent]:
    with database(client).session() as s:
        stmt = select(AuditEvent).order_by(AuditEvent.id)
        for key, value in where.items():
            stmt = stmt.where(getattr(AuditEvent, key) == value)
        return list(s.execute(stmt).scalars())


def _actions(client: TestClient, **where: Any) -> list[str]:
    return [e.action for e in _events(client, **where)]


def _comment(client: TestClient, project_id: str, **body: Any) -> dict[str, Any]:
    res = client.post(f"{API}/projects/{project_id}/comments", json={"body": "Hm", **body})
    assert res.status_code == 201, res.text
    return dict(res.json())


# -- comments ------------------------------------------------------------------------------------
def test_threads_on_parts_reply_and_resolve(client: TestClient) -> None:
    p = create_project(client)
    root = _comment(client, p["id"], anchorKind="node", anchorId="cache", body="  Why 2 GB?  ")
    assert (root["anchorKind"], root["anchorId"], root["parentId"]) == ("node", "cache", None)
    assert root["body"] == "Why 2 GB?" and root["author"]["id"] == "local"
    assert root["detached"] is False and root["canEdit"] and root["canDelete"]
    reply = _comment(client, p["id"], parentId=root["id"], anchorKind="project", body="Hit ratio")
    # a reply joins its thread's part, whatever it says
    assert (reply["parentId"], reply["anchorKind"], reply["anchorId"]) == (
        root["id"],
        "node",
        "cache",
    )
    nested = _comment(client, p["id"], parentId=reply["id"], body="Agreed")
    assert nested["parentId"] == root["id"]  # one level of threads
    _comment(client, p["id"], anchorKind="edge", anchorId="api-db", body="Pool size?")
    _comment(client, p["id"], body="Overall looks fine")

    on_cache = client.get(
        f"{API}/projects/{p['id']}/comments", params={"anchorKind": "node", "anchorId": "cache"}
    ).json()
    assert [c["id"] for c in on_cache] == [root["id"], reply["id"], nested["id"]]
    assert len(client.get(f"{API}/projects/{p['id']}/comments").json()) == 5

    # resolving from a reply resolves the thread
    res = client.post(f"{API}/comments/{reply['id']}/resolve", json={"resolved": True})
    assert res.status_code == 200
    assert res.json()["id"] == root["id"] and res.json()["resolvedAt"]
    assert res.json()["resolvedBy"]["id"] == "local"
    res = client.post(f"{API}/comments/{root['id']}/resolve", json={"resolved": False})
    assert res.json()["resolvedAt"] is None and res.json()["resolvedBy"] is None


def test_comment_text_and_anchors_are_checked(client: TestClient) -> None:
    p = create_project(client)
    other = create_project(client)
    other_run = _finished_run(client, other["id"])
    url = f"{API}/projects/{p['id']}/comments"
    assert client.post(url, json={"body": ""}).status_code == 422
    assert client.post(url, json={"body": "   "}).status_code == 422
    assert client.post(url, json={"body": "x" * 4001}).status_code == 422
    assert client.post(url, json={"body": "x" * 4000}).status_code == 201
    assert client.post(url, json={"body": "x", "anchorKind": "node"}).status_code == 422
    assert client.post(url, json={"body": "x", "anchorKind": "run"}).status_code == 422
    assert client.post(url, json={"body": "x", "anchorKind": "wall"}).status_code == 422
    # runs and threads of another project are not this one's
    res = client.post(url, json={"body": "x", "anchorKind": "run", "anchorId": other_run["id"]})
    assert res.status_code == 404
    next_to_other_run = {"anchorKind": "node", "anchorId": "api", "runId": other_run["id"]}
    assert client.post(url, json={"body": "x", **next_to_other_run}).status_code == 404
    elsewhere = _comment(client, other["id"])
    assert client.post(url, json={"body": "x", "parentId": elsewhere["id"]}).status_code == 404
    # editing keeps the checks
    mine = _comment(client, p["id"])
    assert client.patch(f"{API}/comments/{mine['id']}", json={"body": " "}).status_code == 422
    edited = client.patch(f"{API}/comments/{mine['id']}", json={"body": "Better"}).json()
    assert edited["body"] == "Better" and edited["editedAt"]


def test_comments_on_deleted_parts_and_runs_are_detached_not_lost(client: TestClient) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"])
    on_cache = _comment(client, p["id"], anchorKind="node", anchorId="cache")
    on_link = _comment(client, p["id"], anchorKind="edge", anchorId="api-cache")
    on_run = _comment(client, p["id"], anchorKind="run", anchorId=run["id"])
    assert on_run["runId"] == run["id"] and on_run["anchorId"] == run["id"]
    on_new = _comment(client, p["id"], anchorKind="node", anchorId="not-saved-yet")
    assert on_new["detached"] is True  # not in the saved design (yet)

    doc = starter()
    arch = doc["architecture"]
    arch["nodes"] = [n for n in arch["nodes"] if n["id"] != "cache"]
    arch["edges"] = [e for e in arch["edges"] if "cache" not in e["id"]]
    assert client.put(f"{API}/projects/{p['id']}", json={"document": doc}).status_code == 200
    assert client.delete(f"{API}/runs/{run['id']}").status_code == 204

    listed = {c["id"]: c for c in client.get(f"{API}/projects/{p['id']}/comments").json()}
    assert listed[on_cache["id"]]["detached"] is True
    assert listed[on_link["id"]]["detached"] is True
    assert listed[on_run["id"]]["detached"] is True and listed[on_run["id"]]["runId"] is None
    assert len(listed) == 4  # nothing was deleted with the parts


def test_project_delete_takes_its_comments_and_signoffs(client: TestClient) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"])
    _comment(client, p["id"])
    client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"})
    assert client.delete(f"{API}/projects/{p['id']}").status_code == 204
    with database(client).session() as s:
        assert s.execute(select(Comment)).first() is None
        assert s.execute(select(ReviewSignoff)).first() is None
    # its history stays, in the workspace's
    page = client.get(f"{API}/workspaces/default/activity").json()
    assert page["items"][0]["action"] == "project.delete"
    assert page["items"][0]["meta"]["name"] == p["name"]


# -- roles ---------------------------------------------------------------------------------------
def test_viewers_comment_resolve_and_sign_off_but_only_authors_edit(client: TestClient) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"])
    owners = _comment(client, p["id"], anchorKind="node", anchorId="api")
    add_user(client, "vera", default="viewer")
    add_user(client, "ed", default="editor")
    with acting_as(client, "vera"):
        listed = client.get(f"{API}/projects/{p['id']}/comments").json()
        assert listed[0]["canEdit"] is False and listed[0]["canDelete"] is False
        mine = _comment(client, p["id"], anchorKind="node", anchorId="api", body="From vera")
        assert mine["author"]["name"] == "Vera"
        assert client.post(f"{API}/comments/{owners['id']}/resolve").status_code == 200
        res = client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"})
        assert res.status_code == 201 and res.json()["reviewer"]["id"] == "vera"
        assert client.patch(f"{API}/comments/{mine['id']}", json={"body": "ok"}).status_code == 200
        for res in (
            client.patch(f"{API}/comments/{owners['id']}", json={"body": "mine now"}),
            client.delete(f"{API}/comments/{owners['id']}"),
            client.put(f"{API}/projects/{p['id']}", json={"name": "Viewer edit"}),
        ):
            assert res.status_code == 403, res.text
    with acting_as(client, "ed"):
        # editors change the design, not other people's words
        assert client.put(f"{API}/projects/{p['id']}", json={"name": "Ed"}).status_code == 200
        assert client.patch(f"{API}/comments/{mine['id']}", json={"body": "x"}).status_code == 403
        assert client.delete(f"{API}/comments/{mine['id']}").status_code == 403
    # an owner may delete anyone's comment (moderation), not edit it
    assert client.patch(f"{API}/comments/{mine['id']}", json={"body": "x"}).status_code == 403
    assert client.delete(f"{API}/comments/{mine['id']}").status_code == 204
    with acting_as(client, "vera"):
        assert client.delete(f"{API}/comments/{owners['id']}").status_code == 403


def test_a_read_only_token_reads_but_cannot_comment_or_sign_off(client: TestClient) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"])
    c = _comment(client, p["id"])
    with acting_as(client, "local", kind="token", read_only=True):
        assert client.get(f"{API}/projects/{p['id']}/comments").status_code == 200
        assert client.get(f"{API}/projects/{p['id']}/signoffs").status_code == 200
        assert client.get(f"{API}/projects/{p['id']}/activity").status_code == 200
        listed = client.get(f"{API}/projects/{p['id']}/comments").json()
        assert listed[0]["canEdit"] is False and listed[0]["canDelete"] is False
        for res in (
            client.post(f"{API}/projects/{p['id']}/comments", json={"body": "x"}),
            client.patch(f"{API}/comments/{c['id']}", json={"body": "x"}),
            client.delete(f"{API}/comments/{c['id']}"),
            client.post(f"{API}/comments/{c['id']}/resolve"),
            client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"}),
        ):
            assert res.status_code == 403, res.text
    with acting_as(client, "local", kind="token"):
        assert client.post(f"{API}/comments/{c['id']}/resolve").status_code == 200
    assert _events(client, action="comment.resolve")[0].actor_kind == "token"


def test_people_outside_the_workspace_get_404(client: TestClient) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"])
    c = _comment(client, p["id"])
    s = client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"}).json()
    add_workspace(client, "elsewhere")
    add_user(client, "eve", elsewhere="owner")
    with acting_as(client, "eve"):
        for res in (
            client.get(f"{API}/projects/{p['id']}/comments"),
            client.post(f"{API}/projects/{p['id']}/comments", json={"body": "x"}),
            client.patch(f"{API}/comments/{c['id']}", json={"body": "x"}),
            client.delete(f"{API}/comments/{c['id']}"),
            client.post(f"{API}/comments/{c['id']}/resolve"),
            client.get(f"{API}/projects/{p['id']}/signoffs"),
            client.get(f"{API}/runs/{run['id']}/signoffs"),
            client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"}),
            client.delete(f"{API}/signoffs/{s['id']}"),
            client.get(f"{API}/projects/{p['id']}/activity"),
            client.get(f"{API}/workspaces/default/activity"),
        ):
            assert res.status_code == 404, res.text
        # and their own workspace's history has none of it
        assert client.get(f"{API}/workspaces/elsewhere/activity").json()["items"] == []


# -- sign-offs -----------------------------------------------------------------------------------
def test_signoffs_record_the_run_and_go_stale_when_the_design_changes(
    client: TestClient,
) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"], digest="abc")
    res = client.post(
        f"{API}/runs/{run['id']}/signoffs",
        json={"decision": "changes_requested", "note": " Cache TTL too long "},
    )
    assert res.status_code == 201, res.text
    first = res.json()
    assert (first["decision"], first["note"], first["stale"]) == (
        "changes_requested",
        "Cache TTL too long",
        False,
    )
    assert (first["projectRevision"], first["inputDigest"], first["resultDigest"]) == (
        1,
        "in-abc",
        "abc",
    )
    assert first["runLabel"] == run["label"] and first["canWithdraw"] is True
    approved = client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"})
    assert approved.json()["stale"] is False

    assert client.put(f"{API}/projects/{p['id']}", json={"name": "Changed"}).status_code == 200
    listed = client.get(f"{API}/projects/{p['id']}/signoffs").json()
    assert [s["decision"] for s in listed] == ["approved", "changes_requested"]  # newest first
    assert all(s["stale"] and s["currentRevision"] == 2 for s in listed)
    assert client.get(f"{API}/runs/{run['id']}/signoffs").json() == listed

    # a sign-off made now is of revision 2: not stale
    fresh = client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"}).json()
    assert (fresh["projectRevision"], fresh["stale"]) == (2, False)

    # deleting the run keeps the sign-off, with its digests
    assert client.delete(f"{API}/runs/{run['id']}").status_code == 204
    kept = client.get(f"{API}/projects/{p['id']}/signoffs").json()
    assert len(kept) == 3 and all(s["runId"] is None and s["resultDigest"] == "abc" for s in kept)


def test_signoff_needs_a_finished_run_and_only_its_reviewer_withdraws_it(
    client: TestClient,
) -> None:
    p = create_project(client)
    queued = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
    res = client.post(f"{API}/runs/{queued['id']}/signoffs", json={"decision": "approved"})
    assert res.status_code == 409
    run = _finished_run(client, p["id"])
    url = f"{API}/runs/{run['id']}/signoffs"
    assert client.post(url, json={"decision": "maybe"}).status_code == 422
    assert client.post(url, json={"decision": "approved", "note": "x" * 2001}).status_code == 422
    mine = client.post(url, json={"decision": "approved"}).json()
    add_user(client, "vera", default="viewer")
    with acting_as(client, "vera"):
        listed = client.get(url).json()
        assert listed[0]["canWithdraw"] is False
        assert client.delete(f"{API}/signoffs/{mine['id']}").status_code == 403
    assert client.delete(f"{API}/signoffs/{mine['id']}").status_code == 204
    assert client.get(url).json() == []
    assert client.delete(f"{API}/signoffs/{mine['id']}").status_code == 404


# -- history -------------------------------------------------------------------------------------
def test_every_change_writes_its_history(client: TestClient) -> None:
    p = create_project(client)
    run = _finished_run(client, p["id"])
    queued = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
    client.post(f"{API}/runs/{queued['id']}/cancel")
    client.put(f"{API}/projects/{p['id']}", json={"name": "Renamed"})
    c = _comment(client, p["id"], anchorKind="node", anchorId="db", body="Replica lag?")
    client.patch(f"{API}/comments/{c['id']}", json={"body": "Replica lag ok?"})
    client.post(f"{API}/comments/{c['id']}/resolve")
    client.post(f"{API}/comments/{c['id']}/resolve", json={"resolved": False})
    s = client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"}).json()
    client.delete(f"{API}/signoffs/{s['id']}")
    client.delete(f"{API}/comments/{c['id']}")
    client.delete(f"{API}/runs/{queued['id']}")
    assert _actions(client, project_id=p["id"]) == [
        "project.create",
        "run.create",
        "run.create",
        "run.cancel",
        "project.update",
        "comment.create",
        "comment.edit",
        "comment.resolve",
        "comment.reopen",
        "signoff.create",
        "signoff.withdraw",
        "comment.delete",
        "run.delete",
    ]
    events = _events(client, project_id=p["id"])
    assert all(e.workspace_id == "default" for e in events)
    assert all((e.actor_user_id, e.actor_kind) == ("local", "user") for e in events)
    update_event = next(e for e in events if e.action == "project.update")
    assert update_event.meta == {"name": "Renamed", "revision": 2}
    edit = next(e for e in events if e.action == "comment.edit")
    assert edit.meta is not None and edit.meta["excerpt"] == "Replica lag ok?"
    assert edit.meta["anchorId"] == "db"

    # the API answers with the same history, newest first, people named
    page = client.get(f"{API}/projects/{p['id']}/activity").json()
    assert [i["action"] for i in page["items"]] == list(
        reversed(_actions(client, project_id=p["id"]))
    )
    assert page["items"][0]["actor"] == {"id": "local", "name": "Local user", "avatarUrl": None}
    assert page["nextBefore"] is None


def test_a_refused_change_writes_no_history(client: TestClient) -> None:
    p = create_project(client)
    before = _actions(client)
    res = client.put(f"{API}/projects/{p['id']}", json={"name": "Late", "expectedRevision": 7})
    assert res.status_code == 409
    add_user(client, "vera", default="viewer")
    with acting_as(client, "vera"):
        assert client.delete(f"{API}/projects/{p['id']}").status_code == 403
        assert client.post(f"{API}/projects/{p['id']}/runs", json={}).status_code == 403
    res = client.post(f"{API}/projects/{p['id']}/comments", json={"body": ""})
    assert res.status_code == 422
    assert _actions(client) == before


def test_membership_changes_are_recorded(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Platform"}).json()
    wid = team["id"]
    add_user(client, "ann")
    with acting_as(client, "local"):
        inv = client.post(f"{API}/workspaces/{wid}/invitations", json={"role": "editor"}).json()
        gone = client.post(f"{API}/workspaces/{wid}/invitations", json={"role": "viewer"}).json()
        client.delete(f"{API}/workspaces/{wid}/invitations/{gone['id']}")
    with acting_as(client, "ann"):
        assert (
            client.post(f"{API}/invitations/accept", json={"token": inv["token"]}).status_code
            == 200
        )
    client.patch(f"{API}/workspaces/{wid}/members/ann", json={"role": "viewer"})
    client.patch(f"{API}/workspaces/{wid}", json={"name": "Platform team"})
    client.delete(f"{API}/workspaces/{wid}/members/ann")
    events = _events(client, workspace_id=wid)
    assert [(e.action, e.target_id, e.actor_user_id) for e in events] == [
        ("workspace.create", wid, "local"),
        ("invitation.create", inv["id"], "local"),
        ("invitation.create", gone["id"], "local"),
        ("invitation.revoke", gone["id"], "local"),
        ("member.join", "ann", "ann"),
        ("member.role", "ann", "local"),
        ("workspace.rename", wid, "local"),
        ("member.remove", "ann", "local"),
    ]
    role = next(e for e in events if e.action == "member.role")
    assert role.meta == {"from": "editor", "to": "viewer"}
    assert all(e.project_id is None for e in events)


def test_activity_pages_and_addresses_only_for_owners(client: TestClient) -> None:
    p = create_project(client)
    for i in range(6):
        _comment(client, p["id"], body=f"Note {i}")
    seen: list[int] = []
    before: int | None = None
    while True:
        params: dict[str, Any] = {"limit": 3}
        if before is not None:
            params["before"] = before
        page = client.get(f"{API}/projects/{p['id']}/activity", params=params).json()
        seen += [i["id"] for i in page["items"]]
        before = page["nextBefore"]
        if before is None:
            break
    assert len(seen) == 7 and seen == sorted(seen, reverse=True)
    assert client.get(f"{API}/projects/{p['id']}/activity", params={"limit": 0}).status_code == 422
    assert (
        client.get(f"{API}/projects/{p['id']}/activity", params={"limit": 201}).status_code == 422
    )

    with database(client).session() as s:
        s.execute(update(AuditEvent).values(ip="203.0.113.9"))
        s.commit()
    items = client.get(f"{API}/workspaces/default/activity").json()["items"]
    assert {i["ip"] for i in items} == {"203.0.113.9"}
    add_user(client, "ed", default="editor")
    with acting_as(client, "ed"):
        items = client.get(f"{API}/workspaces/default/activity").json()["items"]
        assert {i["ip"] for i in items} == {None}


def test_comments_and_history_stay_in_their_workspace(client: TestClient) -> None:
    team = client.post(f"{API}/workspaces", json={"name": "Team"}).json()
    mine = create_project(client)
    theirs = client.post(
        f"{API}/projects", params={"workspace": team["id"]}, json={"document": starter()}
    ).json()
    _comment(client, theirs["id"], body="In the team")
    _comment(client, mine["id"], body="In default")
    with database(client).session() as s:
        rows = {c.body: c.workspace_id for c in s.execute(select(Comment)).scalars()}
    assert rows == {"In the team": team["id"], "In default": "default"}
    team_items = client.get(f"{API}/workspaces/{team['id']}/activity").json()["items"]
    assert {i["projectId"] for i in team_items} == {theirs["id"], None}
    default_items = client.get(f"{API}/workspaces/default/activity").json()["items"]
    assert theirs["id"] not in {i["projectId"] for i in default_items}
    add_user(client, "tim", **{team["id"]: "viewer"})
    with acting_as(client, "tim"):
        assert client.get(f"{API}/projects/{theirs['id']}/comments").status_code == 200
        assert client.get(f"{API}/projects/{mine['id']}/comments").status_code == 404


def test_signing_off_a_simulated_run(db_url: str) -> None:
    """The whole path with a real run: simulate, comment on it, approve, change, stale."""
    with make_client(db_url) as client:
        p = create_project(client)
        run = client.post(f"{API}/projects/{p['id']}/runs", json={}).json()
        done = wait_for(client, run["id"])
        assert done["status"] == "succeeded"
        c = _comment(client, p["id"], anchorKind="run", anchorId=run["id"], body="Looks right")
        assert c["detached"] is False
        s = client.post(f"{API}/runs/{run['id']}/signoffs", json={"decision": "approved"}).json()
        assert s["resultDigest"] == done["resultDigest"] and s["inputDigest"] == done["inputDigest"]
        client.put(f"{API}/projects/{p['id']}", json={"document": starter(duration=6)})
        assert client.get(f"{API}/runs/{run['id']}/signoffs").json()[0]["stale"] is True
