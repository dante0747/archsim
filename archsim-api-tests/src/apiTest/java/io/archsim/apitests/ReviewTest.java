package io.archsim.apitests;

import static io.archsim.apitests.Json.items;
import static io.archsim.apitests.Json.object;
import static io.archsim.apitests.Json.strings;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reviewing together: comments on parts (threads, resolve, detached), sign-offs that go stale,
 * and the history every change writes in its own transaction. Roles: viewers read, comment and
 * sign off; authors edit and delete their own comments; owners may delete any; read-only tokens
 * only read; people outside the workspace get 404. From {@code
 * services/api/tests/test_api_review.py}.
 *
 * <p>The Python tests mark runs succeeded in the database with made-up digests; here the runs
 * are real short simulations, and sign-offs are checked against their digests. Tests with other
 * people run on the hosted server, with real people in a team workspace; the history is read
 * from {@code audit_events}, for the test's own project or workspace.
 */
@ApiTest(Profile.LOCAL)
class ReviewTest {

    // -- comments ----------------------------------------------------------------------------------
    @Test
    void threadsOnPartsReplyAndResolve(ApiClient client) {
        String p = project(client);
        JsonNode root = comment(client, p, object("anchorKind", "node", "anchorId", "cache", "body", "  Why 2 GB?  "));
        assertThat(fields(root, "anchorKind", "anchorId", "parentId"))
                .isEqualTo(object("anchorKind", "node", "anchorId", "cache", "parentId", null));
        assertThat(root.path("body").asString()).isEqualTo("Why 2 GB?");
        assertThat(root.path("author").path("id").asString()).isEqualTo("local");
        assertThat(fields(root, "detached", "canEdit", "canDelete"))
                .isEqualTo(object("detached", false, "canEdit", true, "canDelete", true));
        JsonNode reply = comment(client, p, object("parentId", id(root), "anchorKind", "project", "body", "Hit ratio"));
        // a reply joins its thread's part, whatever it says
        assertThat(fields(reply, "parentId", "anchorKind", "anchorId"))
                .isEqualTo(object("parentId", id(root), "anchorKind", "node", "anchorId", "cache"));
        JsonNode nested = comment(client, p, object("parentId", id(reply), "body", "Agreed"));
        assertThat(nested.path("parentId").asString()).isEqualTo(id(root)); // one level of threads
        comment(client, p, object("anchorKind", "edge", "anchorId", "api-db", "body", "Pool size?"));
        comment(client, p, object("body", "Overall looks fine"));

        JsonNode onCache = client.get(
                        ApiClient.query("/projects/" + p + "/comments", "anchorKind", "node", "anchorId", "cache"))
                .json();
        assertThat(strings(onCache, "id")).containsExactly(id(root), id(reply), id(nested));
        assertThat(client.get("/projects/" + p + "/comments").json().size()).isEqualTo(5);

        // resolving from a reply resolves the thread
        ApiResponse res = client.post("/comments/" + id(reply) + "/resolve", object("resolved", true));
        assertThat(res.status()).isEqualTo(200);
        assertThat(id(res.json())).isEqualTo(id(root));
        assertThat(res.json().path("resolvedAt").isString()).isTrue();
        assertThat(res.json().path("resolvedBy").path("id").asString()).isEqualTo("local");
        res = client.post("/comments/" + id(root) + "/resolve", object("resolved", false));
        assertThat(fields(res.json(), "resolvedAt", "resolvedBy"))
                .isEqualTo(object("resolvedAt", null, "resolvedBy", null));
    }

