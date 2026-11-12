package io.archsim.app.access;

import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ApiException;

/**
 * Gives a controller parameter of type {@link Principal} who is asking, or of type
 * {@link Person} who is asking in person. Without sign-in that is the local user. With
 * it, the API token Spring Security's bearer token filter put in the security context
 * ({@code Authorization: Bearer ...}), or the browser session in the cookie, else
 * {@code 401}; a cookie request that changes something must carry the session's CSRF
 * token too. A request is signed in once, however many parameters ask.
 */
class PrincipalResolver implements HandlerMethodArgumentResolver {

	/** Where a request keeps its principal once signed in. */
	private static final String ATTRIBUTE = PrincipalResolver.class.getName();

	/** The methods that change nothing, so need no CSRF token. */
	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final ArchsimProperties properties;

	private final ClientIps clientIps;

	private final ObjectProvider<Credentials> credentials;

	private final ObjectProvider<Csrf> csrf;

	private final SecurityContextHolderStrategy contexts;

	/**
	 * A resolver whose sign-in beans are looked up when a request first needs them, so
	 * the web layer's test slices, which have none, still start.
	 * @param properties the settings
	 * @param clientIps where requests come from
	 * @param credentials signs sessions in
	 * @param csrf checks the CSRF header
	 */
	PrincipalResolver(ArchsimProperties properties, ClientIps clientIps, ObjectProvider<Credentials> credentials,
			ObjectProvider<Csrf> csrf) {
		this.properties = properties;
		this.clientIps = clientIps;
		this.credentials = credentials;
		this.csrf = csrf;
		this.contexts = SecurityContextHolder.getContextHolderStrategy();
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
		if (!this.properties.hosted()) {
			return Principal.local(ip);
		}
		Authentication authentication = this.contexts.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof ApiTokenPrincipal token) {
			return Principal.token(token.userId(), token.grant(), ip);
		}
		if (StringUtils.hasLength(request.getHeader(HttpHeaders.AUTHORIZATION))) {
			// credentials of another kind, which the bearer token filter lets through
			throw ApiException.unauthorized(SignInRefusals.SEND_A_TOKEN);
		}
		String cookie = sessionCookie(request).orElseThrow(() -> ApiException.unauthorized(SignInRefusals.SIGN_IN));
		Credentials.SignedSession session = this.credentials.getObject().session(cookie, ip);
		if (!SAFE_METHODS.contains(request.getMethod())) {
			this.csrf.getObject().check(request.getHeader(Csrf.HEADER), session.sessionId());
		}
		return session.principal();
	}

	private static Optional<String> sessionCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (Principal.SESSION_COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
				return Optional.of(cookie.getValue());
			}
		}
		return Optional.empty();
	}

}
