package io.archsim.app.signin;

import java.util.List;

/**
 * A sandbox just started: its guest as {@code GET /auth/me} describes them, and the
 * cookies that sign the browser in as the guest.
 *
 * @param me the guest, their sandbox and the session's CSRF token
 * @param cookies the {@code Set-Cookie} headers of the session and its CSRF token
 */
record StartedSandbox(Me.View me, List<String> cookies) {

	StartedSandbox {
		cookies = List.copyOf(cookies);
	}

}