    @Test
    void commentTextAndAnchorsAreChecked(ApiClient client) {
        String p = project(client);
        String other = project(client);
        String otherRun = id(finishedRun(client, other));
        String url = "/projects/" + p + "/comments";
        assertThat(client.post(url, object("body", "")).status()).isEqualTo(422);
        assertThat(client.post(url, object("body", "   ")).status()).isEqualTo(422);
        assertThat(client.post(url, object("body", "x".repeat(4001))).status()).isEqualTo(422);
        assertThat(client.post(url, object("body", "x".repeat(4000))).status()).isEqualTo(201);
        assertThat(client.post(url, object("body", "x", "anchorKind", "node")).status())
                .isEqualTo(422);
        assertThat(client.post(url, object("body", "x", "anchorKind", "run")).status())
                .isEqualTo(422);
        assertThat(client.post(url, object("body", "x", "anchorKind", "wall")).status())
                .isEqualTo(422);
        // runs and threads of another project are not this one's
        ApiResponse res = client.post(url, object("body", "x", "anchorKind", "run", "anchorId", otherRun));
        assertThat(res.status()).isEqualTo(404);
        ObjectNode nextToOtherRun = object("body", "x", "anchorKind", "node", "anchorId", "api", "runId", otherRun);
        assertThat(client.post(url, nextToOtherRun).status()).isEqualTo(404);
        JsonNode elsewhere = comment(client, other, object());
        assertThat(client.post(url, object("body", "x", "parentId", id(elsewhere)))
                        .status())
                .isEqualTo(404);
        // editing keeps the checks
        JsonNode mine = comment(client, p, object());
        assertThat(client.patch("/comments/" + id(mine), object("body", " ")).status())
                .isEqualTo(422);
        JsonNode edited =
                client.patch("/comments/" + id(mine), object("body", "Better")).json();
        assertThat(edited.path("body").asString()).isEqualTo("Better");
        assertThat(edited.path("editedAt").isString()).isTrue();
    }

    @Test
    void commentsOnDeletedPartsAndRunsAreDetachedNotLost(ApiClient client) {
        String p = project(client);
        String run = id(finishedRun(client, p));
        JsonNode onCache = comment(client, p, object("anchorKind", "node", "anchorId", "cache"));
        JsonNode onLink = comment(client, p, object("anchorKind", "edge", "anchorId", "api-cache"));
        JsonNode onRun = comment(client, p, object("anchorKind", "run", "anchorId", run));
        assertThat(onRun.path("runId").asString()).isEqualTo(run);
        assertThat(onRun.path("anchorId").asString()).isEqualTo(run);
        JsonNode onNew = comment(client, p, object("anchorKind", "node", "anchorId", "not-saved-yet"));
        assertThat(onNew.path("detached").asBoolean()).isTrue(); // not in the saved design (yet)

        ObjectNode doc = design();
        ObjectNode arch = (ObjectNode) doc.path("architecture");
        arch.set(
                "nodes",
                Json.array(items(arch.path("nodes")).stream()
                        .filter(n -> !n.path("id").asString().equals("cache"))
                        .toArray()));
        arch.set(
                "edges",
                Json.array(items(arch.path("edges")).stream()
                        .filter(e -> !e.path("id").asString().contains("cache"))
                        .toArray()));
        assertThat(client.put("/projects/" + p, object("document", doc)).status())
                .isEqualTo(200);
        assertThat(client.delete("/runs/" + run).status()).isEqualTo(204);

        JsonNode listed = client.get("/projects/" + p + "/comments").json();
        assertThat(Json.find(listed, "id", id(onCache)).path("detached").asBoolean())
                .isTrue();
        assertThat(Json.find(listed, "id", id(onLink)).path("detached").asBoolean())
                .isTrue();
        JsonNode runComment = Json.find(listed, "id", id(onRun));
        assertThat(runComment.path("detached").asBoolean()).isTrue();
        assertThat(runComment.path("runId").isNull()).isTrue();
        assertThat(listed.size()).isEqualTo(4); // nothing was deleted with the parts
    }

