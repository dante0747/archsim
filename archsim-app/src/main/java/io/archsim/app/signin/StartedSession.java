package io.archsim.app.signin;

import java.util.List;

/**
 * A browser session just started, and what the browser is sent for it.
 *
 * @param id the session's id, the SHA-256 of its cookie
 * @param csrfToken the session's CSRF token, which its changes send back
 * @param cookies the {@code Set-Cookie} headers of the session and its CSRF token
 */
record StartedSession(String id, String csrfToken, List<String> cookies) {

	StartedSession {
		cookies = List.copyOf(cookies);
	}

}
