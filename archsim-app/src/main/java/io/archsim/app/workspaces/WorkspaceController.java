package io.archsim.app.workspaces;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;

/**
 * Workspaces and their members.
 */
@RestController
@RequestMapping("/workspaces")
class WorkspaceController {

	private final Workspaces workspaces;

	WorkspaceController(Workspaces workspaces) {
		this.workspaces = workspaces;
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

}