    @Test
    void projectDeleteTakesItsCommentsAndSignoffs(ApiServer server) {
        ApiClient client = server.client();
        String workspace = Workspaces.enter(client);
        JsonNode project = Runs.createProject(client, design());
        String p = id(project);
        String run = id(finishedRun(client, p));
        comment(client, p, object());
        client.post("/runs/" + run + "/signoffs", object("decision", "approved"));
        assertThat(rowsOf(server, "comments", p)).isEqualTo(1L);
        assertThat(rowsOf(server, "review_signoffs", p)).isEqualTo(1L);
        assertThat(client.delete("/projects/" + p).status()).isEqualTo(204);
        assertThat(rowsOf(server, "comments", p)).isZero();
        assertThat(rowsOf(server, "review_signoffs", p)).isZero();
        // its history stays, in the workspace's
        JsonNode page = client.get("/workspaces/" + workspace + "/activity").json();
        assertThat(page.path("items").path(0).path("action").asString()).isEqualTo("project.delete");
        assertThat(page.path("items").path(0).path("meta").path("name")).isEqualTo(project.path("name"));
    }

    // -- roles -------------------------------------------------------------------------------------
    @Test
    void viewersCommentResolveAndSignOffButOnlyAuthorsEdit(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String team = Workspaces.enter(owner);
        String p = project(owner);
        String run = id(finishedRun(owner, p));
        JsonNode owners = comment(owner, p, object("anchorKind", "node", "anchorId", "api"));
        ApiClient vera = server.signedIn("vera");
        Workspaces.join(owner, team, vera, "viewer");
        ApiClient ed = server.signedIn("ed");
        Workspaces.join(owner, team, ed, "editor");

        JsonNode listed = vera.get("/projects/" + p + "/comments").json();
        assertThat(fields(listed.path(0), "canEdit", "canDelete"))
                .isEqualTo(object("canEdit", false, "canDelete", false));
        JsonNode mine = comment(vera, p, object("anchorKind", "node", "anchorId", "api", "body", "From vera"));
        assertThat(mine.path("author").path("name").asString()).isEqualTo("Vera");
        assertThat(vera.post("/comments/" + id(owners) + "/resolve").status()).isEqualTo(200);
        ApiResponse res = vera.post("/runs/" + run + "/signoffs", object("decision", "approved"));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.json().path("reviewer").path("id").asString()).isEqualTo(Workspaces.userId(vera));
        assertThat(vera.patch("/comments/" + id(mine), object("body", "ok")).status())
                .isEqualTo(200);
        for (ApiResponse refused : List.of(
                vera.patch("/comments/" + id(owners), object("body", "mine now")),
                vera.delete("/comments/" + id(owners)),
                vera.put("/projects/" + p, object("name", "Viewer edit")))) {
            assertThat(refused.status()).as(refused + ": " + refused.text()).isEqualTo(403);
        }
        // editors change the design, not other people's words
        assertThat(ed.put("/projects/" + p, object("name", "Ed")).status()).isEqualTo(200);
        assertThat(ed.patch("/comments/" + id(mine), object("body", "x")).status())
                .isEqualTo(403);
        assertThat(ed.delete("/comments/" + id(mine)).status()).isEqualTo(403);
        // an owner may delete anyone's comment (moderation), not edit it
        assertThat(owner.patch("/comments/" + id(mine), object("body", "x")).status())
                .isEqualTo(403);
        assertThat(owner.delete("/comments/" + id(mine)).status()).isEqualTo(204);
        assertThat(vera.delete("/comments/" + id(owners)).status()).isEqualTo(403);
    }

    @Test
    void aReadOnlyTokenReadsButCannotCommentOrSignOff(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String p = project(owner);
        String run = id(finishedRun(owner, p));
        JsonNode c = comment(owner, p, object());
        ApiClient reading = tokenClient(server, owner, "read");
        assertThat(reading.get("/projects/" + p + "/comments").status()).isEqualTo(200);
        assertThat(reading.get("/projects/" + p + "/signoffs").status()).isEqualTo(200);
        assertThat(reading.get("/projects/" + p + "/activity").status()).isEqualTo(200);
        JsonNode listed = reading.get("/projects/" + p + "/comments").json();
        assertThat(fields(listed.path(0), "canEdit", "canDelete"))
                .isEqualTo(object("canEdit", false, "canDelete", false));
        for (ApiResponse refused : List.of(
                reading.post("/projects/" + p + "/comments", object("body", "x")),
                reading.patch("/comments/" + id(c), object("body", "x")),
                reading.delete("/comments/" + id(c)),
                reading.post("/comments/" + id(c) + "/resolve"),
                reading.post("/runs/" + run + "/signoffs", object("decision", "approved")))) {
            assertThat(refused.status()).as(refused + ": " + refused.text()).isEqualTo(403);
        }
        ApiClient writing = tokenClient(server, owner, "write");
        assertThat(writing.post("/comments/" + id(c) + "/resolve").status()).isEqualTo(200);
        List<Map<String, @Nullable Object>> resolved = projectEvents(server, p).stream()
                .filter(e -> "comment.resolve".equals(e.get("action")))
                .toList();
        assertThat(resolved).isNotEmpty();
        assertThat(resolved.get(0).get("actor_kind")).isEqualTo("token");
    }

    @Test
    void peopleOutsideTheWorkspaceGet404(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String workspace = Workspaces.first(owner);
        String p = project(owner);
        String run = id(finishedRun(owner, p));
        JsonNode c = comment(owner, p, object());
        JsonNode s = owner.post("/runs/" + run + "/signoffs", object("decision", "approved"))
                .json();
        ApiClient eve = server.signedIn("eve"); // the owner of a workspace of her own, elsewhere
        String elsewhere = Workspaces.first(eve);
        for (ApiResponse res : List.of(
                eve.get("/projects/" + p + "/comments"),
                eve.post("/projects/" + p + "/comments", object("body", "x")),
                eve.patch("/comments/" + id(c), object("body", "x")),
                eve.delete("/comments/" + id(c)),
                eve.post("/comments/" + id(c) + "/resolve"),
                eve.get("/projects/" + p + "/signoffs"),
                eve.get("/runs/" + run + "/signoffs"),
                eve.post("/runs/" + run + "/signoffs", object("decision", "approved")),
                eve.delete("/signoffs/" + id(s)),
                eve.get("/projects/" + p + "/activity"),
                eve.get("/workspaces/" + workspace + "/activity"))) {
            assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(404);
        }
        // and their own workspace's history has none of it
        assertThat(eve.get("/workspaces/" + elsewhere + "/activity").json().path("items"))
                .isEqualTo(Json.array());
    }

    // -- sign-offs ---------------------------------------------------------------------------------
    @Test
    void signoffsRecordTheRunAndGoStaleWhenTheDesignChanges(ApiClient client) {
        String p = project(client);
        JsonNode run = finishedRun(client, p);
        String signoffs = "/runs/" + id(run) + "/signoffs";
        ApiResponse res =
                client.post(signoffs, object("decision", "changes_requested", "note", " Cache TTL too long "));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        JsonNode first = res.json();
        assertThat(fields(first, "decision", "note", "stale"))
                .isEqualTo(object("decision", "changes_requested", "note", "Cache TTL too long", "stale", false));
        assertThat(fields(first, "projectRevision", "inputDigest", "resultDigest"))
                .isEqualTo(object(
                        "projectRevision",
                        1,
                        "inputDigest",
                        run.path("inputDigest"),
                        "resultDigest",
                        run.path("resultDigest")));
        assertThat(run.path("inputDigest").asString()).isNotEmpty();
        assertThat(first.path("runLabel")).isEqualTo(run.path("label"));
        assertThat(first.path("canWithdraw").asBoolean()).isTrue();
        JsonNode approved =
                client.post(signoffs, object("decision", "approved")).json();
        assertThat(approved.path("stale")).isEqualTo(Json.tree(false));

        assertThat(client.put("/projects/" + p, object("name", "Changed")).status())
                .isEqualTo(200);
        JsonNode listed = client.get("/projects/" + p + "/signoffs").json();
        assertThat(strings(listed, "decision")).containsExactly("approved", "changes_requested"); // newest first
        assertThat(items(listed))
                .allSatisfy(s -> assertThat(fields(s, "stale", "currentRevision"))
                        .isEqualTo(object("stale", true, "currentRevision", 2)));
        assertThat(client.get(signoffs).json()).isEqualTo(listed);

        // a sign-off made now is of revision 2: not stale
        JsonNode fresh = client.post(signoffs, object("decision", "approved")).json();
        assertThat(fields(fresh, "projectRevision", "stale")).isEqualTo(object("projectRevision", 2, "stale", false));

        // deleting the run keeps the sign-off, with its digests
        assertThat(client.delete("/runs/" + id(run)).status()).isEqualTo(204);
        JsonNode kept = client.get("/projects/" + p + "/signoffs").json();
        assertThat(kept.size()).isEqualTo(3);
        assertThat(items(kept))
                .allSatisfy(s -> assertThat(fields(s, "runId", "resultDigest"))
                        .isEqualTo(object("runId", null, "resultDigest", run.path("resultDigest"))));
    }

    /** The Python test's run stays queued (no worker); here it is a long run, then cancelled. */
    @Test
    void signoffNeedsAFinishedRunAndOnlyItsReviewerWithdrawsIt(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String team = Workspaces.enter(owner);
        String p = project(owner);
        String unfinished = startLongRun(owner, p);
        ApiResponse res = owner.post("/runs/" + unfinished + "/signoffs", object("decision", "approved"));
        assertThat(res.status()).isEqualTo(409);
        owner.post("/runs/" + unfinished + "/cancel");
        Runs.waitFor(owner, unfinished);
        String run = id(finishedRun(owner, p));
        String url = "/runs/" + run + "/signoffs";
        assertThat(owner.post(url, object("decision", "maybe")).status()).isEqualTo(422);
        assertThat(owner.post(url, object("decision", "approved", "note", "x".repeat(2001)))
                        .status())
                .isEqualTo(422);
        JsonNode mine = owner.post(url, object("decision", "approved")).json();
        ApiClient vera = server.signedIn("vera");
        Workspaces.join(owner, team, vera, "viewer");
        JsonNode listed = vera.get(url).json();
        assertThat(listed.path(0).path("canWithdraw")).isEqualTo(Json.tree(false));
        assertThat(vera.delete("/signoffs/" + id(mine)).status()).isEqualTo(403);
        assertThat(owner.delete("/signoffs/" + id(mine)).status()).isEqualTo(204);
        assertThat(owner.get(url).json()).isEqualTo(Json.array());
        assertThat(owner.delete("/signoffs/" + id(mine)).status()).isEqualTo(404);
    }

    // -- history -----------------------------------------------------------------------------------
    /** The Python test's second run stays queued (no worker); here it is long, and cancelled. */
    @Test
    void everyChangeWritesItsHistory(ApiServer server) {
        ApiClient client = server.client();
        String workspace = Workspaces.enter(client);
        String p = project(client);
        String run = id(finishedRun(client, p));
        String queued = startLongRun(client, p);
        client.post("/runs/" + queued + "/cancel");
        Runs.waitFor(client, queued);
        client.put("/projects/" + p, object("name", "Renamed"));
        JsonNode c = comment(client, p, object("anchorKind", "node", "anchorId", "db", "body", "Replica lag?"));
        client.patch("/comments/" + id(c), object("body", "Replica lag ok?"));
        client.post("/comments/" + id(c) + "/resolve");
        client.post("/comments/" + id(c) + "/resolve", object("resolved", false));
        JsonNode s = client.post("/runs/" + run + "/signoffs", object("decision", "approved"))
                .json();
        client.delete("/signoffs/" + id(s));
        client.delete("/comments/" + id(c));
        client.delete("/runs/" + queued);
        List<Map<String, @Nullable Object>> events = projectEvents(server, p);
        List<String> actions = actions(events);
        assertThat(actions)
                .containsExactly(
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
                        "run.delete");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("workspace_id")).isEqualTo(workspace);
            assertThat(e.get("actor_user_id")).isEqualTo("local");
            assertThat(e.get("actor_kind")).isEqualTo("user");
        });
        assertThat(meta(event(events, "project.update"))).isEqualTo(object("name", "Renamed", "revision", 2));
        JsonNode edit = meta(event(events, "comment.edit"));
        assertThat(edit.path("excerpt").asString()).isEqualTo("Replica lag ok?");
        assertThat(edit.path("anchorId").asString()).isEqualTo("db");

        // the API answers with the same history, newest first, people named
        JsonNode page = client.get("/projects/" + p + "/activity").json();
        assertThat(strings(page.path("items"), "action")).containsExactlyElementsOf(actions.reversed());
        assertThat(page.path("items").path(0).path("actor"))
                .isEqualTo(object("id", "local", "name", "Local user", "avatarUrl", null));
        assertThat(page.path("nextBefore").isNull()).isTrue();
    }

    @Test
    void aRefusedChangeWritesNoHistory(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String team = Workspaces.enter(owner);
        String p = project(owner);
        ApiClient vera = server.signedIn("vera");
        Workspaces.join(owner, team, vera, "viewer");
        List<String> before = actions(workspaceEvents(server, team));
        ApiResponse res = owner.put("/projects/" + p, object("name", "Late", "expectedRevision", 7));
        assertThat(res.status()).isEqualTo(409);
        assertThat(vera.delete("/projects/" + p).status()).isEqualTo(403);
        assertThat(vera.post("/projects/" + p + "/runs", object()).status()).isEqualTo(403);
        res = owner.post("/projects/" + p + "/comments", object("body", ""));
        assertThat(res.status()).isEqualTo(422);
        assertThat(actions(workspaceEvents(server, team))).isEqualTo(before);
    }

    @Test
    void membershipChangesAreRecorded(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String local = Workspaces.userId(owner);
        String wid = id(Workspaces.create(owner, "Platform"));
        ApiClient ann = server.signedIn("ann");
        String annId = Workspaces.userId(ann);
        JsonNode inv = owner.post("/workspaces/" + wid + "/invitations", object("role", "editor"))
                .json();
        JsonNode gone = owner.post("/workspaces/" + wid + "/invitations", object("role", "viewer"))
                .json();
        owner.delete("/workspaces/" + wid + "/invitations/" + id(gone));
        assertThat(ann.post("/invitations/accept", object("token", inv.path("token")))
                        .status())
                .isEqualTo(200);
        owner.patch("/workspaces/" + wid + "/members/" + annId, object("role", "viewer"));
        owner.patch("/workspaces/" + wid, object("name", "Platform team"));
        owner.delete("/workspaces/" + wid + "/members/" + annId);
        List<Map<String, @Nullable Object>> events = workspaceEvents(server, wid);
        assertThat(events)
                .extracting(e -> e.get("action") + " " + e.get("target_id") + " " + e.get("actor_user_id"))
                .containsExactly(
                        "workspace.create " + wid + " " + local,
                        "invitation.create " + id(inv) + " " + local,
                        "invitation.create " + id(gone) + " " + local,
                        "invitation.revoke " + id(gone) + " " + local,
                        "member.join " + annId + " " + annId,
                        "member.role " + annId + " " + local,
                        "workspace.rename " + wid + " " + local,
                        "member.remove " + annId + " " + local);
        assertThat(meta(event(events, "member.role"))).isEqualTo(object("from", "editor", "to", "viewer"));
        assertThat(events).allSatisfy(e -> assertThat(e.get("project_id")).isNull());
    }

    /**
     * The Python test writes one address on every event; on the hosted server each change
     * already has the address of the client that made it (its {@code X-ArchSim-Client-IP}).
     */
    @Test
    void activityPagesAndAddressesOnlyForOwners(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String team = Workspaces.enter(owner);
        String p = project(owner);
        for (int i = 0; i < 6; i++) {
            comment(owner, p, object("body", "Note " + i));
        }
        List<Long> seen = new ArrayList<>();
        Long before = null;
        while (true) {
            JsonNode page = owner.get(ApiClient.query("/projects/" + p + "/activity", "limit", 3, "before", before))
                    .json();
            items(page.path("items")).forEach(i -> seen.add(i.path("id").asLong()));
            if (page.path("nextBefore").isNull()) {
                break;
            }
            before = page.path("nextBefore").asLong();
        }
        assertThat(seen).hasSize(7).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(owner.get(ApiClient.query("/projects/" + p + "/activity", "limit", 0))
                        .status())
                .isEqualTo(422);
        assertThat(owner.get(ApiClient.query("/projects/" + p + "/activity", "limit", 201))
                        .status())
                .isEqualTo(422);

        ApiClient ed = server.signedIn("ed");
        Workspaces.join(owner, team, ed, "editor"); // ed's change, from ed's address
        JsonNode items = owner.get("/workspaces/" + team + "/activity").json().path("items");
        assertThat(addresses(items))
                .isEqualTo(Set.of(
                        requireNonNull(owner.header("X-ArchSim-Client-IP")),
                        requireNonNull(ed.header("X-ArchSim-Client-IP"))));
        JsonNode edSees = ed.get("/workspaces/" + team + "/activity").json().path("items");
        assertThat(items(edSees))
                .isNotEmpty()
                .allSatisfy(i -> assertThat(i.path("ip").isNull()).isTrue());
    }

    @Test
    void commentsAndHistoryStayInTheirWorkspace(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String personal = Workspaces.first(owner);
        String team = id(Workspaces.create(owner, "Team"));
        String mine = project(owner);
        ApiResponse res = owner.post(ApiClient.query("/projects", "workspace", team), object("document", design()));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        String theirs = id(res.json());
        comment(owner, theirs, object("body", "In the team"));
        comment(owner, mine, object("body", "In personal"));
        Map<String, @Nullable Object> rows = new HashMap<>();
        for (Map<String, @Nullable Object> row :
                server.db().rows("SELECT body, workspace_id FROM comments WHERE project_id IN (?, ?)", mine, theirs)) {
            rows.put(String.valueOf(row.get("body")), row.get("workspace_id"));
        }
        assertThat(rows).isEqualTo(Map.of("In the team", team, "In personal", personal));
        JsonNode teamItems =
                owner.get("/workspaces/" + team + "/activity").json().path("items");
        assertThat(projectIds(teamItems)).containsExactlyInAnyOrder(theirs, "none");
        JsonNode personalItems =
                owner.get("/workspaces/" + personal + "/activity").json().path("items");
        assertThat(projectIds(personalItems)).doesNotContain(theirs).contains(mine);
        ApiClient tim = server.signedIn("tim");
        Workspaces.join(owner, team, tim, "viewer");
        assertThat(tim.get("/projects/" + theirs + "/comments").status()).isEqualTo(200);
        assertThat(tim.get("/projects/" + mine + "/comments").status()).isEqualTo(404);
    }

    @Test
    void signingOffASimulatedRun(ApiClient client) {
        // the whole path with a real run: simulate, comment on it, approve, change, stale
        String p = project(client);
        JsonNode done = finishedRun(client, p);
        String run = id(done);
        JsonNode c = comment(client, p, object("anchorKind", "run", "anchorId", run, "body", "Looks right"));
        assertThat(c.path("detached")).isEqualTo(Json.tree(false));
        JsonNode s = client.post("/runs/" + run + "/signoffs", object("decision", "approved"))
                .json();
        assertThat(s.path("resultDigest")).isEqualTo(done.path("resultDigest"));
        assertThat(s.path("inputDigest")).isEqualTo(done.path("inputDigest"));
        client.put("/projects/" + p, object("document", Examples.starter(6, 1)));
        assertThat(client.get("/runs/" + run + "/signoffs")
                        .json()
                        .path(0)
                        .path("stale")
                        .asBoolean())
                .isTrue();
    }

    // -- helpers -----------------------------------------------------------------------------------
    /** The design of these tests: the starter, short enough to simulate in a moment. */
    private static ObjectNode design() {
        return Examples.starter(4, 1);
    }

    /** A project of {@link #design()} in the client's workspace; returns its id. */
    private static String project(ApiClient client) {
        return id(Runs.createProject(client, design()));
    }

    /** A run of the project's saved design, simulated to the end; returns the run. */
    private static JsonNode finishedRun(ApiClient client, String projectId) {
        ApiResponse res = client.post("/projects/" + projectId + "/runs", object());
        assertThat(res.status()).as(res.text()).isEqualTo(202);
        JsonNode done = Runs.waitFor(client, id(res.json()));
        assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("succeeded");
        return done;
    }

    /** A run of 120 simulated seconds, still queued or running when this returns; its id. */
    private static String startLongRun(ApiClient client, String projectId) {
        ApiResponse res = client.post("/projects/" + projectId + "/runs", object("document", Examples.starter(120, 5)));
        assertThat(res.status()).as(res.text()).isEqualTo(202);
        return id(res.json());
    }

    /** {@code POST /projects/{id}/comments} with {@code body} (its text "Hm" unless given). */
    private static JsonNode comment(ApiClient client, String projectId, ObjectNode body) {
        ObjectNode all = object("body", "Hm");
        all.setAll(body);
        ApiResponse res = client.post("/projects/" + projectId + "/comments", all);
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        return res.json();
    }

    /** A client that sends {@code person}'s new API token of {@code scope} (read or write). */
    private static ApiClient tokenClient(ApiServer server, ApiClient person, String scope) {
        ApiResponse res = person.post("/tokens", object("name", "test " + scope, "scope", scope));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        return server.client()
                .header("Authorization", "Bearer " + res.json().path("token").asString());
    }

    private static final String EVENTS = "SELECT id, workspace_id, project_id, actor_user_id, actor_kind, action,"
            + " target_id, meta::text AS meta FROM audit_events";

    /** The project's history, oldest first. */
    private static List<Map<String, @Nullable Object>> projectEvents(ApiServer server, String projectId) {
        return server.db().rows(EVENTS + " WHERE project_id = ? ORDER BY id", projectId);
    }

    /** The workspace's history, oldest first. */
    private static List<Map<String, @Nullable Object>> workspaceEvents(ApiServer server, String workspaceId) {
        return server.db().rows(EVENTS + " WHERE workspace_id = ? ORDER BY id", workspaceId);
    }

    private static List<String> actions(List<Map<String, @Nullable Object>> events) {
        return events.stream().map(e -> String.valueOf(e.get("action"))).toList();
    }

    /** The first event of {@code action}. */
    private static Map<String, @Nullable Object> event(List<Map<String, @Nullable Object>> events, String action) {
        return events.stream()
                .filter(e -> action.equals(e.get("action")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + action + " in " + events));
    }

    private static JsonNode meta(Map<String, @Nullable Object> event) {
        return Json.parse(String.valueOf(requireNonNull(event.get("meta"), "meta")));
    }

    /** How many rows of {@code table} belong to the project. */
    private static long rowsOf(ApiServer server, String table, String projectId) {
        Object count = server.db().value("SELECT count(*) FROM " + table + " WHERE project_id = ?", projectId);
        return ((Number) requireNonNull(count)).longValue();
    }

    /** The {@code ip} of each history item (null for none). */
    private static Set<@Nullable String> addresses(JsonNode items) {
        Set<@Nullable String> out = new HashSet<>();
        items(items)
                .forEach(
                        i -> out.add(i.path("ip").isNull() ? null : i.path("ip").asString()));
        return out;
    }

    /** The {@code projectId} of each history item ("none" for none). */
    private static Set<String> projectIds(JsonNode items) {
        Set<String> out = new HashSet<>();
        items(items).forEach(i -> out.add(i.path("projectId").asString("none")));
        return out;
    }

    /** Just these fields of {@code node}, to compare several at once. */
    private static ObjectNode fields(JsonNode node, String... names) {
        ObjectNode out = object();
        for (String name : names) {
            if (node.has(name)) {
                out.set(name, node.path(name));
            }
        }
        return out;
    }

    private static String id(JsonNode node) {
        return node.path("id").asString();
    }
}
