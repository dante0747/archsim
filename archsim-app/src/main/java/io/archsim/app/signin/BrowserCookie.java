package io.archsim.app.signin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import io.archsim.app.access.Csrf;
import io.archsim.app.access.Principal;

/**
 * A cookie signing in sets: {@code __Host-} cookies, so {@code Secure}, path {@code /}
 * and no domain, held for the web app's origin. The {@code Set-Cookie} headers are
 * written as the Python API writes them, attributes in its order.
 *
 * @param name the cookie's name
 * @param sameSite {@code lax} or {@code strict}
 * @param httpOnly whether the web app's scripts cannot read it
 */
record BrowserCookie(String name, String sameSite, boolean httpOnly) {

	/** The name of the cookie that carries a sign-in's state. */
	static final String STATE_NAME = "__Host-archsim_oauth";

	/** The signed state that carries a sign-in from the provider back to the callback. */
	static final BrowserCookie STATE = new BrowserCookie(STATE_NAME, "lax", true);

	/** The browser session's secret. */
	static final BrowserCookie SESSION = new BrowserCookie(Principal.SESSION_COOKIE, "lax", true);

	/**
	 * The session's CSRF token: readable by the web app's scripts, never sent by another
	 * site's requests.
	 */
	static final BrowserCookie CSRF = new BrowserCookie(Csrf.COOKIE, "strict", false);

	private static final DateTimeFormatter EXPIRES = DateTimeFormatter
		.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
		.withZone(ZoneOffset.UTC);

	/**
	 * The header that sets the cookie.
	 * @param value its value, which needs no quoting
	 * @param maxAgeSeconds how long the browser keeps it
	 * @return the {@code Set-Cookie} header's value
	 */
	String set(String value, long maxAgeSeconds) {
		return this.name + "=" + value + attributes("Max-Age=" + maxAgeSeconds);
	}

	/**
	 * The header that clears the cookie.
	 * @param now the time, when it expires
	 * @return the {@code Set-Cookie} header's value
	 */
	String cleared(Instant now) {
		return this.name + "=\"\"; expires=" + EXPIRES.format(now) + attributes("Max-Age=0");
	}

	private String attributes(String maxAge) {
		return (this.httpOnly ? "; HttpOnly; " : "; ") + maxAge + "; Path=/; SameSite=" + this.sameSite + "; Secure";
	}

}
