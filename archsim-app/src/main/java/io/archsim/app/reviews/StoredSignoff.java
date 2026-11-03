package io.archsim.app.reviews;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A sign-off as stored.
 *
 * @param id the sign-off's id
 * @param workspaceId its project's workspace
 * @param projectId its project
 * @param runId the run it is on, while it exists
 * @param runLabel that run's label
 * @param projectRevision the design's revision it applies to
 * @param inputDigest the run's input digest
 * @param resultDigest the run's result digest
 * @param reviewerId who signed off
 * @param decision {@code approved} or {@code changes_requested}
 * @param note why
 * @param createdAt when
 */
record StoredSignoff(String id, String workspaceId, String projectId, @Nullable String runId, @Nullable String runLabel,
		int projectRevision, @Nullable String inputDigest, @Nullable String resultDigest, @Nullable String reviewerId,
		String decision, @Nullable String note, Instant createdAt) {

}
