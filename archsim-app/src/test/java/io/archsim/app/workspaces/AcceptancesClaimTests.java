package io.archsim.app.workspaces;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
 * Accepting a link someone else claims in the meantime: it is used, so nobody joins.
 */
class AcceptancesClaimTests {

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
