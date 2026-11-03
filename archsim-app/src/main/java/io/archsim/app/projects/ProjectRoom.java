package io.archsim.app.projects;

import java.util.List;
import java.util.OptionalInt;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.archsim.app.access.Access;
import io.archsim.app.limits.WorkspaceLimits;
import io.archsim.app.web.ApiException;

/**
 * Whether a workspace has room for one more project: a kind that caps them (the sandbox)
 * refuses one past the cap. The workspace is locked before its projects are counted, so
 * two requests cannot both take the last place.
 */
@Component
class ProjectRoom {

	private final Access access;

	private final WorkspaceLimits limits;

	private final ProjectRepository projects;

	ProjectRoom(Access access, WorkspaceLimits limits, ProjectRepository projects) {
		this.access = access;
		this.limits = limits;
		this.projects = projects;
	}

	/**
	 * Refuses one more project in a full workspace.
	 * @param workspaceId the workspace
	 * @throws ApiException {@code 409} when it holds as many projects as its kind allows
	 */
	void check(String workspaceId) {
		OptionalInt cap = this.access.lockWorkspace(workspaceId)
			.map((workspace) -> this.limits.maxProjects(workspace.kind()))
			.orElse(OptionalInt.empty());
		if (cap.isPresent() && this.projects.count(workspaceId) >= cap.getAsInt()) {
			throw new ApiException(HttpStatus.CONFLICT,
					"This workspace holds at most %d projects: delete one first.".formatted(cap.getAsInt()), List.of());
		}
	}

}
