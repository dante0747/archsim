package io.archsim.app.access;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A personal API token as stored: only its SHA-256 identifies it.
 *
 * @param id the token's id
 * @param userId whose token it is
 * @param workspaceId the one workspace it is limited to, if any
 * @param scope {@code read} or {@code write}
 * @param expiresAt when it stops working, if ever
 * @param revokedAt when it was revoked, if it was
 * @param lastUsedAt when a request last used it, give or take a minute
 */
record StoredToken(String id, String userId, @Nullable String workspaceId, String scope, @Nullable Instant expiresAt,
		@Nullable Instant revokedAt, @Nullable Instant lastUsedAt) {

	/** The scope of a token that only reads. */
	private static final String READ = "read";

	/**
	 * Whether the token still signs requests in.
	 * @param now the time
	 * @return {@code true} unless it was revoked or has expired
	 */
	boolean active(Instant now) {
		return this.revokedAt == null && (this.expiresAt == null || this.expiresAt.isAfter(now));
	}

	/**
	 * What the token allows.
	 * @return its grant
	 */
	TokenGrant grant() {
		return new TokenGrant(this.workspaceId, READ.equals(this.scope));
	}

}
