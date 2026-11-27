package io.archsim.app.projects;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Access;
import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.access.WorkspaceAccess;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.web.ApiException;

/**
 * Makes projects in the request's workspace: from a design, a copy of an example, an
 * exported file, or blank.
 */
@Service
public class ProjectCreation {

	private final Access access;

	private final Designs designs;

	private final ProjectChanges changes;

	private final ExampleCopies examples;

	ProjectCreation(Access access, Designs designs, ProjectChanges changes, ExampleCopies examples) {
		this.access = access;
		this.designs = designs;
		this.changes = changes;
		this.examples = examples;
	}

	/**
	 * Makes a project from a design, an example or nothing. With {@code reuseUnedited},
	 * an example and no name, opening the same example twice hands back the copy the
	 * asker made in this workspace the first time, as long as it was never saved and the
	 * example has not changed since; someone else's copy is never handed back.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @param request what to make it from
	 * @return the project, and whether it is an existing copy
	 * @throws ApiException {@code 400} for both a design and an example, {@code 404} for
	 * an unknown example, {@code 409} for a workspace as full as it may be
	 */
	@Transactional
	Created create(Principal principal, RequestedWorkspace workspace, ProjectCreate request) {
		WorkspaceAccess where = editable(principal, workspace);
		if (request.document() != null && request.exampleId() != null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Pass either document or exampleId, not both", List.of());
		}
		String exampleId = request.exampleId();
		if (exampleId == null) {
			ObjectNode document = request.document();
			ObjectNode design = (document != null && !document.isEmpty()) ? document : this.designs.blank();
			return new Created(make(principal, newProject(where, design, request, null)), false);
		}
		ObjectNode example = this.examples.example(exampleId);
		if (request.reuseUnedited() && request.name() == null) {
			Optional<Project> copy = this.examples.uneditedCopy(where.workspace().id(), principal.userId(), exampleId);
			if (copy.isPresent()) {
				return new Created(ProjectView.of(copy.get()), true);
			}
		}
		String source = (request.name() == null) ? exampleId : null;
		return new Created(make(principal, newProject(where, example, request, source)), false);
	}

	/**
	 * Makes a project from a design: one exported earlier, or one a share link shows. An
	 * older schema is upgraded.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @param document the design
	 * @param name the project's name; the design's when {@code null}
	 * @return the project
	 * @throws ApiException {@code 403} for a viewer, {@code 409} for a workspace as full
	 * as it may be
	 */
	@Transactional
	public ProjectView importDesign(Principal principal, RequestedWorkspace workspace, ObjectNode document,
			@Nullable String name) {
		WorkspaceAccess where = editable(principal, workspace);
		ObjectNode design = this.designs.normalize(document, name);
		return make(principal, new NewProject(where.workspace().id(), design, null, null));
	}

	private WorkspaceAccess editable(Principal principal, RequestedWorkspace workspace) {
		WorkspaceAccess where = this.access.workspace(principal, workspace);
		where.require(WorkspaceRole.EDITOR);
		return where;
	}

	private NewProject newProject(WorkspaceAccess where, ObjectNode document, ProjectCreate request,
			@Nullable String sourceExample) {
		ObjectNode design = this.designs.normalize(document, request.name());
		return new NewProject(where.workspace().id(), design, request.description(), sourceExample);
	}

	private ProjectView make(Principal principal, NewProject project) {
		return ProjectView.of(this.changes.create(project, principal));
	}

	/**
	 * A project {@code POST /projects} made, or found.
	 *
	 * @param project the project
	 * @param reused {@code true} when it is an existing, never-saved copy of the example
	 */
	record Created(ProjectView project, boolean reused) {

	}

}
