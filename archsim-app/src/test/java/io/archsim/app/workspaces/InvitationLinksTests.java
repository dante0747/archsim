package io.archsim.app.workspaces;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invitation links: each is claimed once.
 */
class InvitationLinksTests extends WorkspaceTestSupport {

	@Autowired
	private InvitationLinks links;

	@Test
	void claim_twice_succeedsOnce() {
		SignedIn ada = signedIn("Ada");
		String id = invite(ada, team(ada), "viewer").path("id").asString();

		assertThat(this.links.claim(id, signedIn("Bob").userId())).isTrue();
		assertThat(this.links.claim(id, signedIn("Cy").userId())).isFalse();
	}

}
