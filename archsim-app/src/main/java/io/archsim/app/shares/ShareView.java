package io.archsim.app.shares;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A share link, as the people of its workspace see it.
 *
 * @param id the share's id
 * @param slug the link's id
 * @param path where the page is on the web app: {@code /r/<slug>}
 * @param url the whole link, when the server knows its public address
 * @param title what the page shows
 * @param projectId the project, while it exists
 * @param runId the run, while it exists
 * @param createdBy who made it, while they exist
 * @param createdAt when it was made
 * @param expiresAt when it stops working, if ever
 * @param revokedAt when it was revoked
 * @param viewCount how many times it was opened
 * @param resultDigest the run's result digest
 * @param reportVersion the version of the report it holds
 * @param status {@code active}, {@code expired} or {@code revoked}
 */
record ShareView(String id, String slug, String path, @Nullable String url, String title, @Nullable String projectId,
		@Nullable String runId, @Nullable String createdBy, Instant createdAt, @Nullable Instant expiresAt,
		@Nullable Instant revokedAt, int viewCount, @Nullable String resultDigest, String reportVersion,
		String status) {

}
