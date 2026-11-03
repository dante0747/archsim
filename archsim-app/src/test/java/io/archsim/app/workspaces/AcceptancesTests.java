package io.archsim.app.workspaces;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accepting an invitation: whoever has the link joins once, and a member keeps the
 * stronger of the role they hold and the one offered.
 */
class AcceptancesTests extends WorkspaceTestSupport {

	@Test
	void accept_openInvitation_makesAMemberWithItsRole() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String token = invite(ada, team(ada), "viewer").path("token").asString();

		JsonNode joined = json(accept(bob, token));

		assertThat(joined.path("role").asString()).isEqualTo("viewer");
	}

	@Test
	void accept_usedInvitation_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String token = invite(ada, team(ada), "viewer").path("token").asString();
		accept(signedIn("Bob"), token);

		assertThat(accept(signedIn("Cy"), token)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This invitation (it may have expired or been used already) was not found");
	}

	@Test
	void accept_expiredInvitation_isNotFound() {
		SignedIn ada = signedIn("Ada");
		JsonNode invitation = invite(ada, team(ada), "viewer");
		this.jdbc()
			.sql("UPDATE invitations SET expires_at = now() - interval '1 second' WHERE id = :id")
			.param("id", invitation.path("id").asString())
			.update();

		assertThat(accept(signedIn("Bob"), invitation.path("token").asString())).hasStatus(404);
	}

	@Test
	void accept_weakerRoleByOwner_keepsOwner() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "owner");
		String token = invite(ada, team, "viewer").path("token").asString();

		JsonNode joined = json(accept(bob, token));

		assertThat(joined.path("role").asString()).isEqualTo("owner");
	}

	@Test
	void accept_strongerRoleByViewer_givesTheStrongerRole() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "viewer");
		String token = invite(ada, team, "editor").path("token").asString();

		JsonNode joined = json(accept(bob, token));

		assertThat(joined.path("role").asString()).isEqualTo("editor");
	}

	@Test
	void accept_openInvitation_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		String token = invite(ada, team, "editor").path("token").asString();

		accept(bob, token);

		assertThat(history(team)).last()
			.isEqualTo(new HistoryEvent("member.join", "user", bob.userId(), json("{\"role\": \"editor\"}"), null));
	}

}
