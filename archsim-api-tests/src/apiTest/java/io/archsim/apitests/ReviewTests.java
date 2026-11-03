package io.archsim.apitests;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reviewing together: comments on parts (threads, resolve, detached) and sign-offs that
 * go stale. Roles: viewers read, comment and sign off; authors edit and delete their own
 * comments; owners may delete any; read-only tokens only read; people outside the
 * workspace get 404. From {@code services/api/tests/test_api_review.py}, whose history
 * tests are {@link ReviewHistoryTests}.
 *
 * <p>
 * The Python tests mark runs succeeded in the database with made-up digests; here the
 * runs are real short simulations, and sign-offs are checked against their digests. Tests
 * with other people run on the hosted server, with real people in a team workspace.
 */
@ApiTest(Profile.LOCAL)
class ReviewTests {

	// -- comments
	@Test
	void comment_onPartWithReplies_threadsAndResolvesTogether(ApiClient client) {
		String project = Reviews.newProject(client);
		JsonNode root = Reviews.comment(client, project,
				Json.object("anchorKind", "node", "anchorId", "cache", "body", "  Why 2 GB?  "));
		assertThat(fields(root, "anchorKind", "anchorId", "parentId"))
			.isEqualTo(Json.object("anchorKind", "node", "anchorId", "cache", "parentId", null));
		assertThat(root.path("body").asString()).isEqualTo("Why 2 GB?");
		assertThat(Json.id(root.path("author"))).isEqualTo("local");
		assertThat(fields(root, "detached", "canEdit", "canDelete"))
			.isEqualTo(Json.object("detached", false, "canEdit", true, "canDelete", true));
		JsonNode reply = Reviews.comment(client, project,
				Json.object("parentId", Json.id(root), "anchorKind", "project", "body", "Hit ratio"));
		// a reply joins its thread's part, whatever it says
		assertThat(fields(reply, "parentId", "anchorKind", "anchorId"))
			.isEqualTo(Json.object("parentId", Json.id(root), "anchorKind", "node", "anchorId", "cache"));
		JsonNode nested = Reviews.comment(client, project, Json.object("parentId", Json.id(reply), "body", "Agreed"));
		// one level of threads
		assertThat(nested.path("parentId").asString()).isEqualTo(Json.id(root));
		Reviews.comment(client, project, Json.object("anchorKind", "edge", "anchorId", "api-db", "body", "Pool size?"));
		Reviews.comment(client, project, Json.object("body", "Overall looks fine"));

		JsonNode onCache = commentsOn(client, project, "node", "cache");
		assertThat(Json.strings(onCache, "id")).containsExactly(Json.id(root), Json.id(reply), Json.id(nested));
		assertThat(client.get("/projects/" + project + "/comments").json().size()).isEqualTo(5);

		// resolving from a reply resolves the thread
		ApiResponse response = client.post("/comments/" + Json.id(reply) + "/resolve", Json.object("resolved", true));
		assertThat(response.status()).isEqualTo(200);
		assertThat(Json.id(response.json())).isEqualTo(Json.id(root));
		assertThat(response.json().path("resolvedAt").isString()).isTrue();
		assertThat(Json.id(response.json().path("resolvedBy"))).isEqualTo("local");
		response = client.post("/comments/" + Json.id(root) + "/resolve", Json.object("resolved", false));
		assertThat(fields(response.json(), "resolvedAt", "resolvedBy"))
			.isEqualTo(Json.object("resolvedAt", null, "resolvedBy", null));
	}

