package io.archsim.apitests;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history every change writes in its own transaction: which changes write it, that a
 * refused one writes none, who sees it (and the addresses in it), and that it stays in
 * its workspace. From {@code services/api/tests/test_api_review.py}, whose other tests
 * are {@link ReviewTests}.
 *
 * <p>
 * Tests with other people run on the hosted server, with real people in a team workspace;
 * the history is read from {@code audit_events}, for the test's own project or workspace.
 */
@ApiTest(Profile.LOCAL)
class ReviewHistoryTests {

	/**
	 * The Python test's second run stays queued (no worker); here it is long, and
	 * cancelled.
	 */
	@Test
	void change_ofEachKind_writesItsHistory(ApiServer server) {
		ApiClient client = server.client();
		String workspace = Workspaces.enter(client);
		String project = Reviews.newProject(client);
		changeEverything(client, project);
		List<Map<String, @Nullable Object>> events = Reviews.projectEvents(server, project);
		List<String> actions = actions(events);
		assertThat(actions).containsExactly("project.create", "run.create", "run.create", "run.cancel",
				"project.update", "comment.create", "comment.edit", "comment.resolve", "comment.reopen",
				"signoff.create", "signoff.withdraw", "comment.delete", "run.delete");
		assertThat(events).allSatisfy((event) -> {
			assertThat(event.get("workspace_id")).isEqualTo(workspace);
			assertThat(event.get("actor_user_id")).isEqualTo("local");
			assertThat(event.get("actor_kind")).isEqualTo("user");
		});
		assertThat(meta(event(events, "project.update"))).isEqualTo(Json.object("name", "Renamed", "revision", 2));
		JsonNode edit = meta(event(events, "comment.edit"));
		assertThat(edit.path("excerpt").asString()).isEqualTo("Replica lag ok?");
		assertThat(edit.path("anchorId").asString()).isEqualTo("db");

		// the API answers with the same history, newest first, people named
		JsonNode page = client.get("/projects/" + project + "/activity").json();
		assertThat(Json.strings(page.path("items"), "action")).containsExactlyElementsOf(actions.reversed());
		assertThat(page.path("items").path(0).path("actor"))
			.isEqualTo(Json.object("id", "local", "name", "Local user", "avatarUrl", null));
		assertThat(page.path("nextBefore").isNull()).isTrue();
	}

	@Test
	void change_whenRefused_writesNoHistory(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.enter(owner);
		String project = Reviews.newProject(owner);
		ApiClient vera = server.signedIn("vera");
		Workspaces.join(owner, team, vera, "viewer");
		List<String> before = actions(Reviews.workspaceEvents(server, team));
		ApiResponse res = owner.put("/projects/" + project, Json.object("name", "Late", "expectedRevision", 7));
		assertThat(res.status()).isEqualTo(409);
		assertThat(vera.delete("/projects/" + project).status()).isEqualTo(403);
		assertThat(vera.post("/projects/" + project + "/runs", Json.object()).status()).isEqualTo(403);
		res = owner.post("/projects/" + project + "/comments", Json.object("body", ""));
		assertThat(res.status()).isEqualTo(422);
		assertThat(actions(Reviews.workspaceEvents(server, team))).isEqualTo(before);
	}

	@Test
	void membershipChange_ofEachKind_isRecorded(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String ownerId = Workspaces.userId(owner);
		String workspace = id(Workspaces.create(owner, "Platform"));
		ApiClient ann = server.signedIn("ann");
		String annId = Workspaces.userId(ann);
		String invitations = "/workspaces/" + workspace + "/invitations";
		JsonNode invitation = owner.post(invitations, Json.object("role", "editor")).json();
		JsonNode gone = owner.post(invitations, Json.object("role", "viewer")).json();
		owner.delete(invitations + "/" + id(gone));
		ApiResponse accepted = ann.post("/invitations/accept", Json.object("token", invitation.path("token")));
		assertThat(accepted.status()).isEqualTo(200);
		owner.patch("/workspaces/" + workspace + "/members/" + annId, Json.object("role", "viewer"));
		owner.patch("/workspaces/" + workspace, Json.object("name", "Platform team"));
		owner.delete("/workspaces/" + workspace + "/members/" + annId);
		List<Map<String, @Nullable Object>> events = Reviews.workspaceEvents(server, workspace);
		assertThat(events)
			.extracting(
					(event) -> event.get("action") + " " + event.get("target_id") + " " + event.get("actor_user_id"))
			.containsExactly("workspace.create " + workspace + " " + ownerId,
					"invitation.create " + id(invitation) + " " + ownerId,
					"invitation.create " + id(gone) + " " + ownerId, "invitation.revoke " + id(gone) + " " + ownerId,
					"member.join " + annId + " " + annId, "member.role " + annId + " " + ownerId,
					"workspace.rename " + workspace + " " + ownerId, "member.remove " + annId + " " + ownerId);
		assertThat(meta(event(events, "member.role"))).isEqualTo(Json.object("from", "editor", "to", "viewer"));
		assertThat(events).allSatisfy((event) -> assertThat(event.get("project_id")).isNull());
	}

