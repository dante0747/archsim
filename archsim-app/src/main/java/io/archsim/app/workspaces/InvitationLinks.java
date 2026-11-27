package io.archsim.app.workspaces;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.access.Secrets;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.WorkspaceTarget;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Invitation links: a random token, shown once and stored as its SHA-256, that stays open
 * for a number of days or until someone accepts it. A link issued or revoked has its line
 * in the workspace's history, written with it.
 */
@Component
class InvitationLinks {

	/** Invitation tokens start with this, so people and secret scanners know them. */
	static final String PREFIX = "asim_inv_";

	private final InvitationRepository repository;

	private final AuditLog audit;

	private final Clock clock;

	InvitationLinks(InvitationRepository repository, AuditLog audit, Clock clock) {
		this.repository = repository;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * A workspace's open invitations.
	 * @param workspaceId the workspace
	 * @return the invitations not accepted and not expired, newest first
	 */
	List<InvitationView> open(String workspaceId) {
		return this.repository.open(workspaceId, Times.now(this.clock));
	}

	/**
	 * The open invitation a token belongs to, if the asker may accept it: anyone with the
	 * link, or for one sent to an email, only someone with that email verified.
	 * @param principal who is asking
	 * @param token the token, as the link carries it
	 * @return the invitation
	 * @throws ApiException {@code 404} for a link used, expired or unknown, {@code 403}
	 * for one sent to another email
	 */
	OpenInvitation openTo(Principal principal, String token) {
		OpenInvitation open = this.repository.openWithToken(Secrets.sha256Hex(token.strip()), Times.now(this.clock))
			.orElseThrow(InvitationLinks::gone);
		String email = open.view().email();
		if (email != null && !this.repository.verifiedEmails(principal.userId()).contains(email)) {
			throw new ApiException(HttpStatus.FORBIDDEN,
					"This invitation is for another email address. Sign in with the account it was sent to.",
					List.of());
		}
		return open;
	}

	Optional<InvitationView> find(String id) {
		return this.repository.find(id);
	}

	/**
	 * A new link, open from now for the days asked, and its line in the history, in one
	 * transaction.
	 * @param workspaceId the workspace it joins
	 * @param request the role, email and days
	 * @param person who sends it
	 * @return the invitation, with its token
	 */
	@Transactional
	InvitationCreated issue(String workspaceId, InvitationCreate request, Person person) {
		String token = Secrets.newSecret(PREFIX);
		String email = (request.email() != null) ? request.email().strip().toLowerCase(Locale.ROOT) : null;
		Instant now = Times.now(this.clock);
		InvitationView invitation = this.repository
			.add(new NewInvitation(Ids.newId(), Secrets.sha256Hex(token), workspaceId, request.role().json(), email,
					person.userId(), now, now.plus(Duration.ofDays(request.expiresInDays()))));
		this.audit.workspace("invitation.create", new WorkspaceTarget(workspaceId, invitation.id()),
				Map.of("role", invitation.role()), person.principal());
		return InvitationCreated.of(invitation, token);
	}

	/**
	 * Marks an invitation accepted, if it is still open.
	 * @param id the invitation
	 * @param userId who accepts it
	 * @return {@code true} when this call claimed it, {@code false} when someone else did
	 * first or it expired meanwhile
	 */
	boolean claim(String id, String userId) {
		return this.repository.claim(id, userId, Times.now(this.clock));
	}

	/**
	 * Revokes an invitation, its line in the history first, in the caller's transaction.
	 * @param invitation the invitation
	 * @param principal who revokes it
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void revoke(InvitationView invitation, Principal principal) {
		this.audit.workspace("invitation.revoke", new WorkspaceTarget(invitation.workspaceId(), invitation.id()),
				Map.of("role", invitation.role()), principal);
		this.repository.delete(invitation.id());
	}

	/**
	 * The answer for a link that cannot be accepted: used, expired, revoked or never
	 * made.
	 * @return the refusal, {@code 404}
	 */
	static ApiException gone() {
		return Access.notFound("This invitation (it may have expired or been used already) was");
	}

}
