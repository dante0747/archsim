package io.archsim.app.reviews;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A comment, as the asker sees it.
 *
 * @param id the comment's id
 * @param projectId its project
 * @param anchorKind what it is on: {@code node}, {@code edge}, {@code project} or
 * {@code run}
 * @param anchorId the part or run it is on
 * @param runId the run it is about or was written next to
 * @param parentId the first comment of its thread, for a reply
 * @param author who wrote it, if they are still here
 * @param body its text
 * @param createdAt when it was written
 * @param editedAt when it was last changed
 * @param resolvedAt when its thread was resolved
 * @param resolvedBy who resolved it
 * @param detached whether the part or run it is about is gone from the saved design
 * @param canEdit whether the asker may change its text
 * @param canDelete whether the asker may delete it
 */
record CommentView(String id, String projectId, String anchorKind, @Nullable String anchorId, @Nullable String runId,
		@Nullable String parentId, @Nullable PersonView author, String body, Instant createdAt,
		@Nullable Instant editedAt, @Nullable Instant resolvedAt, @Nullable PersonView resolvedBy, boolean detached,
		boolean canEdit, boolean canDelete) {

}
