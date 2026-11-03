package io.archsim.app.reviews;

import java.util.List;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.archsim.app.access.Principal;

/**
 * A project's comments, in threads. A comment whose part was deleted stays, marked
 * detached.
 */
@RestController
class CommentListController {

	private final CommentThreads threads;

	CommentListController(CommentThreads threads) {
		this.threads = threads;
	}

	/**
	 * The project's comments, oldest first (replies carry {@code parentId}), optionally
	 * only those on one part, run or the project. {@code detached} marks comments whose
	 * part or run is gone from the saved design.
	 * @param principal who is asking
	 * @param projectId the project
	 * @param query the query string: {@code anchorKind}, {@code anchorId} and
	 * {@code runId}, read from it alone
	 * @return the comments
	 */
	@GetMapping("/projects/{projectId}/comments")
	@Parameter(name = "anchorKind", in = ParameterIn.QUERY, description = "Only comments on this kind of thing",
			schema = @Schema(type = "string", allowableValues = { "node", "edge", "project", "run" }))
	@Parameter(name = "anchorId", in = ParameterIn.QUERY, description = "Only comments on this part or run",
			schema = @Schema(type = "string", maxLength = CommentCreate.MAX_ANCHOR_ID))
	@Parameter(name = "runId", in = ParameterIn.QUERY, description = "Only comments about this run",
			schema = @Schema(type = "string", maxLength = CommentCreate.MAX_ID))
	List<CommentView> list(Principal principal, @PathVariable String projectId,
			@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
		return this.threads.list(principal, projectId, CommentFilter.of(query));
	}

}
