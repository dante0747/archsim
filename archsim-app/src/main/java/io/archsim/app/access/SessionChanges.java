package io.archsim.app.access;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The requests that need a CSRF token: changes a browser session authenticates. A request
 * that only reads (as Spring Security's CSRF filter tells them) needs none, and neither
 * does one an API token or nobody signs in, so the session is read only for a change that
 * carries its cookie.
 */
public final class SessionChanges implements RequestMatcher {

	private final SecurityContextHolderStrategy contexts;

	/**
	 * A matcher that reads the request's browser session from the security context.
	 * @param contexts where Spring Security keeps the request's context
	 */
	public SessionChanges(SecurityContextHolderStrategy contexts) {
		this.contexts = contexts;
	}

	@Override
	public boolean matches(HttpServletRequest request) {
		if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) || BrowserSessions.sessionCookie(request).isEmpty()) {
			return false;
		}
		Authentication authentication = this.contexts.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof BrowserSession;
	}

	@Override
	public String toString() {
		return "changes a browser session signs in";
	}

}
