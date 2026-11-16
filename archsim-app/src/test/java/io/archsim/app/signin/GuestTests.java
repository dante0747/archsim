package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.access.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a sandbox's guest may do: design and simulate in their sandbox, within its limits;
 * not what takes an account of one's own (API tokens, team workspaces, invitations) or
 * publishes the sandbox's results (share links).
 */
class GuestTests extends SandboxTestSupport {

	@Test
	void createProject_guest_makesItInTheSandbox() {
		SignedIn guest = guest();

		String project = created(send(guest, HttpMethod.POST, "/api/v1/projects", "{}")).path("id").asString();

		assertThat(this.jdbc()
			.sql("SELECT workspace_id FROM projects WHERE id = :id")
			.param("id", project)
			.query(String.class)
			.single()).isEqualTo(guest.workspaceId());
	}

	@Test
	void createToken_guest_isForbidden() {
		SignedIn guest = guest();

		MvcTestResult refused = send(guest, HttpMethod.POST, "/api/v1/tokens", "{\"name\": \"CI\"}");

		assertThat(refused).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(Principal.GUESTS_CANNOT);
	}

	@Test
	void createInvitation_guest_isForbidden() {
		SignedIn guest = guest();

		MvcTestResult refused = send(guest, HttpMethod.POST,
				"/api/v1/workspaces/" + guest.workspaceId() + "/invitations", "{\"role\": \"viewer\"}");

		assertThat(refused).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(Principal.GUESTS_CANNOT);
	}

	@Test
	void createWorkspace_guest_isForbidden() {
		SignedIn guest = guest();

		MvcTestResult refused = send(guest, HttpMethod.POST, "/api/v1/workspaces", "{\"name\": \"Team\"}");

		assertThat(refused).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(Principal.GUESTS_CANNOT);
	}

	@Test
	void acceptInvitation_guest_isForbidden() {
		SignedIn guest = guest();

		MvcTestResult refused = send(guest, HttpMethod.POST, "/api/v1/invitations/accept", "{\"token\": \"any\"}");

		assertThat(refused).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(Principal.GUESTS_CANNOT);
	}

	@Test
	void createShare_guest_isForbidden() {
		SignedIn guest = guest();

		MvcTestResult refused = send(guest, HttpMethod.POST, "/api/v1/runs/any/shares", "{}");

		assertThat(refused).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(Principal.GUESTS_CANNOT);
	}

	@Test
	void logout_guest_endsTheSession() {
		SignedIn guest = guest();

		send(guest, HttpMethod.POST, "/api/v1/auth/logout", null);

		assertThat(send(guest, HttpMethod.GET, "/api/v1/auth/me", null)).hasStatus(401);
	}

}
