package io.archsim.app.access;

import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * The CSRF token of a browser, as Spring Security's CSRF filter keeps it: a random value
 * in a cookie the web app's scripts read, which a change a browser session authenticates
 * sends back in a header. Another site can make a browser send the cookie, but cannot
 * read it, so it cannot send the header.
 */
public final class BrowserCsrf {

	/** The header a change sends the token in. */
	public static final String HEADER = "X-ArchSim-CSRF";

	/**
	 * The cookie the token is kept in: readable by the web app's scripts, never sent by
	 * another site's requests.
	 */
	public static final String COOKIE = "__Host-archsim_csrf";

	private BrowserCsrf() {
	}

	/**
	 * Where the tokens are kept: the {@value #COOKIE} cookie, a {@code __Host-} cookie,
	 * so {@code Secure}, on path {@code /} and with no domain, and
	 * {@code SameSite=strict}. A cookie this repository sets, when a browser has none (at
	 * {@code GET /auth/me}), lasts the browser's session; signing in sets its own, for as
	 * long as the session lasts.
	 * @return the repository
	 */
	public static CsrfTokenRepository tokens() {
		CookieCsrfTokenRepository tokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
		tokens.setCookieName(COOKIE);
		tokens.setHeaderName(HEADER);
		tokens.setCookiePath("/");
		tokens.setCookieCustomizer((cookie) -> cookie.secure(true).sameSite("strict"));
		return tokens;
	}

}
