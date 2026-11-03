package io.archsim.app.workspaces;

import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HostedTestSupport;

/**
 * What the tests of workspaces, members and invitations share: requests made as someone
 * signed in, team workspaces and invitations to them, and the history they leave.
 */
abstract class WorkspaceTestSupport extends HostedTestSupport {

	static final String WORKSPACES = "/api/v1/workspaces";

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

}
