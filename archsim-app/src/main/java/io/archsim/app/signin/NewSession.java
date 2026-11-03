package io.archsim.app.signin;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A browser session to store.
 *
 * @param id the SHA-256 of its cookie
 * @param userId whose it is
 * @param createdAt when it starts
 * @param expiresAt when it stops working
 * @param ip the address it was started from
 * @param userAgent the browser that started it, at most 300 characters
 */
record NewSession(String id, String userId, Instant createdAt, Instant expiresAt, @Nullable String ip,
		@Nullable String userAgent) {

}
