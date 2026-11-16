package io.archsim.app.workspaces;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.store.Ids;
import io.archsim.app.store.Text;
import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Workspaces: the ones someone belongs to, each with the limits that apply to it, and new
 * ones with their owner.
 */
@Service
public class Workspaces {

	/** The kind of workspace each person has, listed first. */
	public static final String PERSONAL = "personal";

	/** The kind of workspace a visitor tries ArchSim in, as a guest, for a day. */
	public static final String SANDBOX = "sandbox";

	/** The kind of workspace people share. */
	static final String TEAM = "team";

	/** The longest name a workspace has. */
	static final int MAX_NAME = 120;

	private final WorkspaceRepository repository;

	private final WorkspaceLimits limits;

	private final Clock clock;

	private final Access access;

	Workspaces(WorkspaceRepository repository, WorkspaceLimits limits, Clock clock, Access access) {
		this.repository = repository;
		this.limits = limits;
		this.clock = clock;
		this.access = access;
	}

	/**
	 * The workspaces a principal belongs to: just a token's own when it names one, with
	 * the role lowered to viewer for a read-only token.
	 * @param principal who is asking
	 * @return the workspaces, the personal one first, then by name
	 */
	public List<WorkspaceView> of(Principal principal) {
		return this.repository.memberships(principal.userId())
			.stream()
			.filter((membership) -> principal.sees(membership.workspace().id()))
			.map((membership) -> view(membership.workspace(), principal.roleFor(WorkspaceRole.of(membership.role()))))
			.sorted(Comparator.comparing((WorkspaceView view) -> !PERSONAL.equals(view.kind()))
				.thenComparing((view) -> view.name().toLowerCase(Locale.ROOT))
				.thenComparing(WorkspaceView::id))
			.toList();
	}

	/**
	 * A workspace something of it refers to (a project, a run), which therefore exists.
	 * @param id the workspace's id
	 * @return the workspace
	 * @throws IllegalStateException when there is no such workspace
	 */
	public WorkspaceInfo get(String id) {
		return this.repository.find(id)
			.orElseThrow(() -> new IllegalStateException("no workspace " + id + ", which a row refers to"));
	}

	/**
	 * Makes a workspace in the caller's transaction, with its maker as its owner.
	 * @param name its name, cut to 120 characters
	 * @param kind {@code personal}, {@code team} or {@code sandbox}
	 * @param ownerId who makes it
	 * @return the new workspace's id
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public String create(String name, String kind, String ownerId) {
		String id = Ids.newId();
		this.repository.create(new NewWorkspace(id, Text.cut(name, MAX_NAME), kind, ownerId, Times.now(this.clock)));
		return id;
	}

	/**
	 * Deletes, in the caller's transaction, the sandboxes someone made, with everything
	 * in them: memberships, projects, runs (a worker simulating one finds it gone at its
	 * next heartbeat and stops it, as when a team workspace is deleted), share links and
	 * history.
	 * @param userId who made them: a guest
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void deleteSandboxesOf(String userId) {
		this.repository.deleteSandboxesOf(userId);
	}

	/**
	 * Makes someone a member of a workspace, from now on.
	 * @param workspaceId the workspace
	 * @param userId who joins
	 * @param role the role they join with
	 */
	void join(String workspaceId, String userId, WorkspaceRole role) {
		this.repository.addMember(workspaceId, userId, role.json(), Times.now(this.clock));
	}

	/**
	 * A workspace the principal belongs to, as they see it.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @return the workspace, with the principal's role there
	 * @throws ApiException {@code 404} without a role there
	 */
	WorkspaceView seenBy(Principal principal, String workspaceId) {
		return view(workspaceId, this.access.workspace(principal, new RequestedWorkspace(workspaceId)).role());
	}

	/**
	 * A workspace the principal owns.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @return the workspace, with the principal's role there
	 * @throws ApiException {@code 404} without a role there, {@code 403} with a role
	 * other than owner
	 */
	WorkspaceAccess owned(Principal principal, String workspaceId) {
		WorkspaceAccess where = this.access.workspace(principal, new RequestedWorkspace(workspaceId));
		where.require(WorkspaceRole.OWNER);
		return where;
	}

	/**
	 * A workspace, as someone with a role there sees it.
	 * @param workspaceId the workspace
	 * @param role their role
	 * @return the workspace with the limits that apply to it
	 */
	WorkspaceView view(String workspaceId, WorkspaceRole role) {
		return view(get(workspaceId), role);
	}

	/**
	 * A workspace, as someone with a role there sees it.
	 * @param workspace the workspace
	 * @param role their role
	 * @return the workspace with the limits that apply to it
	 */
	WorkspaceView view(WorkspaceInfo workspace, WorkspaceRole role) {
		return new WorkspaceView(workspace.id(), workspace.name(), workspace.kind(), role.json(),
				nullable(this.limits.maxConcurrentRuns(workspace.kind(), workspace.maxConcurrentRuns())),
				nullable(this.limits.runsPerHour(workspace.kind(), workspace.maxRunsPerHour())), workspace.createdAt());
	}

	void rename(String workspaceId, String name) {
		this.repository.rename(workspaceId, name);
	}

	void delete(String workspaceId) {
		this.repository.delete(workspaceId);
	}

	private static @Nullable Integer nullable(OptionalInt limit) {
		return limit.isPresent() ? limit.getAsInt() : null;
	}

}