	@Test
	void comment_withBadTextOrAnchor_isRefused(ApiClient client) {
		String project = Reviews.newProject(client);
		String other = Reviews.newProject(client);
		String otherRun = Json.id(Runs.finishedRun(client, other));
		String url = "/projects/" + project + "/comments";
		assertThat(client.post(url, Json.object("body", "")).status()).isEqualTo(422);
		assertThat(client.post(url, Json.object("body", "   ")).status()).isEqualTo(422);
		assertThat(client.post(url, Json.object("body", "x".repeat(4001))).status()).isEqualTo(422);
		assertThat(client.post(url, Json.object("body", "x".repeat(4000))).status()).isEqualTo(201);
		assertThat(client.post(url, Json.object("body", "x", "anchorKind", "node")).status()).isEqualTo(422);
		assertThat(client.post(url, Json.object("body", "x", "anchorKind", "run")).status()).isEqualTo(422);
		assertThat(client.post(url, Json.object("body", "x", "anchorKind", "wall")).status()).isEqualTo(422);
		// runs and threads of another project are not this one's
		ApiResponse response = client.post(url, Json.object("body", "x", "anchorKind", "run", "anchorId", otherRun));
		assertThat(response.status()).isEqualTo(404);
		ObjectNode nextToOtherRun = Json.object("body", "x", "anchorKind", "node", "anchorId", "api", "runId",
				otherRun);
		assertThat(client.post(url, nextToOtherRun).status()).isEqualTo(404);
		JsonNode elsewhere = Reviews.comment(client, other, Json.object());
		assertThat(client.post(url, Json.object("body", "x", "parentId", Json.id(elsewhere))).status()).isEqualTo(404);
		// editing keeps the checks
		JsonNode mine = Reviews.comment(client, project, Json.object());
		assertThat(client.patch("/comments/" + Json.id(mine), Json.object("body", " ")).status()).isEqualTo(422);
		JsonNode edited = client.patch("/comments/" + Json.id(mine), Json.object("body", "Better")).json();
		assertThat(edited.path("body").asString()).isEqualTo("Better");
		assertThat(edited.path("editedAt").isString()).isTrue();
	}

	@Test
	void comment_onDeletedPartOrRun_isDetachedNotLost(ApiClient client) {
		String project = Reviews.newProject(client);
		String run = Json.id(Runs.finishedRun(client, project));
		JsonNode onCache = Reviews.comment(client, project, Json.object("anchorKind", "node", "anchorId", "cache"));
		JsonNode onLink = Reviews.comment(client, project, Json.object("anchorKind", "edge", "anchorId", "api-cache"));
		JsonNode onRun = Reviews.comment(client, project, Json.object("anchorKind", "run", "anchorId", run));
		assertThat(onRun.path("runId").asString()).isEqualTo(run);
		assertThat(onRun.path("anchorId").asString()).isEqualTo(run);
		JsonNode onNew = Reviews.comment(client, project,
				Json.object("anchorKind", "node", "anchorId", "not-saved-yet"));
		// not in the saved design (yet)
		assertThat(onNew.path("detached").asBoolean()).isTrue();

		assertThat(client.put("/projects/" + project, Json.object("document", designWithoutTheCache())).status())
			.isEqualTo(200);
		assertThat(client.delete("/runs/" + run).status()).isEqualTo(204);

		JsonNode listed = client.get("/projects/" + project + "/comments").json();
		assertThat(Json.find(listed, "id", Json.id(onCache)).path("detached").asBoolean()).isTrue();
		assertThat(Json.find(listed, "id", Json.id(onLink)).path("detached").asBoolean()).isTrue();
		JsonNode runComment = Json.find(listed, "id", Json.id(onRun));
		assertThat(runComment.path("detached").asBoolean()).isTrue();
		assertThat(runComment.path("runId").isNull()).isTrue();
		// nothing was deleted with the parts
		assertThat(listed.size()).isEqualTo(4);
	}

	@Test
	void deleteProject_withCommentsAndSignoffs_deletesThemButKeepsHistory(ApiServer server) {
		ApiClient client = server.client();
		String workspace = Workspaces.enter(client);
		JsonNode created = Runs.createProject(client, Reviews.design());
		String project = Json.id(created);
		String run = Json.id(Runs.finishedRun(client, project));
		Reviews.comment(client, project, Json.object());
		client.post("/runs/" + run + "/signoffs", Json.object("decision", "approved"));
		assertThat(rowsOf(server, "comments", project)).isEqualTo(1L);
		assertThat(rowsOf(server, "review_signoffs", project)).isEqualTo(1L);
		assertThat(client.delete("/projects/" + project).status()).isEqualTo(204);
		assertThat(rowsOf(server, "comments", project)).isZero();
		assertThat(rowsOf(server, "review_signoffs", project)).isZero();
		// its history stays, in the workspace's
		JsonNode page = client.get("/workspaces/" + workspace + "/activity").json();
		assertThat(page.path("items").path(0).path("action").asString()).isEqualTo("project.delete");
		assertThat(page.path("items").path(0).path("meta").path("name")).isEqualTo(created.path("name"));
	}

