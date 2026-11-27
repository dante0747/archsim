package io.archsim.app.workspaces;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.WorkspaceTarget;
import io.archsim.app.web.ApiException;

/**
 * A workspace's members. Everyone in it sees them; owners change roles and remove
 * members, and anyone may leave. A workspace always keeps an owner, and a personal one
 * its own: changes to roles lock the workspace first, so two owners demoting each other
 * at once cannot both succeed.
 */
@Service
class Members {

	private final MemberRepository repository;

	private final Access access;

	private final AuditLog audit;

	Members(MemberRepository repository, Access access, AuditLog audit) {
		this.repository = repository;
		this.access = access;
		this.audit = audit;
	}

	/**
	 * A workspace's members.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @return its members, the longest-standing first
	 */
	List<MemberView> list(Principal principal, String workspaceId) {
		this.access.workspace(principal, new RequestedWorkspace(workspaceId));
		return this.repository.of(workspaceId);
	}

	/**
	 * Changes a member's role (owners only). The last owner stays an owner.
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @param userId the member
	 * @param role the new role
	 * @return the member
	 * @throws ApiException {@code 409} when the last owner would stop being one
	 */
	@Transactional
	MemberView changeRole(Person person, String workspaceId, String userId, WorkspaceRole role) {
		Principal principal = person.principal();
		lockedFor(principal, workspaceId).require(WorkspaceRole.OWNER);
		MemberView member = member(workspaceId, userId);
		if (WorkspaceRole.OWNER.json().equals(member.role()) && role != WorkspaceRole.OWNER) {
			requireAnotherOwner(workspaceId);
		}
		this.audit.workspace("member.role", new WorkspaceTarget(workspaceId, userId),
				Map.of("from", member.role(), "to", role.json()), principal);
		this.repository.setRole(workspaceId, userId, role.json());
		return member(workspaceId, userId);
	}

	/**
	 * Removes a member (owners only), or leaves (anyone, for themselves).
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @param userId the member
	 * @throws ApiException {@code 409} for a personal workspace's owner or the last owner
	 */
	@Transactional
	void remove(Person person, String workspaceId, String userId) {
		Principal principal = person.principal();
		boolean leaving = userId.equals(principal.userId());
		WorkspaceAccess where = lockedFor(principal, workspaceId);
		where.require(leaving ? WorkspaceRole.VIEWER : WorkspaceRole.OWNER);
		MemberView member = member(workspaceId, userId);
		boolean owner = WorkspaceRole.OWNER.json().equals(member.role());
		if (owner && Workspaces.PERSONAL.equals(where.workspace().kind())) {
			throw ApiException.conflict("A personal workspace keeps its owner.");
		}
		if (owner) {
			requireAnotherOwner(workspaceId);
		}
		this.audit.workspace("member.remove", new WorkspaceTarget(workspaceId, userId),
				Map.of("role", member.role(), "left", leaving), principal);
		this.repository.remove(workspaceId, userId);
	}

	/**
	 * A workspace locked for a change to its members, with the asker's role there. The
	 * lock comes first: under READ COMMITTED the asker's role is then read as the last
	 * change to it left it, so an owner demoted a moment ago no longer acts as one. A
	 * workspace that does not exist is not locked, and the access check answers
	 * {@code 404} for it.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @return the workspace, with the asker's role there
	 * @throws ApiException {@code 404} without a role there
	 */
	private WorkspaceAccess lockedFor(Principal principal, String workspaceId) {
		this.access.lockWorkspace(workspaceId);
		return this.access.workspace(principal, new RequestedWorkspace(workspaceId));
	}

	private MemberView member(String workspaceId, String userId) {
		return this.repository.find(workspaceId, userId).orElseThrow(() -> Access.notFound("Member"));
	}

	private void requireAnotherOwner(String workspaceId) {
		if (this.repository.owners(workspaceId) <= 1) {
			throw ApiException.conflict("A workspace needs an owner: make someone else an owner first.");
		}
	}

}
