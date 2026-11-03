package io.archsim.app.runs;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;

/**
 * Seed groups: the runs of one input started together ({@code seeds} above 1), read back
 * together.
 */
@RestController
class RunGroupController {

	private final RunGroups groups;

	RunGroupController(RunGroups groups) {
		this.groups = groups;
	}

	/**
	 * A group's runs, in seed order, and the engine's summary of the finished ones.
	 * @param principal who is asking
	 * @param groupId the group
	 * @return the group
	 */
	@GetMapping("/run-groups/{groupId}")
	RunGroupView get(Principal principal, @PathVariable String groupId) {
		return this.groups.get(principal, groupId);
	}

}