	// -- roles
	@Test
	void review_asViewer_commentsResolvesAndSignsOffButOnlyAuthorsEdit(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.enter(owner);
		String project = Reviews.newProject(owner);
		String run = Json.id(Runs.finishedRun(owner, project));
		JsonNode owners = Reviews.comment(owner, project, Json.object("anchorKind", "node", "anchorId", "api"));
		ApiClient vera = server.signedIn("vera");
		Workspaces.join(owner, team, vera, "viewer");
		ApiClient ed = server.signedIn("ed");
		Workspaces.join(owner, team, ed, "editor");

		JsonNode listed = vera.get("/projects/" + project + "/comments").json();
		assertThat(fields(listed.path(0), "canEdit", "canDelete"))
			.isEqualTo(Json.object("canEdit", false, "canDelete", false));
		JsonNode mine = Reviews.comment(vera, project,
				Json.object("anchorKind", "node", "anchorId", "api", "body", "From vera"));
		assertThat(mine.path("author").path("name").asString()).isEqualTo("Vera");
		assertThat(vera.post("/comments/" + Json.id(owners) + "/resolve").status()).isEqualTo(200);
		ApiResponse response = vera.post("/runs/" + run + "/signoffs", Json.object("decision", "approved"));
		assertThat(response.status()).isEqualTo(201);
		assertThat(Json.id(response.json().path("reviewer"))).isEqualTo(Workspaces.userId(vera));
		assertThat(vera.patch("/comments/" + Json.id(mine), Json.object("body", "ok")).status()).isEqualTo(200);
		assertEachAnswers(403,
				List.of(vera.patch("/comments/" + Json.id(owners), Json.object("body", "mine now")),
						vera.delete("/comments/" + Json.id(owners)),
						vera.put("/projects/" + project, Json.object("name", "Viewer edit"))));
		assertEditorAndOwnerRights(project, mine, ed, owner);
		assertThat(vera.delete("/comments/" + Json.id(owners)).status()).isEqualTo(403);
	}

	/**
	 * Editors change the design, not other people's words; an owner may delete anyone's
	 * comment (moderation), not edit it. {@code comment} is neither's own.
	 */
	private static void assertEditorAndOwnerRights(String project, JsonNode comment, ApiClient editor,
			ApiClient owner) {
		assertThat(editor.put("/projects/" + project, Json.object("name", "Ed")).status()).isEqualTo(200);
		assertThat(editor.patch("/comments/" + Json.id(comment), Json.object("body", "x")).status()).isEqualTo(403);
		assertThat(editor.delete("/comments/" + Json.id(comment)).status()).isEqualTo(403);
		assertThat(owner.patch("/comments/" + Json.id(comment), Json.object("body", "x")).status()).isEqualTo(403);
		assertThat(owner.delete("/comments/" + Json.id(comment)).status()).isEqualTo(204);
	}

	@Test
	void review_withReadOnlyToken_readsButCannotCommentOrSignOff(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String project = Reviews.newProject(owner);
		String run = Json.id(Runs.finishedRun(owner, project));
		JsonNode comment = Reviews.comment(owner, project, Json.object());
		ApiClient reading = server.tokenClient(owner, "read");
		assertThat(reading.get("/projects/" + project + "/comments").status()).isEqualTo(200);
		assertThat(reading.get("/projects/" + project + "/signoffs").status()).isEqualTo(200);
		assertThat(reading.get("/projects/" + project + "/activity").status()).isEqualTo(200);
		JsonNode listed = reading.get("/projects/" + project + "/comments").json();
		assertThat(fields(listed.path(0), "canEdit", "canDelete"))
			.isEqualTo(Json.object("canEdit", false, "canDelete", false));
		String commentUrl = "/comments/" + Json.id(comment);
		assertEachAnswers(403,
				List.of(reading.post("/projects/" + project + "/comments", Json.object("body", "x")),
						reading.patch(commentUrl, Json.object("body", "x")), reading.delete(commentUrl),
						reading.post(commentUrl + "/resolve"),
						reading.post("/runs/" + run + "/signoffs", Json.object("decision", "approved"))));
		ApiClient writing = server.tokenClient(owner, "write");
		assertThat(writing.post(commentUrl + "/resolve").status()).isEqualTo(200);
		List<Map<String, @Nullable Object>> resolved = Reviews.projectEvents(server, project)
			.stream()
			.filter((event) -> "comment.resolve".equals(event.get("action")))
			.toList();
		assertThat(resolved).isNotEmpty();
		assertThat(resolved.get(0).get("actor_kind")).isEqualTo("token");
	}

