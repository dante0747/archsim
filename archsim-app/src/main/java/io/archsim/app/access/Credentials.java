package io.archsim.app.access;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Signs a request in from its browser session's cookie. Only the secret's SHA-256 is
 * stored, so a session is ended at once on every instance by deleting its row. How
 * recently it was used is kept, but written at most every five minutes. API tokens are
 * Spring Security's bearer token filter's ({@link ApiTokenIntrospector}).
 */
@Component
class Credentials {

	/** How stale a session's last-seen time may get before a request updates it. */
	private static final Duration SESSION_TOUCH = Duration.ofMinutes(5);

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
			.orElseThrow(() -> ApiException.unauthorized(SignInRefusals.SESSION_EXPIRED));
		requireActive(session.userId());
		if (Duration.between(session.lastSeenAt(), now).compareTo(SESSION_TOUCH) > 0) {
			this.repository.touch(session, now);
		}
		return new SignedSession(session.id(), Principal.session(session.userId(), session.id(), ip));
	}

	private void requireActive(String userId) {
		if (!this.repository.active(userId)) {
			throw ApiException.unauthorized(SignInRefusals.ACCOUNT_DISABLED);
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
