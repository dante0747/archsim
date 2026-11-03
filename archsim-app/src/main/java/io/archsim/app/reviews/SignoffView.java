package io.archsim.app.reviews;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A sign-off on a run, as the asker sees it.
 *
 * @param id the sign-off's id
 * @param projectId its project
 * @param runId the run it is on, while it exists
 * @param runLabel that run's label
 * @param projectRevision the design's revision it applies to
 * @param currentRevision the design's revision now
 * @param stale whether the design was saved again since
 * @param inputDigest the run's input digest
 * @param resultDigest the run's result digest
 * @param reviewer who signed off, if they are still here
 * @param decision {@code approved} or {@code changes_requested}
 * @param note why
 * @param createdAt when
 * @param canWithdraw whether the asker may withdraw it
 */
record SignoffView(String id, String projectId, @Nullable String runId, @Nullable String runLabel, int projectRevision,
		int currentRevision, boolean stale, @Nullable String inputDigest, @Nullable String resultDigest,
		@Nullable PersonView reviewer, String decision, @Nullable String note, Instant createdAt, boolean canWithdraw) {

}