	@Test
	void review_fromOutsideTheWorkspace_is404(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String workspace = Workspaces.first(owner);
		String project = Reviews.newProject(owner);
		String run = Json.id(Runs.finishedRun(owner, project));
		JsonNode comment = Reviews.comment(owner, project, Json.object());
		JsonNode signoff = owner.post("/runs/" + run + "/signoffs", Json.object("decision", "approved")).json();
		String commentUrl = "/comments/" + Json.id(comment);
		// the owner of a workspace of her own, elsewhere
		ApiClient eve = server.signedIn("eve");
		String elsewhere = Workspaces.first(eve);
		assertEachAnswers(404,
				List.of(eve.get("/projects/" + project + "/comments"),
						eve.post("/projects/" + project + "/comments", Json.object("body", "x")),
						eve.patch(commentUrl, Json.object("body", "x")), eve.delete(commentUrl),
						eve.post(commentUrl + "/resolve"), eve.get("/projects/" + project + "/signoffs"),
						eve.get("/runs/" + run + "/signoffs"),
						eve.post("/runs/" + run + "/signoffs", Json.object("decision", "approved")),
						eve.delete("/signoffs/" + Json.id(signoff)), eve.get("/projects/" + project + "/activity"),
						eve.get("/workspaces/" + workspace + "/activity")));
		// and their own workspace's history has none of it
		assertThat(eve.get("/workspaces/" + elsewhere + "/activity").json().path("items")).isEqualTo(Json.array());
	}

	// -- sign-offs
	@Test
	void signoff_afterDesignChange_recordsTheRunAndGoesStale(ApiClient client) {
		String project = Reviews.newProject(client);
		JsonNode run = Runs.finishedRun(client, project);
		String url = "/runs/" + Json.id(run) + "/signoffs";
		ApiResponse response = client.post(url,
				Json.object("decision", "changes_requested", "note", " Cache TTL too long "));
		assertThat(response.status()).as(response.text()).isEqualTo(201);
		JsonNode first = response.json();
		assertThat(fields(first, "decision", "note", "stale"))
			.isEqualTo(Json.object("decision", "changes_requested", "note", "Cache TTL too long", "stale", false));
		assertThat(fields(first, "projectRevision", "inputDigest", "resultDigest"))
			.isEqualTo(Json.object("projectRevision", 1, "inputDigest", run.path("inputDigest"), "resultDigest",
					run.path("resultDigest")));
		assertThat(run.path("inputDigest").asString()).isNotEmpty();
		assertThat(first.path("runLabel")).isEqualTo(run.path("label"));
		assertThat(first.path("canWithdraw").asBoolean()).isTrue();
		JsonNode approved = client.post(url, Json.object("decision", "approved")).json();
		assertThat(approved.path("stale")).isEqualTo(Json.tree(false));

		assertThat(client.put("/projects/" + project, Json.object("name", "Changed")).status()).isEqualTo(200);
		JsonNode listed = client.get("/projects/" + project + "/signoffs").json();
		// newest first
		assertThat(Json.strings(listed, "decision")).containsExactly("approved", "changes_requested");
		assertThat(Json.items(listed)).allSatisfy((signoff) -> assertThat(fields(signoff, "stale", "currentRevision"))
			.isEqualTo(Json.object("stale", true, "currentRevision", 2)));
		assertThat(client.get(url).json()).isEqualTo(listed);

		// a sign-off made now is of revision 2: not stale
		JsonNode fresh = client.post(url, Json.object("decision", "approved")).json();
		assertThat(fields(fresh, "projectRevision", "stale"))
			.isEqualTo(Json.object("projectRevision", 2, "stale", false));

		assertDeletingTheRunKeepsItsSignoffs(client, project, run);
	}

	/** Deleting the run keeps its project's three sign-offs, with the run's digests. */
	private static void assertDeletingTheRunKeepsItsSignoffs(ApiClient client, String project, JsonNode run) {
		assertThat(client.delete("/runs/" + Json.id(run)).status()).isEqualTo(204);
		JsonNode kept = client.get("/projects/" + project + "/signoffs").json();
		assertThat(kept.size()).isEqualTo(3);
		assertThat(Json.items(kept)).allSatisfy((signoff) -> assertThat(fields(signoff, "runId", "resultDigest"))
			.isEqualTo(Json.object("runId", null, "resultDigest", run.path("resultDigest"))));
	}

