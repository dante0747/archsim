package io.archsim.app.access;

/**
 * A workspace a request may use, and the asker's role there.
 *
 * @param workspace the workspace
 * @param role the asker's role in it
 */
public record WorkspaceAccess(WorkspaceRef workspace, WorkspaceRole role) {

	/**
	 * Refuses unless the asker's role allows an action.
	 * @param minimum the role the action needs
	 * @throws io.archsim.app.web.ApiException {@code 403} when the role is weaker
	 */
	public void require(WorkspaceRole minimum) {
		Access.requireRole(this.role, minimum);
	}

}
