package io.archsim.app.shares;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A share link as stored.
 *
 * @param id the share's id
 * @param slug the link's id
 * @param workspaceId the workspace it was made in
 * @param projectId the project, while it exists
 * @param runId the run, while it exists
 * @param createdBy who made it, while they exist
 * @param title what the page shows
 * @param payload the frozen copy of the run's results; none once revoked, or when not
 * read
 * @param payloadVersion the version of the copy's shape
 * @param reportVersion the version of the report it holds
 * @param resultDigest the run's result digest
 * @param createdAt when it was made
 * @param expiresAt when it stops working, if ever
 * @param revokedAt when it was revoked
 * @param viewCount how many times it was opened
 */
record StoredShare(String id, String slug, String workspaceId, @Nullable String projectId, @Nullable String runId,
		@Nullable String createdBy, String title, @Nullable ObjectNode payload, int payloadVersion,
		String reportVersion, @Nullable String resultDigest, Instant createdAt, @Nullable Instant expiresAt,
		@Nullable Instant revokedAt, int viewCount) {

	private static final String ACTIVE = "active";

	/**
	 * Whether the link works, was revoked, or expired.
	 * @param now the time it is
	 * @return {@code active}, {@code expired} or {@code revoked}
	 */
	String status(Instant now) {
		if (this.revokedAt != null) {
			return "revoked";
		}
		if (this.expiresAt != null && !this.expiresAt.isAfter(now)) {
			return "expired";
		}
		return ACTIVE;
	}

	/**
	 * Whether the link opens its copy: neither revoked nor expired.
	 * @param now the time it is
	 * @return {@code true} while it works
	 */
	boolean works(Instant now) {
		return ACTIVE.equals(status(now));
	}

	/**
	 * The frozen copy, or an empty one when there is none.
	 * @return the payload
	 */
	ObjectNode payloadOrEmpty() {
		return (this.payload != null) ? this.payload : JsonNodeFactory.instance.objectNode();
	}

	/**
	 * The name of the project when the link was made, else the link's title, as the page
	 * and "Open a copy" name it.
	 * @return the name
	 */
	String sharedProjectName() {
		JsonNode name = payloadOrEmpty().path("projectName");
		return (name.isString() && !name.stringValue().isEmpty()) ? name.stringValue() : this.title;
	}

}
