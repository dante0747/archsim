package io.archsim.app.signin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Secrets;
import io.archsim.app.access.Visitor;
import io.archsim.app.config.SignInProperties;
import io.archsim.app.store.Text;
import io.archsim.app.store.Times;

/**
 * Browser sessions: a random secret in an HttpOnly cookie, stored only as its SHA-256.
 * Ending a session deletes its row, so it stops working at once on every instance. Its
 * CSRF token is Spring Security's ({@link io.archsim.app.access.BrowserCsrf}), whose
 * filter checks every change the session makes, signing out included.
 */
@Service
class Sessions {

	/** The longest {@code User-Agent} a session keeps. */
	private static final int MAX_USER_AGENT = 300;

	private final SessionRepository repository;

	private final SignInProperties settings;

	private final Clock clock;

	Sessions(SessionRepository repository, SignInProperties settings, Clock clock) {
		this.repository = repository;
		this.settings = settings;
		this.clock = clock;
	}

	/**
	 * Starts a session for someone who signed in, for as long as a sign-in lasts
	 * ({@code ARCHSIM_SESSION_DAYS}).
	 * @param userId the person
	 * @param visitor where they signed in from, which the session records
	 * @return the session, with the {@code Set-Cookie} headers of it and its CSRF token
	 */
	StartedSession start(String userId, Visitor visitor) {
		Instant now = Times.now(this.clock);
		return create(userId, visitor, now, now.plus(Duration.ofDays(this.settings.sessionDays())));
	}

	/**
	 * Starts a session that lasts until a given time: a guest's ends with their sandbox.
	 * @param userId the person
	 * @param visitor where they signed in from, which the session records
	 * @param expiresAt when the session stops working
	 * @return the session, with the {@code Set-Cookie} headers of it and its CSRF token
	 */
	StartedSession startUntil(String userId, Visitor visitor, Instant expiresAt) {
		return create(userId, visitor, Times.now(this.clock), expiresAt);
	}

	/**
	 * Starts a session, with a new CSRF token for the browser: a random value in its
	 * cookie, where Spring Security's CSRF filter reads it.
	 * @param userId the person
	 * @param visitor where they signed in from, which the session records
	 * @param now the time it starts
	 * @param expiresAt when it stops working
	 * @return the session, with the {@code Set-Cookie} headers of it and its CSRF token
	 */
	private StartedSession create(String userId, Visitor visitor, Instant now, Instant expiresAt) {
		String secret = Secrets.newSecret("");
		String id = Secrets.sha256Hex(secret);
		String csrfToken = Secrets.newSecret("");
		long seconds = wholeSeconds(Duration.between(now, expiresAt));
		this.repository.add(new NewSession(id, userId, now, expiresAt, visitor.ip(), userAgent(visitor)));
		return new StartedSession(id, csrfToken,
				List.of(BrowserCookie.SESSION.set(secret, seconds), BrowserCookie.CSRF.set(csrfToken, seconds)));
	}

	/**
	 * How long a cookie lasts, in seconds: the session's length rounded up, so the cookie
	 * never goes before its session, which the server ends on time anyway.
	 * @param length how long the session lasts
	 * @return whole seconds
	 */
	private static long wholeSeconds(Duration length) {
		return length.toSeconds() + ((length.toNanosPart() > 0) ? 1 : 0);
	}

	/**
	 * Whether a cookie signs someone in now.
	 * @param cookie the session cookie's value, if the browser sent one
	 * @return {@code true} for a session that exists and has not expired
	 */
	boolean signsIn(@Nullable String cookie) {
		if (cookie == null || cookie.isEmpty()) {
			return false;
		}
		return this.repository.userOf(Secrets.sha256Hex(cookie), Times.now(this.clock)).isPresent();
	}

	/**
	 * Ends the session a cookie carries. A cookie of no session is signed out already.
	 * @param cookie the session cookie's value
	 */
	void end(String cookie) {
		this.repository.delete(Secrets.sha256Hex(cookie));
	}

	/**
	 * Ends every session of the person whose session a cookie carries, while it lasts: an
	 * expired session signs no one in, so the CSRF filter asked it for no token, and it
	 * ends no other session.
	 * @param cookie the session cookie's value
	 */
	void endEverywhere(String cookie) {
		this.repository.userOf(Secrets.sha256Hex(cookie), Times.now(this.clock))
			.ifPresent(this.repository::deleteAllOf);
	}

	/**
	 * The headers that clear a browser's session and CSRF cookies.
	 * @return the {@code Set-Cookie} headers
	 */
	List<String> clearCookies() {
		Instant now = this.clock.instant();
		return List.of(BrowserCookie.SESSION.cleared(now), BrowserCookie.CSRF.cleared(now));
	}

	private static @Nullable String userAgent(Visitor visitor) {
		String agent = visitor.userAgent();
		if (agent == null || agent.isEmpty()) {
			return null;
		}
		return Text.cut(agent, MAX_USER_AGENT);
	}

}