	/**
	 * The Python test writes one address on every event; on the hosted server each change
	 * already has the address of the client that made it (its
	 * {@code X-ArchSim-Client-IP}).
	 */
	@Test
	void activity_readInPages_showsAddressesOnlyToOwners(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.enter(owner);
		String project = Reviews.newProject(owner);
		for (int i = 0; i < 6; i++) {
			Reviews.comment(owner, project, Json.object("body", "Note " + i));
		}
		String activity = "/projects/" + project + "/activity";
		assertThat(idsPageByPage(owner, activity)).hasSize(7).isSortedAccordingTo(Comparator.reverseOrder());
		assertThat(owner.get(ApiClient.query(activity, "limit", 0)).status()).isEqualTo(422);
		assertThat(owner.get(ApiClient.query(activity, "limit", 201)).status()).isEqualTo(422);

		ApiClient ed = server.signedIn("ed");
		// ed's change, from ed's address
		Workspaces.join(owner, team, ed, "editor");
		JsonNode items = owner.get("/workspaces/" + team + "/activity").json().path("items");
		assertThat(addresses(items)).isEqualTo(Set.of(Objects.requireNonNull(owner.header("X-ArchSim-Client-IP")),
				Objects.requireNonNull(ed.header("X-ArchSim-Client-IP"))));
		JsonNode edSees = ed.get("/workspaces/" + team + "/activity").json().path("items");
		assertThat(Json.items(edSees)).isNotEmpty().allSatisfy((item) -> assertThat(item.path("ip").isNull()).isTrue());
	}

	@Test
	void commentsAndHistory_ofTwoWorkspaces_stayInTheirWorkspace(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String personal = Workspaces.first(owner);
		String team = id(Workspaces.create(owner, "Team"));
		String mine = Reviews.newProject(owner);
		ApiResponse res = owner.post(ApiClient.query("/projects", "workspace", team),
				Json.object("document", Reviews.design()));
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		String theirs = id(res.json());
		Reviews.comment(owner, theirs, Json.object("body", "In the team"));
		Reviews.comment(owner, mine, Json.object("body", "In personal"));
		Map<String, @Nullable Object> rows = new HashMap<>();
		for (Map<String, @Nullable Object> row : server.db()
			.rows("SELECT body, workspace_id FROM comments WHERE project_id IN (?, ?)", mine, theirs)) {
			rows.put(String.valueOf(row.get("body")), row.get("workspace_id"));
		}
		assertThat(rows).isEqualTo(Map.of("In the team", team, "In personal", personal));
		JsonNode teamItems = owner.get("/workspaces/" + team + "/activity").json().path("items");
		assertThat(projectIds(teamItems)).containsExactlyInAnyOrder(theirs, "none");
		JsonNode personalItems = owner.get("/workspaces/" + personal + "/activity").json().path("items");
		assertThat(projectIds(personalItems)).doesNotContain(theirs).contains(mine);
		ApiClient tim = server.signedIn("tim");
		Workspaces.join(owner, team, tim, "viewer");
		assertThat(tim.get("/projects/" + theirs + "/comments").status()).isEqualTo(200);
		assertThat(tim.get("/projects/" + mine + "/comments").status()).isEqualTo(404);
	}

	/**
	 * Makes every change the project's history records, in this order: runs (one
	 * cancelled), the project, a comment and its thread, a sign-off, and deletions.
	 */
	private static void changeEverything(ApiClient client, String project) {
		String run = id(Reviews.finishedRun(client, project));
		String queued = Reviews.startLongRun(client, project);
		client.post("/runs/" + queued + "/cancel");
		Runs.waitFor(client, queued);
		client.put("/projects/" + project, Json.object("name", "Renamed"));
		JsonNode comment = Reviews.comment(client, project,
				Json.object("anchorKind", "node", "anchorId", "db", "body", "Replica lag?"));
		client.patch("/comments/" + id(comment), Json.object("body", "Replica lag ok?"));
		client.post("/comments/" + id(comment) + "/resolve");
		client.post("/comments/" + id(comment) + "/resolve", Json.object("resolved", false));
		JsonNode signoff = client.post("/runs/" + run + "/signoffs", Json.object("decision", "approved")).json();
		client.delete("/signoffs/" + id(signoff));
		client.delete("/comments/" + id(comment));
		client.delete("/runs/" + queued);
	}

	/** The ids of the history items at {@code activity}, read three to a page. */
	private static List<Long> idsPageByPage(ApiClient client, String activity) {
		List<Long> seen = new ArrayList<>();
		Long before = null;
		while (true) {
			JsonNode page = client.get(ApiClient.query(activity, "limit", 3, "before", before)).json();
			Json.items(page.path("items")).forEach((item) -> seen.add(item.path("id").asLong()));
			if (page.path("nextBefore").isNull()) {
				return seen;
			}
			before = page.path("nextBefore").asLong();
		}
	}

	private static List<String> actions(List<Map<String, @Nullable Object>> events) {
		return events.stream().map((event) -> String.valueOf(event.get("action"))).toList();
	}

	/** The first event of {@code action}. */
	private static Map<String, @Nullable Object> event(List<Map<String, @Nullable Object>> events, String action) {
		return events.stream()
			.filter((event) -> action.equals(event.get("action")))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + action + " in " + events));
	}

	private static JsonNode meta(Map<String, @Nullable Object> event) {
		return Json.parse(String.valueOf(Objects.requireNonNull(event.get("meta"), "meta")));
	}

	/** The {@code ip} of each history item (null for none). */
	private static Set<@Nullable String> addresses(JsonNode items) {
		Set<@Nullable String> out = new HashSet<>();
		Json.items(items).forEach((item) -> out.add(item.path("ip").isNull() ? null : item.path("ip").asString()));
		return out;
	}

	/** The {@code projectId} of each history item ("none" for none). */
	private static Set<String> projectIds(JsonNode items) {
		Set<String> out = new HashSet<>();
		Json.items(items).forEach((item) -> out.add(item.path("projectId").asString("none")));
		return out;
	}

	private static String id(JsonNode node) {
		return node.path("id").asString();
	}

}
