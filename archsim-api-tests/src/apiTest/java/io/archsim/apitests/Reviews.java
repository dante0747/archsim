package io.archsim.apitests;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The steps {@link ReviewTests} and {@link ReviewHistoryTests} share: a design short
 * enough to simulate in a moment, its projects, runs and comments, and the history
 * ({@code audit_events}) of a project or a workspace.
 */
final class Reviews {

	private static final String EVENTS = "SELECT id, workspace_id, project_id, actor_user_id, actor_kind, action,"
			+ " target_id, meta::text AS meta FROM audit_events";

	private Reviews() {
	}

	/** The design of these tests: the starter, short enough to simulate in a moment. */
	static ObjectNode design() {
		return Examples.starter(4, 1);
	}

	/** A project of {@link #design()} in the client's workspace; returns its id. */
	static String newProject(ApiClient client) {
		return id(Runs.createProject(client, design()));
	}

	/** A run of the project's saved design, simulated to the end; returns the run. */
	static JsonNode finishedRun(ApiClient client, String projectId) {
		ApiResponse res = client.post("/projects/" + projectId + "/runs", Json.object());
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		JsonNode done = Runs.waitFor(client, id(res.json()));
		assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("succeeded");
		return done;
	}

	/**
	 * A run of 120 simulated seconds, still queued or running when this returns; its id.
	 */
	static String startLongRun(ApiClient client, String projectId) {
		ApiResponse res = client.post("/projects/" + projectId + "/runs",
				Json.object("document", Examples.starter(120, 5)));
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		return id(res.json());
	}

	/**
	 * {@code POST /projects/{id}/comments} with {@code body} (its text "Hm" unless
	 * given).
	 */
	static JsonNode comment(ApiClient client, String projectId, ObjectNode body) {
		ObjectNode all = Json.object("body", "Hm");
		all.setAll(body);
		ApiResponse res = client.post("/projects/" + projectId + "/comments", all);
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return res.json();
	}

	/** The project's history, oldest first. */
	static List<Map<String, @Nullable Object>> projectEvents(ApiServer server, String projectId) {
		return server.db().rows(EVENTS + " WHERE project_id = ? ORDER BY id", projectId);
	}

	/** The workspace's history, oldest first. */
	static List<Map<String, @Nullable Object>> workspaceEvents(ApiServer server, String workspaceId) {
		return server.db().rows(EVENTS + " WHERE workspace_id = ? ORDER BY id", workspaceId);
	}

	private static String id(JsonNode node) {
		return node.path("id").asString();
	}

}
