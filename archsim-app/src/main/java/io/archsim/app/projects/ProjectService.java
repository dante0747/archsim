package io.archsim.app.projects;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.store.Text;
import io.archsim.app.web.ApiException;

/**
 * Projects: saved designs, each in a workspace. A list uses the request's workspace; a
 * project asked for by id answers {@code 404} to anyone without a role in its workspace,
 * and {@code 403} to a role too weak for the request.
 */
@Service
public class ProjectService {

	private static final int MAX_NAME = 120;

	private final ProjectRepository projects;

	private final ProjectChanges changes;

	private final Designs designs;

	private final Access access;

	ProjectService(ProjectRepository projects, ProjectChanges changes, Designs designs, Access access) {
		this.projects = projects;
		this.changes = changes;
		this.designs = designs;
		this.access = access;
	}

	/**
	 * A workspace's projects.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @return its projects, most recently saved first
	 */
	List<ProjectSummary> list(Principal principal, RequestedWorkspace workspace) {
		WorkspaceAccess where = this.access.workspace(principal, workspace);
		return this.projects.inWorkspace(where.workspace().id()).stream().map(ProjectSummary::of).toList();
	}

	ProjectView get(Principal principal, String id) {
		return ProjectView.of(accessible(principal, id, WorkspaceRole.VIEWER));
	}

	/**
	 * Saves a project's new name, description or design, as its next revision.
	 * @param principal who is asking
	 * @param id the project
	 * @param update what changes
	 * @return the project as saved
	 * @throws ApiException {@code 409} when it was saved elsewhere since the revision the
	 * update expects
	 */
	@Transactional
	ProjectView update(Principal principal, String id, ProjectUpdate update) {
		Project project = accessible(principal, id, WorkspaceRole.EDITOR);
		Integer expected = update.expectedRevision();
		if (expected != null && expected != project.revision()) {
			throw ProjectChanges.modifiedElsewhere(project.revision(), expected);
		}
		return ProjectView.of(this.changes.update(project, revision(project, update), principal));
	}

	@Transactional
	void delete(Principal principal, String id) {
		this.changes.delete(accessible(principal, id, WorkspaceRole.EDITOR), principal);
	}

	/**
	 * Copies a project as "Name (copy)", in the same workspace.
	 * @param principal who is asking
	 * @param id the project to copy
	 * @return the copy
	 */
	@Transactional
	ProjectView duplicate(Principal principal, String id) {
		Project project = accessible(principal, id, WorkspaceRole.EDITOR);
		ObjectNode design = this.designs.normalize(project.document().deepCopy(), copyName(project.name()));
		NewProject copy = new NewProject(project.workspaceId(), design, project.description(), null);
		return ProjectView.of(this.changes.create(copy, principal));
	}

	/**
	 * A project as a file to download: its design, and a file name made from its name.
	 * @param principal who is asking
	 * @param id the project
	 * @return the file
	 */
	ExportedProject export(Principal principal, String id) {
		Project project = accessible(principal, id, WorkspaceRole.VIEWER);
		String slug = project.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		return new ExportedProject((slug.isEmpty() ? "architecture" : slug) + ".archsim.json", project.document());
	}

	/**
	 * What an update saves: a new name is the design's name too, and a new design is
	 * normalized first.
	 * @param project the project as stored
	 * @param update what changes
	 * @return the next revision
	 */
	private Revision revision(Project project, ProjectUpdate update) {
		String name = (update.name() != null) ? update.name() : project.name();
		ObjectNode design = project.document();
		if (update.document() != null) {
			design = this.designs.normalize(update.document(), name);
		}
		else if (update.name() != null) {
			design = project.document().deepCopy();
			design.withObjectProperty("metadata").put("name", name);
		}
		String description = (update.description() != null) ? update.description() : project.description();
		int expected = (update.expectedRevision() != null) ? update.expectedRevision() : project.revision();
		return new Revision(name, description, design, expected);
	}

	/**
	 * A project's saved design, for a feature that works on it (a run simulates it).
	 * @param principal who is asking
	 * @param projectId the project
	 * @param minimum the role the work needs
	 * @return the project's id, workspace and design
	 * @throws ApiException {@code 404} without a role in its workspace, {@code 403} with
	 * one too weak
	 */
	public ProjectDesign design(Principal principal, String projectId, WorkspaceRole minimum) {
		Project project = accessible(principal, projectId, minimum);
		return new ProjectDesign(project.id(), project.workspaceId(), project.document());
	}

	/**
	 * Checks that the principal may work on a project, without reading it.
	 * @param principal who is asking
	 * @param projectId the project
	 * @param minimum the role the work needs
	 * @throws ApiException {@code 404} without a role in its workspace, {@code 403} with
	 * one too weak
	 */
	public void requireAccess(Principal principal, String projectId, WorkspaceRole minimum) {
		String what = what(projectId);
		String workspaceId = this.projects.workspaceOf(projectId).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, workspaceId, minimum, what);
	}

	private static String what(String projectId) {
		return "Project '" + projectId + "'";
	}

	private Project accessible(Principal principal, String id, WorkspaceRole minimum) {
		String what = what(id);
		Project project = this.projects.find(id).orElseThrow(() -> Access.notFound(what));
		this.access.require(principal, project.workspaceId(), minimum, what);
		return project;
	}

	/**
	 * A copy's name: the original's and " (copy)", cut to the longest a name may be.
	 * @param name the original's name
	 * @return the copy's name
	 */
	private static String copyName(String name) {
		return Text.cut(name + " (copy)", MAX_NAME);
	}

}
