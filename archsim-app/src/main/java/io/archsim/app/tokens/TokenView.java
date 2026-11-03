package io.archsim.app.tokens;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A token, without its secret.
 *
 * @param id the token's id
 * @param name what it is for
 * @param prefix its first characters, to tell tokens apart
 * @param scope {@code read} or {@code write}
 * @param workspaceId the one workspace it is limited to, if any
 * @param createdAt when it was made
 * @param lastUsedAt when a request last used it, give or take a minute
 * @param expiresAt when it stops working, if ever
 * @param revokedAt when it was revoked, if it was
 */
record TokenView(String id, String name, String prefix, String scope, @Nullable String workspaceId, Instant createdAt,
		@Nullable Instant lastUsedAt, @Nullable Instant expiresAt, @Nullable Instant revokedAt) {

}
