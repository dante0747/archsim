package io.archsim.app.workspaces;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.web.ApiBody;

/**
 * Workspaces. Everyone sees the workspaces they belong to; anyone signed in makes team
 * workspaces; owners rename and delete them.
 */
@RestController
@RequestMapping("/workspaces")
class WorkspaceController {

	private final Workspaces workspaces;

	private final WorkspaceChanges changes;

	WorkspaceController(Workspaces workspaces, WorkspaceChanges changes) {
		this.workspaces = workspaces;
		this.changes = changes;
	}

	/**
	 * The workspaces you belong to, with your role in each (personal first).
	 * @param principal who is asking
	 * @return the workspaces
	 */
	@GetMapping
	List<WorkspaceView> list(Principal principal) {
		return this.workspaces.of(principal);
	}

	/**
	 * A new team workspace, with you as its owner.
	 * @param person who is asking
	 * @param request its name
	 * @return {@code 201} with the workspace
	 */
	@PostMapping
	ResponseEntity<WorkspaceView> create(Person person, @ApiBody WorkspaceCreate request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.changes.create(person, request));
	}

	@GetMapping("/{workspaceId}")
	WorkspaceView get(Principal principal, @PathVariable String workspaceId) {
		return this.workspaces.seenBy(principal, workspaceId);
	}

	/**
	 * Rename a workspace (owners only, in person: not with an API token or as a sandbox's
	 * guest).
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @param request its new name, if any
	 * @return the workspace
	 */
	@PatchMapping("/{workspaceId}")
	WorkspaceView update(Person person, @PathVariable String workspaceId, @ApiBody WorkspaceUpdate request) {
		return this.changes.update(person, workspaceId, request);
	}

	/**
	 * Delete a team workspace with all its projects and runs (owners only). The default
	 * and personal workspaces stay.
	 * @param person who is asking
	 * @param workspaceId the workspace
	 * @return {@code 204}
	 */
	@DeleteMapping("/{workspaceId}")
	ResponseEntity<Void> delete(Person person, @PathVariable String workspaceId) {
		this.changes.delete(person, workspaceId);
		return ResponseEntity.noContent().build();
	}

}
