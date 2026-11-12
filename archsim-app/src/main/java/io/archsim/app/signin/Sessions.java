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
	 * Starts a session for someone who signed in, with a new CSRF token for the browser:
	 * a random value in its cookie, where Spring Security's CSRF filter reads it.
	 * @param userId the person
	 * @param visitor where they signed in from, which the session records
	 * @return the {@code Set-Cookie} headers of the session and its CSRF token
	 */
	List<String> start(String userId, Visitor visitor) {
		String secret = Secrets.newSecret("");
		String id = Secrets.sha256Hex(secret);
		Duration length = Duration.ofDays(this.settings.sessionDays());
		Instant now = Times.now(this.clock);
		this.repository.add(new NewSession(id, userId, now, now.plus(length), visitor.ip(), userAgent(visitor)));
		return List.of(BrowserCookie.SESSION.set(secret, length.toSeconds()),
				BrowserCookie.CSRF.set(Secrets.newSecret(""), length.toSeconds()));
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