	/**
	 * The Python test's run stays queued (no worker); here it is a long run, then
	 * cancelled.
	 */
	@Test
	void signoff_ofUnfinishedRun_isRefusedAndOnlyItsReviewerWithdraws(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.enter(owner);
		String project = Reviews.newProject(owner);
		String unfinished = Runs.startLongRun(owner, project);
		ApiResponse response = owner.post("/runs/" + unfinished + "/signoffs", Json.object("decision", "approved"));
		assertThat(response.status()).isEqualTo(409);
		owner.post("/runs/" + unfinished + "/cancel");
		Runs.waitFor(owner, unfinished);
		String run = Json.id(Runs.finishedRun(owner, project));
		String url = "/runs/" + run + "/signoffs";
		assertThat(owner.post(url, Json.object("decision", "maybe")).status()).isEqualTo(422);
		assertThat(owner.post(url, Json.object("decision", "approved", "note", "x".repeat(2001))).status())
			.isEqualTo(422);
		JsonNode mine = owner.post(url, Json.object("decision", "approved")).json();
		ApiClient vera = server.signedIn("vera");
		Workspaces.join(owner, team, vera, "viewer");
		JsonNode listed = vera.get(url).json();
		assertThat(listed.path(0).path("canWithdraw")).isEqualTo(Json.tree(false));
		assertThat(vera.delete("/signoffs/" + Json.id(mine)).status()).isEqualTo(403);
		assertThat(owner.delete("/signoffs/" + Json.id(mine)).status()).isEqualTo(204);
		assertThat(owner.get(url).json()).isEqualTo(Json.array());
		assertThat(owner.delete("/signoffs/" + Json.id(mine)).status()).isEqualTo(404);
	}

	@Test
	void signoff_ofSimulatedRun_goesStaleWhenTheDesignChanges(ApiClient client) {
		// the whole path with a real run: simulate, comment on it, approve, change, stale
		String project = Reviews.newProject(client);
		JsonNode done = Runs.finishedRun(client, project);
		String run = Json.id(done);
		JsonNode comment = Reviews.comment(client, project,
				Json.object("anchorKind", "run", "anchorId", run, "body", "Looks right"));
		assertThat(comment.path("detached")).isEqualTo(Json.tree(false));
		JsonNode signoff = client.post("/runs/" + run + "/signoffs", Json.object("decision", "approved")).json();
		assertThat(signoff.path("resultDigest")).isEqualTo(done.path("resultDigest"));
		assertThat(signoff.path("inputDigest")).isEqualTo(done.path("inputDigest"));
		client.put("/projects/" + project, Json.object("document", Examples.starter(6, 1)));
		assertThat(client.get("/runs/" + run + "/signoffs").json().path(0).path("stale").asBoolean()).isTrue();
	}

	// -- helpers
	/** The project's comments on one part, by its kind and id. */
	private static JsonNode commentsOn(ApiClient client, String project, String anchorKind, String anchorId) {
		return client
			.get(ApiClient.query("/projects/" + project + "/comments", "anchorKind", anchorKind, "anchorId", anchorId))
			.json();
	}

	/** {@link Reviews#design()} without the cache and the links to and from it. */
	private static ObjectNode designWithoutTheCache() {
		ObjectNode document = Reviews.design();
		ObjectNode architecture = (ObjectNode) document.path("architecture");
		architecture.set("nodes",
				Json.array(Json.items(architecture.path("nodes"))
					.stream()
					.filter((node) -> !Json.id(node).equals("cache"))
					.toArray()));
		architecture.set("edges",
				Json.array(Json.items(architecture.path("edges"))
					.stream()
					.filter((edge) -> !Json.id(edge).contains("cache"))
					.toArray()));
		return document;
	}

	/** Each of the answers has {@code status} (the requests are all made first). */
	private static void assertEachAnswers(int status, List<ApiResponse> answers) {
		for (ApiResponse response : answers) {
			assertThat(response.status()).as(response + ": " + response.text()).isEqualTo(status);
		}
	}

	/** How many rows of {@code table} belong to the project. */
	private static long rowsOf(ApiServer server, String table, String projectId) {
		Object count = server.db().value("SELECT count(*) FROM " + table + " WHERE project_id = ?", projectId);
		return ((Number) Objects.requireNonNull(count)).longValue();
	}

	/** Just these fields of {@code node}, to compare several at once. */
	private static ObjectNode fields(JsonNode node, String... names) {
		ObjectNode out = Json.object();
		for (String name : names) {
			if (node.has(name)) {
				out.set(name, node.path(name));
			}
		}
		return out;
	}

}
