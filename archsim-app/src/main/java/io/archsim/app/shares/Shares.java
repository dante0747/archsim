package io.archsim.app.shares;

import java.util.List;

import org.springframework.stereotype.Service;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectService;

/**
 * The share links of a project or a workspace, which anyone there may list, newest first,
 * revoked and expired ones included.
 */
@Service
class Shares {

	private final ShareRepository shares;

	private final ProjectService projects;

	private final Access access;

	Shares(ShareRepository shares, ProjectService projects, Access access) {
		this.shares = shares;
		this.projects = projects;
		this.access = access;
	}

	List<StoredShare> ofProject(Principal principal, String projectId) {
		this.projects.requireAccess(principal, projectId, WorkspaceRole.VIEWER);
		return this.shares.ofProject(projectId);
	}

	/**
	 * Every share link of a workspace, including those whose project was deleted (they
	 * keep working until they expire or are revoked).
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @return the links
	 */
	List<StoredShare> ofWorkspace(Principal principal, RequestedWorkspace workspace) {
		String workspaceId = this.access.workspace(principal, workspace).workspace().id();
		return this.shares.ofWorkspace(workspaceId);
	}

}
