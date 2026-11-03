package io.archsim.app.projects;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.web.ApiBody;

/**
 * Projects: saved designs, their import and export. Lists and new projects use the
 * workspace the request names, else the asker's own.
 */
@RestController
@RequestMapping("/projects")
class ProjectController {

	private final ProjectService projects;

	private final ProjectCreation creation;

	ProjectController(ProjectService projects, ProjectCreation creation) {
		this.projects = projects;
		this.creation = creation;
	}

	/**
	 * The workspace's projects, most recently updated first.
	 * @param principal who is asking
	 * @param workspace the workspace the request names
	 * @return the projects
	 */
	@GetMapping
	List<ProjectSummary> list(Principal principal, RequestedWorkspace workspace) {
		return this.projects.list(principal, workspace);
	}

	/**
	 * Create a project in the workspace from a document, an example ({@code exampleId})
	 * or blank.
	 * @param principal who is asking
	 * @param request what to make it from
	 * @param workspace the workspace the request names
	 * @return {@code 201} with the project, or {@code 200} with an unedited copy of the
	 * example
	 */
	@PostMapping
	ResponseEntity<ProjectView> create(Principal principal, @ApiBody ProjectCreate request,
			RequestedWorkspace workspace) {
		ProjectCreation.Created created = this.creation.create(principal, workspace, request);
		return ResponseEntity.status(created.reused() ? HttpStatus.OK : HttpStatus.CREATED).body(created.project());
	}

	/**
	 * Import a document exported earlier into the workspace (older schema versions are
	 * migrated).
	 * @param principal who is asking
	 * @param document a versioned ArchSim document
	 * @param name the project's name, the last one given as FastAPI reads a repeated
	 * parameter; the document's when not given
	 * @param workspace the workspace the request names
	 * @return the project
	 */
	@PostMapping("/import")
	ResponseEntity<ProjectView> importDesign(Principal principal, @ApiBody ObjectNode document,
			@RequestParam(required = false) @Nullable List<String> name, RequestedWorkspace workspace) {
		String last = (name != null && !name.isEmpty()) ? name.getLast() : null;
		ProjectView project = this.creation.importDesign(principal, workspace, document, last);
		return ResponseEntity.status(HttpStatus.CREATED).body(project);
	}

	@GetMapping("/{projectId}")
	ProjectView get(Principal principal, @PathVariable String projectId) {
		return this.projects.get(principal, projectId);
	}

	@PutMapping("/{projectId}")
	ProjectView update(Principal principal, @PathVariable String projectId, @ApiBody ProjectUpdate update) {
		return this.projects.update(principal, projectId, update);
	}

	@DeleteMapping("/{projectId}")
	ResponseEntity<Void> delete(Principal principal, @PathVariable String projectId) {
		this.projects.delete(principal, projectId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Copy as "Name (copy)", in the same workspace.
	 * @param principal who is asking
	 * @param projectId the project to copy
	 * @return the copy
	 */
	@PostMapping("/{projectId}/duplicate")
	ResponseEntity<ProjectView> duplicate(Principal principal, @PathVariable String projectId) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.projects.duplicate(principal, projectId));
	}

	/**
	 * Download the project as a versioned JSON document.
	 * @param principal who is asking
	 * @param projectId the project
	 * @return the document, as an attachment
	 */
	@GetMapping("/{projectId}/export")
	ResponseEntity<ObjectNode> export(Principal principal, @PathVariable String projectId) {
		return this.projects.export(principal, projectId).answer();
	}

}
