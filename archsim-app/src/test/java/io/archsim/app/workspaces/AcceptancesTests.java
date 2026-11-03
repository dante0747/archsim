package io.archsim.app.workspaces;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

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
			.isEqualTo(new Event("member.join", "user", bob.userId(), meta("{\"role\": \"editor\"}"), null));
	}

	@Test
	void accept_invitationClaimedMeanwhile_isNotFound() {
		InvitationLinks links = mock();
		MemberRepository members = mock();
		Workspaces workspaces = mock();
		AuditLog audit = mock();
		Acceptances acceptances = new Acceptances(links, members, workspaces, audit);
		Person bob = new Person(Principal.session("bob", "s", null));
		Instant now = Instant.parse("2026-11-03T10:00:00Z");
		InvitationView open = new InvitationView("i1", "w1", "editor", null, now, now.plusSeconds(60), null);
		given(links.openTo(bob.principal(), "asim_inv_x")).willReturn(new OpenInvitation(open, "Team", "Ada"));
		given(links.claim("i1", "bob")).willReturn(false);

		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> acceptances.accept(bob, "asim_inv_x"))
			.satisfies((refused) -> {
				assertThat(refused.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(refused.getMessage())
					.isEqualTo("This invitation (it may have expired or been used already) was not found");
			});
		then(members).shouldHaveNoInteractions();
		then(workspaces).shouldHaveNoInteractions();
		then(audit).shouldHaveNoInteractions();
	}

}
