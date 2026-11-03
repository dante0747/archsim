package io.archsim.app.reviews;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A comment as stored.
 *
 * @param id the comment's id
 * @param workspaceId its project's workspace
 * @param projectId its project
 * @param anchorKind what it is on
 * @param anchorId the part or run it is on
 * @param runId the run it is about
 * @param parentId its thread's first comment, for a reply
 * @param authorId who wrote it
 * @param body its text
 * @param createdAt when it was written
 * @param editedAt when it was last changed
 * @param resolvedAt when its thread was resolved
 * @param resolvedBy who resolved it
 */
record StoredComment(String id, String workspaceId, String projectId, String anchorKind, @Nullable String anchorId,
		@Nullable String runId, @Nullable String parentId, @Nullable String authorId, String body, Instant createdAt,
		@Nullable Instant editedAt, @Nullable Instant resolvedAt, @Nullable String resolvedBy) {

	/**
	 * This comment with new text.
	 * @param text the text
	 * @param at when it changed
	 * @return the comment, edited
	 */
	StoredComment edited(String text, Instant at) {
		return new StoredComment(this.id, this.workspaceId, this.projectId, this.anchorKind, this.anchorId, this.runId,
				this.parentId, this.authorId, text, this.createdAt, at, this.resolvedAt, this.resolvedBy);
	}

	/**
	 * This thread resolved or reopened.
	 * @param at when it was resolved, or {@code null} to reopen it
	 * @param by who resolved it, or {@code null}
	 * @return the first comment, changed
	 */
	StoredComment resolved(@Nullable Instant at, @Nullable String by) {
		return new StoredComment(this.id, this.workspaceId, this.projectId, this.anchorKind, this.anchorId, this.runId,
				this.parentId, this.authorId, this.body, this.createdAt, this.editedAt, at, by);
	}

}
