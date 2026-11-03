package io.archsim.app.shares;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.projects.ProjectCreation;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.projects.ProjectView;
import io.archsim.app.web.ApiException;

/**
 * "Open a copy": a new project, in the asker's workspace, from the design a share link
 * shows (the exact design that was simulated).
 */
@Service
class ShareCopies {

	private final PublicShares shares;

	private final Access access;

	private final ProjectCreation projects;

	ShareCopies(PublicShares shares, Access access, ProjectCreation projects) {
		this.shares = shares;
		this.access = access;
		this.projects = projects;
	}

	/**
	 * Copies a shared design into a new project, named as a copy of the shared project
	 * unless the request names it.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @param request the link and the new project's name
	 * @return the project
	 * @throws ApiException {@code 404} for a link that does not work or holds no design,
	 * {@code 403} for a viewer, {@code 409} for a workspace as full as it may be
	 */
	@Transactional
	ProjectView copy(Principal principal, RequestedWorkspace workspace, CopyFromShare request) {
		this.access.workspace(principal, workspace).require(WorkspaceRole.EDITOR);
		StoredShare share = this.shares.active(request.slug());
		JsonNode document = share.payloadOrEmpty().path("document");
		if (!(document instanceof ObjectNode design)) {
			throw Access.notFound("A design in this share link was");
		}
		String name = (request.name() != null) ? request.name() : ProjectService.copyName(share.sharedProjectName());
		return this.projects.importDesign(principal, workspace, design.deepCopy(), name);
	}

}
