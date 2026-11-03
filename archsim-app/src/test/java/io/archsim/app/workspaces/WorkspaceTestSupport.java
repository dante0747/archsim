package io.archsim.app.workspaces;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the tests of workspaces, members and invitations share: requests made as someone
 * signed in, team workspaces and invitations to them, and the history they leave.
 */
abstract class WorkspaceTestSupport extends HostedTestSupport {

	static final String WORKSPACES = "/api/v1/workspaces";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * A new team workspace.
	 * @param owner who makes it, and so owns it
	 * @return its id
	 */
	String team(SignedIn owner) {
		return created(send(owner, HttpMethod.POST, WORKSPACES, "{\"name\": \"Team\"}")).path("id").asString();
	}

	/**
	 * A new invitation to a team workspace.
	 * @param owner who sends it
	 * @param team the workspace
	 * @param role the role it gives
	 * @return the invitation, with its token
	 */
	JsonNode invite(SignedIn owner, String team, String role) {
		return created(
				send(owner, HttpMethod.POST, WORKSPACES + "/" + team + "/invitations", "{\"role\": \"" + role + "\"}"));
	}

	MvcTestResult accept(SignedIn person, String token) {
		return send(person, HttpMethod.POST, "/api/v1/invitations/accept", "{\"token\": \"" + token + "\"}");
	}

	MvcTestResult send(SignedIn person, HttpMethod method, String uri, @Nullable String body) {
		MockMvcTester.MockMvcRequestBuilder request = person.browser(this.mvc().method(method).uri(uri));
		return (body != null) ? request.contentType(MediaType.APPLICATION_JSON).content(body).exchange()
				: request.exchange();
	}

	/**
	 * A workspace's history.
	 * @param workspaceId the workspace
	 * @return its events, oldest first
	 */
	List<Event> history(String workspaceId) {
		return this.jdbc()
			.sql("SELECT action, target_kind, target_id, meta::text AS meta, project_id FROM audit_events "
					+ "WHERE workspace_id = :workspace ORDER BY id")
			.param("workspace", workspaceId)
			.query((rows, number) -> new Event(rows.getString("action"), rows.getString("target_kind"),
					rows.getString("target_id"), JSON.readTree(rows.getString("meta")), rows.getString("project_id")))
			.list();
	}

	static JsonNode created(MvcTestResult result) {
		assertThat(result).hasStatus(201);
		return json(result);
	}

	static JsonNode json(MvcTestResult result) {
		return JSON.readTree(result.getResponse().getContentAsByteArray());
	}

	static JsonNode meta(String json) {
		return JSON.readTree(json);
	}

	/**
	 * An event in a workspace's history.
	 *
	 * @param action what changed
	 * @param targetKind what kind of thing it changed
	 * @param targetId which one
	 * @param meta what else it records
	 * @param projectId the project it is in, if any
	 */
	record Event(String action, String targetKind, String targetId, JsonNode meta, @Nullable String projectId) {

	}

}
