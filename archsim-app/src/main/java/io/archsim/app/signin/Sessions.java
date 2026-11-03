package io.archsim.app.signin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.archsim.app.access.Csrf;
import io.archsim.app.access.Secrets;
import io.archsim.app.access.Visitor;
import io.archsim.app.config.SignInProperties;
import io.archsim.app.store.Text;
import io.archsim.app.store.Times;
import io.archsim.app.web.ApiException;

/**
 * Browser sessions: a random secret in an HttpOnly cookie, stored only as its SHA-256,
 * with the session's CSRF token in a cookie the web app's scripts read. Ending a session
 * deletes its row, so it stops working at once on every instance.
 */
@Service
class Sessions {

	/** The longest {@code User-Agent} a session keeps. */
	private static final int MAX_USER_AGENT = 300;

	private final SessionRepository repository;

	private final Csrf csrf;

	private final SignInProperties settings;

	private final Clock clock;

	Sessions(SessionRepository repository, Csrf csrf, SignInProperties settings, Clock clock) {
		this.repository = repository;
		this.csrf = csrf;
		this.settings = settings;
		this.clock = clock;
	}

	/**
	 * Starts a session for someone who signed in.
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
				BrowserCookie.CSRF.set(this.csrf.token(id), length.toSeconds()));
	}

	/**
	 * Ends the session a cookie carries. A cookie of no session is signed out already.
	 * @param cookie the session cookie's value
	 * @param csrfHeader the CSRF header, which ending a session needs like any change
	 * @throws ApiException {@code 403} without the session's CSRF token
	 */
	void end(String cookie, @Nullable String csrfHeader) {
		String id = Secrets.sha256Hex(cookie);
		if (this.repository.userOf(id).isPresent()) {
			this.csrf.check(csrfHeader, id);
			this.repository.delete(id);
		}
	}

	/**
	 * Ends every session of the person whose session a cookie carries.
	 * @param cookie the session cookie's value
	 * @param csrfHeader the CSRF header, which ending a session needs like any change
	 * @throws ApiException {@code 403} without the session's CSRF token
	 */
	void endEverywhere(String cookie, @Nullable String csrfHeader) {
		String id = Secrets.sha256Hex(cookie);
		this.repository.userOf(id).ifPresent((userId) -> {
			this.csrf.check(csrfHeader, id);
			this.repository.deleteAllOf(userId);
		});
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
