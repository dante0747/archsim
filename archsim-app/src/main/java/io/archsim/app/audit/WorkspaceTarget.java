package io.archsim.app.audit;

import org.jspecify.annotations.Nullable;

/**
 * A workspace, or one of its members or invitations, as the history names it.
 *
 * @param workspaceId the workspace
 * @param targetId the member's user id or the invitation's id; none for the workspace
 * itself
 */
public record WorkspaceTarget(String workspaceId, @Nullable String targetId) {

	/**
	 * The workspace itself.
	 * @param workspaceId the workspace
	 * @return the target
	 */
	public static WorkspaceTarget of(String workspaceId) {
		return new WorkspaceTarget(workspaceId, null);
	}

}
