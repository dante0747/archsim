package io.archsim.app.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.archsim.app.web.ApiException;

/**
 * The CSRF token of a browser session. Another site can make a browser send the session
 * cookie, but cannot read the token, so a change a cookie authenticates must carry it in
 * {@value #HEADER}. The token is derived from the session's id, so every instance agrees
 * on it without storing it.
 */
@Component
public class Csrf {

	/** The header a change sends the token in. */
	public static final String HEADER = "X-ArchSim-CSRF";

	/**
	 * The cookie the web app's scripts read the token from (never sent by another site's
	 * requests).
	 */
	public static final String COOKIE = "__Host-archsim_csrf";

	private static final String PURPOSE = "csrf";

	private final SigningKeys keys;

	Csrf(SigningKeys keys) {
		this.keys = keys;
	}

	/**
	 * The token of a session.
	 * @param sessionId the session's id (the SHA-256 of its cookie)
	 * @return the token, base64url without padding
	 */
	public String token(String sessionId) {
		byte[] mac = this.keys.mac(PURPOSE, sessionId.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
	}

	/**
	 * Refuses a change whose header does not carry the session's token.
	 * @param sent the header's value, if any
	 * @param sessionId the session the cookie names
	 * @throws ApiException {@code 403} saying what to send
	 */
	public void check(@Nullable String sent, String sessionId) {
		byte[] expected = token(sessionId).getBytes(StandardCharsets.UTF_8);
		if (sent == null || sent.isEmpty() || !MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), expected)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "Missing or wrong " + HEADER
					+ " header: send the csrfToken from GET /auth/me (or the " + COOKIE + " cookie) with every change",
					List.of());
		}
	}

}
