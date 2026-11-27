package io.archsim.app.workspaces;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An invitation's row, with the email it was sent to, goes once it expires, whether or
 * not someone accepted it: its link opens nothing any more.
 */
class InvitationRetentionTests extends WorkspaceTestSupport {

	@Autowired
	private InvitationRetention retention;

	@Test
	void prune_expiredInvitation_deletesIt() {
		SignedIn ada = signedIn("Ada");
		String invitation = invite(ada, team(ada), "viewer").path("id").asString();
		expire(invitation);

		this.retention.prune(Instant.now());

		assertThat(stored(invitation)).isZero();
	}

	@Test
	void prune_acceptedInvitationOnceItExpires_deletesIt() {
		SignedIn ada = signedIn("Ada");
		String invitation = invite(ada, team(ada), "viewer").path("id").asString();
		jdbc().sql("UPDATE invitations SET accepted_at = now(), accepted_by = :user WHERE id = :id")
			.param("user", signedIn("Bob").userId())
			.param("id", invitation)
			.update();
		expire(invitation);

		this.retention.prune(Instant.now());

		assertThat(stored(invitation)).isZero();
	}

	@Test
	void prune_invitationBeforeItExpires_canStillBeAccepted() {
		SignedIn ada = signedIn("Ada");
		JsonNode invitation = invite(ada, team(ada), "viewer");

		this.retention.prune(Instant.now());

		assertThat(accept(signedIn("Bob"), invitation.path("token").asString())).hasStatusOk();
	}

	private void expire(String invitationId) {
		jdbc().sql("UPDATE invitations SET expires_at = now() - interval '1 second' WHERE id = :id")
			.param("id", invitationId)
			.update();
	}

	private int stored(String invitationId) {
		return jdbc().sql("SELECT count(*) FROM invitations WHERE id = :id")
			.param("id", invitationId)
			.query(Integer.class)
			.single();
	}

}
