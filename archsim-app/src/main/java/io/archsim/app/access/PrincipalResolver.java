package io.archsim.app.access;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import io.archsim.app.web.ApiException;

/**
 * Gives a controller parameter of type {@link Principal} who is asking, or of type
 * {@link Person} who is asking in person, from what Spring Security's chain put in the
 * security context: the local user without sign-in; with it, the API token
 * ({@code Authorization: Bearer ...}) or the browser session in the cookie, else
 * {@code 401}, saying why. A request is signed in once, however many parameters ask.
 */
class PrincipalResolver implements HandlerMethodArgumentResolver {

	/** Where a request keeps its principal once signed in. */
	private static final String ATTRIBUTE = PrincipalResolver.class.getName();

	private final ClientIps clientIps;

	private final SecurityContextHolderStrategy contexts;

	/**
	 * A resolver reading Spring Security's context.
	 * @param clientIps where requests come from
	 * @param contexts where Spring Security keeps the request's context
	 */
	PrincipalResolver(ClientIps clientIps, SecurityContextHolderStrategy contexts) {
		this.clientIps = clientIps;
		this.contexts = contexts;
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == Principal.class || parameter.getParameterType() == Person.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
			NativeWebRequest request, @Nullable WebDataBinderFactory binders) {
		HttpServletRequest servletRequest = request.getNativeRequest(HttpServletRequest.class);
		if (servletRequest == null) {
			throw new IllegalStateException("a principal is only known for a servlet request");
		}
		Principal principal = principal(servletRequest);
		if (parameter.getParameterType() != Person.class) {
			return principal;
		}
		principal.requirePerson();
		return new Person(principal);
	}

	private Principal principal(HttpServletRequest request) {
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
