package io.archsim.app.access;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.web.ApiException;

/**
 * What a principal may do: its role in a workspace is its membership's. Someone without a
 * role in a workspace learns nothing about it, so its workspace, projects and runs answer
 * {@code 404} as if they did not exist; a role too weak for an action answers
 * {@code 403}.
 */
@Service
public class Access {

	/** The workspace every installation has, which the local user owns. */
	public static final String DEFAULT_WORKSPACE_ID = "default";

	private static final String PERSONAL = "personal";

	private final AccessRepository repository;

	Access(AccessRepository repository) {
		this.repository = repository;
	}

	/**
	 * The workspace a list or create request is about: the one it names, else the
	 * principal's own.
	 * @param principal who is asking
	 * @param requested the workspace the request names, if any
	 * @return the workspace and the principal's role there
	 * @throws ApiException {@code 404} when the principal has no role there
	 */
	public WorkspaceAccess workspace(Principal principal, RequestedWorkspace requested) {
		String id = (requested.id() != null) ? requested.id() : defaultWorkspaceId(principal);
		Optional<WorkspaceRef> workspace = this.repository.workspace(id);
		Optional<WorkspaceRole> role = workspace.flatMap((found) -> roleIn(principal, found.id()));
		if (workspace.isEmpty() || role.isEmpty()) {
			throw notFound("Workspace '" + id + "'");
		}
		return new WorkspaceAccess(workspace.get(), role.get());
	}

	/**
	 * A workspace, whoever asks, locked until the transaction ends: requests that count
	 * what it holds before adding to it take turns. For a feature that has already
	 * checked the asker's role there.
	 * @param id the workspace's id
	 * @return the workspace, if it exists
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<WorkspaceRef> lockWorkspace(String id) {
		return this.repository.lockWorkspace(id);
	}

	/**
	 * The principal's role in the workspace something belongs to, if it allows an action.
	 * @param principal who is asking
	 * @param workspaceId the workspace of the thing
	 * @param minimum the role the action needs
	 * @param what the thing, for the {@code 404}: {@code Project 'abc'}
	 * @return the principal's role there
	 * @throws ApiException {@code 404} without a role there, {@code 403} with one too
	 * weak
	 */
	public WorkspaceRole require(Principal principal, String workspaceId, WorkspaceRole minimum, String what) {
		WorkspaceRole role = roleIn(principal, workspaceId).orElseThrow(() -> notFound(what));
		requireRole(role, minimum);
		return role;
	}

	/**
	 * A thing the asker may not see, or that does not exist: the same answer for both.
	 * @param what the thing, such as {@code Project 'abc'}
	 * @return {@code 404} with "… not found"
	 */
	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, what + " not found", List.of());
	}

	/**
	 * Refuses unless a role allows an action.
	 * @param role the asker's role
	 * @param minimum the role the action needs
	 * @throws ApiException {@code 403}, naming both, when the role is weaker
	 */
	static void requireRole(WorkspaceRole role, WorkspaceRole minimum) {
		if (!role.atLeast(minimum)) {
			String article = (role == WorkspaceRole.VIEWER) ? "a" : "an";
			throw new ApiException(HttpStatus.FORBIDDEN, "You are %s %s in this workspace; this needs %s access."
				.formatted(article, role.json(), minimum.json()), List.of());
		}
	}

	private Optional<WorkspaceRole> roleIn(Principal principal, String workspaceId) {
		return this.repository.role(workspaceId, principal.userId()).map(WorkspaceRole::of);
	}

	/**
	 * The workspace a request means when it names none: the principal's personal
	 * workspace, else the default one, else its oldest membership.
	 * @param principal who is asking
	 * @return the workspace's id
	 * @throws ApiException {@code 404} when the principal belongs to no workspace
	 */
	private String defaultWorkspaceId(Principal principal) {
		List<WorkspaceRef> memberships = this.repository.memberships(principal.userId());
		if (memberships.isEmpty()) {
			throw notFound("A workspace you belong to was");
		}
		return memberships.stream()
			.filter((workspace) -> PERSONAL.equals(workspace.kind()))
			.findFirst()
			.or(() -> memberships.stream()
				.filter((workspace) -> DEFAULT_WORKSPACE_ID.equals(workspace.id()))
				.findFirst())
			.orElse(memberships.getFirst())
			.id();
	}

}
