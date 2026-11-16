package io.archsim.app.signin;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A person, as others see them.
 *
 * @param id the person's user id
 * @param name their name
 * @param email their verified email, if any
 * @param avatarUrl their picture, if any
 * @param guest whether they are a sandbox's guest, who did not sign in
 * @param expiresAt when a guest's account and sandbox are deleted; {@code null} for
 * everyone else
 */
record UserView(String id, String name, @Nullable String email, @Nullable String avatarUrl, boolean guest,
		@Nullable Instant expiresAt) {

	/**
	 * A person who is a guest exactly when their account expires.
	 * @param id the person's user id
	 * @param name their name
	 * @param email their verified email, if any
	 * @param avatarUrl their picture, if any
	 * @param guest whether they are a sandbox's guest
	 * @param expiresAt when a guest's account is deleted
	 */
	UserView {
		if (guest != (expiresAt != null)) {
			throw new IllegalArgumentException("a guest's account, and only a guest's, expires");
		}
	}

}
