package io.archsim.app.workspaces;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invitation links, which the owners of a team workspace send, list and revoke.
 */
class InvitationsTests extends WorkspaceTestSupport {

	@Test
	void create_forTeam_isOpenForTheDaysAsked() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		JsonNode invitation = created(
				send(ada, HttpMethod.POST, WORKSPACES + "/" + team + "/invitations", "{\"expiresInDays\": 3}"));

		assertThat(Duration.between(Instant.parse(invitation.path("createdAt").asString()),
				Instant.parse(invitation.path("expiresAt").asString())))
			.isEqualTo(Duration.ofDays(3));
	}

	@Test
	void create_forTeam_givesATokenWithThePrefix() {
		SignedIn ada = signedIn("Ada");

		JsonNode invitation = invite(ada, team(ada), "viewer");

		assertThat(invitation.path("token").asString()).startsWith(InvitationLinks.PREFIX);
	}

	@Test
	void create_forTeam_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		String id = invite(ada, team, "viewer").path("id").asString();

		assertThat(history(team)).last()
			.isEqualTo(new Event("invitation.create", "invitation", id, meta("{\"role\": \"viewer\"}"), null));
	}

	@Test
	void create_forPersonalWorkspace_isAConflict() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.POST, WORKSPACES + "/" + ada.workspaceId() + "/invitations", "{}"))
			.hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Only team workspaces can be shared: create one under Settings.");
	}

	@Test
	void create_emailEndingInANewline_isRefused() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		assertThat(send(ada, HttpMethod.POST, WORKSPACES + "/" + team + "/invitations",
				"{\"email\": \"ann@example.com\\n\"}"))
			.hasStatus(422);
	}

	@Test
	void list_withExpiredInvitation_leavesItOut() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);
		String open = invite(ada, team, "viewer").path("id").asString();
		String expired = invite(ada, team, "viewer").path("id").asString();
		this.jdbc()
			.sql("UPDATE invitations SET expires_at = now() - interval '1 second' WHERE id = :id")
			.param("id", expired)
			.update();

		JsonNode listed = json(send(ada, HttpMethod.GET, WORKSPACES + "/" + team + "/invitations", null));

		assertThat(listed.findValuesAsString("id")).containsExactly(open);
	}

	@Test
	void revoke_invitationOfAnotherWorkspace_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);
		String other = team(ada);
		String id = invite(ada, other, "viewer").path("id").asString();

		assertThat(send(ada, HttpMethod.DELETE, WORKSPACES + "/" + team + "/invitations/" + id, null)).hasStatus(404);
	}

	@Test
	void revoke_invitation_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);
		String id = invite(ada, team, "editor").path("id").asString();

		send(ada, HttpMethod.DELETE, WORKSPACES + "/" + team + "/invitations/" + id, null);

		assertThat(history(team)).last()
			.isEqualTo(new Event("invitation.revoke", "invitation", id, meta("{\"role\": \"editor\"}"), null));
	}

}
