package io.archsim.app.reviews;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.MaxLength;
import io.archsim.model.codec.MinLength;

/**
 * What {@code POST /projects/{id}/comments} takes.
 *
 * @param anchorKind what the comment is on
 * @param anchorId the node or edge id in the design, or the run id (none for the project)
 * @param runId the run this comment is about or was written next to
 * @param parentId reply to this comment's thread
 * @param body the text
 */
@IgnoreUnknownKeys
record CommentCreate(@Default("project") AnchorKind anchorKind, @MaxLength(MAX_ANCHOR_ID) @Nullable String anchorId,
		@MaxLength(MAX_ID) @Nullable String runId, @MaxLength(MAX_ID) @Nullable String parentId,
		@MinLength(1) @MaxLength(MAX_BODY) String body) {

	/** The longest part or run id a comment names. */
	static final int MAX_ANCHOR_ID = 64;

	/** The longest run or comment id a comment names. */
	static final int MAX_ID = 32;

	/** The longest comment. */
	static final int MAX_BODY = 4000;

}
