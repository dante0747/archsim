package io.archsim.app.workspaces;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import io.archsim.app.access.Access;
import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.audit.AuditLog;
import io.archsim.app.audit.WorkspaceTarget;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.store.Text;
import io.archsim.app.web.ApiException;

/**
 * Team workspaces: made by anyone signed in, who owns them; renamed and deleted by their
 * owners. The default and personal workspaces stay.
 */
@Service
class WorkspaceChanges {

	/** The name of a team workspace made with a blank one. */
	private static final String DEFAULT_NAME = "Team";

	private final Workspaces workspaces;

	private final RateLimits limits;

	private final AuditLog audit;

	private final TransactionOperations transactions;

	WorkspaceChanges(Workspaces workspaces, RateLimits limits, AuditLog audit, TransactionOperations transactions) {
		this.workspaces = workspaces;
		this.limits = limits;
		this.audit = audit;
		this.transactions = transactions;
	}

	/**
	 * A new team workspace, with its maker as its owner. The person's limit is charged
	 * before the workspace's transaction begins, so the request holds one connection at a
	 * time (ADR-0105).
	 * @param person who makes it
	 * @param request its name
	 * @return the workspace
	 * @throws ApiException {@code 429} after many workspaces in a day
	 */
	WorkspaceView create(Person person, WorkspaceCreate request) {
		this.limits.enforce("user:" + person.userId() + ":workspaces", RateLimit.WORKSPACES_PER_USER,
				"You have made many workspaces today.");
		String name = request.name().strip();
		// one transaction, written out: the charge above must commit before it
		// begins, and a call to a private method would not go through the proxy
		return Objects.requireNonNull(
				this.transactions.execute((status) -> createTeam(name.isEmpty() ? DEFAULT_NAME : name, person)));
	}

	// the workspace, its owner and its line in the history
	private WorkspaceView createTeam(String name, Person person) {
		String id = this.workspaces.create(name, Workspaces.TEAM, person.userId());
		WorkspaceView made = this.workspaces.view(id, WorkspaceRole.OWNER);
		this.audit.workspace("workspace.create", WorkspaceTarget.of(id), Map.of("name", made.name()),
				person.principal());
		return made;
	}

	/**
	 * Renames a workspace (owners only).
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @param request its new name, if any
	 * @return the workspace
	 */
	@Transactional
	WorkspaceView update(Person person, String workspaceId, WorkspaceUpdate request) {
		Principal principal = person.principal();
		WorkspaceAccess where = this.workspaces.owned(principal, workspaceId);
		String requested = request.name();
		if (requested != null) {
			String name = Text.cut(requested.strip(), Workspaces.MAX_NAME);
			String kept = name.isEmpty() ? this.workspaces.get(workspaceId).name() : name;
			this.workspaces.rename(workspaceId, kept);
			this.audit.workspace("workspace.rename", WorkspaceTarget.of(workspaceId), Map.of("name", kept), principal);
		}
		return this.workspaces.view(workspaceId, where.role());
	}

	/**
	 * Deletes a team workspace with all its projects and runs (owners only).
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @throws ApiException {@code 409} for the default workspace or one not a team's
	 */
	@Transactional
	void delete(Person person, String workspaceId) {
		WorkspaceAccess where = this.workspaces.owned(person.principal(), workspaceId);
		if (Access.DEFAULT_WORKSPACE_ID.equals(workspaceId) || !Workspaces.TEAM.equals(where.workspace().kind())) {
			throw ApiException.conflict("Only team workspaces you created can be deleted.");
		}
		this.workspaces.delete(workspaceId);
	}

}
