package io.archsim.app.reviews;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.limits.RateLimit;
import io.archsim.app.limits.RateLimits;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.projects.ProjectService;
import io.archsim.app.web.ApiException;

/**
 * New comments: on a part (a node or an edge, by its id), the project or a run, or a
 * reply to a thread, which joins that thread's part. Viewers comment too; a read-only API
 * token does not.
 */
@Service
class CommentWriter {

	private final ProjectService projects;

	private final CommentAnchors anchors;

	private final CommentRecords records;

	private final RateLimits limits;

	CommentWriter(ProjectService projects, CommentAnchors anchors, CommentRecords records, RateLimits limits) {
		this.projects = projects;
		this.anchors = anchors;
		this.records = records;
		this.limits = limits;
	}

	/**
	 * Writes a comment.
	 * @param principal who writes it
	 * @param projectId the project
	 * @param request what it says and what it is on
	 * @return the comment, with its project
	 * @throws ApiException {@code 403} for a read-only token, {@code 422} for no text or
	 * no part named, {@code 404} for a run or thread of another project, {@code 429}
	 * after many comments in an hour
	 */
	@Transactional
	ProjectComment create(Principal principal, String projectId, CommentCreate request) {
		ProjectDesign project = this.projects.design(principal, projectId, WorkspaceRole.VIEWER);
		principal.requireWritable();
		String text = CommentText.of(request.body());
		CommentAnchors.Anchor anchor = this.anchors.of(project, request);
		this.limits.enforce("user:" + principal.userId() + ":comments", RateLimit.COMMENTS_PER_USER,
				"You have written many comments this hour.");
		return new ProjectComment(this.records.add(project, anchor, text, principal), project);
	}

}
