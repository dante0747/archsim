package io.archsim.app.reviews;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.archsim.app.access.Access;
import io.archsim.app.projects.ProjectDesign;
import io.archsim.app.runs.RunLookup;
import io.archsim.app.web.ApiException;

/**
 * What a new comment is on: a reply joins its thread's part; otherwise the part, run or
 * project the request names. A run it names must be one of the project's.
 */
@Component
class CommentAnchors {

	private final CommentRepository comments;

	private final RunLookup runs;

	CommentAnchors(CommentRepository comments, RunLookup runs) {
		this.comments = comments;
		this.runs = runs;
	}

	/**
	 * Where a new comment goes.
	 * @param project its project
	 * @param request what the request says it is on
	 * @return the comment's place
	 * @throws ApiException {@code 404} for a thread or run of another project,
	 * {@code 422} for a part or run not named
	 */
	Anchor of(ProjectDesign project, CommentCreate request) {
		String parentId = request.parentId();
		if (parentId != null) {
			return reply(project, parentId);
		}
		AnchorKind kind = request.anchorKind();
		String anchorId = request.anchorId();
		String runId = request.runId();
		if (kind == AnchorKind.PROJECT) {
			anchorId = null;
		}
		else if (blank(anchorId) && (kind != AnchorKind.RUN || blank(runId))) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Say which " + kind.json() + " the comment is on",
					List.of());
		}
		else if (kind == AnchorKind.RUN) {
			runId = blank(anchorId) ? runId : anchorId;
			anchorId = runId;
		}
		if (runId != null) {
			requireRun(project, runId);
		}
		return new Anchor(kind.json(), anchorId, runId, null);
	}

	private Anchor reply(ProjectDesign project, String parentId) {
		StoredComment parent = this.comments.find(parentId)
			.filter((found) -> found.projectId().equals(project.id()))
			.orElseThrow(
					() -> new ApiException(HttpStatus.NOT_FOUND, "The comment to reply to was not found", List.of()));
		String rootId = parent.parentId();
		StoredComment root = (rootId != null)
				? this.comments.find(rootId)
					.orElseThrow(() -> new IllegalStateException("no comment " + rootId + ", which a reply refers to"))
				: parent;
		return new Anchor(root.anchorKind(), root.anchorId(), root.runId(), root.id());
	}

	private void requireRun(ProjectDesign project, String runId) {
		if (!this.runs.inProject(runId, project.id())) {
			throw Access.notFound("Run '" + runId + "' of this project");
		}
	}

	private static boolean blank(@Nullable String id) {
		return id == null || id.isEmpty();
	}

	/**
	 * Where a comment goes.
	 *
	 * @param kind {@code node}, {@code edge}, {@code project} or {@code run}
	 * @param anchorId the part or run it is on
	 * @param runId the run it is about
	 * @param parentId its thread's first comment, for a reply
	 */
	record Anchor(String kind, @Nullable String anchorId, @Nullable String runId, @Nullable String parentId) {

	}

}
