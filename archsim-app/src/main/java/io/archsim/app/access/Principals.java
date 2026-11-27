package io.archsim.app.access;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import io.archsim.app.web.ApiException;

/**
 * Who is asking, from what Spring Security's chain put in the security context: the local
 * user without sign-in; with it, the API token ({@code Authorization: Bearer ...}) or the
 * browser session in the cookie, else {@code 401}, saying why. A request is signed in
 * once: its principal is kept with it, for the controller's parameters and for what runs
 * before them.
 */
public final class Principals {

	/** Where a request keeps its principal once signed in. */
	private static final String ATTRIBUTE = Principals.class.getName();

	private final ClientIps clientIps;

	private final SecurityContextHolderStrategy contexts;

	/**
	 * The principals as Spring Security's context has them.
	 * @param clientIps where requests come from
	 * @param contexts where Spring Security keeps the request's context
	 */
	Principals(ClientIps clientIps, SecurityContextHolderStrategy contexts) {
		this.clientIps = clientIps;
		this.contexts = contexts;
	}

	/**
	 * Who is asking.
	 * @param request the request
	 * @return the principal
	 * @throws ApiException {@code 401} when the request signs no one in, saying why
	 */
	public Principal of(HttpServletRequest request) {
		if (request.getAttribute(ATTRIBUTE) instanceof Principal known) {
			return known;
		}
		Principal principal = signIn(request);
		request.setAttribute(ATTRIBUTE, principal);
		return principal;
	}

	private Principal signIn(HttpServletRequest request) {
		String ip = this.clientIps.of(request).orElse(null);
		Authentication authentication = this.contexts.getContext().getAuthentication();
		Object who = (authentication != null) ? authentication.getPrincipal() : null;
		return switch (who) {
			case ApiTokenPrincipal token -> Principal.token(token.userId(), token.grant(), ip);
			case LocalUser _ -> Principal.local(ip);
			case BrowserSession session when session.guest() ->
				Principal.guest(session.userId(), session.sessionId(), ip);
			case BrowserSession session -> Principal.session(session.userId(), session.sessionId(), ip);
			case null, default -> throw notSignedIn(request);
		};
	}

	/**
	 * Why nobody is signed in.
	 * @param request the request
	 * @return the refusal: credentials of another kind than a bearer token, which the
	 * token filter lets through; a session cookie that signs no one in; or nothing at all
	 */
	private static ApiException notSignedIn(HttpServletRequest request) {
		if (BrowserSessions.carriesAuthorization(request)) {
			return ApiException.unauthorized(SignInRefusals.SEND_A_TOKEN);
		}
		if (BrowserSessions.sessionCookie(request).isPresent()) {
			return ApiException.unauthorized(BrowserSessions.refusal(request).orElse(SignInRefusals.SESSION_EXPIRED));
		}
		return ApiException.unauthorized(SignInRefusals.SIGN_IN);
	}

}
