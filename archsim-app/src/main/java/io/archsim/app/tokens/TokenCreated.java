package io.archsim.app.tokens;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A token just made, with its secret: shown in this answer only.
 *
 * @param id the token's id
 * @param name what it is for
 * @param prefix its first characters
 * @param scope {@code read} or {@code write}
 * @param workspaceId the one workspace it is limited to, if any
 * @param createdAt when it was made
 * @param lastUsedAt never, yet
 * @param expiresAt when it stops working, if ever
 * @param revokedAt never, yet
 * @param token the secret to send as {@code Authorization: Bearer}
 */
record TokenCreated(String id, String name, String prefix, String scope, @Nullable String workspaceId,
		Instant createdAt, @Nullable Instant lastUsedAt, @Nullable Instant expiresAt, @Nullable Instant revokedAt,
		String token) {

	/**
	 * A token with its secret.
	 * @param view the token
	 * @param token its secret
	 * @return the answer
	 */
	static TokenCreated of(TokenView view, String token) {
		return new TokenCreated(view.id(), view.name(), view.prefix(), view.scope(), view.workspaceId(),
				view.createdAt(), view.lastUsedAt(), view.expiresAt(), view.revokedAt(), token);
	}

}
