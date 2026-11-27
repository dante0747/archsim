package io.archsim.app.projects;

import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import io.archsim.app.access.Access;
import io.archsim.app.access.WorkspaceRef;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.limits.WorkspaceStorage;
import io.archsim.app.web.ApiException;

/**
 * Whether a workspace has room for one more project, and for a design: a kind that caps
 * its projects refuses one past the cap, and a workspace that stores its quota or more
 * takes neither (ADR-0109). The workspace is locked before either is counted, so two
 * requests cannot both take the last place.
 */
@Component
class ProjectRoom {

	private final Access access;

	private final WorkspaceLimits limits;

	private final WorkspaceStorage storage;

	private final ProjectRepository projects;

	ProjectRoom(Access access, WorkspaceLimits limits, WorkspaceStorage storage, ProjectRepository projects) {
		this.access = access;
		this.limits = limits;
		this.storage = storage;
		this.projects = projects;
	}

	/**
	 * Refuses one more project in a full workspace.
	 * @param workspaceId the workspace
	 * @throws ApiException {@code 409} when it holds as many projects as its kind allows,
	 * or stores its quota or more
	 */
	void check(String workspaceId) {
		this.access.lockWorkspace(workspaceId).ifPresent(this::requireRoom);
	}

	/**
	 * Refuses a design in a workspace that stores its quota or more.
	 * @param workspaceId the workspace
	 * @throws ApiException {@code 409} when it stores its quota or more
	 */
	void checkDesign(String workspaceId) {
		this.storage.requireRoomInTurn(workspaceId);
	}

	private void requireRoom(WorkspaceRef workspace) {
		OptionalInt cap = this.limits.maxProjects(workspace.kind());
		if (cap.isPresent() && this.projects.count(workspace.id()) >= cap.getAsInt()) {
			throw ApiException
				.conflict("This workspace holds at most %d projects: delete one first.".formatted(cap.getAsInt()));
		}
		this.storage.requireRoom(workspace.id(), workspace.kind());
	}

}
