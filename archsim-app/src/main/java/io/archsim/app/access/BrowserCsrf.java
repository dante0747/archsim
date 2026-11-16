package io.archsim.app.access;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;

/**
 * The CSRF token of a browser, as Spring Security's CSRF filter keeps it: a random value
 * in a cookie the web app's scripts read, which a change a browser session authenticates
 * sends back in a header. No one else can read the cookie or set it, so no one else can
 * send the header that matches it.
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
	 * {@code GET /auth/me}, and at {@code GET /auth/config} where sandboxes are offered),
	 * lasts the browser's session; signing in, or starting a sandbox, sets its own, for
	 * as long as the session lasts.
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

	/**
	 * How a request hands the CSRF filter its token: in the {@value #HEADER} header only,
	 * as the cookie's value. Spring Security's plain handler also takes a {@code _csrf}
	 * form or query parameter, but a token in a URL ends up in access logs, history and
	 * {@code Referer} headers, and the web app sends the header.
	 * @return the handler
	 */
	public static CsrfTokenRequestHandler headerOnlyHandler() {
		return new HeaderOnlyHandler();
	}

	/**
	 * Spring Security's plain handler, reading the token from its header only.
	 */
	private static final class HeaderOnlyHandler extends CsrfTokenRequestAttributeHandler {

		@Override
		public @Nullable String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			return request.getHeader(csrfToken.getHeaderName());
		}

	}

}
