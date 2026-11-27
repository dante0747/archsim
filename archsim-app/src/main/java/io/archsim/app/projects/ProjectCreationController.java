package io.archsim.app.projects;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;
import io.archsim.app.limits.EngineRoute;
import io.archsim.app.web.ApiBody;

/**
 * New projects: from a document, an example or blank, and imports of an exported
 * document, in the workspace the request names, else the asker's own.
 */
@RestController
@RequestMapping("/projects")
class ProjectCreationController {

	private final ProjectCreation creation;

	ProjectCreationController(ProjectCreation creation) {
		this.creation = creation;
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
	@EngineRoute
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
	@EngineRoute
	ResponseEntity<ProjectView> importDesign(Principal principal, @ApiBody ObjectNode document,
			@RequestParam(required = false) @Nullable List<String> name, RequestedWorkspace workspace) {
		String last = (name != null && !name.isEmpty()) ? name.getLast() : null;
		ProjectView project = this.creation.importDesign(principal, workspace, document, last);
		return ResponseEntity.status(HttpStatus.CREATED).body(project);
	}

}
