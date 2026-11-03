package io.archsim.app.reviews;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;

/**
 * The history of a project or a workspace, newest first, a page at a time.
 */
@RestController
class ActivityController {

	private static final String BEFORE = "Events older than this id.";

	private final Activity activity;

	ActivityController(Activity activity) {
		this.activity = activity;
	}

	/**
	 * The project's history: edits, runs, comments and sign-offs.
	 * @param principal who is asking
	 * @param projectId the project
	 * @param before only changes older than this id
	 * @param limit how many changes, from 1 to 200
	 * @return the page
	 */
	@GetMapping("/projects/{projectId}/activity")
	ActivityPage ofProject(Principal principal, @PathVariable String projectId,
			@RequestParam(name = "before", required = false) @Parameter(description = BEFORE,
					schema = @Schema(type = "integer", minimum = "1")) @Nullable List<String> before,
			@RequestParam(name = "limit", required = false) @Parameter(schema = @Schema(type = "integer", minimum = "1",
					maximum = "200", defaultValue = "50")) @Nullable List<String> limit) {
		return this.activity.ofProject(principal, projectId, HistoryRange.of(before, limit));
	}

	/**
	 * The workspace's history, including members, invitations and deleted projects.
	 * @param principal who is asking
	 * @param workspaceId the workspace
	 * @param before only changes older than this id
	 * @param limit how many changes, from 1 to 200
	 * @return the page
	 */
	@GetMapping("/workspaces/{workspaceId}/activity")
	ActivityPage ofWorkspace(Principal principal, @PathVariable String workspaceId,
			@RequestParam(name = "before", required = false) @Parameter(description = BEFORE,
					schema = @Schema(type = "integer", minimum = "1")) @Nullable List<String> before,
			@RequestParam(name = "limit", required = false) @Parameter(schema = @Schema(type = "integer", minimum = "1",
					maximum = "200", defaultValue = "50")) @Nullable List<String> limit) {
		return this.activity.ofWorkspace(principal, workspaceId, HistoryRange.of(before, limit));
	}

}
