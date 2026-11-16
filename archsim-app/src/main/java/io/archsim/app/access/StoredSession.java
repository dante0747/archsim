package io.archsim.app.access;

import java.time.Instant;

/**
 * A browser session as stored: its id is the SHA-256 of its cookie.
 *
 * @param id the session's id
 * @param userId whose session it is
 * @param expiresAt when it stops working
 * @param lastSeenAt when a request last used it, give or take a few minutes
 * @param guest whether its account is a sandbox's guest's, which expires
 */
record StoredSession(String id, String userId, Instant expiresAt, Instant lastSeenAt, boolean guest) {

	/**
	 * Whether the session still signs requests in.
	 * @param now the time
	 * @return {@code true} until it expires
	 */
	boolean active(Instant now) {
		return this.expiresAt.isAfter(now);
	}

}
