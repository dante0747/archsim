package io.archsim.app.workspaces;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.WorkspaceTarget;
import io.archsim.app.web.ApiException;

/**
 * Accepting an invitation: anyone with its link joins, except that one with an email can
 * only be accepted by someone signed in with that verified email. Each link is used once.
 */
@Service
class Acceptances {

	private final InvitationLinks links;

	private final MemberRepository members;

	private final Workspaces workspaces;

	private final AuditLog audit;

	Acceptances(InvitationLinks links, MemberRepository members, Workspaces workspaces, AuditLog audit) {
		this.links = links;
		this.members = members;
		this.workspaces = workspaces;
		this.audit = audit;
	}

	/**
	 * What accepting an invitation would do: the workspace, the role and who sent it.
	 * @param principal who is asking
	 * @param token the invitation's token
	 * @return the preview
	 * @throws ApiException {@code 404} for a link used, expired or unknown, {@code 403}
	 * for one sent to another email
	 */
	InvitationPreview preview(Principal principal, String token) {
		OpenInvitation open = this.links.openTo(principal, token);
		InvitationView view = open.view();
		return new InvitationPreview(open.workspaceName(), view.role(), view.email(), view.expiresAt(),
				open.invitedBy());
	}

	/**
	 * Joins the workspace an invitation is for. A member keeps the stronger of the two
	 * roles.
	 * @param person who accepts
	 * @param token the invitation's token
	 * @return the workspace, with the person's role there
	 * @throws ApiException {@code 404} for a link used, expired or unknown, {@code 403}
	 * for one sent to another email
	 */
	@Transactional
	WorkspaceView accept(Person person, String token) {
		InvitationView invitation = this.links.openTo(person.principal(), token).view();
		if (!this.links.claim(invitation.id(), person.userId())) {
			throw InvitationLinks.gone();
		}
		String workspaceId = invitation.workspaceId();
		WorkspaceRole role = admit(workspaceId, person.userId(), WorkspaceRole.of(invitation.role()));
		this.audit.workspace("member.join", new WorkspaceTarget(workspaceId, person.userId()),
				Map.of("role", role.json()), person.principal());
		return this.workspaces.view(workspaceId, role);
	}

	/**
	 * Makes someone a member with the role offered; a member already keeps the stronger
	 * of the role they hold and the one offered.
	 * @param workspaceId the workspace
	 * @param userId who joins
	 * @param offered the invitation's role
	 * @return the role they hold now
	 */
	private WorkspaceRole admit(String workspaceId, String userId, WorkspaceRole offered) {
		Optional<WorkspaceRole> held = this.members.find(workspaceId, userId)
			.map((member) -> WorkspaceRole.of(member.role()));
		if (held.isEmpty()) {
			this.workspaces.join(workspaceId, userId, offered);
			return offered;
		}
		if (held.get().atLeast(offered)) {
			return held.get();
		}
		this.members.setRole(workspaceId, userId, offered.json());
		return offered;
	}

}
