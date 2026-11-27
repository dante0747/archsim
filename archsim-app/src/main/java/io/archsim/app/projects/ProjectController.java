package io.archsim.app.projects;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.limits.EngineRoute;
import io.archsim.app.web.ApiBody;

/**
 * Projects: saved designs and their export. Lists use the workspace the request names,
 * else the asker's own. New projects are {@link ProjectCreationController}'s.
 */
@RestController
@RequestMapping("/projects")
class ProjectController {

	private final ProjectService projects;

	ProjectController(ProjectService projects) {
		this.projects = projects;
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

	@GetMapping("/{projectId}")
	ProjectView get(Principal principal, @PathVariable String projectId) {
		return this.projects.get(principal, projectId);
	}

	@PutMapping("/{projectId}")
	@EngineRoute
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
	@EngineRoute
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
