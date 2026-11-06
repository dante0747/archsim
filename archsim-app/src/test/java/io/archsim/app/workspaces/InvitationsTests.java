package io.archsim.app.workspaces;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Secrets;
import io.archsim.app.support.HistoryEvent;
import io.archsim.app.support.SameInstant;

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
			.isEqualTo(new HistoryEvent("invitation.create", "invitation", id, json("{\"role\": \"viewer\"}"), null));
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
	void list_invitationsMadeAtTheSameInstant_highestIdFirst() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);
		List<String> lowestFirst = SameInstant.storeThree((id) -> storeInvitationAtNine(id, team));

		JsonNode listed = json(send(ada, HttpMethod.GET, WORKSPACES + "/" + team + "/invitations", null));

		assertThat(ids(listed)).containsExactlyElementsOf(lowestFirst.reversed());
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
			.isEqualTo(new HistoryEvent("invitation.revoke", "invitation", id, json("{\"role\": \"editor\"}"), null));
	}

	/**
	 * Stores an open invitation to a workspace, made at nine o'clock.
	 * @param id its id
	 * @param workspaceId the workspace
	 */
	private void storeInvitationAtNine(String id, String workspaceId) {
		this.jdbc()
			.sql("""
					INSERT INTO invitations (id, workspace_id, token_hash, role, created_at, expires_at)
					VALUES (:id, :workspace, :hash, 'viewer', :at, now() + interval '30 days')""")
			.param("id", id)
			.param("workspace", workspaceId)
			.param("hash", Secrets.sha256Hex(id))
			.param("at", Timestamp.from(SameInstant.NINE))
			.update();
	}

}
