package io.archsim.app.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Signs a request in from what it carries: a browser session's cookie or a personal API
 * token ({@code Authorization: Bearer asim_pat_...}). Only the secrets' SHA-256 is
 * stored, so a session or token is revoked at once on every instance by deleting or
 * marking its row. How recently each was used is kept, but written at most every few
 * minutes.
 */
@Component
class Credentials {

	/** How stale a session's last-seen time may get before a request updates it. */
	private static final Duration SESSION_TOUCH = Duration.ofMinutes(5);

	/** How stale a token's last-used time may get before a request updates it. */
	private static final Duration TOKEN_TOUCH = Duration.ofMinutes(1);

	private static final String BEARER = "bearer";

	private final CredentialRepository repository;

	private final Clock clock;

	Credentials(CredentialRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * The user whose session a cookie carries.
	 * @param cookie the session cookie's value
	 * @param ip the address the request comes from
	 * @return the session's id and principal
	 * @throws ApiException {@code 401} when the session is unknown or expired, or its
	 * account cannot sign in
	 */
	SignedSession session(String cookie, @Nullable String ip) {
		Instant now = now();
		StoredSession session = this.repository.session(Secrets.sha256Hex(cookie))
			.filter((stored) -> stored.active(now))
			.orElseThrow(() -> ApiException.unauthorized("Your sign-in has expired: sign in again"));
		requireActive(session.userId());
		if (Duration.between(session.lastSeenAt(), now).compareTo(SESSION_TOUCH) > 0) {
			this.repository.touch(session, now);
		}
		return new SignedSession(session.id(), Principal.session(session.userId(), session.id(), ip));
	}

	/**
	 * The user whose API token an {@code Authorization} header carries.
	 * @param authorization the header's value
	 * @param ip the address the request comes from
	 * @return the principal
	 * @throws ApiException {@code 401} when the header holds no API token, or one that is
	 * unknown, revoked or expired, or whose account cannot sign in
	 */
	Principal token(String authorization, @Nullable String ip) {
		String secret = bearerToken(authorization);
		Instant now = now();
		StoredToken token = this.repository.token(Secrets.sha256Hex(secret))
			.filter((stored) -> stored.active(now))
			.orElseThrow(() -> ApiException
				.unauthorized("This API token is not valid: it was revoked, expired or mistyped"));
		requireActive(token.userId());
		Instant used = token.lastUsedAt();
		if (used == null || Duration.between(used, now).compareTo(TOKEN_TOUCH) > 0) {
			this.repository.touch(token, now);
		}
		return Principal.token(token.userId(), token.grant(), ip);
	}

	private static String bearerToken(String authorization) {
		int space = authorization.indexOf(' ');
		String scheme = (space < 0) ? authorization : authorization.substring(0, space);
		String value = (space < 0) ? "" : authorization.substring(space + 1).strip();
		if (!BEARER.equalsIgnoreCase(scheme) || !value.startsWith(Secrets.TOKEN_PREFIX)) {
			throw ApiException
				.unauthorized("Send an API token as Authorization: Bearer " + Secrets.TOKEN_PREFIX + "...");
		}
		return value;
	}

	private void requireActive(String userId) {
		if (!this.repository.active(userId)) {
			throw ApiException.unauthorized("This account cannot sign in");
		}
	}

	private Instant now() {
		return Times.now(this.clock);
	}

	/**
	 * A request signed in with a browser session.
	 *
	 * @param sessionId the session's id, whose CSRF token a change must carry
	 * @param principal who is asking
	 */
	record SignedSession(String sessionId, Principal principal) {

	}

}
