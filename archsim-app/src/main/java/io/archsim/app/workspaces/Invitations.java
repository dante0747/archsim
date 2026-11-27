package io.archsim.app.workspaces;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.WorkspaceTarget;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.web.ApiException;

/**
 * A workspace's invitation links, which its owners send, list and revoke. Only team
 * workspaces can be shared.
 */
@Service
class Invitations {

	private final InvitationLinks links;

	private final Workspaces workspaces;

	private final RateLimits limits;

	private final AuditLog audit;

	Invitations(InvitationLinks links, Workspaces workspaces, RateLimits limits, AuditLog audit) {
		this.links = links;
		this.workspaces = workspaces;
		this.limits = limits;
		this.audit = audit;
	}

	/**
	 * A workspace's invitations not yet accepted and not expired.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @return the invitations, newest first
	 */
	List<InvitationView> list(Principal principal, String workspaceId) {
		this.workspaces.owned(principal, workspaceId);
		return this.links.open(workspaceId);
	}

	/**
	 * A new invitation link. The token is in this answer only.
	 * @param person who sends it
	 * @param workspaceId the workspace
	 * @param request the role, email and days it is good for
	 * @return the invitation with its token
	 * @throws ApiException {@code 409} for a workspace that is not a team's, {@code 429}
	 * after many invitations in a day
	 */
	@Transactional
	InvitationCreated create(Person person, String workspaceId, InvitationCreate request) {
		WorkspaceAccess where = this.workspaces.owned(person.principal(), workspaceId);
		if (!Workspaces.TEAM.equals(where.workspace().kind())) {
			throw ApiException.conflict("Only team workspaces can be shared: create one under Settings.");
		}
		this.limits.enforce("ws:" + workspaceId + ":invitations", RateLimit.INVITES_PER_WORKSPACE,
				"This workspace has sent many invitations today.");
		InvitationCreated created = this.links.issue(workspaceId, request, person.userId());
		this.audit.workspace("invitation.create", new WorkspaceTarget(workspaceId, created.id()),
				Map.of("role", created.role()), person.principal());
		return created;
	}

	/**
	 * Revokes an invitation.
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @param invitationId the invitation
	 * @throws ApiException {@code 404} for an invitation of no such workspace
	 */
	@Transactional
	void revoke(Person person, String workspaceId, String invitationId) {
		Principal principal = person.principal();
		this.workspaces.owned(principal, workspaceId);
		InvitationView invitation = this.links.find(invitationId)
			.filter((found) -> found.workspaceId().equals(workspaceId))
			.orElseThrow(() -> Access.notFound("Invitation"));
		this.audit.workspace("invitation.revoke", new WorkspaceTarget(workspaceId, invitationId),
				Map.of("role", invitation.role()), principal);
		this.links.revoke(invitationId);
	}

}
