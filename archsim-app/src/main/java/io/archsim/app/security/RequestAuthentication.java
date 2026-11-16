package io.archsim.app.security;

import java.util.Optional;

import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.BrowserCsrf;
import io.archsim.app.access.BrowserSessions;
import io.archsim.app.access.CsrfRefusal;
import io.archsim.app.access.LocalUserFilter;
import io.archsim.app.access.SessionChanges;
import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.signin.OAuthSignIn;
import io.archsim.app.signin.SandboxOffer;
import io.archsim.app.web.ErrorResponses;

/**
 * Who is asking, as the chain puts it in Spring Security's context for each request
 * (ADR-0065): with sign-in, the API token a request carries (the resource server,
 * {@link ApiTokens}) or its browser session ({@link BrowserSessions}, read when the
 * context is first asked for), the CSRF token every change a session makes, and every
 * start of a sandbox ({@link SandboxOffer}), must send back ({@link BrowserCsrf}), and
 * signing in with GitHub or Google, which starts a session (Spring Security's OAuth2
 * login, {@link OAuthSignIn}); without, the local user ({@link LocalUserFilter}) and no
 * CSRF token. A configurer of the chain of its own, so it finds the application's beans
 * as Spring Security's configurers do; the web layer's test slices have no sign-in.
 */
final class RequestAuthentication extends AbstractHttpConfigurer<RequestAuthentication, HttpSecurity> {

	private final ArchsimProperties properties;

	/**
	 * Authentication as the settings have it.
	 * @param properties the settings: whether people sign in, and the public address
	 */
	RequestAuthentication(ArchsimProperties properties) {
		this.properties = properties;
	}

	@Override
	public void init(HttpSecurity http) {
		ApplicationContext context = http.getSharedObject(ApplicationContext.class);
		http.securityContext((security) -> security.securityContextRepository(contextRepository(context)));
		if (!this.properties.hosted()) {
			http.csrf(AbstractHttpConfigurer::disable);
			return;
		}
		ErrorResponses responses = new ErrorResponses(context.getBean(JsonMapper.class));
		http.oauth2ResourceServer(
				new ApiTokens(this.properties, context.getBeanProvider(OpaqueTokenIntrospector.class), responses));
		http.csrf((csrf) -> csrf.csrfTokenRepository(BrowserCsrf.tokens())
			.csrfTokenRequestHandler(BrowserCsrf.headerOnlyHandler())
			.requireCsrfProtectionMatcher(csrfProtected(context)));
		http.exceptionHandling((exceptions) -> exceptions.accessDeniedHandler(new CsrfRefusal(responses)));
		context.getBeanProvider(OAuthSignIn.class).ifAvailable((signIn) -> http.oauth2Login(signIn::configure));
	}

	@Override
	public void configure(HttpSecurity http) {
		if (!this.properties.hosted()) {
			http.addFilterAfter(new LocalUserFilter(getSecurityContextHolderStrategy()),
					SecurityContextHolderFilter.class);
		}
	}

	/**
	 * The requests that must send the browser's CSRF token: changes a browser session
	 * authenticates and, where sandboxes are offered, starts of a sandbox, which set a
	 * session cookie. The web layer's test slices have no sign-in, and so no sandboxes.
	 * @param context the application
	 * @return the matcher
	 */
	private RequestMatcher csrfProtected(ApplicationContext context) {
		RequestMatcher sessionChanges = new SessionChanges(getSecurityContextHolderStrategy());
		SandboxOffer offer = context.getBeanProvider(SandboxOffer.class).getIfAvailable();
		Optional<RequestMatcher> starts = (offer != null) ? offer.starts() : Optional.empty();
		if (starts.isEmpty()) {
			return sessionChanges;
		}
		return new OrRequestMatcher(sessionChanges, starts.get());
	}

	/**
	 * Where a request's context comes from: the request itself, where the token filter
	 * keeps a token's for a dispatch to an error page; then, with sign-in, its browser
	 * session. The web layer's test slices have no database, and so no sessions to read.
	 * @param context the application
	 * @return the repository
	 */
	private SecurityContextRepository contextRepository(ApplicationContext context) {
		SecurityContextRepository request = new RequestAttributeSecurityContextRepository();
		if (!this.properties.hosted()) {
			return request;
		}
		BrowserSessions sessions = context.getBeanProvider(BrowserSessions.class).getIfAvailable();
		return (sessions != null) ? new DelegatingSecurityContextRepository(request, sessions) : request;
	}

}
