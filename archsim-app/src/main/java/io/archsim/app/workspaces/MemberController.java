package io.archsim.app.workspaces;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiBody;

/**
 * A workspace's members and invitations. Everyone in it sees its members; owners change
 * roles, remove members and invite people; anyone may leave.
 */
@RestController
@RequestMapping("/workspaces")
class MemberController {

	private final Members members;

	private final Invitations invitations;

	MemberController(Members members, Invitations invitations) {
		this.members = members;
		this.invitations = invitations;
	}

	@GetMapping("/{workspaceId}/members")
	List<MemberView> members(Principal principal, @PathVariable String workspaceId) {
		return this.members.list(principal, workspaceId);
	}

	/**
	 * Change a member's role (owners only). The last owner stays an owner.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @param userId the member
	 * @param request the new role
	 * @return the member
	 */
	@PatchMapping("/{workspaceId}/members/{userId}")
	MemberView changeRole(Principal principal, @PathVariable String workspaceId, @PathVariable String userId,
			@ApiBody MemberUpdate request) {
		return this.members.changeRole(principal, workspaceId, userId, request.role());
	}

	/**
	 * Remove a member (owners), or leave (anyone, for themselves). The last owner cannot.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @param userId the member
	 * @return {@code 204}
	 */
	@DeleteMapping("/{workspaceId}/members/{userId}")
	ResponseEntity<Void> removeMember(Principal principal, @PathVariable String workspaceId,
			@PathVariable String userId) {
		this.members.remove(principal, workspaceId, userId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Invitations not yet accepted and not expired (owners only).
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @return the invitations, newest first
	 */
	@GetMapping("/{workspaceId}/invitations")
	List<InvitationView> invitations(Principal principal, @PathVariable String workspaceId) {
		return this.invitations.list(principal, workspaceId);
	}

	/**
	 * An invitation link (owners only). The token is in this answer only: share
	 * {@code <web address>/invite/<token>}.
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @param request the role, email and days it is good for
	 * @return {@code 201} with the invitation and its token
	 */
	@PostMapping("/{workspaceId}/invitations")
	ResponseEntity<InvitationCreated> invite(Person person, @PathVariable String workspaceId,
			@ApiBody InvitationCreate request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.invitations.create(person, workspaceId, request));
	}

	@DeleteMapping("/{workspaceId}/invitations/{invitationId}")
	ResponseEntity<Void> revokeInvitation(Principal principal, @PathVariable String workspaceId,
			@PathVariable String invitationId) {
		this.invitations.revoke(principal, workspaceId, invitationId);
		return ResponseEntity.noContent().build();
	}

}
